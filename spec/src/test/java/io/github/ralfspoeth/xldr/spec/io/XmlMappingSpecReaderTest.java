package io.github.ralfspoeth.xldr.spec.io;

import io.github.ralfspoeth.xldr.spec.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.github.ralfspoeth.xldr.spec.io.Streams.stream;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /**
     * A call carries its name, the type it returns, and one {@code <arg>} per
     * argument - each of which carries exactly what a {@code <fieldMapping>}
     * carries, so an argument may be a var, a nested {@code <lookup>} or another
     * {@code <fn>}.
     * <p>
     * The nested {@code today} is also the no-argument case: no {@code <arg>}
     * children reads as none.
     */
    @Test
    void parsesAcallWithItsArguments() {
        var source = """
                <mappingSpec>
                    <input mimeType="text/csv">
                        <var name="feed" constant="funds"/>
                        <var name="loadId">
                            <fn name="pkg_load.next_id" type="INTEGRAL">
                                <arg var="feed"/>
                                <arg>
                                    <lookup table="load_batch" column="id" keyColumn="feed"
                                            constant="funds"/>
                                </arg>
                                <arg>
                                    <fn name="today" type="DATE"/>
                                </arg>
                            </fn>
                        </var>
                    </input>
                </mappingSpec>
                """;
        var vars = List.copyOf(new XmlMappingSpecReader().read(stream(source)).inputSpec().vars());

        assertEquals(
                new VarSpec("loadId", new ValueSource.FunctionCall(
                        "pkg_load.next_id", DataType.INTEGRAL, List.of(
                                new ValueSource.Var("feed"),
                                new ValueSource.Lookup("load_batch", "id", "feed",
                                        new ValueSource.Constant("funds")),
                                new ValueSource.FunctionCall("today", DataType.DATE, List.of())))),
                vars.get(1));
    }

    /**
     * One source, and a call is one: a var carrying both an {@code <fn>} and a
     * {@code constant} attribute says two things, and the reader will not pick.
     */
    @Test
    void refusesAcallBesideAsourceAttribute() {
        var source = """
                <mappingSpec>
                    <input mimeType="text/csv">
                        <var name="loadId" constant="1">
                            <fn name="next_id" type="INTEGRAL"/>
                        </var>
                    </input>
                </mappingSpec>
                """;
        var thrown = assertThrows(IllegalArgumentException.class,
                () -> new XmlMappingSpecReader().read(stream(source)));
        assertTrue(thrown.getMessage().contains("one source is wanted"), thrown.getMessage());
    }

    /** and the two child elements are two sources, for the same reason */
    @Test
    void refusesAlookupAndAcallTogether() {
        var source = """
                <mappingSpec>
                    <input mimeType="text/csv">
                        <var name="loadId">
                            <lookup table="t" column="c" keyColumn="k" constant="x"/>
                            <fn name="next_id" type="INTEGRAL"/>
                        </var>
                    </input>
                </mappingSpec>
                """;
        var thrown = assertThrows(IllegalArgumentException.class,
                () -> new XmlMappingSpecReader().read(stream(source)));
        assertTrue(thrown.getMessage().contains("one source is wanted"), thrown.getMessage());
    }
}
