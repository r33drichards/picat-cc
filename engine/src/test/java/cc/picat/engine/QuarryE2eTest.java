package cc.picat.engine;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class QuarryE2eTest {
    @Test void quarryPlanComesBackStructured() throws Exception {
        String prog = new String(getClass().getResourceAsStream("/quarry_small.pi")
            .readAllBytes(), StandardCharsets.UTF_8);
        var svc = new PicatService(1, 2, 120_000);
        try {
            var r = svc.query(prog, "do_plan(Plan, Cost)",
                List.of("Plan", "Cost"), Map.of("timeout", 120.0)).get();
            assertTrue(r.ok(), () -> "error: " + r.error());
            var sol = r.solutions().get(0);
            assertTrue(sol.get("Cost") instanceof Double);
            @SuppressWarnings("unchecked")
            var plan = (List<Object>) sol.get("Plan");
            assertFalse(plan.isEmpty());
            long mines = 0;
            for (Object a : plan) {
                if (a instanceof String s) {
                    assertTrue(s.equals("dump") || s.equals("refuel"), s);
                } else {
                    @SuppressWarnings("unchecked")
                    var struct = (Map<String, Object>) a;
                    assertEquals("mine", struct.get("f"));
                    @SuppressWarnings("unchecked")
                    var pos = (List<Double>) ((List<Object>) struct.get("args")).get(0);
                    assertEquals(3, pos.size());
                    mines++;
                }
            }
            assertEquals(2 * 2 * 2, mines, "2x2 chunk, 2 layers => 8 mined blocks");
        } finally { svc.shutdown(); }
    }
}
