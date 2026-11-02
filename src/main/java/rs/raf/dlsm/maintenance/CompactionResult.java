package rs.raf.dlsm.maintenance;

import java.util.List;

/** Odgovor ručnog compaction-a (Checkpoint 1 §23.2). outputTableId je null za NO_OP. */
public record CompactionResult(String result, List<Long> inputTableIds, Long outputTableId,
                               long manifestGeneration) {
}
