package io.github.ralfspoeth.xldr.spec;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A var is evaluated once, before the first record is read, so it can read no
 * field - and this is where that is settled.
 * <p>
 * The rule was in this class's documentation and in no code: a spec naming a
 * field in a var read cleanly, deployed, and threw from the loader on the first
 * file. Nothing about the input could have made it work, which is what makes it
 * the document's mistake rather than the file's.
 */
class VarSpecTest {

    @Test
    void refusesAfieldAsTheSource() {
        var thrown = assertThrows(IllegalArgumentException.class,
                () -> new VarSpec("batch", new ValueSource.Field("id")));
        assertAll(
                () -> assertTrue(thrown.getMessage().contains("batch"), thrown.getMessage()),
                () -> assertTrue(thrown.getMessage().contains("id"),
                        "and names the field it was asked to read: " + thrown.getMessage()));
    }

    /**
     * One level down, as a lookup's key. Evaluated in the same breath as the var
     * and with the same nothing in hand, so the same refusal.
     */
    @Test
    void refusesAfieldAsAlookupKey() {
        var thrown = assertThrows(IllegalArgumentException.class,
                () -> new VarSpec("region", new ValueSource.Lookup(
                        "region", "id", "city", new ValueSource.Field("city"))));
        assertTrue(thrown.getMessage().contains("city"), thrown.getMessage());
    }

    /** and as an argument to a call, which is the same rule one level down again */
    @Test
    void refusesAfieldAsAcallArgument() {
        var thrown = assertThrows(IllegalArgumentException.class,
                () -> new VarSpec("next", new ValueSource.FunctionCall(
                        "next_id", DataType.INTEGRAL,
                        List.of(new ValueSource.Constant(1), new ValueSource.Field("seq")))));
        assertTrue(thrown.getMessage().contains("seq"), thrown.getMessage());
    }

    /**
     * Everything else a var may be, including the two that carry other sources.
     * A rule that refused too much would be worse than one that refused nothing,
     * since the tutorial's own vars are an expression and a lookup.
     */
    @Test
    void acceptsEverySourceThatNeedsNoRecord() {
        assertAll(
                () -> assertDoesNotThrow(() -> new VarSpec("a", new ValueSource.Constant("PD"))),
                () -> assertDoesNotThrow(() -> new VarSpec("b", new ValueSource.Expr("${now()}"))),
                () -> assertDoesNotThrow(() -> new VarSpec("c", new ValueSource.Var("a"))),
                () -> assertDoesNotThrow(() -> new VarSpec("d", new ValueSource.Lookup(
                        "load_batch", "id", "feed", new ValueSource.Constant("funds")))),
                () -> assertDoesNotThrow(() -> new VarSpec("e", new ValueSource.FunctionCall(
                        "next_id", DataType.INTEGRAL, List.of(new ValueSource.Var("a"))))));
    }
}
