package rs.raf.dlsm.testkit;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import rs.raf.dlsm.json.Json;

/**
 * Izvršavač studentskih testova za Checkpoint 1 (Project Info §25.2–25.3). Vlasnik: Emilija.
 * <pre>
 *   --list                      ispisuje tačno 33 stabilna ID-ja
 *   --seed N                    pokreće svih 33
 *   --case ID --seed N          pokreće jedan scenario
 *   --reports DIR               gde ide reports/checkpoint-1-&lt;seed&gt;.json (podrazumevano ./reports)
 * </pre>
 * Izlazni kod je 0 samo ako su svi izabrani scenariji PASSED. Izveštaj se upisuje pre izlaza.
 */
public final class Cp1Runner {

    private Cp1Runner() {
    }

    public static void main(String[] args) throws IOException {
        String seedArg = "1";
        String caseId = null;
        boolean list = false;
        Path reports = Path.of(System.getenv().getOrDefault("DLSM_REPORTS_DIR", "reports"));
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--list" -> list = true;
                case "--seed" -> seedArg = args[++i];
                case "--case" -> caseId = args[++i];
                case "--reports" -> reports = Path.of(args[++i]);
                default -> {
                    System.err.println("Nepoznat argument: " + args[i]);
                    System.exit(2);
                }
            }
        }
        if (list) {
            Cp1Suite.ALL.keySet().forEach(System.out::println);
            return;
        }
        long seed = Long.parseUnsignedLong(seedArg);
        List<String> selected = caseId == null ? new ArrayList<>(Cp1Suite.ALL.keySet()) : List.of(caseId);
        Path work = Files.createTempDirectory("dlsm-cp1-");
        List<Object> cases = new ArrayList<>();
        int failed = 0;
        for (String id : selected) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", id);
            Path art = reports.resolve("artifacts").resolve(id);
            long t0 = System.nanoTime();
            Scenario s = Cp1Suite.ALL.get(id);
            String status;
            if (s == null) {
                status = "FAILED";
                entry.put("phase", "setup");
                entry.put("diagnostic", "Nepoznat ID scenarija");
            } else {
                try (Ctx ctx = new Ctx(id, seed, work.resolve(id), art)) {
                    try {
                        s.run(ctx);
                        status = "PASSED";
                    } catch (Throwable e) {
                        status = "FAILED";
                        entry.put("phase", ctx.phase());
                        entry.put("diagnostic", String.valueOf(e.getMessage()));
                        StringWriter sw = new StringWriter();
                        e.printStackTrace(new PrintWriter(sw));
                        Files.writeString(art.resolve("failure.txt"), sw.toString(), StandardCharsets.UTF_8);
                    }
                }
            }
            long ms = (System.nanoTime() - t0) / 1_000_000;
            entry.put("status", status);
            entry.put("seed", Long.toUnsignedString(seed));
            entry.put("durationMs", ms);
            entry.put("artifactPaths", artifacts(art));
            cases.add(entry);
            if (!"PASSED".equals(status)) {
                failed++;
            }
            System.out.printf("%-12s %-7s %6d ms%s%n", id, status, ms,
                    "PASSED".equals(status) ? "" : "  [" + entry.get("phase") + "] " + entry.get("diagnostic"));
            deleteTree(work.resolve(id));
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schemaVersion", 1);
        report.put("suite", "checkpoint-1");
        report.put("seed", Long.toUnsignedString(seed));
        report.put("selectedCaseIds", selected);
        report.put("cases", cases);
        Files.createDirectories(reports);
        Path out = reports.resolve("checkpoint-1-" + Long.toUnsignedString(seed) + ".json");
        Files.writeString(out, Json.write(report), StandardCharsets.UTF_8);
        System.out.println("Izveštaj: " + out + "  (" + (selected.size() - failed) + "/" + selected.size() + " PASSED)");
        deleteTree(work);
        System.exit(failed == 0 ? 0 : 1);
    }

    private static List<Object> artifacts(Path dir) throws IOException {
        List<Object> out = new ArrayList<>();
        if (Files.isDirectory(dir)) {
            try (Stream<Path> s = Files.list(dir)) {
                s.sorted().forEach(p -> out.add(p.toString()));
            }
        }
        return out;
    }

    private static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) {
            return;
        }
        try (Stream<Path> s = Files.walk(p)) {
            for (Path x : (Iterable<Path>) s.sorted(Comparator.reverseOrder())::iterator) {
                Files.deleteIfExists(x);
            }
        }
    }
}
