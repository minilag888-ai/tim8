package rs.raf.dlsm.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import rs.raf.dlsm.manifest.ManifestInspector;
import rs.raf.dlsm.sstable.SSTableInspector;
import rs.raf.dlsm.wal.WalInspector;

/**
 * Offline pregled fajlova baze (Checkpoint 1 §25): {@code dbctl inspect manifest | wal | sstable <id>}.
 * Samo čita fajlove; nikada ih ne popravlja. Izlazni kod: 0 = sve važeće, 1 = pronađeno oštećenje,
 * 2 = pogrešna upotreba ili nedostajući fajl. Vlasnik: Emilija (spaja inspector-e Vojislava, Katarine i Ivone).
 */
public final class InspectCli {

    private InspectCli() {
    }

    public static void main(String[] args) throws Exception {
        Path dataDir = Path.of(System.getenv().getOrDefault("DATA_DIR", "/var/lib/dlsm"));
        int i = 0;
        if (args.length >= 2 && args[0].equals("--data-dir")) {
            dataDir = Path.of(args[1]);
            i = 2;
        }
        if (args.length <= i) {
            usage();
            System.exit(2);
        }
        Path lsm = dataDir.resolve("lsm");
        if (!Files.isDirectory(lsm)) {
            System.err.println("Ne postoji direktorijum baze: " + lsm);
            System.exit(2);
        }
        int code = switch (args[i]) {
            case "manifest" -> ManifestInspector.inspect(lsm, System.out);
            case "wal" -> WalInspector.inspect(lsm.resolve("wal"), System.out);
            case "sstable" -> {
                if (args.length <= i + 1) {
                    usage();
                    yield 2;
                }
                yield SSTableInspector.inspect(lsm.resolve("sst"), args[i + 1], System.out);
            }
            default -> {
                usage();
                yield 2;
            }
        };
        System.exit(code);
    }

    private static void usage() {
        System.err.println("Upotreba: InspectCli [--data-dir DIR] manifest | wal | sstable <id>");
    }
}
