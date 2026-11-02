package rs.raf.dlsm.wal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rs.raf.dlsm.failpoint.Failpoints;
import rs.raf.dlsm.io.FileOps;
import rs.raf.dlsm.json.Json;
import rs.raf.dlsm.record.InternalRecord;

class WalWriterReaderTest {

    @TempDir
    Path root;
    Path wal;
    Path tmp;

    @BeforeEach
    void dirs() throws IOException {
        wal = Files.createDirectories(root.resolve("wal"));
        tmp = Files.createDirectories(root.resolve("tmp"));
    }

    @AfterEach
    void off() {
        Failpoints.setEnabledForTests(false);
    }

    private Path seg(long id) {
        return wal.resolve(WalFormat.fileName(id));
    }

    private Path writeRecords(long id, List<InternalRecord> recs) throws IOException {
        try (FileWalWriter w = FileWalWriter.create(FileOps.local(), wal, tmp, id, new WalStats())) {
            for (InternalRecord r : recs) {
                w.append(r);
            }
        }
        return seg(id);
    }

    private static final List<InternalRecord> RECS = List.of(
            InternalRecord.put("alpha", 1, new byte[]{0, 1, (byte) 0xFF}),
            InternalRecord.put("beta", 2, new byte[0]),
            InternalRecord.delete("alpha", 3));

    @Test
    void codecRoundTripAndSizes() throws IOException {
        ByteBuffer f = WalFormat.encodeFrame(InternalRecord.put("a", 7, new byte[0]));
        assertEquals(31, f.remaining(), "najmanji okvir je 31 B");
        assertEquals(InternalRecord.put("a", 7, new byte[0]), WalFormat.decodeFrame(f.array()));
        assertEquals(262_302, WalFormat.encodeFrame(InternalRecord.put("k".repeat(128), 1,
                new byte[InternalRecord.MAX_VALUE_BYTES])).remaining());
        assertEquals(5, WalFormat.parseSegmentId(WalFormat.fileName(5)));
        assertEquals(-1, WalFormat.parseSegmentId("wal-5.log"));
    }

    @Test
    void writeAndReadBack() throws IOException {
        WalStats stats = new WalStats();
        try (FileWalWriter w = FileWalWriter.create(FileOps.local(), wal, tmp, 1, stats)) {
            for (InternalRecord r : RECS) {
                w.append(r);
            }
        }
        assertEquals(3, stats.forcesSucceeded.get());
        assertEquals(3, stats.appendsCompleted.get());
        assertTrue(Files.list(tmp).findAny().isEmpty(), "privremeni fajl je preimenovan");
        WalScanResult r = new FileWalReader().scan(seg(1));
        assertEquals(WalScanResult.Status.VALID, r.status());
        assertEquals(RECS, r.records());
        assertArrayEquals(new byte[]{0, 1, (byte) 0xFF}, r.records().get(0).value());
    }

    @Test
    void tornTailAtEveryCut() throws IOException {
        Path p = writeRecords(2, RECS);
        long full = Files.size(p);
        long lastFrame = WalFormat.encodeFrame(RECS.get(2)).remaining();
        long lastStart = full - lastFrame;
        for (long cut = lastStart + 1; cut < full; cut++) {
            Path copy = root.resolve("c").resolve(WalFormat.fileName(2));
            Files.createDirectories(copy.getParent());
            Files.copy(p, copy, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            try (FileChannel ch = FileChannel.open(copy, StandardOpenOption.WRITE)) {
                ch.truncate(cut);
            }
            WalScanResult r = new FileWalReader().scan(copy);
            assertEquals(WalScanResult.Status.TORN_TAIL, r.status(), "presek na " + cut);
            assertEquals(lastStart, r.validEndOffset());
            assertEquals(2, r.records().size());
        }
    }

    @Test
    void flippedByteInCompleteFrameIsCorrupt() throws IOException {
        Path p = writeRecords(3, RECS);
        long size = Files.size(p);
        try (RandomAccessFile raf = new RandomAccessFile(p.toFile(), "rw")) {
            raf.seek(size - 6); // u poslednjem, kompletnom okviru
            int b = raf.read();
            raf.seek(size - 6);
            raf.write(b ^ 0x01);
        }
        WalScanResult r = new FileWalReader().scan(p);
        assertEquals(WalScanResult.Status.CORRUPT, r.status(), "kompletan okvir sa lošim CRC-om nije presečen kraj");
        assertTrue(r.error().contains("offset"));
    }

    @Test
    void brokenLengthProtectionIsCorruptEvenAtEnd() throws IOException {
        Path p = writeRecords(4, RECS);
        long lastStart = Files.size(p) - WalFormat.encodeFrame(RECS.get(2)).remaining();
        try (RandomAccessFile raf = new RandomAccessFile(p.toFile(), "rw")) {
            raf.seek(lastStart + 5);
            raf.write(0x00);
        }
        assertEquals(WalScanResult.Status.CORRUPT, new FileWalReader().scan(p).status());
    }

    @Test
    void headerMismatchIsCorrupt() throws IOException {
        Path p = writeRecords(5, RECS);
        Path renamed = wal.resolve(WalFormat.fileName(6));
        Files.move(p, renamed);
        assertEquals(WalScanResult.Status.CORRUPT, new FileWalReader().scan(renamed).status());
    }

    @Test
    void ioErrorBeforeAppendWritesNothingAndBlocksFurtherAppends() throws IOException {
        Failpoints.setEnabledForTests(true);
        Failpoints.arm("io", Json.parseObject("{\"name\":\"wal.before-append\",\"action\":\"IO_ERROR\",\"afterHits\":1}"));
        FileWalWriter w = FileWalWriter.create(FileOps.local(), wal, tmp, 7, new WalStats());
        w.append(RECS.get(0));
        long before = Files.size(seg(7));
        assertThrows(IOException.class, () -> w.append(RECS.get(1)));
        assertEquals(before, Files.size(seg(7)), "nijedan bajt novog zapisa");
        assertThrows(IOException.class, () -> w.append(RECS.get(2)), "posle greške nema dopisivanja");
        w.close();
    }

    @Test
    void prefixFailpointLeavesStrictPrefixThatReadsAsTornTail() throws IOException {
        Failpoints.setEnabledForTests(true);
        Failpoints.arm("pre", Json.parseObject("{\"name\":\"wal.after-record-prefix\",\"action\":\"IO_ERROR\","
                + "\"afterHits\":1,\"parameters\":{\"prefixBytes\":17}}"));
        FileWalWriter w = FileWalWriter.create(FileOps.local(), wal, tmp, 8, new WalStats());
        w.append(RECS.get(0));
        long before = Files.size(seg(8));
        assertThrows(IOException.class, () -> w.append(RECS.get(1)));
        w.close();
        assertEquals(before + 17, Files.size(seg(8)));
        WalScanResult r = new FileWalReader().scan(seg(8));
        assertEquals(WalScanResult.Status.TORN_TAIL, r.status());
        assertEquals(before, r.validEndOffset());
        assertEquals(1, r.records().size());
    }

    @Test
    void createRefusesExistingSegment() throws IOException {
        writeRecords(9, List.of());
        assertThrows(IOException.class, () -> FileWalWriter.create(FileOps.local(), wal, tmp, 9));
    }
}
