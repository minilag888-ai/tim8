package rs.raf.dlsm.recovery;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import rs.raf.dlsm.engine.Lifecycle;
import rs.raf.dlsm.engine.NodeState;
import rs.raf.dlsm.engine.StorageEngine;
import rs.raf.dlsm.failpoint.Failpoints;
import rs.raf.dlsm.io.FileOps;
import rs.raf.dlsm.log.Log;
import rs.raf.dlsm.maintenance.OrphanCleaner;
import rs.raf.dlsm.manifest.FileManifestStore;
import rs.raf.dlsm.manifest.Manifest;
import rs.raf.dlsm.manifest.TableIdAllocator;
import rs.raf.dlsm.memtable.MemTable;
import rs.raf.dlsm.node.NodeDir;
import rs.raf.dlsm.record.InternalRecord;
import rs.raf.dlsm.sstable.FileSSTableReader;
import rs.raf.dlsm.sstable.SSTableCorruptionException;
import rs.raf.dlsm.sstable.SSTableMeta;
import rs.raf.dlsm.sstable.SSTableReader;
import rs.raf.dlsm.wal.FileWalReader;
import rs.raf.dlsm.wal.FileWalWriter;
import rs.raf.dlsm.wal.WalFormat;
import rs.raf.dlsm.wal.WalRegistry;
import rs.raf.dlsm.wal.WalScanResult;
import rs.raf.dlsm.wal.WalStats;

/**
 * Oporavak pri pokretanju, obavezni algoritam iz Checkpoint 1 §21.1. Vlasnik: Katarina.
 *
 * <ol>
 *   <li>Isključivo vlasništvo nad direktorijumom (LOCK) i provera/inicijalizacija node.json.</li>
 *   <li>Učitavanje manifesta; početni prazan manifest samo za dokazano novu bazu.</li>
 *   <li>Strimovana validacija svake SSTable koju manifest referencira i poređenje sa manifestom.</li>
 *   <li>Dodela sledećih ID-jeva (tabele i WAL) PRE čišćenja, pa brisanje privremenih i orphan fajlova.</li>
 *   <li>Čitanje WAL segmenata po rastućem ID-ju; odsecanje SAMO nepotpunog poslednjeg okvira (+ force).</li>
 *   <li>Replay zapisa iznad flushedThroughSequence u jednu oporavljenu nepromenljivu MemTable, bez dopisivanja.</li>
 *   <li>Sledeći redni broj = max(flushedThroughSequence, najveći važeći WAL redni broj) + 1.</li>
 *   <li>Nov aktivni WAL (nikad se ne nastavlja stari segment), brisanje praznih i pokrivenih segmenata.</li>
 *   <li>Mehanizam se pravi tek kad je sve validno; READY postavlja Main.</li>
 * </ol>
 * Svaki korak je bezbedan za ponavljanje posle novog pada (I6). Svaka greška je {@link RecoveryException}
 * sa stabilnom kategorijom; čvor tada ostaje FAILED i ne opslužuje KV saobraćaj (I7).
 */
public final class Recovery {

    /** Sve što Main i održavanje dobijaju posle uspešnog oporavka. */
    public record Result(NodeDir dir, FileManifestStore manifests, List<SSTableReader> tables,
                         TableIdAllocator tableIds, WalRegistry wals, StorageEngine engine,
                         MemTable recoveredImmutable) {
    }

    private Recovery() {
    }

    public static Result run(StorageEngine.Config config, NodeState state, FileOps ops, Path root)
            throws RecoveryException {
        state.transition(Lifecycle.RECOVERING);
        NodeDir dir;
        try {
            dir = NodeDir.open(ops, root, state.nodeId());
        } catch (IOException e) {
            throw new RecoveryException(e.getMessage().contains("LOCK") ? "DIRECTORY_LOCKED" : "NODE_IDENTITY",
                    e.getMessage(), e);
        }
        List<SSTableReader> readers = new ArrayList<>();
        try {
            return recover(config, state, ops, dir, readers);
        } catch (RecoveryException | RuntimeException e) {
            closeAll(readers);
            closeQuietly(dir);
            throw e;
        }
    }

    private static Result recover(StorageEngine.Config config, NodeState state, FileOps ops, NodeDir dir,
                                  List<SSTableReader> readers) throws RecoveryException {
        // ---- korak 3: manifest
        FileManifestStore manifests = new FileManifestStore(ops, dir.lsmDir(), dir.tmpDir());
        Manifest manifest;
        try {
            if (manifests.exists()) {
                manifest = manifests.load();
            } else if (hasEntries(dir.walDir()) || hasEntries(dir.sstDir())) {
                throw new RecoveryException("MANIFEST_MISSING",
                        "MANIFEST.json ne postoji, a WAL/SSTable fajlovi postoje; baza se ne pravi ponovo kao prazna");
            } else {
                manifest = manifests.createInitial();
                Log.info("recovery.manifest.initialized");
            }
        } catch (IOException e) {
            throw new RecoveryException("MANIFEST_INVALID", e.getMessage(), e);
        }
        Log.info("recovery.manifest.loaded", "manifestGeneration", manifest.generation(),
                "flushedThroughSequence", manifest.flushedThroughSequence());
        Failpoints.hit("recovery.after-manifest-load", Map.of("manifestGeneration", manifest.generation()));

        // ---- korak 4: validacija svake referencirane SSTable
        for (SSTableMeta expected : manifest.sstables()) {
            Path file = dir.sstDir().resolve(expected.file());
            if (!Files.exists(file)) {
                throw new RecoveryException("REFERENCED_FILE_MISSING",
                        "Manifest referencira tabelu " + expected.tableId() + " (" + expected.file() + ") koja ne postoji");
            }
            FileSSTableReader r;
            try {
                r = FileSSTableReader.open(file, expected.tableId());
                readers.add(r);
                r.validateFully();
            } catch (SSTableCorruptionException e) {
                throw new RecoveryException(e.category(), "Tabela " + expected.tableId() + ": " + e.getMessage(), e);
            } catch (IOException e) {
                throw new RecoveryException("SSTABLE_INVALID", "Tabela " + expected.tableId() + ": " + e.getMessage(), e);
            }
            SSTableMeta actual = r.meta();
            if (!actual.equals(expected)) {
                throw new RecoveryException("MANIFEST_TABLE_MISMATCH",
                        "Metapodaci tabele " + expected.tableId() + " u manifestu " + expected + " ne odgovaraju fajlu " + actual);
            }
        }

        // ---- korak 5–6: ID alokatori pre čišćenja, pa čišćenje
        TableIdAllocator tableIds;
        WalRegistry wals;
        try {
            tableIds = TableIdAllocator.fromDisk(manifest.nextTableId(), dir.sstDir(), dir.tmpDir());
            wals = new WalRegistry(ops, dir.walDir(), dir.tmpDir());
            OrphanCleaner.clean(ops, dir, manifest);
        } catch (IOException e) {
            throw new RecoveryException("CLEANUP_FAILED", e.getMessage(), e);
        }

        // ---- koraci 7–10: WAL segmenti redom, odsecanje, replay
        long flushed = manifest.flushedThroughSequence();
        List<Path> segments = walSegments(dir.walDir());
        FileWalReader reader = new FileWalReader();
        long lastSeq = 0;
        long maxWalSeq = 0;
        MemTable recovered = new MemTable(1, List.of());
        Set<Long> replayedSegments = new HashSet<>();
        List<Long> replayedSegmentIds = new ArrayList<>();
        for (Path seg : segments) {
            WalScanResult res;
            try {
                res = reader.scan(seg);
            } catch (IOException e) {
                throw new RecoveryException("WAL_IO", seg.getFileName() + ": " + e.getMessage(), e);
            }
            if (res.status() == WalScanResult.Status.CORRUPT) {
                throw new RecoveryException("WAL_CORRUPT", "Segment " + res.segmentId() + ", " + res.error());
            }
            long size = res.fileSize();
            if (res.status() == WalScanResult.Status.TORN_TAIL) {
                try (FileChannel ch = FileChannel.open(seg, StandardOpenOption.WRITE)) {
                    ch.truncate(res.validEndOffset());
                    ops.force(ch, null);
                } catch (IOException e) {
                    throw new RecoveryException("WAL_TRUNCATE_FAILED", e.getMessage(), e);
                }
                size = res.validEndOffset();
                WalStats.GLOBAL.tornTailsTruncated.incrementAndGet();
                Log.warn("recovery.wal.torn-tail-truncated", "walSegmentId", res.segmentId(),
                        "offset", res.validEndOffset(), "eof", res.fileSize());
                Failpoints.hit("recovery.after-wal-tail-truncate", Map.of("walSegmentId", res.segmentId()));
            }
            Long min = null;
            Long max = null;
            for (InternalRecord r : res.records()) {
                if (r.sequence() <= lastSeq) {
                    throw new RecoveryException("WAL_SEQUENCE", "Segment " + res.segmentId() + ": redni broj "
                            + r.sequence() + " nije veći od prethodnog " + lastSeq + " (preko granice segmenata)");
                }
                lastSeq = r.sequence();
                maxWalSeq = Math.max(maxWalSeq, r.sequence());
                if (min == null) {
                    min = r.sequence();
                }
                max = r.sequence();
                if (r.sequence() > flushed) {
                    recovered.put(r); // pravilo najvećeg rednog broja; nema dopisivanja u WAL
                    WalStats.GLOBAL.recordsReplayed.incrementAndGet();
                    if (replayedSegments.add(res.segmentId())) {
                        replayedSegmentIds.add(res.segmentId());
                    }
                }
            }
            wals.registerExisting(new WalRegistry.SegmentInfo(res.segmentId(), min, max, size));
        }
        recovered.setWalSegmentIds(replayedSegmentIds);
        long nextSequence = Math.max(flushed, maxWalSeq) + 1;

        // ---- koraci 12–13: nov aktivni WAL, brisanje praznih i pokrivenih segmenata
        FileWalWriter active;
        try {
            active = wals.createSegment();
            wals.deleteCovered(flushed, active.segmentId(), replayedSegments);
        } catch (IOException e) {
            throw new RecoveryException("WAL_CREATE_FAILED", e.getMessage(), e);
        }
        Log.info("recovery.wal.replayed", "records", recovered.entryCount(), "nextSequence", nextSequence,
                "activeWalSegmentId", active.segmentId());
        Failpoints.hit("recovery.after-wal-replay.before-ready", Map.of("nextSequence", nextSequence));

        // ---- korak 14: strukture za čitanje i mehanizam
        MemTable immutable = recovered.isEmpty() ? null : recovered;
        StorageEngine engine = new StorageEngine(config, state, active, wals::createSegment,
                new StorageEngine.InitialState(nextSequence, immutable, readers));
        return new Result(dir, manifests, List.copyOf(readers), tableIds, wals, engine, immutable);
    }

    private static List<Path> walSegments(Path walDir) throws RecoveryException {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(walDir)) {
            s.filter(p -> {
                String n = p.getFileName().toString();
                return n.endsWith(".log") && WalFormat.parseSegmentId(n) > 0;
            }).forEach(out::add);
        } catch (IOException e) {
            throw new RecoveryException("WAL_IO", e.getMessage(), e);
        }
        // Redosled po ID-ju segmenta, nikad po listanju direktorijuma ili vremenu izmene.
        out.sort((a, b) -> Long.compare(WalFormat.parseSegmentId(a.getFileName().toString()),
                WalFormat.parseSegmentId(b.getFileName().toString())));
        return out;
    }

    private static boolean hasEntries(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return false;
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.findAny().isPresent();
        }
    }

    private static void closeAll(List<SSTableReader> readers) {
        for (SSTableReader r : readers) {
            try {
                r.close();
            } catch (IOException ignored) {
                // zatvaranje posle neuspeha
            }
        }
    }

    private static void closeQuietly(NodeDir dir) {
        try {
            dir.close();
        } catch (IOException ignored) {
            // zatvaranje posle neuspeha
        }
    }
}
