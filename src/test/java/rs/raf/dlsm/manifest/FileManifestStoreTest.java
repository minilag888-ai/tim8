package rs.raf.dlsm.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rs.raf.dlsm.failpoint.Failpoints;
import rs.raf.dlsm.io.FileOps;
import rs.raf.dlsm.json.Json;
import rs.raf.dlsm.sstable.SSTableMeta;

class FileManifestStoreTest {

    @TempDir
    Path lsm;
    Path tmp;
    FileManifestStore store;

    @BeforeEach
    void setUp() throws IOException {
        tmp = Files.createDirectories(lsm.resolve("tmp"));
        store = new FileManifestStore(FileOps.local(), lsm, tmp);
    }

    @AfterEach
    void off() {
        Failpoints.setEnabledForTests(false);
    }

    private static SSTableMeta table(long id, long minSeq, long maxSeq) {
        return new SSTableMeta(id, SSTableMeta.fileName(id), "a", "z", minSeq, maxSeq, 3, 100);
    }

    @Test
    void initialPublishAndReload() throws IOException {
        Manifest m0 = store.createInitial();
        assertEquals(0, m0.generation());
        assertEquals(1, m0.nextTableId());
        Manifest m1 = new Manifest(1, 1, 2, 40, List.of(table(1, 1, 40)));
        store.publish(m1);
        FileManifestStore other = new FileManifestStore(FileOps.local(), lsm, tmp);
        assertEquals(m1, other.load());
        assertTrue(Files.readString(store.manifestFile()).contains("\"flushedThroughSequence\":40"));
        assertThrows(IOException.class, store::createInitial);
        assertThrows(IllegalStateException.class, () -> store.publish(new Manifest(1, 5, 2, 40, List.of())));
    }

    @Test
    void validationRejectsInconsistentManifests() {
        assertThrows(IOException.class, () -> FileManifestStore.validate(new Manifest(1, 1, 2, 40,
                List.of(table(1, 1, 40), table(1, 1, 40)))), "dupli ID");
        assertThrows(IOException.class, () -> FileManifestStore.validate(new Manifest(1, 1, 1, 40,
                List.of(table(1, 1, 40)))), "nextTableId nije veći od ID-ja");
        assertThrows(IOException.class, () -> FileManifestStore.validate(new Manifest(1, 1, 2, 10,
                List.of(table(1, 1, 40)))), "tabela iznad high-water mark-a");
        assertThrows(IOException.class, () -> FileManifestStore.parse("{\"formatVersion\":2}"));
        assertThrows(IOException.class, () -> FileManifestStore.parse("nije json"));
        assertThrows(IOException.class, () -> FileManifestStore.parse(
                "{\"formatVersion\":1,\"generation\":0,\"nextTableId\":1,\"sstables\":[]}"), "nedostaje polje");
    }

    @Test
    void ioErrorBeforeRenameKeepsOldManifest() throws IOException {
        store.createInitial();
        Failpoints.setEnabledForTests(true);
        Failpoints.arm("m", Json.parseObject("{\"name\":\"manifest.after-temp-sync.before-rename\",\"action\":\"IO_ERROR\"}"));
        assertThrows(IOException.class, () -> store.publish(new Manifest(1, 1, 2, 40, List.of(table(1, 1, 40)))));
        Manifest onDisk = new FileManifestStore(FileOps.local(), lsm, tmp).load();
        assertEquals(0, onDisk.generation(), "stari MANIFEST.json ostaje merodavan");
        assertTrue(Files.exists(tmp.resolve("MANIFEST-1.tmp")), "kandidat ostaje samo za čišćenje");
    }

    @Test
    void tableIdAllocatorSkipsOrphans() throws IOException {
        Path sst = Files.createDirectories(lsm.resolve("sst"));
        Files.writeString(sst.resolve(SSTableMeta.fileName(12)), "orphan");
        Files.writeString(tmp.resolve(SSTableMeta.fileName(15) + ".tmp"), "tmp");
        TableIdAllocator a = TableIdAllocator.fromDisk(1, sst, tmp);
        assertEquals(16, a.allocate());
        assertEquals(17, a.peekNext());
        assertFalse(TableIdAllocator.fromDisk(20, sst, tmp).peekNext() < 20);
    }
}
