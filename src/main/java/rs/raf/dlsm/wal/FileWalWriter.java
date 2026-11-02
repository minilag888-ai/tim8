package rs.raf.dlsm.wal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.OptionalLong;
import rs.raf.dlsm.failpoint.Failpoints;
import rs.raf.dlsm.failpoint.InjectedIoException;
import rs.raf.dlsm.io.FileOps;
import rs.raf.dlsm.record.InternalRecord;

/**
 * Upis u jedan aktivni WAL segment (Checkpoint 1 §9.1 koraci 5–7, §16.1). Vlasnik: Katarina.
 *
 * <p>Pravljenje segmenta je mali protokol objavljivanja: zaglavlje u tmp/ → force → atomski rename u wal/ →
 * force direktorijuma wal/. Tek posle toga segment sme da primi zapis.
 *
 * <p>{@link #append}: ceo okvir se kodira u memoriji, upisuje se SVAKI bajt (u petlji, kroz FileOps),
 * pa {@code force}. Svaki izuzetak znači neizvestan kraj — StorageEngine prelazi u FAILED.
 */
public final class FileWalWriter implements WalWriter {

    private final FileOps ops;
    private final long segmentId;
    private final FileChannel ch;
    private final WalStats stats;
    private long size;
    private boolean failed;
    private Long minSequence;
    private Long maxSequence;

    private FileWalWriter(FileOps ops, long segmentId, FileChannel ch, long size, WalStats stats) {
        this.ops = ops;
        this.segmentId = segmentId;
        this.ch = ch;
        this.size = size;
        this.stats = stats;
    }

    /** Pravi i trajno finalizuje nov prazan segment (§16.1), pa ga otvara za dopisivanje. */
    public static FileWalWriter create(FileOps ops, Path walDir, Path tmpDir, long segmentId) throws IOException {
        return create(ops, walDir, tmpDir, segmentId, WalStats.GLOBAL);
    }

    public static FileWalWriter create(FileOps ops, Path walDir, Path tmpDir, long segmentId, WalStats stats)
            throws IOException {
        if (segmentId < 1) {
            throw new IllegalArgumentException("ID segmenta mora biti pozitivan");
        }
        Path tmp = tmpDir.resolve(WalFormat.tmpName(segmentId));
        Path target = walDir.resolve(WalFormat.fileName(segmentId));
        try (FileChannel t = FileChannel.open(tmp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ops.writeFully(t, WalFormat.encodeHeader(segmentId), null);
            ops.force(t, null);
        }
        ops.atomicMove(tmp, target, false, null); // postojeće odredište je greška, nikad se ne prepisuje
        ops.forceDirectory(walDir, null);
        FileChannel ch = FileChannel.open(target, StandardOpenOption.WRITE);
        long size = ch.size();
        ch.position(size);
        return new FileWalWriter(ops, segmentId, ch, size, stats);
    }

    @Override
    public synchronized void append(InternalRecord record) throws IOException {
        if (failed) {
            throw new IOException("WAL segment " + segmentId + " ima neizvestan kraj; dopisivanje je zabranjeno");
        }
        ByteBuffer frame = WalFormat.encodeFrame(record);
        int frameLength = frame.remaining();
        Map<String, Object> ctx = Map.of("sequence", record.sequence(), "walSegmentId", segmentId);
        try {
            stats.appendAttempts.incrementAndGet();
            int prefix = prefixBytes(ctx, frameLength);
            if (prefix > 0) {
                // wal.after-record-prefix: upiši tačno prefiks, pa stani na failpoint-u (§26.1).
                ByteBuffer first = frame.duplicate().limit(prefix);
                ops.writeFully(ch, first, "wal.before-append");
                stats.bytesWritten.addAndGet(prefix);
                try {
                    Failpoints.hit("wal.after-record-prefix", Map.of("sequence", record.sequence(),
                            "walSegmentId", segmentId, "prefixBytes", prefix, "frameLength", frameLength));
                } catch (InjectedIoException e) {
                    throw e.getCause();
                }
                ByteBuffer rest = frame.duplicate().position(prefix);
                ops.writeFully(ch, rest, null);
                stats.bytesWritten.addAndGet(frameLength - prefix);
            } else {
                ops.writeFully(ch, frame, "wal.before-append");
                stats.bytesWritten.addAndGet(frameLength);
                // Pogodak se broji i kad pravilo još ne okida (afterHits), da bi brojanje bilo determinističko.
                Failpoints.hit("wal.after-record-prefix", ctx);
            }
            stats.appendsCompleted.incrementAndGet();

            stats.forceAttempts.incrementAndGet();
            ops.force(ch, "wal.after-append.before-sync"); // GRANICA TRAJNOSTI
            stats.forcesSucceeded.incrementAndGet();
            size += frameLength;
            if (minSequence == null) {
                minSequence = record.sequence();
            }
            maxSequence = record.sequence();
        } catch (IOException | RuntimeException e) {
            failed = true;
            throw e;
        }
        // Zapis je trajan, ali još nije u MemTable.
        Failpoints.hit("wal.after-sync.before-memtable", ctx);
    }

    private static int prefixBytes(Map<String, Object> ctx, int frameLength) {
        if (!Failpoints.wouldTrigger("wal.after-record-prefix", ctx)) {
            return 0;
        }
        OptionalLong p = Failpoints.parameter("wal.after-record-prefix", "prefixBytes", ctx);
        long n = p.isPresent() ? p.getAsLong() : frameLength / 2;
        // prefixBytes mora biti 1..frameLength-1 da bi ostao STROGI prefiks.
        return (int) Math.max(1, Math.min(n, frameLength - 1));
    }

    @Override
    public long segmentId() {
        return segmentId;
    }

    @Override
    public synchronized long sizeBytes() {
        return size;
    }

    /** Najmanji redni broj u segmentu ili null za prazan segment. */
    public synchronized Long minSequence() {
        return minSequence;
    }

    /** Najveći redni broj u segmentu ili null za prazan segment. */
    public synchronized Long maxSequence() {
        return maxSequence;
    }

    @Override
    public synchronized void close() throws IOException {
        ch.close();
    }
}
