package io.github.ralfspoeth.xldr.spec.io;

import io.github.ralfspoeth.xldr.spec.*;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.github.ralfspoeth.xldr.spec.io.Streams.stream;
import static org.junit.jupiter.api.Assertions.*;

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
     * A lookup may match on several columns, written as {@code <condition>}
     * children so that the order is the document's.
     */
    @Test
    void parsesAlookupOnSeveralColumns() {
        var source = """
                <mappingSpec>
                    <input mimeType="text/csv"/>
                    <mapping recordSelector="r" table="t">
                        <fieldMapping column="factor">
                            <lookup table="rate" column="factor">
                                <conditions>
                                    <condition column="ccy" fieldSelector="currency"/>
                                    <condition column="asof" var="day"/>
                                </conditions>
                            </lookup>
                        </fieldMapping>
                    </mapping>
                </mappingSpec>
                """;
        var conditions = new LinkedHashMap<SqlIdentifier, ValueSource>();
        conditions.put(new SqlIdentifier("ccy"), new ValueSource.Field("currency"));
        conditions.put(new SqlIdentifier("asof"), new ValueSource.Var("day"));

        var mapping = List.copyOf(
                new XmlMappingSpecReader().read(stream(source)).recordMappingSpecs()).getFirst();
        assertEquals(
                List.of(new FieldMappingSpec("factor",
                        new ValueSource.Lookup("rate", "factor", conditions))),
                mapping.fieldMappings());
    }

    /** the two spellings say the same thing, so a lookup writes one of them */
    @Test
    void refusesKeyColumnAndConditionsTogether() {
        var source = """
                <mappingSpec>
                    <input mimeType="text/csv"/>
                    <mapping recordSelector="r" table="t">
                        <fieldMapping column="x">
                            <lookup table="r" column="id" keyColumn="a" fieldSelector="f">
                                <conditions><condition column="b" var="v"/></conditions>
                            </lookup>
                        </fieldMapping>
                    </mapping>
                </mappingSpec>
                """;
        var thrown = assertThrows(IllegalArgumentException.class,
                () -> new XmlMappingSpecReader().read(stream(source)));
        assertTrue(thrown.getMessage().contains("one of the two is wanted"), thrown.getMessage());
    }

    /**
     * A var's lookup may be keyed by a call. The XSD has said so since 0.40 and
     * the reader threw until 0.43.
     */
    @Test
    void parsesAvarLookupKeyedByAcall() {
        var source = """
                <mappingSpec>
                    <input mimeType="text/csv">
                        <var name="batch">
                            <lookup table="load_batch" column="id" keyColumn="feed">
                                <fn name="current_feed" type="TEXT"/>
                            </lookup>
                        </var>
                    </input>
                </mappingSpec>
                """;
        assertEquals(
                List.of(new VarSpec("batch", new ValueSource.Lookup("load_batch", "id", "feed",
                        new ValueSource.FunctionCall("current_feed", DataType.TEXT, List.of())))),
                List.copyOf(new XmlMappingSpecReader().read(stream(source)).inputSpec().vars()));
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

    /**
     * A {@code <transform>} is read as a procedure call, one {@code <arg>} per
     * argument - the same {@code <arg>} an {@code <fn>} takes, so an argument may
     * be anything a var may be.
     */
    @Test
    void parsesTransforms() {
        var source = """
                <mappingSpec>
                    <input mimeType="text/csv">
                        <var name="batch" constant="b1"/>
                    </input>
                    <transform name="pkg_load.close_batch">
                        <arg var="batch"/>
                        <arg expr="${xldr.rowsLoaded}"/>
                    </transform>
                    <transform name="reconcile"/>
                </mappingSpec>
                """;
        assertEquals(
                List.of(
                        new ProcedureCall("pkg_load.close_batch", List.of(
                                new ValueSource.Var("batch"),
                                new ValueSource.Expr("${xldr.rowsLoaded}"))),
                        new ProcedureCall("reconcile", List.of())),
                new XmlMappingSpecReader().read(stream(source)).transforms());
    }

    /**
     * The reader takes a {@code <transform>} wherever it stands, though the
     * schema requires it after the mappings - which is the order that says what
     * it means. Stricter in the editor than in the reader, and in the safe
     * direction: nothing the schema accepts is refused here.
     */
    @Test
    void parsesAtransformWrittenBeforeTheMappings() {
        var source = """
                <mappingSpec>
                    <input mimeType="text/csv"/>
                    <transform name="reconcile"/>
                    <mapping recordSelector="r" table="t">
                        <fieldMapping fieldSelector="id" column="id"/>
                    </mapping>
                </mappingSpec>
                """;
        var spec = new XmlMappingSpecReader().read(stream(source));
        assertEquals(List.of(new ProcedureCall("reconcile", List.of())), spec.transforms());
        assertEquals(1, spec.recordMappingSpecs().size());
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

    /**
     * A {@code <regex>} carries its pattern and group as attributes and its
     * subject as whatever a source is written as - here another attribute, and
     * below a child element.
     * <p>
     * The attribute is {@code pattern} where a {@code <discriminator>} says
     * {@code matches}: a discriminator carries its pattern beside the
     * {@code equals} it is an alternative to, so the attribute has to say which
     * test is meant, and here the element already says it.
     */
    @Test
    void parsesAregexOverAnExpression() {
        var source = """
                <mappingSpec>
                    <input mimeType="text/csv">
                        <var name="currency">
                            <regex pattern=".*_([A-Z]{3})_.*" group="1" expr="${xldr.filename}"/>
                        </var>
                    </input>
                </mappingSpec>
                """;
        assertEquals(
                List.of(new VarSpec("currency", ValueSource.Regex.matching(
                        new ValueSource.Expr("${xldr.filename}"), ".*_([A-Z]{3})_.*", 1))),
                List.copyOf(new XmlMappingSpecReader().read(stream(source)).inputSpec().vars()));
    }

    /**
     * A field mapping's regex reads a field, and no {@code group} attribute means
     * the whole match - the common case, a pattern written to match exactly what
     * is wanted.
     */
    @Test
    void parsesAregexOverAfieldAndDefaultsTheGroupToTheWholeMatch() {
        var source = """
                <mappingSpec>
                    <input mimeType="text/csv"/>
                    <mapping recordSelector="r" table="t">
                        <fieldMapping column="year">
                            <regex pattern="\\d{4}" fieldSelector="booked"/>
                        </fieldMapping>
                    </mapping>
                </mappingSpec>
                """;
        var mapping = List.copyOf(
                new XmlMappingSpecReader().read(stream(source)).recordMappingSpecs()).getFirst();

        assertEquals(
                List.of(new FieldMappingSpec("year", ValueSource.Regex.matching(
                        new ValueSource.Field("booked"), "\\d{4}", 0))),
                mapping.fieldMappings());
    }

    /**
     * The subject may be a child element rather than an attribute, which is how a
     * regex reads what a call returned: the whole {@code <regex>} is handed to the
     * method that reads a {@code <fieldMapping>}, so it takes everything that
     * takes.
     */
    @Test
    void parsesAregexOverAcall() {
        var source = """
                <mappingSpec>
                    <input mimeType="text/csv">
                        <var name="batchDay">
                            <regex pattern="(\\d{4})-\\d\\d-\\d\\d" group="1">
                                <fn name="current_batch" type="TEXT"/>
                            </regex>
                        </var>
                    </input>
                </mappingSpec>
                """;
        assertEquals(
                List.of(new VarSpec("batchDay", ValueSource.Regex.matching(
                        new ValueSource.FunctionCall("current_batch", DataType.TEXT, List.of()),
                        "(\\d{4})-\\d\\d-\\d\\d", 1))),
                List.copyOf(new XmlMappingSpecReader().read(stream(source)).inputSpec().vars()));
    }

    /**
     * A pattern that will not compile is refused when the spec is read.
     * <p>
     * This is the whole reason the pattern is compiled there rather than at the
     * first record: a feed whose spec is read is a feed that will run, and a
     * broken pattern that waits for a file to arrive has already been deployed by
     * the time anyone hears about it.
     */
    @Test
    void refusesApatternThatDoesNotCompile() {
        var source = """
                <mappingSpec>
                    <input mimeType="text/csv">
                        <var name="x"><regex pattern="([A-Z]" expr="${xldr.filename}"/></var>
                    </input>
                </mappingSpec>
                """;
        var thrown = assertThrows(IllegalArgumentException.class,
                () -> new XmlMappingSpecReader().read(stream(source)));
        assertTrue(thrown.getMessage().contains("does not compile"), thrown.getMessage());
    }

    /** a regex without a pattern is not a regex */
    @Test
    void refusesAregexWithoutApattern() {
        var source = """
                <mappingSpec>
                    <input mimeType="text/csv">
                        <var name="x"><regex group="1" expr="${xldr.filename}"/></var>
                    </input>
                </mappingSpec>
                """;
        var thrown = assertThrows(IllegalArgumentException.class,
                () -> new XmlMappingSpecReader().read(stream(source)));
        assertTrue(thrown.getMessage().contains("pattern"), thrown.getMessage());
    }

    /**
     * A regex is a source like the other two children, so it counts alongside
     * them when the reader asks how many have been written.
     */
    @Test
    void refusesAregexBesideAsourceAttribute() {
        var source = """
                <mappingSpec>
                    <input mimeType="text/csv">
                        <var name="x" constant="a">
                            <regex pattern="(a)b" group="1" expr="${xldr.filename}"/>
                        </var>
                    </input>
                </mappingSpec>
                """;
        var thrown = assertThrows(IllegalArgumentException.class,
                () -> new XmlMappingSpecReader().read(stream(source)));
        assertTrue(thrown.getMessage().contains("one source is wanted"), thrown.getMessage());
    }

    /**
     * A lookup may match on part of a value, which is a regex in a condition -
     * allowed there for the same reason an {@code <fn>} is. What a condition may
     * not hold is another {@code <lookup>}, that being a join.
     */
    @Test
    void parsesAregexAsAlookupCondition() {
        var source = """
                <mappingSpec>
                    <input mimeType="text/csv"/>
                    <mapping recordSelector="r" table="t">
                        <fieldMapping column="factor">
                            <lookup table="rate" column="factor" keyColumn="ccy">
                                <regex pattern=".*_([A-Z]{3})_.*" group="1" fieldSelector="instrument"/>
                            </lookup>
                        </fieldMapping>
                    </mapping>
                </mappingSpec>
                """;
        var mapping = List.copyOf(
                new XmlMappingSpecReader().read(stream(source)).recordMappingSpecs()).getFirst();

        assertEquals(
                List.of(new FieldMappingSpec("factor", new ValueSource.Lookup("rate", "factor", "ccy",
                        ValueSource.Regex.matching(
                                new ValueSource.Field("instrument"), ".*_([A-Z]{3})_.*", 1)))),
                mapping.fieldMappings());
    }
}
