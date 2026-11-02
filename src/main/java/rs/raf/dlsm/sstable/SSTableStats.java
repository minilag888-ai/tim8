package rs.raf.dlsm.sstable;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Brojači čitanja SSTable tabela (Checkpoint 1 §24). Deli ih ceo proces; Ilijin status ih prikazuje.
 * Validacija pri pokretanju se računa u sstableDataBlocksRead/BytesRead (dozvoljeno po §24).
 */
public final class SSTableStats {

    public static final SSTableStats GLOBAL = new SSTableStats();

    public final AtomicLong dataBlocksRead = new AtomicLong();
    public final AtomicLong dataBytesRead = new AtomicLong();
    public final AtomicLong bloomNegativeSkips = new AtomicLong();
    public final AtomicLong bloomPositiveProbes = new AtomicLong();
    public final AtomicLong rangeScanBlocksRead = new AtomicLong();

    public void reset() {
        dataBlocksRead.set(0);
        dataBytesRead.set(0);
        bloomNegativeSkips.set(0);
        bloomPositiveProbes.set(0);
        rangeScanBlocksRead.set(0);
    }
}
