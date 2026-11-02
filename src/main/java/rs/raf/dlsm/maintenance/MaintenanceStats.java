package rs.raf.dlsm.maintenance;

import java.util.concurrent.atomic.AtomicLong;

/** Brojači održavanja i čišćenja (Checkpoint 1 §24). Vlasnik: Ivona. */
public final class MaintenanceStats {

    public static final MaintenanceStats GLOBAL = new MaintenanceStats();

    public final AtomicLong flushAttempts = new AtomicLong();
    public final AtomicLong flushPublications = new AtomicLong();
    public final AtomicLong compactionAttempts = new AtomicLong();
    public final AtomicLong compactionPublications = new AtomicLong();
    public final AtomicLong orphanFilesRemoved = new AtomicLong();
    public final AtomicLong temporaryFilesRemoved = new AtomicLong();

    public void reset() {
        flushAttempts.set(0);
        flushPublications.set(0);
        compactionAttempts.set(0);
        compactionPublications.set(0);
        orphanFilesRemoved.set(0);
        temporaryFilesRemoved.set(0);
    }
}
