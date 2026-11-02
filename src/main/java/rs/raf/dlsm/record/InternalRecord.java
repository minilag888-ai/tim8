package rs.raf.dlsm.record;

import java.util.Arrays;
import java.util.Objects;

/**
 * Jedna sačuvana verzija ključa (Checkpoint 1 §6).
 *
 * <ul>
 *   <li>{@code sequence} je pozitivan redni broj mutacije; za isti ključ pobeđuje najveći.</li>
 *   <li>{@code value} je {@code null} samo za DELETE (tombstone). Prazan niz je važeća vrednost.</li>
 *   <li>Vrednost se kopira pri pravljenju i pri čitanju, pa je zapis nepromenljiv.</li>
 * </ul>
 *
 * Ovu klasu koriste svi moduli. Menja se samo uz dogovor celog tima.
 */
public record InternalRecord(String key, long sequence, Kind kind, byte[] value) {

    public static final int MAX_KEY_LENGTH = 128;
    public static final int MAX_VALUE_BYTES = 262_144;

    public InternalRecord {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(kind, "kind");
        if (key.isEmpty() || key.length() > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException("Ključ mora imati 1..128 znakova");
        }
        if (sequence <= 0) {
            throw new IllegalArgumentException("Redni broj mora biti pozitivan: " + sequence);
        }
        if (kind == Kind.PUT) {
            Objects.requireNonNull(value, "PUT mora imati vrednost (prazan niz je dozvoljen)");
            if (value.length > MAX_VALUE_BYTES) {
                throw new IllegalArgumentException("Vrednost je veća od 262144 bajta");
            }
            value = value.clone();
        } else if (value != null) {
            throw new IllegalArgumentException("DELETE ne sme imati vrednost");
        }
    }

    public static InternalRecord put(String key, long sequence, byte[] value) {
        return new InternalRecord(key, sequence, Kind.PUT, value);
    }

    public static InternalRecord delete(String key, long sequence) {
        return new InternalRecord(key, sequence, Kind.DELETE, null);
    }

    public boolean isTombstone() {
        return kind == Kind.DELETE;
    }

    /** Vraća kopiju vrednosti, ili {@code null} za tombstone. */
    @Override
    public byte[] value() {
        return value == null ? null : value.clone();
    }

    /** Dužina vrednosti u bajtovima; 0 za tombstone i za praznu vrednost. */
    public int valueLength() {
        return value == null ? 0 : value.length;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof InternalRecord r
                && sequence == r.sequence
                && key.equals(r.key)
                && kind == r.kind
                && Arrays.equals(value, r.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(key, sequence, kind) * 31 + Arrays.hashCode(value);
    }

    @Override
    public String toString() {
        return "InternalRecord[" + key + " @" + sequence + " " + kind
                + (value == null ? "" : " " + value.length + "B") + "]";
    }
}
