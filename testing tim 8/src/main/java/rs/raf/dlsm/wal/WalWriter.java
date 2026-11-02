package rs.raf.dlsm.wal;

import java.io.Closeable;
import java.io.IOException;
import rs.raf.dlsm.record.InternalRecord;

/**
 * Upis u jedan aktivni WAL segment. Vlasnik: Katarina (Checkpoint 1 §9.1 koraci 5–7, §16).
 *
 * <p>{@link #append} kodira ceo okvir, upisuje SVE bajtove i radi force. Kada se vrati bez izuzetka,
 * zapis je trajan i Ilijin StorageEngine sme da ga stavi u MemTable. Svaki izuzetak znači
 * neizvestan završetak: put upisa prelazi u FAILED i ništa se više ne dopisuje.
 */
public interface WalWriter extends Closeable {

    void append(InternalRecord record) throws IOException;

    /** ID ovog segmenta (deo imena fajla wal-&lt;20 cifara&gt;.log). */
    long segmentId();

    /** Trenutna veličina segmenta u bajtovima (za status). */
    long sizeBytes();
}
