package rs.raf.dlsm.testkit;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.stream.Stream;
import rs.raf.dlsm.json.Json;

/**
 * Kontekst jednog scenarija: seed, izolovan radni direktorijum, trag operacija, procesi i provere.
 * Vlasnik: Emilija. Provera koja ne prođe baca AssertionError sa opisom; runner ga upisuje u izveštaj.
 */
public final class Ctx implements AutoCloseable {

    private final String caseId;
    private final long seed;
    private final Path work;
    private final Path artifacts;
    private final List<String> trace = new ArrayList<>();
    private final List<Node> nodes = new ArrayList<>();
    private final Random rnd;
    private String phase = "setup";
    private int requestCounter;

    public Ctx(String caseId, long seed, Path work, Path artifacts) throws IOException {
        this.caseId = caseId;
        this.seed = seed;
        this.work = Files.createDirectories(work);
        this.artifacts = Files.createDirectories(artifacts);
        this.rnd = new Random(seed ^ caseId.hashCode());
    }

    public long seed() {
        return seed;
    }

    public Random rnd() {
        return rnd;
    }

    public Path artifactDir() {
        return artifacts;
    }

    public String phase() {
        return phase;
    }

    public void phase(String p) {
        this.phase = p;
        trace("== faza: " + p);
    }

    /** Deterministički jedinstven ID zahteva iz ID-ja scenarija, seed-a i rednog broja operacije. */
    public String rid() {
        return caseId + "-" + (requestCounter++);
    }

    /** Nov čvor nad novim praznim direktorijumom. */
    public Node node(String name) throws IOException {
        return node(name, Files.createDirectories(work.resolve(name)));
    }

    public Node node(String name, Path dataDir) {
        Node n = new Node(this, name, dataDir);
        nodes.add(n);
        return n;
    }

    public Path work() {
        return work;
    }

    public synchronized void trace(String line) {
        trace.add(System.currentTimeMillis() + " " + line);
    }

    public List<String> traceLines() {
        return trace;
    }

    // ================================================================ provere

    public void check(boolean cond, String what) {
        if (!cond) {
            throw new AssertionError("[" + phase + "] " + what);
        }
        trace("ok: " + what);
    }

    public void eq(Object expected, Object actual, String what) {
        boolean same = expected instanceof byte[] e && actual instanceof byte[] a ? Arrays.equals(e, a)
                : Objects.equals(expected, actual);
        if (!same) {
            throw new AssertionError("[" + phase + "] " + what + ": očekivano <" + show(expected) + "> a dobijeno <"
                    + show(actual) + ">");
        }
        trace("ok: " + what + " = " + show(actual));
    }

    private static String show(Object o) {
        if (o instanceof byte[] b) {
            return "bytes(" + b.length + "):" + Base64.getEncoder().encodeToString(b.length > 24 ? Arrays.copyOf(b, 24) : b);
        }
        return String.valueOf(o);
    }

    /** Ograničeno periodično proveravanje vidljivog uslova; nikad fiksno čekanje kao dokaz. */
    public void await(Callable<Boolean> cond, long timeoutMs, String what) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (Boolean.TRUE.equals(cond.call())) {
                trace("dostignuto: " + what);
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("[" + phase + "] nije dostignuto za " + timeoutMs + " ms: " + what);
    }

    // ================================================================ logičko stanje

    /** GET i SCAN za svaki ključ modela i za date dodatne ključeve moraju tačno da odgovaraju modelu. */
    public void assertModel(Node n, RefModel model, Iterable<String> extraKeys) {
        for (String k : model.snapshot().keySet()) {
            assertGet(n, model, k);
        }
        if (extraKeys != null) {
            for (String k : extraKeys) {
                assertGet(n, model, k);
            }
        }
        List<Map.Entry<String, byte[]>> all = scanAll(n);
        List<Map.Entry<String, byte[]>> expected = model.scan("-", "~~~~~~~~", Integer.MAX_VALUE);
        eq(expected.size(), all.size(), "broj živih ključeva u SCAN-u");
        for (int i = 0; i < all.size(); i++) {
            eq(expected.get(i).getKey(), all.get(i).getKey(), "SCAN ključ #" + i);
            eq(expected.get(i).getValue(), all.get(i).getValue(), "SCAN vrednost za " + all.get(i).getKey());
        }
    }

    public void assertGet(Node n, RefModel model, String k) {
        byte[] exp = model.get(k);
        Resp r = n.get(rid(), k);
        if (exp == null) {
            eq(404, r.status(), "GET " + k + " je NOT_FOUND");
            eq("NOT_FOUND", r.code(), "kod za " + k);
        } else {
            eq(200, r.status(), "GET " + k + " status");
            eq(exp, r.value(), "GET " + k + " vrednost");
        }
    }

    /** Ceo prostor ključeva kroz ograničena skeniranja od po 1000. */
    public List<Map.Entry<String, byte[]>> scanAll(Node n) {
        List<Map.Entry<String, byte[]>> out = new ArrayList<>();
        String start = "-";
        while (true) {
            Resp r = n.scan(rid(), start, "~~~~~~~~", 1000);
            eq(200, r.status(), "SCAN status");
            List<Object> items = r.list("items");
            for (Object o : items) {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) o;
                String key = (String) m.get("key");
                if (!out.isEmpty()) {
                    check(key.compareTo(out.get(out.size() - 1).getKey()) > 0, "SCAN je strogo rastući kod " + key);
                }
                out.add(Map.entry(key, Base64.getDecoder().decode((String) m.get("value"))));
            }
            if (items.size() < 1000) {
                return out;
            }
            start = out.get(out.size() - 1).getKey() + "-";
        }
    }

    /** Lista ključ=vrednost iz SCAN odgovora, za poređenje sa tačnom listom. */
    public List<String> scanList(Node n, String start, String end, int limit) {
        Resp r = n.scan(rid(), start, end, limit);
        eq(200, r.status(), "SCAN [" + start + "," + end + ") status");
        List<String> out = new ArrayList<>();
        for (Object o : r.list("items")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) o;
            out.add(m.get("key") + "=" + new String(Base64.getDecoder().decode((String) m.get("value")),
                    StandardCharsets.UTF_8));
        }
        eq((long) out.size(), r.num("count"), "SCAN count");
        eq("[{partitionId=0, epoch=0}]", String.valueOf(r.get("observedPartitions")), "observedPartitions");
        return out;
    }

    public void putOk(Node n, RefModel m, String k, byte[] v) {
        Resp r = n.put(rid(), k, v);
        eq(200, r.status(), "PUT " + k);
        if (m != null) {
            m.put(k, v);
        }
    }

    public void putOk(Node n, RefModel m, String k, String v) {
        putOk(n, m, k, v.getBytes(StandardCharsets.UTF_8));
    }

    public void deleteOk(Node n, RefModel m, String k) {
        Resp r = n.delete(rid(), k);
        eq(200, r.status(), "DELETE " + k);
        if (m != null) {
            m.delete(k);
        }
    }

    public Map<String, Object> flushPublished(Node n) {
        Resp r = n.flush();
        eq(200, r.status(), "ručni flush");
        return r.body();
    }

    // ================================================================ fajlovi (samo dok proces NE radi)

    /** Kopija celog direktorijuma baze (za nezavisne podslučajeve). */
    public Path cloneDir(Path src, String name) throws IOException {
        Path dst = work.resolve(name);
        try (Stream<Path> s = Files.walk(src)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                Path t = dst.resolve(src.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(t);
                } else if (!p.getFileName().toString().equals("LOCK")) {
                    Files.copy(p, t, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        trace("klon " + src.getFileName() + " → " + name);
        return dst;
    }

    /** Menja tačno jedan bajt (XOR maska) i čuva dužinu fajla. */
    public void flipByte(Path file, long offset, int mask) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
            raf.seek(offset);
            int old = raf.read();
            raf.seek(offset);
            raf.write(old ^ mask);
            trace("izmena bajta " + file.getFileName() + " @" + offset + ": " + old + " → " + (old ^ mask)
                    + " (dužina " + raf.length() + ")");
        }
    }

    public void truncate(Path file, long size) throws IOException {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.WRITE)) {
            ch.truncate(size);
        }
        trace("skraćeno " + file.getFileName() + " na " + size);
    }

    public static List<Path> list(Path dir, String suffix) throws IOException {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(p -> p.getFileName().toString().endsWith(suffix)).sorted().forEach(out::add);
        }
        return out;
    }

    public static String json(Object... kv) {
        return Json.write(Json.obj(kv));
    }

    @Override
    public void close() throws IOException {
        for (Node n : nodes) {
            n.close();
        }
        Files.write(artifacts.resolve("trace.txt"), trace, StandardCharsets.UTF_8);
    }
}
