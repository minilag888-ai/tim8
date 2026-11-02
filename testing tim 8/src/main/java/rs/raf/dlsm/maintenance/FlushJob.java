package rs.raf.dlsm.maintenance;

/**
 * Flush po 13 koraka (Checkpoint 1 §19.1) i ručni flush (§23.1). Vlasnik: Ivona.
 * OSNOVA: samo potpis.
 */
public final class FlushJob {

    /** Radi FIFO flush dok flushedThroughSequence ne dostigne targetSequence. */
    public FlushResult run(long targetSequence) {
        throw new UnsupportedOperationException("TODO Ivona: FlushJob");
    }
}
