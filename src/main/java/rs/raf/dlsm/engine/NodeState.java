package rs.raf.dlsm.engine;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Jedno mesto koje drži stanje životnog ciklusa procesa i poslednju fatalnu grešku.
 * {@code /health/ready} vraća 200 samo kad je stanje READY. Oporavak (Katarina) samo prijavljuje prelaze.
 */
public final class NodeState {

    /** Fatalna greška za status: kod i poruka. */
    public record FatalError(String code, String message) {
    }

    private final String nodeId;
    private volatile Lifecycle lifecycle = Lifecycle.STARTING;
    private final AtomicReference<FatalError> lastFatalError = new AtomicReference<>();

    public NodeState(String nodeId) {
        this.nodeId = nodeId;
    }

    public String nodeId() {
        return nodeId;
    }

    public Lifecycle lifecycle() {
        return lifecycle;
    }

    public void transition(Lifecycle next) {
        this.lifecycle = next;
    }

    public void fail(String code, String message) {
        lastFatalError.compareAndSet(null, new FatalError(code, message));
        this.lifecycle = Lifecycle.FAILED;
    }

    public FatalError lastFatalError() {
        return lastFatalError.get();
    }

    public boolean isReady() {
        return lifecycle == Lifecycle.READY;
    }
}
