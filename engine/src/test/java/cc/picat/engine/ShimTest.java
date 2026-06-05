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
    static Map<String, Object> firstSol(Map<String, Object> resp) {
        return ((List<Map<String, Object>>) resp.get("solutions")).get(0);
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

    @Test void atomsBecomeStrings() {
        var resp = run("", "X = kelp", List.of("X"), 1, "[]");
        assertEquals("kelp", firstSol(resp).get("X"));
    }

    @Test void listsBecomeArrays() {
        var resp = run("", "X = [1,2,3]", List.of("X"), 1, "[]");
        assertEquals(List.of(1.0, 2.0, 3.0), firstSol(resp).get("X"));
    }

    @Test void tuplesBecomeArrays() {
        var resp = run("", "X = {0,63,5}", List.of("X"), 1, "[]");
        assertEquals(List.of(0.0, 63.0, 5.0), firstSol(resp).get("X"));
    }

    @SuppressWarnings("unchecked")
    @Test void structsBecomeFArgs() {
        var resp = run("", "X = $mine({1,2,3})", List.of("X"), 1, "[]");
        Map<String, Object> x = (Map<String, Object>) firstSol(resp).get("X");
        assertEquals("mine", x.get("f"));
        assertEquals(List.of(List.of(1.0, 2.0, 3.0)), x.get("args"));
    }

    @Test void floatsStayFloats() {
        var resp = run("", "X = 2.5", List.of("X"), 1, "[]");
        assertEquals(2.5, firstSol(resp).get("X"));
    }

    @Test void stringsAreStrings() {
        var resp = run("", "X = \"abc\"", List.of("X"), 1, "[]");
        assertEquals("abc", firstSol(resp).get("X"));
    }

    @Test void emptyListIsArray() {
        var resp = run("", "X = []", List.of("X"), 1, "[]");
        assertEquals(List.of(), firstSol(resp).get("X"));
    }

    @SuppressWarnings("unchecked")
    @Test void unboundVarMarker() {
        var resp = run("", "X = [1, Y]", List.of("X"), 1, "[]");
        var x = (List<Object>) firstSol(resp).get("X");
        assertEquals(1.0, x.get(0));
        assertEquals("_", ((Map<String, Object>) x.get(1)).get("var"));
    }

    @Test void atomWithQuotesRoundTrips() {
        // outbound JSON escaping of a Picat atom containing a double-quote.
        // Build the atom 'a"b' via to_atom on a 3-char string containing a quote.
        var resp = run("", "X = to_atom(\"a\\\"b\")", List.of("X"), 1, "[]");
        // The REAL assertion: response JSON parsed (Gson would throw otherwise)
        // and the value contains the quote char.
        Object x = firstSol(resp).get("X");
        assertNotNull(x);
        assertEquals("a\"b", x);
    }

    @SuppressWarnings("unchecked")
    @Test void multipleSolutionsViaBacktracking() {
        var resp = run("", "member(X, [a,b,c])", List.of("X"), 2, "[]");
        var sols = (List<Map<String, Object>>) resp.get("solutions");
        assertEquals(List.of("a", "b"),
            sols.stream().map(s -> s.get("X")).toList());
    }

    @SuppressWarnings("unchecked")
    @Test void maxLargerThanSolutionCountCollectsAll() {
        var resp = run("", "member(X, [a,b])", List.of("X"), 10, "[]");
        var sols = (List<Map<String, Object>>) resp.get("solutions");
        assertEquals(2, sols.size());
    }

    @Test void goalFailureIsStatusFailed() {
        var resp = run("", "1 = 2", List.of(), 1, "[]");
        assertEquals("failed", resp.get("status"));
    }

    @Test void thrownErrorIsStatusError() {
        var resp = run("boom() => throw $custom_err.\n", "boom()", List.of(), 1, "[]");
        assertEquals("error", resp.get("status"));
        assertTrue(((String) resp.get("message")).contains("custom_err"));
    }

    @Test void unknownVarNameIsError() {
        var resp = run("", "X = 1", List.of("Z"), 1, "[]");
        assertEquals("error", resp.get("status"));
        assertTrue(((String) resp.get("message")).contains("var_not_in_goal"));
    }

    @Test void bindInjectsValues() {
        var resp = run("sum_list(L) = sum(L).\n", "S = sum_list(Xs)",
            List.of("S"), 1, "[('Xs', [1,2,3])]");
        assertEquals(6.0, firstSol(resp).get("S"));
    }

    @Test void bindStructLiteral() {
        // $ in the bind value makes A a struct; the head pattern matches the
        // struct directly (no $ — $ is illegal in a clause head).
        var resp = run("get_pos(mine(P)) = P.\n", "P = get_pos(A)",
            List.of("P"), 1, "[('A', $mine([7,8,9]))]");
        assertEquals(List.of(7.0, 8.0, 9.0), firstSol(resp).get("P"));
    }

    @Test void twoVarsCaptured() {
        var resp = run("", "X = 1, Y = two", List.of("X", "Y"), 1, "[]");
        var s = firstSol(resp);
        assertEquals(1.0, s.get("X"));
        assertEquals("two", s.get("Y"));
    }

    @SuppressWarnings("unchecked")
    @Test void nestedStructure() {
        var resp = run("", "X = [$mine({0,63,0}), dump, refuel]", List.of("X"), 1, "[]");
        var x = (List<Object>) firstSol(resp).get("X");
        assertEquals(3, x.size());
        assertEquals("mine", ((Map<String, Object>) x.get(0)).get("f"));
        assertEquals("dump", x.get(1));
        assertEquals("refuel", x.get(2));
    }

    @Test void compileErrorIsStatusError() {
        var resp = run("this is !!! not picat", "true", List.of(), 1, "[]");
        assertEquals("error", resp.get("status"));
    }

    @Test void negativeIntegers() {
        var resp = run("", "X = -7", List.of("X"), 1, "[]");
        assertEquals(-7.0, firstSol(resp).get("X"));
    }

    @Test void negativeFloats() {
        var resp = run("", "X = -2.5", List.of("X"), 1, "[]");
        assertEquals(-2.5, firstSol(resp).get("X"));
    }

    @Test void bigintLosesPrecisionAsDocumented() {
        // Picat emits the exact integer 2**60 as a bare JSON number; Gson parses
        // every bare number to a Double, so it round-trips as the (lossy) double
        // without throwing and the JSON stays parseable. Pins the documented
        // contract that numbers cross the boundary as doubles.
        var resp = run("", "X = 2**60", List.of("X"), 1, "[]");
        assertEquals(Math.pow(2, 60), (Double) firstSol(resp).get("X"), 0.0);
    }
}
