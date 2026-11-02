package rs.raf.dlsm.testkit;

import java.util.Base64;
import java.util.List;
import java.util.Map;

/** Odgovor HTTP poziva u testu. status = 0 znači: nema odgovora (pad procesa, prekinuta veza, timeout). */
public record Resp(int status, Map<String, Object> body, String raw) {

    public boolean noResponse() {
        return status == 0;
    }

    public boolean ok() {
        return status == 200;
    }

    public String code() {
        return body == null ? null : (String) body.get("code");
    }

    public Object get(String k) {
        return body == null ? null : body.get(k);
    }

    public long num(String k) {
        return ((Number) body.get(k)).longValue();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> obj(String k) {
        return (Map<String, Object>) body.get(k);
    }

    @SuppressWarnings("unchecked")
    public List<Object> list(String k) {
        return (List<Object>) body.get(k);
    }

    /** Dekodirana vrednost iz GET odgovora. */
    public byte[] value() {
        return Base64.getDecoder().decode((String) body.get("value"));
    }

    @Override
    public String toString() {
        return status + " " + (raw == null ? "" : raw.length() > 300 ? raw.substring(0, 300) + "..." : raw);
    }
}
