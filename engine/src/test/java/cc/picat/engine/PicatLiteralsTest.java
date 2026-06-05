package cc.picat.engine;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PicatLiteralsTest {
    @Test void integers()      { assertEquals("42", PicatLiterals.toLiteral(42.0)); }
    @Test void floats()        { assertEquals("3.5", PicatLiterals.toLiteral(3.5)); }
    @Test void atoms()         { assertEquals("'kelp'", PicatLiterals.toLiteral("kelp")); }
    @Test void atomEscaping()  { assertEquals("'it''s'", PicatLiterals.toLiteral("it's")); }
    @Test void atomBackslashEscaping() {
        // Picat treats \ as an escape introducer inside quoted atoms; a trailing
        // backslash would otherwise escape the closing quote and run the lexer
        // past the atom (code injection). Backslash must be doubled.
        assertEquals("'foo\\\\'", PicatLiterals.toLiteral("foo\\"));
    }
    @Test void atomBackslashN() {
        // "a\nb" (backslash, letter n) must not collapse to a newline-bearing atom.
        assertEquals("'a\\\\nb'", PicatLiterals.toLiteral("a\\nb"));
    }
    @Test void atomMixedQuoteAndBackslash() {
        assertEquals("'a''b\\\\c'", PicatLiterals.toLiteral("a'b\\c"));
    }
    @Test void atomControlChars() {
        // Raw control chars escaped via Picat's \n \r \t escape sequences.
        assertEquals("'a\\nb'", PicatLiterals.toLiteral("a\nb"));
        assertEquals("'a\\rb'", PicatLiterals.toLiteral("a\rb"));
        assertEquals("'a\\tb'", PicatLiterals.toLiteral("a\tb"));
    }
    @Test void booleans()      { assertEquals("true", PicatLiterals.toLiteral(true)); }
    @Test void scientificNotation() {
        // Picat's tokenizer accepts uppercase-E float syntax (token.c:1247
        // `(d | 32) == 'e'`), so Double.toString's "1.0E21" lexes fine.
        assertEquals("1.0E21", PicatLiterals.toLiteral(1e21));
    }
    @Test void rejectsNonFiniteNumbers() {
        // NaN/Infinity render via Double.toString as "NaN"/"Infinity", which
        // Picat lexes as unbound variables (uppercase start) — silent meaning
        // change, so reject them.
        assertThrows(IllegalArgumentException.class,
            () -> PicatLiterals.toLiteral(Double.NaN));
        assertThrows(IllegalArgumentException.class,
            () -> PicatLiterals.toLiteral(Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class,
            () -> PicatLiterals.toLiteral(Double.NEGATIVE_INFINITY));
    }
    @Test void lists() {
        assertEquals("[1,2,3]", PicatLiterals.toLiteral(List.of(1.0, 2.0, 3.0)));
    }
    @Test void luaArrayTablesAreLists() {
        // CC:Tweaked delivers Lua tables as Map<Double,Object> with keys 1..n
        assertEquals("[1,2]", PicatLiterals.toLiteral(Map.of(1.0, 1.0, 2.0, 2.0)));
    }
    @Test void structs() {
        assertEquals("$swap(3,7)", PicatLiterals.toLiteral(
            Map.of("f", "swap", "args", Map.of(1.0, 3.0, 2.0, 7.0))));
    }
    @Test void nestedListInStruct() {
        assertEquals("$mine([0,63,0])", PicatLiterals.toLiteral(
            Map.of("f", "mine", "args", Map.of(1.0, Map.of(1.0, 0.0, 2.0, 63.0, 3.0, 0.0)))));
    }
    @Test void wholeNumberDoublesAreInts() {
        assertEquals("63", PicatLiterals.toLiteral(63.0)); // Lua numbers arrive as Double
    }
    @Test void negativeNumbers() {
        assertEquals("-7", PicatLiterals.toLiteral(-7.0));
    }
    @Test void emptyList() {
        assertEquals("[]", PicatLiterals.toLiteral(List.of()));
    }
    @Test void structWithNoArgsIsAtom() {
        // Zero-arity struct $foo() is not valid Picat; render as the bare atom 'foo'.
        assertEquals("'foo'", PicatLiterals.toLiteral(
            Map.of("f", "foo", "args", Map.of())));
    }
    @Test void wholeDoublesBeyond2Pow53AreFloats() {
        // 2^53 + 1 is not exactly representable as a double, and the (long) cast
        // would lose precision silently — render as a float instead.
        double big = 9.007199254740993E18; // well beyond 2^53
        assertEquals(Double.toString(big), PicatLiterals.toLiteral(big));
        // The exact 2^53 boundary is still representable and renders as an int.
        assertEquals("9007199254740992", PicatLiterals.toLiteral(9007199254740992.0)); // 2^53
    }
    @Test void rejectsNonArrayMapsWithoutF() {
        assertThrows(IllegalArgumentException.class,
            () -> PicatLiterals.toLiteral(Map.of("x", 1.0)));
    }
    @Test void rejectsSparseArrays() {
        assertThrows(IllegalArgumentException.class,
            () -> PicatLiterals.toLiteral(Map.of(1.0, 1.0, 3.0, 3.0)));
    }
    @Test void rejectsBadFunctorName() {
        assertThrows(IllegalArgumentException.class,
            () -> PicatLiterals.toLiteral(Map.of("f", "Bad-Name", "args", Map.of())));
    }
    @Test void rejectsNull() {
        assertThrows(IllegalArgumentException.class,
            () -> PicatLiterals.toLiteral(null));
    }
    @Test void rejectsDeepNesting() {
        Object v = 1.0;
        for (int i = 0; i < 70; i++) v = List.of(v);
        Object deep = v;
        assertThrows(IllegalArgumentException.class,
            () -> PicatLiterals.toLiteral(deep));
    }
}
