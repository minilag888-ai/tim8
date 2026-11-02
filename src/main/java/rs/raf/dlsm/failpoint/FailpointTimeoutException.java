package rs.raf.dlsm.failpoint;

/** PAUSE je dostigla pauseTimeoutMs bez release-a: operacija nastavlja ovom greškom (Project Info §21.1). */
public final class FailpointTimeoutException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public FailpointTimeoutException(String failpointName) {
        super("Pauza na failpoint-u " + failpointName + " je istekla");
    }
}
