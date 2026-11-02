package rs.raf.dlsm.testkit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * CP1-FLS-001 … CP1-FLS-006 i CP1-CMP-003 … CP1-CMP-005 (Checkpoint 1 §29.3 i §29.5). Vlasnik: Ivona.
 * Svaki pad je pravi kraj procesa (failpoint CRASH → Runtime.halt), a restart je nov proces nad istim volumenom.
 */
public final class FlushScenarios {

    private FlushScenarios() {
    }

    // ================================================================ pomoćno

    /** Mešavina iz §29.3: PUT, prepisivanje, prazna vrednost i DELETE (uključujući ključ koji nije postojao). */
    static RefModel mixed(Ctx c, Node n, String prefix, int count) {
        RefModel m = new RefModel();
        for (int i = 0; i < count; i++) {
            byte[] v = new byte[1 + c.rnd().nextInt(40)];
            c.rnd().nextBytes(v);
            c.putOk(n, m, prefix + String.format("%03d", i), v);
        }
        c.putOk(n, m, prefix + "000", "prepisano");
        c.putOk(n, m, prefix + "001", "");
        c.deleteOk(n, m, prefix + "002");
        c.deleteOk(n, m, prefix + "nikad");
        return m;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> manifest(Node n) {
        return (Map<String, Object>) n.status().get("manifest");
    }

    static long generation(Node n) {
        return ((Number) manifest(n).get("generation")).longValue();
    }

    static List<String> files(Path dir, String suffix) throws Exception {
        List<String> out = new ArrayList<>();
        for (Path p : Ctx.list(dir, suffix)) {
            out.add(p.getFileName().toString());
        }
        return out;
    }

    static String manifestText(Node n) throws Exception {
        return Files.readString(n.lsm().resolve("MANIFEST.json"));
    }

    /** Aktivira CRASH na održavanju, pokreće ga i čeka kraj procesa. */
    static void crashDuring(Ctx c, Node n, String hook, boolean flush) throws Exception {
        n.arm("crash", Ctx.json("name", hook, "action", "CRASH"));
        Resp r = flush ? n.flush() : n.compact();
        c.check(r.noResponse(), "održavanje nema uspešan odgovor na " + hook + " (" + r.status() + ")");
        n.awaitExit();
    }

    @SuppressWarnings("unchecked")
    static void awaitWalCleanup(Ctx c, Node n) throws Exception {
        c.await(() -> {
            for (Object o : (List<Object>) n.status().get("walSegments")) {
                if ("COVERED_PENDING_DELETE".equals(((Map<String, Object>) o).get("role"))) {
                    return false;
                }
            }
            return true;
        }, 15_000, "pokriveni WAL je očišćen");
    }

    static void awaitIdle(Ctx c, Node n) throws Exception {
        c.await(() -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) n.status().get("maintenance");
            return "IDLE".equals(m.get("phase"));
        }, 15_000, "održavanje miruje");
    }

    // ================================================================ 29.3 flush

    /** Ručni flush, no-op flush i restart. */
    public static void fls001(Ctx c) throws Exception {
        Node n = c.node("n").start();
        c.eq(0L, manifest(n).get("sstableCount"), "nema prethodne SSTable");
        RefModel m = mixed(c, n, "f", 60);
        long t = ((Number) n.status().get("nextSequence")).longValue() - 1;
        Resp f1 = n.flush();
        c.eq("PUBLISHED", f1.get("result"), "prvi flush");
        c.eq(t, f1.num("targetSequence"), "targetSequence = T");
        c.check(!f1.list("publishedTableIds").isEmpty(), "neprazna lista objavljenih tabela");
        c.check(f1.num("flushedThroughSequence") >= t, "flushedThroughSequence >= T");
        awaitIdle(c, n);
        Map<String, Object> st = n.status();
        c.check(st.get("immutableMemtable") == null, "nema nepromenljive MemTable");
        long gen = generation(n);
        c.eq(f1.num("manifestGeneration"), gen, "vraćena generacija je objavljena");
        @SuppressWarnings("unchecked")
        List<Object> tables = (List<Object>) st.get("sstables");
        @SuppressWarnings("unchecked")
        long tid = ((Number) ((Map<String, Object>) tables.get(0)).get("tableId")).longValue();
        c.eq(f1.list("publishedTableIds").get(0), tid, "manifest referencira vraćenu tabelu");
        Resp f2 = n.flush();
        c.eq("NO_OP", f2.get("result"), "drugi flush je NO_OP");
        c.eq(0, f2.list("publishedTableIds").size(), "prazna lista tabela");
        c.eq(gen, generation(n), "generacija nepromenjena pri NO_OP");
        awaitWalCleanup(c, n);
        n.resetStats();
        c.assertModel(n, m, List.of("fnikad"));
        c.check(n.stat("sstableDataBlocksRead") > 0, "čitanja dolaze iz SSTable blokova");
        n.restart();
        n.resetStats();
        c.assertModel(n, m, List.of("fnikad"));
        c.check(n.stat("sstableDataBlocksRead") > 0, "posle restarta čitanja iz SSTable");
        c.eq(0L, n.stat("walRecordsReplayed"), "nijedan pokriven zapis nije ponovo primenjen");
    }

    /** Pad pre force-a ili konačnog preimenovanja SSTable. */
    public static void fls002(Ctx c) throws Exception {
        for (String hook : List.of("sstable.after-write.before-sync", "sstable.after-sync.before-rename")) {
            c.phase(hook);
            Node n = c.node("n-" + hook.replace('.', '_')).start();
            RefModel m = mixed(c, n, "p", 80);
            String before = manifestText(n);
            List<String> walBefore = files(n.lsm().resolve("wal"), ".log");
            crashDuring(c, n, hook, true);
            List<String> tmp = files(n.lsm().resolve("tmp"), ".tmp");
            c.check(!tmp.isEmpty(), "privremeni izlaz postoji pre oporavka " + tmp);
            c.eq(List.of(), files(n.lsm().resolve("sst"), ".sst"), "nema konačne tabele");
            c.eq(before, manifestText(n), "pre oporavka merodavan je M");
            c.check(files(n.lsm().resolve("wal"), ".log").containsAll(walBefore), "zaštitni WAL ostaje");
            n.start();
            c.check(n.stat("temporaryFilesRemoved") >= 1, "oporavak uklanja privremeni izlaz");
            c.check(n.stat("walRecordsReplayed") > 0, "podaci se oporavljaju iz WAL-a");
            String after = manifestText(n);
            for (String t : tmp) {
                c.check(!after.contains(t.replace(".tmp", "")), "privremeni izlaz nikad nije referenciran");
            }
            c.assertModel(n, m, List.of("pnikad"));
            // oporavljena generacija ide na flush odmah posle spremnosti (DESIGN.md): to je kasniji uspešan flush
            c.check(List.of("PUBLISHED", "NO_OP").contains(n.flush().get("result")), "kasniji flush uspeva");
            n.restart();
            c.assertModel(n, m, List.of("pnikad"));
            n.kill();
        }
    }

    /** Pad posle finalizacije SSTable, pre manifesta. */
    public static void fls003(Ctx c) throws Exception {
        Node n = c.node("n").start();
        RefModel m = mixed(c, n, "o", 80);
        String before = manifestText(n);
        crashDuring(c, n, "flush.after-sstable-finalize.before-manifest", true);
        List<String> sst = files(n.lsm().resolve("sst"), ".sst");
        c.eq(1, sst.size(), "postoji finalizovana tabela");
        c.eq(before, manifestText(n), "M ne referencira finalizovanu tabelu");
        c.check(!before.contains(sst.get(0)), "ime tabele nije u manifestu");
        long orphanId = Long.parseLong(sst.get(0).replaceAll("\\D", ""));
        n.start();
        c.check(n.stat("orphanFilesRemoved") >= 1, "orphan je uklonjen");
        c.check(!files(n.lsm().resolve("sst"), ".sst").contains(sst.get(0)), "orphan nije živ");
        c.check(n.stat("walRecordsReplayed") > 0, "oporavak ide kroz stari WAL");
        c.assertModel(n, m, List.of("onikad"));
        n.flush();
        awaitIdle(c, n);
        @SuppressWarnings("unchecked")
        List<Object> live = (List<Object>) n.status().get("sstables");
        c.eq(1, live.size(), "jedna stvarno objavljena tabela");
        @SuppressWarnings("unchecked")
        long newId = ((Number) ((Map<String, Object>) live.get(0)).get("tableId")).longValue();
        c.check(newId > orphanId, "ID orphan-a se preskače (" + newId + " > " + orphanId + ")");
        n.restart();
        c.assertModel(n, m, List.of("onikad"));
    }

    /** Pad posle objavljivanja manifesta, pre brisanja WAL-a. */
    public static void fls004(Ctx c) throws Exception {
        Node n = c.node("n").start();
        RefModel m = mixed(c, n, "w", 80);
        long g = generation(n);
        long last = ((Number) n.status().get("nextSequence")).longValue() - 1;
        crashDuring(c, n, "flush.after-manifest-publish.before-wal-delete", true);
        n.start();
        c.check(generation(n) >= g + 1, "nova generacija manifesta");
        c.check(((Number) manifest(n).get("flushedThroughSequence")).longValue() >= last, "high-water pokriva generaciju");
        c.eq(0L, n.stat("walRecordsReplayed"), "pokriveni zapisi se ne primenjuju ponovo");
        c.assertModel(n, m, List.of("wnikad"));
        List<String> scan = c.scanList(n, "w", "x", 1000);
        c.eq(scan.stream().distinct().count(), (long) scan.size(), "skeniranje bez duplikata");
        c.putOk(n, m, "wposle", "nepokriven");
        awaitWalCleanup(c, n);
        n.restart();
        c.assertModel(n, m, List.of("wnikad"));
        c.eq(1L, n.stat("walRecordsReplayed"), "samo nepokriven zapis se ponovo primenjuje");
    }

    /** I/O greška pre preimenovanja manifesta. */
    public static void fls005(Ctx c) throws Exception {
        Node n = c.node("n").start();
        RefModel m = mixed(c, n, "e", 80);
        String before = manifestText(n);
        List<String> walBefore = files(n.lsm().resolve("wal"), ".log");
        n.arm("io", Ctx.json("name", "manifest.after-temp-sync.before-rename", "action", "IO_ERROR"));
        Resp r = n.flush();
        c.check(!r.ok(), "prvi flush ne uspeva");
        c.eq("STORAGE_IO", r.code(), "kod greške");
        c.eq(before, manifestText(n), "stari manifest je merodavan");
        c.check(files(n.lsm().resolve("wal"), ".log").containsAll(walBefore), "zaštitni WAL ostaje");
        c.eq(List.of(), files(n.lsm().resolve("sst"), ".sst"), "nereferencirani izlaz nije živ");
        c.eq("READY", n.admin("GET", "/admin/v1/status", null).get("lifecycle"), "politika: ostaje READY");
        c.assertModel(n, m, List.of("enikad"));
        n.admin("DELETE", "/admin/v1/failpoints/io", null);
        c.eq("PUBLISHED", n.flush().get("result"), "ponovni pokušaj objavljuje");
        n.restart();
        c.assertModel(n, m, List.of("enikad"));
    }

    /** Otkaz posle preimenovanja manifesta, pre sinhronizacije direktorijuma. */
    public static void fls006(Ctx c) throws Exception {
        for (String action : List.of("CRASH", "IO_ERROR")) {
            c.phase(action);
            Node n = c.node("n-" + action).start();
            RefModel m = mixed(c, n, "d", 80);
            String old = manifestText(n);
            List<String> walBefore = files(n.lsm().resolve("wal"), ".log");
            n.arm("ds", Ctx.json("name", "manifest.after-rename.before-directory-sync", "action", action));
            Resp r = n.flush();
            if ("CRASH".equals(action)) {
                c.check(r.noResponse(), "nema odgovora");
                n.awaitExit();
            } else {
                c.check(!r.ok(), "flush ne uspeva");
                c.eq("FAILED", n.admin("GET", "/admin/v1/status", null).get("lifecycle"), "IO_ERROR vodi u FAILED");
                n.kill();
            }
            c.check(files(n.lsm().resolve("wal"), ".log").containsAll(walBefore), "nijedan ulaz nije obrisan " + walBefore + " -> " + files(n.lsm().resolve("wal"), ".log"));
            String now = manifestText(n);
            boolean isOld = now.equals(old);
            c.trace("zatečen " + (isOld ? "stari" : "novi") + " manifest");
            n.start();
            c.assertModel(n, m, List.of("dnikad"));
            if (!isOld) {
                c.eq(1L, manifest(n).get("sstableCount"), "novi manifest referencira novu tabelu");
            }
            n.restart();
            c.assertModel(n, m, List.of("dnikad"));
            n.kill();
        }
    }

    // ================================================================ 29.5 compaction

    /** Dve preklopljene objavljene tabele. */
    static RefModel twoTables(Ctx c, Node n) {
        RefModel m = new RefModel();
        c.putOk(n, m, "alpha", "A1");
        c.putOk(n, m, "beta", "B1");
        c.putOk(n, m, "delta", "D1");
        c.flushPublished(n);
        c.putOk(n, m, "alpha", "A2");
        c.deleteOk(n, m, "beta");
        c.putOk(n, m, "gamma", "");
        c.flushPublished(n);
        return m;
    }

    /** Pad posle finalizacije izlaza, pre manifesta. */
    public static void cmp003(Ctx c) throws Exception {
        Node n = c.node("n").start();
        RefModel m = twoTables(c, n);
        String before = manifestText(n);
        List<String> inputs = files(n.lsm().resolve("sst"), ".sst");
        crashDuring(c, n, "compaction.after-output-finalize.before-manifest", false);
        List<String> now = files(n.lsm().resolve("sst"), ".sst");
        c.eq(3, now.size(), "ulazi i nereferencirani izlaz su prisutni");
        c.eq(before, manifestText(n), "manifest M nepromenjen");
        n.start();
        c.eq(inputs, files(n.lsm().resolve("sst"), ".sst"), "ulazi ostaju, izlaz uklonjen");
        c.check(n.stat("orphanFilesRemoved") >= 1, "izlaz je očišćen kao orphan");
        c.eq(0L, n.stat("compactionPublications"), "nema prijavljenog objavljivanja");
        c.assertModel(n, m, List.of("beta"));
        c.eq("PUBLISHED", n.compact().get("result"), "ponovni pokušaj uspeva");
        n.restart();
        c.assertModel(n, m, List.of("beta"));
    }

    /** Pad posle manifesta, pre brisanja ulaza. */
    public static void cmp004(Ctx c) throws Exception {
        Node n = c.node("n").start();
        RefModel m = twoTables(c, n);
        List<String> inputs = files(n.lsm().resolve("sst"), ".sst");
        Object hw = manifest(n).get("flushedThroughSequence");
        crashDuring(c, n, "compaction.after-manifest-publish.before-input-delete", false);
        String man = manifestText(n);
        for (String in : inputs) {
            c.check(!man.contains(in), "novi manifest ne referencira ulaz " + in);
        }
        n.start();
        c.eq(1L, manifest(n).get("sstableCount"), "merodavan je samo izlaz");
        c.eq(hw, manifest(n).get("flushedThroughSequence"), "high-water nepromenjen");
        c.assertModel(n, m, List.of("beta"));
        c.await(() -> files(n.lsm().resolve("sst"), ".sst").size() == 1, 15_000, "stari ulazi očišćeni");
        n.restart();
        c.assertModel(n, m, List.of("beta"));
        c.eq(List.of("alpha=A2", "delta=D1", "gamma="), c.scanList(n, "a", "z", 100), "jedna verzija po ključu");
    }

    /** Trka flush-a i izbora ulaza za compaction. */
    public static void cmp005(Ctx c) throws Exception {
        Node n = c.node("n").start();
        RefModel m = twoTables(c, n);
        @SuppressWarnings("unchecked")
        List<Object> sel = (List<Object>) n.status().get("sstables");
        c.putOk(n, m, "newcomer", "N");
        n.arm("sel", Ctx.json("name", "compaction.after-selection.before-output", "action", "PAUSE",
                "pauseTimeoutMs", 60_000));
        CompletableFuture<Resp> comp = n.async(n::compact);
        n.awaitTriggered("sel");
        CompletableFuture<Resp> flush = n.async(n::flush);
        c.trace("flush poslat dok je compaction pauziran");
        Thread.sleep(300);
        n.release("sel");
        Resp cr = comp.get(30, TimeUnit.SECONDS);
        Resp fr = flush.get(30, TimeUnit.SECONDS);
        if (fr.status() == 409) {
            fr = n.flush();
        }
        c.eq("PUBLISHED", cr.get("result"), "compaction objavljen");
        c.eq("PUBLISHED", fr.get("result"), "flush objavljen");
        c.eq(sel.size(), cr.list("inputTableIds").size(), "izabrani su obe stare tabele");
        awaitIdle(c, n);
        @SuppressWarnings("unchecked")
        List<Object> fin = (List<Object>) n.status().get("sstables");
        c.eq(2, fin.size(), "izlaz compaction-a i flush tabela");
        for (Object o : fin) {
            @SuppressWarnings("unchecked")
            Object id = ((Map<String, Object>) o).get("tableId");
            for (Object s : sel) {
                @SuppressWarnings("unchecked")
                Object sid = ((Map<String, Object>) s).get("tableId");
                c.check(!id.equals(sid), "izabrani ulaz " + sid + " je zamenjen");
            }
        }
        c.assertModel(n, m, List.of("beta"));
        n.restart();
        c.assertModel(n, m, List.of("beta"));
    }
}
