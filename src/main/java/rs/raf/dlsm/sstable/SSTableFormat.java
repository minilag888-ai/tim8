package rs.raf.dlsm.sstable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.CRC32C;
import rs.raf.dlsm.record.InternalRecord;
import rs.raf.dlsm.record.Kind;

/**
 * Tačan raspored bajtova SSTable fajla, verzija 1 (opisano i u FORMAT.md). Svi brojevi su big-endian.
 *
 * <pre>
 * [ZAGLAVLJE 24 B] magic "LSMSST01" (8) | verzija u16 = 1 | rezervisano u16 = 0 | tableId u64 | CRC32C(0..19) u32
 * [BLOKOVI]        za svaki blok: dužina sadržaja u32 | CRC32C(sadržaj) u32 | sadržaj
 *                  sadržaj = niz zapisa: dužina ključa u16 | ključ | redni broj u64 | vrsta u8 (1 PUT, 2 DELETE)
 *                                       | dužina vrednosti u32 | vrednost
 * [INDEKS]         broj stavki u32 | za svaku: dužina ključa u16 | prvi ključ bloka | offset bloka u64 | dužina bloka u32
 *                  | dužina minKey u16 | minKey | dužina maxKey u16 | maxKey
 * [BLOOM]          algoritam u8 = 1 | broj proba u8 | broj bajtova u32 | bajtovi filtera
 * [FOOTER 100 B]   magic "LSMSSTFT" (8) | verzija u16 | rezervisano u16 | tableId u64 | dataOffset u64 | dataLength u64
 *                  | indexOffset u64 | indexLength u32 | indexCrc u32 | bloomOffset u64 | bloomLength u32 | bloomCrc u32
 *                  | entryCount u64 | minSequence u64 | maxSequence u64 | blockCount u32 | CRC32C(0..95) u32
 * </pre>
 */
public final class SSTableFormat {

    public static final byte[] HEADER_MAGIC = "LSMSST01".getBytes(StandardCharsets.US_ASCII);
    public static final byte[] FOOTER_MAGIC = "LSMSSTFT".getBytes(StandardCharsets.US_ASCII);
    public static final int VERSION = 1;
    public static final int HEADER_SIZE = 24;
    public static final int FOOTER_SIZE = 100;
    public static final int BLOCK_HEADER_SIZE = 8;
    public static final int DATA_BLOCK_TARGET_BYTES = 4096;
    /** Najveći kodirani zapis: 2 + 128 + 8 + 1 + 4 + 262144. */
    public static final int MAX_RECORD_BYTES = 2 + InternalRecord.MAX_KEY_LENGTH + 8 + 1 + 4 + InternalRecord.MAX_VALUE_BYTES;

    private SSTableFormat() {
    }

    public static int crc(ByteBuffer buf) {
        CRC32C c = new CRC32C();
        c.update(buf.duplicate());
        return (int) c.getValue();
    }

    public static int crc(byte[] b, int off, int len) {
        CRC32C c = new CRC32C();
        c.update(b, off, len);
        return (int) c.getValue();
    }

    public static int encodedSize(InternalRecord r) {
        return 2 + r.key().length() + 8 + 1 + 4 + r.valueLength();
    }

    public static void encodeRecord(InternalRecord r, ByteBuffer out) {
        byte[] k = r.key().getBytes(StandardCharsets.US_ASCII);
        out.putShort((short) k.length);
        out.put(k);
        out.putLong(r.sequence());
        out.put(r.kind().code());
        byte[] v = r.value();
        out.putInt(v == null ? 0 : v.length);
        if (v != null) {
            out.put(v);
        }
    }

    /** Dekodira jedan zapis; proverava svaku dužinu pre dodele memorije. */
    public static InternalRecord decodeRecord(ByteBuffer in) throws IOException {
        if (in.remaining() < 2) {
            throw new IOException("Zapis bloka je presečen (dužina ključa)");
        }
        int kl = Short.toUnsignedInt(in.getShort());
        if (kl < 1 || kl > InternalRecord.MAX_KEY_LENGTH || in.remaining() < kl + 13) {
            throw new IOException("Neispravna dužina ključa u bloku: " + kl);
        }
        byte[] k = new byte[kl];
        in.get(k);
        long seq = in.getLong();
        byte kindCode = in.get();
        long vl = Integer.toUnsignedLong(in.getInt());
        if (vl > InternalRecord.MAX_VALUE_BYTES || vl > in.remaining()) {
            throw new IOException("Neispravna dužina vrednosti u bloku: " + vl);
        }
        Kind kind;
        try {
            kind = Kind.fromCode(kindCode);
        } catch (IllegalArgumentException e) {
            throw new IOException("Nepoznata vrsta zapisa u bloku: " + kindCode);
        }
        if (kind == Kind.DELETE && vl != 0) {
            throw new IOException("Tombstone sa vrednošću u bloku");
        }
        byte[] v = new byte[(int) vl];
        in.get(v);
        try {
            return new InternalRecord(new String(k, StandardCharsets.US_ASCII), seq, kind,
                    kind == Kind.PUT ? v : null);
        } catch (IllegalArgumentException e) {
            throw new IOException("Neispravan zapis u bloku: " + e.getMessage());
        }
    }

    /** Stavka retkog indeksa. */
    public record IndexEntry(String firstKey, long offset, int length) {
    }

    /** Dekodiran footer. */
    public record Footer(long tableId, long dataOffset, long dataLength, long indexOffset, int indexLength,
                         int indexCrc, long bloomOffset, int bloomLength, int bloomCrc, long entryCount,
                         long minSequence, long maxSequence, int blockCount) {

        ByteBuffer encode() {
            ByteBuffer b = ByteBuffer.allocate(FOOTER_SIZE);
            b.put(FOOTER_MAGIC).putShort((short) VERSION).putShort((short) 0).putLong(tableId)
                    .putLong(dataOffset).putLong(dataLength).putLong(indexOffset).putInt(indexLength).putInt(indexCrc)
                    .putLong(bloomOffset).putInt(bloomLength).putInt(bloomCrc).putLong(entryCount)
                    .putLong(minSequence).putLong(maxSequence).putInt(blockCount);
            b.putInt(crc(b.array(), 0, FOOTER_SIZE - 4));
            return b.flip();
        }

        static Footer decode(ByteBuffer b, long fileSize) throws IOException {
            if (b.remaining() != FOOTER_SIZE) {
                throw new IOException("Footer je presečen");
            }
            byte[] all = new byte[FOOTER_SIZE];
            b.duplicate().get(all);
            int stored = ByteBuffer.wrap(all, FOOTER_SIZE - 4, 4).getInt();
            if (stored != crc(all, 0, FOOTER_SIZE - 4)) {
                throw new IOException("CRC footer-a se ne poklapa");
            }
            byte[] magic = new byte[8];
            b.get(magic);
            if (!java.util.Arrays.equals(magic, FOOTER_MAGIC)) {
                throw new IOException("Pogrešan magic footer-a");
            }
            int version = Short.toUnsignedInt(b.getShort());
            if (version != VERSION) {
                throw new IOException("Nepodržana verzija SSTable formata: " + version);
            }
            if (b.getShort() != 0) {
                throw new IOException("Rezervisano polje footer-a nije 0");
            }
            Footer f = new Footer(b.getLong(), b.getLong(), b.getLong(), b.getLong(), b.getInt(), b.getInt(),
                    b.getLong(), b.getInt(), b.getInt(), b.getLong(), b.getLong(), b.getLong(), b.getInt());
            long footerStart = fileSize - FOOTER_SIZE;
            if (f.dataOffset != HEADER_SIZE || f.dataLength < BLOCK_HEADER_SIZE
                    || f.indexOffset != f.dataOffset + f.dataLength || f.indexLength < 4
                    || f.bloomOffset != f.indexOffset + f.indexLength || f.bloomLength < 6
                    || f.bloomOffset + f.bloomLength != footerStart
                    || f.entryCount < 1 || f.blockCount < 1 || f.minSequence < 1 || f.maxSequence < f.minSequence) {
                throw new IOException("Granice oblasti u footer-u nisu ispravne");
            }
            return f;
        }
    }

    static ByteBuffer encodeHeader(long tableId) {
        ByteBuffer b = ByteBuffer.allocate(HEADER_SIZE);
        b.put(HEADER_MAGIC).putShort((short) VERSION).putShort((short) 0).putLong(tableId);
        b.putInt(crc(b.array(), 0, HEADER_SIZE - 4));
        return b.flip();
    }

    static void validateHeader(ByteBuffer b, long expectedTableId) throws IOException {
        byte[] all = new byte[HEADER_SIZE];
        b.get(all);
        ByteBuffer w = ByteBuffer.wrap(all);
        byte[] magic = new byte[8];
        w.get(magic);
        if (!java.util.Arrays.equals(magic, HEADER_MAGIC)) {
            throw new IOException("Pogrešan magic zaglavlja SSTable");
        }
        if (Short.toUnsignedInt(w.getShort()) != VERSION || w.getShort() != 0) {
            throw new IOException("Nepodržana verzija ili rezervisano polje zaglavlja SSTable");
        }
        long id = w.getLong();
        if (w.getInt() != crc(all, 0, HEADER_SIZE - 4)) {
            throw new IOException("CRC zaglavlja SSTable se ne poklapa");
        }
        if (id != expectedTableId) {
            throw new IOException("ID tabele u zaglavlju (" + id + ") ne odgovara footer-u (" + expectedTableId + ")");
        }
    }

    static ByteBuffer encodeIndex(List<IndexEntry> entries, String minKey, String maxKey) {
        int size = 4;
        for (IndexEntry e : entries) {
            size += 2 + e.firstKey().length() + 8 + 4;
        }
        size += 2 + minKey.length() + 2 + maxKey.length();
        ByteBuffer b = ByteBuffer.allocate(size);
        b.putInt(entries.size());
        for (IndexEntry e : entries) {
            putKey(b, e.firstKey());
            b.putLong(e.offset()).putInt(e.length());
        }
        putKey(b, minKey);
        putKey(b, maxKey);
        return b.flip();
    }

    private static void putKey(ByteBuffer b, String key) {
        byte[] k = key.getBytes(StandardCharsets.US_ASCII);
        b.putShort((short) k.length).put(k);
    }

    static String getKey(ByteBuffer b) throws IOException {
        if (b.remaining() < 2) {
            throw new IOException("Indeks je presečen");
        }
        int len = Short.toUnsignedInt(b.getShort());
        if (len < 1 || len > InternalRecord.MAX_KEY_LENGTH || len > b.remaining()) {
            throw new IOException("Neispravna dužina ključa u indeksu: " + len);
        }
        byte[] k = new byte[len];
        b.get(k);
        return new String(k, StandardCharsets.US_ASCII);
    }
}
