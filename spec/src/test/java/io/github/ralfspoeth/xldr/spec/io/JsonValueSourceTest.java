package io.github.ralfspoeth.xldr.spec.io;

import io.github.ralfspoeth.xldr.spec.*;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static io.github.ralfspoeth.xldr.spec.io.Streams.stream;
import static org.junit.jupiter.api.Assertions.*;

public class JsonValueSourceTest {

    /**
     * A field mapping is one of fieldSelector / constant / var; a JSON constant
     * keeps its literal type (string, number as BigDecimal, boolean); and a
     * record mapping's limit is read.
     */
    @Test
    public void readsSourcesAndLimit() throws IOException {
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
    public void readsAlookup() throws IOException {
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

    @Test
    public void defaultsLimitToNull() throws IOException {
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
    public void rejectsTwoSourcesInOneMapping() {
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
    public void readsAnExpression() throws IOException {
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
    public void ignoresUnknownMembers() throws IOException {
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
    public void readsAdapterSettingsFromTheInput() throws IOException {
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
    public void readsANullConstant() throws IOException {
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
    public void readsARecordSelectorWithoutASelector() throws IOException {
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
    public void refusesTwoFieldMappingsOntoOneColumn() {
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
    public void refusesTwoSpellingsOfOneUnquotedColumn() {
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
    public void allowsAquotedColumnBesideItsUnquotedNamesake() throws IOException {
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
    public void allowsOneColumnInTwoDifferentMappings() throws IOException {
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
    public void refusesASelectorAndADiscriminatorTogether() {
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
