package rs.raf.dlsm.record;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class InternalRecordTest {

    @Test
    void emptyValueIsValidPut() {
        InternalRecord r = InternalRecord.put("alpha", 1, new byte[0]);
        assertEquals(Kind.PUT, r.kind());
        assertEquals(0, r.valueLength());
        assertArrayEquals(new byte[0], r.value());
    }

    @Test
    void tombstoneHasNoValue() {
        InternalRecord r = InternalRecord.delete("alpha", 2);
        assertTrue(r.isTombstone());
        assertNull(r.value());
    }

    @Test
    void valueIsCopied() {
        byte[] v = {1, 2, 3};
        InternalRecord r = InternalRecord.put("k", 5, v);
        v[0] = 9;
        assertEquals(1, r.value()[0]);
    }

    @Test
    void rejectsInvalidRecords() {
        assertThrows(IllegalArgumentException.class, () -> InternalRecord.put("k", 0, new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> InternalRecord.put("", 1, new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> new InternalRecord("k", 1, Kind.DELETE, new byte[1]));
        assertThrows(IllegalArgumentException.class,
                () -> InternalRecord.put("k", 1, new byte[InternalRecord.MAX_VALUE_BYTES + 1]));
    }

    @Test
    void kindCodesMatchWalFormat() {
        assertEquals(Kind.PUT, Kind.fromCode((byte) 1));
        assertEquals(Kind.DELETE, Kind.fromCode((byte) 2));
    }
}
