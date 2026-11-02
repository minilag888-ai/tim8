package rs.raf.dlsm.engine;

/**
 * Greška mehanizma sa kodom iz zajedničkog omotača (Project Info §18.7).
 * HTTP sloj je samo prevodi u odgovor.
 */
public final class EngineException extends Exception {

    private static final long serialVersionUID = 1L;

    private final String code;
    private final int httpStatus;
    private final boolean retryable;

    public EngineException(String code, int httpStatus, boolean retryable, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.httpStatus = httpStatus;
        this.retryable = retryable;
    }

    public static EngineException storageIo(String message, Throwable cause) {
        return new EngineException("STORAGE_IO", 500, false, message, cause);
    }

    public static EngineException notReady(String message) {
        return new EngineException("NODE_NOT_READY", 503, true, message, null);
    }

    public static EngineException deadline(String message) {
        return new EngineException("DEADLINE_EXCEEDED", 504, true, message, null);
    }

    public String code() {
        return code;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public boolean retryable() {
        return retryable;
    }
}
