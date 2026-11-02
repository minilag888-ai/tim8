package rs.raf.dlsm.testkit;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * CP1-API-001 … CP1-API-005 i CP1-FLS-007 (Checkpoint 1 §29.1, §29.3). Vlasnik: Ilija.
 * Svaki scenario pokreće pravi proces baze nad praznim direktorijumom i proverava i logičko stanje
 * (GET/SCAN prema modelu) i fizičko stanje (status, brojači).
 */
public final class ApiScenarios {

    private ApiScenarios() {
    }

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** Upis, čitanje, prepisivanje i prazna vrednost. */
    public static void api001(Ctx c) throws Exception {
        Node n = c.node("n").start();
        n.resetStats();
        byte[] a = {0x00, (byte) 0xFF, 0x41, 0x7F};
        byte[] bb = {0x10, 0x20};
        byte[] e = new byte[0];
        c.phase("mutacije");
        c.eq(200, n.put(c.rid(), "alpha", a).status(), "PUT A");
        c.eq(a, n.get(c.rid(), "alpha").value(), "GET A (netekstualni bajtovi)");
        c.eq(200, n.put(c.rid(), "alpha", bb).status(), "PUT B");
        c.eq(bb, n.get(c.rid(), "alpha").value(), "GET B");
        c.eq(200, n.put(c.rid(), "alpha", e).status(), "PUT prazna vrednost");
        Resp g = n.get(c.rid(), "alpha");
        c.eq(200, g.status(), "prazna vrednost je 200, ne 404");
        c.eq(e, g.value(), "GET prazna vrednost");
        c.eq(List.of("alpha="), c.scanList(n, "alpha", "alphb", 10), "SCAN [alpha,alphb)");
        c.phase("status");
        Map<String, Object> st = n.status();
        c.eq(4L, st.get("nextSequence"), "nextSequence posle tri mutacije");
        c.eq(3L, n.stat("walAppendsCompleted"), "tri završena WAL dopisivanja");
        c.eq(3L, n.stat("walForcesSucceeded"), "tri uspešna WAL force-a");
        @SuppressWarnings("unchecked")
        Map<String, Object> active = (Map<String, Object>) st.get("activeMemtable");
        c.eq(1L, active.get("entryCount"), "MemTable drži jedan zapis za alpha");
        Map<String, Object> common = n.admin("GET", "/admin/v1/status", null).body();
        @SuppressWarnings("unchecked")
        Map<String, Object> p0 = (Map<String, Object>) ((List<Object>) common.get("partitions")).get(0);
        c.eq(3L, p0.get("lastAcceptedSequence"), "lastAcceptedSequence");
        c.eq(3L, p0.get("lastDurableSequence"), "lastDurableSequence");
    }

    /** Brisanje postojećih i nepostojećih ključeva, flush i restart. */
    public static void api002(Ctx c) throws Exception {
        Node n = c.node("n").start();
        c.eq(200, n.put(c.rid(), "alpha", "A").status(), "PUT alpha");
        c.eq(b("A"), n.get(c.rid(), "alpha").value(), "GET alpha");
        c.phase("brisanja");
        c.eq(200, n.delete(c.rid(), "alpha").status(), "DELETE alpha");
        c.eq(404, n.get(c.rid(), "alpha").status(), "alpha NOT_FOUND");
        c.eq(200, n.delete(c.rid(), "ghost").status(), "DELETE nepostojećeg ghost");
        c.eq("NOT_FOUND", n.get(c.rid(), "ghost").code(), "ghost NOT_FOUND");
        Map<String, Object> f = c.flushPublished(n);
        c.eq("PUBLISHED", f.get("result"), "flush objavljen");
        c.check(((Number) f.get("flushedThroughSequence")).longValue() >= 3, "high-water pokriva oba brisanja");
        c.phase("restart");
        n.restart();
        c.eq(404, n.get(c.rid(), "alpha").status(), "alpha posle restarta");
        c.eq(404, n.get(c.rid(), "ghost").status(), "ghost posle restarta");
        c.eq(List.of(), c.scanList(n, "a", "zzz", 100), "skeniranje ne emituje obrisane ključeve");
        n.kill();
        c.phase("pregled SSTable");
        java.nio.file.Path sst = Ctx.list(n.lsm().resolve("sst"), ".sst").get(0);
        try (var r = rs.raf.dlsm.sstable.FileSSTableReader.open(sst,
                rs.raf.dlsm.sstable.SSTableFiles.parseTableId(sst.getFileName().toString()))) {
            c.check(r.get("alpha").orElseThrow().isTombstone(), "SSTable ima tombstone za alpha");
            c.check(r.get("ghost").orElseThrow().isTombstone(), "SSTable ima tombstone za ghost");
        }
    }

    /** Ograničeno uređeno skeniranje. */
    public static void api003(Ctx c) throws Exception {
        Node n = c.node("n").start();
        String[][] init = {{"a", "A"}, {"alpha", "A1"}, {"beta", "B"}, {"delta", "D1"}, {"epsilon", ""}, {"omega", "O"},
            {"zulu", "Z"}};
        for (String[] kv : init) {
            c.putOk(n, null, kv[0], kv[1]);
        }
        c.putOk(n, null, "delta", "D2");
        c.deleteOk(n, null, "beta");
        List<String> full = List.of("alpha=A1", "delta=D2", "epsilon=");
        c.eq(full, c.scanList(n, "alpha", "omega", 100), "(a) SCAN [alpha,omega)");
        c.eq(full.subList(0, 2), c.scanList(n, "alpha", "omega", 2), "(b) limit=2");
        c.eq(List.of("a=A"), c.scanList(n, "a", "alpha", 100), "(c) SCAN [a,alpha)");
        c.eq(List.of("omega=O"), c.scanList(n, "omega", "zulu", 100), "(d) SCAN [omega,zulu)");
    }

    /** Konkurentne mutacije istog ključa uz PAUSE posle WAL force-a. */
    public static void api004(Ctx c) throws Exception {
        Node n = c.node("n").start();
        n.resetStats();
        n.arm("api4", Ctx.json("name", "wal.after-sync.before-memtable", "action", "PAUSE",
                "filters", Map.of("requestId", "CP1-API-004-A"), "pauseTimeoutMs", 30_000));
        CompletableFuture<Resp> a = n.async(() -> n.put("CP1-API-004-A", "alpha", "A"));
        n.awaitTriggered("api4");
        c.trace("A je trajan i pauziran; šaljem B");
        CompletableFuture<Resp> bf = n.async(() -> n.put("CP1-API-004-B", "alpha", "B"));
        n.release("api4");
        c.eq(200, a.get(20, TimeUnit.SECONDS).status(), "PUT A");
        c.eq(200, bf.get(20, TimeUnit.SECONDS).status(), "PUT B");
        c.eq(b("B"), n.get(c.rid(), "alpha").value(), "B pobeđuje (kasniji redni broj)");
        c.eq(2L, n.stat("walForcesSucceeded"), "dva uspešna force-a");
        n.restart();
        c.eq(b("B"), n.get(c.rid(), "alpha").value(), "B posle naglog restarta");
    }

    /** Skeniranje konkurentno sa mutacijama: jedan stabilan snapshot. */
    public static void api005(Ctx c) throws Exception {
        Node n = c.node("n").start();
        c.putOk(n, null, "alpha", "A");
        c.putOk(n, null, "gamma", "G");
        n.arm("api5", Ctx.json("name", "scan.after-snapshot.before-merge", "action", "PAUSE",
                "filters", Map.of("requestId", "CP1-API-005-scan"), "pauseTimeoutMs", 30_000));
        CompletableFuture<Resp> scan = n.async(() -> n.scan("CP1-API-005-scan", "alpha", "omega", 100));
        n.awaitTriggered("api5");
        CompletableFuture<Resp> put = n.async(() -> n.put("CP1-API-005-put", "beta", "B"));
        CompletableFuture<Resp> del = n.async(() -> n.delete("CP1-API-005-del", "gamma"));
        n.release("api5");
        Resp s = scan.get(20, TimeUnit.SECONDS);
        c.eq(200, s.status(), "pauzirano skeniranje");
        c.eq("[{key=alpha, value=QQ==}, {key=gamma, value=Rw==}]", String.valueOf(s.get("items")),
                "pauzirano skeniranje vidi jedan snapshot");
        c.eq(200, put.get(20, TimeUnit.SECONDS).status(), "PUT beta");
        c.eq(200, del.get(20, TimeUnit.SECONDS).status(), "DELETE gamma");
        c.eq(List.of("alpha=A", "beta=B"), c.scanList(n, "alpha", "omega", 100), "kasnije skeniranje");
        n.restart();
        c.eq(List.of("alpha=A", "beta=B"), c.scanList(n, "alpha", "omega", 100), "skeniranje posle restarta");
    }

    /** Vidljiv backpressure dok je flush pauziran (§29.3, FLS-007). */
    public static void fls007(Ctx c) throws Exception {
        long s = 256;
        Node n = c.node("n").memtable(s).start();
        byte[] big = new byte[(int) s];
        n.arm("bp", Ctx.json("name", "sstable.after-write.before-sync", "action", "PAUSE", "pauseTimeoutMs", 60_000));
        c.eq(200, n.put(c.rid(), "old", withTag(big, 'A')).status(), "PUT old dostiže prag");
        c.eq(200, n.put(c.rid(), "current", withTag(big, 'B')).status(), "PUT current rotira i puni novu generaciju");
        n.awaitTriggered("bp");
        CompletableFuture<Resp> waiting = n.async(() -> n.put("CP1-FLS-007-wait", "waiting", "C"));
        c.await(() -> {
            Map<String, Object> st = n.status();
            @SuppressWarnings("unchecked")
            Map<String, Object> active = (Map<String, Object>) st.get("activeMemtable");
            return st.get("immutableMemtable") != null && Boolean.TRUE.equals(active.get("rotationRequired"))
                    && Boolean.TRUE.equals(st.get("writeBackpressure"))
                    && ((Number) st.get("backpressuredWriters")).longValue() >= 1;
        }, 10_000, "status pokazuje backpressure");
        c.check(!waiting.isDone(), "treći PUT čeka dok je flush pauziran");
        n.release("bp");
        c.eq(200, waiting.get(20, TimeUnit.SECONDS).status(), "treći PUT uspeva posle oslobađanja");
        c.await(() -> n.stat("flushPublications") >= 1 && "NONE".equals(maint(n)), 15_000, "flush objavljen, održavanje miruje");
        c.check(!Boolean.TRUE.equals(n.status().get("writeBackpressure")), "backpressure je prestao");
        n.restart();
        c.eq(withTag(big, 'A'), n.get(c.rid(), "old").value(), "old posle restarta");
        c.eq(withTag(big, 'B'), n.get(c.rid(), "current").value(), "current posle restarta");
        c.eq(b("C"), n.get(c.rid(), "waiting").value(), "waiting posle restarta");
    }

    static String maint(Node n) {
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) n.status().get("maintenance");
        return (String) m.get("operation");
    }

    private static byte[] withTag(byte[] base, char tag) {
        byte[] v = base.clone();
        v[0] = (byte) tag;
        return v;
    }
}
