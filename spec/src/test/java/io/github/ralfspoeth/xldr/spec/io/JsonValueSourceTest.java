package io.github.ralfspoeth.xldr.spec.io;

import io.github.ralfspoeth.xldr.spec.*;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.github.ralfspoeth.xldr.spec.io.Streams.stream;
import static org.junit.jupiter.api.Assertions.*;

class JsonValueSourceTest {

    /**
     * A field mapping is one of fieldSelector / constant / var; a JSON constant
     * keeps its literal type (string, number as BigDecimal, boolean); and a
     * record mapping's limit is read.
     */
    @Test
    void readsSourcesAndLimit() throws IOException {
        var source = """
                {
                    "input": { "mimeType": "text/csv" },
                    "mapping": [
                        {
                            "recordSelector": "r",
                            "table": "t",
                            "limit": 100,
                            "fieldMapping": [
                                { "fieldSelector": "id", "column": "id" },
                                { "constant": "PD",      "column": "src" },
                                { "constant": 42,        "column": "n" },
                                { "constant": true,      "column": "flag" }
                            ]
                        }
                    ]
                }
                """;

        var spec = new JsonMappingSpecReader().read(stream(source));
        var mapping = List.copyOf(spec.recordMappingSpecs()).getFirst();

        assertEquals(100, mapping.limit());
        assertEquals(
                List.of(
                        new FieldMappingSpec("id", new ValueSource.Field("id")),
                        new FieldMappingSpec("src", new ValueSource.Constant("PD")),
                        new FieldMappingSpec("n", new ValueSource.Constant(new BigDecimal("42"))),
                        new FieldMappingSpec("flag", new ValueSource.Constant(Boolean.TRUE))
                ),
                mapping.fieldMappings());
    }

    /**
     * A lookup carries the reference table, the returned column, the key column,
     * and a key that is itself a basic source.
     */
    @Test
    void readsAlookup() throws IOException {
        var source = """
                {
                    "input": { "mimeType": "text/csv" },
                    "mapping": [
                        {
                            "recordSelector": "r",
                            "table": "t",
                            "fieldMapping": [
                                {
                                    "lookup": {
                                        "table": "country",
                                        "column": "id",
                                        "keyColumn": "iso",
                                        "fieldSelector": "c"
                                    },
                                    "column": "country_id"
                                }
                            ]
                        }
                    ]
                }
                """;
        var spec = new JsonMappingSpecReader().read(stream(source));
        var mapping = List.copyOf(spec.recordMappingSpecs()).getFirst();
        assertEquals(
                List.of(new FieldMappingSpec(
                        "country_id", new ValueSource.Lookup("country", "id", "iso", new ValueSource.Field("c"))
                )),
                mapping.fieldMappings());
    }

    /**
     * A lookup may match on several columns, written as a {@code conditions}
     * array so that the order is the document's rather than a map's.
     */
    @Test
    void readsAlookupOnSeveralColumns() throws IOException {
        var source = """
                {
                    "input": { "mimeType": "text/csv" },
                    "mapping": [
                        { "recordSelector": "r", "table": "t", "fieldMapping": [
                            { "column": "factor", "lookup": {
                                "table": "rate", "column": "factor", "conditions": [
                                    { "column": "ccy",  "fieldSelector": "currency" },
                                    { "column": "asof", "var": "day" } ] } } ] }
                    ]
                }
                """;
        var conditions = new LinkedHashMap<SqlIdentifier, ValueSource>();
        conditions.put(new SqlIdentifier("ccy"), new ValueSource.Field("currency"));
        conditions.put(new SqlIdentifier("asof"), new ValueSource.Var("day"));

        var mapping = List.copyOf(new JsonMappingSpecReader().read(stream(source)).recordMappingSpecs()).getFirst();
        assertEquals(
                List.of(new FieldMappingSpec("factor",
                        new ValueSource.Lookup("rate", "factor", conditions))),
                mapping.fieldMappings());
    }

    /**
     * The one-condition spelling and the many-condition one are two ways to say
     * the same thing, and a lookup writing both has said it twice.
     */
    @Test
    void refusesKeyColumnAndConditionsTogether() {
        var source = """
                { "input": { "mimeType": "text/csv" },
                  "mapping": [ { "recordSelector": "r", "table": "t", "fieldMapping": [
                      { "column": "x", "lookup": { "table": "r", "column": "id", "keyColumn": "a",
                          "fieldSelector": "f", "conditions": [ { "column": "b", "var": "v" } ] } } ] } ] }
                """;
        var thrown = assertThrows(IllegalArgumentException.class,
                () -> new JsonMappingSpecReader().read(stream(source)));
        assertTrue(thrown.getMessage().contains("one of the two is wanted"), thrown.getMessage());
    }

    /** and one that names a column twice has contradicted itself */
    @Test
    void refusesTwoConditionsOnOneColumn() {
        var source = """
                { "input": { "mimeType": "text/csv" },
                  "mapping": [ { "recordSelector": "r", "table": "t", "fieldMapping": [
                      { "column": "x", "lookup": { "table": "r", "column": "id", "conditions": [
                          { "column": "a", "var": "v" }, { "column": "a", "constant": 1 } ] } } ] } ] }
                """;
        var thrown = assertThrows(IllegalArgumentException.class,
                () -> new JsonMappingSpecReader().read(stream(source)));
        assertTrue(thrown.getMessage().contains("twice"), thrown.getMessage());
    }

    /**
     * And two spellings of one column are that same contradiction: an unquoted
     * identifier is case-insensitive, so {@code a} and {@code A} are one column.
     * <p>
     * The reader is where this is reported, and the only place it can be: keying
     * the conditions by {@link io.github.ralfspoeth.xldr.spec.SqlIdentifier}
     * means the map cannot hold both, so by the time a {@code Lookup} exists
     * there is nothing left to notice.
     */
    @Test
    void refusesTwoSpellingsOfOneColumn() {
        var source = """
                { "input": { "mimeType": "text/csv" },
                  "mapping": [ { "recordSelector": "r", "table": "t", "fieldMapping": [
                      { "column": "x", "lookup": { "table": "r", "column": "id", "conditions": [
                          { "column": "a", "var": "v" }, { "column": "A", "constant": 1 } ] } } ] } ] }
                """;
        var thrown = assertThrows(IllegalArgumentException.class,
                () -> new JsonMappingSpecReader().read(stream(source)));
        assertTrue(thrown.getMessage().contains("twice"), thrown.getMessage());
    }

    /**
     * A var's lookup may be keyed by a function call. The schema has said so
     * since 0.40 and the reader threw until 0.43, so an editor passed the spec
     * and the server then refused to load it.
     */
    @Test
    void readsAvarLookupKeyedByAcall() throws IOException {
        var source = """
                { "input": { "mimeType": "text/csv", "vars": [
                    { "name": "batch", "lookup": { "table": "load_batch", "column": "id",
                        "keyColumn": "feed", "fn": { "name": "current_feed", "type": "TEXT" } } } ] },
                  "mapping": [] }
                """;
        assertEquals(
                List.of(new VarSpec("batch", new ValueSource.Lookup("load_batch", "id", "feed",
                        new ValueSource.FunctionCall("current_feed", DataType.TEXT, List.of())))),
                List.copyOf(new JsonMappingSpecReader().read(stream(source)).inputSpec().vars()));
    }

    @Test
    void defaultsLimitToNull() throws IOException {
        var source = """
                {
                    "input": { "mimeType": "text/csv" },
                    "mapping": [
                        {
                            "recordSelector": "r",
                            "table": "t",
                            "fieldMapping": [ { "fieldSelector": "id", "column": "id" } ]
                        }
                    ]
                }
                """;
        var spec = new JsonMappingSpecReader().read(stream(source));
        assertNull(List.copyOf(spec.recordMappingSpecs()).getFirst().limit());
    }

    @Test
    void rejectsTwoSourcesInOneMapping() {
        var source = """
                {
                    "input": { "mimeType": "text/csv" },
                    "mapping": [
                        {
                            "recordSelector": "r",
                            "table": "t",
                            "fieldMapping": [
                                { "fieldSelector": "id", "constant": 1, "column": "id" }
                            ]
                        }
                    ]
                }
                """;
        assertThrows(IllegalArgumentException.class,
                () -> new JsonMappingSpecReader().read(stream(source)));
    }

    /**
     * An {@code expr} source is read as a template, both as a var and as a field
     * mapping.
     */
    @Test
    void readsAnExpression() throws IOException {
        var source = """
                {
                    "input": { "mimeType": "text/csv",
                        "vars": [ { "name": "gid", "expr": "${xldr.filename}-${nextval('b')}" } ] },
                    "mapping": [
                        { "recordSelector": "r", "table": "t",
                          "fieldMapping": [ { "expr": "${now()}", "column": "loaded_at" } ] }
                    ]
                }
                """;
        var spec = new JsonMappingSpecReader().read(stream(source));

        assertEquals(
                List.of(new VarSpec("gid", new ValueSource.Expr("${xldr.filename}-${nextval('b')}"))),
                List.copyOf(spec.inputSpec().vars()));

        var mapping = List.copyOf(spec.recordMappingSpecs()).getFirst();
        var fm = List.copyOf(mapping.fieldMappings()).getFirst();
        assertEquals(new ValueSource.Expr("${now()}"), fm.source());
    }

    /**
     * Members the reader does not consume are ignored at every level, so an
     * annotated spec parses to the same result as a bare one.
     * <p>
     * The fixture still carries a {@code load} block, which used to be the one
     * reserved name and is now an unrecognised member like any other - kept here
     * because a spec written against an older release may well contain one, and
     * it has to go on being ignored rather than becoming an error.
     */
    @Test
    void ignoresUnknownMembers() throws IOException {
        var bare = """
                {
                    "input": { "mimeType": "text/csv",
                        "recordSelectors": [ { "name": "r", "selector": "//r",
                            "fieldSelectors": [ { "name": "id", "selector": "@id" } ] } ] },
                    "mapping": [
                        { "recordSelector": "r", "table": "t",
                          "fieldMapping": [ { "fieldSelector": "id", "column": "id" } ] }
                    ]
                }
                """;
        var annotated = """
                {
                    "comments": "top-level note",
                    "load": { "commitPolicy": "ON_CLOSE" },
                    "input": { "mimeType": "text/csv", "note": "why this feed exists",
                        "recordSelectors": [ { "name": "r", "selector": "//r", "x": 1,
                            "fieldSelectors": [ { "name": "id", "selector": "@id", "unit": "n/a" } ] } ] },
                    "mapping": [
                        { "recordSelector": "r", "table": "t", "todo": "verify",
                          "fieldMapping": [ { "fieldSelector": "id", "column": "id", "comment": "pk" } ] }
                    ]
                }
                """;
        var reader = new JsonMappingSpecReader();
        assertEquals(
                reader.read(stream(bare)),
                reader.read(stream(annotated)));
    }

    /**
     * The settings of the adapter are grouped in {@code properties}, since which
     * of them mean anything depends on the MIME type. A scalar keeps its text,
     * so a number or a boolean may be written as one.
     */
    @Test
    void readsAdapterSettingsFromTheInput() throws IOException {
        var source = """
                {
                    "input": {
                        "mimeType": "text/csv",
                        "comments": "an annotation, not a setting",
                        "properties": {
                            "fieldSeparator": ";",
                            "header": false,
                            "linesPerRecord": 2,
                            "ns.f": "https://example.com/funds"
                        },
                        "recordSelectors": []
                    },
                    "mapping": []
                }
                """;
        var input = new JsonMappingSpecReader().read(stream(source)).inputSpec();

        assertEquals(
                Map.of("fieldSeparator", ";", "header", "false",
                        "linesPerRecord", "2", "ns.f", "https://example.com/funds"),
                input.properties());
        assertEquals("text/csv", input.mimeType());
    }


    /**
     * A JSON null is a constant like any other and loads a SQL NULL. It has to
     * count as a source, or a field mapping carrying only it would be rejected
     * for having none - which is how a missing member and a null one differ.
     */
    @Test
    void readsANullConstant() throws IOException {
        var source = """
                {
                    "input": { "mimeType": "text/csv" },
                    "mapping": [
                        {
                            "recordSelector": "r",
                            "table": "t",
                            "fieldMapping": [
                                { "constant": null, "column": "note" },
                                { "constant": "PD", "column": "src" }
                            ]
                        }
                    ]
                }
                """;
        var mapping = List.copyOf(
                new JsonMappingSpecReader().read(stream(source)).recordMappingSpecs()).getFirst();

        assertEquals(
                List.of(
                        new FieldMappingSpec("note", new ValueSource.Constant(null)),
                        new FieldMappingSpec("src", new ValueSource.Constant("PD"))),
                mapping.fieldMappings());
    }

    /**
     * A record selector may omit its selector: a CSV with a header holds one
     * kind of record, so there is nothing to locate. That reads as
     * {@link Locator.Every}, which the flat adapters honour and the ones that
     * have to be pointed at refuse by name.
     */
    @Test
    void readsARecordSelectorWithoutASelector() throws IOException {
        var source = """
                {
                    "input": {
                        "mimeType": "text/csv",
                        "recordSelectors": [
                            { "name": "people", "fieldSelectors": [ {"name": "id", "selector": "id"} ] }
                        ]
                    },
                    "mapping": []
                }
                """;
        var input = new JsonMappingSpecReader().read(stream(source)).inputSpec();
        var recordSelector = List.copyOf(input.recordSelectors()).getFirst();

        assertEquals(Locator.every(), recordSelector.locator());
    }

    /**
     * A mapping writes each column once.
     * <p>
     * The loader builds its insert from the field mappings in order, so two onto
     * one column produce {@code insert into t(name, name) values(?, ?)}, which
     * every database rejects - on the first record of the first file, with the
     * feed deployed. The mirror of the rule on field selector names, refused for
     * the same reason: a spec that cannot load should not be readable.
     */
    @Test
    void refusesTwoFieldMappingsOntoOneColumn() {
        var source = """
                {
                    "input": { "mimeType": "text/csv" },
                    "mapping": [
                        { "recordSelector": "r", "table": "person", "fieldMapping": [
                            { "fieldSelector": "id",   "column": "id" },
                            { "fieldSelector": "name", "column": "name" },
                            { "constant": "X",         "column": "name" }
                        ] }
                    ]
                }
                """;
        var thrown = assertThrows(IllegalArgumentException.class,
                () -> new JsonMappingSpecReader().read(stream(source)));
        assertAll(
                () -> assertTrue(thrown.getMessage().contains("name"), thrown.getMessage()),
                () -> assertTrue(thrown.getMessage().contains("person"), thrown.getMessage()),
                () -> assertTrue(thrown.getMessage().contains("more than once"), thrown.getMessage()));
    }

    /**
     * Compared as SQL sees them, not as strings. An unquoted identifier folds, so
     * {@code name} and {@code NAME} are one column and would build exactly the
     * insert above.
     */
    @Test
    void refusesTwoSpellingsOfOneUnquotedColumn() {
        var source = """
                {
                    "input": { "mimeType": "text/csv" },
                    "mapping": [
                        { "recordSelector": "r", "table": "person", "fieldMapping": [
                            { "fieldSelector": "a", "column": "name" },
                            { "fieldSelector": "b", "column": "NAME" }
                        ] }
                    ]
                }
                """;
        assertThrows(IllegalArgumentException.class,
                () -> new JsonMappingSpecReader().read(stream(source)));
    }

    /**
     * And a quoted name is left alone, because a quoted identifier is
     * case-sensitive by definition: a database holding both {@code name} and
     * {@code "Name"} has two columns, and a spec is entitled to write both.
     */
    @Test
    void allowsAquotedColumnBesideItsUnquotedNamesake() throws IOException {
        var source = """
                {
                    "input": { "mimeType": "text/csv" },
                    "mapping": [
                        { "recordSelector": "r", "table": "person", "fieldMapping": [
                            { "fieldSelector": "a", "column": "name" },
                            { "fieldSelector": "b", "column": "\\"name\\"" }
                        ] }
                    ]
                }
                """;
        var spec = new JsonMappingSpecReader().read(stream(source));
        assertEquals(2, List.copyOf(spec.recordMappingSpecs()).getFirst().fieldMappings().size());
    }

    /**
     * Two mappings into one table are not a repeat. Each is its own insert with
     * its own columns, which is how a spec fills different columns of one table
     * from different kinds of record.
     */
    @Test
    void allowsOneColumnInTwoDifferentMappings() throws IOException {
        var source = """
                {
                    "input": { "mimeType": "text/csv" },
                    "mapping": [
                        { "recordSelector": "a", "table": "person", "fieldMapping": [
                            { "fieldSelector": "id", "column": "id" } ] },
                        { "recordSelector": "b", "table": "person", "fieldMapping": [
                            { "fieldSelector": "id", "column": "id" } ] }
                    ]
                }
                """;
        assertEquals(2, new JsonMappingSpecReader().read(stream(source)).recordMappingSpecs().size());
    }

    /**
     * A call carries its name, the type it returns, and arguments that are value
     * sources in their own right - so an argument may be a var, a lookup, or
     * another call, and the reader builds the whole tree.
     * <p>
     * The nested {@code today} is also the no-argument case: {@code args} left
     * out reads as none rather than as a missing member.
     */
    @Test
    void readsAcallWithItsArguments() throws IOException {
        var source = """
                {
                    "input": { "mimeType": "text/csv", "vars": [
                        { "name": "feed", "constant": "funds" },
                        { "name": "loadId", "fn": {
                            "name": "pkg_load.next_id", "type": "INTEGRAL", "args": [
                                { "var": "feed" },
                                { "lookup": { "table": "load_batch", "column": "id",
                                              "keyColumn": "feed", "constant": "funds" } },
                                { "fn": { "name": "today", "type": "TEMPORAL" } }
                            ] } }
                    ] },
                    "mapping": []
                }
                """;
        var vars = List.copyOf(new JsonMappingSpecReader().read(stream(source)).inputSpec().vars());

        assertEquals(
                new VarSpec("loadId", new ValueSource.FunctionCall(
                        "pkg_load.next_id", DataType.INTEGRAL, List.of(
                                new ValueSource.Var("feed"),
                                new ValueSource.Lookup("load_batch", "id", "feed",
                                        new ValueSource.Constant("funds")),
                                new ValueSource.FunctionCall("today", DataType.TEMPORAL, List.of())))),
                vars.get(1));
    }

    /**
     * One source, and a call is one: a var saying both {@code fn} and
     * {@code constant} says two things, and the reader will not pick.
     */
    @Test
    void refusesAcallBesideAnotherSource() {
        var source = """
                {
                    "input": { "mimeType": "text/csv", "vars": [
                        { "name": "loadId", "constant": 1,
                          "fn": { "name": "next_id", "type": "INTEGRAL" } } ] },
                    "mapping": []
                }
                """;
        var thrown = assertThrows(IllegalArgumentException.class,
                () -> new JsonMappingSpecReader().read(stream(source)));
        assertTrue(thrown.getMessage().contains("one is wanted"), thrown.getMessage());
    }

    /** and the two object sources are two sources, for the same reason */
    @Test
    void refusesAlookupAndAcallTogether() {
        var source = """
                {
                    "input": { "mimeType": "text/csv", "vars": [
                        { "name": "loadId",
                          "lookup": { "table": "t", "column": "c", "keyColumn": "k", "constant": "x" },
                          "fn": { "name": "next_id", "type": "INTEGRAL" } } ] },
                    "mapping": []
                }
                """;
        var thrown = assertThrows(IllegalArgumentException.class,
                () -> new JsonMappingSpecReader().read(stream(source)));
        assertTrue(thrown.getMessage().contains("one is wanted"), thrown.getMessage());
    }

    /**
     * A {@code transform} is read as a procedure call per entry, in the order
     * written, each with arguments that are ordinary value sources.
     */
    @Test
    void readsTransforms() throws IOException {
        var source = """
                {
                    "input": { "mimeType": "text/csv", "vars": [
                        { "name": "batch", "constant": "b1" } ] },
                    "mapping": [],
                    "transform": [
                        { "name": "pkg_load.close_batch", "args": [
                            { "var": "batch" },
                            { "expr": "${xldr.rowsLoaded}" } ] },
                        { "name": "reconcile" }
                    ]
                }
                """;
        assertEquals(
                List.of(
                        new ProcedureCall("pkg_load.close_batch", List.of(
                                new ValueSource.Var("batch"),
                                new ValueSource.Expr("${xldr.rowsLoaded}"))),
                        new ProcedureCall("reconcile", List.of())),
                new JsonMappingSpecReader().read(stream(source)).transforms());
    }

    /** and a spec that says nothing about transforms has none, rather than null */
    @Test
    void readsNoTransformsAsNone() throws IOException {
        var source = """
                { "input": { "mimeType": "text/csv" }, "mapping": [] }
                """;
        assertEquals(List.of(), new JsonMappingSpecReader().read(stream(source)).transforms());
    }

    /**
     * A transform's argument is evaluated after the last record, so it may not
     * read a field - the rule {@link ProcedureCall} enforces, met here through
     * the reader because a spec file is where such a thing gets written.
     */
    @Test
    void refusesAtransformArgumentThatReadsAfield() {
        var source = """
                { "input": { "mimeType": "text/csv" }, "mapping": [],
                  "transform": [ { "name": "close", "args": [ { "fieldSelector": "id" } ] } ] }
                """;
        var thrown = assertThrows(IllegalArgumentException.class,
                () -> new JsonMappingSpecReader().read(stream(source)));
        assertTrue(thrown.getMessage().contains("id"), thrown.getMessage());
    }

    /**
     * A regex is a pattern and a group applied to another source, and the source
     * sits among them rather than under a member of its own.
     * <p>
     * Which is what lets it be any source at all: the object is handed to the
     * same method that reads a field mapping's, so {@code expr} here could as
     * well have been {@code fieldSelector}, {@code var}, or a nested {@code fn}.
     */
    @Test
    void readsAregexOverAnExpression() throws IOException {
        var source = """
                { "input": { "mimeType": "text/csv", "vars": [
                    { "name": "currency", "regex": {
                        "pattern": ".*_([A-Z]{3})_.*", "group": 1, "expr": "${xldr.filename}" } } ] },
                  "mapping": [] }
                """;
        assertEquals(
                List.of(new VarSpec("currency", ValueSource.Regex.matching(
                        new ValueSource.Expr("${xldr.filename}"), ".*_([A-Z]{3})_.*", 1))),
                List.copyOf(new JsonMappingSpecReader().read(stream(source)).inputSpec().vars()));
    }

    /**
     * A field mapping's regex reads a field, and a spec that says no
     * {@code group} means the whole match - the common case, a pattern written to
     * match exactly what is wanted.
     * <p>
     * The pattern is JSON text, so a backslash is escaped as JSON escapes it. The
     * fixture uses one deliberately: it is the character every pattern in earnest
     * is full of, and the one a format could quietly eat.
     */
    @Test
    void readsAregexOverAfieldAndDefaultsTheGroupToTheWholeMatch() throws IOException {
        var source = """
                { "input": { "mimeType": "text/csv" },
                  "mapping": [ { "recordSelector": "r", "table": "t", "fieldMapping": [
                      { "column": "year", "regex": {
                          "pattern": "\\\\d{4}", "fieldSelector": "booked" } } ] } ] }
                """;
        var mapping = List.copyOf(
                new JsonMappingSpecReader().read(stream(source)).recordMappingSpecs()).getFirst();

        assertEquals(
                List.of(new FieldMappingSpec("year", ValueSource.Regex.matching(
                        new ValueSource.Field("booked"), "\\d{4}", 0))),
                mapping.fieldMappings());
    }

    /**
     * A pattern that will not compile is refused when the spec is read.
     * <p>
     * This is the whole reason the pattern is compiled here rather than at the
     * first record: a feed whose spec is read is a feed that will run, and a
     * broken pattern that waits for a file to arrive has already been deployed by
     * the time anyone hears about it.
     */
    @Test
    void refusesApatternThatDoesNotCompile() {
        var source = """
                { "input": { "mimeType": "text/csv", "vars": [
                    { "name": "x", "regex": { "pattern": "([A-Z]", "expr": "${xldr.filename}" } } ] },
                  "mapping": [] }
                """;
        var thrown = assertThrows(IllegalArgumentException.class,
                () -> new JsonMappingSpecReader().read(stream(source)));
        assertTrue(thrown.getMessage().contains("does not compile"), thrown.getMessage());
    }

    /**
     * And a group the pattern does not capture is refused for the same reason: it
     * is a mistake the document alone proves, {@code group 2} of a pattern with
     * one pair of parentheses being nothing this or any input could supply.
     */
    @Test
    void refusesAgroupThePatternDoesNotCapture() {
        var source = """
                { "input": { "mimeType": "text/csv", "vars": [
                    { "name": "x", "regex": {
                        "pattern": "(a)b", "group": 2, "expr": "${xldr.filename}" } } ] },
                  "mapping": [] }
                """;
        var thrown = assertThrows(IllegalArgumentException.class,
                () -> new JsonMappingSpecReader().read(stream(source)));
        assertTrue(thrown.getMessage().contains("captures"), thrown.getMessage());
    }

    /** a regex without a pattern is not a regex */
    @Test
    void refusesAregexWithoutApattern() {
        var source = """
                { "input": { "mimeType": "text/csv", "vars": [
                    { "name": "x", "regex": { "group": 1, "expr": "${xldr.filename}" } } ] },
                  "mapping": [] }
                """;
        var thrown = assertThrows(IllegalArgumentException.class,
                () -> new JsonMappingSpecReader().read(stream(source)));
        assertTrue(thrown.getMessage().contains("pattern"), thrown.getMessage());
    }

    /** and one without a subject has nothing to match against */
    @Test
    void refusesAregexWithoutAsubject() {
        var source = """
                { "input": { "mimeType": "text/csv", "vars": [
                    { "name": "x", "regex": { "pattern": "(a)b", "group": 1 } } ] },
                  "mapping": [] }
                """;
        var thrown = assertThrows(IllegalArgumentException.class,
                () -> new JsonMappingSpecReader().read(stream(source)));
        assertTrue(thrown.getMessage().contains("exactly one of"), thrown.getMessage());
    }

    /**
     * A regex is a source like the other two objects, so it counts alongside them
     * when the reader asks how many have been written.
     */
    @Test
    void refusesAregexBesideAnotherSource() {
        var source = """
                { "input": { "mimeType": "text/csv", "vars": [
                    { "name": "x", "constant": "a",
                      "regex": { "pattern": "(a)b", "group": 1, "expr": "${xldr.filename}" } } ] },
                  "mapping": [] }
                """;
        var thrown = assertThrows(IllegalArgumentException.class,
                () -> new JsonMappingSpecReader().read(stream(source)));
        assertTrue(thrown.getMessage().contains("one is wanted"), thrown.getMessage());
    }

    /**
     * A lookup may match on part of a value, which is a regex in a condition -
     * allowed there for the same reason an {@code fn} is, and refused there for
     * nothing: what a condition may not hold is another lookup, that being a
     * join.
     */
    @Test
    void readsAregexAsAlookupCondition() throws IOException {
        var source = """
                { "input": { "mimeType": "text/csv" },
                  "mapping": [ { "recordSelector": "r", "table": "t", "fieldMapping": [
                      { "column": "factor", "lookup": {
                          "table": "rate", "column": "factor", "keyColumn": "ccy",
                          "regex": { "pattern": ".*_([A-Z]{3})_.*", "group": 1,
                                     "fieldSelector": "instrument" } } } ] } ] }
                """;
        var mapping = List.copyOf(
                new JsonMappingSpecReader().read(stream(source)).recordMappingSpecs()).getFirst();

        assertEquals(
                List.of(new FieldMappingSpec("factor", new ValueSource.Lookup("rate", "factor", "ccy",
                        ValueSource.Regex.matching(
                                new ValueSource.Field("instrument"), ".*_([A-Z]{3})_.*", 1)))),
                mapping.fieldMappings());
    }

    /**
     * A call inside a regex inside a column's lookup is still a call in a column,
     * which is one round trip a row.
     * <p>
     * The rule is {@link FieldMappingSpec}'s and it walks the whole tree, so the
     * new case did not need a new rule - but it did need the walk to know about
     * it, and a case that returns nothing rather than recursing is invisible.
     * That is what this fixture is for.
     */
    @Test
    void refusesAcallHiddenInAregexInAcolumnsLookup() {
        var source = """
                { "input": { "mimeType": "text/csv" },
                  "mapping": [ { "recordSelector": "r", "table": "t", "fieldMapping": [
                      { "column": "factor", "lookup": {
                          "table": "rate", "column": "factor", "keyColumn": "ccy",
                          "regex": { "pattern": "(.*)", "group": 1,
                                     "fn": { "name": "current_feed", "type": "TEXT" } } } } ] } ] }
                """;
        var thrown = assertThrows(IllegalArgumentException.class,
                () -> new JsonMappingSpecReader().read(stream(source)));
        assertTrue(thrown.getMessage().contains("current_feed"), thrown.getMessage());
    }

    /**
     * A selector and a discriminator together describe no input, and this is now
     * the only place that can say so.
     * <p>
     * It used to be refused by {@link RecordSelectorSpec}'s constructor, which
     * had two nullable fields and four states to police. A {@link Locator} has
     * three cases and both-at-once is not one of them, so the combination can no
     * longer be built in Java at all - it survives only as something a spec file
     * might say, and a reader is the only thing that reads spec files.
     */
    @Test
    void refusesASelectorAndADiscriminatorTogether() {
        var source = """
                {
                    "input": {
                        "mimeType": "text/csv",
                        "recordSelectors": [
                            {
                                "name": "orders",
                                "selector": "//order",
                                "discriminator": { "nth": 1, "equals": "O" },
                                "fieldSelectors": [ {"name": "id", "nth": 2} ]
                            }
                        ]
                    },
                    "mapping": []
                }
                """;
        var thrown = assertThrows(IllegalArgumentException.class,
                () -> new JsonMappingSpecReader().read(stream(source)));
        assertAll(
                () -> assertTrue(thrown.getMessage().contains("orders"), thrown.getMessage()),
                () -> assertTrue(thrown.getMessage().contains("no input is read both ways"),
                        thrown.getMessage()));
    }
}
