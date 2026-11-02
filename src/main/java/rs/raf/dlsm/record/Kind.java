package rs.raf.dlsm.record;

/** Vrsta mutacije. Kod u WAL-u: PUT = 1, DELETE = 2 (Checkpoint 1 §16.3.1). */
public enum Kind {
    PUT((byte) 1),
    DELETE((byte) 2);

    private final byte code;

    Kind(byte code) {
        this.code = code;
    }

    public byte code() {
        return code;
    }

    public static Kind fromCode(byte code) {
        return switch (code) {
            case 1 -> PUT;
            case 2 -> DELETE;
            default -> throw new IllegalArgumentException("Nepoznata vrsta zapisa: " + code);
        };
    }
}
