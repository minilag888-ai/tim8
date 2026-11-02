package rs.raf.dlsm.testkit;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import rs.raf.dlsm.json.Json;

/**
 * Pravi produkcioni proces baze kao proces dete nad JEDNIM izolovanim direktorijumom (Project Info §25.2,
 * varijanta 2). Restart = nov proces nad istim direktorijumom; pad = destroyForcibly (SIGKILL) ili
 * failpoint CRASH (Runtime.halt). Vlasnik: Emilija.
 */
public final class Node implements AutoCloseable {

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    private final Ctx ctx;
    private final String name;
    private final Path dataDir;
    private final Map<String, String> env = new LinkedHashMap<>();
    private Process proc;
    private int publicPort;
    private int adminPort;
    private int starts;

    Node(Ctx ctx, String name, Path dataDir) {
        this.ctx = ctx;
        this.name = name;
        this.dataDir = dataDir;
        env.put("MEMTABLE_MAX_BYTES", Long.toString(1L << 20));
    }

    public Path dataDir() {
        return dataDir;
    }

    public Path lsm() {
        return dataDir.resolve("lsm");
    }

    /** Podešavanje koje važi za sledeća pokretanja (npr. MEMTABLE_MAX_BYTES). */
    public Node env(String key, String value) {
        env.put(key, value);
        return this;
    }

    public Node memtable(long bytes) {
        return env("MEMTABLE_MAX_BYTES", Long.toString(bytes));
    }

    // ================================================================ životni ciklus

    /** Pokreće proces i čeka spremnost (najviše 20 s). */
    public Node start() throws Exception {
        launch(null);
        awaitReady();
        return this;
    }

    /** Pokreće sa bootstrap failpoint pravilima (JSON niz) i NE čeka spremnost. */
    public Node launch(String bootstrapRulesJson) throws IOException {
        if (alive()) {
            throw new IllegalStateException(name + " već radi");
        }
        publicPort = freePort();
        adminPort = freePort();
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        List<String> cmd = new ArrayList<>(List.of(java.toString(), "-XX:TieredStopAtLevel=1", "-XX:+UseSerialGC",
                "-Xmx512m", "-cp", System.getProperty("java.class.path"), "rs.raf.dlsm.Main"));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().remove("DLSM_TEST_BOOTSTRAP_FAILPOINTS_B64");
        pb.environment().putAll(env);
        pb.environment().put("DATA_DIR", dataDir.toString());
        pb.environment().put("PUBLIC_PORT", Integer.toString(publicPort));
        pb.environment().put("ADMIN_PORT", Integer.toString(adminPort));
        pb.environment().put("NODE_ID", env.getOrDefault("NODE_ID", "standalone"));
        pb.environment().put("TEST_MODE", "true");
        if (bootstrapRulesJson != null) {
            pb.environment().put("DLSM_TEST_BOOTSTRAP_FAILPOINTS_B64",
                    Base64.getEncoder().encodeToString(bootstrapRulesJson.getBytes(StandardCharsets.UTF_8)));
        }
        Path log = ctx.artifactDir().resolve(name + ".log");
        Files.createDirectories(log.getParent());
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
        starts++;
        ctx.trace("start " + name + " #" + starts + (bootstrapRulesJson == null ? "" : " bootstrap=" + bootstrapRulesJson));
        proc = pb.start();
        return this;
    }

    public void awaitReady() throws Exception {
        ctx.await(() -> {
            if (!alive()) {
                throw new AssertionError(name + " je završio pre spremnosti (izlazni kod " + proc.exitValue() + ")");
            }
            return admin("GET", "/health/ready", null).status() == 200;
        }, 20_000, name + " spreman");
    }

    /** Čeka da proces ostane živ u stanju FAILED (oporavak je odbio stanje). Vraća kod fatalne greške. */
    public String awaitFailed() throws Exception {
        String[] code = new String[1];
        ctx.await(() -> {
            if (!alive()) {
                throw new AssertionError(name + " je završio umesto da prijavi FAILED");
            }
            Resp r = admin("GET", "/health/ready", null);
            if (r.status() == 200) {
                throw new AssertionError(name + " je postao spreman, a očekivan je FAILED");
            }
            if (r.status() == 503 && "FAILED".equals(r.get("lifecycle"))) {
                @SuppressWarnings("unchecked")
                Map<String, Object> fe = (Map<String, Object>) r.get("lastFatalError");
                code[0] = fe == null ? null : (String) fe.get("code");
                return true;
            }
            return false;
        }, 20_000, name + " FAILED");
        return code[0];
    }

    /** Čeka da proces sam završi (npr. failpoint CRASH). */
    public void awaitExit() throws Exception {
        if (!proc.waitFor(20, TimeUnit.SECONDS)) {
            throw new AssertionError(name + " nije završio u roku (očekivan CRASH)");
        }
        ctx.trace(name + " završio, kod " + proc.exitValue());
    }

    /** Nagli prekid (SIGKILL), bez urednog gašenja. */
    public void kill() throws InterruptedException {
        if (proc != null && proc.isAlive()) {
            proc.destroyForcibly();
            proc.waitFor(10, TimeUnit.SECONDS);
            ctx.trace("SIGKILL " + name);
        }
    }

    public Node restart() throws Exception {
        kill();
        return start();
    }

    public boolean alive() {
        return proc != null && proc.isAlive();
    }

    @Override
    public void close() {
        try {
            kill();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ================================================================ javni API

    public Resp put(String rid, String key, byte[] value) {
        return call("PUT", publicPort, "/v1/kv/" + key,
                Json.write(Json.obj("value", Base64.getEncoder().encodeToString(value))), rid, 10_000);
    }

    public Resp put(String rid, String key, String value) {
        return put(rid, key, value.getBytes(StandardCharsets.UTF_8));
    }

    public Resp get(String rid, String key) {
        return call("GET", publicPort, "/v1/kv/" + key, null, rid, 10_000);
    }

    public Resp delete(String rid, String key) {
        return call("DELETE", publicPort, "/v1/kv/" + key, null, rid, 10_000);
    }

    public Resp scan(String rid, String start, String end, int limit) {
        return call("GET", publicPort, "/v1/scan?start=" + start + "&end=" + end + "&limit=" + limit, null, rid, 10_000);
    }

    public CompletableFuture<Resp> async(java.util.function.Supplier<Resp> call) {
        return CompletableFuture.supplyAsync(call);
    }

    /** Sirov zahtev ka javnom API-ju (za fazu validacije: neispravno telo, zaglavlja, parametri). */
    public Resp raw(String method, String pathAndQuery, String body, Map<String, String> headers) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + publicPort + pathAndQuery))
                .timeout(Duration.ofSeconds(15))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(b::header);
        try {
            HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
            Map<String, Object> parsed = null;
            try {
                parsed = Json.parseObject(r.body());
            } catch (RuntimeException ignored) {
                // telo nije JSON objekat
            }
            return new Resp(r.statusCode(), parsed, r.body());
        } catch (IOException e) {
            return new Resp(0, null, "no response: " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Resp(0, null, "interrupted");
        }
    }

    // ================================================================ admin API

    public Resp admin(String method, String path, String body) {
        return call(method, adminPort, path, body, null, 10_000);
    }

    public Map<String, Object> status() {
        Resp r = admin("GET", "/admin/v1/storage/status", null);
        if (!r.ok()) {
            throw new AssertionError("status nije dostupan: " + r);
        }
        return r.body();
    }

    public Resp flush() {
        return call("POST", adminPort, "/admin/v1/storage/flush", "{}", null, 10_000);
    }

    public Resp compact() {
        return call("POST", adminPort, "/admin/v1/storage/compact", "{}", null, 10_000);
    }

    public long resetStats() {
        return admin("POST", "/admin/v1/storage/stats/reset", "{}").num("resetEpoch");
    }

    @SuppressWarnings("unchecked")
    public long stat(String name) {
        return ((Number) ((Map<String, Object>) status().get("stats")).get(name)).longValue();
    }

    public void arm(String token, String ruleJson) {
        Resp r = admin("PUT", "/admin/v1/failpoints/" + token, ruleJson);
        if (!r.ok()) {
            throw new AssertionError("failpoint " + token + " nije aktiviran: " + r);
        }
        ctx.trace("arm " + token + " " + ruleJson);
    }

    public String fpState(String token) {
        Resp r = admin("GET", "/admin/v1/failpoints/" + token, null);
        return r.ok() ? (String) r.get("state") : "UNREACHABLE";
    }

    public void awaitTriggered(String token) throws Exception {
        ctx.await(() -> "TRIGGERED".equals(fpState(token)), 15_000, "failpoint " + token + " TRIGGERED");
    }

    public void release(String token) {
        admin("POST", "/admin/v1/failpoints/" + token + "/release", "{}");
        ctx.trace("release " + token);
    }

    // ================================================================ HTTP

    private Resp call(String method, int port, String path, String body, String rid, int timeoutMs) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofMillis(timeoutMs + 5_000L))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (rid != null) {
            b.header("X-Request-Id", rid);
        }
        b.header("X-Request-Timeout-Ms", Integer.toString(timeoutMs));
        try {
            HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
            Map<String, Object> parsed = null;
            try {
                parsed = Json.parseObject(r.body());
            } catch (RuntimeException ignored) {
                // telo nije JSON objekat
            }
            return new Resp(r.statusCode(), parsed, r.body());
        } catch (IOException e) {
            return new Resp(0, null, "no response: " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Resp(0, null, "interrupted");
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            s.setReuseAddress(true);
            return s.getLocalPort();
        }
    }
}
