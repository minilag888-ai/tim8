package rs.raf.dlsm.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonTest {

    @Test
    void roundTrip() {
        Map<String, Object> m = Json.obj("a", 1, "b", "x\"y\n", "c", List.of(true, false), "d", null, "e", Json.obj());
        String text = Json.write(m);
        assertEquals("{\"a\":1,\"b\":\"x\\\"y\\n\",\"c\":[true,false],\"d\":null,\"e\":{}}", text);
        Map<String, Object> back = Json.parseObject(text);
        assertEquals(1L, back.get("a"));
        assertEquals("x\"y\n", back.get("b"));
        assertEquals(List.of(true, false), back.get("c"));
        assertNull(back.get("d"));
    }

    @Test
    void parsesNumbersAndEscapes() {
        assertEquals(-42L, Json.parse("-42"));
        assertEquals(1.5, Json.parse("1.5"));
        assertEquals("Aé", Json.parse("\"\\u0041\\u00e9\""));
    }

    @Test
    void rejectsInvalidJson() {
        assertThrows(Json.JsonException.class, () -> Json.parse("{\"a\":}"));
        assertThrows(Json.JsonException.class, () -> Json.parse("{\"a\":1} x"));
        assertThrows(Json.JsonException.class, () -> Json.parse("{\"a\":1,\"a\":2}"));
        assertThrows(Json.JsonException.class, () -> Json.parseObject("[1]"));
        assertThrows(Json.JsonException.class, () -> Json.parse(""));
    }
}
