package rs.raf.dlsm.wal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32C;
import rs.raf.dlsm.record.InternalRecord;
import rs.raf.dlsm.record.Kind;

/**
 * Preporučeni WAL raspored iz Checkpoint 1 §16.3.1, usvojen tačno. Svi brojevi su big-endian.
 *
 * <pre>
 * ZAGLAVLJE SEGMENTA (24 B)
 *   0  8  ASCII "LSMWAL01"
 *   8  2  verzija formata fajla = 1
 *  10  2  rezervisano = 0
 *  12  8  ID segmenta (mora odgovarati imenu fajla)
 *  20  4  CRC32C bajtova 0..19
 *
 * OKVIR ZAPISA (30 + k + v B)
 *   0  4  totalFrameLength = 30 + k + v
 *   4  4  totalFrameLength XOR 0xFFFFFFFF (zaštita dužine)
 *   8  2  verzija zapisa = 1
 *  10  1  vrsta: 1 = PUT, 2 = DELETE
 *  11  1  rezervisano = 0
 *  12  8  redni broj mutacije (pozitivan)
 *  20  2  dužina ključa k (1..128)
 *  22  4  dužina vrednosti v (0..262144; 0 za DELETE)
 *  26  k  ključ
 * 26+k v  vrednost
 * 26+k+v 4 CRC32C bajtova 8..(25+k+v)
 * </pre>
 */
public final class WalFormat {

    public static final byte[] MAGIC = "LSMWAL01".getBytes(StandardCharsets.US_ASCII);
    public static final int FILE_VERSION = 1;
    public static final int RECORD_VERSION = 1;
    public static final int HEADER_SIZE = 24;
    public static final int FRAME_OVERHEAD = 30;
    public static final int PROTECTED_LENGTH_BYTES = 8;
    public static final int MIN_FRAME = FRAME_OVERHEAD + 1;
    public static final int MAX_FRAME = FRAME_OVERHEAD + InternalRecord.MAX_KEY_LENGTH + InternalRecord.MAX_VALUE_BYTES;

    private WalFormat() {
    }

    public static String fileName(long segmentId) {
        return String.format("wal-%020d.log", segmentId);
    }

    public static String tmpName(long segmentId) {
        return fileName(segmentId) + ".tmp";
    }

    /** ID iz wal-&lt;20 cifara&gt;.log ili .log.tmp, ili -1. */
    public static long parseSegmentId(String name) {
        if (!name.startsWith("wal-") || name.length() < 4 + 20 + 4) {
            return -1;
        }
        String digits = name.substring(4, 24);
        String tail = name.substring(24);
        if (!tail.equals(".log") && !tail.equals(".log.tmp") || !digits.chars().allMatch(Character::isDigit)) {
            return -1;
        }
        return Long.parseLong(digits);
    }

    static int crc(byte[] b, int off, int len) {
        CRC32C c = new CRC32C();
        c.update(b, off, len);
        return (int) c.getValue();
    }

    public static ByteBuffer encodeHeader(long segmentId) {
        ByteBuffer b = ByteBuffer.allocate(HEADER_SIZE);
        b.put(MAGIC).putShort((short) FILE_VERSION).putShort((short) 0).putLong(segmentId);
        b.putInt(crc(b.array(), 0, 20));
        return b.flip();
    }

    /** Proverava zaglavlje; baca IOException sa razlogom. */
    public static void validateHeader(byte[] h, long expectedSegmentId) throws IOException {
        if (h.length != HEADER_SIZE) {
            throw new IOException("Zaglavlje segmenta je presečeno");
        }
        ByteBuffer b = ByteBuffer.wrap(h);
        if (!Arrays.equals(Arrays.copyOfRange(h, 0, 8), MAGIC)) {
            throw new IOException("Pogrešan WAL magic");
        }
        b.position(8);
        int version = Short.toUnsignedInt(b.getShort());
        int reserved = Short.toUnsignedInt(b.getShort());
        long id = b.getLong();
        int stored = b.getInt();
        if (stored != crc(h, 0, 20)) {
            throw new IOException("CRC zaglavlja segmenta se ne poklapa");
        }
        if (version != FILE_VERSION) {
            throw new IOException("Nepodržana verzija WAL fajla: " + version);
        }
        if (reserved != 0) {
            throw new IOException("Rezervisano polje zaglavlja nije 0");
        }
        if (id != expectedSegmentId) {
            throw new IOException("ID segmenta u zaglavlju (" + id + ") ne odgovara imenu fajla (" + expectedSegmentId + ")");
        }
    }

    /** Kodira ceo okvir u memoriji pre dopisivanja (Checkpoint 1 §9.1 korak 5). */
    public static ByteBuffer encodeFrame(InternalRecord r) {
        byte[] k = r.key().getBytes(StandardCharsets.US_ASCII);
        byte[] v = r.value();
        int vl = v == null ? 0 : v.length;
        int total = FRAME_OVERHEAD + k.length + vl;
        ByteBuffer b = ByteBuffer.allocate(total);
        b.putInt(total).putInt(total ^ 0xFFFFFFFF);
        b.putShort((short) RECORD_VERSION).put(r.kind().code()).put((byte) 0).putLong(r.sequence());
        b.putShort((short) k.length).putInt(vl).put(k);
        if (v != null) {
            b.put(v);
        }
        b.putInt(crc(b.array(), 8, total - 12));
        return b.flip();
    }

    /** Dekodira kompletan okvir (dužina je već proverena); baca IOException za oštećenje. */
    public static InternalRecord decodeFrame(byte[] f) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(f);
        int total = b.getInt();
        b.getInt();
        int version = Short.toUnsignedInt(b.getShort());
        byte kindCode = b.get();
        byte reserved = b.get();
        long seq = b.getLong();
        int k = Short.toUnsignedInt(b.getShort());
        long v = Integer.toUnsignedLong(b.getInt());
        if (version != RECORD_VERSION) {
            throw new IOException("Nepodržana verzija zapisa: " + version);
        }
        if (reserved != 0) {
            throw new IOException("Rezervisano polje zapisa nije 0");
        }
        if (k < 1 || k > InternalRecord.MAX_KEY_LENGTH || v > InternalRecord.MAX_VALUE_BYTES
                || FRAME_OVERHEAD + k + v != total) {
            throw new IOException("Unutrašnje dužine ne objašnjavaju dužinu okvira");
        }
        int stored = ByteBuffer.wrap(f, total - 4, 4).getInt();
        if (stored != crc(f, 8, total - 12)) {
            throw new IOException("CRC zapisa se ne poklapa");
        }
        Kind kind;
        try {
            kind = Kind.fromCode(kindCode);
        } catch (IllegalArgumentException e) {
            throw new IOException("Nepoznata vrsta zapisa: " + kindCode);
        }
        if (kind == Kind.DELETE && v != 0) {
            throw new IOException("DELETE zapis ima vrednost");
        }
        if (seq <= 0) {
            throw new IOException("Redni broj nije pozitivan: " + seq);
        }
        String key = new String(f, 26, k, StandardCharsets.US_ASCII);
        if (!key.matches("[A-Za-z0-9._~-]{1,128}")) {
            throw new IOException("Neispravni bajtovi ključa");
        }
        byte[] value = kind == Kind.PUT ? Arrays.copyOfRange(f, 26 + k, 26 + k + (int) v) : null;
        return new InternalRecord(key, seq, kind, value);
    }
}
