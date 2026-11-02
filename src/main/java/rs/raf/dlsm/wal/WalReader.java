package rs.raf.dlsm.wal;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Čitanje i klasifikacija jednog WAL segmenta. Vlasnik: Katarina (Checkpoint 1 §16.3–16.4).
 * Ne menja fajl; odsecanje nepotpunog kraja radi oporavak, ne čitač.
 */
public interface WalReader {

    WalScanResult scan(Path segment) throws IOException;
}
