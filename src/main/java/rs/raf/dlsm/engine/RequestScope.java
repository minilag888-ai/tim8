package rs.raf.dlsm.engine;

/**
 * X-Request-Id tekućeg zahteva za ovu nit. Failpoint filteri ({@code requestId}) i logovi ga čitaju
 * bez prosleđivanja kroz sve metode.
 */
public final class RequestScope implements AutoCloseable {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private final String previous;

    private RequestScope(String previous) {
        this.previous = previous;
    }

    public static RequestScope enter(String requestId) {
        String prev = CURRENT.get();
        CURRENT.set(requestId);
        return new RequestScope(prev);
    }

    /** X-Request-Id tekućeg zahteva ili null (npr. za flush radnika). */
    public static String currentRequestId() {
        return CURRENT.get();
    }

    @Override
    public void close() {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }
}
