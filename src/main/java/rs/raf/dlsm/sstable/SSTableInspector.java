package rs.raf.dlsm.sstable;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import rs.raf.dlsm.record.InternalRecord;

/**
 * {@code dbctl inspect sstable <fajl ili ID>} (Checkpoint 1 §25). Vlasnik: Vojislav.
 * Samo čita: ispisuje format, footer, oblasti, retki indeks, Bloom metapodatke, zapise i status provere.
 * Izlazni kod je 0 za ispravnu tabelu, a različit od nule za oštećenje ili nedostajuću tabelu.
 */
public final class SSTableInspector {

    private SSTableInspector() {
    }

    /** {@code arg} je ID tabele ili ime/putanja fajla. */
    public static int inspect(Path sstDir, String arg, PrintStream out) {
        Path file;
        long id;
        if (arg.chars().allMatch(Character::isDigit)) {
            id = Long.parseLong(arg);
            file = sstDir.resolve(SSTableMeta.fileName(id));
        } else {
            file = arg.contains("/") ? Path.of(arg) : sstDir.resolve(arg);
            id = SSTableFiles.parseTableId(file.getFileName().toString());
        }
        out.println("file: " + file.getFileName());
        if (!Files.exists(file)) {
            out.println("status: MISSING");
            return 2;
        }
        try (FileSSTableReader r = FileSSTableReader.open(file, id, new SSTableStats())) {
            SSTableFormat.Footer f = r.footer();
            out.println("format: LSMSST01 version " + SSTableFormat.VERSION);
            out.println("tableId: " + f.tableId());
            out.println("sizeBytes: " + Files.size(file));
            out.println("entryCount: " + f.entryCount() + "  blocks: " + f.blockCount());
            out.println("sequences: " + f.minSequence() + ".." + f.maxSequence());
            out.println("keys: " + r.meta().minKey() + " .. " + r.meta().maxKey());
            out.println("regions: data@" + f.dataOffset() + "+" + f.dataLength() + "  index@" + f.indexOffset() + "+"
                    + f.indexLength() + " crc=" + hex(f.indexCrc()) + "  bloom@" + f.bloomOffset() + "+"
                    + f.bloomLength() + " crc=" + hex(f.bloomCrc()));
            out.println("bloom: bits=" + r.bloomBits() + " probes=" + r.bloomProbes() + " algorithm=SHA256-double-hash");
            out.println("sparse index:");
            int b = 0;
            for (SSTableFormat.IndexEntry e : r.indexEntries()) {
                out.println("  block " + (b++) + " firstKey=" + e.firstKey() + " offset=" + e.offset() + " length=" + e.length());
            }
            out.println("records:");
            Iterator<InternalRecord> it = r.iterator(null);
            while (it.hasNext()) {
                InternalRecord rec = it.next();
                String what = rec.isTombstone() ? "DELETE (tombstone)"
                        : rec.valueLength() == 0 ? "PUT EMPTY_VALUE (0 B)" : "PUT " + rec.valueLength() + " B";
                out.println("  seq=" + rec.sequence() + " key=" + rec.key() + " " + what);
            }
            r.validateFully();
            out.println("checksums: OK");
            out.println("status: VALID");
            return 0;
        } catch (SSTableCorruptionException e) {
            out.println("status: CORRUPT category=" + e.category() + " reason=" + e.getMessage());
            return 1;
        } catch (IOException | UncheckedIOException e) {
            out.println("status: CORRUPT reason=" + e.getMessage());
            return 1;
        }
    }

    private static String hex(int v) {
        return String.format("%08x", v);
    }
}
