package rs.raf.dlsm.log;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import rs.raf.dlsm.engine.RequestScope;
import rs.raf.dlsm.json.Json;

/**
 * Jedan JSON objekat po redu na stdout. Polja: timestamp, level, event, nodeId, requestId (ako postoji)
 * i dodatni parovi ključ/vrednost (sequence, walSegmentId, tableId, manifestGeneration, failpoint, code...).
 * Vrednosti korisnika se NE upisuju u log.
 */
public final class Log {

    private static volatile String nodeId = "standalone";

    private Log() {
    }

    public static void setNodeId(String id) {
        nodeId = id;
    }

    public static void info(String event, Object... kv) {
        write("INFO", event, kv);
    }

    public static void warn(String event, Object... kv) {
        write("WARN", event, kv);
    }

    public static void error(String event, Object... kv) {
        write("ERROR", event, kv);
    }

    private static void write(String level, String event, Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("timestamp", Instant.now().toString());
        m.put("level", level);
        m.put("event", event);
        m.put("nodeId", nodeId);
        String rid = RequestScope.currentRequestId();
        if (rid != null) {
            m.put("requestId", rid);
        }
        for (int i = 0; i + 1 < kv.length; i += 2) {
            Object v = kv[i + 1];
            m.put(String.valueOf(kv[i]), v instanceof Number || v instanceof Boolean || v == null ? v : String.valueOf(v));
        }
        String line = Json.write(m);
        synchronized (System.out) {
            System.out.println(line);
        }
    }
}
