package rs.raf.dlsm.manifest;

import java.io.IOException;

/**
 * Učitavanje i atomsko objavljivanje manifesta. Vlasnik: Ivona (Checkpoint 1 §18.1–18.2).
 *
 * <p>{@link #publish}: tmp/MANIFEST-&lt;gen&gt;.tmp → force → atomski rename preko lsm/MANIFEST.json →
 * force direktorijuma lsm/. Kad se vrati bez izuzetka, novi manifest je trajno merodavan.
 */
public interface ManifestStore {

    Manifest load() throws IOException;

    void publish(Manifest next) throws IOException;
}
