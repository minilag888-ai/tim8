package rs.raf.dlsm.failpoint;

/** Akcija RETURN_ERROR: odgovarajuća operacija vraća konfigurisanu internu grešku. */
public final class FailpointErrorException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String errorCode;

    public FailpointErrorException(String failpointName, String errorCode) {
        super("Ubrizgana greška " + errorCode + " na failpoint-u " + failpointName);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
