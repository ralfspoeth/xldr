package io.github.ralfspoeth.xldr.spec.test;

import io.github.ralfspoeth.xldr.spec.io.XmlMappingSpecReader;
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

import static io.github.ralfspoeth.xldr.spec.test.Streams.stream;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The published XSD has to keep describing what the reader accepts. A schema is
 * only useful while it agrees with the code, and nothing else would notice it
 * drifting: an editor validates against it, but no build step does.
 * <p>
 * The schema is the one served from GitHub Pages, read from the repository so
 * that the file the author downloads is the file tested here.
 */
public class XsdTest {

    private static final Path SCHEMA = Path.of("..", "docs", "schema", "mapping-spec-0.13.xsd");

    /**
     * Every element and attribute the reader knows, in one document.
     */
    private static final String COMPLETE_SPEC = """
            <?xml version='1.0'?>
            <mappingSpec>
                <input mimeType="text/xml" accepts="glob:*.xml">
                    <properties ns.f="http://example.com/funds" dateFormat="dd.MM.yyyy"/>
                    <var name="source" constant="PD"/>
                    <var name="batch">
                        <lookup table="load_batch" column="id" keyColumn="feed" constant="funds"/>
                    </var>
                    <recordSelector name="fund" selector="/root/fund">
                        <fieldSelector name="id" selector="@id" type="STRING"/>
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
    public void theCompleteSpecIsValidAndReadable() throws Exception {
        assertDoesNotThrow(() -> validate(COMPLETE_SPEC));

        var spec = new XmlMappingSpecReader().read(stream(COMPLETE_SPEC));
        assertTrue(spec.inputSpec().properties().containsKey("ns.f"));
        assertTrue(spec.recordMappingSpecs().stream()
                .anyMatch(m -> m.fieldMappings().size() == 5));
    }

    /**
     * A minimal spec - only what is required - validates too.
     */
    @Test
    public void aMinimalSpecIsValid() {
        assertDoesNotThrow(() -> validate("""
                <mappingSpec>
                    <input mimeType="text/csv" sentinel="glob:*.done"/>
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
    public void aRecordSelectorMayOmitItsSelector() throws Exception {
        var xml = """
                <mappingSpec>
                    <input mimeType="text/csv" accepts="glob:*.csv">
                        <recordSelector name="people">
                            <fieldSelector name="id" selector="id" type="INTEGER"/>
                        </recordSelector>
                    </input>
                </mappingSpec>
                """;
        assertDoesNotThrow(() -> validate(xml));

        var spec = new XmlMappingSpecReader().read(stream(xml));
        var recordSelector = List.copyOf(spec.inputSpec().recordSelectors()).getFirst();
        assertNull(recordSelector.selector());
        assertThrows(IllegalArgumentException.class, recordSelector::requireSelector);
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
                    <input mimeType="text/csv" accepts="glob:*.csv"/>
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
    public void aCommentAttributeIsAllowedEverywhereAndIgnored() throws Exception {
        var xml = """
                <mappingSpec comment="the fund feed">
                    <input mimeType="text/csv" accepts="glob:*.csv" comment="delivered nightly">
                        <var name="source" constant="PD" comment="the sending system"/>
                        <recordSelector name="people" comment="one record per line">
                            <fieldSelector name="id" selector="Id" type="INTEGER" comment="the key"/>
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
     * misspelled one, and a delivery pattern without its glob:/regex: prefix.
     */
    @Test
    public void catchesTheMistakesItShould() {
        assertAllInvalid("""
                <mappingSpec>
                    <input accepts="glob:*.csv"/>
                </mappingSpec>
                """);
        assertAllInvalid("""
                <mappingSpec>
                    <input mimeType="text/csv" accepts="*.csv"/>
                </mappingSpec>
                """);
        assertAllInvalid("""
                <mappingSpec>
                    <input mimeType="text/csv" accepts="glob:*.csv">
                        <recordSelector name="r" selectr="//r"/>
                    </input>
                </mappingSpec>
                """);
        assertAllInvalid("""
                <mappingSpec>
                    <input mimeType="text/csv" accepts="glob:*.csv">
                        <recordSelector name="r" selector="//r">
                            <fieldSelector name="id" selector="@id" type="TEXT"/>
                        </recordSelector>
                    </input>
                </mappingSpec>
                """);
    }

    private static void assertAllInvalid(String xml) {
        assertThrows(SAXException.class, () -> validate(xml), () -> "should not validate: " + xml);
    }

}
