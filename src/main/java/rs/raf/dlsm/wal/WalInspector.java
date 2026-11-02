package rs.raf.dlsm.wal;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import rs.raf.dlsm.record.InternalRecord;

/**
 * {@code dbctl inspect wal} (Checkpoint 1 §25). Vlasnik: Katarina.
 * Klasifikuje svaki segment kao VALID, TORN_TAIL (sa offset-om početka okvira i EOF-om) ili CORRUPT
 * (sa offset-om i razlogom). NIKAD ne menja fajl. Izlazni kod: 0 za VALID/TORN_TAIL, 1 za oštećenje.
 */
public final class WalInspector {

    private WalInspector() {
    }

    public static int inspect(Path walDir, PrintStream out) throws IOException {
        List<Path> segs = new ArrayList<>();
        try (Stream<Path> s = Files.list(walDir)) {
            s.filter(p -> p.getFileName().toString().endsWith(".log")
                    && WalFormat.parseSegmentId(p.getFileName().toString()) > 0).forEach(segs::add);
        }
        segs.sort((a, b) -> Long.compare(WalFormat.parseSegmentId(a.getFileName().toString()),
                WalFormat.parseSegmentId(b.getFileName().toString())));
        int code = 0;
        FileWalReader reader = new FileWalReader();
        for (Path p : segs) {
            WalScanResult r = reader.scan(p);
            out.println("segment " + r.segmentId() + " (" + p.getFileName() + ") format LSMWAL01 v" + WalFormat.FILE_VERSION
                    + " size=" + r.fileSize());
            long off = WalFormat.HEADER_SIZE;
            for (InternalRecord rec : r.records()) {
                int len = WalFormat.FRAME_OVERHEAD + rec.key().length() + rec.valueLength();
                String what = rec.isTombstone() ? "DELETE (tombstone)"
                        : rec.valueLength() == 0 ? "PUT EMPTY_VALUE (0 B)" : "PUT " + rec.valueLength() + " B";
                out.println("  offset=" + off + " len=" + len + " seq=" + rec.sequence() + " key=" + rec.key() + " " + what
                        + " crc=OK");
                off += len;
            }
            switch (r.status()) {
                case VALID -> out.println("  status: VALID");
                case TORN_TAIL -> out.println("  status: TORN_TAIL frameStart=" + r.validEndOffset() + " eof=" + r.fileSize());
                case CORRUPT -> {
                    out.println("  status: CORRUPT " + r.error());
                    code = 1;
                }
                default -> throw new IllegalStateException();
            }
        }
        if (segs.isEmpty()) {
            out.println("(nema WAL segmenata)");
        }
        return code;
    }
}
