package rs.raf.dlsm.node;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rs.raf.dlsm.io.FileOps;

class NodeDirTest {

    @TempDir
    Path root;

    @Test
    void firstStartCreatesLayoutAndNodeJson() throws IOException {
        try (NodeDir d = NodeDir.open(FileOps.local(), root, "standalone")) {
            assertTrue(d.freshlyInitialized());
            assertTrue(Files.isDirectory(d.walDir()));
            assertTrue(Files.isDirectory(d.sstDir()));
            assertTrue(Files.isDirectory(d.tmpDir()));
            assertTrue(Files.readString(root.resolve("node.json")).contains("\"nodeId\":\"standalone\""));
        }
        try (NodeDir d = NodeDir.open(FileOps.local(), root, "standalone")) {
            assertFalse(d.freshlyInitialized());
        }
    }

    @Test
    void secondOwnerIsRejected() throws IOException {
        try (NodeDir d = NodeDir.open(FileOps.local(), root, "standalone")) {
            assertThrows(IOException.class, () -> NodeDir.open(FileOps.local(), root, "standalone"));
            assertTrue(Files.isDirectory(d.lsmDir()), "prvi vlasnik ostaje netaknut");
        }
    }

    @Test
    void wrongNodeIdFails() throws IOException {
        NodeDir.open(FileOps.local(), root, "standalone").close();
        assertThrows(IOException.class, () -> NodeDir.open(FileOps.local(), root, "storage-1"));
    }

    @Test
    void nonEmptyDatabaseWithoutNodeJsonIsNotReinitialized() throws IOException {
        NodeDir.open(FileOps.local(), root, "standalone").close();
        Files.writeString(root.resolve("lsm/wal/wal-00000000000000000001.log"), "x");
        Files.delete(root.resolve("node.json"));
        assertThrows(IOException.class, () -> NodeDir.open(FileOps.local(), root, "standalone"));
        assertFalse(Files.exists(root.resolve("node.json")));
    }

    @Test
    void missingRequiredDirectoryAfterInitFails() throws IOException {
        NodeDir.open(FileOps.local(), root, "standalone").close();
        Files.delete(root.resolve("lsm/sst"));
        assertThrows(IOException.class, () -> NodeDir.open(FileOps.local(), root, "standalone"));
    }
}
