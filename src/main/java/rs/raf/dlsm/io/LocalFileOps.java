package rs.raf.dlsm.io;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import rs.raf.dlsm.failpoint.Failpoints;
import rs.raf.dlsm.failpoint.InjectedIoException;

/** Osnovna implementacija {@link FileOps} nad lokalnim Linux sistemom fajlova. */
final class LocalFileOps implements FileOps {

    static final LocalFileOps INSTANCE = new LocalFileOps();

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows");

    private LocalFileOps() {
    }

    @Override
    public void writeFully(FileChannel ch, ByteBuffer buf, String failpointName) throws IOException {
        checkpoint(failpointName);
        while (buf.hasRemaining()) {
            int written = ch.write(buf);
            if (written <= 0) {
                throw new IOException("Upis nije napredovao (write vratio " + written + ")");
            }
        }
    }

    @Override
    public void force(FileChannel ch, String failpointName) throws IOException {
        checkpoint(failpointName);
        ch.force(true);
    }

    @Override
    public void atomicMove(Path from, Path to, boolean replaceExisting, String failpointName) throws IOException {
        checkpoint(failpointName);
        if (replaceExisting) {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } else {
            if (Files.exists(to)) {
                throw new FileAlreadyExistsException(to.toString());
            }
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        }
    }

    @Override
    public void delete(Path path) throws IOException {
        Files.deleteIfExists(path);
    }

    @Override
    public void forceDirectory(Path dir, String failpointName) throws IOException {
        checkpoint(failpointName);
        if (WINDOWS) {
            // Windows ne dozvoljava otvaranje direktorijuma kao FileChannel (AccessDeniedException).
            // Baza se ocenjuje u Linux Docker kontejneru, gde se force direktorijuma stvarno izvršava;
            // na Windows-u se preskače samo da bi razvoj i JUnit testovi radili lokalno.
            return;
        }
        // Na Linuxu se direktorijum otvara za čitanje i nad njim se poziva force,
        // čime promene imena (rename, create, delete) postaju trajne.
        try (FileChannel ch = FileChannel.open(dir, StandardOpenOption.READ)) {
            ch.force(true);
        }
    }

    /** Proverava failpoint pre operacije; IO_ERROR postaje pravi IOException. */
    private static void checkpoint(String failpointName) throws IOException {
        if (failpointName == null) {
            return;
        }
        try {
            Failpoints.hit(failpointName, Map.of());
        } catch (InjectedIoException e) {
            throw e.getCause();
        }
    }
}
