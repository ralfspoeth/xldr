package io.github.ralfspoeth.xldr.spec.io;

import io.github.ralfspoeth.xldr.spec.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.github.ralfspoeth.xldr.spec.io.Streams.stream;
import static org.junit.jupiter.api.Assertions.assertEquals;

class XmlMappingSpecReaderTest {

    @Test
    void readsAcompleteSpec() {
        var source = """
                <?xml version='1.0'?>
                <mappingSpec>
                    <input mimeType="text/xml">
                        <recordSelector name="fund" selector="/root/fund">
                            <fieldSelector name="id" selector="@id" type="TEXT"/>
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
                new InputSpec("text/xml", List.of(
                        new RecordSelectorSpec("fund", new Locator.At("/root/fund"), List.of(
                                new FieldSelectorSpec("id", "@id", DataType.TEXT),
                                // lower case in the document, the enum is matched case-insensitively
                                new FieldSelectorSpec("nav", "nav", DataType.DECIMAL),
                                // no type attribute at all
                                new FieldSelectorSpec("desc", "normalize-space(./text())", null)
                        )),
                        new RecordSelectorSpec("position", new Locator.At("//position"), List.of(
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

        assertEquals(expected, new XmlMappingSpecReader().read(stream(source)));
    }

    /**
     * A spec carrying only an input parses, with no record selectors or mappings.
     */
    @Test
    void parsesAnInputOnlySpec() {
        var source = """
                <mappingSpec>
                    <input mimeType="text/csv"/>
                </mappingSpec>
                """;
        var spec = new XmlMappingSpecReader().read(stream(source));
        assertEquals(List.of(), List.copyOf(spec.inputSpec().recordSelectors()));
        assertEquals(List.of(), List.copyOf(spec.recordMappingSpecs()));
    }

    /**
     * Vars are parsed as input-level named sources, and a field mapping refers to
     * one with a {@code var} attribute.
     */
    @Test
    void parsesVarsAndAVarReference() {
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
        var spec = new XmlMappingSpecReader().read(stream(source));

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
