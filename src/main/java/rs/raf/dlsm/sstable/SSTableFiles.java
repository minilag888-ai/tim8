package rs.raf.dlsm.sstable;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import rs.raf.dlsm.io.FileOps;

/**
 * Finalizacija izlaza flush-a ili compaction-a (Checkpoint 1 §19.1 koraci 6–8, §20.3 koraci 3–5).
 * Posle uspeha fajl je "finalizovan": trajan i sa konačnim imenom, ali još NIJE deo baze dok ga
 * manifest ne referencira (Ivona).
 */
public final class SSTableFiles {

    private SSTableFiles() {
    }

    /** Ime privremenog fajla u tmp/: sst-&lt;20 cifara&gt;.sst.tmp */
    public static String tmpName(long tableId) {
        return SSTableMeta.fileName(tableId) + ".tmp";
    }

    /** Vraća ID iz imena sst-&lt;20 cifara&gt;.sst ili .sst.tmp, ili -1 ako ime ne odgovara. */
    public static long parseTableId(String fileName) {
        if (!fileName.startsWith("sst-")) {
            return -1;
        }
        String rest = fileName.substring(4);
        int dot = rest.indexOf('.');
        if (dot != 20) {
            return -1;
        }
        String tail = rest.substring(dot);
        if (!tail.equals(".sst") && !tail.equals(".sst.tmp")) {
            return -1;
        }
        try {
            return Long.parseLong(rest.substring(0, dot));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * force privremenog fajla → atomski rename u sst/ (postojeće odredište je greška) → force direktorijuma sst/.
     * Failpoint-i: sstable.after-write.before-sync, sstable.after-sync.before-rename,
     * sstable.after-rename.before-directory-sync (sva tri podržavaju IO_ERROR kroz FileOps).
     */
    public static Path finalizeTable(FileOps ops, Path tmpFile, Path sstDir, long tableId) throws IOException {
        try (FileChannel ch = FileChannel.open(tmpFile, StandardOpenOption.WRITE)) {
            ops.force(ch, "sstable.after-write.before-sync");
        }
        Path target = sstDir.resolve(SSTableMeta.fileName(tableId));
        ops.atomicMove(tmpFile, target, false, "sstable.after-sync.before-rename");
        ops.forceDirectory(sstDir, "sstable.after-rename.before-directory-sync");
        return target;
    }
}
