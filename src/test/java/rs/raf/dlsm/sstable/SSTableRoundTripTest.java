package rs.raf.dlsm.sstable;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rs.raf.dlsm.io.FileOps;
import rs.raf.dlsm.record.InternalRecord;

class SSTableRoundTripTest {

    @TempDir
    Path dir;

    private static String key(int i) {
        return String.format("k%05d", i);
    }

    private List<InternalRecord> records(int n) {
        List<InternalRecord> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (i % 10 == 3) {
                out.add(InternalRecord.delete(key(i), i + 1));
            } else if (i % 10 == 5) {
                out.add(InternalRecord.put(key(i), i + 1, new byte[0]));
            } else {
                out.add(InternalRecord.put(key(i), i + 1, ("v" + i + "-padding-padding-padding").getBytes()));
            }
        }
        return out;
    }

    private Path build(long id, List<InternalRecord> recs) throws IOException {
        Path tmp = dir.resolve(SSTableFiles.tmpName(id));
        new FileSSTableWriter(FileOps.local()).build(id, recs.iterator(), tmp);
        return tmp;
    }

    @Test
    void getFirstMiddleLastAndAbsent() throws IOException {
        List<InternalRecord> recs = records(2000);
        Path f = build(7, recs);
        SSTableStats stats = new SSTableStats();
        try (FileSSTableReader r = FileSSTableReader.open(f, 7, stats)) {
            assertTrue(r.blockCount() > 10, "očekivano više blokova");
            for (int i : new int[]{0, 999, 1999}) {
                assertEquals(recs.get(i), r.get(key(i)).orElseThrow());
            }
            assertTrue(r.get(key(3)).orElseThrow().isTombstone());
            assertArrayEquals(new byte[0], r.get(key(5)).orElseThrow().value());
            assertFalse(r.get("a").isPresent());
            assertFalse(r.get("zzz").isPresent());
            stats.reset();
            r.get(key(1500));
            assertEquals(1, stats.dataBlocksRead.get(), "GET mora da pročita najviše jedan blok");
        }
    }

    @Test
    void absentKeyInsideRangeIsMostlySkippedByBloom() throws IOException {
        Path f = build(1, records(1000));
        SSTableStats stats = new SSTableStats();
        try (FileSSTableReader r = FileSSTableReader.open(f, 1, stats)) {
            for (int i = 0; i < 200; i++) {
                assertFalse(r.get(key(i) + "x").isPresent());
            }
            assertTrue(stats.bloomNegativeSkips.get() >= 180, "Bloom je preskočio " + stats.bloomNegativeSkips.get());
            assertTrue(stats.dataBlocksRead.get() <= 20);
        }
    }

    @Test
    void iteratorStartsNearStartKey() throws IOException {
        List<InternalRecord> recs = records(2000);
        Path f = build(2, recs);
        SSTableStats stats = new SSTableStats();
        try (FileSSTableReader r = FileSSTableReader.open(f, 2, stats)) {
            Iterator<InternalRecord> it = r.iterator(key(1990));
            int count = 0;
            while (it.hasNext()) {
                assertEquals(recs.get(1990 + count), it.next());
                count++;
            }
            assertEquals(10, count);
            assertTrue(stats.rangeScanBlocksRead.get() <= 2, "skeniranje je čitalo od početka");
        }
    }

    @Test
    void largeValueGetsOwnBlock() throws IOException {
        List<InternalRecord> recs = List.of(
                InternalRecord.put("a", 1, new byte[10]),
                InternalRecord.put("b", 2, new byte[InternalRecord.MAX_VALUE_BYTES]),
                InternalRecord.put("c", 3, new byte[10]));
        Path f = build(3, recs);
        try (FileSSTableReader r = FileSSTableReader.open(f, 3)) {
            assertEquals(3, r.blockCount());
            assertEquals(InternalRecord.MAX_VALUE_BYTES, r.get("b").orElseThrow().valueLength());
            r.validateFully();
        }
    }

    @Test
    void rejectsUnsortedOrEmptyInput() {
        assertThrows(IllegalArgumentException.class, () -> build(4, List.of(
                InternalRecord.put("b", 1, new byte[1]), InternalRecord.put("a", 2, new byte[1]))));
        assertThrows(IllegalArgumentException.class, () -> build(5, List.of()));
    }

    @Test
    void corruptedBlockIsAnErrorNotAbsence() throws IOException {
        Path f = build(6, records(500));
        try (RandomAccessFile raf = new RandomAccessFile(f.toFile(), "rw")) {
            raf.seek(SSTableFormat.HEADER_SIZE + 20);
            int b = raf.read();
            raf.seek(SSTableFormat.HEADER_SIZE + 20);
            raf.write(b ^ 0xFF);
        }
        try (FileSSTableReader r = FileSSTableReader.open(f, 6)) {
            assertThrows(IOException.class, r::validateFully);
            assertThrows(IOException.class, () -> r.get(key(0)));
        }
    }

    @Test
    void corruptedFooterFailsOpen() throws IOException {
        Path f = build(8, records(50));
        long size = Files.size(f);
        try (RandomAccessFile raf = new RandomAccessFile(f.toFile(), "rw")) {
            raf.seek(size - 10);
            raf.write(0x7F);
        }
        assertThrows(IOException.class, () -> FileSSTableReader.open(f, 8));
        assertThrows(IOException.class, () -> FileSSTableReader.open(build(9, records(5)), 10));
    }

    @Test
    void finalizeMovesToSstDirAndRejectsExistingTarget() throws IOException {
        Path sst = Files.createDirectories(dir.resolve("sst"));
        Path tmp = build(11, records(5));
        Path fin = SSTableFiles.finalizeTable(FileOps.local(), tmp, sst, 11);
        assertTrue(Files.exists(fin));
        assertFalse(Files.exists(tmp));
        assertEquals(11, SSTableFiles.parseTableId(fin.getFileName().toString()));
        Path tmp2 = build(11, records(5));
        assertThrows(IOException.class, () -> SSTableFiles.finalizeTable(FileOps.local(), tmp2, sst, 11));
    }
}
