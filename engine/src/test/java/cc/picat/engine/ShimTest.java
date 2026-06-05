package cc.picat.engine;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ShimTest {
    static final Gson GSON = new Gson();

    @SuppressWarnings("unchecked")
    static Map<String, Object> run(String userProg, String goal,
                                   List<String> vars, int max, String bindList) {
        String request = """
            module request.
            goal_str() = "%s".
            var_names() = [%s].
            max_sols() = %d.
            bind_list() = %s.
            """.formatted(goal.replace("\\", "\\\\").replace("\"", "\\\""),
                String.join(",", vars.stream().map(s -> "\"" + s + "\"").toList()),
                max, bindList);
        var r = PicatRunner.runRaw(Map.of(
                "/work/user.pi", userProg,
                "/work/request.pi", request),
            List.of("picat", "/work/shim.pi"), 30_000);
        assertNotNull(r.responseJson(), "no response.json; stderr: " + r.stderr()
            + " stdout: " + r.stdout());
        return GSON.fromJson(r.responseJson(), Map.class);
    }

    @SuppressWarnings("unchecked")
    @Test void singleSolution() {
        var resp = run("double(X) = X * 2.\n", "Y = double(21)",
            List.of("Y"), 1, "[]");
        assertEquals("ok", resp.get("status"));
        var sols = (List<Map<String, Object>>) resp.get("solutions");
        assertEquals(1, sols.size());
        assertEquals(42.0, sols.get(0).get("Y"));
    }

    @SuppressWarnings("unchecked")
    @Test void multiSolution() {
        // Validates the fail-driven backtracking loop collects up to max_sols.
        var resp = run("", "member(X, [a,b,c])", List.of("X"), 2, "[]");
        assertEquals("ok", resp.get("status"));
        var sols = (List<Map<String, Object>>) resp.get("solutions");
        assertEquals(List.of("a", "b"),
            sols.stream().map(s -> s.get("X")).toList());
    }
}
