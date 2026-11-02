package rs.raf.dlsm;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

/**
 * Ulazna tačka procesa.
 *
 * <p>OSNOVA: pokreće dva prazna HTTP servera (javni 8080 i admin 8082). {@code /health/live} radi,
 * a sve ostalo vraća 503 NODE_NOT_READY dok Ilija ne poveže StorageEngine, HTTP rute i oporavak.
 *
 * <p>Konfiguracija kroz promenljive okruženja:
 * PUBLIC_PORT (8080), ADMIN_PORT (8082), NODE_ID (standalone), DATA_DIR (/var/lib/dlsm), TEST_MODE.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws IOException {
        int publicPort = intEnv("PUBLIC_PORT", 8080);
        int adminPort = intEnv("ADMIN_PORT", 8082);
        String nodeId = env("NODE_ID", "standalone");

        HttpServer publicServer = HttpServer.create(new InetSocketAddress(publicPort), 0);
        publicServer.createContext("/", ex -> notReady(ex));
        publicServer.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

        HttpServer adminServer = HttpServer.create(new InetSocketAddress(adminPort), 0);
        adminServer.createContext("/health/live", ex ->
                send(ex, 200, "{\"status\":\"OK\",\"nodeId\":\"" + nodeId + "\",\"lifecycle\":\"STARTING\"}"));
        adminServer.createContext("/", ex -> notReady(ex));
        adminServer.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

        publicServer.start();
        adminServer.start();
        System.out.println("{\"level\":\"INFO\",\"event\":\"process.started\",\"nodeId\":\"" + nodeId
                + "\",\"publicPort\":" + publicPort + ",\"adminPort\":" + adminPort + "}");
    }

    private static void notReady(HttpExchange ex) throws IOException {
        String requestId = ex.getRequestHeaders().getFirst("X-Request-Id");
        send(ex, 503, "{\"status\":\"ERROR\",\"code\":\"NODE_NOT_READY\","
                + "\"message\":\"Node is not ready.\","
                + "\"requestId\":" + (requestId == null ? "null" : "\"" + requestId.replace("\"", "") + "\"") + ","
                + "\"retryable\":true}");
    }

    static void send(HttpExchange ex, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }

    private static int intEnv(String name, int fallback) {
        return Integer.parseInt(env(name, Integer.toString(fallback)));
    }
}
