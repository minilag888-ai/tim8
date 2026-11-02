package rs.raf.dlsm.failpoint;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import rs.raf.dlsm.api.ApiError;
import rs.raf.dlsm.api.HttpSupport;
import rs.raf.dlsm.json.Json;

/**
 * Failpoint API na admin portu 8082 (Project Info §21.1). Radi samo uz TEST_MODE=true.
 * <pre>
 * GET    /admin/v1/failpoints/catalog
 * PUT    /admin/v1/failpoints/{token}          telo: pravilo
 * GET    /admin/v1/failpoints/{token}          → ARMED | TRIGGERED | RELEASED | EXHAUSTED | UNKNOWN
 * POST   /admin/v1/failpoints/{token}/release
 * DELETE /admin/v1/failpoints/{token}
 * DELETE /admin/v1/failpoints                  (sva pravila, oslobađa pauze)
 * </pre>
 */
public final class FailpointHttp {

    private static final String PREFIX = "/admin/v1/failpoints";

    private FailpointHttp() {
    }

    public static void register(HttpServer server) {
        server.createContext(PREFIX, FailpointHttp::handle);
    }

    private static void handle(HttpExchange ex) throws IOException {
        try {
            if (!Failpoints.testMode()) {
                throw new ApiError(404, "NOT_FOUND", "Failpoint-i su dostupni samo uz TEST_MODE=true", false);
            }
            String path = ex.getRequestURI().getPath().substring(PREFIX.length());
            String method = ex.getRequestMethod();
            if (path.isEmpty() || path.equals("/")) {
                if (!"DELETE".equals(method)) {
                    throw ApiError.invalid("Na " + PREFIX + " je podržan samo DELETE");
                }
                Failpoints.disarmAll();
                HttpSupport.send(ex, 200, Json.obj("protocolVersion", 1, "status", "OK"));
                return;
            }
            String[] parts = path.substring(1).split("/");
            if (parts.length == 1 && parts[0].equals("catalog") && "GET".equals(method)) {
                List<Object> items = new ArrayList<>();
                FailpointCatalog.ALL.forEach((name, actions) -> {
                    List<String> a = new ArrayList<>();
                    actions.forEach(x -> a.add(x.name()));
                    items.add(Json.obj("name", name, "actions", a));
                });
                HttpSupport.send(ex, 200, Json.obj("protocolVersion", 1, "status", "OK", "failpoints", items));
                return;
            }
            String token = parts[0];
            if (parts.length == 2 && parts[1].equals("release") && "POST".equals(method)) {
                boolean released = Failpoints.release(token);
                HttpSupport.send(ex, 200, Json.obj("protocolVersion", 1, "status", "OK", "token", token,
                        "released", released, "state", Failpoints.state(token).name()));
                return;
            }
            if (parts.length != 1) {
                throw new ApiError(404, "NOT_FOUND", "Nepoznata failpoint putanja", false);
            }
            switch (method) {
                case "PUT" -> {
                    Map<String, Object> body = Json.parseObject(HttpSupport.readBody(ex));
                    try {
                        Failpoints.arm(token, body);
                    } catch (IllegalArgumentException e) {
                        throw ApiError.invalid(e.getMessage());
                    }
                    HttpSupport.send(ex, 200, Json.obj("protocolVersion", 1, "status", "OK", "token", token,
                            "state", Failpoints.state(token).name()));
                }
                case "GET" -> {
                    Map<String, Object> d = Failpoints.describe(token)
                            .orElse(Json.obj("token", token, "state", "UNKNOWN"));
                    d.put("protocolVersion", 1);
                    d.put("status", "OK");
                    HttpSupport.send(ex, 200, d);
                }
                case "DELETE" -> {
                    Failpoints.disarm(token);
                    HttpSupport.send(ex, 200, Json.obj("protocolVersion", 1, "status", "OK", "token", token));
                }
                default -> throw ApiError.invalid("Metod " + method + " nije podržan");
            }
        } catch (ApiError e) {
            HttpSupport.send(ex, e.httpStatus(), HttpSupport.error(e.code(), e.getMessage(), null, e.retryable(), false));
        } catch (Json.JsonException e) {
            HttpSupport.send(ex, 400, HttpSupport.error("INVALID_REQUEST", "Telo nije ispravan JSON", null, false, false));
        }
    }
}
