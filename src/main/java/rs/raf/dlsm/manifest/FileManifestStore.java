package rs.raf.dlsm.manifest;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import rs.raf.dlsm.io.FileOps;
import rs.raf.dlsm.json.Json;
import rs.raf.dlsm.sstable.SSTableMeta;

/**
 * MANIFEST.json: učitavanje, validacija i atomsko objavljivanje (Checkpoint 1 §18). Vlasnik: Ivona.
 *
 * <p>Objavljivanje: tmp/MANIFEST-&lt;gen&gt;.tmp → force → atomski rename preko lsm/MANIFEST.json →
 * force direktorijuma lsm/. Failpoint-i: manifest.after-temp-sync.before-rename i
 * manifest.after-rename.before-directory-sync (oba podržavaju IO_ERROR kroz FileOps).
 */
public final class FileManifestStore implements ManifestStore {

    private final FileOps ops;
    private final Path lsmDir;
    private final Path tmpDir;
    private Manifest current;

    public FileManifestStore(FileOps ops, Path lsmDir, Path tmpDir) {
        this.ops = ops;
        this.lsmDir = lsmDir;
        this.tmpDir = tmpDir;
    }

    public Path manifestFile() {
        return lsmDir.resolve("MANIFEST.json");
    }

    public boolean exists() {
        return Files.exists(manifestFile());
    }

    @Override
    public synchronized Manifest load() throws IOException {
        String text;
        try {
            text = Files.readString(manifestFile(), StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            throw new IOException("MANIFEST.json ne postoji");
        }
        Manifest m = parse(text);
        validate(m);
        current = m;
        return m;
    }

    /** Poslednji učitan ili objavljen manifest (null pre prvog load/publish). */
    public synchronized Manifest current() {
        return current;
    }

    /** Početni prazan manifest generacije 0, samo za dokazano novu bazu (§14.2 korak 3). */
    public synchronized Manifest createInitial() throws IOException {
        if (exists()) {
            throw new IOException("MANIFEST.json već postoji; početni manifest se ne pravi ponovo");
        }
        Manifest m = Manifest.initial();
        write(m);
        current = m;
        return m;
    }

    @Override
    public synchronized void publish(Manifest next) throws IOException {
        validate(next);
        if (current != null && next.generation() != current.generation() + 1) {
            throw new IllegalStateException("Generacija mora rasti za 1: " + current.generation() + " → " + next.generation());
        }
        try {
            write(next);
        } catch (DirectorySyncUncertainException e) {
            current = next; // ime je zamenjeno; trajnost je neizvesna, mehanizam ide u FAILED
            throw e;
        }
        current = next;
    }

    private void write(Manifest m) throws IOException {
        Path tmp = tmpDir.resolve("MANIFEST-" + m.generation() + ".tmp");
        Files.deleteIfExists(tmp);
        byte[] bytes = Json.write(toJson(m)).getBytes(StandardCharsets.UTF_8);
        try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ops.writeFully(ch, ByteBuffer.wrap(bytes), null);
            ops.force(ch, null);
        }
        ops.atomicMove(tmp, manifestFile(), true, "manifest.after-temp-sync.before-rename");
        try {
            ops.forceDirectory(lsmDir, "manifest.after-rename.before-directory-sync");
        } catch (IOException e) {
            throw new DirectorySyncUncertainException("Force direktorijuma lsm/ posle zamene manifesta nije uspeo", e);
        }
    }

    // ---------------------------------------------------------------- JSON

    public static Map<String, Object> toJson(Manifest m) {
        List<Object> tables = new ArrayList<>();
        for (SSTableMeta t : m.sstables()) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("tableId", t.tableId());
            e.put("file", t.file());
            e.put("minKey", t.minKey());
            e.put("maxKey", t.maxKey());
            e.put("minSequence", t.minSequence());
            e.put("maxSequence", t.maxSequence());
            e.put("entryCount", t.entryCount());
            e.put("sizeBytes", t.sizeBytes());
            tables.add(e);
        }
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("formatVersion", m.formatVersion());
        o.put("generation", m.generation());
        o.put("nextTableId", m.nextTableId());
        o.put("flushedThroughSequence", m.flushedThroughSequence());
        o.put("sstables", tables);
        return o;
    }

    public static Manifest parse(String text) throws IOException {
        Map<String, Object> o;
        try {
            o = Json.parseObject(text);
        } catch (Json.JsonException e) {
            throw new IOException("MANIFEST.json nije ispravan JSON: " + e.getMessage());
        }
        long version = num(o, "formatVersion");
        if (version != Manifest.FORMAT_VERSION) {
            throw new IOException("Nepodržana verzija manifesta: " + version);
        }
        if (!(o.get("sstables") instanceof List<?> list)) {
            throw new IOException("Manifest nema niz sstables");
        }
        List<SSTableMeta> tables = new ArrayList<>();
        for (Object x : list) {
            if (!(x instanceof Map<?, ?> raw)) {
                throw new IOException("Stavka sstables nije objekat");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> t = (Map<String, Object>) raw;
            tables.add(new SSTableMeta(num(t, "tableId"), str(t, "file"), str(t, "minKey"), str(t, "maxKey"),
                    num(t, "minSequence"), num(t, "maxSequence"), num(t, "entryCount"), num(t, "sizeBytes")));
        }
        return new Manifest((int) version, num(o, "generation"), num(o, "nextTableId"),
                num(o, "flushedThroughSequence"), tables);
    }

    /** Pravila iz §18.2. Provera samih SSTable fajlova je posebna (validateFully pri oporavku). */
    public static void validate(Manifest m) throws IOException {
        if (m.formatVersion() != Manifest.FORMAT_VERSION) {
            throw new IOException("Nepodržana verzija manifesta: " + m.formatVersion());
        }
        if (m.generation() < 0 || m.nextTableId() < 1 || m.flushedThroughSequence() < 0) {
            throw new IOException("Generacija, nextTableId ili flushedThroughSequence su van granica");
        }
        Set<Long> ids = new HashSet<>();
        Set<String> files = new HashSet<>();
        for (SSTableMeta t : m.sstables()) {
            if (t.tableId() < 1 || !ids.add(t.tableId())) {
                throw new IOException("Neispravan ili dupli ID tabele: " + t.tableId());
            }
            if (!files.add(t.file()) || !t.file().equals(SSTableMeta.fileName(t.tableId()))) {
                throw new IOException("Neispravno ili duplo ime fajla tabele: " + t.file());
            }
            if (t.tableId() >= m.nextTableId()) {
                throw new IOException("nextTableId (" + m.nextTableId() + ") nije veći od ID-ja tabele " + t.tableId());
            }
            if (t.minSequence() < 1 || t.maxSequence() < t.minSequence() || t.entryCount() < 1 || t.sizeBytes() < 1
                    || t.minKey().compareTo(t.maxKey()) > 0) {
                throw new IOException("Neispravni metapodaci tabele " + t.tableId());
            }
            if (t.maxSequence() > m.flushedThroughSequence()) {
                throw new IOException("Tabela " + t.tableId() + " ima redni broj iznad flushedThroughSequence");
            }
        }
    }

    private static long num(Map<String, Object> o, String k) throws IOException {
        if (!(o.get(k) instanceof Long v)) {
            throw new IOException("Manifest: polje " + k + " nedostaje ili nije ceo broj");
        }
        return v;
    }

    private static String str(Map<String, Object> o, String k) throws IOException {
        if (!(o.get(k) instanceof String v)) {
            throw new IOException("Manifest: polje " + k + " nedostaje ili nije string");
        }
        return v;
    }
}
