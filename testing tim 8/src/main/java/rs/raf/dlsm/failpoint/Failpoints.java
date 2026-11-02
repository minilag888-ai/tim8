package rs.raf.dlsm.failpoint;

import java.util.Map;

/**
 * Ulazna tačka za sve failpoint-e (Checkpoint 1 §26, Project Info §21).
 *
 * <p>OSNOVA: metoda {@link #hit} trenutno ne radi ništa. Svaki vlasnik modula je odmah poziva na
 * svojim mestima, npr. {@code Failpoints.hit("wal.after-sync.before-memtable", Map.of("sequence", seq))}.
 * Emilija kasnije dodaje pravo telo (PAUSE, CRASH, IO_ERROR, RETURN_ERROR, filteri, API na 8082)
 * BEZ promene potpisa.
 *
 * <p>Dogovor za IO_ERROR: kada je failpoint aktiviran sa akcijom IO_ERROR, {@code hit} baca
 * {@link InjectedIoException}. {@code FileOps} ga hvata i baca pravi {@link java.io.IOException}, tako da
 * greška ide kroz istu granu kao stvarni otkaz diska.
 */
public final class Failpoints {

    private Failpoints() {
    }

    /** Poziva se na imenovanoj granici. U osnovi ne radi ništa. */
    public static void hit(String name, Map<String, Object> ctx) {
        // Emilija: ovde ide pravi registar pravila. Potpis se ne menja.
    }

    /** Kraći oblik bez konteksta. */
    public static void hit(String name) {
        hit(name, Map.of());
    }

    /** Da li je testni režim uključen (TEST_MODE=true). Failpoint-i rade samo tada. */
    public static boolean testMode() {
        return "true".equalsIgnoreCase(System.getenv("TEST_MODE"));
    }
}
