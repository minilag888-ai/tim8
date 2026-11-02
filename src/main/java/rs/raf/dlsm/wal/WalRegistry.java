package rs.raf.dlsm.wal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import rs.raf.dlsm.io.FileOps;
import rs.raf.dlsm.log.Log;

/**
 * Evidencija WAL segmenata jednog čvora: dodela ID-jeva, pravljenje novih segmenata, opseg rednih brojeva
 * za status i brisanje segmenata pokrivenih manifestom (Checkpoint 1 §16.1, §19.1 koraci 12–13). Vlasnik: Katarina.
 *
 * <p>Alokator: posle pokretanja sledeći ID je max(1, 1 + najveći ispravan WAL ID u wal/ ili tmp/).
 * WAL i SSTable ID-jevi su odvojeni prostori imena.
 */
public final class WalRegistry {

    /** Podaci o jednom segmentu za status i brisanje. */
    public record SegmentInfo(long segmentId, Long minSequence, Long maxSequence, long sizeBytes) {
    }

    private final FileOps ops;
    private final Path walDir;
    private final Path tmpDir;
    private final Map<Long, Object> segments = new TreeMap<>(); // FileWalWriter ili SegmentInfo
    private long nextId;

    public WalRegistry(FileOps ops, Path walDir, Path tmpDir) throws IOException {
        this.ops = ops;
        this.walDir = walDir;
        this.tmpDir = tmpDir;
        long max = 0;
        for (Path d : new Path[]{walDir, tmpDir}) {
            if (!Files.isDirectory(d)) {
                continue;
            }
            try (Stream<Path> s = Files.list(d)) {
                for (Path p : (Iterable<Path>) s::iterator) {
                    max = Math.max(max, WalFormat.parseSegmentId(p.getFileName().toString()));
                }
            }
        }
        this.nextId = Math.max(1, max + 1);
    }

    /** Segment pronađen pri oporavku (posle eventualnog odsecanja kraja). */
    public synchronized void registerExisting(SegmentInfo info) {
        segments.put(info.segmentId(), info);
    }

    /** Pravi i trajno finalizuje nov prazan segment (koristi ga oporavak i rotacija). */
    public synchronized FileWalWriter createSegment() throws IOException {
        long id = nextId++;
        FileWalWriter w = FileWalWriter.create(ops, walDir, tmpDir, id);
        segments.put(id, w);
        Log.info("wal.segment.created", "walSegmentId", id);
        return w;
    }

    public synchronized List<SegmentInfo> snapshot() {
        List<SegmentInfo> out = new ArrayList<>();
        for (Object o : segments.values()) {
            out.add(info(o));
        }
        return out;
    }

    private static SegmentInfo info(Object o) {
        if (o instanceof FileWalWriter w) {
            return new SegmentInfo(w.segmentId(), w.minSequence(), w.maxSequence(), w.sizeBytes());
        }
        return (SegmentInfo) o;
    }

    /**
     * Briše segmente (osim aktivnog i zaštićenih) koji su prazni ili čiji je najveći redni broj
     * &lt;= flushedThroughSequence, pa radi force direktorijuma wal/. Vraća broj obrisanih.
     */
    public synchronized int deleteCovered(long flushedThroughSequence, long activeSegmentId, Set<Long> protectedIds)
            throws IOException {
        List<Long> victims = new ArrayList<>();
        for (Map.Entry<Long, Object> e : segments.entrySet()) {
            long id = e.getKey();
            if (id == activeSegmentId || protectedIds.contains(id)) {
                continue;
            }
            SegmentInfo i = info(e.getValue());
            if (i.maxSequence() == null || i.maxSequence() <= flushedThroughSequence) {
                victims.add(id);
            }
        }
        for (long id : victims) {
            Object o = segments.remove(id);
            if (o instanceof FileWalWriter w) {
                w.close();
            }
            ops.delete(walDir.resolve(WalFormat.fileName(id)));
            Log.info("wal.segment.deleted", "walSegmentId", id, "flushedThroughSequence", flushedThroughSequence);
        }
        if (!victims.isEmpty()) {
            ops.forceDirectory(walDir, null);
        }
        return victims.size();
    }

    public Path walDir() {
        return walDir;
    }
}
