package rs.raf.dlsm.failpoint;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Katalog svih 21 obaveznih failpoint-a za Checkpoint 1 (§26.1) i akcija koje podržavaju.
 * Svi podržavaju PAUSE i CRASH; IO_ERROR samo obavezna mapiranja na poziv skladišta.
 */
public final class FailpointCatalog {

    private static final Set<Failpoints.Action> BASIC = EnumSet.of(Failpoints.Action.PAUSE, Failpoints.Action.CRASH);
    private static final Set<Failpoints.Action> WITH_IO =
            EnumSet.of(Failpoints.Action.PAUSE, Failpoints.Action.CRASH, Failpoints.Action.IO_ERROR);

    /** Ime → podržane akcije, redosledom iz specifikacije. */
    public static final Map<String, Set<Failpoints.Action>> ALL;

    static {
        Map<String, Set<Failpoints.Action>> m = new LinkedHashMap<>();
        // Mutacije i WAL — Katarina (osim memtable.* — Ilija)
        m.put("wal.before-append", WITH_IO);
        m.put("wal.after-record-prefix", WITH_IO);
        m.put("wal.after-append.before-sync", WITH_IO);
        m.put("wal.after-sync.before-memtable", BASIC);
        m.put("memtable.after-apply.before-response", BASIC);
        // Rotacija i scan — Ilija
        m.put("rotation.after-new-wal-sync.before-switch", BASIC);
        m.put("rotation.after-switch.before-flush", BASIC);
        m.put("scan.after-snapshot.before-merge", BASIC);
        // SSTable — Vojislav; manifest — Ivona
        m.put("sstable.after-write.before-sync", WITH_IO);
        m.put("sstable.after-sync.before-rename", WITH_IO);
        m.put("sstable.after-rename.before-directory-sync", WITH_IO);
        m.put("manifest.after-temp-sync.before-rename", WITH_IO);
        m.put("manifest.after-rename.before-directory-sync", WITH_IO);
        // Flush i compaction — Ivona
        m.put("flush.after-sstable-finalize.before-manifest", BASIC);
        m.put("flush.after-manifest-publish.before-wal-delete", BASIC);
        m.put("compaction.after-selection.before-output", BASIC);
        m.put("compaction.after-output-finalize.before-manifest", BASIC);
        m.put("compaction.after-manifest-publish.before-input-delete", BASIC);
        // Oporavak — Katarina
        m.put("recovery.after-manifest-load", BASIC);
        m.put("recovery.after-wal-tail-truncate", BASIC);
        m.put("recovery.after-wal-replay.before-ready", BASIC);
        ALL = java.util.Collections.unmodifiableMap(m);
    }

    private FailpointCatalog() {
    }

    public static boolean exists(String name) {
        return ALL.containsKey(name);
    }

    public static boolean supports(String name, Failpoints.Action action) {
        Set<Failpoints.Action> s = ALL.get(name);
        return s != null && s.contains(action);
    }
}
