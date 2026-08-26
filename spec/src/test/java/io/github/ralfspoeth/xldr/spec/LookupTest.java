package io.github.ralfspoeth.xldr.spec;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A lookup matches on one column or on several, and the several are what this
 * is about: what the shape guarantees, the one thing it refuses - matching on
 * nothing - and the one it makes unrepresentable, which is matching on the same
 * column twice.
 */
class LookupTest {

    private static SequencedMap<SqlIdentifier, ValueSource> conditions(String... columns) {
        var map = new LinkedHashMap<SqlIdentifier, ValueSource>();
        for (var column : columns) {
            map.put(new SqlIdentifier(column), new ValueSource.Field(column));
        }
        return map;
    }

    /**
     * The order the conditions were written is the order they are kept, because
     * it is the order they will be emitted and bound in. This is the whole
     * reason the component is a {@link SequencedMap}: {@code Map.copyOf}
     * randomises iteration order per JVM run, which would make the same spec
     * produce different SQL on different days.
     */
    @Test
    void keepsTheOrderTheConditionsWereWrittenIn() {
        var lookup = new ValueSource.Lookup("rate", "factor", conditions("ccy", "asof", "src"));
        assertEquals(
                List.of(new SqlIdentifier("ccy"), new SqlIdentifier("asof"), new SqlIdentifier("src")),
                List.copyOf(lookup.conditions().keySet()));
    }

    /** and holds them, whoever mutates the map it was handed afterwards */
    @Test
    void copiesTheConditionsItWasGiven() {
        var given = conditions("ccy");
        var lookup = new ValueSource.Lookup("rate", "factor", given);
        given.put(new SqlIdentifier("asof"), new ValueSource.Constant("x"));

        assertAll(
                () -> assertEquals(List.of(new SqlIdentifier("ccy")),
                        List.copyOf(lookup.conditions().keySet())),
                () -> assertThrows(UnsupportedOperationException.class,
                        () -> lookup.conditions().put(
                                new SqlIdentifier("asof"), new ValueSource.Constant("x"))));
    }

    /**
     * A lookup matching on nothing selects the whole table, which is not a thing
     * anyone means: it would return an arbitrary row's value, or fail on more
     * than one, depending on the database.
     */
    @Test
    void refusesALookupThatMatchesOnNothing() {
        var thrown = assertThrows(IllegalArgumentException.class,
                () -> new ValueSource.Lookup("rate", "factor",
                        new LinkedHashMap<>()));
        assertTrue(thrown.getMessage().contains("rate"), thrown.getMessage());
    }

    /**
     * Two conditions on one column cannot be built, which is the point of
     * keying the map by {@link SqlIdentifier}: {@code ccy} and {@code CCY} are
     * one unquoted column, so they are one key and the map holds one entry.
     * <p>
     * This used to be a scan in the constructor that threw. Making it
     * unrepresentable is better than refusing it, and it moves the reporting to
     * the readers, where the {@code put} that returns a previous value is what
     * tells a spec author they wrote the column twice.
     */
    @Test
    void cannotHoldTwoConditionsOnOneColumn() {
        var folded = new LinkedHashMap<SqlIdentifier, ValueSource>();
        folded.put(new SqlIdentifier("ccy"), new ValueSource.Field("a"));
        var previous = folded.put(new SqlIdentifier("CCY"), new ValueSource.Field("b"));

        var lookup = new ValueSource.Lookup("rate", "factor", folded);
        assertAll(
                () -> assertEquals(new ValueSource.Field("a"), previous,
                        "the second put replaced the first, so a caller can see the collision"),
                () -> assertEquals(1, lookup.conditions().size()),
                () -> assertEquals(List.of(new SqlIdentifier("ccy")),
                        List.copyOf(lookup.conditions().keySet()),
                        "and the map keeps the spelling that was put in first"),
                () -> assertEquals(new ValueSource.Field("b"),
                        lookup.conditions().get(new SqlIdentifier("cCy")),
                        "however the column is spelled when it is asked for"));
    }

    /**
     * And a quoted name is left alone, a quoted identifier being case-sensitive
     * by definition - the same exception {@code RecordMappingSpec} makes.
     */
    @Test
    void allowsAquotedColumnBesideItsUnquotedNamesake() {
        var quoted = new LinkedHashMap<SqlIdentifier, ValueSource>();
        quoted.put(new SqlIdentifier("ccy"), new ValueSource.Field("a"));
        quoted.put(new SqlIdentifier("\"ccy\""), new ValueSource.Field("b"));

        assertEquals(2, new ValueSource.Lookup("rate", "factor", quoted).conditions().size());
    }

    /**
     * The one-condition constructor is the shape nearly every lookup has, and
     * the two accessors that go with it read back what it was given.
     */
    @Test
    void theOneConditionFormIsStillOneCondition() {
        var lookup = new ValueSource.Lookup("country", "id", "iso", new ValueSource.Field("c"));
        assertAll(
                () -> assertEquals(new SqlIdentifier("iso"), lookup.keyColumn()),
                () -> assertEquals(new ValueSource.Field("c"), lookup.key()),
                () -> assertEquals(1, lookup.conditions().size()),
                () -> assertEquals(
                        new ValueSource.Lookup("country", "id", conditions("iso")).conditions().keySet(),
                        lookup.conditions().keySet()));
    }

    /**
     * Asking a composite lookup for "the" key column is a question with no
     * answer, and it says so rather than handing back the first one - which
     * would be right often enough to hide the times it was not.
     */
    @Test
    void refusesToNameOneKeyColumnWhenThereAreSeveral() {
        var lookup = new ValueSource.Lookup("rate", "factor", conditions("ccy", "asof"));
        var thrown = assertThrows(IllegalStateException.class, lookup::keyColumn);
        assertTrue(thrown.getMessage().contains("ccy"), thrown.getMessage());
    }
}
