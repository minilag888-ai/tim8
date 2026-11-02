package rs.raf.dlsm.maintenance;

import rs.raf.dlsm.engine.EngineException;

/**
 * Ručni major compaction (Checkpoint 1 §20, §23.2): sažima sve trenutno objavljene tabele u jednu,
 * zadržava tombstone-e i objavljuje jedan zamenski manifest. Algoritam je u {@link MaintenanceService}.
 * Vlasnik: Ivona.
 */
public final class CompactionJob {

    private final MaintenanceService service;

    public CompactionJob(MaintenanceService service) {
        this.service = service;
    }

    public CompactionResult run(long deadlineNanos) throws EngineException {
        return service.manualCompaction(deadlineNanos);
    }
}
