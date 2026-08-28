package io.github.ralfspoeth.xldr.spec;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The mirror of {@link VarSpecTest}: a column is bound once per record, so it
 * cannot call a function - a call is made once per load, and one per record
 * would be a round trip a row.
 * <p>
 * The two rules are worth stating separately because each is what makes the
 * other's home the only home. A call has to live somewhere, and a var is the
 * only place evaluated at the moment a call is made; a field has to live
 * somewhere, and a column is the only place evaluated with a record in hand.
 */
class FieldMappingSpecTest {

    @Test
    void refusesAcallAsTheSource() {
        var thrown = assertThrows(IllegalArgumentException.class,
                () -> new FieldMappingSpec("load_id", new ValueSource.FunctionCall(
                        "next_id", DataType.INTEGRAL, List.of())));
        assertAll(
                () -> assertTrue(thrown.getMessage().contains("load_id"), thrown.getMessage()),
                () -> assertTrue(thrown.getMessage().contains("next_id"),
                        "and names the function it was asked to call: " + thrown.getMessage()),
                () -> assertTrue(thrown.getMessage().contains("var"),
                        "and says where the call belongs instead: " + thrown.getMessage()));
    }

    /**
     * One level down, as a lookup's key. A lookup's key is evaluated wherever the
     * lookup is, so a call buried in one is a call per record like any other.
     */
    @Test
    void refusesAcallAsAlookupKey() {
        var thrown = assertThrows(IllegalArgumentException.class,
                () -> new FieldMappingSpec("country_id", new ValueSource.Lookup(
                        "country", "id", "iso", new ValueSource.FunctionCall(
                                "default_iso", DataType.TEXT, List.of()))));
        assertTrue(thrown.getMessage().contains("default_iso"), thrown.getMessage());
    }

    /** and inside a regex, which a call may sit in as readily as anything else */
    @Test
    void refusesAcallInsideAregex() {
        var thrown = assertThrows(IllegalArgumentException.class,
                () -> new FieldMappingSpec("iso", ValueSource.Regex.matching(
                        new ValueSource.FunctionCall("default_iso", DataType.TEXT, List.of()),
                        "([A-Z]{2})", 1)));
        assertTrue(thrown.getMessage().contains("default_iso"), thrown.getMessage());
    }

    /**
     * The other way round is refused too, and for a reason of its own: a
     * column's regex may not read a lookup.
     * <p>
     * A regex runs on this side of the database, on a value bound as a
     * parameter, and a column's lookup is a subquery of the insert - so there is
     * nothing to match against until the statement runs. A var may hold exactly
     * this, being evaluated one value at a time, which is why the rule is here
     * and not in {@code RowIndependence}.
     * <p>
     * It used to be the loader's, thrown while planning the insert. Same
     * refusal, three steps later: after the editor said the spec was valid,
     * after it was deployed, on the first file.
     */
    @Test
    void refusesAlookupUnderAregex() {
        var thrown = assertThrows(IllegalArgumentException.class,
                () -> new FieldMappingSpec("ccy", ValueSource.Regex.matching(
                        new ValueSource.Lookup("instrument", "code", "id", new ValueSource.Field("i")),
                        ".*_([A-Z]{3})_.*", 1)));
        assertAll(
                () -> assertTrue(thrown.getMessage().contains("ccy"), thrown.getMessage()),
                () -> assertTrue(thrown.getMessage().contains("instrument"),
                        "and names the lookup it was asked to match against: " + thrown.getMessage()),
                () -> assertTrue(thrown.getMessage().contains("var"),
                        "and says what to do instead: " + thrown.getMessage()));
    }

    /**
     * Everything a column may be, including the field a var may not read. A rule
     * that refused too much would be worse than one that refused nothing, since
     * these are what nearly every mapping in the tutorial says.
     * <p>
     * The last two are the shapes the two refusals above are the edges of: a
     * regex over a field is the ordinary use of one, and a lookup keyed by a
     * regex is a regex that runs before the subquery is bound rather than after.
     */
    @Test
    void acceptsEverySourceAcolumnMayHave() {
        assertAll(
                () -> assertDoesNotThrow(() -> new FieldMappingSpec("a", new ValueSource.Field("id"))),
                () -> assertDoesNotThrow(() -> new FieldMappingSpec("b", new ValueSource.Constant("X"))),
                () -> assertDoesNotThrow(() -> new FieldMappingSpec("c", new ValueSource.Var("batch"))),
                () -> assertDoesNotThrow(() -> new FieldMappingSpec("d", new ValueSource.Expr("${now()}"))),
                () -> assertDoesNotThrow(() -> new FieldMappingSpec("e", new ValueSource.Lookup(
                        "country", "id", "iso", new ValueSource.Field("c")))),
                () -> assertDoesNotThrow(() -> new FieldMappingSpec("f", ValueSource.Regex.matching(
                        new ValueSource.Field("raw"), "([A-Z]{3})", 1))),
                () -> assertDoesNotThrow(() -> new FieldMappingSpec("g", new ValueSource.Lookup(
                        "rate", "factor", "ccy", ValueSource.Regex.matching(
                                new ValueSource.Field("sku"), ".*_([A-Z]{3})_.*", 1)))));
    }
}
