package rs.raf.dlsm.memtable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import rs.raf.dlsm.record.InternalRecord;

class MemTableTest {

    @Test
    void keepsHighestSequenceAndCountsLogicalBytes() {
        MemTable m = new MemTable(1, List.of(1L));
        m.put(InternalRecord.put("alpha", 1, new byte[10]));
        assertEquals(32 + 5 + 10, m.logicalBytes());
        m.put(InternalRecord.put("alpha", 2, new byte[0]));
        assertEquals(32 + 5, m.logicalBytes());
        m.put(InternalRecord.put("alpha", 1, new byte[100])); // stariji zapis ne sme da pobedi
        assertEquals(2, m.get("alpha").sequence());
        m.put(InternalRecord.delete("alpha", 3));
        assertEquals(32 + 5, m.logicalBytes());
        assertEquals(1, m.entryCount());
        assertEquals(Long.valueOf(1), m.minSequence());
        assertEquals(Long.valueOf(3), m.maxSequence());
    }

    @Test
    void iteratesHalfOpenRange() {
        MemTable m = new MemTable(1, List.of(1L));
        for (String k : List.of("a", "b", "c", "d")) {
            m.put(InternalRecord.put(k, k.charAt(0), new byte[0]));
        }
        List<String> keys = new ArrayList<>();
        m.iterator("b", "d").forEachRemaining(r -> keys.add(r.key()));
        assertEquals(List.of("b", "c"), keys);
    }

    @Test
    void frozenTableRejectsWrites() {
        MemTable m = new MemTable(1, List.of(1L));
        m.freeze();
        assertThrows(IllegalStateException.class, () -> m.put(InternalRecord.put("a", 1, new byte[0])));
        assertNull(new MemTable(2, List.of()).maxSequence());
    }
}
