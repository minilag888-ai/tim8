package rs.raf.dlsm.merge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import rs.raf.dlsm.record.InternalRecord;

class MergingIteratorTest {

    @Test
    void highestSequenceWinsAndTombstonesAreKept() {
        // Primer iz Checkpoint 1 §12.1
        List<InternalRecord> active = List.of(InternalRecord.delete("beta", 12), InternalRecord.put("delta", 13, "D".getBytes()));
        List<InternalRecord> immutable = List.of(InternalRecord.put("alpha", 8, "A2".getBytes()), InternalRecord.put("beta", 9, "B".getBytes()));
        List<InternalRecord> sst = List.of(InternalRecord.put("alpha", 3, "A1".getBytes()), InternalRecord.put("gamma", 6, "G".getBytes()));
        MergingIterator it = new MergingIterator(List.of(active.iterator(), immutable.iterator(), sst.iterator()));
        List<InternalRecord> out = new ArrayList<>();
        it.forEachRemaining(out::add);
        assertEquals(List.of(
                InternalRecord.put("alpha", 8, "A2".getBytes()),
                InternalRecord.delete("beta", 12),
                InternalRecord.put("delta", 13, "D".getBytes()),
                InternalRecord.put("gamma", 6, "G".getBytes())), out);
    }

    @Test
    void emptySources() {
        assertFalse(new MergingIterator(List.of()).hasNext());
        assertFalse(new MergingIterator(List.of(List.<InternalRecord>of().iterator())).hasNext());
    }
}
