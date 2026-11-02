package rs.raf.dlsm.manifest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import rs.raf.dlsm.sstable.SSTableFiles;

/**
 * Jedan sinhronizovan alokator ID-jeva tabela za flush i compaction (Checkpoint 1 §18.3).
 * Pri pokretanju: next = max(manifest.nextTableId, 1 + najveći ispravan ID u sst/ ili tmp/).
 * Preskočeni ID-jevi su bezopasni; ponovna upotreba nije dozvoljena.
 */
public final class TableIdAllocator {

    private long next;

    public TableIdAllocator(long next) {
        if (next < 1) {
            throw new IllegalArgumentException("nextTableId mora biti >= 1");
        }
        this.next = next;
    }

    public static TableIdAllocator fromDisk(long manifestNextTableId, Path sstDir, Path tmpDir) throws IOException {
        long maxSeen = 0;
        for (Path d : new Path[]{sstDir, tmpDir}) {
            if (!Files.isDirectory(d)) {
                continue;
            }
            try (Stream<Path> s = Files.list(d)) {
                for (Path p : (Iterable<Path>) s::iterator) {
                    maxSeen = Math.max(maxSeen, SSTableFiles.parseTableId(p.getFileName().toString()));
                }
            }
        }
        return new TableIdAllocator(Math.max(manifestNextTableId, maxSeen + 1));
    }

    public synchronized long allocate() {
        if (next == Long.MAX_VALUE) {
            throw new IllegalStateException("ID-jevi tabela su iscrpljeni");
        }
        return next++;
    }

    /** Vrednost koja ide u manifest kao nextTableId (veća od svakog već dodeljenog ID-ja). */
    public synchronized long peekNext() {
        return next;
    }
}
