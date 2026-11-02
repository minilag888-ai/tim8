package rs.raf.dlsm.manifest;

import java.io.IOException;

/**
 * Preimenovanje MANIFEST.json je urađeno, ali force direktorijuma lsm/ nije uspeo (Checkpoint 1 §26.1):
 * posle pada oporavak može videti stari ILI novi kompletan manifest. Mehanizam prelazi u FAILED,
 * ne briše nijedan ulaz i traži restart.
 */
public final class DirectorySyncUncertainException extends IOException {

    private static final long serialVersionUID = 1L;

    public DirectorySyncUncertainException(String message, Throwable cause) {
        super(message, cause);
    }
}
