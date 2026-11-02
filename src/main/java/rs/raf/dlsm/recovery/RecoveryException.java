package rs.raf.dlsm.recovery;

/**
 * Neuspeh oporavka sa stabilnom kategorijom (Checkpoint 1 §21.4). Primeri kategorija:
 * DIRECTORY_LOCKED, NODE_IDENTITY, MANIFEST_MISSING, MANIFEST_INVALID, REFERENCED_FILE_MISSING,
 * MANIFEST_TABLE_MISMATCH, DATA_BLOCK, INDEX, BLOOM, FOOTER, HEADER, TRUNCATED_REGION,
 * WAL_CORRUPT, WAL_SEQUENCE, WAL_CREATE_FAILED.
 */
public final class RecoveryException extends Exception {

    private static final long serialVersionUID = 1L;

    private final String category;

    public RecoveryException(String category, String message) {
        this(category, message, null);
    }

    public RecoveryException(String category, String message, Throwable cause) {
        super(message, cause);
        this.category = category;
    }

    public String category() {
        return category;
    }
}
