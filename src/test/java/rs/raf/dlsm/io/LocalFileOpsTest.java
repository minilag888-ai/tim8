package rs.raf.dlsm.io;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalFileOpsTest {

    @TempDir
    Path dir;

    @Test
    void writeForceMoveAndForceDirectory() throws Exception {
        FileOps ops = FileOps.local();
        Path tmp = dir.resolve("a.tmp");
        Path fin = dir.resolve("a.bin");
        try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ops.writeFully(ch, ByteBuffer.wrap(new byte[]{1, 2, 3}), null);
            ops.force(ch, null);
        }
        ops.atomicMove(tmp, fin, false, null);
        ops.forceDirectory(dir, null);
        assertFalse(Files.exists(tmp));
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(fin));
    }

    @Test
    void moveWithoutReplaceRejectsExistingTarget() throws Exception {
        Path a = Files.write(dir.resolve("a"), new byte[]{1});
        Path b = Files.write(dir.resolve("b"), new byte[]{2});
        assertThrows(FileAlreadyExistsException.class, () -> FileOps.local().atomicMove(a, b, false, null));
        assertTrue(Files.exists(a));
    }
}
