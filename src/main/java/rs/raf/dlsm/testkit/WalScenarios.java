package rs.raf.dlsm.testkit;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32C;
import rs.raf.dlsm.wal.FileWalReader;
import rs.raf.dlsm.wal.WalScanResult;

/**
 * CP1-WAL-001 … CP1-WAL-008 (Checkpoint 1 §29.2). Vlasnik: Katarina.
 * WAL-007 pravi pripremljen volumen NEZAVISNIM graditeljem (ne koristi produkcioni WAL kod).
 */
public final class WalScenarios {

    private WalScenarios() {
    }

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static Path activeWal(Node n) throws IOException {
        List<Path> segs = Ctx.list(n.lsm().resolve("wal"), ".log");
        return segs.get(segs.size() - 1);
    }

    @SuppressWarnings("unchecked")
    private static long activeWalId(Node n) {
        return ((Number) ((Map<String, Object>) n.status().get("activeMemtable")).get("walSegmentId")).longValue();
    }

    /** Prekid posle potvrđenih mutacija: sve potvrđeno preživljava SIGKILL. */
    public static void wal001(Ctx c) throws Exception {
        Node n = c.node("n").start();
        RefModel m = new RefModel();
        n.resetStats();
        c.phase("mutacije");
        int count = 0;
        for (int i = 0; i < 12; i++) {
            c.putOk(n, m, "k" + i, "v" + i);
            count++;
        }
        c.putOk(n, m, "k3", "prepisano");
        c.putOk(n, m, "empty", new byte[0]);
        c.putOk(n, m, "bin", new byte[]{0, 1, (byte) 0x80, (byte) 0xFF});
        c.deleteOk(n, m, "k4");
        c.deleteOk(n, m, "never-existed");
        count += 5;
        for (int i = 12; i < 22; i++) {
            c.putOk(n, m, "k" + i, "w" + i);
            count++;
        }
        c.deleteOk(n, m, "k20");
        count++;
        c.assertGet(n, m, "k3");
        c.assertGet(n, m, "k4");
        c.eq((long) count, n.stat("walForcesSucceeded"), "jedan uspešan force po potvrđenoj mutaciji");
        long walBefore = activeWalId(n);
        c.phase("SIGKILL i oporavak");
        n.kill();
        n.start();
        c.assertModel(n, m, List.of("k4", "never-existed", "k20"));
        c.eq((long) count, n.stat("walRecordsReplayed"), "replay svakog zapisa iznad high-water");
        c.eq((long) count + 1, n.status().get("nextSequence"), "nextSequence iznad najvećeg zapisa");
        c.check(activeWalId(n) > walBefore, "nov aktivni WAL pre spremnosti");
        c.putOk(n, m, "after", "x");
        n.restart();
        c.assertModel(n, m, null);
    }

    /** Nepotpun završni okvir na više mesta preseka. */
    public static void wal002(Ctx c) throws Exception {
        int frame = 30 + "torn".length() + 1; // PUT(torn,"T")
        for (int prefix : new int[]{3, 8, frame - 1}) {
            c.phase("presek " + prefix);
            Node n = c.node("n" + prefix).start();
            c.putOk(n, null, "base1", "V1");
            c.putOk(n, null, "base2", "V2");
            Path wal = activeWal(n);
            long l = Files.size(wal);
            n.arm("torn", Ctx.json("name", "wal.after-record-prefix", "action", "CRASH",
                    "filters", Map.of("requestId", "CP1-WAL-002-torn"), "parameters", Map.of("prefixBytes", prefix)));
            c.check(n.put("CP1-WAL-002-torn", "torn", "T").noResponse(), "PUT torn nema odgovor");
            n.awaitExit();
            c.eq(l + prefix, Files.size(wal), "na disku je strogi prefiks");
            WalScanResult r = new FileWalReader().scan(wal);
            c.eq(WalScanResult.Status.TORN_TAIL, r.status(), "inspect wal: TORN_TAIL");
            c.eq(l, r.validEndOffset(), "početak okvira je L");
            n.start();
            c.eq(b("V1"), n.get(c.rid(), "base1").value(), "base1");
            c.eq(b("V2"), n.get(c.rid(), "base2").value(), "base2");
            c.eq(404, n.get(c.rid(), "torn").status(), "torn je NOT_FOUND");
            c.eq(1L, n.stat("walTornTailsTruncated"), "jedno odsecanje");
            n.restart();
            c.eq(0L, n.stat("walTornTailsTruncated"), "drugi oporavak ništa ne odseca");
            c.eq(404, n.get(c.rid(), "torn").status(), "torn posle drugog restarta");
            c.eq(b("V2"), n.get(c.rid(), "base2").value(), "base2 posle drugog restarta");
            if (Files.exists(wal)) {
                c.eq(l, Files.size(wal), "popravljeni segment zadržava kraj L");
            }
            n.kill();
        }
    }

    /** Oštećenje kompletnog okvira: FAILED, ne best-effort. */
    public static void wal003(Ctx c) throws Exception {
        Node n = c.node("prep").start();
        for (int i = 1; i <= 3; i++) {
            c.putOk(n, null, "k" + i, "v" + i);
        }
        n.kill();
        int frame = 30 + 2 + 2;
        long f2 = 24 + frame; // drugi okvir; iza njega postoji kompletan treći
        String walName = activeWal(n).getFileName().toString();
        Object[][] cases = {
            {"a-kljuc", f2 + 26, 0x01},
            {"b-crc", f2 + frame - 1, 0x01},
            {"c-duzina", f2 + 5, 0x01},
            {"d-zaglavlje", 0L, 0x01},
        };
        for (Object[] k : cases) {
            c.phase("podslučaj " + k[0]);
            Path dir = c.cloneDir(n.dataDir(), "c-" + k[0]);
            Path wal = dir.resolve("lsm/wal").resolve(walName);
            long size = Files.size(wal);
            c.flipByte(wal, (Long) k[1], (Integer) k[2]);
            Node x = c.node("x-" + k[0], dir);
            x.launch(null);
            String code = x.awaitFailed();
            c.eq("WAL_CORRUPT", code, "dijagnostika oštećenja WAL-a");
            c.eq(503, x.get(c.rid(), "k1").status(), "KV saobraćaj se ne opslužuje");
            x.kill();
            c.eq(size, Files.size(wal), "dužina oštećenog fajla se ne smanjuje");
            c.eq(WalScanResult.Status.CORRUPT, new FileWalReader().scan(wal).status(), "inspect wal: CORRUPT");
        }
    }

    /** Ubrizgane greške dopisivanja i force-a. */
    public static void wal004(Ctx c) throws Exception {
        String[][] cases = {{"a", "wal.before-append"}, {"b", "wal.after-record-prefix"}, {"c", "wal.after-append.before-sync"}};
        for (String[] k : cases) {
            c.phase("podslučaj " + k[0]);
            Node n = c.node("n" + k[0]).start();
            c.putOk(n, null, "base", "V");
            n.resetStats();
            Path wal = activeWal(n);
            long l = Files.size(wal);
            String rule = k[0].equals("b")
                    ? Ctx.json("name", k[1], "action", "IO_ERROR", "parameters", Map.of("prefixBytes", 10))
                    : Ctx.json("name", k[1], "action", "IO_ERROR");
            n.arm("io", rule);
            Resp r = n.put(c.rid(), "candidate", "C");
            c.check(r.status() == 500 || r.status() == 503, "candidate ne dobija uspeh (" + r.status() + ")");
            c.check(n.get(c.rid(), "candidate").status() != 200, "candidate nije vidljiv pre restarta");
            c.check(n.put(c.rid(), "later", "L").status() != 200, "kasnija mutacija se odbija");
            c.eq("FAILED", n.status().get("engineMode"), "put upisa je FAILED");
            switch (k[0]) {
                case "a" -> c.eq(l, Files.size(wal), "(a) nijedan bajt kandidata");
                case "b" -> c.eq(l + 10, Files.size(wal), "(b) ostao je strogi prefiks");
                default -> {
                    c.eq(1L, n.stat("walAppendsCompleted"), "(c) dopisivanje završeno");
                    c.eq(1L, n.stat("walForceAttempts"), "(c) pokušan force");
                    c.eq(0L, n.stat("walForcesSucceeded"), "(c) nema uspešnog force-a");
                }
            }
            n.restart();
            c.eq(b("V"), n.get(c.rid(), "base").value(), "base posle restarta");
            Resp cand = n.get(c.rid(), "candidate");
            if (k[0].equals("c")) {
                c.check(cand.status() == 404 || (cand.ok() && "C".equals(new String(cand.value(), StandardCharsets.UTF_8))),
                        "(c) kandidat je potpuno odsutan ili tačno C");
            } else {
                c.eq(404, cand.status(), "kandidat je odsutan");
            }
            c.eq(404, n.get(c.rid(), "later").status(), "odbijena mutacija nije upisana");
            n.kill();
        }
    }

    /** Pad posle force-a, pre MemTable: mutacija MORA da preživi. */
    public static void wal005(Ctx c) throws Exception {
        Node n = c.node("n").start();
        n.arm("w5", Ctx.json("name", "wal.after-sync.before-memtable", "action", "CRASH",
                "filters", Map.of("requestId", "CP1-WAL-005-put")));
        c.check(n.put("CP1-WAL-005-put", "alpha", "A").noResponse(), "nema odgovora");
        n.awaitExit();
        n.start();
        c.eq(b("A"), n.get(c.rid(), "alpha").value(), "alpha = A posle oporavka");
        c.eq(2L, n.status().get("nextSequence"), "sledeći redni broj iznad zapisa");
        c.eq(1L, n.stat("walRecordsReplayed"), "zapis je ponovo primenjen");
        c.eq(0L, n.stat("walAppendAttempts"), "oporavak ne dopisuje kopiju");
        n.restart();
        c.eq(b("A"), n.get(c.rid(), "alpha").value(), "alpha posle drugog restarta");
    }

    /** Pad posle kompletnog dopisivanja, pre force-a: dva dozvoljena ishoda. */
    public static void wal006(Ctx c) throws Exception {
        Node n = c.node("n").start();
        c.putOk(n, null, "base", "V");
        n.arm("w6", Ctx.json("name", "wal.after-append.before-sync", "action", "CRASH",
                "filters", Map.of("requestId", "CP1-WAL-006-put")));
        c.check(n.put("CP1-WAL-006-put", "candidate", "C").noResponse(), "nema odgovora");
        n.awaitExit();
        n.start();
        c.eq(b("V"), n.get(c.rid(), "base").value(), "base");
        Resp cand = n.get(c.rid(), "candidate");
        boolean present = cand.ok();
        c.check(cand.status() == 404 || (present && "C".equals(new String(cand.value(), StandardCharsets.UTF_8))),
                "kandidat je odsutan ili tačno C");
        c.putOk(n, null, "after", "R");
        n.restart();
        c.eq(b("V"), n.get(c.rid(), "base").value(), "base posle drugog restarta");
        c.eq(b("R"), n.get(c.rid(), "after").value(), "after posle drugog restarta");
        c.eq(present ? 200 : 404, n.get(c.rid(), "candidate").status(), "kandidat zadržava prvi opaženi model");
    }

    /** Replay više WAL segmenata redom (pripremljen volumen). */
    public static void wal007(Ctx c) throws Exception {
        c.phase("važeći pripremljeni skup");
        Path dir = c.work().resolve("prepared");
        Fixture.emptyDatabase(dir);
        Fixture.walSegment(dir, 2, List.of(Fixture.put(1, "alpha", "A1"), Fixture.put(2, "beta", "B1")));
        Fixture.walSegment(dir, 5, List.of(Fixture.put(3, "alpha", "A2"), Fixture.del(4, "beta")));
        Fixture.walSegment(dir, 9, List.of(Fixture.put(5, "gamma", "G"), Fixture.put(6, "alpha", "A3")));
        Node n = c.node("n", dir).start();
        c.eq(b("A3"), n.get(c.rid(), "alpha").value(), "alpha = A3");
        c.eq(404, n.get(c.rid(), "beta").status(), "beta NOT_FOUND");
        c.eq(b("G"), n.get(c.rid(), "gamma").value(), "gamma = G");
        c.eq(6L, n.stat("walRecordsReplayed"), "tačno šest zapisa");
        c.eq(7L, n.status().get("nextSequence"), "nextSequence = 7");
        c.check(activeWalId(n) > 9, "nov aktivni WAL ima ID veći od 9");
        c.putOk(n, null, "delta", "D");
        n.restart();
        c.eq(b("A3"), n.get(c.rid(), "alpha").value(), "alpha posle restarta");
        c.eq(b("D"), n.get(c.rid(), "delta").value(), "delta posle restarta");
        n.kill();

        c.phase("nevažeći redosled preko granice segmenata");
        Path bad = c.work().resolve("bad");
        Fixture.emptyDatabase(bad);
        Fixture.walSegment(bad, 2, List.of(Fixture.put(1, "alpha", "A1"), Fixture.put(2, "beta", "B1")));
        Fixture.walSegment(bad, 5, List.of(Fixture.put(2, "alpha", "A2"), Fixture.del(4, "beta")));
        Node x = c.node("bad", bad);
        x.launch(null);
        c.eq("WAL_SEQUENCE", x.awaitFailed(), "stabilna dijagnostika redosleda");
    }

    /** Ponavljani padovi tokom oporavka. */
    public static void wal008(Ctx c) throws Exception {
        Node n = c.node("prep").start();
        for (int i = 0; i < 5; i++) {
            c.putOk(n, null, "base" + i, "v" + i);
        }
        c.deleteOk(n, null, "base2");
        Path wal = activeWal(n);
        long l = Files.size(wal);
        n.arm("torn", Ctx.json("name", "wal.after-record-prefix", "action", "CRASH",
                "filters", Map.of("requestId", "CP1-WAL-008-torn"), "parameters", Map.of("prefixBytes", 12)));
        n.put("CP1-WAL-008-torn", "torn", "T");
        n.awaitExit();
        Path control = c.cloneDir(n.dataDir(), "control");
        Path test = c.cloneDir(n.dataDir(), "test");

        c.phase("kontrola");
        Node ctl = c.node("ctl", control).start();
        List<Map.Entry<String, byte[]>> expected = c.scanAll(ctl);
        ctl.kill();

        c.phase("ponavljani padovi");
        Node t = c.node("t", test);
        String walName = wal.getFileName().toString();
        for (String hook : List.of("recovery.after-manifest-load", "recovery.after-wal-tail-truncate",
                "recovery.after-wal-replay.before-ready", "recovery.after-wal-replay.before-ready")) {
            t.launch("[" + Ctx.json("token", "boot", "name", hook, "action", "CRASH") + "]");
            t.awaitExit();
            Path w = test.resolve("lsm/wal").resolve(walName);
            if (Files.exists(w)) {
                long size = Files.size(w);
                c.check(size == l || size == l + 12, "kraj WAL-a je L ili originalni prefiks, nikad manje (" + size + ")");
            }
        }
        c.phase("konačno pokretanje");
        t.start();
        List<Map.Entry<String, byte[]>> actual = c.scanAll(t);
        c.eq(expected.size(), actual.size(), "isti broj ključeva kao kontrola");
        for (int i = 0; i < actual.size(); i++) {
            c.eq(expected.get(i).getKey(), actual.get(i).getKey(), "ključ #" + i);
            c.eq(expected.get(i).getValue(), actual.get(i).getValue(), "vrednost " + actual.get(i).getKey());
        }
        c.eq(404, t.get(c.rid(), "torn").status(), "nepotpuna mutacija je odsutna");
        c.eq(0L, t.stat("walAppendAttempts"), "replay ne dopisuje kopije");
        Set<String> names = Set.copyOf(Ctx.list(test.resolve("lsm/wal"), ".log").stream()
                .map(p -> p.getFileName().toString()).toList());
        c.check(!names.isEmpty(), "postoji upotrebljiv aktivni WAL");
    }

    /** Nezavisan graditelj pripremljenih volumena (ne koristi produkcioni WAL/manifest kod). */
    static final class Fixture {

        record Rec(long seq, int kind, String key, byte[] value) {
        }

        static Rec put(long seq, String k, String v) {
            return new Rec(seq, 1, k, v.getBytes(StandardCharsets.UTF_8));
        }

        static Rec del(long seq, String k) {
            return new Rec(seq, 2, k, new byte[0]);
        }

        static void emptyDatabase(Path root) throws IOException {
            Files.createDirectories(root.resolve("lsm/wal"));
            Files.createDirectories(root.resolve("lsm/sst"));
            Files.createDirectories(root.resolve("lsm/tmp"));
            Files.writeString(root.resolve("node.json"), "{\"formatVersion\":1,\"nodeId\":\"standalone\"}");
            Files.writeString(root.resolve("lsm/MANIFEST.json"),
                    "{\"formatVersion\":1,\"generation\":0,\"nextTableId\":1,\"flushedThroughSequence\":0,\"sstables\":[]}");
        }

        /** Raspored iz Checkpoint 1 §16.3.1, napisan nezavisno. */
        static void walSegment(Path root, long id, List<Rec> recs) throws IOException {
            ByteBuffer h = ByteBuffer.allocate(24);
            h.put("LSMWAL01".getBytes(StandardCharsets.US_ASCII)).putShort((short) 1).putShort((short) 0).putLong(id);
            h.putInt(crc(h.array(), 0, 20));
            List<byte[]> parts = new ArrayList<>();
            parts.add(h.array());
            for (Rec r : recs) {
                byte[] k = r.key().getBytes(StandardCharsets.US_ASCII);
                int total = 30 + k.length + r.value().length;
                ByteBuffer f = ByteBuffer.allocate(total);
                f.putInt(total).putInt(~total).putShort((short) 1).put((byte) r.kind()).put((byte) 0).putLong(r.seq())
                        .putShort((short) k.length).putInt(r.value().length).put(k).put(r.value());
                f.putInt(crc(f.array(), 8, total - 12));
                parts.add(f.array());
            }
            int size = parts.stream().mapToInt(a -> a.length).sum();
            ByteBuffer all = ByteBuffer.allocate(size);
            parts.forEach(all::put);
            Files.write(root.resolve("lsm/wal").resolve(String.format("wal-%020d.log", id)), all.array());
        }

        private static int crc(byte[] b, int off, int len) {
            CRC32C c = new CRC32C();
            c.update(b, off, len);
            return (int) c.getValue();
        }
    }
}
