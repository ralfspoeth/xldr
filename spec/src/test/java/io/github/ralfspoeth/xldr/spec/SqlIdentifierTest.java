package io.github.ralfspoeth.xldr.spec;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Two identifiers are equal when a database would resolve them to one column,
 * which is what makes this a type rather than the static helper it replaced: a
 * map or a set keyed by it cannot hold the same column twice.
 */
class SqlIdentifierTest {

    @Test
    void anUnquotedNameIsCaseInsensitive() {
        assertAll(
                () -> assertEquals(new SqlIdentifier("ccy"), new SqlIdentifier("CCY")),
                () -> assertEquals(new SqlIdentifier("ccy").hashCode(), new SqlIdentifier("CcY").hashCode()),
                () -> assertEquals("CCY", new SqlIdentifier("ccy").folded()));
    }

    /**
     * A quoted name is case-sensitive by definition, so these are two columns
     * and a spec is entitled to mean either.
     */
    @Test
    void aQuotedNameIsExact() {
        assertAll(
                () -> assertNotEquals(new SqlIdentifier("\"ccy\""), new SqlIdentifier("ccy")),
                () -> assertNotEquals(new SqlIdentifier("\"ccy\""), new SqlIdentifier("\"CCY\"")),
                () -> assertEquals("\"ccy\"", new SqlIdentifier("\"ccy\"").folded()),
                () -> assertEquals("ccy", new SqlIdentifier("\"ccy\"").unquoted()));
    }

    /**
     * The consequence the type exists for: a collection keyed by these does the
     * comparing, so the rules that used to scan for collisions are now things
     * the collection makes impossible.
     */
    @Test
    void aCollectionKeyedByTheseHoldsOneColumnOnce() {
        var map = new LinkedHashMap<SqlIdentifier, String>();
        map.put(new SqlIdentifier("ccy"), "first");
        var previous = map.put(new SqlIdentifier("CCY"), "second");

        assertAll(
                () -> assertEquals("first", previous, "so the caller can report the collision"),
                () -> assertEquals(1, map.size()),
                () -> assertEquals("second", map.get(new SqlIdentifier("cCy"))),
                () -> assertEquals(2, Set.of(new SqlIdentifier("ccy"), new SqlIdentifier("\"ccy\"")).size(),
                        "and a quoted name is still its own column"));
    }

    /**
     * {@link Locale#ROOT} rather than the default: under a Turkish locale
     * {@code "id".toUpperCase()} yields {@code "İD"}, and a server would then
     * address a different column depending on where it was started.
     */
    @Test
    void foldsUnderTheRootLocale() {
        var previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"));
            assertEquals("ID", new SqlIdentifier("id").folded());
        } finally {
            Locale.setDefault(previous);
        }
    }

    /**
     * The spelling the spec used, so that a message naming a column reads as the
     * author wrote it rather than as {@code SqlIdentifier[name=ccy]}.
     */
    @Test
    void readsBackAsItWasWritten() {
        assertAll(
                () -> assertEquals("ccy", new SqlIdentifier("ccy").name()),
                () -> assertEquals("ccy", new SqlIdentifier("ccy").toString()),
                () -> assertEquals("a lookup of 'rate'", "a lookup of '" + new SqlIdentifier("rate") + "'"));
    }

    @Test
    void refusesANameThatIsNoName() {
        assertAll(
                () -> assertThrows(NullPointerException.class, () -> new SqlIdentifier(null)),
                () -> assertThrows(IllegalArgumentException.class, () -> new SqlIdentifier("")),
                () -> assertThrows(IllegalArgumentException.class, () -> new SqlIdentifier("  ")));
    }
}
