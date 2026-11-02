package rs.raf.dlsm.engine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import rs.raf.dlsm.failpoint.Failpoints;
import rs.raf.dlsm.memtable.MemTable;
import rs.raf.dlsm.merge.MergingIterator;
import rs.raf.dlsm.record.InternalRecord;
import rs.raf.dlsm.record.Kind;
import rs.raf.dlsm.sstable.SSTableReader;
import rs.raf.dlsm.wal.WalWriter;

/**
 * Lokalni LSM mehanizam: put mutacije, GET, SCAN, rotacija i backpressure (Checkpoint 1 §5–13).
 *
 * <h2>Brave (Checkpoint 1 §13.1)</h2>
 * <ul>
 *   <li>{@code engineLock}: pravedni ReentrantReadWriteLock. PUT/DELETE drže write lock od provere stanja
 *       kroz dodelu rednog broja, WAL append+force i upis u MemTable. GET i SCAN drže read lock.</li>
 *   <li>{@code maintenanceLock}: drži ga ceo flush/compaction posao (Ivona). Redosled je UVEK
 *       maintenanceLock → engineLock. Klijentska mutacija nikad ne uzima maintenanceLock.</li>
 *   <li>Backpressure: pisac čeka na {@code spaceAvailable} (Condition write lock-a); await oslobađa bravu
 *       dok flush napreduje, pa nema deadlock-a.</li>
 * </ul>
 *
 * <h2>Tačka linearizacije</h2>
 * Mutacija je potvrđena kada se uspešno završi {@code WalWriter.append} (koji radi force). Upis u MemTable
 * sledi dok se još drži write lock, pa nijedno čitanje ne može da vidi stanje između (I1, I2).
 */
public final class StorageEngine {

    /** Konfiguracija mehanizma. */
    public record Config(long memtableMaxBytes) {

        public static final long DEFAULT_MEMTABLE_MAX_BYTES = 1L << 20;

        public Config {
            if (memtableMaxBytes < 1) {
                throw new IllegalArgumentException("MEMTABLE_MAX_BYTES mora biti pozitivan");
            }
        }

        /** Čita MEMTABLE_MAX_BYTES iz okruženja (podrazumevano 1 MiB). */
        public static Config fromEnv() {
            String v = System.getenv("MEMTABLE_MAX_BYTES");
            return new Config(v == null || v.isBlank() ? DEFAULT_MEMTABLE_MAX_BYTES : Long.parseLong(v.trim()));
        }
    }

    /** Pravi i trajno finalizuje novi prazan WAL segment (Katarina). */
    public interface WalSegmentFactory {
        WalWriter openNew() throws IOException;
    }

    /** Obaveštava flush radnika (Ivona) da postoji nepromenljiva MemTable. */
    public interface RotationListener {
        void onImmutable(MemTable immutable);
    }

    /** Stanje sa kojim mehanizam počinje posle oporavka. */
    public record InitialState(long nextSequence, MemTable recoveredImmutable, List<SSTableReader> tables) {

        public InitialState {
            tables = List.copyOf(tables);
            if (nextSequence < 1) {
                throw new IllegalArgumentException("nextSequence mora biti >= 1");
            }
        }

        public static InitialState empty() {
            return new InitialState(1, null, List.of());
        }
    }

    /** Rezultat uspešne mutacije. */
    public record MutationResult(long sequence) {
    }

    private final Config config;
    private final NodeState nodeState;
    private final WalSegmentFactory walFactory;
    private final ReentrantReadWriteLock engineLock = new ReentrantReadWriteLock(true);
    private final ReentrantLock maintenanceLock = new ReentrantLock();
    private final Condition spaceAvailable = engineLock.writeLock().newCondition();

    private volatile RotationListener rotationListener = m -> { };

    // Sva polja ispod štiti engineLock.
    private MemTable active;
    private WalWriter activeWal;
    private MemTable immutable;
    private List<SSTableReader> tables;
    private long nextSequence;
    private boolean sequencesExhausted;
    private boolean rotationRequired;
    private int backpressuredWriters;
    private EngineMode mode = EngineMode.SERVING;
    private long lastGeneration;

    public StorageEngine(Config config, NodeState nodeState, WalWriter activeWal, WalSegmentFactory walFactory,
                         InitialState initial) {
        this.config = config;
        this.nodeState = nodeState;
        this.walFactory = walFactory;
        this.activeWal = activeWal;
        this.tables = initial.tables();
        this.nextSequence = initial.nextSequence();
        if (initial.recoveredImmutable() != null) {
            // Oporavljena nepromenljiva tabela dobija generaciju 1, nova aktivna 2 (Checkpoint 1 §14.2).
            this.immutable = initial.recoveredImmutable();
            this.immutable.freeze();
            this.lastGeneration = Math.max(1, immutable.generation());
        }
        this.active = new MemTable(++lastGeneration, List.of(activeWal.segmentId()));
    }

    // ================================================================ mutacije

    public MutationResult put(String key, byte[] value, long deadlineNanos) throws EngineException {
        return mutate(Kind.PUT, key, value, deadlineNanos);
    }

    public MutationResult delete(String key, long deadlineNanos) throws EngineException {
        return mutate(Kind.DELETE, key, null, deadlineNanos);
    }

    private MutationResult mutate(Kind kind, String key, byte[] value, long deadlineNanos) throws EngineException {
        long seq;
        acquireWrite(deadlineNanos);
        try {
            checkWritable();
            // Obavezna rotacija mora da se završi pre nego što nova mutacija počne dopisivanje (§10).
            while (rotationRequired) {
                if (immutable == null) {
                    try {
                        rotateLocked();
                    } catch (IOException e) {
                        // Stari par aktivni WAL + MemTable ostaje važeći; mutacija ne uspeva.
                        throw EngineException.storageIo("Rotacija WAL-a nije uspela: " + e.getMessage(), e);
                    }
                } else {
                    awaitSpace(deadlineNanos);
                    checkWritable();
                }
            }

            if (sequencesExhausted) {
                failLocked("SEQUENCE_EXHAUSTED", "Redni brojevi mutacija su iscrpljeni");
                throw EngineException.notReady("Redni brojevi su iscrpljeni");
            }
            seq = nextSequence;
            if (seq == Long.MAX_VALUE) {
                sequencesExhausted = true;
            } else {
                nextSequence = seq + 1;
            }
            InternalRecord record = new InternalRecord(key, seq, kind, value);

            try {
                activeWal.append(record); // upis celog okvira + force; posle ovoga je mutacija trajna
            } catch (IOException | UncheckedIOException e) {
                // Neizvestan kraj WAL-a: ništa se više ne dopisuje (Checkpoint 1 §9.3).
                failLocked("STORAGE_IO", "WAL append/force nije uspeo: " + e.getMessage());
                throw EngineException.storageIo("WAL upis nije uspeo", e);
            }

            active.put(record);
            if (active.logicalBytes() >= config.memtableMaxBytes()) {
                rotationRequired = true;
            }
        } finally {
            engineLock.writeLock().unlock();
        }
        Failpoints.hit("memtable.after-apply.before-response", Map.of("sequence", seq));
        return new MutationResult(seq);
    }

    private void acquireWrite(long deadlineNanos) throws EngineException {
        try {
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0 || !engineLock.writeLock().tryLock(remaining, TimeUnit.NANOSECONDS)) {
                throw EngineException.deadline("Rok zahteva je istekao pre dobijanja brave");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw EngineException.deadline("Zahtev je prekinut");
        }
    }

    /** Čeka da flush oslobodi mesto; await privremeno oslobađa write lock. */
    private void awaitSpace(long deadlineNanos) throws EngineException {
        backpressuredWriters++;
        if (mode == EngineMode.SERVING) {
            mode = EngineMode.BACKPRESSURED;
        }
        try {
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0) {
                throw EngineException.deadline("Backpressure: flush nije oslobodio mesto pre isteka roka");
            }
            spaceAvailable.awaitNanos(remaining);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw EngineException.deadline("Zahtev je prekinut tokom backpressure-a");
        } finally {
            backpressuredWriters--;
            if (backpressuredWriters == 0 && mode == EngineMode.BACKPRESSURED) {
                mode = EngineMode.SERVING;
            }
        }
    }

    private void checkWritable() throws EngineException {
        if (mode == EngineMode.FAILED) {
            throw EngineException.notReady("Mehanizam je u stanju FAILED");
        }
    }

    // ================================================================ rotacija (§10.1)

    private void rotateLocked() throws IOException {
        WalWriter newWal = walFactory.openNew(); // kreiran, force, rename, force direktorijuma
        Failpoints.hit("rotation.after-new-wal-sync.before-switch", Map.of("walSegmentId", newWal.segmentId()));
        MemTable old = active;
        old.freeze();
        WalWriter oldWal = activeWal;
        immutable = old;
        active = new MemTable(++lastGeneration, List.of(newWal.segmentId()));
        activeWal = newWal;
        rotationRequired = false;
        try {
            oldWal.close(); // svi zapisi starog segmenta su već force-ovani
        } catch (IOException ignored) {
            // zatvaranje posle force-a ne utiče na trajnost
        }
        Failpoints.hit("rotation.after-switch.before-flush", Map.of("generation", old.generation()));
        rotationListener.onImmutable(old);
    }

    /**
     * Za ručni flush (Ivona): vraća postojeću nepromenljivu MemTable, ili rotira nepraznu aktivnu i vraća
     * novu nepromenljivu. Vraća null ako nema šta da se flush-uje.
     */
    public MemTable rotateForFlush() throws EngineException {
        engineLock.writeLock().lock();
        try {
            checkWritable();
            if (immutable != null) {
                return immutable;
            }
            if (active.isEmpty()) {
                return null;
            }
            try {
                rotateLocked();
            } catch (IOException e) {
                throw EngineException.storageIo("Rotacija WAL-a nije uspela: " + e.getMessage(), e);
            }
            return immutable;
        } finally {
            engineLock.writeLock().unlock();
        }
    }

    /**
     * Flush je trajno objavio manifest sa novom tabelom (Ivona): atomski menja skup tabela, povlači
     * nepromenljivu MemTable i budi pisce koji čekaju (§19.2 korak 11).
     */
    public void completeFlush(MemTable flushed, List<SSTableReader> newTables) {
        engineLock.writeLock().lock();
        try {
            if (immutable != flushed) {
                throw new IllegalStateException("Flush-ovana generacija nije trenutna nepromenljiva MemTable");
            }
            tables = List.copyOf(newTables);
            immutable = null;
            spaceAvailable.signalAll();
        } finally {
            engineLock.writeLock().unlock();
        }
    }

    /** Compaction (Ivona) je objavio novi skup tabela. */
    public void replaceTables(List<SSTableReader> newTables) {
        engineLock.writeLock().lock();
        try {
            tables = List.copyOf(newTables);
        } finally {
            engineLock.writeLock().unlock();
        }
    }

    public void setRotationListener(RotationListener listener) {
        this.rotationListener = listener;
        MemTable pending;
        engineLock.readLock().lock();
        try {
            pending = immutable;
        } finally {
            engineLock.readLock().unlock();
        }
        if (pending != null) {
            listener.onImmutable(pending);
        }
    }

    /** Fatalna greška održavanja ili skladišta (npr. manifest.after-rename.before-directory-sync). */
    public void fail(String code, String message) {
        engineLock.writeLock().lock();
        try {
            failLocked(code, message);
        } finally {
            engineLock.writeLock().unlock();
        }
    }

    private void failLocked(String code, String message) {
        mode = EngineMode.FAILED;
        nodeState.fail(code, message);
        spaceAvailable.signalAll();
    }

    // ================================================================ čitanja

    /** Najnovija verzija ključa (može biti tombstone), ili prazno ako ključ nikad nije upisan. */
    public Optional<InternalRecord> getRecord(String key) throws EngineException {
        engineLock.readLock().lock();
        try {
            checkReadable();
            InternalRecord best = newer(null, active.get(key));
            if (immutable != null) {
                best = newer(best, immutable.get(key));
            }
            for (SSTableReader t : tables) {
                // Pobeđuje najveći redni broj, ne položaj tabele (I3).
                best = newer(best, t.get(key).orElse(null));
            }
            return Optional.ofNullable(best);
        } catch (IOException e) {
            throw EngineException.storageIo("Čitanje SSTable nije uspelo: " + e.getMessage(), e);
        } finally {
            engineLock.readLock().unlock();
        }
    }

    /** Vrednost ključa, ili prazno ako ga nema ili je najnoviji zapis tombstone. */
    public Optional<byte[]> get(String key) throws EngineException {
        return getRecord(key).filter(r -> !r.isTombstone()).map(InternalRecord::value);
    }

    private static InternalRecord newer(InternalRecord a, InternalRecord b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return b.sequence() > a.sequence() ? b : a;
    }

    /**
     * Živi parovi u [startInclusive, endExclusive), rastuće, najviše {@code limit} (Checkpoint 1 §12).
     * Ceo scan se radi pod read lock-om, pa vidi jedan stabilan snapshot.
     */
    public List<InternalRecord> scan(String startInclusive, String endExclusive, int limit) throws EngineException {
        engineLock.readLock().lock();
        try {
            checkReadable();
            Failpoints.hit("scan.after-snapshot.before-merge", Map.of());
            List<Iterator<InternalRecord>> sources = new ArrayList<>();
            sources.add(active.iterator(startInclusive, endExclusive));
            if (immutable != null) {
                sources.add(immutable.iterator(startInclusive, endExclusive));
            }
            for (SSTableReader t : tables) {
                sources.add(bounded(t.iterator(startInclusive), endExclusive));
            }
            MergingIterator merged = new MergingIterator(sources);
            List<InternalRecord> out = new ArrayList<>();
            while (out.size() < limit && merged.hasNext()) {
                InternalRecord r = merged.next();
                if (!r.isTombstone()) { // tombstone ne ulazi u limit
                    out.add(r);
                }
            }
            return out;
        } catch (IOException | UncheckedIOException e) {
            throw EngineException.storageIo("Skeniranje SSTable nije uspelo: " + e.getMessage(), e);
        } finally {
            engineLock.readLock().unlock();
        }
    }

    private static Iterator<InternalRecord> bounded(Iterator<InternalRecord> it, String endExclusive) {
        return new Iterator<>() {
            private InternalRecord next;
            private boolean done;

            @Override
            public boolean hasNext() {
                if (next != null) {
                    return true;
                }
                if (done || !it.hasNext()) {
                    return false;
                }
                InternalRecord r = it.next();
                if (endExclusive != null && r.key().compareTo(endExclusive) >= 0) {
                    done = true;
                    return false;
                }
                next = r;
                return true;
            }

            @Override
            public InternalRecord next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                InternalRecord r = next;
                next = null;
                return r;
            }
        };
    }

    private void checkReadable() throws EngineException {
        if (mode == EngineMode.FAILED) {
            // Izbor tima (§37): posle fatalne greške odbijamo i čitanja.
            throw EngineException.notReady("Mehanizam je u stanju FAILED");
        }
    }

    // ================================================================ za flush, compaction i status

    public ReentrantLock maintenanceLock() {
        return maintenanceLock;
    }

    public List<SSTableReader> tables() {
        engineLock.readLock().lock();
        try {
            return tables;
        } finally {
            engineLock.readLock().unlock();
        }
    }

    public MemTable immutable() {
        engineLock.readLock().lock();
        try {
            return immutable;
        } finally {
            engineLock.readLock().unlock();
        }
    }

    /** Najveći redni broj u bilo kom izvoru (uključujući tombstone), ili 0 (ciljni broj ručnog flush-a §23.1). */
    public long highestSequence() {
        engineLock.readLock().lock();
        try {
            long max = 0;
            if (active.maxSequence() != null) {
                max = active.maxSequence();
            }
            if (immutable != null && immutable.maxSequence() != null) {
                max = Math.max(max, immutable.maxSequence());
            }
            for (SSTableReader t : tables) {
                max = Math.max(max, t.meta().maxSequence());
            }
            return max;
        } finally {
            engineLock.readLock().unlock();
        }
    }

    public EngineMode mode() {
        engineLock.readLock().lock();
        try {
            return mode;
        } finally {
            engineLock.readLock().unlock();
        }
    }

    public long activeWalSegmentId() {
        engineLock.readLock().lock();
        try {
            return activeWal.segmentId();
        } finally {
            engineLock.readLock().unlock();
        }
    }

    /** Deo statusa koji poznaje mehanizam (Checkpoint 1 §24), kao JSON mapa. */
    public Map<String, Object> statusFields() {
        engineLock.readLock().lock();
        try {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("engineMode", mode.name());
            m.put("writeBackpressure", mode == EngineMode.BACKPRESSURED || backpressuredWriters > 0);
            m.put("backpressuredWriters", backpressuredWriters);
            m.put("nextSequence", sequencesExhausted ? null : nextSequence);
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("generation", active.generation());
            a.put("walSegmentId", activeWal.segmentId());
            a.put("entryCount", active.entryCount());
            a.put("logicalBytes", active.logicalBytes());
            a.put("minSequence", active.minSequence());
            a.put("maxSequence", active.maxSequence());
            a.put("rotationRequired", rotationRequired);
            m.put("activeMemtable", a);
            if (immutable == null) {
                m.put("immutableMemtable", null);
            } else {
                Map<String, Object> im = new LinkedHashMap<>();
                im.put("generation", immutable.generation());
                im.put("entryCount", immutable.entryCount());
                im.put("logicalBytes", immutable.logicalBytes());
                im.put("minSequence", immutable.minSequence());
                im.put("maxSequence", immutable.maxSequence());
                im.put("walSegmentIds", immutable.walSegmentIds());
                m.put("immutableMemtable", im);
            }
            long visible = 0;
            if (active.maxSequence() != null) {
                visible = active.maxSequence();
            }
            if (immutable != null && immutable.maxSequence() != null) {
                visible = Math.max(visible, immutable.maxSequence());
            }
            for (SSTableReader t : tables) {
                visible = Math.max(visible, t.meta().maxSequence());
            }
            m.put("lastVisibleSequence", visible);
            return m;
        } finally {
            engineLock.readLock().unlock();
        }
    }
}
