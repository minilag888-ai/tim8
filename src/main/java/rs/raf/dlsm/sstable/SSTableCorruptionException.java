package rs.raf.dlsm.sstable;

import java.io.IOException;

/**
 * Oštećenje SSTable tabele sa stabilnom kategorijom (Checkpoint 1 §21.4, CP1-SST-006).
 * Kategorije: HEADER, DATA_BLOCK, INDEX, BLOOM, FOOTER, TRUNCATED_REGION.
 */
public final class SSTableCorruptionException extends IOException {

    private static final long serialVersionUID = 1L;

    private final String category;

    public SSTableCorruptionException(String category, String message) {
        super(category + ": " + message);
        this.category = category;
    }

    public String category() {
        return category;
    }
}
