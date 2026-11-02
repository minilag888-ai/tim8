package rs.raf.dlsm;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import rs.raf.dlsm.api.AdminApi;
import rs.raf.dlsm.api.NodeRuntime;
import rs.raf.dlsm.api.PublicApi;
import rs.raf.dlsm.api.StatusService;
import rs.raf.dlsm.engine.Lifecycle;
import rs.raf.dlsm.engine.NodeState;
import rs.raf.dlsm.engine.StorageEngine;
import rs.raf.dlsm.failpoint.FailpointHttp;
import rs.raf.dlsm.failpoint.Failpoints;
import rs.raf.dlsm.io.FileOps;
import rs.raf.dlsm.log.Log;
import rs.raf.dlsm.maintenance.MaintenanceService;
import rs.raf.dlsm.recovery.Recovery;
import rs.raf.dlsm.recovery.RecoveryException;

/**
 * Ulazna tačka procesa. Vlasnik: Ilija (povezivanje svih delova).
 *
 * <p>Redosled pokretanja:
 * <ol>
 *   <li>Failpoint-i iz DLSM_TEST_BOOTSTRAP_FAILPOINTS_B64 (pre bilo kakve izmene fajlova).</li>
 *   <li>Admin server (8082): /health/live radi i tokom oporavka.</li>
 *   <li>Oporavak (Katarina): LOCK, node.json, manifest, validacija SSTable, WAL replay, nov aktivni WAL.</li>
 *   <li>Održavanje (Ivona): flush oporavljene generacije odmah posle spremnosti, automatski compaction.</li>
 *   <li>Javni server (8080) i prelaz u READY.</li>
 * </ol>
 * Ako oporavak ne uspe, proces OSTAJE živ u stanju FAILED: /health/ready vraća 503 sa razlogom,
 * a javni API ne opslužuje KV saobraćaj (I7, I9).
 *
 * <p>Konfiguracija: PUBLIC_PORT (8080), ADMIN_PORT (8082), NODE_ID (standalone), DATA_DIR (/var/lib/dlsm),
 * MEMTABLE_MAX_BYTES (1 MiB), TEST_MODE, DLSM_TEST_BOOTSTRAP_FAILPOINTS_B64.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws IOException {
        // Bez ovoga JDK HTTP server šalje zaglavlja i telo u odvojenim paketima, pa Nagle + odloženi ACK
        // dodaju ~40 ms na SVAKI odgovor.
        System.setProperty("sun.net.httpserver.nodelay", "true");
        int publicPort = intEnv("PUBLIC_PORT", 8080);
        int adminPort = intEnv("ADMIN_PORT", 8082);
        Path dataDir = Path.of(env("DATA_DIR", "/var/lib/dlsm"));
        NodeState nodeState = new NodeState(env("NODE_ID", "standalone"));
        Log.setNodeId(nodeState.nodeId());

        try {
            int n = Failpoints.bootstrapFromEnv(System.getenv("DLSM_TEST_BOOTSTRAP_FAILPOINTS_B64"));
            if (n > 0) {
                Log.info("failpoint.bootstrap", "rules", n);
            }
        } catch (IllegalArgumentException e) {
            Log.error("startup.invalid-test-config", "message", e.getMessage());
            System.exit(2);
            return;
        }

        AtomicReference<NodeRuntime> runtime = new AtomicReference<>();
        StatusService status = new StatusService(nodeState, runtime);
        HttpServer adminServer = HttpServer.create(new InetSocketAddress(adminPort), 0);
        adminServer.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        new AdminApi(nodeState, runtime, status).register(adminServer);
        FailpointHttp.register(adminServer);
        adminServer.start();

        StorageEngine engine = null;
        MaintenanceService maintenance = null;
        try {
            Recovery.Result r = Recovery.run(StorageEngine.Config.fromEnv(), nodeState, FileOps.local(), dataDir);
            engine = r.engine();
            maintenance = new MaintenanceService(engine, r.dir(), r.manifests(), r.tableIds(), r.wals(), r.tables(),
                    FileOps.local());
            runtime.set(new NodeRuntime(engine, maintenance));
        } catch (RecoveryException e) {
            nodeState.fail(e.category(), e.getMessage());
            Log.error("recovery.failed", "code", e.category(), "message", e.getMessage());
        } catch (RuntimeException e) {
            nodeState.fail("INTERNAL_ERROR", String.valueOf(e.getMessage()));
            Log.error("recovery.failed", "code", "INTERNAL_ERROR", "message", e.toString());
        }

        HttpServer publicServer = HttpServer.create(new InetSocketAddress(publicPort), 0);
        publicServer.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        new PublicApi(engine, nodeState).register(publicServer);
        publicServer.start();

        if (maintenance != null) {
            nodeState.transition(Lifecycle.READY);
            maintenance.start(); // oporavljena generacija ide na flush odmah POSLE spremnosti (dokumentovano)
            Log.info("process.ready", "publicPort", publicPort, "adminPort", adminPort, "testMode", Failpoints.testMode());
        }

        MaintenanceService m = maintenance;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            nodeState.transition(Lifecycle.STOPPING);
            publicServer.stop(0);
            adminServer.stop(0);
            if (m != null) {
                m.close();
            }
        }));
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }

    private static int intEnv(String name, int fallback) {
        return Integer.parseInt(env(name, Integer.toString(fallback)));
    }
}
