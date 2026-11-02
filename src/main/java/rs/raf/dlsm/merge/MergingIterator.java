package rs.raf.dlsm.merge;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.PriorityQueue;
import rs.raf.dlsm.record.InternalRecord;

/**
 * k-way merge više uređenih izvora (Checkpoint 1 §12, §20.2). Vlasnik: Vojislav.
 *
 * <p>Svaki izvor mora biti strogo rastući po ključu. Za svaki ključ vraća TAČNO JEDAN zapis:
 * onaj sa najvećim rednim brojem, UKLJUČUJUĆI tombstone. Pobednika bira redni broj, nikad položaj
 * izvora. Ilijin SCAN posle toga preskače tombstone-e; Ivonin compaction ih zadržava.
 *
 * <p>Min-heap drži najviše jednu tekuću stavku po izvoru; sve verzije najmanjeg ključa uklanjaju se
 * kao jedna grupa i svaki izvor koji je doprineo pomera se napred.
 */
public final class MergingIterator implements Iterator<InternalRecord> {

    private record Head(InternalRecord record, int source) {
    }

    private final List<Iterator<InternalRecord>> sources;
    private final PriorityQueue<Head> heap;
    private InternalRecord pending;

    public MergingIterator(List<Iterator<InternalRecord>> sources) {
        this.sources = new ArrayList<>(sources);
        this.heap = new PriorityQueue<>(Math.max(1, sources.size()),
                (a, b) -> a.record().key().compareTo(b.record().key()));
        for (int i = 0; i < this.sources.size(); i++) {
            pull(i);
        }
    }

    private void pull(int source) {
        Iterator<InternalRecord> it = sources.get(source);
        if (it.hasNext()) {
            heap.add(new Head(it.next(), source));
        }
    }

    @Override
    public boolean hasNext() {
        if (pending != null) {
            return true;
        }
        if (heap.isEmpty()) {
            return false;
        }
        Head first = heap.poll();
        String key = first.record().key();
        InternalRecord winner = first.record();
        List<Integer> contributors = new ArrayList<>();
        contributors.add(first.source());
        while (!heap.isEmpty() && heap.peek().record().key().equals(key)) {
            Head h = heap.poll();
            contributors.add(h.source());
            if (h.record().sequence() > winner.sequence()) {
                winner = h.record();
            }
        }
        for (int s : contributors) {
            pull(s);
        }
        pending = winner;
        return true;
    }

    @Override
    public InternalRecord next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }
        InternalRecord r = pending;
        pending = null;
        return r;
    }
}
