package rs.raf.dlsm.maintenance;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;
import rs.raf.dlsm.io.FileOps;
import rs.raf.dlsm.log.Log;
import rs.raf.dlsm.manifest.Manifest;
import rs.raf.dlsm.node.NodeDir;
import rs.raf.dlsm.sstable.SSTableFiles;
import rs.raf.dlsm.sstable.SSTableMeta;

/**
 * Klasifikacija i brisanje privremenih i orphan fajlova pri oporavku (Checkpoint 1 §21.1 koraci 5–6).
 * Vlasnik: Ivona. Odluke se izvode iz MERODAVNOG manifesta, nikad iz imena fajlova:
 * <ul>
 *   <li>sve u lsm/tmp/ i node.json.tmp je privremeno i briše se;</li>
 *   <li>.sst fajl u sst/ koji manifest ne referencira je orphan i briše se (čak i ako je ispravan);</li>
 *   <li>WAL segmente ovde NE dira — njihovu pokrivenost utvrđuje oporavak posle čitanja.</li>
 * </ul>
 * Ponavljanje posle novog pada daje isti zaključak (idempotentno).
 */
public final class OrphanCleaner {

    private OrphanCleaner() {
    }

    public static void clean(FileOps ops, NodeDir dir, Manifest manifest) throws IOException {
        MaintenanceStats stats = MaintenanceStats.GLOBAL;
        int temps = 0;
        try (Stream<Path> s = Files.list(dir.tmpDir())) {
            for (Path p : (Iterable<Path>) s::iterator) {
                if (Files.isRegularFile(p)) {
                    ops.delete(p);
                    stats.temporaryFilesRemoved.incrementAndGet();
                    temps++;
                    Log.info("cleanup.temporary-removed", "file", p.getFileName());
                }
            }
        }
        if (temps > 0) {
            ops.forceDirectory(dir.tmpDir(), null);
        }
        Path nodeTmp = dir.root().resolve("node.json.tmp");
        if (Files.exists(nodeTmp)) {
            ops.delete(nodeTmp);
            stats.temporaryFilesRemoved.incrementAndGet();
            ops.forceDirectory(dir.root(), null);
        }
        Set<String> referenced = new HashSet<>();
        for (SSTableMeta t : manifest.sstables()) {
            referenced.add(t.file());
        }
        int orphans = 0;
        try (Stream<Path> s = Files.list(dir.sstDir())) {
            for (Path p : (Iterable<Path>) s::iterator) {
                String name = p.getFileName().toString();
                if (SSTableFiles.parseTableId(name) > 0 && !referenced.contains(name)) {
                    ops.delete(p);
                    stats.orphanFilesRemoved.incrementAndGet();
                    orphans++;
                    Log.info("cleanup.orphan-removed", "file", name, "manifestGeneration", manifest.generation());
                }
            }
        }
        if (orphans > 0) {
            ops.forceDirectory(dir.sstDir(), null);
        }
    }
}
