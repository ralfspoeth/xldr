package io.github.ralfspoeth.xldr.it;

import io.github.ralfspoeth.xldr.app.App;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code xldr check} against a real database and a real file.
 * <p>
 * Every finding here is a mistake that a schema-valid spec can contain and that
 * nothing catches until a producer has delivered something: a mapping naming a
 * record selector the input never declared, a column the table has not got, a
 * record selector that matches nothing in a file the author says is
 * representative. The point of the command is that they are findable before a
 * feed exists, so the point of this test is that they are actually found.
 * <p>
 * The command is driven through {@code picocli} rather than by calling the class
 * behind it, because the argument parsing is part of what an author uses and a
 * mis-declared option would otherwise pass here and fail at a keyboard.
 * <p>
 * A file-based H2 under {@code target/}, not an in-memory one: {@code check}
 * connects on its own and closes again, and an in-memory database would be gone
 * between the setup and the run unless kept alive by a connection nobody in the
 * command holds.
 */
public class CheckIT {

    private static final Path DB = Path.of("target", "check-it");
    private static final String JDBC_URL = "jdbc:h2:./" + DB;

    /**
     * The table tutorial page 8 loads into, so the fixtures below are its own,
     * plus the reference table its lookup pages use.
     */
    @BeforeAll
    static void createTheSchema() throws SQLException {
        try (var conn = DriverManager.getConnection(JDBC_URL);
             var stmt = conn.createStatement()) {
            stmt.execute("drop table if exists customer");
            stmt.execute("""
                    create table customer(
                        id integer, name varchar(50), since date, balance decimal(12,2),
                        -- for the mapping plan, which wants a column per kind of source
                        source_cd varchar(10), region_id integer, loaded_from varchar(60))
                    """);
            stmt.execute("drop table if exists region");
            stmt.execute("create table region(city varchar(50), id integer)");
        }
    }

    /** the German file of tutorial page 8 */
    private static final String SAMPLE = """
            id,name,since,balance
            1,Alice,01.03.2026,"1.234,56"
            2,Bob,15.03.2026,"98,00"
            """;

    private static final String SPEC = """
            {
              "input": {
                "mimeType": "text/csv",
                "properties": {
                  "dateFormat": "dd.MM.yyyy",
                  "numberFormat": "#,##0.00",
                  "locale": "de-DE"
                },
                "recordSelectors": [
                  { "name": "customers",
                    "fieldSelectors": [
                      {"name": "id",      "selector": "id",      "type": "INTEGRAL"},
                      {"name": "name",    "selector": "name"},
                      {"name": "since",   "selector": "since",   "type": "DATE"},
                      {"name": "balance", "selector": "balance", "type": "DECIMAL"}
                    ]
                  }
                ]
              },
              "mapping": [
                { "recordSelector": "%s", "table": "customer",
                  "fieldMapping": [
                    {"fieldSelector": "id",      "column": "id"},
                    {"fieldSelector": "name",    "column": "name"},
                    {"fieldSelector": "since",   "column": "since"},
                    {"fieldSelector": "balance", "column": "%s"}
                  ]
                }
              ]
            }
            """;

    private record Run(int exitCode, String out, String err) {
        boolean reports(String text) {
            return out.contains(text) || err.contains(text);
        }
    }

    private static Run check(Path dir, String spec, String sample, String... extra)
            throws IOException {
        var specFile = dir.resolve("spec.json");
        Files.writeString(specFile, spec);
        var sampleFile = dir.resolve("data.csv");
        Files.writeString(sampleFile, sample);

        var out = new StringWriter();
        var err = new StringWriter();
        var args = new ArrayList<>(List.of(
                "check", specFile.toString(),
                "--sample", sampleFile.toString(),
                "--url", JDBC_URL));
        args.addAll(List.of(extra));

        var exit = new CommandLine(new App())
                .setOut(new PrintWriter(out, true))
                .setErr(new PrintWriter(err, true))
                .execute(args.toArray(String[]::new));
        return new Run(exit, out.toString(), err.toString());
    }

    /** the spec as the tutorial writes it, which has nothing wrong with it */
    @Test
    void acorrectSpecHasNoFindings(@TempDir Path dir) throws IOException {
        var run = check(dir, SPEC.formatted("customers", "balance"), SAMPLE);
        assertAll(
                () -> assertEquals(0, run.exitCode(), run.out() + run.err()),
                () -> assertTrue(run.reports("no findings"), run.out()),
                () -> assertTrue(run.reports("2 record(s) matched"), run.out()));
    }

    /**
     * A mapping naming a record selector the input does not declare. Nothing
     * cross-checks this in a running server: the name goes straight to the
     * adapter, which refuses it on the first file.
     */
    @Test
    void findsArecordSelectorTheInputDoesNotDeclare(@TempDir Path dir) throws IOException {
        var run = check(dir, SPEC.formatted("custmoers", "balance"), SAMPLE);
        assertAll(
                () -> assertFalse(run.exitCode() == 0, "should have found something"),
                () -> assertTrue(run.reports("custmoers"), run.out()),
                () -> assertTrue(run.reports("does not declare"), run.out()),
                // and says what the input does declare, which is the fix
                () -> assertTrue(run.reports("customers"), run.out()));
    }

    /**
     * A column the target table has not got. This one ends as a SQL error on the
     * first insert, inside a transaction, with the file already claimed.
     */
    @Test
    void findsAcolumnTheTableHasNotGot(@TempDir Path dir) throws IOException {
        var run = check(dir, SPEC.formatted("customers", "blance"), SAMPLE);
        assertAll(
                () -> assertFalse(run.exitCode() == 0, "should have found something"),
                () -> assertTrue(run.reports("blance"), run.out()),
                () -> assertTrue(run.reports("BALANCE"),
                        "and lists the columns the table has: " + run.out()));
    }

    /**
     * A record selector that is well formed, names real columns, and matches
     * nothing in the file. Nothing refuses this at any point - the load succeeds
     * and inserts no rows, which is the quietest failure of the three.
     */
    @Test
    void findsArecordSelectorThatMatchesNothing(@TempDir Path dir) throws IOException {
        var discriminated = SPEC.formatted("customers", "balance")
                .replace("\"name\": \"customers\",",
                        "\"name\": \"customers\", \"discriminator\": { \"selector\": \"name\", \"equals\": \"Nobody\" },");
        var run = check(dir, discriminated, SAMPLE);
        assertAll(
                () -> assertFalse(run.exitCode() == 0, "should have found something"),
                () -> assertTrue(run.reports("matches nothing"), run.out()),
                () -> assertTrue(run.reports("0 record(s) matched"), run.out()));
    }

    /**
     * A lookup names a table and two columns of its own, and those are checked
     * too.
     * <p>
     * They were not at first, which the tutorial sweep found - its two lookup
     * pages passed with their reference tables never examined. A lookup against
     * a table that is not there fails on the first record of the first file, by
     * which point the load has begun and the file is claimed.
     */
    @Test
    void findsAlookupAgainstAtableThatIsNotThere(@TempDir Path dir) throws IOException {
        var run = check(dir, withLookup("regoin", "id", "city"), SAMPLE);
        assertAll(
                () -> assertFalse(run.exitCode() == 0, "should have found something"),
                () -> assertTrue(run.reports("regoin"), run.out()),
                () -> assertTrue(run.reports("not in the target database"), run.out()));
    }

    /** and the column it returns, and the column it matches on */
    @Test
    void findsAlookupColumnThatIsNotThere(@TempDir Path dir) throws IOException {
        var returned = check(dir, withLookup("region", "ident", "city"), SAMPLE);
        var key = check(dir, withLookup("region", "id", "twon"), SAMPLE);
        assertAll(
                () -> assertTrue(returned.reports("ident"), returned.out()),
                () -> assertTrue(returned.reports("CITY"),
                        "and lists what region has: " + returned.out()),
                () -> assertTrue(key.reports("twon"), key.out()));
    }

    /** a lookup that resolves against a real table and real columns is quiet */
    @Test
    void acorrectLookupHasNoFindings(@TempDir Path dir) throws IOException {
        var run = check(dir, withLookup("region", "id", "city"), SAMPLE);
        assertEquals(0, run.exitCode(), run.out() + run.err());
    }

    /**
     * A spec whose lookup is inside a var rather than a field mapping. A var is
     * evaluated once per load rather than per record, so a broken one fails
     * before a single row has been read - and it is reached by a different walk,
     * which is the reason to test it separately.
     */
    @Test
    void findsAbrokenLookupInsideAvar(@TempDir Path dir) throws IOException {
        var spec = SPEC.formatted("customers", "balance").replace(
                "\"recordSelectors\":",
                """
                        "vars": [
                          { "name": "regionId",
                            "lookup": { "table": "regoin", "column": "id",
                                        "keyColumn": "city", "constant": "Berlin" } }
                        ],
                        "recordSelectors":""");
        var run = check(dir, spec, SAMPLE);
        assertAll(
                () -> assertFalse(run.exitCode() == 0, "should have found something"),
                () -> assertTrue(run.reports("regoin"), run.out()));
    }

    /**
     * The page-8 spec with its {@code name} column filled by a lookup instead,
     * so that one field mapping carries a {@code ValueSource.Lookup}.
     */
    private static String withLookup(String table, String column, String keyColumn) {
        return SPEC.formatted("customers", "balance").replace(
                "{\"fieldSelector\": \"name\",    \"column\": \"name\"},",
                """
                        {"column": "name",
                         "lookup": {"table": "%s", "column": "%s",
                                    "keyColumn": "%s", "fieldSelector": "name"}},"""
                        .formatted(table, column, keyColumn));
    }

    // ---- one spec against another ----------------------------------------------

    /**
     * A spec transliterated into the other format is the same spec, and
     * {@code --same-as} says so.
     * <p>
     * Tutorial page 3 is entirely about converting between the two formats, and
     * until now nothing could tell you whether a conversion was faithful. Both
     * are read into a {@code MappingSpec}, which is records all the way down, so
     * the comparison is equality.
     */
    @Test
    void saysWhenTheOtherFormatSaysTheSameThing(@TempDir Path dir) throws IOException {
        var run = check(dir, SPEC.formatted("customers", "balance"), SAMPLE,
                "--same-as", xml(dir, "same.xml", EQUIVALENT_XML));
        assertAll(
                () -> assertEquals(0, run.exitCode(), run.out() + run.err()),
                () -> assertTrue(run.reports("matches same.xml"), run.out()));
    }

    /**
     * And names what parted company, rather than only that something did.
     * <p>
     * "They differ" is no help against a hundred lines, so the comparison is
     * piecewise and by name - a record selector written in a different order is
     * the same spec, and reporting that would bury the difference that matters.
     */
    @Test
    void namesWhatDiffersBetweenTheTwoFormats(@TempDir Path dir) throws IOException {
        // a different target table, which is a difference and not a broken spec:
        // changing a column instead would have put two field mappings onto one
        // column, and that is now refused when the spec is read
        var wrong = EQUIVALENT_XML.replace("table=\"customer\"", "table=\"customers\"");
        var run = check(dir, SPEC.formatted("customers", "balance"), SAMPLE,
                "--same-as", xml(dir, "wrong.xml", wrong));
        assertAll(
                () -> assertFalse(run.exitCode() == 0, "should have found something"),
                () -> assertTrue(run.reports("differs"), run.out()),
                () -> assertTrue(run.reports("mapping 'customers'"), run.out()));
    }

    /**
     * And a comparison spec that will not parse is a finding, not a shrug.
     * <p>
     * It printed to stderr and returned, leaving the exit code at zero - so
     * {@code --same-as} pointed at a broken file reported success, which is the
     * one answer it must never give: the question is whether the two agree, and
     * one of them could not be read.
     */
    @Test
    void anUnreadableComparisonSpecIsAfinding(@TempDir Path dir) throws IOException {
        var run = check(dir, SPEC.formatted("customers", "balance"), SAMPLE,
                "--same-as", xml(dir, "broken.xml", "<mappingSpec><input"));
        assertAll(
                () -> assertFalse(run.exitCode() == 0, "should have found something"),
                () -> assertTrue(run.reports("broken.xml"), run.out() + run.err()),
                () -> assertTrue(run.reports("nothing was compared"), run.out()));
    }

    /** the page-8 spec written as XML, which is what page 3 teaches */
    private static final String EQUIVALENT_XML = """
            <mappingSpec>
                <input mimeType="text/csv">
                    <properties dateFormat="dd.MM.yyyy" numberFormat="#,##0.00" locale="de-DE"/>
                    <recordSelector name="customers">
                        <fieldSelector name="id" selector="id" type="INTEGRAL"/>
                        <fieldSelector name="name" selector="name"/>
                        <fieldSelector name="since" selector="since" type="DATE"/>
                        <fieldSelector name="balance" selector="balance" type="DECIMAL"/>
                    </recordSelector>
                </input>
                <mapping recordSelector="customers" table="customer">
                    <fieldMapping fieldSelector="id" column="id"/>
                    <fieldMapping fieldSelector="name" column="name"/>
                    <fieldMapping fieldSelector="since" column="since"/>
                    <fieldMapping fieldSelector="balance" column="balance"/>
                </mapping>
            </mappingSpec>
            """;

    private static String xml(Path dir, String name, String content) throws IOException {
        return Files.writeString(dir.resolve(name), content).toString();
    }

    // ---- the mapping side ------------------------------------------------------

    /**
     * Where every target column's value comes from, which nothing else in this
     * command says anything about.
     * <p>
     * A spec spreads its columns over a hundred lines with each source nested
     * inside its own object, so the question "where does this column come from?"
     * is answered nowhere in one place - and a column wired to the wrong source
     * validates, loads, and is wrong in every row.
     */
    @Test
    void showsWhereEachColumnComesFrom(@TempDir Path dir) throws IOException {
        var withEverything = SPEC.formatted("customers", "balance")
                .replace("\"recordSelectors\":", """
                        "vars": [ { "name": "src", "constant": "PD" } ],
                        "recordSelectors":""")
                .replace("{\"fieldSelector\": \"balance\", \"column\": \"balance\"}", """
                        {"fieldSelector": "balance", "column": "balance"},
                        {"var": "src", "column": "source_cd"},
                        {"expr": "${xldr.filename}", "column": "loaded_from"},
                        {"column": "region_id",
                         "lookup": {"table": "region", "column": "id",
                                    "keyColumn": "city", "fieldSelector": "name"}}""");
        var run = check(dir, withEverything, SAMPLE);
        assertAll(
                () -> assertTrue(run.reports("customer <- 'customers'"), run.out()),
                () -> assertTrue(run.reports("field     id"), run.out()),
                () -> assertTrue(run.reports("var       src"), run.out()),
                () -> assertTrue(run.reports("expr      ${xldr.filename}"), run.out()),
                () -> assertTrue(run.reports("lookup    region.id where city = field name"),
                        run.out()),
                () -> assertTrue(run.reports("vars, evaluated once per load"), run.out()),
                () -> assertTrue(run.reports("constant  'PD'"), run.out()));
    }

    /**
     * The dry run, which is the half no static check can do.
     * <p>
     * A date read under the wrong pattern is still a date and a German decimal
     * read as a plain one is still a number, so nothing refuses either. What
     * catches them is seeing the parsed value: the file says {@code 01.03.2026}
     * and the output has to say the first of March, which is the assertion that
     * would fail if {@code dateFormat} were {@code MM.dd.yyyy}.
     */
    @Test
    void showsWhatTheValuesParseTo(@TempDir Path dir) throws IOException {
        var run = check(dir, SPEC.formatted("customers", "balance"), SAMPLE, "--rows", "2");
        assertAll(
                () -> assertEquals(0, run.exitCode(), run.out() + run.err()),
                () -> assertTrue(run.reports("2026-03-01"),
                        "the first of March, not the third of January: " + run.out()),
                () -> assertTrue(run.reports("1234.56"),
                        "the German grouping read as a thousand and a bit: " + run.out()),
                // and the Java type beside each, which is what says INTEGRAL
                // became a Long rather than staying text
                () -> assertTrue(run.reports("(Long)"), run.out()),
                () -> assertTrue(run.reports("(BigDecimal)"), run.out()),
                () -> assertTrue(run.reports("(LocalDateTime)"), run.out()));
    }

    /** {@code --rows 0} asks for the checks without the sample of values */
    @Test
    void showsNoRowsWhenNoneAreAskedFor(@TempDir Path dir) throws IOException {
        var run = check(dir, SPEC.formatted("customers", "balance"), SAMPLE, "--rows", "0");
        assertAll(
                () -> assertEquals(0, run.exitCode()),
                () -> assertFalse(run.reports("Alice"), "no values shown: " + run.out()),
                () -> assertTrue(run.reports("2 record(s) matched"), run.out()));
    }

    /**
     * Nothing is written. The command is meant to be safe to point at the only
     * database that has the table, which is often production.
     */
    @Test
    void insertsNothing(@TempDir Path dir) throws IOException, SQLException {
        check(dir, SPEC.formatted("customers", "balance"), SAMPLE);
        try (var conn = DriverManager.getConnection(JDBC_URL);
             var stmt = conn.createStatement();
             var rs = stmt.executeQuery("select count(*) from customer")) {
            assertTrue(rs.next());
            assertEquals(0, rs.getInt(1), "check must not load anything");
        }
    }
}
