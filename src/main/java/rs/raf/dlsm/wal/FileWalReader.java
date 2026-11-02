package rs.raf.dlsm.wal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import rs.raf.dlsm.record.InternalRecord;

/**
 * Čitanje i klasifikacija jednog WAL segmenta (Checkpoint 1 §16.3–16.4). Vlasnik: Katarina.
 *
 * <ul>
 *   <li>EOF preseca zaštićeno zaglavlje okvira (8 B) → TORN_TAIL.</li>
 *   <li>Zaštićeno zaglavlje je ispravno, ali deklarisani okvir prelazi EOF → TORN_TAIL.</li>
 *   <li>Zaštita dužine ne prolazi (komplement, granice) → CORRUPT, čak i za poslednji okvir.</li>
 *   <li>Kompletan okvir sa lošim CRC-om ili nevažećim poljima → CORRUPT.</li>
 *   <li>Redni brojevi moraju strogo da rastu → inače CORRUPT.</li>
 * </ul>
 * Čitač nikad ne menja fajl; odsecanje radi oporavak.
 */
public final class FileWalReader implements WalReader {

    @Override
    public WalScanResult scan(Path segment) throws IOException {
        long expectedId = WalFormat.parseSegmentId(segment.getFileName().toString());
        try (FileChannel ch = FileChannel.open(segment, StandardOpenOption.READ)) {
            long size = ch.size();
            List<InternalRecord> records = new ArrayList<>();
            if (size < WalFormat.HEADER_SIZE) {
                return new WalScanResult(expectedId, WalScanResult.Status.CORRUPT, records, 0, size,
                        "offset 0: zaglavlje segmenta je kraće od 24 B");
            }
            try {
                WalFormat.validateHeader(read(ch, 0, WalFormat.HEADER_SIZE), expectedId);
            } catch (IOException e) {
                return new WalScanResult(expectedId, WalScanResult.Status.CORRUPT, records, 0, size,
                        "offset 0: " + e.getMessage());
            }
            long off = WalFormat.HEADER_SIZE;
            long lastSeq = 0;
            while (off < size) {
                if (size - off < WalFormat.PROTECTED_LENGTH_BYTES) {
                    return new WalScanResult(expectedId, WalScanResult.Status.TORN_TAIL, records, off, size, null);
                }
                ByteBuffer lb = ByteBuffer.wrap(read(ch, off, WalFormat.PROTECTED_LENGTH_BYTES));
                int len = lb.getInt();
                int comp = lb.getInt();
                if ((len ^ 0xFFFFFFFF) != comp || len < WalFormat.MIN_FRAME || len > WalFormat.MAX_FRAME) {
                    return corrupt(expectedId, records, off, size, "zaštita dužine okvira ne prolazi");
                }
                if (off + len > size) {
                    return new WalScanResult(expectedId, WalScanResult.Status.TORN_TAIL, records, off, size, null);
                }
                InternalRecord r;
                try {
                    r = WalFormat.decodeFrame(read(ch, off, len));
                } catch (IOException e) {
                    return corrupt(expectedId, records, off, size, e.getMessage());
                }
                if (r.sequence() <= lastSeq) {
                    return corrupt(expectedId, records, off, size,
                            "redni broj " + r.sequence() + " ne raste (prethodni " + lastSeq + ")");
                }
                lastSeq = r.sequence();
                records.add(r);
                off += len;
            }
            return new WalScanResult(expectedId, WalScanResult.Status.VALID, records, off, size, null);
        }
    }

    private static WalScanResult corrupt(long id, List<InternalRecord> records, long off, long size, String why) {
        return new WalScanResult(id, WalScanResult.Status.CORRUPT, records, off, size, "offset " + off + ": " + why);
    }

    private static byte[] read(FileChannel ch, long pos, int len) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(len);
        while (b.hasRemaining()) {
            int n = ch.read(b, pos + b.position());
            if (n < 0) {
                throw new IOException("Neočekivan kraj fajla");
            }
        }
        return b.array();
    }
}
