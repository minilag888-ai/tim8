package rs.raf.dlsm.api;

import java.util.Base64;
import java.util.regex.Pattern;
import rs.raf.dlsm.record.InternalRecord;

/**
 * Provera ulaza PRE bilo kakvog efekta na skladište (Checkpoint 1 §9.1 korak 1, Project Info §17–18).
 * Neispravan zahtev dobija 400 i ne troši redni broj niti piše u WAL.
 */
public final class Validation {

    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9._~-]{1,128}");
    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9._:-]{1,64}");
    public static final int DEFAULT_TIMEOUT_MS = 5000;
    public static final int MIN_TIMEOUT_MS = 100;
    public static final int MAX_TIMEOUT_MS = 10_000;
    public static final int MAX_SCAN_LIMIT = 1000;

    private Validation() {
    }

    public static String key(String key) throws ApiError {
        if (key == null || !KEY.matcher(key).matches()) {
            throw ApiError.invalid("Ključ mora imati 1..128 znakova iz [A-Za-z0-9._~-]");
        }
        return key;
    }

    public static String requestId(String id) throws ApiError {
        if (id == null || !REQUEST_ID.matcher(id).matches()) {
            throw ApiError.invalid("X-Request-Id mora imati 1..64 znakova iz [A-Za-z0-9._:-]");
        }
        return id;
    }

    /** Vraća trajanje roka u milisekundama (podrazumevano 5000, dozvoljeno 100..10000). */
    public static int timeoutMs(String header) throws ApiError {
        if (header == null) {
            return DEFAULT_TIMEOUT_MS;
        }
        try {
            int v = Integer.parseInt(header.trim());
            if (v < MIN_TIMEOUT_MS || v > MAX_TIMEOUT_MS) {
                throw ApiError.invalid("X-Request-Timeout-Ms mora biti 100..10000");
            }
            return v;
        } catch (NumberFormatException e) {
            throw ApiError.invalid("X-Request-Timeout-Ms nije broj");
        }
    }

    /** Standardni Base64 sa dopunom; prazan string je prazna vrednost. */
    public static byte[] value(Object raw) throws ApiError {
        if (!(raw instanceof String s)) {
            throw ApiError.invalid("Polje value mora biti Base64 string");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(s);
        } catch (IllegalArgumentException e) {
            throw ApiError.invalid("Polje value nije ispravan Base64");
        }
        // Java dekoder prihvata i bez dopune; tražimo kanonski oblik sa dopunom.
        if (!Base64.getEncoder().encodeToString(decoded).equals(s)) {
            throw ApiError.invalid("Polje value mora biti standardni Base64 sa dopunom");
        }
        if (decoded.length > InternalRecord.MAX_VALUE_BYTES) {
            throw new ApiError(400, "VALUE_TOO_LARGE", "Vrednost je veća od 262144 bajta", false);
        }
        return decoded;
    }

    public static int limit(String raw) throws ApiError {
        if (raw == null) {
            throw ApiError.invalid("Parametar limit je obavezan");
        }
        try {
            int v = Integer.parseInt(raw);
            if (v < 1 || v > MAX_SCAN_LIMIT) {
                throw ApiError.invalid("limit mora biti 1..1000");
            }
            return v;
        } catch (NumberFormatException e) {
            throw ApiError.invalid("limit nije broj");
        }
    }
}
