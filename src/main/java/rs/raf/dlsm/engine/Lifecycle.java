package rs.raf.dlsm.engine;

/** Zajednička stanja životnog ciklusa (Project Info §20.1). */
public enum Lifecycle {
    STARTING,
    RECOVERING,
    JOINING,
    READY,
    DEGRADED,
    FAILED,
    STOPPING
}
