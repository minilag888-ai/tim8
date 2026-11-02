package rs.raf.dlsm.wal;

import java.util.List;
import rs.raf.dlsm.record.InternalRecord;

/**
 * Rezultat čitanja jednog WAL segmenta.
 *
 * @param segmentId      ID iz validiranog zaglavlja
 * @param status         VALID, TORN_TAIL (nepotpun poslednji okvir) ili CORRUPT (oštećenje)
 * @param records        važeći zapisi redom kojim su upisani
 * @param validEndOffset offset posle poslednjeg važećeg okvira (do njega se odseca TORN_TAIL)
 * @param fileSize       stvarna veličina fajla
 * @param error          opis greške za CORRUPT (offset i razlog), inače {@code null}
 */
public record WalScanResult(long segmentId, Status status, List<InternalRecord> records,
                            long validEndOffset, long fileSize, String error) {

    public enum Status { VALID, TORN_TAIL, CORRUPT }

    public WalScanResult {
        records = List.copyOf(records);
    }
}
