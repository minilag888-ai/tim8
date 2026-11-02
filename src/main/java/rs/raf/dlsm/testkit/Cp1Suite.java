package rs.raf.dlsm.testkit;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Tačno 33 obavezna scenarija Checkpoint 1 (§29), stabilni ID-jevi redosledom iz specifikacije. */
public final class Cp1Suite {

    public static final Map<String, Scenario> ALL;

    static {
        Map<String, Scenario> m = new LinkedHashMap<>();
        // 29.1 API i model — Ilija
        m.put("CP1-API-001", ApiScenarios::api001);
        m.put("CP1-API-002", ApiScenarios::api002);
        m.put("CP1-API-003", ApiScenarios::api003);
        m.put("CP1-API-004", ApiScenarios::api004);
        m.put("CP1-API-005", ApiScenarios::api005);
        // 29.2 WAL — Katarina
        m.put("CP1-WAL-001", WalScenarios::wal001);
        m.put("CP1-WAL-002", WalScenarios::wal002);
        m.put("CP1-WAL-003", WalScenarios::wal003);
        m.put("CP1-WAL-004", WalScenarios::wal004);
        m.put("CP1-WAL-005", WalScenarios::wal005);
        m.put("CP1-WAL-006", WalScenarios::wal006);
        m.put("CP1-WAL-007", WalScenarios::wal007);
        m.put("CP1-WAL-008", WalScenarios::wal008);
        // 29.3 Flush — Ivona (001–006), Ilija (007)
        m.put("CP1-FLS-001", FlushScenarios::fls001);
        m.put("CP1-FLS-002", FlushScenarios::fls002);
        m.put("CP1-FLS-003", FlushScenarios::fls003);
        m.put("CP1-FLS-004", FlushScenarios::fls004);
        m.put("CP1-FLS-005", FlushScenarios::fls005);
        m.put("CP1-FLS-006", FlushScenarios::fls006);
        m.put("CP1-FLS-007", ApiScenarios::fls007);
        // 29.4 SSTable — Vojislav
        m.put("CP1-SST-001", SstScenarios::sst001);
        m.put("CP1-SST-002", SstScenarios::sst002);
        m.put("CP1-SST-003", SstScenarios::sst003);
        m.put("CP1-SST-004", SstScenarios::sst004);
        m.put("CP1-SST-005", SstScenarios::sst005);
        m.put("CP1-SST-006", SstScenarios::sst006);
        // 29.5 Compaction — Vojislav (001, 002)
        m.put("CP1-CMP-001", SstScenarios::cmp001);
        m.put("CP1-CMP-002", SstScenarios::cmp002);
        // 29.5 Compaction — Ivona (003–005)
        m.put("CP1-CMP-003", FlushScenarios::cmp003);
        m.put("CP1-CMP-004", FlushScenarios::cmp004);
        m.put("CP1-CMP-005", FlushScenarios::cmp005);
        // 29.6 Model i kampanja padova — Emilija
        m.put("CP1-MDL-001", ModelScenarios::mdl001);
        m.put("CP1-MDL-002", ModelScenarios::mdl002);
        ALL = Collections.unmodifiableMap(m);
    }

    private Cp1Suite() {
    }
}
