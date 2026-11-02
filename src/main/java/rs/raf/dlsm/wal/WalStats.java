package rs.raf.dlsm.wal;

import java.util.concurrent.atomic.AtomicLong;

/** Brojači WAL-a (Checkpoint 1 §16.5, granice uvećavanja iz §24). */
public final class WalStats {

    public static final WalStats GLOBAL = new WalStats();

    public final AtomicLong appendAttempts = new AtomicLong();
    public final AtomicLong appendsCompleted = new AtomicLong();
    public final AtomicLong forceAttempts = new AtomicLong();
    public final AtomicLong forcesSucceeded = new AtomicLong();
    public final AtomicLong bytesWritten = new AtomicLong();
    public final AtomicLong recordsReplayed = new AtomicLong();
    public final AtomicLong tornTailsTruncated = new AtomicLong();

    public void reset() {
        appendAttempts.set(0);
        appendsCompleted.set(0);
        forceAttempts.set(0);
        forcesSucceeded.set(0);
        bytesWritten.set(0);
        recordsReplayed.set(0);
        tornTailsTruncated.set(0);
    }
}
