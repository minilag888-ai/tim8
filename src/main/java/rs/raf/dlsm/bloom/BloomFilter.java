package rs.raf.dlsm.bloom;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Bloom filter za jednu SSTable tabelu (Checkpoint 1 §17.5).
 *
 * <ul>
 *   <li>m = max(64, 10·n) bitova, zaokruženo naviše na cele bajtove; k = 7 proba.</li>
 *   <li>Heš: SHA-256 nad ASCII bajtovima ključa. h1 = prvih 8 bajtova digest-a (big-endian long),
 *       h2 = sledećih 8 bajtova sa postavljenim najnižim bitom (neparan).</li>
 *   <li>Proba i: pozicija = Long.remainderUnsigned(h1 + i·h2, m) (sabiranje sa prelivanjem).</li>
 *   <li>Bit b je u bajtu b &gt;&gt;&gt; 3, maska 1 &lt;&lt; (b &amp; 7).</li>
 * </ul>
 * Lažno negativni rezultati su nemogući; lažno pozitivni su dozvoljeni.
 */
public final class BloomFilter {

    /** Identifikator algoritma koji se čuva u SSTable fajlu. */
    public static final byte ALGORITHM_SHA256_DOUBLE_HASH = 1;
    public static final int PROBES = 7;

    private final byte[] bits;
    private final long numBits;
    private final int probes;

    private BloomFilter(byte[] bits, int probes) {
        this.bits = bits;
        this.numBits = (long) bits.length * 8L;
        this.probes = probes;
    }

    /** Prazan filter dimenzionisan za n ključeva. */
    public static BloomFilter forEntries(long n) {
        long m = Math.max(64L, 10L * n);
        long bytes = (m + 7) / 8;
        if (bytes > Integer.MAX_VALUE - 16) {
            throw new IllegalArgumentException("Bloom filter je prevelik za " + n + " ključeva");
        }
        return new BloomFilter(new byte[(int) bytes], PROBES);
    }

    /** Filter učitan iz fajla. */
    public static BloomFilter fromBytes(byte[] bits, int probes) throws IOException {
        if (bits.length < 8 || probes < 1 || probes > 32) {
            throw new IOException("Neispravni Bloom metapodaci: bajtova=" + bits.length + ", proba=" + probes);
        }
        return new BloomFilter(bits.clone(), probes);
    }

    public void add(String key) {
        long[] h = hash(key);
        addHashed(h[0], h[1]);
    }

    /** Dodaje ključ čiji je heš već izračunat sa {@link #hash(String)}. */
    public void addHashed(long h1, long h2) {
        long[] h = {h1, h2};
        for (int i = 0; i < probes; i++) {
            long bit = Long.remainderUnsigned(h[0] + i * h[1], numBits);
            bits[(int) (bit >>> 3)] |= (byte) (1 << (bit & 7));
        }
    }

    /** {@code false} znači: ključ sigurno nije u tabeli. {@code true}: možda jeste. */
    public boolean mightContain(String key) {
        long[] h = hash(key);
        for (int i = 0; i < probes; i++) {
            long bit = Long.remainderUnsigned(h[0] + i * h[1], numBits);
            if ((bits[(int) (bit >>> 3)] & (1 << (bit & 7))) == 0) {
                return false;
            }
        }
        return true;
    }

    public byte[] bytes() {
        return bits.clone();
    }

    public int probes() {
        return probes;
    }

    public long numBits() {
        return numBits;
    }

    /** Vraća {h1, h2} za ključ (h2 je uvek neparan). */
    public static long[] hash(String key) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.US_ASCII));
            ByteBuffer b = ByteBuffer.wrap(d);
            long h1 = b.getLong(0);
            long h2 = b.getLong(8) | 1L;
            return new long[]{h1, h2};
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 nije dostupan", e);
        }
    }
}
