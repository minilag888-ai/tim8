package rs.raf.dlsm.testkit;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import rs.raf.dlsm.manifest.ManifestInspector;
import rs.raf.dlsm.record.InternalRecord;
import rs.raf.dlsm.sstable.FileSSTableReader;
import rs.raf.dlsm.sstable.SSTableFiles;
import rs.raf.dlsm.sstable.SSTableFormat;
import rs.raf.dlsm.sstable.SSTableInspector;

/**
 * CP1-SST-001 … CP1-SST-006, CP1-CMP-001 i CP1-CMP-002 (Checkpoint 1 §29.4–29.5). Vlasnik: Vojislav.
 * Brojači se resetuju neposredno pre merenog intervala; porede se razlike, ne istorijski zbirovi.
 */
public final class SstScenarios {

    private SstScenarios() {
    }

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static Path onlyTable(Node n) throws Exception {
        List<Path> t = Ctx.list(n.lsm().resolve("sst"), ".sst");
        if (t.size() != 1) {
            throw new AssertionError("očekivana tačno jedna SSTable, ima " + t.size());
        }
        return t.get(0);
    }

    private static FileSSTableReader open(Path p) throws Exception {
        return FileSSTableReader.open(p, SSTableFiles.parseTableId(p.getFileName().toString()),
                new rs.raf.dlsm.sstable.SSTableStats());
    }

    private static long[] counters(Node n) {
        return new long[]{n.stat("sstableDataBlocksRead"), n.stat("bloomNegativeSkips"), n.stat("bloomPositiveProbes")};
    }

    /** Čitanje prvog, srednjeg, poslednjeg i odsutnih ključeva. */
    public static void sst001(Ctx c) throws Exception {
        Node n = c.node("n").start();
        RefModel m = new RefModel();
        for (int i = 0; i < 300; i++) {
            byte[] v = new byte[24];
            c.rnd().nextBytes(v);
            c.putOk(n, m, String.format("k%04d", i), v);
        }
        c.eq("PUBLISHED", c.flushPublished(n).get("result"), "flush u jednu SSTable");
        n.restart();
        @SuppressWarnings("unchecked")
        Map<String, Object> active = (Map<String, Object>) n.status().get("activeMemtable");
        c.eq(0L, active.get("entryCount"), "aktivna MemTable je prazna");
        for (String k : List.of("k0000", "k0150", "k0299")) {
            n.resetStats();
            c.eq(m.get(k), n.get(c.rid(), k).value(), "GET " + k);
            long[] d = counters(n);
            c.check(d[0] >= 1 && d[0] <= 2, k + " čita 1–2 kandidata za blok (" + d[0] + ")");
        }
        n.resetStats();
        c.eq(404, n.get(c.rid(), "k0150x").status(), "odsutan ključ u opsegu");
        long[] d = counters(n);
        c.check((d[1] == 1 && d[0] == 0) || (d[2] == 1 && d[0] <= 2), "Bloom negativan bez čitanja ili lažno pozitivan uz <=2 bloka");
        n.resetStats();
        c.eq(404, n.get(c.rid(), "zzzz").status(), "ključ van opsega");
        c.eq(0L, counters(n)[0], "ključ van opsega ne čita blok");
    }

    /** Bloom: bez lažno negativnih, najviše 5% lažno pozitivnih. */
    public static void sst002(Ctx c) throws Exception {
        Node n = c.node("n").start();
        List<String> stored = new ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            String k = String.format("b%04d", i);
            stored.add(k);
            if (i % 10 == 3) {
                c.deleteOk(n, null, k); // tombstone (ključ nikad nije postojao)
            } else {
                c.putOk(n, null, k, i % 10 == 5 ? "" : "v" + i);
            }
        }
        c.eq("PUBLISHED", c.flushPublished(n).get("result"), "flush");
        c.phase("komponentna provera filtera");
        try (FileSSTableReader r = open(onlyTable(n))) {
            for (String k : stored) {
                c.check(r.get(k).isPresent(), "Bloom/čitač vraća sačuvan ključ " + k);
            }
        }
        c.phase("odsutni ključevi");
        n.resetStats();
        int mCount = 10_000;
        for (int i = 0; i < mCount; i++) {
            String k = String.format("b%04d_%d", i % 1999, i);
            Resp r = n.get(c.rid(), k);
            if (r.status() != 404) {
                throw new AssertionError("odsutan ključ " + k + " nije NOT_FOUND: " + r);
            }
        }
        long neg = n.stat("bloomNegativeSkips");
        long pos = n.stat("bloomPositiveProbes");
        long blocks = n.stat("sstableDataBlocksRead");
        c.eq((long) mCount, neg + pos, "negativne + pozitivne probe = M");
        c.check(pos <= mCount * 0.05, "lažno pozitivnih najviše 5% (" + pos + ")");
        c.check(blocks <= 2 * pos, "negativne probe ne čitaju blokove (" + blocks + " <= 2*" + pos + ")");
    }

    /** Ograničena pretraga retkim indeksom u velikoj tabeli. */
    public static void sst003(Ctx c) throws Exception {
        Node n = c.node("n").start();
        RefModel m = new RefModel();
        for (int i = 0; i < 10_000; i++) {
            byte[] v = new byte[64];
            ByteBuffer.wrap(v).putInt(i);
            c.putOk(n, m, String.format("t%05d", i), v);
        }
        c.flushPublished(n);
        n.restart();
        int blocks;
        try (FileSSTableReader r = open(onlyTable(n))) {
            blocks = r.blockCount();
        }
        c.check(blocks >= 100, "najmanje 100 blokova (" + blocks + ")");
        for (int i : new int[]{3, 5000, 9996}) {
            String k = String.format("t%05d", i);
            n.resetStats();
            c.eq(m.get(k), n.get(c.rid(), k).value(), "GET " + k);
            long br = n.stat("sstableDataBlocksRead");
            long bytes = n.stat("sstableDataBytesRead");
            c.check(br >= 1 && br <= 2, k + ": 1–2 čitanja bloka (" + br + ")");
            c.check(bytes <= 2L * (SSTableFormat.DATA_BLOCK_TARGET_BYTES + 256), k + ": bajtovi ograničeni blokom (" + bytes + ")");
        }
        n.restart();
        n.resetStats();
        c.eq(m.get("t05000"), n.get(c.rid(), "t05000").value(), "GET posle restarta");
        c.check(n.stat("sstableDataBlocksRead") <= 2, "posle restarta 1–2 čitanja");
    }

    /** Pozicioniranje opsega blizu kraja velike tabele. */
    public static void sst004(Ctx c) throws Exception {
        Node n = c.node("n").start();
        RefModel m = new RefModel();
        for (int i = 0; i < 20_000; i++) {
            c.putOk(n, m, String.format("r%06d", i), "value-" + i + "-pad-pad-pad-pad-pad-pad");
        }
        c.flushPublished(n);
        if (Ctx.list(n.lsm().resolve("sst"), ".sst").size() > 1) {
            // automatski flush je napravio više tabela; spajamo ih u jednu veliku
            c.eq("PUBLISHED", n.compact().get("result"), "compaction u jednu tabelu");
        }
        n.restart();
        int start = 18_700;
        String startKey = String.format("r%06d", start) + "-"; // počinje unutar bloka, posle postojećeg ključa
        String endKey = String.format("r%06d", start + 21);
        int span;
        int total;
        try (FileSSTableReader r = open(onlyTable(n))) {
            total = r.blockCount();
            List<SSTableFormat.IndexEntry> idx = r.indexEntries();
            int first = 0;
            int last = 0;
            for (int i = 0; i < idx.size(); i++) {
                if (idx.get(i).firstKey().compareTo(startKey) <= 0) {
                    first = i;
                }
                if (idx.get(i).firstKey().compareTo(String.format("r%06d", start + 20)) <= 0) {
                    last = i;
                }
            }
            span = last - first + 1;
        }
        c.check(total >= 200, "najmanje 200 blokova (" + total + ")");
        n.resetStats();
        List<String> got = c.scanList(n, startKey, endKey, 20);
        List<String> exp = new ArrayList<>();
        for (var e : m.scan(startKey, endKey, 20)) {
            exp.add(e.getKey() + "=" + new String(e.getValue(), StandardCharsets.UTF_8));
        }
        c.eq(20, got.size(), "tačno 20 stavki");
        c.eq(exp, got, "stavke odgovaraju modelu");
        long rs = n.stat("rangeScanBlocksRead");
        c.check(rs <= span + 1, "rangeScanBlocksRead (" + rs + ") <= raspon (" + span + ") + 1");
        c.check(n.stat("sstableDataBlocksRead") >= rs, "uključeno u ukupna čitanja blokova");
        c.check(rs < total / 10, "znatno manje od broja blokova tabele");
    }

    /** Spajanje aktivne i nepromenljive MemTable i više SSTable. */
    public static void sst005(Ctx c) throws Exception {
        Node n = c.node("n").start();
        c.putOk(n, null, "alpha", "A1");
        c.putOk(n, null, "beta", "B1");
        c.putOk(n, null, "epsilon", "E1");
        c.flushPublished(n);
        c.putOk(n, null, "alpha", "A2");
        c.putOk(n, null, "gamma", "G2");
        c.putOk(n, null, "delta", "D2");
        c.flushPublished(n);
        c.deleteOk(n, null, "beta");
        c.putOk(n, null, "delta", "D3");
        n.arm("pause", Ctx.json("name", "sstable.after-write.before-sync", "action", "PAUSE", "pauseTimeoutMs", 60_000));
        CompletableFuture<Resp> flush = n.async(n::flush);
        n.awaitTriggered("pause");
        c.putOk(n, null, "alpha", "A4");
        c.deleteOk(n, null, "gamma");
        c.putOk(n, null, "zeta", "Z4");
        c.phase("tokom pauze");
        Map<String, Object> st = n.status();
        c.check(st.get("immutableMemtable") != null, "postoji nepromenljiva MemTable");
        @SuppressWarnings("unchecked")
        Map<String, Object> man = (Map<String, Object>) st.get("manifest");
        c.check(((Number) man.get("sstableCount")).longValue() >= 2, "najmanje dve objavljene SSTable");
        expect(c, n);
        n.release("pause");
        c.eq(200, flush.get(30, TimeUnit.SECONDS).status(), "flush posle oslobađanja");
        c.phase("posle flush-a");
        expect(c, n);
        c.phase("posle compaction-a");
        c.eq("PUBLISHED", n.compact().get("result"), "compaction");
        expect(c, n);
    }

    private static void expect(Ctx c, Node n) {
        c.eq(b("A4"), n.get(c.rid(), "alpha").value(), "alpha");
        c.eq(404, n.get(c.rid(), "beta").status(), "beta");
        c.eq(404, n.get(c.rid(), "gamma").status(), "gamma");
        c.eq(b("D3"), n.get(c.rid(), "delta").value(), "delta");
        c.eq(b("E1"), n.get(c.rid(), "epsilon").value(), "epsilon");
        c.eq(b("Z4"), n.get(c.rid(), "zeta").value(), "zeta");
        c.eq(List.of("alpha=A4", "delta=D3", "epsilon=E1", "zeta=Z4"), c.scanList(n, "alpha", "zzzz", 100),
                "SCAN [alpha,zzzz)");
    }

    /** Oštećenje objavljenih metapodataka ili referencirane SSTable: uvek FAILED. */
    public static void sst006(Ctx c) throws Exception {
        Node n = c.node("prep").start();
        for (int i = 0; i < 300; i++) {
            c.putOk(n, null, String.format("s%04d", i), "vrednost-" + i + "-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx");
        }
        c.flushPublished(n);
        n.kill();
        Path table = onlyTable(n);
        String tableName = table.getFileName().toString();
        long size = Files.size(table);
        long indexOffset;
        long bloomOffset;
        try (FileChannel ch = FileChannel.open(table, StandardOpenOption.READ)) {
            ByteBuffer f = ByteBuffer.allocate(100);
            ch.read(f, size - 100);
            indexOffset = f.getLong(36);
            bloomOffset = f.getLong(52);
        }
        String manifest = Files.readString(n.lsm().resolve("MANIFEST.json"));
        String[] cases = {"a-blok", "b-indeks", "c-bloom", "d-footer", "e-skraceno", "f-uklonjena", "g-manifest", "h-neslaganje"};
        for (String k : cases) {
            c.phase("podslučaj " + k);
            Path dir = c.cloneDir(n.dataDir(), "c-" + k);
            Path t = dir.resolve("lsm/sst").resolve(tableName);
            Path man = dir.resolve("lsm/MANIFEST.json");
            switch (k.charAt(0)) {
                case 'a' -> c.flipByte(t, 24 + 40, 0x01);
                case 'b' -> c.flipByte(t, indexOffset + 6, 0x01);
                case 'c' -> c.flipByte(t, bloomOffset + 8, 0x01);
                case 'd' -> c.flipByte(t, size - 50, 0x01);
                case 'e' -> c.truncate(t, size - 200);
                case 'f' -> Files.delete(t);
                case 'g' -> Files.writeString(man, "{\"formatVersion\":1, ovo nije json");
                default -> Files.writeString(man, manifest.replaceFirst("\"entryCount\":(\\d+)", "\"entryCount\":7"));
            }
            String manBefore = Files.readString(man);
            Node x = c.node("x-" + k, dir);
            x.launch(null);
            String code = x.awaitFailed();
            c.check(code != null, "stabilna dijagnostika (" + code + ")");
            c.eq(503, x.get(c.rid(), "s0001").status(), "nema delimičnog GET opsluživanja");
            x.kill();
            c.eq(manBefore, Files.readString(man), "manifest nije prepravljen");
            if (k.charAt(0) <= 'e') {
                c.check(Files.exists(t), "referencirani fajl nije obrisan kao orphan");
                c.check(SSTableInspector.inspect(dir.resolve("lsm/sst"), tableName, new java.io.PrintStream(
                        java.io.OutputStream.nullOutputStream())) != 0, "inspect sstable vraća kod različit od nule");
            }
            if (k.charAt(0) == 'f') {
                c.eq("REFERENCED_FILE_MISSING", code, "kategorija za nedostajuću tabelu");
            }
            if (k.charAt(0) == 'g') {
                c.eq("MANIFEST_INVALID", code, "kategorija za neispravan manifest");
                c.check(ManifestInspector.inspect(dir.resolve("lsm"), new java.io.PrintStream(
                        java.io.OutputStream.nullOutputStream())) != 0, "inspect manifest vraća kod različit od nule");
            }
            if (k.charAt(0) == 'h') {
                c.eq("MANIFEST_TABLE_MISMATCH", code, "kategorija za neslaganje sa manifestom");
            }
        }
    }

    /** Sažimanje preklopljenih verzija. */
    public static void cmp001(Ctx c) throws Exception {
        Node n = c.node("n").start();
        RefModel m = new RefModel();
        c.putOk(n, m, "alpha", "A1");
        c.putOk(n, m, "beta", "B1");
        c.putOk(n, m, "delta", "D1");
        c.flushPublished(n);
        c.putOk(n, m, "alpha", "A2");
        c.putOk(n, m, "gamma", "G2");
        c.flushPublished(n);
        c.putOk(n, m, "beta", "B3");
        c.putOk(n, m, "delta", "D3");
        c.putOk(n, m, "epsilon", "E3");
        Map<String, Object> f3 = c.flushPublished(n);
        long flushed = ((Number) f3.get("flushedThroughSequence")).longValue();
        c.assertModel(n, m, null);
        n.resetStats();
        Resp r = n.compact();
        c.eq("PUBLISHED", r.get("result"), "compaction objavljen");
        c.eq(3, r.list("inputTableIds").size(), "sva tri ulaza");
        long out = r.num("outputTableId");
        c.assertModel(n, m, null);
        @SuppressWarnings("unchecked")
        Map<String, Object> man = (Map<String, Object>) n.status().get("manifest");
        c.eq(1L, man.get("sstableCount"), "manifest ima samo izlaz");
        c.eq(flushed, man.get("flushedThroughSequence"), "high-water je nepromenjen");
        c.eq(1L, n.stat("compactionAttempts"), "jedan pokušaj");
        c.eq(1L, n.stat("compactionPublications"), "jedno objavljivanje");
        n.kill();
        try (FileSSTableReader t = open(n.lsm().resolve("sst").resolve(String.format("sst-%020d.sst", out)))) {
            c.eq(5L, t.meta().entryCount(), "jedan zapis po ključu");
            c.eq(b("A2"), t.get("alpha").orElseThrow().value(), "izlaz zadržava najveći redni broj");
            t.validateFully();
        }
        n.start();
        c.assertModel(n, m, null);
    }

    /** Tombstone zapisi se zadržavaju kroz compaction. */
    public static void cmp002(Ctx c) throws Exception {
        Node n = c.node("n").start();
        c.putOk(n, null, "alpha", "A1");
        c.putOk(n, null, "beta", "B1");
        c.flushPublished(n);
        c.deleteOk(n, null, "alpha");
        c.deleteOk(n, null, "ghost");
        c.putOk(n, null, "beta", "B2");
        c.flushPublished(n);
        check2(c, n);
        Resp r = n.compact();
        c.eq("PUBLISHED", r.get("result"), "compaction");
        check2(c, n);
        n.kill();
        try (FileSSTableReader t = open(n.lsm().resolve("sst").resolve(String.format("sst-%020d.sst", r.num("outputTableId"))))) {
            c.eq(3L, t.meta().entryCount(), "tri zapisa: dva tombstone-a i beta");
            InternalRecord a = t.get("alpha").orElseThrow();
            InternalRecord g = t.get("ghost").orElseThrow();
            c.check(a.isTombstone() && g.isTombstone(), "tombstone-i za alpha i ghost su zadržani (i u Bloom-u)");
            c.eq(b("B2"), t.get("beta").orElseThrow().value(), "beta = B2");
        }
        n.start();
        check2(c, n);
    }

    private static void check2(Ctx c, Node n) {
        c.eq(404, n.get(c.rid(), "alpha").status(), "alpha NOT_FOUND");
        c.eq(404, n.get(c.rid(), "ghost").status(), "ghost NOT_FOUND");
        c.eq(b("B2"), n.get(c.rid(), "beta").value(), "beta = B2");
        c.eq(List.of("beta=B2"), c.scanList(n, "a", "zzz", 100), "skeniranje");
    }
}
