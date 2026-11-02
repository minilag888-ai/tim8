package rs.raf.dlsm.maintenance;

import java.util.List;

/** Odgovor ručnog flush-a (Checkpoint 1 §23.1). result je "PUBLISHED" ili "NO_OP". */
public record FlushResult(String result, long targetSequence, List<Long> publishedTableIds,
                          long manifestGeneration, long flushedThroughSequence) {
}
