package rs.raf.dlsm.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import rs.raf.dlsm.engine.Lifecycle;
import rs.raf.dlsm.engine.NodeState;
import rs.raf.dlsm.engine.StorageEngine;
import rs.raf.dlsm.json.Json;
import rs.raf.dlsm.record.InternalRecord;
import rs.raf.dlsm.wal.WalWriter;

class PublicApiTest {

    private HttpServer server;
    private String base;
    private final HttpClient client = HttpClient.newHttpClient();
    private final NodeState state = new NodeState("standalone");

    private record NoWal(long segmentId) implements WalWriter {
        @Override
        public void append(InternalRecord r) {
        }

        @Override
        public long sizeBytes() {
            return 0;
        }

        @Override
        public void close() {
        }
    }

    @BeforeEach
    void start() throws Exception {
        StorageEngine engine = new StorageEngine(new StorageEngine.Config(1 << 20), state, new NoWal(1),
                () -> new NoWal(2), StorageEngine.InitialState.empty());
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        new PublicApi(engine, state).register(server);
        new AdminApi(state).register(server);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        state.transition(Lifecycle.READY);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private HttpResponse<String> call(String method, String path, String body, String rid) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (rid != null) {
            b.header("X-Request-Id", rid);
        }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void putGetDeleteAndEmptyValue() throws Exception {
        HttpResponse<String> put = call("PUT", "/v1/kv/alpha", "{\"value\":\"SGVsbG8=\"}", "put-001");
        assertEquals(200, put.statusCode());
        Map<String, Object> pb = Json.parseObject(put.body());
        assertEquals("put-001", pb.get("requestId"));
        assertEquals(0L, pb.get("partitionId"));
        assertEquals(0L, pb.get("epoch"));
        assertEquals("no-store", put.headers().firstValue("Cache-Control").orElse(""));

        Map<String, Object> got = Json.parseObject(call("GET", "/v1/kv/alpha", null, "get-001").body());
        assertEquals("SGVsbG8=", got.get("value"));

        assertEquals(200, call("PUT", "/v1/kv/alpha", "{\"value\":\"\"}", "put-002").statusCode());
        HttpResponse<String> empty = call("GET", "/v1/kv/alpha", null, "get-002");
        assertEquals(200, empty.statusCode());
        assertEquals("", Json.parseObject(empty.body()).get("value"));

        assertEquals(200, call("DELETE", "/v1/kv/alpha", null, "del-001").statusCode());
        assertEquals(200, call("DELETE", "/v1/kv/nothing", null, "del-002").statusCode());
        HttpResponse<String> nf = call("GET", "/v1/kv/alpha", null, "get-003");
        assertEquals(404, nf.statusCode());
        Map<String, Object> err = Json.parseObject(nf.body());
        assertEquals("NOT_FOUND", err.get("code"));
        assertEquals("ERROR", err.get("status"));
        assertEquals(0L, err.get("partitionId"));
    }

    @Test
    void validationErrorsAre400() throws Exception {
        assertEquals(400, call("PUT", "/v1/kv/bad%20key", "{\"value\":\"QQ==\"}", "r1").statusCode());
        assertEquals(400, call("PUT", "/v1/kv/k", "{\"value\":\"QQ\"}", "r2").statusCode(), "Base64 bez dopune");
        assertEquals(400, call("PUT", "/v1/kv/k", "{\"value\":\"!!!!\"}", "r3").statusCode());
        assertEquals(400, call("PUT", "/v1/kv/k", "{}", "r4").statusCode());
        assertEquals(400, call("PUT", "/v1/kv/k", "nije json", "r5").statusCode());
        assertEquals(400, call("GET", "/v1/kv/k", null, null).statusCode(), "X-Request-Id je obavezan");
        assertEquals(400, call("GET", "/v1/kv/" + "a".repeat(129), null, "r6").statusCode());
        assertEquals(400, call("GET", "/v1/scan?start=b&end=a&limit=5", null, "r7").statusCode());
        assertEquals(400, call("GET", "/v1/scan?start=a&end=b&limit=1001", null, "r8").statusCode());
        assertEquals(400, call("GET", "/v1/scan?start=a&end=b", null, "r9").statusCode());
        HttpResponse<String> big = call("PUT", "/v1/kv/k",
                "{\"value\":\"" + java.util.Base64.getEncoder().encodeToString(new byte[262_145]) + "\"}", "r10");
        assertEquals(400, big.statusCode());
        assertEquals("VALUE_TOO_LARGE", Json.parseObject(big.body()).get("code"));
    }

    @Test
    void scanReturnsSortedLiveItems() throws Exception {
        for (String k : List.of("c", "a", "b", "d")) {
            call("PUT", "/v1/kv/" + k, "{\"value\":\"QQ==\"}", "p-" + k);
        }
        call("DELETE", "/v1/kv/b", null, "d-b");
        Map<String, Object> body = Json.parseObject(call("GET", "/v1/scan?start=a&end=d&limit=10", null, "s1").body());
        assertEquals(2L, body.get("count"));
        assertEquals("[{key=a, value=QQ==}, {key=c, value=QQ==}]", body.get("items").toString());
        assertTrue(body.get("observedPartitions").toString().contains("partitionId=0"));
    }

    @Test
    void notReadyIs503() throws Exception {
        state.transition(Lifecycle.RECOVERING);
        HttpResponse<String> r = call("GET", "/v1/kv/a", null, "nr-1");
        assertEquals(503, r.statusCode());
        assertEquals("NODE_NOT_READY", Json.parseObject(r.body()).get("code"));
        assertEquals(503, call("GET", "/health/ready", null, null).statusCode());
        assertEquals(200, call("GET", "/health/live", null, null).statusCode());
    }
}
