package io.github.ralfspoeth.xldr.spec.io;

import io.github.ralfspoeth.xldr.spec.Discriminator;
import io.github.ralfspoeth.xldr.spec.FieldSelectorSpec;
import io.github.ralfspoeth.xldr.spec.Locator;
import io.github.ralfspoeth.xldr.spec.Selector;
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
public class XsdTest {

    private static final Path SCHEMA = Path.of("..", "docs", "schema", "mapping-spec-0.35.xsd");

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
                    <fieldMapping expr="${xldr.filename}" column="loaded_from"/>
                    <fieldMapping column="country_id">
                        <lookup table="country" column="id" keyColumn="iso" fieldSelector="c"/>
                    </fieldMapping>
                </mapping>
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
    public void theCompleteSpecIsValidAndReadable() {
        assertDoesNotThrow(() -> validate(COMPLETE_SPEC));

        var spec = new XmlMappingSpecReader().read(stream(COMPLETE_SPEC));
        assertTrue(spec.inputSpec().properties().containsKey("ns.f"));
        assertTrue(spec.recordMappingSpecs().stream()
                .anyMatch(m -> m.fieldMappings().size() == 5));
    }

    /**
     * A field may count its component instead of naming one, and the schema has
     * to say so - otherwise an editor flags a spec the reader loads happily,
     * which is the drift this class exists to catch.
     */
    @Test
    public void aFieldSelectorMayCountInsteadOfNaming() {
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
    public void theSchemaTypesNthAsANumber() {
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
    public void aRecordSelectorMayCarryADiscriminator() {
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
    public void aMinimalSpecIsValid() {
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
    public void aRecordSelectorMayOmitItsSelector() {
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
    public void commentsAreAllowed() {
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
    public void aCommentAttributeIsAllowedEverywhereAndIgnored() {
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
        assertEquals("person", List.copyOf(spec.recordMappingSpecs()).getFirst().table());
    }

    /**
     * The mistakes worth catching in an editor: a missing required attribute, a
     * misspelled one, an unknown field type, and a delivery rule left behind in
     * the spec now that it belongs to the feed's delivery.properties.
     */
    @Test
    public void catchesTheMistakesItShould() {
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

    private static void assertAllInvalid(String xml) {
        assertThrows(SAXException.class, () -> validate(xml), () -> "should not validate: " + xml);
    }

}
