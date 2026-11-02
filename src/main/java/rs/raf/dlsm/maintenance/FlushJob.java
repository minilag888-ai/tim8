package rs.raf.dlsm.maintenance;

import rs.raf.dlsm.engine.EngineException;

/**
 * Ručni flush (Checkpoint 1 §23.1): uzima ciljni redni broj i radi FIFO flush dok ga manifest ne pokrije.
 * Sam algoritam od 13 koraka je u {@link MaintenanceService}, koji serijalizuje sve održavanje. Vlasnik: Ivona.
 */
public final class FlushJob {

    private final MaintenanceService service;

    public FlushJob(MaintenanceService service) {
        this.service = service;
    }

    public FlushResult run(long deadlineNanos) throws EngineException {
        return service.manualFlush(deadlineNanos);
    }
}
