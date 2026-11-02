package rs.raf.dlsm.manifest;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import rs.raf.dlsm.sstable.SSTableMeta;

/**
 * {@code dbctl inspect manifest} (Checkpoint 1 §25). Vlasnik: Ivona.
 * Ispisuje i validira MANIFEST.json bez menjanja. Izlazni kod 0 za ispravan, različit od nule inače.
 */
public final class ManifestInspector {

    private ManifestInspector() {
    }

    public static int inspect(Path lsmDir, PrintStream out) {
        Path file = lsmDir.resolve("MANIFEST.json");
        out.println("file: " + file.getFileName());
        if (!Files.exists(file)) {
            out.println("status: MISSING");
            return 2;
        }
        try {
            Manifest m = FileManifestStore.parse(Files.readString(file, StandardCharsets.UTF_8));
            out.println("formatVersion: " + m.formatVersion());
            out.println("generation: " + m.generation());
            out.println("nextTableId: " + m.nextTableId());
            out.println("flushedThroughSequence: " + m.flushedThroughSequence());
            out.println("sstables: " + m.sstables().size());
            for (SSTableMeta t : m.sstables()) {
                out.println("  tableId=" + t.tableId() + " file=" + t.file() + " keys=" + t.minKey() + ".." + t.maxKey()
                        + " seq=" + t.minSequence() + ".." + t.maxSequence() + " entries=" + t.entryCount()
                        + " size=" + t.sizeBytes()
                        + (Files.exists(lsmDir.resolve("sst").resolve(t.file())) ? "" : "  [FAJL NEDOSTAJE]"));
            }
            FileManifestStore.validate(m);
            out.println("status: VALID");
            return 0;
        } catch (IOException e) {
            out.println("status: INVALID reason=" + e.getMessage());
            return 1;
        }
    }
}
