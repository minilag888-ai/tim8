package rs.raf.dlsm.merge;

import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import rs.raf.dlsm.record.InternalRecord;

/**
 * k-way merge više uređenih izvora (Checkpoint 1 §12, §20.2). Vlasnik: Vojislav.
 *
 * <p>Za svaki ključ vraća TAČNO JEDAN zapis: onaj sa najvećim rednim brojem, UKLJUČUJUĆI tombstone.
 * Ilijin SCAN posle toga preskače tombstone-e i primenjuje limit; Ivonin compaction ih zadržava.
 *
 * <p>OSNOVA: samo potpis. Implementaciju piše Vojislav.
 */
public final class MergingIterator implements Iterator<InternalRecord> {

    public MergingIterator(List<Iterator<InternalRecord>> sources) {
        // Vojislav: min-heap po ključu, grupisanje svih verzija istog ključa.
    }

    @Override
    public boolean hasNext() {
        throw new UnsupportedOperationException("TODO Vojislav: MergingIterator");
    }

    @Override
    public InternalRecord next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }
        throw new UnsupportedOperationException("TODO Vojislav: MergingIterator");
    }
}
