package io.github.ralfspoeth.xldr.spec;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /**
     * Everything a column may be, including the field a var may not read. A rule
     * that refused too much would be worse than one that refused nothing, since
     * these five are what nearly every mapping in the tutorial says.
     */
    @Test
    void acceptsEverySourceAcolumnMayHave() {
        assertAll(
                () -> assertDoesNotThrow(() -> new FieldMappingSpec("a", new ValueSource.Field("id"))),
                () -> assertDoesNotThrow(() -> new FieldMappingSpec("b", new ValueSource.Constant("X"))),
                () -> assertDoesNotThrow(() -> new FieldMappingSpec("c", new ValueSource.Var("batch"))),
                () -> assertDoesNotThrow(() -> new FieldMappingSpec("d", new ValueSource.Expr("${now()}"))),
                () -> assertDoesNotThrow(() -> new FieldMappingSpec("e", new ValueSource.Lookup(
                        "country", "id", "iso", new ValueSource.Field("c")))));
    }
}
