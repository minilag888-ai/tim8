package rs.raf.dlsm.api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import rs.raf.dlsm.engine.EngineException;
import rs.raf.dlsm.engine.NodeState;
import rs.raf.dlsm.json.Json;
import rs.raf.dlsm.maintenance.CompactionJob;
import rs.raf.dlsm.maintenance.CompactionResult;
import rs.raf.dlsm.maintenance.FlushJob;
import rs.raf.dlsm.maintenance.FlushResult;

/**
 * Admin API na portu 8082 (Project Info §20, Checkpoint 1 §23–24). Vlasnik: Ilija.
 * <pre>
 * GET  /health/live                    200 dok proces radi
 * GET  /health/ready                   200 samo u READY, inače 503 sa lifecycle
 * GET  /admin/v1/status                zajednički status sa standaloneStorage
 * GET  /admin/v1/storage/status        status skladišta (§24)
 * POST /admin/v1/storage/flush         ručni flush (§23.1)
 * POST /admin/v1/storage/compact       ručni compaction (§23.2)
 * POST /admin/v1/storage/stats/reset   resetovanje brojača (§24.1)
 * </pre>
 * Failpoint rute ({@code /admin/v1/failpoints}) registruje Emilija.
 */
public final class AdminApi {

    private final NodeState nodeState;
    private final AtomicReference<NodeRuntime> runtime;
    private final StatusService status;

    public AdminApi(NodeState nodeState, AtomicReference<NodeRuntime> runtime, StatusService status) {
        this.nodeState = nodeState;
        this.runtime = runtime;
        this.status = status;
    }

    /** Za testove bez skladišta: samo health rute. */
    public AdminApi(NodeState nodeState) {
        this(nodeState, new AtomicReference<>(), new StatusService(nodeState, new AtomicReference<>()));
    }

    public void register(HttpServer server) {
        server.createContext("/health/live", ex -> HttpSupport.send(ex, 200, health()));
        server.createContext("/health/ready", ex -> {
            if (nodeState.isReady()) {
                HttpSupport.send(ex, 200, health());
            } else {
                Map<String, Object> body = HttpSupport.error("NODE_NOT_READY", "Čvor nije spreman",
                        ex.getRequestHeaders().getFirst("X-Request-Id"), true, false);
                body.put("lifecycle", nodeState.lifecycle().name());
                NodeState.FatalError fe = nodeState.lastFatalError();
                body.put("lastFatalError", fe == null ? null : Json.obj("code", fe.code(), "message", fe.message()));
                HttpSupport.send(ex, 503, body);
            }
        });
        server.createContext("/admin/v1/status", ex -> get(ex, status.commonStatus()));
        server.createContext("/admin/v1/storage/status", ex -> get(ex, status.storageStatus()));
        server.createContext("/admin/v1/storage/stats/reset", ex -> {
            if (!"POST".equals(ex.getRequestMethod())) {
                error(ex, ApiError.invalid("Koristi POST"));
                return;
            }
            HttpSupport.send(ex, 200, Json.obj("protocolVersion", 1, "status", "OK", "resetEpoch", status.resetStats()));
        });
        server.createContext("/admin/v1/storage/flush", this::flush);
        server.createContext("/admin/v1/storage/compact", this::compact);
    }

    private void flush(HttpExchange ex) throws IOException {
        try {
            long deadline = maintenanceRequest(ex);
            FlushResult r = new FlushJob(runtime.get().maintenance()).run(deadline);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("protocolVersion", 1);
            body.put("status", "OK");
            body.put("operation", "FLUSH");
            body.put("result", r.result());
            body.put("targetSequence", r.targetSequence());
            body.put("publishedTableIds", r.publishedTableIds());
            body.put("manifestGeneration", r.manifestGeneration());
            body.put("flushedThroughSequence", r.flushedThroughSequence());
            HttpSupport.send(ex, 200, body);
        } catch (ApiError e) {
            error(ex, e);
        } catch (EngineException e) {
            HttpSupport.send(ex, e.httpStatus(), HttpSupport.error(e.code(), e.getMessage(), null, e.retryable(), false));
        }
    }

    private void compact(HttpExchange ex) throws IOException {
        try {
            long deadline = maintenanceRequest(ex);
            CompactionResult r = new CompactionJob(runtime.get().maintenance()).run(deadline);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("protocolVersion", 1);
            body.put("status", "OK");
            body.put("operation", "COMPACTION");
            body.put("result", r.result());
            body.put("inputTableIds", r.inputTableIds());
            body.put("outputTableId", r.outputTableId());
            body.put("manifestGeneration", r.manifestGeneration());
            HttpSupport.send(ex, 200, body);
        } catch (ApiError e) {
            error(ex, e);
        } catch (EngineException e) {
            HttpSupport.send(ex, e.httpStatus(), HttpSupport.error(e.code(), e.getMessage(), null, e.retryable(), false));
        }
    }

    /** Proverava metod, telo (prazno ili {}), rok i spremnost; vraća rok u nanosekundama. */
    private long maintenanceRequest(HttpExchange ex) throws IOException, ApiError {
        if (!"POST".equals(ex.getRequestMethod())) {
            throw ApiError.invalid("Koristi POST");
        }
        String body = HttpSupport.readBody(ex).trim();
        if (!body.isEmpty()) {
            try {
                Json.parseObject(body);
            } catch (Json.JsonException e) {
                throw ApiError.invalid("Telo mora biti prazno ili JSON objekat");
            }
        }
        int ms = Validation.timeoutMs(ex.getRequestHeaders().getFirst("X-Request-Timeout-Ms"));
        if (!nodeState.isReady() || runtime.get() == null) {
            throw new ApiError(503, "NODE_NOT_READY", "Čvor nije spreman (" + nodeState.lifecycle() + ")", true);
        }
        return System.nanoTime() + ms * 1_000_000L;
    }

    private static void get(HttpExchange ex, Map<String, Object> body) throws IOException {
        if (!"GET".equals(ex.getRequestMethod())) {
            error(ex, ApiError.invalid("Koristi GET"));
            return;
        }
        HttpSupport.send(ex, 200, body);
    }

    private static void error(HttpExchange ex, ApiError e) throws IOException {
        HttpSupport.send(ex, e.httpStatus(), HttpSupport.error(e.code(), e.getMessage(), null, e.retryable(), false));
    }

    private Map<String, Object> health() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "OK");
        m.put("nodeId", nodeState.nodeId());
        m.put("lifecycle", nodeState.lifecycle().name());
        return m;
    }
}
