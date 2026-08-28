package io.github.ralfspoeth.xldr.spec;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A transform runs after the last record, so its arguments are held to the rule
 * a {@link VarSpec} is held to at the other end of the load: no field, at any
 * depth. The two share one implementation, and this is the half of it reached
 * from here.
 */
class ProcedureCallTest {

    @Test
    void refusesAfieldAsAnArgument() {
        var thrown = assertThrows(IllegalArgumentException.class,
                () -> new ProcedureCall("close_batch", List.of(new ValueSource.Field("id"))));
        assertAll(
                () -> assertTrue(thrown.getMessage().contains("close_batch"), thrown.getMessage()),
                () -> assertTrue(thrown.getMessage().contains("id"),
                        "and names the field it was asked to read: " + thrown.getMessage()),
                () -> assertTrue(thrown.getMessage().contains("after the last record"),
                        "and says why, in a transform's own terms: " + thrown.getMessage()));
    }

    /** and one level down, as a lookup's key or a call's argument */
    @Test
    void refusesAfieldHoweverDeeplyItIsBuried() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new ProcedureCall("close", List.of(new ValueSource.Lookup(
                                "region", "id", "city", new ValueSource.Field("city"))))),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new ProcedureCall("close", List.of(new ValueSource.FunctionCall(
                                "next_id", DataType.INTEGRAL, List.of(new ValueSource.Field("seq")))))));
    }

    /**
     * Everything a transform may be passed, which is everything a var may be -
     * including a call, since a function may be called for an argument to a
     * procedure as readily as for a var's value.
     */
    @Test
    void acceptsEverySourceThatNeedsNoRecord() {
        assertDoesNotThrow(() -> new ProcedureCall("pkg_load.close_batch", List.of(
                new ValueSource.Constant("funds"),
                new ValueSource.Var("batch"),
                new ValueSource.Expr("${xldr.rowsLoaded}"),
                new ValueSource.Lookup("load_batch", "id", "feed", new ValueSource.Constant("funds")),
                new ValueSource.FunctionCall("today", DataType.TEMPORAL, List.of()))));
    }

    /** a procedure with nothing to say to it is the ordinary case */
    @Test
    void acceptsNoArgumentsAtAll() {
        assertEquals(List.of(), new ProcedureCall("reconcile", List.of()).arguments());
    }

    /**
     * The name rule, shared with {@link ValueSource.FunctionCall}: a procedure
     * name reaches the text of a statement, so it is one or more identifiers
     * separated by dots and nothing else.
     */
    @Test
    void refusesAnameThatIsNotAname() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new ProcedureCall("close(); drop table t", List.of())),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new ProcedureCall("", List.of())),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new ProcedureCall("pkg..close", List.of())),
                () -> assertDoesNotThrow(() -> new ProcedureCall("warehouse.app.close_batch", List.of())));
    }

    /**
     * The message says "procedure" where a function's says "function". Both come
     * from one method, and a message that named the wrong kind would send a
     * reader to the wrong line of the spec.
     */
    @Test
    void saysWhichKindOfCallItRefused() {
        var procedure = assertThrows(IllegalArgumentException.class,
                () -> new ProcedureCall("not a name", List.of()));
        var function = assertThrows(IllegalArgumentException.class,
                () -> new ValueSource.FunctionCall("not a name", DataType.TEXT, List.of()));
        assertAll(
                () -> assertTrue(procedure.getMessage().contains("procedure"), procedure.getMessage()),
                () -> assertTrue(function.getMessage().contains("function"), function.getMessage()));
    }
}
