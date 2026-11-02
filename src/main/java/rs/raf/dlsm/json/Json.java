package rs.raf.dlsm.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimalan JSON (RFC 8259) bez spoljnih biblioteka.
 *
 * <p>Parsiranje vraća: {@code Map<String,Object>} (redosled ključeva očuvan), {@code List<Object>},
 * {@code String}, {@code Long} (celi brojevi), {@code Double}, {@code Boolean} ili {@code null}.
 * Pisanje prihvata iste tipove, plus {@code Integer} i druge {@code Number} vrednosti.
 */
public final class Json {

    private Json() {
    }

    // ---------------------------------------------------------------- pisanje

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value);
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String s) {
            writeString(sb, s);
        } else if (v instanceof Boolean b) {
            sb.append(b);
        } else if (v instanceof Double d) {
            if (d.isNaN() || d.isInfinite()) {
                throw new IllegalArgumentException("JSON ne podržava " + d);
            }
            sb.append(d);
        } else if (v instanceof Number n) {
            sb.append(n.longValue());
        } else if (v instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString(sb, String.valueOf(e.getKey()));
                sb.append(':');
                writeValue(sb, e.getValue());
            }
            sb.append('}');
        } else if (v instanceof Iterable<?> it) {
            sb.append('[');
            boolean first = true;
            for (Object o : it) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeValue(sb, o);
            }
            sb.append(']');
        } else {
            throw new IllegalArgumentException("Nepodržan JSON tip: " + v.getClass().getName());
        }
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    /** Pomoćna funkcija za pravljenje uređene mape: obj("a", 1, "b", "x"). */
    public static Map<String, Object> obj(Object... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("obj traži parove ključ/vrednost");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            m.put((String) keyValues[i], keyValues[i + 1]);
        }
        return m;
    }

    // ---------------------------------------------------------------- parsiranje

    /** Parsira ceo tekst; baca {@link JsonException} za neispravan JSON. */
    public static Object parse(String text) {
        Parser p = new Parser(text);
        p.skipWs();
        Object v = p.value(0);
        p.skipWs();
        if (p.pos != text.length()) {
            throw new JsonException("Višak teksta posle JSON vrednosti na poziciji " + p.pos);
        }
        return v;
    }

    /** Parsira tekst koji mora biti JSON objekat. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object v = parse(text);
        if (!(v instanceof Map)) {
            throw new JsonException("Očekivan JSON objekat");
        }
        return (Map<String, Object>) v;
    }

    /** Greška parsiranja JSON-a. */
    public static final class JsonException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public JsonException(String message) {
            super(message);
        }
    }

    private static final class Parser {
        private static final int MAX_DEPTH = 64;
        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s;
        }

        void skipWs() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        Object value(int depth) {
            if (depth > MAX_DEPTH) {
                throw new JsonException("JSON je previše ugnežđen");
            }
            if (pos >= s.length()) {
                throw new JsonException("Neočekivan kraj JSON-a");
            }
            char c = s.charAt(pos);
            return switch (c) {
                case '{' -> object(depth);
                case '[' -> array(depth);
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        yield number();
                    }
                    throw new JsonException("Neočekivan znak '" + c + "' na poziciji " + pos);
                }
            };
        }

        Object literal(String word, Object v) {
            if (!s.startsWith(word, pos)) {
                throw new JsonException("Neispravan literal na poziciji " + pos);
            }
            pos += word.length();
            return v;
        }

        Map<String, Object> object(int depth) {
            Map<String, Object> m = new LinkedHashMap<>();
            pos++;
            skipWs();
            if (peek() == '}') {
                pos++;
                return m;
            }
            while (true) {
                skipWs();
                if (peek() != '"') {
                    throw new JsonException("Očekivan ključ na poziciji " + pos);
                }
                String k = string();
                skipWs();
                expect(':');
                skipWs();
                if (m.containsKey(k)) {
                    throw new JsonException("Dupli ključ: " + k);
                }
                m.put(k, value(depth + 1));
                skipWs();
                char c = next();
                if (c == '}') {
                    return m;
                }
                if (c != ',') {
                    throw new JsonException("Očekivan ',' ili '}' na poziciji " + (pos - 1));
                }
            }
        }

        List<Object> array(int depth) {
            List<Object> l = new ArrayList<>();
            pos++;
            skipWs();
            if (peek() == ']') {
                pos++;
                return l;
            }
            while (true) {
                skipWs();
                l.add(value(depth + 1));
                skipWs();
                char c = next();
                if (c == ']') {
                    return l;
                }
                if (c != ',') {
                    throw new JsonException("Očekivan ',' ili ']' na poziciji " + (pos - 1));
                }
            }
        }

        String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = next();
                if (c == '"') {
                    return sb.toString();
                }
                if (c < 0x20) {
                    throw new JsonException("Kontrolni znak u stringu na poziciji " + (pos - 1));
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                char e = next();
                switch (e) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (pos + 4 > s.length()) {
                            throw new JsonException("Presečen \\u escape");
                        }
                        try {
                            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                        } catch (NumberFormatException ex) {
                            throw new JsonException("Neispravan \\u escape");
                        }
                        pos += 4;
                    }
                    default -> throw new JsonException("Neispravan escape \\" + e);
                }
            }
        }

        Object number() {
            int start = pos;
            if (peek() == '-') {
                pos++;
            }
            boolean isDouble = false;
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c >= '0' && c <= '9') {
                    pos++;
                } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    isDouble = true;
                    pos++;
                } else {
                    break;
                }
            }
            String t = s.substring(start, pos);
            try {
                if (isDouble) {
                    return Double.parseDouble(t);
                }
                return Long.parseLong(t);
            } catch (NumberFormatException e) {
                throw new JsonException("Neispravan broj: " + t);
            }
        }

        char peek() {
            if (pos >= s.length()) {
                throw new JsonException("Neočekivan kraj JSON-a");
            }
            return s.charAt(pos);
        }

        char next() {
            char c = peek();
            pos++;
            return c;
        }

        void expect(char c) {
            if (next() != c) {
                throw new JsonException("Očekivan '" + c + "' na poziciji " + (pos - 1));
            }
        }
    }
}
