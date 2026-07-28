package io.github.ralfspoeth.xldr.spec.test;

import io.github.ralfspoeth.xldr.spec.*;
import io.github.ralfspoeth.xldr.spec.io.XmlMappingSpecReader;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
                    <mapping recordSelector="fund" table="snmandat">
                        <fieldMapping fieldSelector="id" column="ident1_txt"/>
                        <fieldMapping fieldSelector="desc" column="kbez_txt"/>
                        <fieldMapping constant="PD" column="syssnmut_cd"/>
                    </mapping>
                    <mapping recordSelector="position" table="snposition" limit="500">
                        <fieldMapping fieldSelector="fund" column="mandat_nr"/>
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
                ), List.of(), Map.of()),
                List.of(
                        new RecordMappingSpec("fund", "snmandat", List.of(
                                new FieldMappingSpec("ident1_txt", new ValueSource.Field("id")),
                                new FieldMappingSpec("kbez_txt", new ValueSource.Field("desc")),
                                // XML constants are always strings - attributes carry no type
                                new FieldMappingSpec("syssnmut_cd", new ValueSource.Constant("PD"))
                        ), null),
                        new RecordMappingSpec("position", "snposition", List.of(
                                new FieldMappingSpec("mandat_nr", new ValueSource.Field("fund"))
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
     * Vars are parsed as input-level named sources, and a field mapping refers to
     * one with a {@code var} attribute.
     */
    @Test
    public void parsesVarsAndAVarReference() throws IOException {
        var source = """
                <mappingSpec>
                    <input mimeType="text/csv">
                        <var name="batchId" constant="B1"/>
                        <var name="src" constant="PD"/>
                    </input>
                    <mapping recordSelector="r" table="t">
                        <fieldMapping var="batchId" column="batch_id"/>
                    </mapping>
                </mappingSpec>
                """;
        var spec = new XmlMappingSpecReader().readFrom(new StringReader(source));

        assertEquals(
                List.of(
                        new VarSpec("batchId", new ValueSource.Constant("B1")),
                        new VarSpec("src", new ValueSource.Constant("PD"))
                ),
                List.copyOf(spec.inputSpec().vars()));

        var mapping = List.copyOf(spec.recordMappingSpecs()).getFirst();
        var fm = List.copyOf(mapping.fieldMappings()).getFirst();
        assertEquals(new ValueSource.Var("batchId"), fm.source());
    }
}
