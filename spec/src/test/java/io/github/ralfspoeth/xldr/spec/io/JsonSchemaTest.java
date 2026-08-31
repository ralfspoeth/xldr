package io.github.ralfspoeth.xldr.spec.io;

import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import io.github.ralfspoeth.xldr.spec.DataType;
import io.github.ralfspoeth.xldr.spec.Locator;
import io.github.ralfspoeth.xldr.spec.ProcedureCall;
import io.github.ralfspoeth.xldr.spec.ValueSource;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static io.github.ralfspoeth.xldr.spec.io.Streams.stream;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The published JSON schema has to keep describing what the reader accepts,
 * which is what {@link XsdTest} says about the XSD - and this half had no test
 * at all until now.
 * <p>
 * It is the half that needed one more. The JSON schema carries rules the XSD
 * cannot state, XSD 1.0 having no way to say "exactly one of these members":
 * that a field mapping has one value source, that a var's source is not a field
 * selector, and that a record selector is not both pointed at and filtered. So
 * the stricter of the two published files was the one nothing checked, and a
 * rule could have been wrong in it for as long as anyone liked.
 * <p>
 * The schema is read from the repository rather than the classpath, so that the
 * file an author downloads from GitHub Pages is the file tested here.
 */
class JsonSchemaTest {

    private static final Path SCHEMA = Path.of("..", "docs", "schema", "mapping-spec-0.50.json");

    /**
     * Every member the reader knows, in one document - the JSON transliteration
     * of {@link XsdTest}'s complete spec, so that a rule added to one format and
     * forgotten in the other shows up as a failure here.
     */
    private static final String COMPLETE_SPEC = """
            {
              "input": {
                "mimeType": "text/xml",
                "properties": { "ns.f": "https://example.com/funds", "dateFormat": "dd.MM.yyyy" },
                "vars": [
                  { "name": "source", "constant": "PD" },
                  { "name": "batch",
                    "lookup": { "table": "load_batch", "column": "id", "keyColumn": "feed",
                                "constant": "funds" } },
                  { "name": "loadId",
                    "fn": { "name": "pkg_load.next_id", "type": "INTEGRAL", "args": [
                              { "constant": "funds" },
                              { "var": "source" },
                              { "fn": { "name": "today", "type": "TEMPORAL" } }
                            ] } },
                  { "name": "currency",
                    "regex": { "pattern": ".*_([A-Z]{3})_.*", "group": 1,
                               "expr": "${xldr.filename}" } }
                ],
                "recordSelectors": [
                  { "name": "fund", "selector": "/root/fund",
                    "fieldSelectors": [
                      { "name": "id", "selector": "@id", "type": "text" },
                      { "name": "nav", "selector": "nav", "type": "decimal" },
                      { "name": "desc", "selector": "normalize-space(./text())" }
                    ] }
                ]
              },
              "mapping": [
                { "recordSelector": "fund", "table": "snmandat", "limit": 1000,
                  "fieldMapping": [
                    { "fieldSelector": "id", "column": "ident1_txt" },
                    { "constant": "X", "column": "status_cd" },
                    { "var": "source", "column": "source_cd" },
                    { "var": "loadId", "column": "load_id" },
                    { "expr": "${xldr.filename}", "column": "loaded_from" },
                    { "column": "country_id",
                      "lookup": { "table": "country", "column": "id", "keyColumn": "iso",
                                  "fieldSelector": "c" } },
                    { "column": "factor",
                      "lookup": { "table": "rate", "column": "factor", "conditions": [
                          { "column": "ccy",  "fieldSelector": "id" },
                          { "column": "asof", "var": "source" } ] } },
                    { "column": "booked_year",
                      "regex": { "pattern": "[0-9]{4}", "fieldSelector": "desc" } }
                  ] }
              ],
              "transform": [
                { "name": "pkg_load.close_batch", "args": [
                    { "var": "batch" },
                    { "expr": "${xldr.rowsLoaded}" } ] },
                { "name": "reconcile" }
              ]
            }
            """;

    /**
     * Loaded from the file's content rather than by location. Every {@code $ref}
     * in this schema is an internal fragment, so there is no relative reference
     * needing a base IRI to resolve against - which is the one reason the
     * library warns against loading a schema this way.
     */
    private static Schema schema() throws IOException {
        assertTrue(Files.isRegularFile(SCHEMA), "schema not found at " + SCHEMA.toAbsolutePath());
        return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(Files.readString(SCHEMA));
    }

    /** What the schema says is wrong with this document; empty where nothing is. */
    private static List<String> errors(String json) throws IOException {
        return schema().validate(json, InputFormat.JSON).stream().map(Object::toString).toList();
    }

    private static void assertValid(String json) throws IOException {
        assertEquals(List.of(), errors(json));
    }

    private static void assertRefused(String json, String because) throws IOException {
        assertFalse(errors(json).isEmpty(),
                () -> "the schema accepted a spec that " + because + ": " + json);
    }

    /**
     * What the schema accepts, the reader reads - so a spec an editor calls
     * valid is one the server can actually load.
     */
    @Test
    void theCompleteSpecIsValidAndReadable() throws IOException {
        assertValid(COMPLETE_SPEC);

        var spec = new JsonMappingSpecReader().read(stream(COMPLETE_SPEC));
        assertTrue(spec.inputSpec().properties().containsKey("ns.f"));
        assertTrue(spec.recordMappingSpecs().stream()
                .anyMatch(m -> m.fieldMappings().size() == 8));
        assertEquals(
                new ValueSource.FunctionCall("pkg_load.next_id", DataType.INTEGRAL, List.of(
                        new ValueSource.Constant("funds"),
                        new ValueSource.Var("source"),
                        new ValueSource.FunctionCall("today", DataType.TEMPORAL, List.of()))),
                spec.inputSpec().vars().stream()
                        .filter(v -> v.name().equals("loadId"))
                        .findFirst()
                        .orElseThrow()
                        .source(),
                "the nesting the schema allows is the nesting the reader builds");
        assertEquals(
                List.of(
                        new ProcedureCall("pkg_load.close_batch", List.of(
                                new ValueSource.Var("batch"),
                                new ValueSource.Expr("${xldr.rowsLoaded}"))),
                        new ProcedureCall("reconcile", List.of())),
                spec.transforms());
    }

    /**
     * A minimal spec - only what is required - validates too.
     */
    @Test
    void aMinimalSpecIsValid() throws IOException {
        assertValid("""
                { "input": { "mimeType": "text/csv" }, "mapping": [] }
                """);
    }

    // ---- the rules XSD 1.0 cannot state, which is why this file matters ------

    /**
     * A field mapping carries exactly one value source. The reader refuses two
     * and refuses none; so does this schema, and the XSD can do neither.
     */
    @Test
    void aFieldMappingHasOneSource() throws IOException {
        assertRefused("""
                { "input": { "mimeType": "text/csv" },
                  "mapping": [ { "recordSelector": "r", "table": "t", "fieldMapping": [
                      { "fieldSelector": "id", "constant": 1, "column": "id" } ] } ] }
                """, "gives a field mapping two sources");
        assertRefused("""
                { "input": { "mimeType": "text/csv" },
                  "mapping": [ { "recordSelector": "r", "table": "t", "fieldMapping": [
                      { "column": "id" } ] } ] }
                """, "gives a field mapping no source at all");
    }

    /**
     * A var is evaluated with no record in hand, so a field selector is not
     * among its sources.
     */
    @Test
    void aVarCannotReadAField() throws IOException {
        assertRefused("""
                { "input": { "mimeType": "text/csv",
                    "vars": [ { "name": "v", "fieldSelector": "id" } ] },
                  "mapping": [] }
                """, "lets a var read a field");
    }

    /**
     * A record selector is pointed at or filtered, never both. This is the rule
     * {@code Locator} made unconstructible in Java, which leaves a spec file as
     * the only place it can still be written - so the schema and the reader are
     * now the only two things that can catch it, and they should agree.
     */
    @Test
    void aRecordSelectorIsNotBothPointedAtAndFiltered() throws IOException {
        var both = """
                { "input": { "mimeType": "text/csv", "recordSelectors": [
                    { "name": "orders", "selector": "//order",
                      "discriminator": { "nth": 1, "equals": "O" },
                      "fieldSelectors": [ { "name": "id", "nth": 2 } ] } ] },
                  "mapping": [] }
                """;
        assertRefused(both, "is both pointed at and filtered");
        assertThrows(IllegalArgumentException.class,
                () -> new JsonMappingSpecReader().read(stream(both)),
                "the reader has to refuse what the schema refuses");
    }

    // ---- the rule 0.35 was published for -------------------------------------

    /**
     * A selector says where something is, so it may not be blank - the change
     * that 0.35 was published for. Blank used to mean two things: the XML and
     * Excel adapters refused it, the JSON one resolved it to the whole document.
     */
    @Test
    void aSelectorIsNeverBlank() throws IOException {
        for (var blank : List.of("\"\"", "\"   \"")) {
            var spec = """
                    { "input": { "mimeType": "text/csv", "recordSelectors": [
                        { "name": "r", "selector": %s,
                          "fieldSelectors": [ { "name": "id", "nth": 1 } ] } ] },
                      "mapping": [] }
                    """.formatted(blank);
            assertRefused(spec, "has a blank record selector");
            assertThrows(IllegalArgumentException.class,
                    () -> new JsonMappingSpecReader().read(stream(spec)),
                    "the reader has to refuse what the schema refuses");
        }
    }

    /**
     * And a field selector's, and a discriminator's, which are the other two
     * places a selector appears.
     */
    @Test
    void norIsAfieldSelectorOrAdiscriminatorSelector() throws IOException {
        assertRefused("""
                { "input": { "mimeType": "text/csv", "recordSelectors": [
                    { "name": "r", "fieldSelectors": [ { "name": "id", "selector": "" } ] } ] },
                  "mapping": [] }
                """, "has a blank field selector");
        assertRefused("""
                { "input": { "mimeType": "text/csv", "recordSelectors": [
                    { "name": "r", "discriminator": { "selector": "", "equals": "O" },
                      "fieldSelectors": [ { "name": "id", "nth": 1 } ] } ] },
                  "mapping": [] }
                """, "has a blank discriminator selector");
    }

    /**
     * Saying nothing is the way to mean every record, and stays valid - the
     * point of refusing blank being that there is one spelling rather than two.
     */
    @Test
    void sayingNothingIsHowYouMeanEveryRecord() throws IOException {
        var spec = """
                { "input": { "mimeType": "text/csv", "recordSelectors": [
                    { "name": "people", "fieldSelectors": [ { "name": "id", "selector": "id" } ] } ] },
                  "mapping": [] }
                """;
        assertValid(spec);

        var input = new JsonMappingSpecReader().read(stream(spec)).inputSpec();
        assertEquals(Locator.every(),
                List.copyOf(input.recordSelectors()).getFirst().locator());
    }

    /**
     * An unknown member is refused rather than ignored, the schemas being
     * stricter than the readers here on purpose: further down a spec an
     * unrecognised name is far more often a misspelling than a note, and
     * {@code fieldSelector} written for {@code fieldSelectors} costs a record
     * every one of its fields without anything saying so.
     */
    @Test
    void anUnknownMemberIsRefused() throws IOException {
        assertRefused("""
                { "input": { "mimeType": "text/csv", "recordSelectors": [
                    { "name": "r", "fieldSelector": [ { "name": "id", "nth": 1 } ] } ] },
                  "mapping": [] }
                """, "misspells fieldSelectors");
    }

    /**
     * And {@code comment} is the one annotation both schemas name, so that a
     * note is sayable where any other unknown member is not.
     */
    @Test
    void commentIsAllowedEverywhere() throws IOException {
        assertDoesNotThrow(() -> assertValid("""
                { "comment": "the nightly delivery",
                  "input": { "mimeType": "text/csv", "comment": "why this feed exists",
                    "recordSelectors": [
                      { "name": "r", "comment": "one kind of row",
                        "fieldSelectors": [ { "name": "id", "nth": 1, "comment": "the key" } ] } ] },
                  "mapping": [ { "recordSelector": "r", "table": "t", "comment": "lands here",
                      "fieldMapping": [ { "fieldSelector": "id", "column": "id" } ] } ] }
                """));
    }

    // ---- and the rules 0.40 was published for --------------------------------

    /**
     * A call belongs to a var and not to a column: it is made once per load, and
     * a column is bound once per record, so the same call in a column would be a
     * round trip per row. {@code fn} is a member of a var and of an argument,
     * and of nothing under {@code mapping}.
     */
    @Test
    void aColumnCannotCallAFunction() throws IOException {
        assertRefusedByBoth("""
                { "input": { "mimeType": "text/csv" },
                  "mapping": [ { "recordSelector": "r", "table": "t", "fieldMapping": [
                      { "column": "load_id", "fn": { "name": "next_id", "type": "INTEGRAL" } } ] } ] }
                """, "lets a column call a function");
    }

    /**
     * An argument is evaluated at the same moment as the var it feeds, which is
     * before the first record is read - so it may be anything a var may be, and
     * a field is not among them.
     */
    @Test
    void aCallArgumentCannotReadAField() throws IOException {
        assertRefusedByBoth("""
                { "input": { "mimeType": "text/csv", "vars": [
                    { "name": "loadId", "fn": { "name": "next_id", "type": "INTEGRAL",
                        "args": [ { "fieldSelector": "id" } ] } } ] },
                  "mapping": [] }
                """, "lets a call argument read a field");
    }

    /**
     * The same rule one level over: a lookup under a var is keyed with no record
     * in hand either. The schemas said this for the first time in 0.40 - until
     * then one {@code lookup} definition served both places, so a var keyed by a
     * field validated in an editor and threw at load.
     */
    @Test
    void aVarLookupCannotBeKeyedByAField() throws IOException {
        assertRefusedByBoth("""
                { "input": { "mimeType": "text/csv", "vars": [
                    { "name": "batch", "lookup": { "table": "load_batch", "column": "id",
                        "keyColumn": "feed", "fieldSelector": "f" } } ] },
                  "mapping": [] }
                """, "keys a var's lookup by a field");
    }

    /**
     * A call says the type it returns, where a field selector may leave its type
     * out: the loader registers the OUT parameter before the call and has
     * nothing to infer it from.
     */
    @Test
    void aCallSaysWhatItReturns() throws IOException {
        assertRefusedByBoth("""
                { "input": { "mimeType": "text/csv", "vars": [
                    { "name": "loadId", "fn": { "name": "next_id" } } ] },
                  "mapping": [] }
                """, "calls a function without saying what it returns");
    }

    /**
     * A function name is the one part of a value source that reaches the text of
     * a statement, so it is held to being a name - identifiers separated by dots
     * and nothing else. Everything else a spec contributes goes in as a bound
     * parameter.
     */
    @Test
    void aFunctionNameIsAName() throws IOException {
        assertRefusedByBoth("""
                { "input": { "mimeType": "text/csv", "vars": [
                    { "name": "loadId", "fn": { "name": "next_id(1); drop table t",
                        "type": "INTEGRAL" } } ] },
                  "mapping": [] }
                """, "calls something that is not a name");
    }

    // ---- and the rules 0.41 was published for --------------------------------

    /**
     * A transform's argument is evaluated after the last record, so it may no
     * more read a field than a var's source may. Its {@code args} therefore take
     * the same {@code varSource} an {@code fn}'s do, which is the definition
     * that already refuses a field selector.
     */
    @Test
    void aTransformArgumentCannotReadAField() throws IOException {
        assertRefusedByBoth("""
                { "input": { "mimeType": "text/csv" }, "mapping": [],
                  "transform": [ { "name": "close_batch", "args": [ { "fieldSelector": "id" } ] } ] }
                """, "lets a transform argument read a field");
    }

    /**
     * A transform has no type, where an {@code fn} requires one: nothing comes
     * back from a procedure. Writing one is the mistake of having meant an
     * {@code fn}, and it is refused rather than ignored - which is what
     * {@code additionalProperties} buys here.
     */
    @Test
    void aTransformSaysNoType() throws IOException {
        assertRefused("""
                { "input": { "mimeType": "text/csv" }, "mapping": [],
                  "transform": [ { "name": "close_batch", "type": "INTEGRAL" } ] }
                """, "gives a transform a return type");
    }

    /** and its name is a name, as a function's is */
    @Test
    void aTransformNameIsAName() throws IOException {
        assertRefusedByBoth("""
                { "input": { "mimeType": "text/csv" }, "mapping": [],
                  "transform": [ { "name": "close(); drop table t" } ] }
                """, "calls something that is not a name");
    }

    /**
     * A transform needs a name and nothing else: a procedure taking no arguments
     * is the ordinary case.
     */
    @Test
    void aTransformNeedsOnlyItsName() throws IOException {
        assertValid("""
                { "input": { "mimeType": "text/csv" }, "mapping": [],
                  "transform": [ { "name": "reconcile" } ] }
                """);
        assertRefused("""
                { "input": { "mimeType": "text/csv" }, "mapping": [],
                  "transform": [ { "args": [] } ] }
                """, "writes a transform with no name");
    }

    // ---- and the rules 0.43 was published for --------------------------------

    /**
     * A lookup matches on one column or on several, and says so one way: a
     * {@code keyColumn} beside its source, or a {@code conditions} array. Both
     * at once is the same thing said twice.
     */
    @Test
    void aLookupSaysWhatItMatchesOnOnce() throws IOException {
        assertRefusedByBoth("""
                { "input": { "mimeType": "text/csv" },
                  "mapping": [ { "recordSelector": "r", "table": "t", "fieldMapping": [
                      { "column": "x", "lookup": { "table": "rate", "column": "factor",
                          "keyColumn": "ccy", "fieldSelector": "c",
                          "conditions": [ { "column": "asof", "var": "d" } ] } } ] } ] }
                """, "gives a lookup a keyColumn and conditions");
        assertRefused("""
                { "input": { "mimeType": "text/csv" },
                  "mapping": [ { "recordSelector": "r", "table": "t", "fieldMapping": [
                      { "column": "x", "lookup": { "table": "rate", "column": "factor" } } ] } ] }
                """, "gives a lookup neither");
    }

    /**
     * An empty {@code conditions} is a lookup of a single-row view, and it has
     * to be written rather than implied: a spec that simply left the key out
     * would otherwise become one silently, which is a wrong value in every row
     * and no complaint anywhere.
     */
    @Test
    void aLookupMayMatchOnNothingIfItSaysSo() throws IOException {
        var explicit = """
                { "input": { "mimeType": "text/csv" },
                  "mapping": [ { "recordSelector": "r", "table": "t", "fieldMapping": [
                      { "column": "x", "lookup": { "table": "current_rate", "column": "factor",
                          "conditions": [] } } ] } ] }
                """;
        assertValid(explicit);
        assertDoesNotThrow(() -> new JsonMappingSpecReader().read(stream(explicit)));

        assertRefusedByBoth("""
                { "input": { "mimeType": "text/csv" },
                  "mapping": [ { "recordSelector": "r", "table": "t", "fieldMapping": [
                      { "column": "x", "lookup": { "table": "rate", "column": "factor" } } ] } ] }
                """, "leaves out both the keyColumn and the conditions");
    }

    /** and a condition names its column, and exactly one source for it */
    @Test
    void aConditionIsAColumnAndOneSource() throws IOException {
        assertRefused("""
                { "input": { "mimeType": "text/csv" },
                  "mapping": [ { "recordSelector": "r", "table": "t", "fieldMapping": [
                      { "column": "x", "lookup": { "table": "rate", "column": "factor",
                          "conditions": [ { "var": "v" } ] } } ] } ] }
                """, "writes a condition with no column");
        assertRefused("""
                { "input": { "mimeType": "text/csv" },
                  "mapping": [ { "recordSelector": "r", "table": "t", "fieldMapping": [
                      { "column": "x", "lookup": { "table": "rate", "column": "factor",
                          "conditions": [ { "column": "a", "var": "v", "constant": 1 } ] } } ] } ] }
                """, "writes a condition with two sources");
    }

    /**
     * A var's conditions are var sources: no field, at any depth, because a var
     * is evaluated before the first record. The two lookup definitions were
     * split for this in 0.40 and the split now covers conditions too.
     */
    @Test
    void aVarLookupConditionCannotReadAField() throws IOException {
        assertRefusedByBoth("""
                { "input": { "mimeType": "text/csv", "vars": [
                    { "name": "v", "lookup": { "table": "rate", "column": "factor", "conditions": [
                        { "column": "ccy", "fieldSelector": "c" } ] } } ] },
                  "mapping": [] }
                """, "lets a var's lookup condition read a field");
    }

    /**
     * And the rule the schema has stated since 0.40 while the reader refused it:
     * a var's lookup may be keyed by a function call. Fixed in 0.43 by teaching
     * the reader, so this now asserts agreement rather than the disagreement.
     */
    @Test
    void aVarLookupMayBeKeyedByAcall() throws IOException {
        var spec = """
                { "input": { "mimeType": "text/csv", "vars": [
                    { "name": "v", "lookup": { "table": "load_batch", "column": "id",
                        "keyColumn": "feed", "fn": { "name": "current_feed", "type": "TEXT" } } } ] },
                  "mapping": [] }
                """;
        assertValid(spec);
        assertDoesNotThrow(() -> new JsonMappingSpecReader().read(stream(spec)),
                "the schema has allowed this since 0.40; the reader threw until 0.43");
    }

    // ---- and the rules 0.46 was published for --------------------------------

    /**
     * A regex says the pattern it matches with, and the group it takes is a
     * capturing group, counted from zero.
     */
    @Test
    void aRegexSaysWhatItMatches() throws IOException {
        assertRefusedByBoth("""
                { "input": { "mimeType": "text/csv" },
                  "mapping": [ { "recordSelector": "r", "table": "t", "fieldMapping": [
                      { "column": "x", "regex": { "group": 1, "fieldSelector": "f" } } ] } ] }
                """, "writes a regex with no pattern");
        assertRefused("""
                { "input": { "mimeType": "text/csv" },
                  "mapping": [ { "recordSelector": "r", "table": "t", "fieldMapping": [
                      { "column": "x", "regex": { "pattern": "(a)", "group": -1,
                                                  "fieldSelector": "f" } } ] } ] }
                """, "asks for a negative group");
    }

    /** and it says what it matches against, exactly once */
    @Test
    void aRegexHasOneSubject() throws IOException {
        assertRefusedByBoth("""
                { "input": { "mimeType": "text/csv" },
                  "mapping": [ { "recordSelector": "r", "table": "t", "fieldMapping": [
                      { "column": "x", "regex": { "pattern": "(a)", "group": 1 } } ] } ] }
                """, "writes a regex with nothing to match against");
        assertRefusedByBoth("""
                { "input": { "mimeType": "text/csv" },
                  "mapping": [ { "recordSelector": "r", "table": "t", "fieldMapping": [
                      { "column": "x", "regex": { "pattern": "(a)", "group": 1,
                          "fieldSelector": "f", "var": "v" } } ] } ] }
                """, "gives a regex two subjects");
    }

    /**
     * A var's regex reads no field, at any depth - the rule that split every
     * source into a row flavour and a var one, applied to the sixth.
     */
    @Test
    void aVarRegexCannotReadAField() throws IOException {
        assertRefusedByBoth("""
                { "input": { "mimeType": "text/csv", "vars": [
                    { "name": "v", "regex": { "pattern": "(a)", "group": 1,
                                              "fieldSelector": "f" } } ] },
                  "mapping": [] }
                """, "lets a var's regex read a field");
    }

    /**
     * And a column's regex reads no lookup, which is the mirror of it.
     * <p>
     * A regex runs on this side of the database, on a value bound as a
     * parameter, and a column's lookup is a subquery of the insert - so there is
     * nothing to match against until the statement runs. In a var the same
     * spelling is fine, a var being evaluated one value at a time, and that is
     * why the two flavours differ here in the direction they do.
     */
    @Test
    void aColumnsRegexCannotReadAlookup() throws IOException {
        assertRefusedByBoth("""
                { "input": { "mimeType": "text/csv" },
                  "mapping": [ { "recordSelector": "r", "table": "t", "fieldMapping": [
                      { "column": "x", "regex": { "pattern": "(a)", "group": 1,
                          "lookup": { "table": "rate", "column": "factor",
                                      "keyColumn": "ccy", "fieldSelector": "c" } } } ] } ] }
                """, "matches a column's pattern against a lookup");

        var inAvar = """
                { "input": { "mimeType": "text/csv", "vars": [
                    { "name": "v", "regex": { "pattern": "(a)", "group": 1,
                        "lookup": { "table": "rate", "column": "factor",
                                    "keyColumn": "ccy", "constant": "EUR" } } } ] },
                  "mapping": [] }
                """;
        assertValid(inAvar);
        assertDoesNotThrow(() -> new JsonMappingSpecReader().read(stream(inAvar)),
                "the same thing in a var is one query and then a match, and is allowed");
    }

    /**
     * A lookup may be keyed by a regex, and a condition may match against one -
     * a regex being a source like any other wherever a source stands.
     */
    @Test
    void aLookupMayBeKeyedByAregex() throws IOException {
        assertValid("""
                { "input": { "mimeType": "text/csv" },
                  "mapping": [ { "recordSelector": "r", "table": "t", "fieldMapping": [
                      { "column": "x", "lookup": { "table": "rate", "column": "factor",
                          "keyColumn": "ccy",
                          "regex": { "pattern": ".*_([A-Z]{3})_.*", "group": 1,
                                     "fieldSelector": "instrument" } } },
                      { "column": "y", "lookup": { "table": "rate", "column": "factor",
                          "conditions": [
                              { "column": "ccy", "regex": { "pattern": "(...)", "group": 1,
                                                            "fieldSelector": "instrument" } } ] } } ] } ] }
                """);
    }

    /**
     * A transform's argument may be a regex, which is where {@code
     * ${xldr.rowsLoaded}} meets one.
     */
    @Test
    void aTransformArgumentMayBeAregex() throws IOException {
        assertValid("""
                { "input": { "mimeType": "text/csv" }, "mapping": [],
                  "transform": [ { "name": "close_batch", "args": [
                      { "regex": { "pattern": "([0-9]+)", "group": 1,
                                   "expr": "${xldr.rowsLoaded}" } } ] } ] }
                """);
    }

    // ---- and the rule 0.50 was published for ----------------------------------

    /**
     * A table or a column is written into the statement rather than bound to it,
     * so it is held to being a name - and the schema says so, which is the whole
     * point of publishing one.
     * <p>
     * Until 0.50 the only rule was non-blank, in the reader and in both schemas,
     * while {@code CallableName} held a routine name to a shape on the grounds
     * that it was the only part of a value source reaching the statement text.
     * That was never true of a table.
     */
    @Test
    void aTableAndAColumnAreHeldToBeingNames() throws IOException {
        assertRefusedByBoth("""
                { "input": { "mimeType": "text/csv" },
                  "mapping": [ { "recordSelector": "r", "table": "t where 1=1 --",
                      "fieldMapping": [ { "fieldSelector": "a", "column": "b" } ] } ] }
                """, "puts a fragment of SQL where a table name goes");
        assertRefusedByBoth("""
                { "input": { "mimeType": "text/csv" },
                  "mapping": [ { "recordSelector": "r", "table": "t",
                      "fieldMapping": [ { "fieldSelector": "a", "column": "count(*)" } ] } ] }
                """, "puts an expression where a column name goes");
        assertRefusedByBoth("""
                { "input": { "mimeType": "text/csv" },
                  "mapping": [ { "recordSelector": "r", "table": "t", "fieldMapping": [
                      { "column": "x", "lookup": { "table": "rate", "column": "factor",
                          "keyColumn": "a b", "var": "v" } } ] } ] }
                """, "puts a space in a lookup's key column");
    }

    /**
     * The set is the union of what the targets accept unquoted rather than the
     * intersection, so a spec can name a column that exists: {@code $} and
     * {@code #} are Oracle's, and a letter is any letter because PostgreSQL takes
     * them unquoted.
     */
    @Test
    void aNameMayBeAnythingTheTargetsCallAName() throws IOException {
        assertValid("""
                { "input": { "mimeType": "text/csv" },
                  "mapping": [ { "recordSelector": "r", "table": "EMP$", "fieldMapping": [
                      { "fieldSelector": "a", "column": "x#y" },
                      { "fieldSelector": "b", "column": "größe" },
                      { "fieldSelector": "c", "column": "_private" } ] } ] }
                """);
    }

    /**
     * And anything else is written in quotes, with an interior quote doubled -
     * which is how SQL escapes one, and the form {@code unquoted()} undoes to
     * match what {@code DatabaseMetaData} reports.
     */
    @Test
    void aQuotedNameCarriesWhatAPlainOneCannot() throws IOException {
        assertValid("""
                { "input": { "mimeType": "text/csv" },
                  "mapping": [ { "recordSelector": "r", "table": "t", "fieldMapping": [
                      { "fieldSelector": "a", "column": "\\"order id\\"" },
                      { "fieldSelector": "b", "column": "\\"a\\"\\"b\\"" } ] } ] }
                """);
        assertRefusedByBoth("""
                { "input": { "mimeType": "text/csv" },
                  "mapping": [ { "recordSelector": "r", "table": "t", "fieldMapping": [
                      { "fieldSelector": "a", "column": "\\"unclosed" } ] } ] }
                """, "leaves a quoted name open");
    }

    /**
     * A qualified name is refused rather than folded through: it used to work by
     * accident, which made the table name and {@code target.properties} two ways
     * to say where a table lives.
     */
    @Test
    void aQualifiedTableNameIsRefused() throws IOException {
        assertRefusedByBoth("""
                { "input": { "mimeType": "text/csv" },
                  "mapping": [ { "recordSelector": "r", "table": "reporting.orders",
                      "fieldMapping": [ { "fieldSelector": "a", "column": "b" } ] } ] }
                """, "qualifies a table name");
    }

    /**
     * Both, because neither catches the other's cases: an editor never runs the
     * reader, and a spec the server loads was never put through the schema.
     */
    private static void assertRefusedByBoth(String json, String because) throws IOException {
        assertRefused(json, because);
        assertThrows(IllegalArgumentException.class,
                () -> new JsonMappingSpecReader().read(stream(json)),
                "the reader has to refuse what the schema refuses");
    }
}
