package io.github.ralfspoeth.xldr.spec.io;

import io.github.ralfspoeth.xldr.spec.*;
import org.junit.jupiter.api.Test;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import java.io.File;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static io.github.ralfspoeth.xldr.spec.io.Streams.stream;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The published XSD has to keep describing what the reader accepts. A schema is
 * only useful while it agrees with the code, and nothing else would notice it
 * drifting: an editor validates against it, but no build step does.
 * <p>
 * The schema is the one served from GitHub Pages, read from the repository so
 * that the file the author downloads is the file tested here.
 */
class XsdTest {

    private static final Path SCHEMA = Path.of("..", "docs", "schema", "mapping-spec-0.44.xsd");

    /**
     * Every element and attribute the reader knows, in one document.
     */
    private static final String COMPLETE_SPEC = """
            <?xml version='1.0'?>
            <mappingSpec>
                <input mimeType="text/xml">
                    <properties ns.f="https://example.com/funds" dateFormat="dd.MM.yyyy"/>
                    <var name="source" constant="PD"/>
                    <var name="batch">
                        <lookup table="load_batch" column="id" keyColumn="feed" constant="funds"/>
                    </var>
                    <var name="loadId">
                        <fn name="pkg_load.next_id" type="INTEGRAL">
                            <arg constant="funds"/>
                            <arg var="source"/>
                            <arg>
                                <fn name="today" type="DATE"/>
                            </arg>
                        </fn>
                    </var>
                    <recordSelector name="fund" selector="/root/fund">
                        <fieldSelector name="id" selector="@id" type="text"/>
                        <fieldSelector name="nav" selector="nav" type="decimal"/>
                        <fieldSelector name="desc" selector="normalize-space(./text())"/>
                    </recordSelector>
                </input>
                <mapping recordSelector="fund" table="snmandat" limit="1000">
                    <fieldMapping fieldSelector="id" column="ident1_txt"/>
                    <fieldMapping constant="X" column="status_cd"/>
                    <fieldMapping var="source" column="source_cd"/>
                    <fieldMapping var="loadId" column="load_id"/>
                    <fieldMapping expr="${xldr.filename}" column="loaded_from"/>
                    <fieldMapping column="country_id">
                        <lookup table="country" column="id" keyColumn="iso" fieldSelector="c"/>
                    </fieldMapping>
                    <fieldMapping column="factor">
                        <lookup table="rate" column="factor">
                            <conditions>
                                <condition column="ccy" fieldSelector="id"/>
                                <condition column="asof" var="source"/>
                            </conditions>
                        </lookup>
                    </fieldMapping>
                </mapping>
                <transform name="pkg_load.close_batch">
                    <arg var="batch"/>
                    <arg expr="${xldr.rowsLoaded}"/>
                </transform>
                <transform name="reconcile"/>
            </mappingSpec>
            """;

    private static Schema schema() throws Exception {
        assertTrue(Files.isRegularFile(SCHEMA), "schema not found at " + SCHEMA.toAbsolutePath());
        return SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI)
                .newSchema(new File(SCHEMA.toString()));
    }

    private static void validate(String xml) throws Exception {
        schema().newValidator().validate(new StreamSource(new StringReader(xml)));
    }

    /**
     * What the schema accepts, the reader reads - so a spec an editor calls
     * valid is one the server can actually load.
     */
    @Test
    void theCompleteSpecIsValidAndReadable() {
        assertDoesNotThrow(() -> validate(COMPLETE_SPEC));

        var spec = new XmlMappingSpecReader().read(stream(COMPLETE_SPEC));
        assertTrue(spec.inputSpec().properties().containsKey("ns.f"));
        assertTrue(spec.recordMappingSpecs().stream()
                .anyMatch(m -> m.fieldMappings().size() == 7));
        assertEquals(
                new ValueSource.FunctionCall("pkg_load.next_id", DataType.INTEGRAL, List.of(
                        new ValueSource.Constant("funds"),
                        new ValueSource.Var("source"),
                        new ValueSource.FunctionCall("today", DataType.DATE, List.of()))),
                spec.inputSpec().vars().stream()
                        .filter(v -> v.name().equals("loadId"))
                        .findFirst()
                        .orElseThrow()
                        .source(),
                "the nesting the schema allows is the nesting the reader builds");
        assertEquals(
                List.of(
                        new ProcedureCall("pkg_load.close_batch", List.of(
                                new ValueSource.Var("batch"),
                                new ValueSource.Expr("${xldr.rowsLoaded}"))),
                        new ProcedureCall("reconcile", List.of())),
                spec.transforms());
    }

    /**
     * A field may count its component instead of naming one, and the schema has
     * to say so - otherwise an editor flags a spec the reader loads happily,
     * which is the drift this class exists to catch.
     */
    @Test
    void aFieldSelectorMayCountInsteadOfNaming() {
        var xml = """
                <mappingSpec>
                    <input mimeType="text/csv">
                        <properties header="absent"/>
                        <recordSelector name="people">
                            <fieldSelector name="id" nth="1" type="INTEGRAL"/>
                            <fieldSelector name="name" nth="2"/>
                        </recordSelector>
                    </input>
                </mappingSpec>
                """;
        assertDoesNotThrow(() -> validate(xml));

        var fields = new XmlMappingSpecReader().read(stream(xml))
                .inputSpec().recordSelectors().iterator().next().fieldSelectors();
        assertEquals(
                List.of(new Selector.Nth(1), new Selector.Nth(2)),
                fields.stream().map(FieldSelectorSpec::selector).toList());
    }

    /**
     * The payoff of two names rather than one attribute of two types: the schema
     * types {@code nth}, so a spec counting a component called {@code first} is
     * refused by an editor before it is ever read.
     */
    @Test
    void theSchemaTypesNthAsANumber() {
        assertThrows(Exception.class, () -> validate("""
                <mappingSpec>
                    <input mimeType="text/csv">
                        <recordSelector name="people">
                            <fieldSelector name="id" nth="first"/>
                        </recordSelector>
                    </input>
                </mappingSpec>
                """));
    }

    /**
     * And a flat record selector may carry a discriminator, saying which lines
     * are of its kind.
     */
    @Test
    void aRecordSelectorMayCarryADiscriminator() {
        var xml = """
                <mappingSpec>
                    <input mimeType="text/csv">
                        <properties header="absent"/>
                        <recordSelector name="orders">
                            <discriminator nth="1" equals="O"/>
                            <fieldSelector name="id" nth="2"/>
                        </recordSelector>
                        <recordSelector name="lines">
                            <discriminator selector="kind" matches="L[0-9]+"/>
                            <fieldSelector name="id" nth="2"/>
                        </recordSelector>
                    </input>
                </mappingSpec>
                """;
        assertDoesNotThrow(() -> validate(xml));

        var selectors = new XmlMappingSpecReader().read(stream(xml))
                .inputSpec().recordSelectors().stream().toList();
        assertAll(
                () -> assertEquals(
                        new Locator.Where(new Discriminator.Equals(new Selector.Nth(1), "O")),
                        selectors.getFirst().locator()),
                () -> assertInstanceOf(Discriminator.Matches.class,
                        assertInstanceOf(Locator.Where.class, selectors.get(1).locator()).test()));
    }

    /**
     * A minimal spec - only what is required - validates too.
     */
    @Test
    void aMinimalSpecIsValid() {
        assertDoesNotThrow(() -> validate("""
                <mappingSpec>
                    <input mimeType="text/csv"/>
                </mappingSpec>
                """));
    }

    /**
     * A record selector may omit its selector, and the schema has to allow that
     * or an editor would flag a spec the server loads happily: a CSV with a
     * header and a fixed-length file each hold one kind of record, so there is
     * nothing to locate. The adapters that do need one say so themselves.
     */
    @Test
    void aRecordSelectorMayOmitItsSelector() {
        var xml = """
                <mappingSpec>
                    <input mimeType="text/csv">
                        <recordSelector name="people">
                            <fieldSelector name="id" selector="id" type="INTEGRAL"/>
                        </recordSelector>
                    </input>
                </mappingSpec>
                """;
        assertDoesNotThrow(() -> validate(xml));

        var spec = new XmlMappingSpecReader().read(stream(xml));
        var recordSelector = List.copyOf(spec.inputSpec().recordSelectors()).getFirst();
        assertEquals(Locator.every(), recordSelector.locator());
    }

    /**
     * XSD 1.0 cannot allow arbitrary extra elements beside named optional ones -
     * the content model would not be deterministic - so an XML spec is annotated
     * with XML comments, which are always allowed and which the reader ignores
     * as a matter of course.
     */
    @Test
    void commentsAreAllowed() {
        assertDoesNotThrow(() -> validate("""
                <mappingSpec>
                    <!-- why this feed exists -->
                    <input mimeType="text/csv"/>
                </mappingSpec>
                """));
    }

    /**
     * A {@code comment} attribute is allowed on every element and ignored by the
     * reader. It is named in the schema rather than left to the general
     * tolerance of the readers, because the schema refuses an attribute it does
     * not know - and that refusal is what catches a misspelling, which is what
     * an unknown attribute usually is.
     */
    @Test
    void aCommentAttributeIsAllowedEverywhereAndIgnored() {
        var xml = """
                <mappingSpec comment="the fund feed">
                    <input mimeType="text/csv" comment="delivered nightly">
                        <var name="source" constant="PD" comment="the sending system"/>
                        <recordSelector name="people" comment="one record per line">
                            <fieldSelector name="id" selector="Id" type="INTEGRAL" comment="the key"/>
                        </recordSelector>
                    </input>
                    <mapping recordSelector="people" table="person" comment="the only mapping">
                        <fieldMapping fieldSelector="id" column="id" comment="straight through"/>
                        <fieldMapping column="country_id">
                            <lookup table="country" column="id" keyColumn="iso" constant="DE" comment="fixed"/>
                        </fieldMapping>
                    </mapping>
                </mappingSpec>
                """;
        assertDoesNotThrow(() -> validate(xml));

        var spec = new XmlMappingSpecReader().read(stream(xml));
        assertEquals(new SqlIdentifier("person"), List.copyOf(spec.recordMappingSpecs()).getFirst().table());
    }

    /**
     * The mistakes worth catching in an editor: a missing required attribute, a
     * misspelled one, an unknown field type, and a delivery rule left behind in
     * the spec now that it belongs to the feed's delivery.properties.
     */
    @Test
    void catchesTheMistakesItShould() {
        assertAllInvalid("""
                <mappingSpec>
                    <input/>
                </mappingSpec>
                """);
        // a spec still carrying the delivery rule the server now owns: refused
        // rather than quietly ignored, so that it is moved and not merely dropped
        assertAllInvalid("""
                <mappingSpec>
                    <input mimeType="text/csv" accepts="glob:*.csv"/>
                </mappingSpec>
                """);
        assertAllInvalid("""
                <mappingSpec>
                    <input mimeType="text/csv">
                        <recordSelector name="r" selectr="//r"/>
                    </input>
                </mappingSpec>
                """);
        assertAllInvalid("""
                <mappingSpec>
                    <input mimeType="text/csv">
                        <recordSelector name="r" selector="//r">
                            <fieldSelector name="id" selector="@id" type="asdf"/>
                        </recordSelector>
                    </input>
                </mappingSpec>
                """);
    }

    // ---- and the rules this schema version exists for ------------------------

    /**
     * A call belongs to a var and not to a column: it is made once per load, and
     * a column is bound once per record, so the same call in a column would be a
     * round trip per row. {@code fn} is therefore a child of {@code <var>} and of
     * {@code <arg>}, and of nothing under {@code <mapping>}.
     */
    @Test
    void aColumnCannotCallAFunction() {
        assertRefusedByBoth("""
                <mappingSpec>
                    <input mimeType="text/csv"/>
                    <mapping recordSelector="r" table="t">
                        <fieldMapping column="load_id">
                            <fn name="next_id" type="INTEGRAL"/>
                        </fieldMapping>
                    </mapping>
                </mappingSpec>
                """);
    }

    /**
     * An argument is evaluated at the same moment as the var it feeds, which is
     * before the first record is read - so it may be anything a var may be, and
     * a field is not among them. {@code <arg>} carries no {@code fieldSelector}.
     */
    @Test
    void aCallArgumentCannotReadAField() {
        assertRefusedByBoth("""
                <mappingSpec>
                    <input mimeType="text/csv">
                        <var name="loadId">
                            <fn name="next_id" type="INTEGRAL">
                                <arg fieldSelector="id"/>
                            </fn>
                        </var>
                    </input>
                </mappingSpec>
                """);
    }

    /**
     * The same rule one level over: a lookup under a var is keyed with no record
     * in hand either. The schema said this for the first time in 0.40 - until
     * then one {@code lookup} type served both places, so a var keyed by a field
     * validated in an editor and threw at load.
     */
    @Test
    void aVarLookupCannotBeKeyedByAField() {
        assertRefusedByBoth("""
                <mappingSpec>
                    <input mimeType="text/csv">
                        <var name="batch">
                            <lookup table="load_batch" column="id" keyColumn="feed" fieldSelector="f"/>
                        </var>
                    </input>
                </mappingSpec>
                """);
    }

    /**
     * A call says the type it returns, where a field selector may leave its type
     * out: the loader registers the OUT parameter before the call and has
     * nothing to infer it from.
     */
    @Test
    void aCallSaysWhatItReturns() {
        assertRefusedByBoth("""
                <mappingSpec>
                    <input mimeType="text/csv">
                        <var name="loadId">
                            <fn name="next_id"/>
                        </var>
                    </input>
                </mappingSpec>
                """);
    }

    /**
     * A function name is the one part of a value source that reaches the text of
     * a statement, so it is held to being a name - identifiers separated by dots
     * and nothing else. Everything else a spec contributes goes in as a bound
     * parameter.
     */
    @Test
    void aFunctionNameIsAName() {
        assertRefusedByBoth("""
                <mappingSpec>
                    <input mimeType="text/csv">
                        <var name="loadId">
                            <fn name="next_id(1); drop table t" type="INTEGRAL"/>
                        </var>
                    </input>
                </mappingSpec>
                """);
    }

    // ---- and the rules 0.41 was published for --------------------------------

    /**
     * A transform's argument is evaluated after the last record, so it may no
     * more read a field than a var's source may - the same {@code <arg>} the
     * schema already gives an {@code <fn>}, and the same refusal.
     */
    @Test
    void aTransformArgumentCannotReadAField() {
        assertRefusedByBoth("""
                <mappingSpec>
                    <input mimeType="text/csv"/>
                    <transform name="close_batch">
                        <arg fieldSelector="id"/>
                    </transform>
                </mappingSpec>
                """);
    }

    /**
     * A transform has no type, where an {@code <fn>} requires one: nothing comes
     * back from a procedure, so there is no OUT parameter to declare. Writing
     * one is the mistake of having meant an {@code <fn>}, and the schema says so
     * rather than accepting an attribute the reader would ignore.
     */
    @Test
    void aTransformSaysNoType() {
        assertAllInvalid("""
                <mappingSpec>
                    <input mimeType="text/csv"/>
                    <transform name="close_batch" type="INTEGRAL"/>
                </mappingSpec>
                """);
    }

    /** and its name is a name, as a function's is */
    @Test
    void aTransformNameIsAName() {
        assertRefusedByBoth("""
                <mappingSpec>
                    <input mimeType="text/csv"/>
                    <transform name="close(); drop table t"/>
                </mappingSpec>
                """);
    }

    /**
     * The schema puts transforms after the mappings, which the reader does not
     * require - it collects them wherever they stand. Stricter in the editor
     * than in the reader, and deliberately: a transform runs after the load, and
     * a spec that writes it first says something it does not mean.
     * <p>
     * The one place this file records the two disagreeing on purpose. Everywhere
     * else a difference between them is the drift it exists to catch.
     */
    @Test
    void theSchemaWantsTransformsLastEvenThoughTheReaderDoesNot() {
        var outOfOrder = """
                <mappingSpec>
                    <input mimeType="text/csv"/>
                    <transform name="reconcile"/>
                    <mapping recordSelector="r" table="t">
                        <fieldMapping fieldSelector="id" column="id"/>
                    </mapping>
                </mappingSpec>
                """;
        assertAllInvalid(outOfOrder);
        assertDoesNotThrow(() -> new XmlMappingSpecReader().read(stream(outOfOrder)),
                "the reader takes it anyway, so the schema is the strict one here");
    }

    // ---- and the rules 0.43 was published for --------------------------------

    /**
     * A condition names the column it matches on, which is the one thing about
     * it a schema can insist on.
     */
    @Test
    void aConditionNamesItsColumn() {
        assertRefusedByBoth("""
                <mappingSpec>
                    <input mimeType="text/csv"/>
                    <mapping recordSelector="r" table="t">
                        <fieldMapping column="x">
                            <lookup table="rate" column="factor">
                                <conditions><condition var="v"/></conditions>
                            </lookup>
                        </fieldMapping>
                    </mapping>
                </mappingSpec>
                """);
    }

    /**
     * A var's condition is a var source: no field, because a var is evaluated
     * before the first record. The XSD can say this, giving the two lookup
     * flavours their own condition types, exactly as it already gives them their
     * own lookup types.
     */
    @Test
    void aVarLookupConditionCannotReadAField() {
        assertRefusedByBoth("""
                <mappingSpec>
                    <input mimeType="text/csv">
                        <var name="v">
                            <lookup table="rate" column="factor">
                                <conditions><condition column="ccy" fieldSelector="c"/></conditions>
                            </lookup>
                        </var>
                    </input>
                </mappingSpec>
                """);
    }

    /**
     * That a lookup says {@code keyColumn} or {@code <condition>} children and
     * not both is a rule XSD 1.0 cannot state - it is the exactly-one-of shape
     * again - so this asserts the schema is the permissive one and the reader
     * catches it, which is the division the two have everywhere else.
     */
    @Test
    void theSchemaCannotRefuseBothSpellingsAtOnceButTheReaderDoes() {
        var both = """
                <mappingSpec>
                    <input mimeType="text/csv"/>
                    <mapping recordSelector="r" table="t">
                        <fieldMapping column="x">
                            <lookup table="rate" column="factor" keyColumn="ccy" fieldSelector="c">
                                <conditions><condition column="asof" var="d"/></conditions>
                            </lookup>
                        </fieldMapping>
                    </mapping>
                </mappingSpec>
                """;
        assertDoesNotThrow(() -> validate(both), "XSD 1.0 has no way to say one of the two");
        assertThrows(IllegalArgumentException.class,
                () -> new XmlMappingSpecReader().read(stream(both)),
                "so the reader is the only thing that can refuse it");
    }

    /**
     * And the rule the XSD has stated since 0.40 while the reader refused it: a
     * var's lookup may be keyed by a call.
     */
    @Test
    void aVarLookupMayBeKeyedByAcall() {
        var xml = """
                <mappingSpec>
                    <input mimeType="text/csv">
                        <var name="v">
                            <lookup table="load_batch" column="id" keyColumn="feed">
                                <fn name="current_feed" type="TEXT"/>
                            </lookup>
                        </var>
                    </input>
                </mappingSpec>
                """;
        assertDoesNotThrow(() -> validate(xml));
        assertDoesNotThrow(() -> new XmlMappingSpecReader().read(stream(xml)),
                "the XSD has allowed this since 0.40; the reader threw until 0.43");
    }

    /**
     * Both, because neither catches the other's cases: an editor never runs the
     * reader, and a spec the server loads was never put through the schema.
     */
    private static void assertRefusedByBoth(String xml) {
        assertAllInvalid(xml);
        assertThrows(IllegalArgumentException.class,
                () -> new XmlMappingSpecReader().read(stream(xml)),
                () -> "the reader has to refuse what the schema refuses: " + xml);
    }

    private static void assertAllInvalid(String xml) {
        assertThrows(SAXException.class, () -> validate(xml), () -> "should not validate: " + xml);
    }

}
