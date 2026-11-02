package rs.raf.dlsm.failpoint;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import rs.raf.dlsm.engine.RequestScope;
import rs.raf.dlsm.json.Json;
import rs.raf.dlsm.log.Log;

/**
 * Deterministički failpoint-i (Checkpoint 1 §26, Project Info §21). Vlasnik: Emilija.
 *
 * <p>Pravilo (token koji bira pozivalac) kaže: na failpoint-u {@code name}, posle {@code afterHits}
 * pogodaka koji odgovaraju filterima, izvrši {@code action} najviše {@code maxTriggers} puta.
 * <ul>
 *   <li>PAUSE: blokira samo tu operaciju dok test ne pozove release; posle pauseTimeoutMs nastavlja sa
 *       {@link FailpointTimeoutException}.</li>
 *   <li>CRASH: {@code Runtime.halt(137)} — proces staje odmah, bez shutdown hook-ova.</li>
 *   <li>IO_ERROR: baca {@link InjectedIoException}; FileOps ga pretvara u pravi IOException.</li>
 *   <li>RETURN_ERROR: baca {@link FailpointErrorException} sa zadatim kodom.</li>
 * </ul>
 * Failpoint-i rade samo uz TEST_MODE=true. Kad su isključeni, {@link #hit} nema nikakav efekat.
 * Potpis {@link #hit(String, Map)} je deo osnove i ne menja se.
 */
public final class Failpoints {

    public enum Action { PAUSE, CRASH, IO_ERROR, RETURN_ERROR }

    public enum State { ARMED, TRIGGERED, RELEASED, EXHAUSTED, UNKNOWN }

    /** Jedno aktivno pravilo. Sva promenljiva stanja štiti monitor objekta. */
    static final class Rule {
        final String token;
        final String name;
        final Action action;
        final long afterHits;
        final long maxTriggers;
        final Map<String, String> filters;
        final String errorCode;
        final long pauseTimeoutMs;
        final Map<String, Object> parameters;
        long hits;
        long triggers;
        State state = State.ARMED;
        CountDownLatch pauseLatch;

        Rule(String token, String name, Action action, long afterHits, long maxTriggers, Map<String, String> filters,
             String errorCode, long pauseTimeoutMs, Map<String, Object> parameters) {
            this.token = token;
            this.name = name;
            this.action = action;
            this.afterHits = afterHits;
            this.maxTriggers = maxTriggers;
            this.filters = filters;
            this.errorCode = errorCode;
            this.pauseTimeoutMs = pauseTimeoutMs;
            this.parameters = parameters;
        }

        boolean matches(String hitName, Map<String, Object> ctx) {
            if (!name.equals(hitName)) {
                return false;
            }
            for (Map.Entry<String, String> f : filters.entrySet()) {
                Object actual = "requestId".equals(f.getKey()) && !ctx.containsKey("requestId")
                        ? RequestScope.currentRequestId() : ctx.get(f.getKey());
                if (actual == null || !f.getValue().equals(String.valueOf(actual))) {
                    return false;
                }
            }
            return true;
        }

        boolean exhausted() {
            return maxTriggers > 0 && triggers >= maxTriggers;
        }
    }

    private static final Map<String, Rule> RULES = new ConcurrentHashMap<>();
    private static volatile boolean enabled = "true".equalsIgnoreCase(System.getenv("TEST_MODE"));

    private Failpoints() {
    }

    /** Da li je testni režim uključen (TEST_MODE=true). */
    public static boolean testMode() {
        return enabled;
    }

    /** Samo za JUnit testove koji rade bez TEST_MODE promenljive. */
    public static void setEnabledForTests(boolean on) {
        enabled = on;
        if (!on) {
            disarmAll();
        }
    }

    // ================================================================ tačka u produkcionom kodu

    /** Kraći oblik bez konteksta. */
    public static void hit(String name) {
        hit(name, Map.of());
    }

    /** Poziva se na imenovanoj granici. Potpis je deo osnove i ne menja se. */
    public static void hit(String name, Map<String, Object> ctx) {
        if (!enabled || RULES.isEmpty()) {
            return;
        }
        for (Rule r : RULES.values()) {
            CountDownLatch latch = null;
            synchronized (r) {
                if (r.exhausted() || !r.matches(name, ctx)) {
                    continue;
                }
                r.hits++;
                if (r.hits <= r.afterHits) {
                    continue;
                }
                r.triggers++;
                r.state = State.TRIGGERED;
                if (r.action == Action.PAUSE) {
                    latch = new CountDownLatch(1);
                    r.pauseLatch = latch;
                }
            }
            Log.warn("failpoint.triggered", "failpoint", name, "action", r.action.name(), "token", r.token);
            switch (r.action) {
                case CRASH -> {
                    System.out.flush();
                    System.err.flush();
                    Runtime.getRuntime().halt(137);
                }
                case IO_ERROR -> {
                    markDone(r);
                    throw new InjectedIoException(name);
                }
                case RETURN_ERROR -> {
                    markDone(r);
                    throw new FailpointErrorException(name, r.errorCode);
                }
                case PAUSE -> {
                    boolean released;
                    try {
                        released = latch.await(r.pauseTimeoutMs, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        released = false;
                    }
                    synchronized (r) {
                        r.pauseLatch = null;
                        r.state = r.exhausted() ? State.EXHAUSTED : State.RELEASED;
                    }
                    if (!released) {
                        throw new FailpointTimeoutException(name);
                    }
                }
                default -> throw new IllegalStateException("Nepoznata akcija " + r.action);
            }
        }
    }

    private static void markDone(Rule r) {
        synchronized (r) {
            if (r.exhausted()) {
                r.state = State.EXHAUSTED;
            }
        }
    }

    /**
     * Vrednost parametra (npr. {@code prefixBytes}) ako bi SLEDEĆI pogodak na {@code name} aktivirao pravilo.
     * Ne troši pogodak. Koristi ga WAL za {@code wal.after-record-prefix}.
     */
    public static OptionalLong parameter(String name, String key, Map<String, Object> ctx) {
        if (!enabled || RULES.isEmpty()) {
            return OptionalLong.empty();
        }
        for (Rule r : RULES.values()) {
            synchronized (r) {
                if (!r.exhausted() && r.matches(name, ctx) && r.hits + 1 > r.afterHits
                        && r.parameters.get(key) instanceof Number n) {
                    return OptionalLong.of(n.longValue());
                }
            }
        }
        return OptionalLong.empty();
    }

    /** Da li bi SLEDEĆI pogodak na {@code name} aktivirao neko pravilo. Ne troši pogodak. */
    public static boolean wouldTrigger(String name, Map<String, Object> ctx) {
        if (!enabled || RULES.isEmpty()) {
            return false;
        }
        for (Rule r : RULES.values()) {
            synchronized (r) {
                if (!r.exhausted() && r.matches(name, ctx) && r.hits + 1 > r.afterHits) {
                    return true;
                }
            }
        }
        return false;
    }

    // ================================================================ upravljanje (API i bootstrap)

    /** Aktivira pravilo iz JSON tela (Project Info §21.1). Baca IllegalArgumentException za neispravno pravilo. */
    public static void arm(String token, Map<String, Object> body) {
        if (token == null || !token.matches("[A-Za-z0-9._:-]{1,64}")) {
            throw new IllegalArgumentException("Neispravan token");
        }
        String name = str(body, "name", null);
        if (name == null || !FailpointCatalog.exists(name)) {
            throw new IllegalArgumentException("Nepoznat failpoint: " + name);
        }
        Action action;
        try {
            action = Action.valueOf(str(body, "action", ""));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Nepoznata akcija: " + body.get("action"));
        }
        if (!FailpointCatalog.supports(name, action)) {
            throw new IllegalArgumentException("Failpoint " + name + " ne podržava " + action);
        }
        long afterHits = num(body, "afterHits", 0);
        long maxTriggers = num(body, "maxTriggers", 1);
        long pauseTimeoutMs = num(body, "pauseTimeoutMs", 30_000);
        if (afterHits < 0 || maxTriggers < 0 || pauseTimeoutMs < 1) {
            throw new IllegalArgumentException("afterHits/maxTriggers/pauseTimeoutMs nisu ispravni");
        }
        Map<String, String> filters = new LinkedHashMap<>();
        if (body.get("filters") instanceof Map<?, ?> f) {
            f.forEach((k, v) -> filters.put(String.valueOf(k), String.valueOf(v)));
        } else if (body.get("filters") != null) {
            throw new IllegalArgumentException("filters mora biti objekat");
        }
        Map<String, Object> params = new LinkedHashMap<>();
        if (body.get("parameters") instanceof Map<?, ?> p) {
            p.forEach((k, v) -> params.put(String.valueOf(k), v));
        } else if (body.get("parameters") != null) {
            throw new IllegalArgumentException("parameters mora biti objekat");
        }
        if ("wal.after-record-prefix".equals(name) && params.get("prefixBytes") != null
                && !(params.get("prefixBytes") instanceof Number pb && pb.longValue() >= 1)) {
            throw new IllegalArgumentException("prefixBytes mora biti pozitivan broj");
        }
        RULES.put(token, new Rule(token, name, action, afterHits, maxTriggers, Map.copyOf(filters),
                str(body, "errorCode", "INJECTED_IO_ERROR"), pauseTimeoutMs, Map.copyOf(params)));
    }

    public static State state(String token) {
        Rule r = RULES.get(token);
        if (r == null) {
            return State.UNKNOWN;
        }
        synchronized (r) {
            return r.state;
        }
    }

    /** Status pravila za GET /admin/v1/failpoints/{token}. */
    public static Optional<Map<String, Object>> describe(String token) {
        Rule r = RULES.get(token);
        if (r == null) {
            return Optional.empty();
        }
        synchronized (r) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("token", r.token);
            m.put("name", r.name);
            m.put("action", r.action.name());
            m.put("state", r.state.name());
            m.put("hits", r.hits);
            m.put("triggers", r.triggers);
            m.put("parameters", r.parameters);
            return Optional.of(m);
        }
    }

    /** Nastavlja jednu pauziranu operaciju. Vraća false ako ništa nije pauzirano. */
    public static boolean release(String token) {
        Rule r = RULES.get(token);
        if (r == null) {
            return false;
        }
        synchronized (r) {
            if (r.pauseLatch == null) {
                return false;
            }
            r.pauseLatch.countDown();
            return true;
        }
    }

    public static void disarm(String token) {
        Rule r = RULES.remove(token);
        if (r != null) {
            release(r);
        }
    }

    /** Uklanja sva pravila i oslobađa sve pauze (čišćenje posle testa). */
    public static void disarmAll() {
        List<Rule> all = new ArrayList<>(RULES.values());
        RULES.clear();
        all.forEach(Failpoints::release);
    }

    private static void release(Rule r) {
        synchronized (r) {
            if (r.pauseLatch != null) {
                r.pauseLatch.countDown();
            }
        }
    }

    /**
     * Pravila iz DLSM_TEST_BOOTSTRAP_FAILPOINTS_B64 (Checkpoint 1 §26.3), aktivirana pre oporavka.
     * Neispravna vrednost ili upotreba van testnog režima baca IllegalArgumentException pre izmene fajlova.
     */
    @SuppressWarnings("unchecked")
    public static int bootstrapFromEnv(String b64) {
        if (b64 == null || b64.isBlank()) {
            return 0;
        }
        if (!enabled) {
            throw new IllegalArgumentException("DLSM_TEST_BOOTSTRAP_FAILPOINTS_B64 je dozvoljen samo uz TEST_MODE=true");
        }
        String json;
        try {
            json = new String(Base64.getDecoder().decode(b64.trim()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("DLSM_TEST_BOOTSTRAP_FAILPOINTS_B64 nije ispravan Base64");
        }
        Object parsed;
        try {
            parsed = Json.parse(json);
        } catch (Json.JsonException e) {
            throw new IllegalArgumentException("DLSM_TEST_BOOTSTRAP_FAILPOINTS_B64 nije ispravan JSON: " + e.getMessage());
        }
        if (!(parsed instanceof List<?> list)) {
            throw new IllegalArgumentException("DLSM_TEST_BOOTSTRAP_FAILPOINTS_B64 mora biti JSON niz pravila");
        }
        int n = 0;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) {
                throw new IllegalArgumentException("Pravilo mora biti JSON objekat");
            }
            Map<String, Object> rule = (Map<String, Object>) m;
            arm(str(rule, "token", null), rule);
            n++;
        }
        return n;
    }

    private static String str(Map<String, Object> m, String k, String def) {
        Object v = m.get(k);
        if (v == null) {
            return def;
        }
        if (!(v instanceof String s)) {
            throw new IllegalArgumentException(k + " mora biti string");
        }
        return s;
    }

    private static long num(Map<String, Object> m, String k, long def) {
        Object v = m.get(k);
        if (v == null) {
            return def;
        }
        if (!(v instanceof Long l)) {
            throw new IllegalArgumentException(k + " mora biti ceo broj");
        }
        return l;
    }
}
