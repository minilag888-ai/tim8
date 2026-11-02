package rs.raf.dlsm.sstable;

/**
 * Metapodaci jedne SSTable tabele; isti podaci stoje u manifestu i u statusu (Checkpoint 1 §18, §24).
 * {@code entryCount} uključuje tombstone zapise.
 */
public record SSTableMeta(long tableId, String file, String minKey, String maxKey,
                          long minSequence, long maxSequence, long entryCount, long sizeBytes) {

    /** Ime fajla za dati ID: sst-&lt;20 cifara&gt;.sst */
    public static String fileName(long tableId) {
        return String.format("sst-%020d.sst", tableId);
    }
}
