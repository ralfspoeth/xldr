package io.github.ralfspoeth.xldr.spec.test;

import io.github.ralfspoeth.xldr.spec.Discriminator;
import io.github.ralfspoeth.xldr.spec.MappingSpec;
import io.github.ralfspoeth.xldr.spec.Selector;
import io.github.ralfspoeth.xldr.spec.io.JsonMappingSpecReader;
import io.github.ralfspoeth.xldr.spec.io.XmlMappingSpecReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Objects;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where a value sits, and which records are of a kind - said twice, once per
 * format, and meaning the same thing both times.
 * <p>
 * Nearly every test here is written against both readers, because that is the
 * property the design turns on. The two names exist rather than one attribute of
 * two types precisely so that XML can say what JSON says; a test that only
 * exercised JSON would pass while the XML reader guessed.
 */
class SelectorTest {

    private static MappingSpec json(String recordSelector) throws IOException {
        var spec = """
                {
                  "input": { "mimeType": "text/csv", "recordSelectors": [ %s ] },
                  "mapping": []
                }
                """.formatted(recordSelector);
        return new JsonMappingSpecReader().read(new ByteArrayInputStream(spec.getBytes(UTF_8)));
    }

    private static MappingSpec xml(String recordSelector) throws IOException {
        var spec = """
                <mappingSpec>
                    <input mimeType="text/csv">
                        %s
                    </input>
                </mappingSpec>
                """.formatted(recordSelector);
        return new XmlMappingSpecReader().read(new ByteArrayInputStream(spec.getBytes(UTF_8)));
    }

    private static Selector onlyFieldSelector(MappingSpec spec) {
        return spec.inputSpec().recordSelectors().iterator().next()
                .fieldSelectors().iterator().next().selector();
    }

    private static Discriminator onlyDiscriminator(MappingSpec spec) {
        var d = spec.inputSpec().recordSelectors().iterator().next().discriminator();
        return Objects.requireNonNull(d, "the record selector carried no discriminator");
    }

    // ---- a field selector names a component or counts one ---------------------

    @Test
    void bothFormatsCount() throws IOException {
        assertAll(
                () -> assertEquals(new Selector.Nth(2), onlyFieldSelector(json("""
                        { "name": "who", "fieldSelectors": [ { "name": "id", "nth": 2 } ] }
                        """))),
                () -> assertEquals(new Selector.Nth(2), onlyFieldSelector(xml("""
                        <recordSelector name="who"><fieldSelector name="id" nth="2"/></recordSelector>
                        """))));
    }

    @Test
    void bothFormatsReadASelector() throws IOException {
        assertAll(
                () -> assertEquals(new Selector.Text("id"), onlyFieldSelector(json("""
                        { "name": "who", "fieldSelectors": [ { "name": "id", "selector": "id" } ] }
                        """))),
                () -> assertEquals(new Selector.Text("id"), onlyFieldSelector(xml("""
                        <recordSelector name="who"><fieldSelector name="id" selector="id"/></recordSelector>
                        """))));
    }

    /**
     * The case the whole change exists for: a header may name a column
     * {@code "3"}, and that is now sayable and distinct from the third one.
     */
    @Test
    void aColumnNamedThreeIsNotTheThirdComponent() throws IOException {
        assertAll(
                () -> assertEquals(new Selector.Text("3"), onlyFieldSelector(json("""
                        { "name": "who", "fieldSelectors": [ { "name": "id", "selector": "3" } ] }
                        """))),
                () -> assertEquals(new Selector.Nth(3), onlyFieldSelector(json("""
                        { "name": "who", "fieldSelectors": [ { "name": "id", "nth": 3 } ] }
                        """))),
                () -> assertEquals(new Selector.Text("3"), onlyFieldSelector(xml("""
                        <recordSelector name="who"><fieldSelector name="id" selector="3"/></recordSelector>
                        """))),
                () -> assertEquals(new Selector.Nth(3), onlyFieldSelector(xml("""
                        <recordSelector name="who"><fieldSelector name="id" nth="3"/></recordSelector>
                        """))));
    }

    @Test
    void bothTogetherAreRefused() {
        assertAll(
                () -> assertRefused("two answers to one question", () -> json("""
                        { "name": "who", "fieldSelectors": [ { "name": "id", "selector": "id", "nth": 1 } ] }
                        """)),
                () -> assertRefused("two answers to one question", () -> xml("""
                        <recordSelector name="who"><fieldSelector name="id" selector="id" nth="1"/></recordSelector>
                        """)));
    }

    @Test
    void neitherIsRefused() {
        assertAll(
                () -> assertRefused("needs a selector or an nth", () -> json("""
                        { "name": "who", "fieldSelectors": [ { "name": "id" } ] }
                        """)),
                () -> assertRefused("needs a selector or an nth", () -> xml("""
                        <recordSelector name="who"><fieldSelector name="id"/></recordSelector>
                        """)));
    }

    /**
     * A quoted number in JSON is a name, not a position - coercing it would put
     * the ambiguity back one level down, where it would be harder to see.
     */
    @Test
    void nthHasToBeAWholeNumber() {
        assertAll(
                () -> assertRefused("whole number", () -> json("""
                        { "name": "who", "fieldSelectors": [ { "name": "id", "nth": "1" } ] }
                        """)),
                () -> assertRefused("whole number", () -> json("""
                        { "name": "who", "fieldSelectors": [ { "name": "id", "nth": 1.5 } ] }
                        """)),
                () -> assertRefused("whole number", () -> xml("""
                        <recordSelector name="who"><fieldSelector name="id" nth="first"/></recordSelector>
                        """)));
    }

    @Test
    void componentsAreCountedFromOne() {
        assertAll(
                () -> assertRefused("counted from 1", () -> json("""
                        { "name": "who", "fieldSelectors": [ { "name": "id", "nth": 0 } ] }
                        """)),
                () -> assertRefused("counted from 1", () -> xml("""
                        <recordSelector name="who"><fieldSelector name="id" nth="0"/></recordSelector>
                        """)));
    }

    // ---- a discriminator says where to look and what for ---------------------

    @Test
    void bothFormatsReadADiscriminator() throws IOException {
        assertAll(
                () -> assertEquals(
                        new Discriminator.Equals(new Selector.Nth(1), "O"),
                        onlyDiscriminator(json("""
                                { "name": "orders", "discriminator": { "nth": 1, "equals": "O" },
                                  "fieldSelectors": [ { "name": "id", "nth": 2 } ] }
                                """))),
                () -> assertEquals(
                        new Discriminator.Equals(new Selector.Nth(1), "O"),
                        onlyDiscriminator(xml("""
                                <recordSelector name="orders">
                                    <discriminator nth="1" equals="O"/>
                                    <fieldSelector name="id" nth="2"/>
                                </recordSelector>
                                """))));
    }

    /**
     * The discriminator column may be named rather than counted, which is what
     * makes a headed file with a type column readable - the case that showed the
     * old first-column-only rule was too narrow.
     */
    @Test
    void aDiscriminatorMayNameWhatItTests() throws IOException {
        var d = onlyDiscriminator(json("""
                { "name": "ones", "discriminator": { "selector": "A", "equals": "1" },
                  "fieldSelectors": [ { "name": "b", "selector": "B" } ] }
                """));
        assertAll(
                () -> assertEquals(new Selector.Text("A"), d.at()),
                () -> assertTrue(d.accepts("1")),
                () -> assertFalse(d.accepts("2")));
    }

    @Test
    void aPatternIsCompiledWhenTheSpecIsRead() throws IOException {
        var d = onlyDiscriminator(json("""
                { "name": "orders", "discriminator": { "nth": 1, "matches": "^O[0-9]+$" },
                  "fieldSelectors": [ { "name": "id", "nth": 2 } ] }
                """));
        assertAll(
                () -> assertInstanceOf(Discriminator.Matches.class, d),
                () -> assertTrue(d.accepts("O12")),
                () -> assertFalse(d.accepts("L12")),
                // matches, not find: a pattern says what a whole value looks like
                () -> assertFalse(d.accepts("xO12")));
    }

    @Test
    void aPatternThatDoesNotCompileIsRefusedThere() {
        assertRefused("does not compile", () -> json("""
                { "name": "orders", "discriminator": { "nth": 1, "matches": "^O[0-9" },
                  "fieldSelectors": [ { "name": "id", "nth": 2 } ] }
                """));
    }

    /** A record type written unquoted is a record type, not a puzzle. */
    @Test
    void equalsTakesAnyScalar() throws IOException {
        assertTrue(onlyDiscriminator(json("""
                { "name": "ones", "discriminator": { "nth": 1, "equals": 1 },
                  "fieldSelectors": [ { "name": "b", "nth": 2 } ] }
                """)).accepts("1"));
    }

    @Test
    void aDiscriminatorNeedsExactlyOneTest() {
        assertAll(
                () -> assertRefused("not both", () -> json("""
                        { "name": "o", "discriminator": { "nth": 1, "equals": "O", "matches": "O.*" },
                          "fieldSelectors": [ { "name": "id", "nth": 2 } ] }
                        """)),
                () -> assertRefused("not both", () -> xml("""
                        <recordSelector name="o">
                            <discriminator nth="1" equals="O" matches="O.*"/>
                            <fieldSelector name="id" nth="2"/>
                        </recordSelector>
                        """)),
                () -> assertRefused("needs equals or matches", () -> json("""
                        { "name": "o", "discriminator": { "nth": 1 },
                          "fieldSelectors": [ { "name": "id", "nth": 2 } ] }
                        """)),
                () -> assertRefused("needs equals or matches", () -> xml("""
                        <recordSelector name="o">
                            <discriminator nth="1"/>
                            <fieldSelector name="id" nth="2"/>
                        </recordSelector>
                        """)));
    }

    /**
     * Pointing and filtering are what the two kinds of input need, and no input
     * needs both: a spec carrying both has confused an XPath with a column.
     */
    @Test
    void aRecordSelectorCannotBothPointAndFilter() {
        assertAll(
                () -> assertRefused("no input is read both ways", () -> json("""
                        { "name": "o", "selector": "//order", "discriminator": { "nth": 1, "equals": "O" },
                          "fieldSelectors": [ { "name": "id", "nth": 2 } ] }
                        """)),
                () -> assertRefused("no input is read both ways", () -> xml("""
                        <recordSelector name="o" selector="//order">
                            <discriminator nth="1" equals="O"/>
                            <fieldSelector name="id" nth="2"/>
                        </recordSelector>
                        """)));
    }

    // ---- a record selector's field names are its own --------------------------

    /**
     * Two field selectors of one name are refused, in both formats and therefore
     * for every adapter.
     * <p>
     * The rule sits on {@link io.github.ralfspoeth.xldr.spec.RecordSelectorSpec}
     * rather than in the adapters because all five of them build a map keyed by
     * that name, and each was quietly picking a winner - CSV the first
     * declaration, the others whichever the loop reached last. A duplicate is not
     * written on purpose; it is a name that was meant to be different, so the
     * field the author intended is missing and a mapping naming it fails
     * elsewhere, about something else.
     */
    @Test
    void twoFieldSelectorsOfOneNameAreRefused() {
        assertAll(
                () -> assertRefused("more than once", () -> json("""
                        { "name": "who", "fieldSelectors": [
                            { "name": "id", "selector": "a" },
                            { "name": "id", "selector": "b" } ] }
                        """)),
                () -> assertRefused("more than once", () -> xml("""
                        <recordSelector name="who">
                            <fieldSelector name="id" selector="a"/>
                            <fieldSelector name="id" selector="b"/>
                        </recordSelector>
                        """)),
                // and the message names the one that repeats, there being no
                // other way to find it in a layout of forty
                () -> assertRefused("id", () -> json("""
                        { "name": "who", "fieldSelectors": [
                            { "name": "a", "selector": "a" },
                            { "name": "id", "selector": "b" },
                            { "name": "id", "selector": "c" } ] }
                        """)));
    }

    // ---- what a discriminator does with a value ------------------------------

    /**
     * Both sides stripped, because a flat file pads and a spec should not have to
     * say so; and a record without the column matches nothing, one that could not
     * be asked not being one that answered.
     */
    @Test
    void valuesAreStrippedAndAnAbsentComponentMatchesNothing() {
        var equals = new Discriminator.Equals(new Selector.Nth(1), "O");
        assertAll(
                () -> assertTrue(equals.accepts("  O  ")),
                () -> assertFalse(equals.accepts("")),
                () -> assertFalse(equals.accepts(null)),
                () -> assertFalse(Discriminator.matching(new Selector.Nth(1), "O.*").accepts(null)));
    }

    /**
     * A refusal is only useful if it says which of the two things went wrong, so
     * each of these asserts on the message and not merely on the type.
     */
    private static void assertRefused(String saying, Executable read) {
        var thrown = assertThrows(IllegalArgumentException.class, read);
        assertTrue(String.valueOf(thrown.getMessage()).contains(saying),
                () -> "expected a message saying '" + saying + "', was: " + thrown.getMessage());
    }
}
