package rs.raf.dlsm.engine;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rs.raf.dlsm.io.FileOps;
import rs.raf.dlsm.memtable.MemTable;
import rs.raf.dlsm.record.InternalRecord;
import rs.raf.dlsm.sstable.FileSSTableReader;
import rs.raf.dlsm.sstable.FileSSTableWriter;
import rs.raf.dlsm.wal.WalWriter;

class StorageEngineTest {

    @TempDir
    Path dir;

    /** WAL za testove: pamti zapise i po želji baca grešku. */
    static final class FakeWal implements WalWriter {
        final long id;
        final List<InternalRecord> records = Collections.synchronizedList(new ArrayList<>());
        volatile boolean fail;

        FakeWal(long id) {
            this.id = id;
        }

        @Override
        public void append(InternalRecord r) throws IOException {
            if (fail) {
                throw new IOException("injected");
            }
            records.add(r);
        }

        @Override
        public long segmentId() {
            return id;
        }

        @Override
        public long sizeBytes() {
            return records.size();
        }

        @Override
        public void close() {
        }
    }

    private final List<FakeWal> wals = Collections.synchronizedList(new ArrayList<>());
    private final NodeState state = new NodeState("standalone");

    private StorageEngine engine(long maxBytes, StorageEngine.InitialState init) {
        FakeWal first = new FakeWal(1);
        wals.add(first);
        return new StorageEngine(new StorageEngine.Config(maxBytes), state, first, () -> {
            FakeWal w = new FakeWal(wals.size() + 1);
            wals.add(w);
            return w;
        }, init);
    }

    private static long soon() {
        return System.nanoTime() + 5_000_000_000L;
    }

    @Test
    void putGetOverwriteEmptyAndDelete() throws Exception {
        StorageEngine e = engine(1 << 20, StorageEngine.InitialState.empty());
        assertEquals(1, e.put("alpha", new byte[]{0, (byte) 0xFF}, soon()).sequence());
        assertArrayEquals(new byte[]{0, (byte) 0xFF}, e.get("alpha").orElseThrow());
        e.put("alpha", "B".getBytes(), soon());
        e.put("alpha", new byte[0], soon());
        assertArrayEquals(new byte[0], e.get("alpha").orElseThrow(), "prazna vrednost nije NOT_FOUND");
        assertEquals(3, wals.get(0).records.size(), "svaka mutacija ide kroz WAL");
        e.delete("alpha", soon());
        e.delete("never", soon());
        assertFalse(e.get("alpha").isPresent());
        assertTrue(e.getRecord("never").orElseThrow().isTombstone(), "brisanje nepostojećeg ostavlja tombstone");
        assertEquals(6L, e.statusFields().get("nextSequence"));
    }

    @Test
    void scanSkipsTombstonesAndRespectsLimitAndBounds() throws Exception {
        StorageEngine e = engine(1 << 20, StorageEngine.InitialState.empty());
        for (String k : List.of("a", "b", "c", "d", "e")) {
            e.put(k, k.getBytes(), soon());
        }
        e.delete("b", soon());
        List<String> keys = new ArrayList<>();
        e.scan("a", "e", 10).forEach(r -> keys.add(r.key()));
        assertEquals(List.of("a", "c", "d"), keys);
        assertEquals(2, e.scan("a", "z", 2).size());
        assertEquals("c", e.scan("b", "z", 1).get(0).key());
    }

    @Test
    void walFailureMovesToFailedAndNothingBecomesVisible() throws Exception {
        StorageEngine e = engine(1 << 20, StorageEngine.InitialState.empty());
        e.put("k", "1".getBytes(), soon());
        wals.get(0).fail = true;
        EngineException ex = assertThrows(EngineException.class, () -> e.put("k", "2".getBytes(), soon()));
        assertEquals("STORAGE_IO", ex.code());
        assertEquals(EngineMode.FAILED, e.mode());
        assertNotNull(state.lastFatalError());
        assertThrows(EngineException.class, () -> e.put("x", new byte[0], soon()));
        assertThrows(EngineException.class, () -> e.get("k"));
    }

    @Test
    void rotationBackpressureAndCompleteFlush() throws Exception {
        StorageEngine e = engine(100, StorageEngine.InitialState.empty());
        AtomicReference<MemTable> flushed = new AtomicReference<>();
        e.setRotationListener(flushed::set);
        e.put("k1", new byte[80], soon());           // 32+2+80 >= 100 → potrebna rotacija
        e.put("k2", new byte[80], soon());           // rotira, pa ide u novu aktivnu
        assertNotNull(flushed.get());
        assertEquals(2, wals.size(), "rotacija otvara novi WAL");
        assertArrayEquals(new byte[80], e.get("k1").orElseThrow(), "nepromenljiva tabela se i dalje čita");

        // Aktivna je opet puna, a nepromenljiva još postoji → backpressure do isteka roka.
        EngineException ex = assertThrows(EngineException.class,
                () -> e.put("k3", new byte[1], System.nanoTime() + 200_000_000L));
        assertEquals("DEADLINE_EXCEEDED", ex.code());
        assertFalse(e.get("k3").isPresent());

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> err = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            try {
                e.put("k4", new byte[1], soon());
            } catch (Throwable t) {
                err.set(t);
            }
            done.countDown();
        });
        writer.start();
        for (int i = 0; i < 200 && (Integer) e.statusFields().get("backpressuredWriters") == 0; i++) {
            Thread.sleep(10); // čekamo vidljivo stanje, ne fiksno vreme
        }
        assertEquals(true, e.statusFields().get("writeBackpressure"));
        e.completeFlush(flushed.get(), List.of());
        done.await();
        assertEquals(null, err.get());
        assertTrue(e.get("k4").isPresent());
    }

    @Test
    void readsResolveHighestSequenceAcrossMemoryAndSSTables() throws Exception {
        Path tmp = dir.resolve("t.sst");
        new FileSSTableWriter(FileOps.local()).build(1, List.of(
                InternalRecord.put("alpha", 3, "A1".getBytes()),
                InternalRecord.put("beta", 4, "B-old".getBytes()),
                InternalRecord.put("gamma", 6, "G".getBytes())).iterator(), tmp);
        FileSSTableReader t = FileSSTableReader.open(tmp, 1);
        MemTable recovered = new MemTable(1, List.of(1L));
        recovered.put(InternalRecord.put("alpha", 8, "A2".getBytes()));
        StorageEngine e = engine(1 << 20, new StorageEngine.InitialState(10, recovered, List.of(t)));
        e.delete("beta", soon());
        assertArrayEquals("A2".getBytes(), e.get("alpha").orElseThrow());
        assertFalse(e.get("beta").isPresent(), "tombstone sakriva stariju vrednost iz SSTable");
        assertArrayEquals("G".getBytes(), e.get("gamma").orElseThrow());
        List<String> keys = new ArrayList<>();
        e.scan("a", "z", 10).forEach(r -> keys.add(r.key() + "=" + new String(r.value())));
        assertEquals(List.of("alpha=A2", "gamma=G"), keys);
        assertEquals(10, e.highestSequence());
        t.close();
    }

    @Test
    void concurrentMutationsGetUniqueSequencesAndLastWins() throws Exception {
        StorageEngine e = engine(1 << 20, StorageEngine.InitialState.empty());
        List<Thread> threads = new ArrayList<>();
        Set<Long> seqs = Collections.synchronizedSet(new HashSet<>());
        for (int t = 0; t < 8; t++) {
            final int id = t;
            Thread th = new Thread(() -> {
                for (int i = 0; i < 200; i++) {
                    try {
                        seqs.add(e.put("same", new byte[]{(byte) id}, soon()).sequence());
                    } catch (EngineException ex) {
                        throw new AssertionError(ex);
                    }
                }
            });
            threads.add(th);
            th.start();
        }
        for (Thread th : threads) {
            th.join();
        }
        assertEquals(1600, seqs.size());
        InternalRecord last = wals.get(0).records.get(wals.get(0).records.size() - 1);
        assertEquals(1600, last.sequence(), "WAL redosled prati redne brojeve");
        assertArrayEquals(last.value(), e.get("same").orElseThrow());
    }
}
