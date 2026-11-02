package rs.raf.dlsm.io;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;

/**
 * JEDINI put do diska za ceo projekat. Niko ne poziva {@code FileChannel.write/force},
 * {@code Files.move} ni {@code Files.delete} direktno, nego sve ide kroz ovaj interfejs.
 * Samo tako IO_ERROR failpoint-i prolaze kroz pravu granu greške (Checkpoint 1 §26.4).
 *
 * <p>{@code failpointName} je ime failpoint-a koji se proverava NEPOSREDNO PRE operacije
 * (npr. {@code "wal.after-append.before-sync"} pre force-a WAL-a). Može biti {@code null}
 * ako na tom mestu nema failpoint-a.
 *
 * <p>Osnovnu verziju ({@link LocalFileOps}) napravio je Ilija. Emilija je proširuje
 * (npr. {@code prefixBytes} za {@code wal.after-record-prefix}) bez promene potpisa.
 */
public interface FileOps {

    /** Upisuje SVE bajtove iz {@code buf} (u petlji, jer jedan write ne garantuje ceo bafer). */
    void writeFully(FileChannel ch, ByteBuffer buf, String failpointName) throws IOException;

    /** {@code ch.force(true)}: granica trajnosti sadržaja fajla. */
    void force(FileChannel ch, String failpointName) throws IOException;

    /**
     * Atomsko preimenovanje na istom sistemu fajlova ({@code ATOMIC_MOVE}).
     * Sa {@code replaceExisting=false} postojeće odredište je greška (finalizacija SSTable/WAL).
     * Sa {@code replaceExisting=true} zamenjuje odredište (MANIFEST.json).
     * Nikad ne prelazi na kopiranje + brisanje.
     */
    void atomicMove(Path from, Path to, boolean replaceExisting, String failpointName) throws IOException;

    /** Briše fajl ako postoji. */
    void delete(Path path) throws IOException;

    /** Force nad direktorijumom: čini trajnim promene imena (rename, create, delete) u njemu. */
    void forceDirectory(Path dir, String failpointName) throws IOException;

    /** Podrazumevana implementacija za ceo proces. */
    static FileOps local() {
        return LocalFileOps.INSTANCE;
    }
}
