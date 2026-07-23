package com.pd.xldr.spec.test;

import com.pd.xldr.spec.CommitPolicy;
import com.pd.xldr.spec.DataType;
import com.pd.xldr.spec.FieldMappingSpec;
import com.pd.xldr.spec.FieldSelectorSpec;
import com.pd.xldr.spec.InputSpec;
import com.pd.xldr.spec.LoadSpec;
import com.pd.xldr.spec.MappingSpec;
import com.pd.xldr.spec.RecordMappingSpec;
import com.pd.xldr.spec.RecordSelectorSpec;
import com.pd.xldr.spec.io.XmlMappingSpecReader;
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
                    </mapping>
                    <mapping recordSelector="position" databaseTable="snposition">
                        <fieldMapping fieldSelector="fund" databaseColumn="mandat_nr"/>
                    </mapping>
                    <load commitPolicy="PER_MAPPING"/>
                </mappingSpec>
                """;

        var expected = new MappingSpec(
                new InputSpec("text/xml", "glob:*.done", List.of(
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
                                new FieldMappingSpec("desc", "kbez_txt")
                        )),
                        new RecordMappingSpec("position", "snposition", List.of(
                                new FieldMappingSpec("fund", "mandat_nr")
                        ))
                ),
                new LoadSpec(CommitPolicy.PER_MAPPING)
        );

        assertEquals(expected, new XmlMappingSpecReader().readFrom(new StringReader(source)));
    }

    /**
     * The load element is optional and defaults to ON_CLOSE, as in JSON.
     */
    @Test
    public void defaultsTheLoadSpec() throws IOException {
        var source = """
                <mappingSpec>
                    <input mimeType="text/csv"/>
                </mappingSpec>
                """;
        var spec = new XmlMappingSpecReader().readFrom(new StringReader(source));
        assertEquals(new LoadSpec(CommitPolicy.ON_CLOSE), spec.loadSpec());
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
