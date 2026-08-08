package io.github.ralfspoeth.xldr.app.test;

import io.github.ralfspoeth.xldr.app.App;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The {@code validate} command through the command line, with the adapters on
 * the module path - which is the point of it: a spec is checked against the
 * adapter that will read it, without a database and without a server.
 * <p>
 * A {@code Test} rather than an {@code IT} despite needing the adapters. What
 * separates the two here is cost and environment - a database, background
 * threads, timeouts measured in tens of seconds - and this has none of them: it
 * writes a file and calls a method. The adapters reach the module path because
 * they are {@code provided} dependencies of {@code app} and it declares
 * {@code uses InputAdapterFactory}, which is as true under surefire as under
 * failsafe.
 */
public class ValidateTest {

    private Path dir;

    @BeforeEach
    void setUp() throws IOException {
        dir = Files.createTempDirectory("xldr-validate");
    }

    private int validate(String name, String spec) throws IOException {
        var file = dir.resolve(name);
        Files.writeString(file, spec);
        return new CommandLine(new App()).execute("validate", file.toString());
    }

    /**
     * A spec the server would load reports nothing.
     */
    @Test
    void acceptsAgoodSpec() throws IOException {
        assertEquals(0, validate("spec.json", """
                {
                  "input": {
                    "mimeType": "text/csv",
                    "accepts": "glob:*.csv",
                    "properties": { "fieldSeparator": "," },
                    "vars": [ { "name": "source", "constant": "PD" } ],
                    "recordSelectors": [
                      { "name": "people", "fieldSelectors": [
                          { "name": "id", "selector": "id", "type": "INTEGER" }
                      ] }
                    ]
                  },
                  "mapping": [
                    { "recordSelector": "people", "table": "person", "fieldMapping": [
                        { "fieldSelector": "id", "column": "id" },
                        { "var": "source", "column": "source_cd" }
                    ] }
                  ]
                }
                """));
    }

    /**
     * The delivery rule is the check that decides whether a feed activates at
     * all - the failure that is otherwise only visible in the server's log.
     */
    @Test
    void rejectsAmissingOrDoubledDeliveryRule() throws IOException {
        assertEquals(1, validate("neither.json", """
                { "input": { "mimeType": "text/csv", "recordSelectors": [] } }
                """));
        assertEquals(1, validate("both.json", """
                { "input": { "mimeType": "text/csv", "accepts": "glob:*.csv",
                             "sentinel": "glob:*.done", "recordSelectors": [] } }
                """));
    }

    /**
     * A name a mapping uses but the input does not declare would otherwise load
     * nothing, or fail half way through a load.
     */
    @Test
    void rejectsNamesTheInputDoesNotDeclare() throws IOException {
        assertEquals(1, validate("record.json", """
                { "input": { "mimeType": "text/csv", "accepts": "glob:*.csv",
                    "recordSelectors": [ { "name": "people" } ] },
                  "mapping": [ { "recordSelector": "persons", "table": "t",
                                 "fieldMapping": [ { "constant": "x", "column": "c" } ] } ] }
                """));
        assertEquals(1, validate("field.json", """
                { "input": { "mimeType": "text/csv", "accepts": "glob:*.csv",
                    "recordSelectors": [ { "name": "people",
                        "fieldSelectors": [ { "name": "id", "selector": "id" } ] } ] },
                  "mapping": [ { "recordSelector": "people", "table": "t",
                                 "fieldMapping": [ { "fieldSelector": "nope", "column": "c" } ] } ] }
                """));
        assertEquals(1, validate("var.json", """
                { "input": { "mimeType": "text/csv", "accepts": "glob:*.csv",
                    "recordSelectors": [ { "name": "people" } ] },
                  "mapping": [ { "recordSelector": "people", "table": "t",
                                 "fieldMapping": [ { "var": "nope", "column": "c" } ] } ] }
                """));
    }

    /**
     * The quietest mistake a spec can make: a CSV selector is a first-column
     * discriminator, so giving one to a feed that has a header loads no row at
     * all and still reports success. Saying so with a header is worth reporting;
     * saying so without one is the interleaved-file case and is fine.
     */
    @Test
    void rejectsAcsvDiscriminatorBesideAheader() throws IOException {
        assertEquals(1, validate("discriminator.json", """
                { "input": { "mimeType": "text/csv", "accepts": "glob:*.csv",
                    "recordSelectors": [ { "name": "people", "selector": "people",
                        "fieldSelectors": [ { "name": "id", "selector": "id" } ] } ] },
                  "mapping": [ { "recordSelector": "people", "table": "t",
                                 "fieldMapping": [ { "fieldSelector": "id", "column": "c" } ] } ] }
                """));
        assertEquals(0, validate("headerless.json", """
                { "input": { "mimeType": "text/csv", "accepts": "glob:*.csv",
                    "properties": { "header": false },
                    "recordSelectors": [ { "name": "people", "selector": "people",
                        "fieldSelectors": [ { "name": "id", "selector": "2" } ] } ] },
                  "mapping": [ { "recordSelector": "people", "table": "t",
                                 "fieldMapping": [ { "fieldSelector": "id", "column": "c" } ] } ] }
                """));
    }

    /**
     * The adapter itself has the last word on a selector: an XPath that does not
     * compile is reported here rather than when the first file arrives.
     */
    @Test
    void rejectsAselectorTheAdapterCannotUse() throws IOException {
        assertEquals(1, validate("xpath.xml", """
                <mappingSpec>
                    <input mimeType="text/xml" accepts="glob:*.xml">
                        <recordSelector name="r" selector="//[[">
                            <fieldSelector name="id" selector="@id"/>
                        </recordSelector>
                    </input>
                </mappingSpec>
                """));
    }

    /**
     * No adapter for the MIME type means the feed can never load, however good
     * the rest of the spec is.
     */
    @Test
    void rejectsAnUnknownMimeType() throws IOException {
        assertEquals(1, validate("mime.json", """
                { "input": { "mimeType": "application/x-nonesuch", "accepts": "glob:*.dat",
                             "recordSelectors": [] } }
                """));
    }

    @Test
    void rejectsAspecThatDoesNotParse() throws IOException {
        assertEquals(1, validate("broken.json", "{ \"input\": { "));
    }

    /**
     * A feed whose header names its fields is entitled to name one the record
     * selector does not declare, so the check that would otherwise report it is
     * off - for that feed only, which is the point of saying so in the spec.
     */
    @Test
    void acceptsAnUndeclaredFieldWhereTheHeaderSuppliesIt() throws IOException {
        var spec = """
                { "input": { "mimeType": "text/csv", "accepts": "glob:*.csv",
                    "properties": { "fieldSeparator": ";"%s },
                    "recordSelectors": [ { "name": "all" } ] },
                  "mapping": [ { "recordSelector": "all", "table": "t",
                                 "fieldMapping": [ { "fieldSelector": "Name", "column": "c" } ] } ] }
                """;
        assertEquals(0, validate("from-header.json", spec.formatted(", \"fieldsFromHeader\": true")));
        assertEquals(1, validate("declared.json", spec.formatted("")));
    }

    /**
     * A record selector spelled {@code fieldSelector} declares no field
     * selectors at all - the reader ignores what it does not know - so every
     * mapped column loads null while the load reports success. The report has
     * to say that the record selector is empty rather than that no record is in
     * scope, which is what a var would be told and points nowhere near the
     * missing letter.
     */
    @Test
    void reportsArecordSelectorWithNoFieldSelectors() throws IOException {
        assertEquals(1, validate("empty.json", """
                { "input": { "mimeType": "text/csv", "accepts": "glob:*.csv",
                    "recordSelectors": [ { "name": "all",
                        "fieldSelector": [ { "name": "n1", "selector": "Name" } ] } ] },
                  "mapping": [ { "recordSelector": "all", "table": "t",
                                 "fieldMapping": [ { "fieldSelector": "n1", "column": "c" } ] } ] }
                """));
    }

    /**
     * A reader is chosen by what it says it accepts, and no reader accepts a
     * name in an unknown format - so the file is refused rather than handed to
     * whichever reader the service loader offered first, which would report a
     * parse error about a format the file was never in.
     */
    @Test
    void rejectsAspecInAnUnknownFormat() throws IOException {
        assertEquals(1, validate("spec.txt", """
                { "input": { "mimeType": "text/csv", "accepts": "glob:*.csv", "recordSelectors": [] } }
                """));
    }

    /**
     * Several specs at once, the exit code reporting whether any was bad - so a
     * whole feed tree can be checked in one call, in CI or before a deploy.
     */
    @Test
    void checksSeveralSpecsAtOnce() throws IOException {
        var good = dir.resolve("good.json");
        Files.writeString(good, """
                { "input": { "mimeType": "text/csv", "accepts": "glob:*.csv", "recordSelectors": [] } }
                """);
        var bad = dir.resolve("bad.json");
        Files.writeString(bad, """
                { "input": { "mimeType": "text/csv", "recordSelectors": [] } }
                """);

        assertEquals(0, new CommandLine(new App()).execute("validate", good.toString()));
        assertEquals(1, new CommandLine(new App())
                .execute("validate", good.toString(), bad.toString()));
    }
}
