package rs.raf.dlsm.manifest;

import java.util.List;
import rs.raf.dlsm.sstable.SSTableMeta;

/**
 * Sadržaj MANIFEST.json (Checkpoint 1 §18). Ceo objavljeni skup tabela, ne razlika.
 *
 * @param flushedThroughSequence svi važeći zapisi sa rednim brojem &lt;= ove vrednosti su već u SSTable tabelama
 */
public record Manifest(int formatVersion, long generation, long nextTableId,
                       long flushedThroughSequence, List<SSTableMeta> sstables) {

    public static final int FORMAT_VERSION = 1;

    public Manifest {
        sstables = List.copyOf(sstables);
    }

    /** Početni prazan manifest za novu bazu (Checkpoint 1 §14.2). */
    public static Manifest initial() {
        return new Manifest(FORMAT_VERSION, 0, 1, 0, List.of());
    }
}
