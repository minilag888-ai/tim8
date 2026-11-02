package rs.raf.dlsm.testkit;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Nezavisni referentni model (Checkpoint 1 §28.3): obična uređena mapa ključ → bajtovi.
 * NE koristi nijedan produkcioni kod (WAL, MemTable, SSTable, MergingIterator).
 */
public final class RefModel {

    private final TreeMap<String, byte[]> map = new TreeMap<>();

    public void put(String k, byte[] v) {
        map.put(k, v.clone());
    }

    public void delete(String k) {
        map.remove(k);
    }

    public byte[] get(String k) {
        byte[] v = map.get(k);
        return v == null ? null : v.clone();
    }

    /** Živi parovi u [start, end), rastuće, najviše limit. */
    public List<Map.Entry<String, byte[]>> scan(String start, String end, int limit) {
        List<Map.Entry<String, byte[]>> out = new ArrayList<>();
        for (Map.Entry<String, byte[]> e : map.subMap(start, true, end, false).entrySet()) {
            if (out.size() >= limit) {
                break;
            }
            out.add(Map.entry(e.getKey(), e.getValue().clone()));
        }
        return out;
    }

    public RefModel copy() {
        RefModel m = new RefModel();
        map.forEach((k, v) -> m.map.put(k, v.clone()));
        return m;
    }

    public TreeMap<String, byte[]> snapshot() {
        TreeMap<String, byte[]> s = new TreeMap<>();
        map.forEach((k, v) -> s.put(k, v.clone()));
        return s;
    }

    public boolean sameAs(RefModel o) {
        if (!map.keySet().equals(o.map.keySet())) {
            return false;
        }
        for (Map.Entry<String, byte[]> e : map.entrySet()) {
            if (!Arrays.equals(e.getValue(), o.map.get(e.getKey()))) {
                return false;
            }
        }
        return true;
    }

    /** Kratak digest za izveštaj. */
    public String digest() {
        long h = 1125899906842597L;
        for (Map.Entry<String, byte[]> e : map.entrySet()) {
            h = 31 * h + e.getKey().hashCode();
            h = 31 * h + Arrays.hashCode(e.getValue());
        }
        return map.size() + ":" + Long.toHexString(h);
    }

    public int size() {
        return map.size();
    }
}
