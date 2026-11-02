package rs.raf.dlsm.engine;

/** Režim mehanizma (Checkpoint 1 §22, §24). */
public enum EngineMode {
    RECOVERING,
    SERVING,
    BACKPRESSURED,
    FAILED
}
