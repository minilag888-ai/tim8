package rs.raf.dlsm.bloom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BloomFilterTest {

    @Test
    void noFalseNegatives() {
        BloomFilter f = BloomFilter.forEntries(5000);
        for (int i = 0; i < 5000; i++) {
            f.add("key-" + i);
        }
        for (int i = 0; i < 5000; i++) {
            assertTrue(f.mightContain("key-" + i), "lažno negativno za key-" + i);
        }
    }

    @Test
    void falsePositiveRateIsAtMostFivePercent() {
        BloomFilter f = BloomFilter.forEntries(10_000);
        for (int i = 0; i < 10_000; i++) {
            f.add("present-" + i);
        }
        int fp = 0;
        for (int i = 0; i < 10_000; i++) {
            if (f.mightContain("absent-" + i)) {
                fp++;
            }
        }
        assertTrue(fp <= 500, "previše lažno pozitivnih: " + fp);
    }

    @Test
    void sizeFollowsSpec() {
        assertEquals(64, BloomFilter.forEntries(1).numBits());
        assertEquals(1000, BloomFilter.forEntries(100).numBits());
        assertEquals(7, BloomFilter.forEntries(100).probes());
    }
}
