package rs.raf.dlsm.api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import rs.raf.dlsm.engine.EngineException;
import rs.raf.dlsm.engine.NodeState;
import rs.raf.dlsm.engine.RequestScope;
import rs.raf.dlsm.engine.StorageEngine;
import rs.raf.dlsm.json.Json;
import rs.raf.dlsm.record.InternalRecord;

/**
 * Javni API na portu 8080 (Project Info §18): PUT/GET/DELETE /v1/kv/{key} i GET /v1/scan.
 * U standalone režimu svaki odgovor nosi partitionId = 0 i epoch = 0.
 */
public final class PublicApi {

    private final StorageEngine engine;
    private final NodeState nodeState;

    public PublicApi(StorageEngine engine, NodeState nodeState) {
        this.engine = engine;
        this.nodeState = nodeState;
    }

    public void register(HttpServer server) {
        server.createContext("/v1/kv/", this::handleKv);
        server.createContext("/v1/scan", this::handleScan);
        server.createContext("/", ex -> respondError(ex, null,
                new ApiError(404, "NOT_FOUND", "Nepoznata putanja", false)));
    }

    // ---------------------------------------------------------------- /v1/kv/{key}

    private void handleKv(HttpExchange ex) throws IOException {
        String rid = ex.getRequestHeaders().getFirst("X-Request-Id");
        try {
            Validation.requestId(rid);
            long deadline = deadline(ex);
            String raw = ex.getRequestURI().getRawPath().substring("/v1/kv/".length());
            String key = Validation.key(raw);
            String method = ex.getRequestMethod();
            switch (method) {
                case "PUT" -> {
                    Object value = Json.parseObject(HttpSupport.readBody(ex)).get("value");
                    byte[] bytes = Validation.value(value);
                    requireReady();
                    RequestScope scope = RequestScope.enter(rid);
                    try {
                        engine.put(key, bytes, deadline);
                    } finally {
                        scope.close();
                    }
                    HttpSupport.send(ex, 200, ok(rid));
                }
                case "DELETE" -> {
                    requireReady();
                    RequestScope scope = RequestScope.enter(rid);
                    try {
                        engine.delete(key, deadline);
                    } finally {
                        scope.close();
                    }
                    HttpSupport.send(ex, 200, ok(rid));
                }
                case "GET" -> {
                    requireReady();
                    Optional<byte[]> v;
                    RequestScope scope = RequestScope.enter(rid);
                    try {
                        v = engine.get(key);
                    } finally {
                        scope.close();
                    }
                    if (v.isEmpty()) {
                        throw new ApiError(404, "NOT_FOUND", "Ključ ne postoji", false);
                    }
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("status", "OK");
                    body.put("requestId", rid);
                    body.put("key", key);
                    body.put("value", Base64.getEncoder().encodeToString(v.get()));
                    body.put("partitionId", 0);
                    body.put("epoch", 0);
                    HttpSupport.send(ex, 200, body);
                }
                default -> throw ApiError.invalid("Metod " + method + " nije podržan za /v1/kv");
            }
        } catch (ApiError e) {
            respondError(ex, rid, e);
        } catch (EngineException e) {
            HttpSupport.send(ex, e.httpStatus(),
                    HttpSupport.error(e.code(), e.getMessage(), safeRid(rid), e.retryable(), true));
        } catch (Json.JsonException e) {
            respondError(ex, rid, ApiError.invalid("Telo nije ispravan JSON: " + e.getMessage()));
        } catch (RuntimeException e) {
            HttpSupport.send(ex, 500, HttpSupport.error("INTERNAL_ERROR", "Interna greška", safeRid(rid), false, true));
        }
    }

    // ---------------------------------------------------------------- /v1/scan

    private void handleScan(HttpExchange ex) throws IOException {
        String rid = ex.getRequestHeaders().getFirst("X-Request-Id");
        try {
            Validation.requestId(rid);
            deadline(ex);
            if (!"GET".equals(ex.getRequestMethod())) {
                throw ApiError.invalid("SCAN koristi GET");
            }
            Map<String, String> q = HttpSupport.query(ex);
            String start = Validation.key(q.get("start"));
            String end = Validation.key(q.get("end"));
            if (start.compareTo(end) >= 0) {
                throw ApiError.invalid("start mora biti manji od end");
            }
            int limit = Validation.limit(q.get("limit"));
            requireReady();
            List<InternalRecord> recs;
            RequestScope scope = RequestScope.enter(rid);
            try {
                recs = engine.scan(start, end, limit);
            } finally {
                scope.close();
            }
            List<Object> items = new ArrayList<>();
            for (InternalRecord r : recs) {
                items.add(Json.obj("key", r.key(), "value", Base64.getEncoder().encodeToString(r.value())));
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "OK");
            body.put("requestId", rid);
            body.put("items", items);
            body.put("count", items.size());
            body.put("observedPartitions", List.of(Json.obj("partitionId", 0, "epoch", 0)));
            HttpSupport.send(ex, 200, body);
        } catch (ApiError e) {
            respondError(ex, rid, e);
        } catch (EngineException e) {
            HttpSupport.send(ex, e.httpStatus(),
                    HttpSupport.error(e.code(), e.getMessage(), safeRid(rid), e.retryable(), true));
        } catch (RuntimeException e) {
            HttpSupport.send(ex, 500, HttpSupport.error("INTERNAL_ERROR", "Interna greška", safeRid(rid), false, true));
        }
    }

    // ---------------------------------------------------------------- pomoćno

    private static long deadline(HttpExchange ex) throws ApiError {
        int ms = Validation.timeoutMs(ex.getRequestHeaders().getFirst("X-Request-Timeout-Ms"));
        return System.nanoTime() + ms * 1_000_000L;
    }

    private void requireReady() throws ApiError {
        if (!nodeState.isReady()) {
            throw new ApiError(503, "NODE_NOT_READY", "Čvor nije spreman (" + nodeState.lifecycle() + ")", true);
        }
    }

    private static Map<String, Object> ok(String rid) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "OK");
        m.put("requestId", rid);
        m.put("partitionId", 0);
        m.put("epoch", 0);
        return m;
    }

    private static String safeRid(String rid) {
        try {
            return Validation.requestId(rid);
        } catch (ApiError e) {
            return null;
        }
    }

    private static void respondError(HttpExchange ex, String rid, ApiError e) throws IOException {
        HttpSupport.send(ex, e.httpStatus(),
                HttpSupport.error(e.code(), e.getMessage(), safeRid(rid), e.retryable(), true));
    }
}
