package rs.raf.dlsm.failpoint;

import java.io.IOException;
import java.io.UncheckedIOException;

/** Baca je {@link Failpoints#hit} za akciju IO_ERROR; FileOps je pretvara u pravi IOException. */
public final class InjectedIoException extends UncheckedIOException {

    private static final long serialVersionUID = 1L;

    public InjectedIoException(String failpointName) {
        super(new IOException("INJECTED_IO_ERROR na failpoint-u " + failpointName));
    }
}
