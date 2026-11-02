package rs.raf.dlsm.api;

import com.sun.net.httpserver.HttpExchange;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import rs.raf.dlsm.json.Json;

/** Zajedničke HTTP pomoćne funkcije: slanje JSON-a, omotač greške, čitanje tela i parametara. */
public final class HttpSupport {

    /** Najveće telo zahteva: Base64 od 256 KiB (~350 KB) plus JSON omotač. */
    public static final int MAX_BODY_BYTES = 400_000;

    private HttpSupport() {
    }

    public static void send(HttpExchange ex, int status, Map<String, Object> body) throws IOException {
        byte[] bytes = Json.write(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        String rid = ex.getRequestHeaders().getFirst("X-Request-Id");
        if (rid != null) {
            ex.getResponseHeaders().set("X-Request-Id", rid);
        }
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    /**
     * Omotač greške: status, code, message, requestId, retryable i, kada su poznati, partitionId i epoch.
     * Bez stack trace-a i apsolutnih putanja.
     */
    public static Map<String, Object> error(String code, String message, String requestId, boolean retryable,
                                            boolean standaloneContext) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "ERROR");
        m.put("code", code);
        m.put("message", message);
        m.put("requestId", requestId);
        m.put("retryable", retryable);
        if (standaloneContext) {
            m.put("partitionId", 0);
            m.put("epoch", 0);
        }
        return m;
    }

    public static String readBody(HttpExchange ex) throws IOException, ApiError {
        try (InputStream in = ex.getRequestBody()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
                if (out.size() > MAX_BODY_BYTES) {
                    throw new ApiError(400, "VALUE_TOO_LARGE", "Telo zahteva je preveliko", false);
                }
            }
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    /** Parametri iz query stringa; dupli parametar je greška. */
    public static Map<String, String> query(HttpExchange ex) throws ApiError {
        Map<String, String> out = new LinkedHashMap<>();
        String q = ex.getRequestURI().getRawQuery();
        if (q == null || q.isEmpty()) {
            return out;
        }
        for (String part : q.split("&")) {
            if (part.isEmpty()) {
                continue;
            }
            int eq = part.indexOf('=');
            String k = URLDecoder.decode(eq < 0 ? part : part.substring(0, eq), StandardCharsets.UTF_8);
            String v = eq < 0 ? "" : URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8);
            if (out.put(k, v) != null) {
                throw ApiError.invalid("Parametar " + k + " je naveden više puta");
            }
        }
        return out;
    }
}
