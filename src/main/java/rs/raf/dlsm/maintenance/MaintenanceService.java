package rs.raf.dlsm.maintenance;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import rs.raf.dlsm.engine.EngineException;
import rs.raf.dlsm.engine.StorageEngine;
import rs.raf.dlsm.failpoint.Failpoints;
import rs.raf.dlsm.io.FileOps;
import rs.raf.dlsm.log.Log;
import rs.raf.dlsm.manifest.DirectorySyncUncertainException;
import rs.raf.dlsm.manifest.FileManifestStore;
import rs.raf.dlsm.manifest.Manifest;
import rs.raf.dlsm.manifest.TableIdAllocator;
import rs.raf.dlsm.memtable.MemTable;
import rs.raf.dlsm.merge.MergingIterator;
import rs.raf.dlsm.node.NodeDir;
import rs.raf.dlsm.record.InternalRecord;
import rs.raf.dlsm.sstable.FileSSTableReader;
import rs.raf.dlsm.sstable.FileSSTableWriter;
import rs.raf.dlsm.sstable.SSTableFiles;
import rs.raf.dlsm.sstable.SSTableMeta;
import rs.raf.dlsm.sstable.SSTableReader;
import rs.raf.dlsm.wal.WalRegistry;

/**
 * Flush i compaction (Checkpoint 1 §19, §20, §23). Vlasnik: Ivona.
 *
 * <p>Osnovni dizajn (RECOMMENDED BASELINE): JEDAN radnik ({@code single-thread executor}) izvršava sve
 * poslove održavanja, pa se flush i compaction nikad ne preklapaju i objavljivanje manifesta je
 * serijalizovano. Ceo posao drži {@code maintenanceLock}; skupa izgradnja fajla ide BEZ engineLock-a,
 * pa obični upisi nastavljaju u aktivnoj generaciji.
 *
 * <p>Flush je prenos odgovornosti: pre objavljivanja manifesta mutacije štiti stari WAL, posle objavljivanja
 * referencirana SSTable. Stari WAL se briše tek kada je novi manifest trajan.
 */
public final class MaintenanceService implements AutoCloseable {

    private final StorageEngine engine;
    private final NodeDir dir;
    private final FileManifestStore manifests;
    private final TableIdAllocator tableIds;
    private final WalRegistry wals;
    private final FileOps ops;
    private final MaintenanceStats stats = MaintenanceStats.GLOBAL;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "maintenance");
        t.setDaemon(true);
        return t;
    });

    /** Objavljeni čitači; menja ih samo radnik održavanja, uvek uređeno po ID-ju tabele. */
    private List<SSTableReader> readers;
    private boolean autoCompactionQueued;

    // Polja za status (§24 "maintenance").
    private volatile String operation = "NONE";
    private volatile String phase = "IDLE";
    private volatile Long targetSequence;
    private volatile List<Long> inputTableIds = List.of();

    public MaintenanceService(StorageEngine engine, NodeDir dir, FileManifestStore manifests,
                              TableIdAllocator tableIds, WalRegistry wals, List<SSTableReader> readers, FileOps ops) {
        this.engine = engine;
        this.dir = dir;
        this.manifests = manifests;
        this.tableIds = tableIds;
        this.wals = wals;
        this.ops = ops;
        this.readers = new ArrayList<>(readers);
    }

    /** Povezuje se sa rotacijom; oporavljena nepromenljiva tabela se odmah stavlja u red za flush. */
    public void start() {
        engine.setRotationListener(m -> worker.submit(() -> backgroundFlush(m)));
        worker.submit(this::maybeQueueAutoCompaction);
    }

    // ================================================================ flush

    private void backgroundFlush(MemTable m) {
        try {
            // Generacija čiji je flush upravo neuspešno završen ne pokušava se ponovo automatski u istoj
            // seriji: ostaje zaštićena WAL-om dok je ne preuzme sledeći ručni flush ili oporavak (DESIGN.md).
            if (engine.immutable() == m && m != lastFailedFlush) {
                flushImmutable(m);
            }
        } catch (Exception e) {
            Log.error("flush.failed", "code", "STORAGE_IO", "message", e.getMessage());
        } finally {
            idle();
        }
    }

    /**
     * Ručni flush (§23.1): uzima ciljni redni broj jednom i radi FIFO flush dok ga manifest ne pokrije.
     * Čeka najviše do roka; posao koji je prihvaćen nastavlja i posle isteka roka (504).
     */
    public FlushResult manualFlush(long deadlineNanos) throws EngineException {
        return await(worker.submit(() -> {
            try {
                return doManualFlush();
            } finally {
                idle();
            }
        }), deadlineNanos);
    }

    private FlushResult doManualFlush() throws Exception {
        long target = engine.highestSequence();
        operation = "FLUSH";
        phase = "CAPTURE";
        targetSequence = target;
        List<Long> published = new ArrayList<>();
        Manifest cur = manifests.current();
        while (cur.flushedThroughSequence() < target) {
            MemTable m = engine.rotateForFlush();
            if (m == null) {
                break; // nema više zapisa u memoriji (npr. sve je bilo tombstone pokriveno)
            }
            published.add(flushImmutable(m));
            cur = manifests.current();
        }
        if (published.isEmpty()) {
            return new FlushResult("NO_OP", target, List.of(), cur.generation(), cur.flushedThroughSequence());
        }
        return new FlushResult("PUBLISHED", target, published, cur.generation(), cur.flushedThroughSequence());
    }

    private volatile MemTable lastFailedFlush;

    /** 13 koraka iz §19.1 za jednu nepromenljivu generaciju. Vraća ID objavljene tabele. */
    private long flushImmutable(MemTable m) throws Exception {
        try {
            long id = flushImmutableOnce(m);
            lastFailedFlush = null;
            return id;
        } catch (Exception e) {
            lastFailedFlush = m;
            throw e;
        }
    }

    private long flushImmutableOnce(MemTable m) throws Exception {
        engine.maintenanceLock().lock();
        try {
            requireNotFailed();
            stats.flushAttempts.incrementAndGet();
            operation = "FLUSH";
            phase = "CAPTURE";
            long id = tableIds.allocate();                                    // korak 3
            Path tmp = dir.tmpDir().resolve(SSTableFiles.tmpName(id));
            phase = "WRITE_OUTPUT";
            SSTableMeta meta = new FileSSTableWriter(ops).build(id,           // koraci 4–5
                    m.iterator(null, null), tmp);
            phase = "SYNC_OUTPUT";
            Path fin = SSTableFiles.finalizeTable(ops, tmp, dir.sstDir(), id); // koraci 6–8
            phase = "FINALIZE_OUTPUT";
            Failpoints.hit("flush.after-sstable-finalize.before-manifest", Map.of("tableId", id));

            phase = "PUBLISH_MANIFEST";                                       // koraci 9–10
            Manifest cur = manifests.current();
            List<SSTableMeta> tables = new ArrayList<>(cur.sstables());
            tables.add(meta);
            Manifest next = new Manifest(Manifest.FORMAT_VERSION, cur.generation() + 1,
                    Math.max(cur.nextTableId(), tableIds.peekNext()),
                    Math.max(cur.flushedThroughSequence(), m.maxSequence()), tables);
            FileSSTableReader reader = FileSSTableReader.open(fin, id);
            publishOrFail(next, fin, reader);

            List<SSTableReader> newReaders = new ArrayList<>(readers);       // korak 11
            newReaders.add(reader);
            newReaders.sort(Comparator.comparingLong(r -> r.meta().tableId()));
            readers = newReaders;
            engine.completeFlush(m, newReaders);
            stats.flushPublications.incrementAndGet();
            Log.info("flush.published", "tableId", id, "manifestGeneration", next.generation(),
                    "flushedThroughSequence", next.flushedThroughSequence());

            phase = "RETIRE_INPUTS";
            Failpoints.hit("flush.after-manifest-publish.before-wal-delete", Map.of("tableId", id));
            phase = "CLEANUP";                                                // koraci 12–13
            Set<Long> protect = new HashSet<>();
            MemTable pending = engine.immutable();
            if (pending != null) {
                protect.addAll(pending.walSegmentIds());
            }
            wals.deleteCovered(next.flushedThroughSequence(), engine.activeWalSegmentId(), protect);
            maybeQueueAutoCompaction();
            return id;
        } finally {
            engine.maintenanceLock().unlock();
        }
    }

    /**
     * Objavljuje manifest. Greška PRE preimenovanja: stari manifest ostaje merodavan, izlaz se uklanja.
     * Greška POSLE preimenovanja (force direktorijuma): mehanizam ide u FAILED, ništa se ne briše.
     */
    private void publishOrFail(Manifest next, Path output, SSTableReader reader) throws IOException {
        try {
            manifests.publish(next);
        } catch (DirectorySyncUncertainException e) {
            reader.close();
            engine.fail("STORAGE_IO", "Trajnost zamene manifesta je neizvesna; potreban je restart");
            throw e;
        } catch (IOException e) {
            reader.close();
            ops.delete(output); // finalizovan izlaz koji nijedan manifest ne referencira
            ops.delete(dir.tmpDir().resolve("MANIFEST-" + next.generation() + ".tmp"));
            throw e;
        }
    }

    // ================================================================ compaction

    /** Ručni compaction (§23.2): sažima sve trenutno objavljene tabele (najmanje 2). */
    public CompactionResult manualCompaction(long deadlineNanos) throws EngineException {
        return await(worker.submit(() -> {
            try {
                return compact(2);
            } finally {
                idle();
            }
        }), deadlineNanos);
    }

    /** Posle oporavka i posle svakog objavljivanja: ako ima &gt;= 4 tabele, jedan compaction u red. */
    private void maybeQueueAutoCompaction() {
        if (!autoCompactionQueued && manifests.current() != null && manifests.current().sstables().size() >= 4) {
            autoCompactionQueued = true;
            worker.submit(() -> {
                autoCompactionQueued = false;
                try {
                    compact(4);
                } catch (Exception e) {
                    Log.error("compaction.failed", "code", "STORAGE_IO", "message", e.getMessage());
                } finally {
                    idle();
                }
            });
        }
    }

    private CompactionResult compact(int minInputs) throws Exception {
        engine.maintenanceLock().lock();
        try {
            requireNotFailed();
            Manifest base = manifests.current();
            List<SSTableReader> inputs = new ArrayList<>(readers);
            List<Long> ids = new ArrayList<>();
            for (SSTableReader r : inputs) {
                ids.add(r.meta().tableId());
            }
            if (inputs.size() < minInputs) {
                return new CompactionResult("NO_OP", ids, null, base.generation());
            }
            stats.compactionAttempts.incrementAndGet();
            operation = "COMPACTION";
            phase = "CAPTURE";
            inputTableIds = List.copyOf(ids);
            Failpoints.hit("compaction.after-selection.before-output", Map.of());

            long id = tableIds.allocate();
            Path tmp = dir.tmpDir().resolve(SSTableFiles.tmpName(id));
            phase = "WRITE_OUTPUT";
            List<Iterator<InternalRecord>> sources = new ArrayList<>();
            for (SSTableReader r : inputs) {
                sources.add(r.iterator(null));
            }
            // Najveći redni broj po ključu; tombstone-i se ZADRŽAVAJU (§20.2).
            SSTableMeta meta = new FileSSTableWriter(ops).build(id, new MergingIterator(sources), tmp);
            phase = "SYNC_OUTPUT";
            Path fin = SSTableFiles.finalizeTable(ops, tmp, dir.sstDir(), id);
            phase = "FINALIZE_OUTPUT";
            Failpoints.hit("compaction.after-output-finalize.before-manifest", Map.of("tableId", id));

            phase = "PUBLISH_MANIFEST";
            Manifest cur = manifests.current(); // ponovna provera nad NAJNOVIJIM manifestom
            Set<Long> selected = new HashSet<>(ids);
            int stillPresent = 0;
            List<SSTableMeta> next = new ArrayList<>();
            for (SSTableMeta t : cur.sstables()) {
                if (selected.contains(t.tableId())) {
                    stillPresent++;
                } else {
                    next.add(t); // nepovezana tabela (npr. konkurentni flush) se čuva
                }
            }
            if (stillPresent != selected.size()) {
                ops.delete(fin); // uslovi zamene su se promenili: izlaz je orphan, ne objavljuje se
                throw new IllegalStateException("Izabrani ulazi više nisu u manifestu");
            }
            next.add(meta);
            next.sort(Comparator.comparingLong(SSTableMeta::tableId));
            Manifest nextManifest = new Manifest(Manifest.FORMAT_VERSION, cur.generation() + 1,
                    Math.max(cur.nextTableId(), tableIds.peekNext()), cur.flushedThroughSequence(), next);
            FileSSTableReader out = FileSSTableReader.open(fin, id);
            publishOrFail(nextManifest, fin, out);

            List<SSTableReader> newReaders = new ArrayList<>();
            for (SSTableReader r : readers) {
                if (!selected.contains(r.meta().tableId())) {
                    newReaders.add(r);
                }
            }
            newReaders.add(out);
            newReaders.sort(Comparator.comparingLong(r -> r.meta().tableId()));
            readers = newReaders;
            engine.replaceTables(newReaders); // posle ovoga nijedan čitalac ne koristi stare ulaze
            stats.compactionPublications.incrementAndGet();
            Log.info("compaction.published", "tableId", id, "inputs", ids.toString(),
                    "manifestGeneration", nextManifest.generation());

            phase = "RETIRE_INPUTS";
            Failpoints.hit("compaction.after-manifest-publish.before-input-delete", Map.of("tableId", id));
            phase = "CLEANUP";
            for (SSTableReader r : inputs) {
                r.close();
                ops.delete(dir.sstDir().resolve(r.meta().file()));
            }
            ops.forceDirectory(dir.sstDir(), null);
            maybeQueueAutoCompaction();
            return new CompactionResult("PUBLISHED", ids, id, nextManifest.generation());
        } finally {
            engine.maintenanceLock().unlock();
        }
    }

    // ================================================================ pomoćno i status

    /**
     * Posle fatalne greške (npr. neizvesna sinhronizacija direktorijuma manifesta) održavanje više ne sme da
     * objavljuje niti briše fajlove: merodavno stanje utvrđuje tek sledeći oporavak.
     */
    private void requireNotFailed() throws IOException {
        if (engine.mode() == rs.raf.dlsm.engine.EngineMode.FAILED) {
            throw new IOException("Mehanizam je u stanju FAILED; održavanje je zaustavljeno do restarta");
        }
    }

    private <T> T await(Future<T> f, long deadlineNanos) throws EngineException {
        try {
            long remaining = deadlineNanos - System.nanoTime();
            return f.get(Math.max(1, remaining), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            throw EngineException.deadline("Održavanje nije završeno pre isteka roka; proveri status");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw EngineException.deadline("Prekinuto");
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof EngineException ee) {
                throw ee;
            }
            throw EngineException.storageIo("Održavanje nije uspelo: " + c.getMessage(), c);
        }
    }

    private void idle() {
        operation = "NONE";
        phase = "IDLE";
        targetSequence = null;
        inputTableIds = List.of();
    }

    /** Objekat "maintenance" za status (§24). */
    public Map<String, Object> statusFields() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("operation", operation);
        m.put("phase", phase);
        m.put("targetSequence", "FLUSH".equals(operation) ? targetSequence : null);
        m.put("inputTableIds", "COMPACTION".equals(operation) ? inputTableIds : List.of());
        return m;
    }

    public boolean idleNow() {
        return "NONE".equals(operation);
    }

    public FileManifestStore manifests() {
        return manifests;
    }

    public WalRegistry wals() {
        return wals;
    }

    public NodeDir dir() {
        return dir;
    }

    @Override
    public void close() {
        worker.shutdownNow();
    }

    /** Za testove: da li postoji fajl tabele. */
    static boolean exists(Path p) {
        return Files.exists(p);
    }
}
