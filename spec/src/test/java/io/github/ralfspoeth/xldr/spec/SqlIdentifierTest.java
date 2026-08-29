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

    // ---- the shape, which is what keeps a spec out of the statement ------------

    /**
     * A name is written into the statement rather than bound to it, so it is
     * held to being a name.
     * <p>
     * {@code Loader} concatenates {@link SqlIdentifier#folded()} into the insert
     * and into every lookup subquery. Until 0.50 the only rule was non-blank, so
     * a table called {@code t where 1=1 --} went in as written - and both
     * published schemas would have validated the spec that said it. The project
     * claimed in {@code CallableName} that a function name was the only part of
     * a value source reaching the statement text, which was never true of a
     * table or a column.
     */
    @Test
    void refusesWhatWouldNotBeANameInAStatement() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new SqlIdentifier("t where 1=1 --")),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new SqlIdentifier("t; drop table u")),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new SqlIdentifier("count(*)")),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new SqlIdentifier("a b")),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new SqlIdentifier("1st")),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new SqlIdentifier("a'b")));
    }

    /**
     * The plain set is the union of what the targets accept unquoted, not the
     * intersection: {@code $} and {@code #} are legal in an Oracle identifier and
     * a letter is any letter because PostgreSQL takes them. Refusing those would
     * mean a spec could not name a column that exists.
     */
    @Test
    void acceptsWhatTheTargetsAcceptUnquoted() {
        assertAll(
                () -> assertDoesNotThrow(() -> new SqlIdentifier("order_id")),
                () -> assertDoesNotThrow(() -> new SqlIdentifier("_private")),
                () -> assertDoesNotThrow(() -> new SqlIdentifier("EMP$")),
                () -> assertDoesNotThrow(() -> new SqlIdentifier("x#y")),
                () -> assertDoesNotThrow(() -> new SqlIdentifier("col2")),
                () -> assertDoesNotThrow(() -> new SqlIdentifier("größe")));
    }

    /**
     * A quoted name is quoted at both ends, holds something, and doubles any
     * quote inside it - which is how SQL escapes one, and how {@code a"b} is
     * written.
     */
    @Test
    void aQuotedNameIsWellFormedOrRefused() {
        assertAll(
                () -> assertDoesNotThrow(() -> new SqlIdentifier("\"order id\"")),
                () -> assertDoesNotThrow(() -> new SqlIdentifier("\"a\"\"b\"")),
                // one quote short at either end
                () -> assertThrows(IllegalArgumentException.class, () -> new SqlIdentifier("\"abc")),
                () -> assertThrows(IllegalArgumentException.class, () -> new SqlIdentifier("abc\"")),
                // quoted and empty, which used to pass the blank check at two characters
                () -> assertThrows(IllegalArgumentException.class, () -> new SqlIdentifier("\"\"")),
                // an interior quote left single, which would close the identifier early
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new SqlIdentifier("\"a\"b\"")));
    }

    /**
     * And the doubling comes off again, so the name can be matched against what
     * {@code DatabaseMetaData} reports - which is the stored name, without the
     * quotes a statement needs and without the doubling.
     */
    @Test
    void unquotingUndoublesAnInteriorQuote() {
        assertAll(
                () -> assertEquals("a\"b", new SqlIdentifier("\"a\"\"b\"").unquoted()),
                () -> assertEquals("\"a\"\"b\"", new SqlIdentifier("\"a\"\"b\"").folded(),
                        "but the statement still gets it doubled, that being the SQL for it"),
                () -> assertEquals("order id", new SqlIdentifier("\"order id\"").unquoted()));
    }

    /**
     * A qualified name is refused rather than folded through. It used to work by
     * accident - {@code folded()} upper-cased across the dot and the emitted SQL
     * happened to be valid - which made {@code target.properties} and the table
     * name two ways to say where a table is.
     * <p>
     * Refusing is the reversible direction: allowing a qualified name later
     * breaks nothing, and refusing one later would break every spec that had
     * started relying on it.
     */
    @Test
    void refusesAqualifiedName() {
        var thrown = assertThrows(IllegalArgumentException.class,
                () -> new SqlIdentifier("reporting.orders"));
        assertTrue(thrown.getMessage().contains("target.properties"),
                "and says where a deployment states that instead: " + thrown.getMessage());
    }
}
