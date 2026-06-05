package cc.picat.engine;

import org.junit.jupiter.api.*;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PicatServiceTest {
    PicatService svc;
    @BeforeEach void setUp() { svc = new PicatService(2, 4, 60_000); }
    @AfterEach void tearDown() { svc.shutdown(); }

    @Test void querySuccess() throws Exception {
        var r = svc.query("inc(X) = X + 1.", "Y = inc(41)",
            List.of("Y"), Map.of()).get();
        assertTrue(r.ok());
        assertEquals(42.0, r.solutions().get(0).get("Y"));
    }

    @Test void compileErrorTaxonomy() throws Exception {
        var r = svc.query("this is !!! not picat", "true", List.of(), Map.of()).get();
        assertFalse(r.ok());
        assertTrue(r.error().startsWith("compile:"), r.error());
    }

    @Test void goalFailedTaxonomy() throws Exception {
        var r = svc.query("", "1 = 2", List.of(), Map.of()).get();
        assertFalse(r.ok());
        assertEquals("goal failed", r.error());
    }

    @Test void thrownErrorTaxonomy() throws Exception {
        var r = svc.query("boom() => throw $custom_err.", "boom()",
            List.of(), Map.of()).get();
        assertFalse(r.ok());
        assertTrue(r.error().startsWith("error:"), r.error());
        assertTrue(r.error().contains("custom_err"));
    }

    @Test void badBindTaxonomy() throws Exception {
        var r = svc.query("", "X = 1", List.of("X"),
            Map.of("bind", Map.of("X", Double.NaN))).get();
        assertFalse(r.ok());
        assertTrue(r.error().startsWith("bind:"), r.error());
    }

    @Test void timeoutAbandonsAndReports() throws Exception {
        var r = svc.query("spin(N) => spin(N+1).", "spin(0)",
            List.of(), Map.of("timeout", 2.0)).get();
        assertFalse(r.ok());
        assertEquals("timeout", r.error());
        assertEquals(1, svc.abandonedCount());
    }

    @Test void saturationRejects() throws Exception {
        for (int i = 0; i < 4; i++) {
            svc.query("spin(N) => spin(N+1).", "spin(0)",
                List.of(), Map.of("timeout", 1.0)).get();
        }
        assertEquals(4, svc.abandonedCount());
        var r = svc.query("", "X = 1", List.of("X"), Map.of()).get();
        assertFalse(r.ok());
        assertTrue(r.error().startsWith("busy:"), r.error());
    }

    @Test void timeoutCappedAtMax() throws Exception {
        var tight = new PicatService(1, 2, 3_000); // max 3s
        try {
            long t0 = System.nanoTime();
            var r = tight.query("spin(N) => spin(N+1).", "spin(0)",
                List.of(), Map.of("timeout", 9999.0)).get();
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
            assertEquals("timeout", r.error());
            assertTrue(elapsedMs < 8_000, "timeout not capped: " + elapsedMs);
        } finally { tight.shutdown(); }
    }

    @Test void multipleSolutionsViaMaxOpt() throws Exception {
        var r = svc.query("", "member(X,[a,b,c])", List.of("X"),
            Map.of("max", 2.0)).get();
        assertTrue(r.ok());
        assertEquals(2, r.solutions().size());
    }

    @Test void evalCapturesStdout() throws Exception {
        var r = svc.eval("main => println(hi).", null).get();
        assertTrue(r.ok());
        assertEquals("hi\n", r.stdout());
    }

    @Test void evalWithExplicitGoal() throws Exception {
        var r = svc.eval("greet() => println(yo).", "greet()").get();
        assertTrue(r.ok());
        assertEquals("yo\n", r.stdout());
    }

    @Test void goalWithPeriodRejectedClearly() throws Exception {
        var r = svc.query("", "X = 1. evil", List.of("X"), Map.of()).get();
        assertFalse(r.ok());
        // raw shim bad_syntax must be mapped or at least prefixed with error:/compile:
        assertTrue(r.error().startsWith("error:") || r.error().startsWith("compile:"), r.error());
    }
}
