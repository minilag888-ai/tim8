package rs.raf.dlsm.failpoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rs.raf.dlsm.engine.RequestScope;
import rs.raf.dlsm.io.FileOps;
import rs.raf.dlsm.json.Json;

class FailpointsTest {

    @TempDir
    Path dir;

    @BeforeEach
    void on() {
        Failpoints.setEnabledForTests(true);
    }

    @AfterEach
    void off() {
        Failpoints.setEnabledForTests(false);
    }

    private static Map<String, Object> rule(String json) {
        return Json.parseObject(json);
    }

    @Test
    void disabledFailpointHasNoEffect() {
        Failpoints.arm("t", rule("{\"name\":\"wal.before-append\",\"action\":\"IO_ERROR\"}"));
        Failpoints.setEnabledForTests(false);
        Failpoints.hit("wal.before-append");
    }

    @Test
    void ioErrorBecomesRealIoExceptionInFileOps() throws Exception {
        Failpoints.arm("io", rule("{\"name\":\"wal.after-append.before-sync\",\"action\":\"IO_ERROR\"}"));
        Path f = dir.resolve("x");
        try (FileChannel ch = FileChannel.open(f, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            FileOps.local().writeFully(ch, ByteBuffer.wrap(new byte[]{1}), null);
            assertThrows(IOException.class, () -> FileOps.local().force(ch, "wal.after-append.before-sync"));
            FileOps.local().force(ch, "wal.after-append.before-sync"); // maxTriggers=1: drugi put prolazi
        }
        assertEquals(Failpoints.State.EXHAUSTED, Failpoints.state("io"));
    }

    @Test
    void afterHitsAndRequestIdFilter() {
        Failpoints.arm("f", rule("{\"name\":\"wal.before-append\",\"action\":\"IO_ERROR\","
                + "\"afterHits\":1,\"filters\":{\"requestId\":\"put-7\"}}"));
        Failpoints.hit("wal.before-append"); // nema requestId → ne odgovara
        RequestScope scope = RequestScope.enter("put-7");
        try {
            Failpoints.hit("wal.before-append", Map.of()); // prvi pogodak se preskače (afterHits=1)
            assertThrows(InjectedIoException.class, () -> Failpoints.hit("wal.before-append", Map.of()));
        } finally {
            scope.close();
        }
    }

    @Test
    void pauseBlocksUntilRelease() throws Exception {
        Failpoints.arm("p", rule("{\"name\":\"wal.after-sync.before-memtable\",\"action\":\"PAUSE\"}"));
        AtomicReference<Throwable> err = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                Failpoints.hit("wal.after-sync.before-memtable");
            } catch (Throwable e) {
                err.set(e);
            }
        });
        t.start();
        for (int i = 0; i < 300 && Failpoints.state("p") != Failpoints.State.TRIGGERED; i++) {
            Thread.sleep(10);
        }
        assertEquals(Failpoints.State.TRIGGERED, Failpoints.state("p"));
        assertTrue(t.isAlive(), "operacija mora da čeka na release");
        assertTrue(Failpoints.release("p"));
        t.join(5000);
        assertFalse(t.isAlive());
        assertEquals(null, err.get());
        assertEquals(Failpoints.State.EXHAUSTED, Failpoints.state("p"));
    }

    @Test
    void pauseTimeoutContinuesWithError() {
        Failpoints.arm("pt", rule("{\"name\":\"wal.after-sync.before-memtable\",\"action\":\"PAUSE\",\"pauseTimeoutMs\":50}"));
        assertThrows(FailpointTimeoutException.class, () -> Failpoints.hit("wal.after-sync.before-memtable"));
    }

    @Test
    void prefixParameterIsVisibleBeforeHit() {
        Failpoints.arm("pre", rule("{\"name\":\"wal.after-record-prefix\",\"action\":\"CRASH\",\"parameters\":{\"prefixBytes\":17}}"));
        assertEquals(17, Failpoints.parameter("wal.after-record-prefix", "prefixBytes", Map.of()).orElseThrow());
        assertFalse(Failpoints.parameter("wal.before-append", "prefixBytes", Map.of()).isPresent());
    }

    @Test
    void invalidRulesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> Failpoints.arm("a", rule("{\"name\":\"nema.takvog\",\"action\":\"CRASH\"}")));
        assertThrows(IllegalArgumentException.class,
                () -> Failpoints.arm("b", rule("{\"name\":\"wal.after-sync.before-memtable\",\"action\":\"IO_ERROR\"}")));
        assertThrows(IllegalArgumentException.class, () -> Failpoints.arm("c", rule("{\"name\":\"wal.before-append\",\"action\":\"NAP\"}")));
        assertEquals(Failpoints.State.UNKNOWN, Failpoints.state("a"));
    }

    @Test
    void catalogHasAll21AndBootstrapParses() {
        assertEquals(21, FailpointCatalog.ALL.size());
        String json = "[{\"token\":\"recovery-crash-1\",\"name\":\"recovery.after-manifest-load\",\"action\":\"CRASH\","
                + "\"afterHits\":0,\"maxTriggers\":1,\"filters\":{}}]";
        String b64 = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(1, Failpoints.bootstrapFromEnv(b64));
        assertEquals(Failpoints.State.ARMED, Failpoints.state("recovery-crash-1"));
        assertThrows(IllegalArgumentException.class, () -> Failpoints.bootstrapFromEnv("!!!"));
        assertThrows(IllegalArgumentException.class,
                () -> Failpoints.bootstrapFromEnv(Base64.getEncoder().encodeToString("{}".getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void tempDirExists() {
        assertTrue(Files.isDirectory(dir));
    }
}
