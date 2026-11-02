package rs.raf.dlsm.sstable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;
import rs.raf.dlsm.record.InternalRecord;

/**
 * Pravi jednu SSTable tabelu u privremenom fajlu. Vlasnik: Vojislav (Checkpoint 1 §17.6).
 *
 * <p>Ulaz mora biti strogo rastući po ključu, najviše jedan zapis po ključu; inače greška.
 * Builder upisuje ceo fajl i samoproverava ga čitačem. Force, rename i objavljivanje NE radi builder:
 * to radi finalizacija (Vojislav) i manifest (Ivona).
 */
public interface SSTableWriter {

    SSTableMeta build(long tableId, Iterator<InternalRecord> sortedInput, Path tmpFile) throws IOException;
}
