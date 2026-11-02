package rs.raf.dlsm.api;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import rs.raf.dlsm.engine.NodeState;
import rs.raf.dlsm.json.Json;
import rs.raf.dlsm.maintenance.MaintenanceStats;
import rs.raf.dlsm.manifest.Manifest;
import rs.raf.dlsm.memtable.MemTable;
import rs.raf.dlsm.sstable.SSTableMeta;
import rs.raf.dlsm.sstable.SSTableStats;
import rs.raf.dlsm.wal.WalRegistry;
import rs.raf.dlsm.wal.WalStats;

/**
 * Status skladišta (Checkpoint 1 §24) i zajednički status (Project Info §20.2). Vlasnik: Ilija.
 * Svi nizovi su uređeni po numeričkom ID-ju; sstableCount i niz sstables dolaze iz istog manifesta.
 */
public final class StatusService {

    private final NodeState nodeState;
    private final AtomicReference<NodeRuntime> runtime;
    private final AtomicLong resetEpoch = new AtomicLong();

    public StatusService(NodeState nodeState, AtomicReference<NodeRuntime> runtime) {
        this.nodeState = nodeState;
        this.runtime = runtime;
    }

    /** POST /admin/v1/storage/stats/reset: jedna granica resetovanja, vraća novu epohu. */
    public long resetStats() {
        synchronized (resetEpoch) {
            WalStats.GLOBAL.reset();
            SSTableStats.GLOBAL.reset();
            MaintenanceStats.GLOBAL.reset();
            return resetEpoch.incrementAndGet();
        }
    }

    public Map<String, Object> storageStatus() {
        NodeRuntime rt = runtime.get();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("protocolVersion", 1);
        m.put("nodeId", nodeState.nodeId());
        m.put("lifecycle", nodeState.lifecycle().name());
        NodeState.FatalError fe = nodeState.lastFatalError();
        if (rt == null) {
            m.put("engineMode", nodeState.lifecycle().name().equals("FAILED") ? "FAILED" : "RECOVERING");
            m.put("writeBackpressure", false);
            m.put("backpressuredWriters", 0);
            m.put("lastFatalError", fe == null ? null : Json.obj("code", fe.code(), "message", fe.message()));
            m.put("nextSequence", null);
            m.put("activeMemtable", null);
            m.put("immutableMemtable", null);
            m.put("walSegments", List.of());
            m.put("manifest", null);
            m.put("sstables", List.of());
            m.put("maintenance", Json.obj("operation", "NONE", "phase", "IDLE", "targetSequence", null,
                    "inputTableIds", List.of()));
            m.put("stats", stats());
            return m;
        }
        Map<String, Object> engine = rt.engine().statusFields();
        m.put("engineMode", engine.get("engineMode"));
        m.put("writeBackpressure", engine.get("writeBackpressure"));
        m.put("backpressuredWriters", engine.get("backpressuredWriters"));
        m.put("lastFatalError", fe == null ? null : Json.obj("code", fe.code(), "message", fe.message()));
        m.put("nextSequence", engine.get("nextSequence"));
        m.put("activeMemtable", engine.get("activeMemtable"));
        m.put("immutableMemtable", engine.get("immutableMemtable"));

        long activeWal = rt.engine().activeWalSegmentId();
        MemTable imm = rt.engine().immutable();
        Set<Long> immWal = new HashSet<>(imm == null ? List.of() : imm.walSegmentIds());
        List<Object> segs = new ArrayList<>();
        for (WalRegistry.SegmentInfo s : rt.maintenance().wals().snapshot()) {
            String role = s.segmentId() == activeWal ? "ACTIVE"
                    : immWal.contains(s.segmentId()) ? "IMMUTABLE" : "COVERED_PENDING_DELETE";
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("segmentId", s.segmentId());
            e.put("role", role);
            e.put("minSequence", s.minSequence());
            e.put("maxSequence", s.maxSequence());
            e.put("sizeBytes", s.sizeBytes());
            segs.add(e);
        }
        m.put("walSegments", segs);

        Manifest man = rt.maintenance().manifests().current();
        m.put("manifest", Json.obj("generation", man.generation(), "nextTableId", man.nextTableId(),
                "flushedThroughSequence", man.flushedThroughSequence(), "sstableCount", man.sstables().size()));
        List<Object> tables = new ArrayList<>();
        for (SSTableMeta t : man.sstables()) {
            tables.add(Json.obj("tableId", t.tableId(), "file", t.file(), "minKey", t.minKey(), "maxKey", t.maxKey(),
                    "minSequence", t.minSequence(), "maxSequence", t.maxSequence(), "entryCount", t.entryCount(),
                    "sizeBytes", t.sizeBytes()));
        }
        m.put("sstables", tables);
        m.put("maintenance", rt.maintenance().statusFields());
        m.put("stats", stats());
        m.put("lastVisibleSequence", engine.get("lastVisibleSequence"));
        return m;
    }

    private Map<String, Object> stats() {
        Map<String, Object> s = new LinkedHashMap<>();
        WalStats w = WalStats.GLOBAL;
        SSTableStats t = SSTableStats.GLOBAL;
        MaintenanceStats mt = MaintenanceStats.GLOBAL;
        s.put("resetEpoch", resetEpoch.get());
        s.put("walAppendAttempts", w.appendAttempts.get());
        s.put("walAppendsCompleted", w.appendsCompleted.get());
        s.put("walForceAttempts", w.forceAttempts.get());
        s.put("walForcesSucceeded", w.forcesSucceeded.get());
        s.put("walBytesWritten", w.bytesWritten.get());
        s.put("flushAttempts", mt.flushAttempts.get());
        s.put("flushPublications", mt.flushPublications.get());
        s.put("compactionAttempts", mt.compactionAttempts.get());
        s.put("compactionPublications", mt.compactionPublications.get());
        s.put("sstableDataBlocksRead", t.dataBlocksRead.get());
        s.put("sstableDataBytesRead", t.dataBytesRead.get());
        s.put("bloomNegativeSkips", t.bloomNegativeSkips.get());
        s.put("bloomPositiveProbes", t.bloomPositiveProbes.get());
        s.put("rangeScanBlocksRead", t.rangeScanBlocksRead.get());
        s.put("orphanFilesRemoved", mt.orphanFilesRemoved.get());
        s.put("temporaryFilesRemoved", mt.temporaryFilesRemoved.get());
        s.put("walRecordsReplayed", w.recordsReplayed.get());
        s.put("walTornTailsTruncated", w.tornTailsTruncated.get());
        return s;
    }

    /** GET /admin/v1/status (Project Info §20.2, Checkpoint 1 §24 standaloneStorage). */
    public Map<String, Object> commonStatus() {
        Map<String, Object> st = storageStatus();
        NodeRuntime rt = runtime.get();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("protocolVersion", 1);
        m.put("nodeId", nodeState.nodeId());
        m.put("nodeRole", "STORAGE");
        m.put("lifecycle", nodeState.lifecycle().name());
        m.put("metadataRevision", 0);
        m.put("metadataLeaderId", null);
        long visible = rt == null ? 0 : ((Number) st.get("lastVisibleSequence")).longValue();
        m.put("partitions", List.of(Json.obj("partitionId", 0, "role", "PRIMARY", "epoch", 0,
                "ready", nodeState.isReady(), "lastAcceptedSequence", visible, "lastDurableSequence", visible)));
        @SuppressWarnings("unchecked")
        Map<String, Object> man = (Map<String, Object>) st.get("manifest");
        m.put("standaloneStorage", Json.obj(
                "engineMode", st.get("engineMode"),
                "manifestGeneration", man == null ? null : man.get("generation"),
                "flushedThroughSequence", man == null ? null : man.get("flushedThroughSequence"),
                "activeWalSegmentId", rt == null ? null : rt.engine().activeWalSegmentId(),
                "sstableCount", man == null ? 0 : man.get("sstableCount"),
                "writeBackpressure", st.get("writeBackpressure")));
        return m;
    }
}
