package rs.raf.dlsm.node;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.stream.Stream;
import rs.raf.dlsm.io.FileOps;
import rs.raf.dlsm.json.Json;

/**
 * Direktorijum baze (Checkpoint 1 §14). Vlasnik: Ivona.
 *
 * <pre>
 * /var/lib/dlsm/
 * ├── LOCK            isključivo vlasništvo (FileChannel.tryLock, drži se ceo život procesa)
 * ├── node.json       {"formatVersion":1,"nodeId":"standalone"}
 * └── lsm/
 *     ├── MANIFEST.json
 *     ├── wal/
 *     ├── sst/
 *     └── tmp/
 * </pre>
 *
 * Prva inicijalizacija (§14.2): LOCK → direktorijumi (+ force roditelja) → node.json atomski.
 * Manifest generacije 0 i prvi WAL pravi oporavak (Katarina) pomoću ManifestStore-a i WAL writer-a.
 * Neprazna baza bez node.json, sa pogrešnim nodeId ili bez obaveznog direktorijuma NIKAD se ne
 * pravi ponovo kao prazna — baca se izuzetak i čvor ide u FAILED.
 */
public final class NodeDir implements Closeable {

    public static final int FORMAT_VERSION = 1;

    private final Path root;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private final boolean freshlyInitialized;

    private NodeDir(Path root, FileChannel lockChannel, FileLock lock, boolean freshlyInitialized) {
        this.root = root;
        this.lockChannel = lockChannel;
        this.lock = lock;
        this.freshlyInitialized = freshlyInitialized;
    }

    public static NodeDir open(FileOps ops, Path root, String nodeId) throws IOException {
        Files.createDirectories(root);
        FileChannel lockChannel = FileChannel.open(root.resolve("LOCK"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock lock;
        try {
            lock = lockChannel.tryLock();
        } catch (OverlappingFileLockException e) {
            lock = null;
        }
        if (lock == null) {
            lockChannel.close();
            throw new IOException("Direktorijum baze već koristi drugi proces (LOCK je zauzet)");
        }
        try {
            boolean fresh = initializeOrValidate(ops, root, nodeId);
            return new NodeDir(root, lockChannel, lock, fresh);
        } catch (IOException | RuntimeException e) {
            lock.release();
            lockChannel.close();
            throw e;
        }
    }

    private static boolean initializeOrValidate(FileOps ops, Path root, String nodeId) throws IOException {
        Path nodeJson = root.resolve("node.json");
        Path lsm = root.resolve("lsm");
        if (Files.exists(nodeJson)) {
            validateNodeJson(nodeJson, nodeId);
            for (Path d : new Path[]{lsm, lsm.resolve("wal"), lsm.resolve("sst"), lsm.resolve("tmp")}) {
                if (!Files.isDirectory(d)) {
                    throw new IOException("Nedostaje obavezni direktorijum " + root.relativize(d) + " posle inicijalizacije");
                }
            }
            return false;
        }
        // Nema node.json: dozvoljeno samo ako baza dokazano nema podataka (§14.2).
        if (Files.exists(lsm.resolve("MANIFEST.json")) || hasFiles(lsm.resolve("wal")) || hasFiles(lsm.resolve("sst"))) {
            throw new IOException("Neprazna baza nema node.json; ne pravi se ponovo kao prazna");
        }
        // Korak 1: direktorijumi i force roditelja.
        Files.createDirectories(lsm.resolve("wal"));
        Files.createDirectories(lsm.resolve("sst"));
        Files.createDirectories(lsm.resolve("tmp"));
        ops.forceDirectory(lsm, null);
        ops.forceDirectory(root, null);
        // Korak 2: node.json kroz privremeni fajl, force, atomski rename, force direktorijuma.
        Path tmp = root.resolve("node.json.tmp");
        Files.deleteIfExists(tmp);
        byte[] body = Json.write(Json.obj("formatVersion", FORMAT_VERSION, "nodeId", nodeId)).getBytes(StandardCharsets.UTF_8);
        try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ops.writeFully(ch, ByteBuffer.wrap(body), null);
            ops.force(ch, null);
        }
        ops.atomicMove(tmp, nodeJson, false, null);
        ops.forceDirectory(root, null);
        return true;
    }

    private static void validateNodeJson(Path nodeJson, String nodeId) throws IOException {
        Map<String, Object> m;
        try {
            m = Json.parseObject(Files.readString(nodeJson, StandardCharsets.UTF_8));
        } catch (Json.JsonException e) {
            throw new IOException("node.json nije ispravan JSON: " + e.getMessage());
        }
        if (!Long.valueOf(FORMAT_VERSION).equals(m.get("formatVersion"))) {
            throw new IOException("Nepodržana verzija node.json: " + m.get("formatVersion"));
        }
        if (!nodeId.equals(m.get("nodeId"))) {
            throw new IOException("node.json pripada čvoru " + m.get("nodeId") + ", a ovaj čvor je " + nodeId);
        }
    }

    private static boolean hasFiles(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return false;
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.findAny().isPresent();
        }
    }

    public Path root() {
        return root;
    }

    public Path lsmDir() {
        return root.resolve("lsm");
    }

    public Path walDir() {
        return lsmDir().resolve("wal");
    }

    public Path sstDir() {
        return lsmDir().resolve("sst");
    }

    public Path tmpDir() {
        return lsmDir().resolve("tmp");
    }

    public Path manifestFile() {
        return lsmDir().resolve("MANIFEST.json");
    }

    /** Da li je node.json upravo napravljen u ovom pokretanju. */
    public boolean freshlyInitialized() {
        return freshlyInitialized;
    }

    @Override
    public void close() throws IOException {
        try {
            lock.release();
        } finally {
            lockChannel.close();
        }
    }
}
