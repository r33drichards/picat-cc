package cc.picat.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Serializes Java/Lua values as Picat term literal source text (the inbound
 *  half of the FFI mapping — see docs/plans/2026-06-04-picat-cc-ffi-design.md).
 *
 *  <p>ComputerCraft delivers Lua values to Java as: numbers → {@link Double},
 *  strings → {@link String}, booleans → {@link Boolean}, tables → {@link Map}
 *  (array-like tables keyed by Double 1..n; structs encoded as
 *  {@code {f=name, args=<array table>}}). Java {@link List}s are also accepted
 *  directly as lists. The result is term source text suitable for injection
 *  into a generated Picat file.
 *
 *  <p>Mapping contract: whole finite number → int, otherwise → float;
 *  string → quoted atom ({@code '...'} with {@code ''} escaping);
 *  boolean → {@code true}/{@code false}; list → {@code [..]};
 *  {@code {f,args}} → struct {@code $name(args...)} (zero args → bare atom).
 *  Rejects null, sparse/non-array maps lacking f+args, functor names not
 *  matching {@code [a-z][a-zA-Z0-9_]*}, nesting deeper than 64, and any
 *  otherwise unmappable type. */
public final class PicatLiterals {
    private PicatLiterals() {}

    private static final int MAX_DEPTH = 64;
    /** Largest magnitude at which consecutive doubles are still exact integers. */
    private static final double MAX_EXACT_INT = 9007199254740992.0; // 2^53

    public static String toLiteral(Object v) {
        StringBuilder sb = new StringBuilder();
        write(sb, v, 0);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object v, int depth) {
        if (depth > MAX_DEPTH) throw new IllegalArgumentException("bind: nesting too deep");
        if (v == null) throw new IllegalArgumentException("bind: nil not mappable");
        if (v instanceof Boolean b) { sb.append(b ? "true" : "false"); return; }
        if (v instanceof Number n) { writeNumber(sb, n); return; }
        if (v instanceof String s) { writeAtom(sb, s); return; }
        if (v instanceof List<?> l) { writeList(sb, l, depth); return; }
        if (v instanceof Map<?, ?> m) { writeMap(sb, m, depth); return; }
        throw new IllegalArgumentException(
            "bind: unmappable type " + v.getClass().getSimpleName());
    }

    private static void writeNumber(StringBuilder sb, Number n) {
        double d = n.doubleValue();
        if (!Double.isFinite(d)) {
            // NaN/Infinity stringify as "NaN"/"Infinity", which Picat would lex
            // as unbound variables (uppercase start) — silent meaning change.
            throw new IllegalArgumentException("bind: non-finite number: " + d);
        }
        if (d == Math.rint(d) && Math.abs(d) <= MAX_EXACT_INT) {
            sb.append((long) d);
        } else {
            // Double.toString emits an uppercase 'E' exponent, which Picat's
            // tokenizer accepts (token.c:1247 `(d | 32) == 'e'`).
            sb.append(Double.toString(d));
        }
    }

    private static void writeAtom(StringBuilder sb, String s) {
        sb.append('\'');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                // Picat's tokenizer reads doubled quotes as a single quote and
                // treats \ as an escape introducer, so both must be escaped or
                // the lexer runs past the atom (code injection / corruption).
                case '\'' -> sb.append("''");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        sb.append('\'');
    }

    private static void writeList(StringBuilder sb, List<?> l, int depth) {
        sb.append('[');
        for (int i = 0; i < l.size(); i++) {
            if (i > 0) sb.append(',');
            write(sb, l.get(i), depth + 1);
        }
        sb.append(']');
    }

    private static void writeMap(StringBuilder sb, Map<?, ?> m, int depth) {
        if (m.containsKey("f") && m.containsKey("args")) {
            writeStruct(sb, m, depth);
        } else {
            writeList(sb, asArray(m), depth);
        }
    }

    private static void writeStruct(StringBuilder sb, Map<?, ?> m, int depth) {
        Object f = m.get("f");
        if (!(f instanceof String name) || !isValidFunctor(name)) {
            throw new IllegalArgumentException("bind: invalid functor name " + f);
        }
        List<?> args = asArray(m.get("args"));
        if (args.isEmpty()) {
            // $foo() is not valid Picat — a zero-arity struct is just the atom.
            writeAtom(sb, name);
            return;
        }
        sb.append('$').append(name).append('(');
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) sb.append(',');
            write(sb, args.get(i), depth + 1);
        }
        sb.append(')');
    }

    private static boolean isValidFunctor(String s) {
        if (s.isEmpty()) return false;
        char first = s.charAt(0);
        if (first < 'a' || first > 'z') return false;
        for (int i = 1; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z')
                || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9')
                || c == '_';
            if (!ok) return false;
        }
        return true;
    }

    /** Coerces an array-like value to a positional list. {@link List} passes
     *  through; a {@link Map} must have Number keys forming exactly 1..n. */
    private static List<?> asArray(Object v) {
        if (v instanceof List<?> l) return l;
        if (v instanceof Map<?, ?> m) {
            TreeMap<Long, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!(e.getKey() instanceof Number num)) {
                    throw new IllegalArgumentException(
                        "bind: non-array map (non-numeric key) " + e.getKey());
                }
                double dk = num.doubleValue();
                if (dk != Math.rint(dk) || !Double.isFinite(dk)) {
                    throw new IllegalArgumentException(
                        "bind: non-array map (non-integer key) " + e.getKey());
                }
                Object prev = sorted.put((long) dk, e.getValue());
                if (prev != null) {
                    throw new IllegalArgumentException(
                        "bind: non-array map (duplicate key) " + e.getKey());
                }
            }
            List<Object> out = new ArrayList<>(sorted.size());
            long expected = 1;
            for (Map.Entry<Long, Object> e : sorted.entrySet()) {
                if (e.getKey() != expected) {
                    throw new IllegalArgumentException(
                        "bind: sparse/non-contiguous array (expected key "
                            + expected + ", saw " + e.getKey() + ")");
                }
                out.add(e.getValue());
                expected++;
            }
            return out;
        }
        throw new IllegalArgumentException(
            "bind: expected array, got " + (v == null ? "nil" : v.getClass().getSimpleName()));
    }
}
