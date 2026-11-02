package rs.raf.dlsm.api;

/** Greška koja se vraća klijentu u zajedničkom omotaču (Project Info §18.7). */
public final class ApiError extends Exception {

    private static final long serialVersionUID = 1L;

    private final int httpStatus;
    private final String code;
    private final boolean retryable;

    public ApiError(int httpStatus, String code, String message, boolean retryable) {
        super(message);
        this.httpStatus = httpStatus;
        this.code = code;
        this.retryable = retryable;
    }

    public static ApiError invalid(String message) {
        return new ApiError(400, "INVALID_REQUEST", message, false);
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }
}
