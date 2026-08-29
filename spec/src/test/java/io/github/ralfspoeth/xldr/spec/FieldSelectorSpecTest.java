package io.github.ralfspoeth.xldr.spec;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The two convenience constructors say exactly what the canonical one says.
 * <p>
 * That is the whole of their job - they exist so that a caller writing a field
 * selector need not name {@link Selector.Text} or {@link Selector.Nth} - and it
 * is a job that can be got wrong silently, which is what happened: the counted
 * one passed {@code null} for the type whatever it was given. Nothing called it,
 * so nothing failed, so it stayed wrong. These tests are cheap and they are the
 * only thing standing between the next caller and a DECIMAL column bound a
 * string.
 */
class FieldSelectorSpecTest {

    @Test
    void theTextOverloadSaysWhatTheCanonicalOneSays() {
        assertEquals(
                new FieldSelectorSpec("nav", new Selector.Text("//nav"), DataType.DECIMAL),
                new FieldSelectorSpec("nav", "//nav", DataType.DECIMAL));
    }

    /** the regression: this one used to drop the type */
    @Test
    void theCountedOverloadSaysWhatTheCanonicalOneSays() {
        assertEquals(
                new FieldSelectorSpec("price", new Selector.Nth(3), DataType.DECIMAL),
                new FieldSelectorSpec("price", 3, DataType.DECIMAL));
    }

    /**
     * Stated the other way round as well, because equality could in principle be
     * satisfied by both sides being wrong together.
     */
    @Test
    void theCountedOverloadKeepsTheTypeItWasGiven() {
        var spec = new FieldSelectorSpec("price", 3, DataType.DECIMAL);
        assertAll(
                () -> assertEquals(DataType.DECIMAL, spec.dataType()),
                () -> assertEquals(new Selector.Nth(3), spec.selector()),
                () -> assertEquals("price", spec.name()));
    }

    /** and a null type stays null, that being how a spec leaves it to the adapter */
    @Test
    void bothOverloadsAllowNoTypeAtAll() {
        assertAll(
                () -> assertNull(new FieldSelectorSpec("a", "//a", null).dataType()),
                () -> assertNull(new FieldSelectorSpec("b", 1, null).dataType()));
    }

    /**
     * Counted from one, as {@link Selector.Nth} counts - so the parameter is not
     * the 0-based index its name once claimed, and 0 is refused rather than
     * quietly meaning the first component.
     */
    @Test
    void theCountedOverloadCountsFromOne() {
        assertEquals(1, ((Selector.Nth) new FieldSelectorSpec("a", 1, null).selector()).n());
        assertEquals(0, ((Selector.Nth) new FieldSelectorSpec("a", 1, null).selector()).index());
        assertThrows(IllegalArgumentException.class, () -> new FieldSelectorSpec("a", 0, null));
    }

    /**
     * An adapter with no notion of a component asks for the selector as text, and
     * the refusal names the field and says which of the two spellings to use -
     * the spec having confused two formats rather than made a typo.
     */
    @Test
    void requireTextRefusesAcountedSelector() {
        var thrown = assertThrows(IllegalArgumentException.class,
                () -> new FieldSelectorSpec("id", 2, DataType.TEXT)
                        .requireText("a fixed-length record has offsets"));
        assertAll(
                () -> assertTrue(thrown.getMessage().contains("id"), thrown.getMessage()),
                () -> assertTrue(thrown.getMessage().contains("offsets"), thrown.getMessage()),
                () -> assertTrue(thrown.getMessage().contains("'nth'"),
                        "and says what to write instead: " + thrown.getMessage()));
    }

    @Test
    void requireTextYieldsTheSelectorOfAtextOne() {
        assertEquals("//nav", new FieldSelectorSpec("nav", "//nav", null).requireText("whatever"));
    }
}
