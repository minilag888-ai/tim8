package rs.raf.dlsm.memtable;

import java.util.Iterator;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;
import rs.raf.dlsm.record.InternalRecord;

/**
 * Uređena tabela u memoriji (Checkpoint 1 §10). Drži samo zapis sa najvećim rednim brojem po ključu.
 *
 * <p>Logička veličina: {@code entryBytes = 32 + dužina ključa + dužina vrednosti} (0 za tombstone i praznu
 * vrednost); zamena ključa oduzima stari i dodaje novi zapis.
 *
 * <p>Klasa NIJE thread-safe: sva pristupanja štiti {@code engineLock} iz StorageEngine-a. Posle
 * {@link #freeze()} tabela je nepromenljiva i {@link #put} baca izuzetak.
 */
public final class MemTable {

    private final long generation;
    private final NavigableMap<String, InternalRecord> map = new TreeMap<>();
    private List<Long> walSegmentIds;
    private long logicalBytes;
    private long minSequence = Long.MAX_VALUE;
    private long maxSequence;
    private boolean frozen;

    public MemTable(long generation, List<Long> walSegmentIds) {
        this.generation = generation;
        this.walSegmentIds = List.copyOf(walSegmentIds);
    }

    public static long entryBytes(InternalRecord r) {
        return 32L + r.key().length() + r.valueLength();
    }

    /** Ubacuje zapis; stariji zapis istog ključa ostaje samo ako ima veći redni broj. */
    public void put(InternalRecord r) {
        if (frozen) {
            throw new IllegalStateException("MemTable generacije " + generation + " je zamrznuta");
        }
        InternalRecord old = map.get(r.key());
        if (old != null && old.sequence() >= r.sequence()) {
            return;
        }
        if (old != null) {
            logicalBytes -= entryBytes(old);
        }
        map.put(r.key(), r);
        logicalBytes += entryBytes(r);
        minSequence = Math.min(minSequence, r.sequence());
        maxSequence = Math.max(maxSequence, r.sequence());
    }

    public InternalRecord get(String key) {
        return map.get(key);
    }

    /** Zapisi sa ključem u [start, end) po rastućem ključu; null granica znači bez ograničenja. */
    public Iterator<InternalRecord> iterator(String startInclusive, String endExclusive) {
        NavigableMap<String, InternalRecord> view = map;
        if (startInclusive != null && endExclusive != null) {
            view = map.subMap(startInclusive, true, endExclusive, false);
        } else if (startInclusive != null) {
            view = map.tailMap(startInclusive, true);
        } else if (endExclusive != null) {
            view = map.headMap(endExclusive, false);
        }
        return view.values().iterator();
    }

    public void freeze() {
        frozen = true;
    }

    public boolean isFrozen() {
        return frozen;
    }

    public boolean isEmpty() {
        return map.isEmpty();
    }

    public long generation() {
        return generation;
    }

    public List<Long> walSegmentIds() {
        return walSegmentIds;
    }

    /** Oporavak može vezati oporavljenu tabelu za više starih WAL segmenata. */
    public void setWalSegmentIds(List<Long> ids) {
        this.walSegmentIds = List.copyOf(ids);
    }

    public int entryCount() {
        return map.size();
    }

    public long logicalBytes() {
        return logicalBytes;
    }

    /** null za praznu tabelu. */
    public Long minSequence() {
        return map.isEmpty() ? null : minSequence;
    }

    /** null za praznu tabelu. */
    public Long maxSequence() {
        return map.isEmpty() ? null : maxSequence;
    }
}
