package rs.raf.dlsm.testkit;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import rs.raf.dlsm.failpoint.FailpointCatalog;
import rs.raf.dlsm.json.Json;
import rs.raf.dlsm.manifest.ManifestInspector;
import rs.raf.dlsm.sstable.SSTableInspector;
import rs.raf.dlsm.wal.WalInspector;

/**
 * CP1-MDL-001 (test modela sa zadatim seed-om) i CP1-MDL-002 (kampanja padova i failpoint-a), Checkpoint 1 §29.6.
 * Vlasnik: Emilija.
 *
 * <p>Referentni model ({@link RefModel}) je obična uređena mapa: ne deli nijednu liniju koda sa WAL-om,
 * MemTable-om, SSTable-om ni spajanjem skeniranja. Sva slučajnost dolazi iz {@code new Random(seed)}.
 */
public final class ModelScenarios {

    private static final PrintStream NULL = new PrintStream(OutputStream.nullOutputStream());

    private ModelScenarios() {
    }

    // ================================================================ zajedničko

    static byte[] randomValue(Random r, byte[] last) {
        int kind = r.nextInt(10);
        if (kind == 0) {
            return new byte[0];                       // prazna vrednost
        }
        if (kind == 1 && last != null) {
            return last.clone();                      // ponovljena vrednost
        }
        int len = kind < 7 ? 1 + r.nextInt(32) : kind < 9 ? 33 + r.nextInt(300) : 1000 + r.nextInt(3000);
        byte[] v = new byte[len];
        r.nextBytes(v);                               // binarna vrednost
        return v;
    }

    static RefModel observed(Ctx c, Node n) {
        RefModel m = new RefModel();
        for (Map.Entry<String, byte[]> e : c.scanAll(n)) {
            m.put(e.getKey(), e.getValue());
        }
        return m;
    }

    @SuppressWarnings("unchecked")
    static String physicalSnapshot(Node n) {
        Map<String, Object> st = n.status();
        long walBytes = 0;
        for (Object o : (List<Object>) st.get("walSegments")) {
            walBytes += ((Number) ((Map<String, Object>) o).get("sizeBytes")).longValue();
        }
        Map<String, Object> man = (Map<String, Object>) st.get("manifest");
        return "next=" + st.get("nextSequence") + " walBytes=" + walBytes + " active=" + st.get("activeMemtable")
                + " immutable=" + st.get("immutableMemtable") + " manifestGen=" + man.get("generation");
    }

    static void inspectAll(Ctx c, Node n) throws Exception {
        c.eq(0, ManifestInspector.inspect(n.lsm(), NULL), "inspect manifest");
        c.eq(0, WalInspector.inspect(n.lsm().resolve("wal"), NULL), "inspect wal");
        for (Path t : Ctx.list(n.lsm().resolve("sst"), ".sst")) {
            c.eq(0, SSTableInspector.inspect(n.lsm().resolve("sst"), t.getFileName().toString(), NULL),
                    "inspect sstable " + t.getFileName());
        }
    }

    // ================================================================ MDL-001

    /** Test modela mašine stanja sa zadatim seed-om. */
    public static void mdl001(Ctx c) throws Exception {
        final int ops = 10_000;
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < 512; i++) {
            keys.add(String.format("k%03d", i));
        }
        Random r = new Random(c.seed());
        // Raspored održavanja sa seed-om, ograničen minimumima: 20 flush, 5 compaction, 10 restarta.
        TreeMap<Integer, String> plan = new TreeMap<>();
        for (int i = 1; i <= 20; i++) {
            plan.put(i * 480 + r.nextInt(20), "FLUSH");
        }
        for (int i = 1; i <= 5; i++) {
            plan.put(i * 1900 + 7 + r.nextInt(40), "COMPACT");
        }
        for (int i = 1; i <= 10; i++) {
            plan.put(i * 950 + 3 + r.nextInt(30), "RESTART");
        }
        Node n = c.node("n").memtable(32 * 1024).start();
        RefModel m = new RefModel();
        List<String> recent = new ArrayList<>();
        int flushes = 0;
        int compactions = 0;
        int restarts = 0;
        long manualCompactionsThisProcess = 0;
        long autoCompactions = 0;
        byte[] last = null;
        for (int i = 0; i < ops; i++) {
            c.phase("op " + i);
            int p = r.nextInt(100);
            String k = keys.get(r.nextInt(keys.size()));
            String op;
            if (p < 45) {
                byte[] v = randomValue(r, last);
                last = v;
                op = "PUT " + k + " len=" + v.length;
                c.putOk(n, m, k, v);
            } else if (p < 65) {
                op = "DELETE " + k;
                c.deleteOk(n, m, k);
            } else if (p < 85) {
                op = "GET " + k;
                c.assertGet(n, m, k);
            } else {
                String b = keys.get(r.nextInt(keys.size()));
                String lo = k.compareTo(b) <= 0 ? k : b;
                String hi = (k.compareTo(b) <= 0 ? b : k) + "~";
                int limit = 1 + r.nextInt(r.nextBoolean() ? 20 : 1000);
                op = "SCAN [" + lo + "," + hi + ") " + limit;
                List<String> exp = new ArrayList<>();
                for (Map.Entry<String, byte[]> e : m.scan(lo, hi, limit)) {
                    exp.add(e.getKey() + "=" + Base64.getEncoder().encodeToString(e.getValue()));
                }
                Resp sr = n.scan(c.rid(), lo, hi, limit);
                c.eq(200, sr.status(), "SCAN status");
                List<String> got = new ArrayList<>();
                for (Object o : sr.list("items")) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> e = (Map<String, Object>) o;
                    got.add(e.get("key") + "=" + e.get("value"));
                }
                c.eq(exp, got, "SCAN rezultat");
            }
            recent.add(i + ": " + op);
            if (recent.size() > 100) {
                recent.remove(0);
            }
            String action = plan.get(i);
            if (action == null) {
                continue;
            }
            c.trace("op " + i + " održavanje " + action + " digest=" + m.digest());
            switch (action) {
                case "FLUSH" -> {
                    c.eq(200, n.flush().status(), "ručni flush");
                    flushes++;
                }
                case "COMPACT" -> {
                    Resp cr = n.compact();
                    if ("NO_OP".equals(cr.get("result"))) {
                        n.flush();
                        cr = n.compact();
                    }
                    if ("NO_OP".equals(cr.get("result"))) { // jedna tabela: napravi drugu pa ponovi
                        c.putOk(n, m, keys.get(0), "compaction-uslov");
                        n.flush();
                        cr = n.compact();
                    }
                    c.eq("PUBLISHED", cr.get("result"), "ručni compaction koji ispunjava uslove");
                    compactions++;
                    manualCompactionsThisProcess++;
                }
                default -> {
                    FlushScenarios.awaitIdle(c, n);
                    autoCompactions += Math.max(0, n.stat("compactionPublications") - manualCompactionsThisProcess);
                    manualCompactionsThisProcess = 0;
                    n.restart();
                    restarts++;
                    c.phase("posle restarta " + restarts);
                    c.assertModel(n, m, keys);
                }
            }
        }
        FlushScenarios.awaitIdle(c, n);
        autoCompactions += Math.max(0, n.stat("compactionPublications") - manualCompactionsThisProcess);
        c.phase("završna provera");
        c.check(flushes >= 20 && compactions >= 5 && restarts >= 10,
                "pokrivenost održavanja: flush=" + flushes + " compaction=" + compactions + " restart=" + restarts);
        c.check(autoCompactions >= 1, "opažen automatski compaction (" + autoCompactions + ")");
        c.assertModel(n, m, keys);
        Files.writeString(c.artifactDir().resolve("mdl-001-last-100-ops.txt"), String.join("\n", recent));

        c.phase("faza validacije");
        validationPhase(c, n, m, keys);

        n.restart();
        c.assertModel(n, m, keys);
        n.kill();
        inspectAll(c, n);
    }

    private static void validationPhase(Ctx c, Node n, RefModel m, List<String> keys) {
        String good = Json.write(Json.obj("value", "dmFsdWU="));
        String tooLarge = Json.write(Json.obj("value", Base64.getEncoder().encodeToString(new byte[262_145])));
        Map<String, String> h = Map.of("X-Request-Id", "val-1");
        List<Object[]> cases = new ArrayList<>();
        cases.add(new Object[]{"PUT", "/v1/kv/k001", Json.write(Json.obj("value", "!!nije-base64!!")), h});
        cases.add(new Object[]{"PUT", "/v1/kv/k001", Json.write(Json.obj("value", "dmFsdWU")), h});
        cases.add(new Object[]{"PUT", "/v1/kv/k001", "{\"value\": 5}", h});
        cases.add(new Object[]{"PUT", "/v1/kv/k001", "nije json", h});
        cases.add(new Object[]{"PUT", "/v1/kv/k%20001", good, h});
        cases.add(new Object[]{"PUT", "/v1/kv/" + "a".repeat(129), good, h});
        cases.add(new Object[]{"DELETE", "/v1/kv/" + "a".repeat(129), null, h});
        cases.add(new Object[]{"PUT", "/v1/kv/k001", tooLarge, h});
        cases.add(new Object[]{"GET", "/v1/scan?start=k100&end=k001&limit=10", null, h});
        cases.add(new Object[]{"GET", "/v1/scan?start=k001&end=k001&limit=10", null, h});
        cases.add(new Object[]{"GET", "/v1/scan?start=k001&end=k100&limit=0", null, h});
        cases.add(new Object[]{"GET", "/v1/scan?start=k001&end=k100&limit=1001", null, h});
        cases.add(new Object[]{"GET", "/v1/scan?start=k001&end=k100", null, h});
        cases.add(new Object[]{"PUT", "/v1/kv/k001", good, Map.of("X-Request-Id", "val-2", "X-Request-Timeout-Ms", "99")});
        cases.add(new Object[]{"PUT", "/v1/kv/k001", good, Map.of("X-Request-Id", "val-3", "X-Request-Timeout-Ms", "abc")});
        cases.add(new Object[]{"PUT", "/v1/kv/k001", good, Map.of("X-Request-Id", "neispravan id!")});
        for (Object[] cs : cases) {
            String before = physicalSnapshot(n);
            @SuppressWarnings("unchecked")
            Resp r = n.raw((String) cs[0], (String) cs[1], (String) cs[2], (Map<String, String>) cs[3]);
            c.eq(400, r.status(), "validacija " + cs[0] + " " + cs[1].toString().substring(0,
                    Math.min(60, cs[1].toString().length())) + " → 400 (" + r.code() + ")");
            c.eq(before, physicalSnapshot(n), "odbijen zahtev ne menja redni broj/WAL/MemTable/manifest");
        }
        c.assertModel(n, m, keys);
    }

    // ================================================================ MDL-002

    private static final List<String> RECOVERY = List.of("recovery.after-manifest-load",
            "recovery.after-wal-tail-truncate", "recovery.after-wal-replay.before-ready");
    private static final List<String> FLUSH_HOOKS = List.of("rotation.after-new-wal-sync.before-switch",
            "rotation.after-switch.before-flush", "sstable.after-write.before-sync", "sstable.after-sync.before-rename",
            "sstable.after-rename.before-directory-sync", "manifest.after-temp-sync.before-rename",
            "manifest.after-rename.before-directory-sync", "flush.after-sstable-finalize.before-manifest",
            "flush.after-manifest-publish.before-wal-delete");

    /** Stanje kampanje: skup kompletnih kandidata modela i mapa pokrivenosti. */
    private static final class Campaign {
        final Ctx c;
        final Random r;
        final Node n;
        final List<String> keys = new ArrayList<>();
        List<RefModel> candidates = new ArrayList<>(List.of(new RefModel()));
        final Map<String, Integer> crash = new TreeMap<>();
        final List<Map<String, Object>> cycles = new ArrayList<>();
        int cycle;

        Campaign(Ctx c, Node n) {
            this.c = c;
            this.r = new Random(c.seed() * 31 + 2);
            this.n = n;
            for (int i = 0; i < 64; i++) {
                keys.add(String.format("c%02d", i));
            }
        }

        RefModel model() {
            if (candidates.size() != 1) {
                throw new AssertionError("model nije usaglašen: " + candidates.size() + " kandidata");
            }
            return candidates.get(0);
        }

        void traffic(int count) {
            RefModel m = model();
            for (int i = 0; i < count; i++) {
                String k = keys.get(r.nextInt(keys.size()));
                int p = r.nextInt(10);
                if (p < 6) {
                    c.putOk(n, m, k, randomValue(r, null));
                } else if (p < 8) {
                    c.deleteOk(n, m, k);
                } else {
                    c.assertGet(n, m, k);
                }
            }
        }

        int tables() {
            return ((Number) FlushScenarios.manifest(n).get("sstableCount")).intValue();
        }

        /** Posle oporavka: stvarno stanje mora biti jedan od kandidata; kandidati se svode na njega. */
        void reconcile(String hook) {
            RefModel seen = observed(c, n);
            RefModel match = null;
            List<String> digests = new ArrayList<>();
            for (RefModel cand : candidates) {
                digests.add(cand.digest());
                if (cand.sameAs(seen)) {
                    match = cand;
                }
            }
            c.check(match != null, "ciklus " + cycle + " (" + hook + "): stanje odgovara jednom od kandidata " + digests);
            for (String k : keys) {
                c.assertGet(n, match, k);
            }
            candidates = new ArrayList<>(List.of(match));
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("cycle", cycle);
            e.put("hook", hook);
            e.put("candidateDigests", digests);
            e.put("observedDigest", seen.digest());
            e.put("manifestGeneration", FlushScenarios.manifest(n).get("generation"));
            cycles.add(e);
        }

        void count(String hook) {
            crash.merge(hook, 1, Integer::sum);
            cycle++;
        }

        /** Jedan ciklus pada na datom hook-u; čvor je na kraju ponovo spreman i model je usaglašen. */
        void crashCycle(String hook) throws Exception {
            c.phase("ciklus " + cycle + " " + hook);
            if (RECOVERY.contains(hook)) {
                recoveryCycle(hook);
                return;
            }
            FlushScenarios.awaitIdle(c, n);
            RefModel before = model();
            String k = keys.get(r.nextInt(keys.size()));
            boolean del = r.nextInt(4) == 0 && before.get(k) != null;
            byte[] v = randomValue(r, null);
            RefModel applied = before.copy();
            if (del) {
                applied.delete(k);
            } else {
                applied.put(k, v);
            }
            n.arm("mdl", Ctx.json("name", hook, "action", "CRASH"));
            Resp resp;
            if (hook.startsWith("wal.") || hook.startsWith("memtable.")) {
                resp = del ? n.delete("mdl-" + cycle, k) : n.put("mdl-" + cycle, k, v);
                candidates = switch (hook) {
                    case "wal.before-append", "wal.after-record-prefix" -> List.of(before);
                    case "wal.after-append.before-sync" -> List.of(before, applied);
                    default -> List.of(applied);
                };
            } else if (hook.startsWith("scan.")) {
                resp = n.scan("mdl-" + cycle, "a", "z", 100);
            } else if (hook.startsWith("compaction.")) {
                resp = n.compact();
            } else {
                resp = n.flush();
            }
            c.check(resp.noResponse(), hook + ": operacija bez odgovora (" + resp.status() + ")");
            n.awaitExit();
            count(hook);
            n.start();
            reconcile(hook);
        }

        void recoveryCycle(String hook) throws Exception {
            if (hook.equals("recovery.after-wal-tail-truncate")) {
                // nepotpun završetak je preduslov: pravi ga pad na wal.after-record-prefix
                FlushScenarios.awaitIdle(c, n);
                n.arm("prefix", Ctx.json("name", "wal.after-record-prefix", "action", "CRASH"));
                c.check(n.put("mdl-prefix-" + cycle, keys.get(0), "nepotpuno").noResponse(), "prefiks bez odgovora");
                n.awaitExit();
                count("wal.after-record-prefix");
            } else {
                traffic(3);
                n.kill();
            }
            String rule = "[" + Ctx.json("token", "boot", "name", hook, "action", "CRASH") + "]";
            n.launch(rule);
            n.awaitExit();
            count(hook);
            if (!hook.equals("recovery.after-wal-tail-truncate")) {
                n.launch(rule); // ponovljen pad na istom recovery hook-u
                n.awaitExit();
                count(hook);
            }
            n.start();
            if (hook.equals("recovery.after-wal-tail-truncate")) {
                c.eq(0L, n.stat("walTornTailsTruncated"), "popravljen kraj je već trajan; ne seče se ponovo");
            }
            reconcile(hook);
        }
    }

    /** Kampanja padova i failpoint-a sa zadatim seed-om. */
    public static void mdl002(Ctx c) throws Exception {
        Node n = c.node("n").start();
        Campaign k = new Campaign(c, n);
        k.traffic(40);
        c.flushPublished(n);
        k.traffic(20);

        c.phase("CRASH kampanja");
        List<String> hooks = new ArrayList<>(FailpointCatalog.ALL.keySet());
        Collections.sort(hooks);
        int round = 0;
        while (k.cycle < 50 || k.crash.size() < hooks.size()) {
            List<String> order = new ArrayList<>(hooks);
            Collections.shuffle(order, k.r);
            for (String hook : order) {
                prepare(c, k, hook);
                k.crashCycle(hook);
                k.traffic(2 + k.r.nextInt(4));
                if (k.r.nextInt(6) == 0) {
                    c.eq(200, n.flush().status(), "povremeni uspešan flush");
                }
            }
            if (++round > 5) {
                break;
            }
        }
        for (String hook : hooks) {
            c.check(k.crash.getOrDefault(hook, 0) >= 1, "CRASH pokrivenost za " + hook);
        }
        for (String hook : RECOVERY) {
            c.check(k.crash.get(hook) >= 2, "ponovljen pad na " + hook);
        }
        c.check(k.cycle >= 50, "najmanje 50 ciklusa pada (" + k.cycle + ")");

        c.phase("IO_ERROR mapiranja");
        Map<String, String> io = new LinkedHashMap<>();
        for (String hook : List.of("wal.before-append", "wal.after-record-prefix", "wal.after-append.before-sync",
                "sstable.after-write.before-sync", "sstable.after-sync.before-rename",
                "sstable.after-rename.before-directory-sync", "manifest.after-temp-sync.before-rename",
                "manifest.after-rename.before-directory-sync")) {
            io.put(hook, ioIteration(c, k, hook));
        }

        c.phase("oštećenja kloniranog volumena");
        k.traffic(30);
        c.flushPublished(n);
        k.traffic(10);
        n.kill();
        Map<String, String> corruption = corruption(c, n);
        n.start();
        k.reconcile("posle-ostecenja");

        c.phase("zaštite pokretanja");
        Map<String, String> startup = startupProtections(c, k);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("seed", c.seed());
        report.put("crashCycles", k.cycle);
        report.put("crash", k.crash);
        report.put("ioError", io);
        report.put("corruption", corruption);
        report.put("startupProtection", startup);
        report.put("cycles", k.cycles);
        Files.writeString(c.artifactDir().resolve("mdl-002-coverage.json"), Json.write(report), StandardCharsets.UTF_8);
    }

    /** Gradi stanje u kojem je granica hook-a dostižna. */
    private static void prepare(Ctx c, Campaign k, String hook) throws Exception {
        if (FLUSH_HOOKS.contains(hook)) {
            k.traffic(5); // nepokriveni podaci za flush
        } else if (hook.startsWith("compaction.")) {
            // automatski compaction (prag od 4 tabele) može da spoji tabele; zato se čeka mirovanje i proverava ponovo
            FlushScenarios.awaitIdle(c, k.n);
            while (k.tables() < 2) {
                k.traffic(5);
                c.eq(200, k.n.flush().status(), "priprema tabela za compaction");
                FlushScenarios.awaitIdle(c, k.n);
            }
        }
    }

    private static String ioIteration(Ctx c, Campaign k, String hook) throws Exception {
        Node n = k.n;
        c.phase("IO_ERROR " + hook);
        FlushScenarios.awaitIdle(c, n);
        k.traffic(5);
        RefModel before = k.model();
        String man = FlushScenarios.manifestText(n);
        n.arm("io", Ctx.json("name", hook, "action", "IO_ERROR"));
        if (hook.startsWith("wal.")) {
            RefModel applied = before.copy();
            applied.put("c00", "io".getBytes(StandardCharsets.UTF_8));
            Resp r = n.put("io-" + hook, "c00", "io");
            c.check(!r.ok(), hook + ": mutacija ne dobija uspeh");
            c.check(!n.put("io-posle", "c01", "x").ok(), hook + ": put upisa je FAILED");
            k.candidates = hook.equals("wal.after-append.before-sync") ? List.of(before, applied) : List.of(before);
            n.kill();
            n.start();
            if (hook.equals("wal.after-record-prefix")) {
                c.check(n.stat("walTornTailsTruncated") >= 1, "popravka tačnog prefiksa");
            }
        } else {
            Resp r = n.flush();
            c.check(!r.ok(), hook + ": održavanje vraća neuspeh");
            c.eq("STORAGE_IO", r.code(), hook + ": kod greške");
            if (hook.equals("manifest.after-rename.before-directory-sync")) {
                c.eq("FAILED", n.admin("GET", "/admin/v1/status", null).get("lifecycle"), "prelazi u FAILED");
            } else {
                c.eq(man, FlushScenarios.manifestText(n), hook + ": stari manifest ostaje merodavan");
            }
            n.kill();
            n.start();
            c.eq(200, n.flush().status(), hook + ": kasniji pokušaj uspeva");
        }
        k.reconcile("IO_ERROR " + hook);
        return "OK";
    }

    private static Map<String, String> corruption(Ctx c, Node src) throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        Path wal = Ctx.list(src.lsm().resolve("wal"), ".log").get(0);
        Path sst = Ctx.list(src.lsm().resolve("sst"), ".sst").get(0);
        c.check(Files.size(wal) > 24 + 2 * 31, "WAL ima bar dva kompletna zapisa");
        String[] cases = {"wal-zapis", "wal-zaglavlje", "manifest", "sstable-nedostaje", "sstable-oblast"};
        for (String cs : cases) {
            Path dir = c.cloneDir(src.dataDir(), "mdl-" + cs);
            Path w = dir.resolve("lsm/wal").resolve(wal.getFileName());
            Path t = dir.resolve("lsm/sst").resolve(sst.getFileName());
            switch (cs) {
                case "wal-zapis" -> c.flipByte(w, 24 + 30 + 1, 0x10);
                case "wal-zaglavlje" -> c.flipByte(w, 3, 0x01);
                case "manifest" -> Files.writeString(dir.resolve("lsm/MANIFEST.json"), "{oštećen");
                case "sstable-nedostaje" -> Files.delete(t);
                default -> c.flipByte(t, 24 + 40, 0x01);
            }
            Node x = c.node("ostecenje-" + cs, dir);
            x.launch(null);
            String code = x.awaitFailed();
            c.check(code != null, cs + ": izričit FAILED");
            x.kill();
            out.put(cs, code);
        }
        return out;
    }

    private static Map<String, String> startupProtections(Ctx c, Campaign k) throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        Node n = k.n;
        Node second = c.node("drugi-proces", n.dataDir());
        second.launch(null);
        String code = second.awaitFailed();
        second.kill();
        c.eq("DIRECTORY_LOCKED", code, "drugi proces ne preuzima zauzet direktorijum");
        c.eq(200, n.admin("GET", "/health/ready", null).status(), "prvi vlasnik ostaje spreman");
        k.reconcile("posle-drugog-procesa");
        out.put("drugi-proces", code);
        n.kill();

        Path noId = c.cloneDir(n.dataDir(), "bez-node-json");
        Files.delete(noId.resolve("node.json"));
        Node a = c.node("bez-node-json", noId);
        a.launch(null);
        out.put("bez-node-json", a.awaitFailed());
        a.kill();

        Path other = c.cloneDir(n.dataDir(), "drugi-node-id");
        Node b = c.node("drugi-node-id", other).env("NODE_ID", "neki-drugi-cvor");
        b.launch(null);
        out.put("pogresan-node-json", b.awaitFailed());
        b.kill();

        Path noMan = c.cloneDir(n.dataDir(), "bez-manifesta");
        Files.delete(noMan.resolve("lsm/MANIFEST.json"));
        Node d = c.node("bez-manifesta", noMan);
        d.launch(null);
        String dc = d.awaitFailed();
        d.kill();
        c.eq("MANIFEST_MISSING", dc, "neprazan volumen bez manifesta se ne inicijalizuje kao prazan");
        out.put("bez-manifesta", dc);

        n.start();
        k.reconcile("kraj");
        return out;
    }
}
