package io.github.ralfspoeth.xldr.spec.test;

import io.github.ralfspoeth.xldr.spec.ColumnSource;
import io.github.ralfspoeth.xldr.spec.DataType;
import io.github.ralfspoeth.xldr.spec.FieldMappingSpec;
import io.github.ralfspoeth.xldr.spec.FieldSelectorSpec;
import io.github.ralfspoeth.xldr.spec.InputSpec;
import io.github.ralfspoeth.xldr.spec.MappingSpec;
import io.github.ralfspoeth.xldr.spec.RecordMappingSpec;
import io.github.ralfspoeth.xldr.spec.RecordSelectorSpec;
import io.github.ralfspoeth.xldr.spec.io.XmlMappingSpecReader;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class XmlMappingSpecReaderTest {

    @Test
    public void readsAcompleteSpec() throws IOException {
        var source = """
                <?xml version='1.0'?>
                <mappingSpec>
                    <input mimeType="text/xml" sentinel="glob:*.done">
                        <recordSelector name="fund" selector="/root/fund">
                            <fieldSelector name="id" selector="@id" type="STRING"/>
                            <fieldSelector name="nav" selector="nav" type="decimal"/>
                            <fieldSelector name="desc" selector="normalize-space(./text())"/>
                        </recordSelector>
                        <recordSelector name="position" selector="//position">
                            <fieldSelector name="fund" selector="../../../fund/@id"/>
                        </recordSelector>
                    </input>
                    <mapping recordSelector="fund" databaseTable="snmandat">
                        <fieldMapping fieldSelector="id" databaseColumn="ident1_txt"/>
                        <fieldMapping fieldSelector="desc" databaseColumn="kbez_txt"/>
                        <fieldMapping constant="PD" databaseColumn="syssnmut_cd"/>
                        <fieldMapping function="sysdate" databaseColumn="mut_dat"/>
                    </mapping>
                    <mapping recordSelector="position" databaseTable="snposition" limit="500">
                        <fieldMapping fieldSelector="fund" databaseColumn="mandat_nr"/>
                    </mapping>
                </mappingSpec>
                """;

        var expected = new MappingSpec(
                new InputSpec("text/xml", "glob:*.done", null, List.of(
                        new RecordSelectorSpec("fund", "/root/fund", List.of(
                                new FieldSelectorSpec("id", "@id", DataType.STRING),
                                // lower case in the document, the enum is matched case-insensitively
                                new FieldSelectorSpec("nav", "nav", DataType.DECIMAL),
                                // no type attribute at all
                                new FieldSelectorSpec("desc", "normalize-space(./text())", null)
                        )),
                        new RecordSelectorSpec("position", "//position", List.of(
                                new FieldSelectorSpec("fund", "../../../fund/@id", null)
                        ))
                )),
                List.of(
                        new RecordMappingSpec("fund", "snmandat", List.of(
                                new FieldMappingSpec("id", "ident1_txt"),
                                new FieldMappingSpec("desc", "kbez_txt"),
                                // XML constants are strings; sysdate is a raw function
                                new FieldMappingSpec(new ColumnSource.Constant("PD"), "syssnmut_cd"),
                                new FieldMappingSpec(new ColumnSource.Function("sysdate"), "mut_dat")
                        )),
                        new RecordMappingSpec("position", "snposition", List.of(
                                new FieldMappingSpec("fund", "mandat_nr")
                        ), 500)
                )
        );

        assertEquals(expected, new XmlMappingSpecReader().readFrom(new StringReader(source)));
    }

    /**
     * A spec carrying only an input parses, with no record selectors or mappings.
     */
    @Test
    public void parsesAnInputOnlySpec() throws IOException {
        var source = """
                <mappingSpec>
                    <input mimeType="text/csv"/>
                </mappingSpec>
                """;
        var spec = new XmlMappingSpecReader().readFrom(new StringReader(source));
        assertEquals(List.of(), List.copyOf(spec.inputSpec().recordSelectors()));
        assertEquals(List.of(), List.copyOf(spec.recordMappingSpecs()));
    }

    /**
     * A missing attribute is a broken spec and names itself, rather than
     * quietly becoming an empty string the way getAttribute would.
     */
    @Test
    public void reportsAmissingAttribute() {
        var source = """
                <mappingSpec>
                    <input mimeType="text/xml">
                        <recordSelector name="fund"/>
                    </input>
                </mappingSpec>
                """;
        var thrown = assertThrows(IllegalArgumentException.class,
                () -> new XmlMappingSpecReader().readFrom(new StringReader(source)));
        assertEquals("<recordSelector> has no selector attribute", thrown.getMessage());
    }
}
