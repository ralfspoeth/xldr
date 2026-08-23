package io.github.ralfspoeth.xldr.spec.io;

import com.networknt.schema.InputFormat;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import io.github.ralfspoeth.xldr.spec.FieldSelectorSpec;
import io.github.ralfspoeth.xldr.spec.MappingSpec;
import io.github.ralfspoeth.xldr.spec.RecordSelectorSpec;
import io.github.ralfspoeth.xldr.spec.ValueSource;
import org.junit.jupiter.api.Test;

import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.SchemaFactory;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static io.github.ralfspoeth.xldr.spec.io.Streams.stream;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The specs printed in the tutorial are specs this release can read.
 * <p>
 * Twelve pages carry specs, sample files and {@code create table} statements,
 * and nothing compiled ever looked at them. That is how the schema page went
 * stale for four releases: documentation drifts silently, because drifting is
 * all it can do. What a reader copies off a page ought to be held to the same
 * standard as a fixture, and the way to do that is to make it one.
 * <p>
 * Everything here is static - the document against the schema, and the document
 * against itself and the page's own DDL. Whether a record selector matches
 * anything in the sample file, and what the values parse to, needs the real
 * adapters and a database, and is {@code tools/check-tutorial.py} run by hand.
 * <p>
 * Tables accumulate across pages and a later page's definition wins, which is
 * what a reader following in order has: each page redefines {@code customer} for
 * its own lesson, page 8's having a balance where page 2's has a city.
 */
class TutorialTest {

    private static final Path TUTORIAL = Path.of("..", "docs", "tutorial");
    private static final Path JSON_SCHEMA = Path.of("..", "docs", "schema", "mapping-spec-0.35.json");
    private static final Path XSD = Path.of("..", "docs", "schema", "mapping-spec-0.35.xsd");

    /** ```lang ... ``` - the only kind of block a page states a fixture in */
    private static final Pattern FENCED = Pattern.compile("```(\\w*)\\n(.*?)```", Pattern.DOTALL);

    private static final Pattern CREATE_TABLE = Pattern.compile(
            "create\\s+table\\s+(?:if\\s+not\\s+exists\\s+)?([A-Za-z_]\\w*)\\s*\\((.*)\\)",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    /**
     * Every page in order, with the tables it has by the time its specs are read.
     */
    private record Page(String name, String text, Map<String, List<String>> tables) {
    }

    private static List<Page> pages() throws IOException {
        var tables = new LinkedHashMap<String, List<String>>();
        var pages = new ArrayList<Page>();
        try (var files = Files.list(TUTORIAL)) {
            for (var path : files.filter(p -> p.getFileName().toString().endsWith(".md")).sorted().toList()) {
                var text = Files.readString(path);
                // this page's DDL before this page's specs, so a page that
                // redefines a table is checked against its own definition
                for (var ddl : blocks(text, "sql")) {
                    for (var statement : ddl.split(";\\s*\\n")) {
                        var m = CREATE_TABLE.matcher(statement.strip());
                        if (m.matches()) {
                            tables.put(m.group(1).toLowerCase(), columnsOf(m.group(2)));
                        }
                    }
                }
                pages.add(new Page(path.getFileName().toString(), text, Map.copyOf(tables)));
            }
        }
        return pages;
    }

    private static List<String> blocks(String text, String language) {
        var found = new ArrayList<String>();
        Matcher m = FENCED.matcher(text);
        while (m.find()) {
            if (language.equals(m.group(1))) {
                found.add(m.group(2));
            }
        }
        return found;
    }

    /**
     * The column names of a {@code create table} body, ignoring types and any
     * table-level constraint - split at the commas that are not inside a type's
     * own parentheses, so that {@code decimal(12,2)} stays one column.
     */
    private static List<String> columnsOf(String body) {
        var columns = new ArrayList<String>();
        var current = new StringBuilder();
        int depth = 0;
        for (var ch : body.toCharArray()) {
            if (ch == '(') {
                depth++;
            } else if (ch == ')') {
                depth--;
            }
            if (ch == ',' && depth == 0) {
                columns.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        columns.add(current.toString());
        return columns.stream()
                .map(String::strip)
                .filter(part -> !part.isEmpty())
                .map(part -> part.split("\\s+")[0].replace("\"", "").toLowerCase())
                .filter(name -> !Set.of("primary", "foreign", "unique", "constraint", "check").contains(name))
                .toList();
    }

    // ---- the document against the published schema -----------------------------

    /**
     * Every JSON spec validates against the schema the pages tell a reader to
     * point at - and with the same validator the rest of this build uses, so a
     * clean run here says something about what the reader accepts rather than
     * about a second implementation's opinion.
     */
    @Test
    void everyJsonSpecValidatesAgainstThePublishedSchema() throws IOException {
        var schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(Files.readString(JSON_SCHEMA));
        var findings = new ArrayList<String>();
        for (var page : pages()) {
            for (var spec : specs(page, "json", "\"input\"")) {
                schema.validate(spec, InputFormat.JSON)
                        .forEach(error -> findings.add(page.name() + ": " + error));
            }
        }
        assertEquals(List.of(), findings);
    }

    /** and every XML one against the XSD, which is page 3 and nothing else today */
    @Test
    void everyXmlSpecValidatesAgainstThePublishedSchema() throws Exception {
        var schema = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI)
                .newSchema(new File(XSD.toString()));
        for (var page : pages()) {
            for (var spec : specs(page, "xml", "<mappingSpec")) {
                schema.newValidator().validate(new StreamSource(new StringReader(spec)));
            }
        }
    }

    // ---- the document against itself -------------------------------------------

    /**
     * Every spec on every page is read by the reader that will read it in
     * anger, and cross-checked against the page's own tables.
     * <p>
     * Three things a schema cannot see, and the three {@code xldr check} was
     * written for: a mapping naming a record selector the input never declared,
     * a field mapping reading a field selector the record selector has not got,
     * and a column the target table has not got.
     */
    @Test
    void everySpecAgreesWithItsOwnPage() throws IOException {
        var findings = new ArrayList<String>();
        for (var page : pages()) {
            for (var text : specs(page, "json", "\"input\"")) {
                check(page, new JsonMappingSpecReader().read(stream(text)), findings);
            }
            for (var text : specs(page, "xml", "<mappingSpec")) {
                check(page, new XmlMappingSpecReader().read(stream(text)), findings);
            }
        }
        assertEquals(List.of(), findings);
    }

    private static void check(Page page, MappingSpec spec, List<String> findings) {
        var declared = spec.inputSpec().recordSelectors().stream()
                .collect(Collectors.toMap(RecordSelectorSpec::name, rs -> rs));
        for (var mapping : spec.recordMappingSpecs()) {
            var rs = declared.get(mapping.recordSelector());
            if (rs == null) {
                findings.add(page.name() + ": mapping into '" + mapping.table() + "' names record selector '"
                        + mapping.recordSelector() + "', which the input does not declare; it declares "
                        + new TreeSet<>(declared.keySet()));
                continue;
            }
            var fields = rs.fieldSelectors().stream()
                    .map(FieldSelectorSpec::name)
                    .collect(Collectors.toCollection(TreeSet::new));
            for (var fm : mapping.fieldMappings()) {
                fieldNames(fm.source()).stream()
                        .filter(name -> !fields.contains(name))
                        .forEach(name -> findings.add(page.name() + ": field mapping reads '" + name
                                + "', which record selector '" + rs.name() + "' does not declare; it has "
                                + fields));
            }
            var columns = page.tables().get(mapping.table().toLowerCase());
            if (columns == null) {
                findings.add(page.name() + ": no create table for '" + mapping.table()
                        + "' on this page or an earlier one; there is " + new TreeSet<>(page.tables().keySet()));
                continue;
            }
            for (var fm : mapping.fieldMappings()) {
                if (!columns.contains(fm.column().replace("\"", "").toLowerCase())) {
                    findings.add(page.name() + ": table '" + mapping.table() + "' has no column '"
                            + fm.column() + "'; it has " + columns);
                }
            }
        }
    }

    /** the field selectors a value source reads, a lookup's key included */
    private static Set<String> fieldNames(ValueSource source) {
        return switch (source) {
            case ValueSource.Field(var name) -> Set.of(name);
            case ValueSource.Lookup(_, _, _, var key) -> fieldNames(key);
            // a call reads no record either: it is a var source, evaluated once
            // before the first one is read
            case ValueSource.Constant _, ValueSource.Var _, ValueSource.Expr _,
                 ValueSource.FunctionCall _ -> Set.of();
        };
    }

    // ---- one page against another ----------------------------------------------

    /**
     * Page 3 says it is page 2's spec written in the other format, and that claim
     * had nothing behind it.
     * <p>
     * Both are read into a {@link MappingSpec}, which is records all the way
     * down, so the claim is equality. This is what {@code xldr check --same-as}
     * does for an author's own two files, applied to the one place in the
     * documentation that makes the promise.
     */
    @Test
    void theXmlPageSaysWhatTheJsonPageSaid() throws IOException {
        var byName = pages().stream().collect(Collectors.toMap(Page::name, p -> p));
        var json = specs(byName.get("02-first-spec.md"), "json", "\"input\"");
        var xml = specs(byName.get("03-in-xml.md"), "xml", "<mappingSpec");
        assertEquals(1, json.size(), "page 2 shows one spec");
        assertEquals(1, xml.size(), "page 3 shows one spec");

        assertEquals(
                new JsonMappingSpecReader().read(stream(json.getFirst())),
                new XmlMappingSpecReader().read(stream(xml.getFirst())),
                "page 3 is page 2 in the other format, and says so");
    }

    // ---- and that this test is looking at anything at all ----------------------

    /**
     * The guard against a green run that checked nothing.
     * <p>
     * Every assertion above passes vacuously if the extraction quietly stops
     * finding blocks - a fence written differently, a page renamed, a regex that
     * no longer matches. That is the failure this whole class exists to prevent,
     * so it would be a poor thing to be prone to.
     */
    @Test
    void thePagesActuallyYieldSpecs() throws IOException {
        var pages = pages();
        var json = pages.stream().mapToLong(p -> specs(p, "json", "\"input\"").size()).sum();
        var xml = pages.stream().mapToLong(p -> specs(p, "xml", "<mappingSpec").size()).sum();
        assertTrue(pages.size() >= 12, "twelve pages and an index, found " + pages.size());
        assertTrue(json >= 8, "eight whole JSON specs at the last count, found " + json);
        assertEquals(1, xml, "one XML spec, on page 3, found " + xml);
        assertTrue(pages.getLast().tables().size() >= 5,
                "five tables by the end, found " + pages.getLast().tables().keySet());
    }

    /** the blocks of one language on a page that are whole specs rather than fragments */
    private static List<String> specs(Page page, String language, String marker) {
        return blocks(page.text(), language).stream().filter(b -> b.contains(marker)).toList();
    }
}
