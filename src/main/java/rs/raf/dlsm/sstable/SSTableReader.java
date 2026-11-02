package rs.raf.dlsm.sstable;

import java.io.Closeable;
import java.io.IOException;
import java.util.Iterator;
import java.util.Optional;
import rs.raf.dlsm.record.InternalRecord;

/**
 * Čitanje jedne objavljene SSTable tabele. Vlasnik: Vojislav (Checkpoint 1 §11.2, §12, §17).
 * Oštećenje je uvek IOException, nikad "nije pronađeno".
 */
public interface SSTableReader extends Closeable {

    /** Zapis za ključ iz OVE tabele (može biti tombstone), ili prazno. Čita najviše jedan blok. */
    Optional<InternalRecord> get(String key) throws IOException;

    /** Iterator od prvog ključa &gt;= startKeyInclusive, pozicioniran preko retkog indeksa. */
    Iterator<InternalRecord> iterator(String startKeyInclusive) throws IOException;

    /** Strimovana provera cele tabele pri pokretanju (Checkpoint 1 §17.3). */
    void validateFully() throws IOException;

    SSTableMeta meta();
}
