package io.github.ralfspoeth.xldr.it;

import io.github.ralfspoeth.xldr.ia.InputAdapter;
import io.github.ralfspoeth.xldr.ia.InputAdapterFactory;
import io.github.ralfspoeth.xldr.ldr.Loader;
import io.github.ralfspoeth.xldr.ldr.Target;
import io.github.ralfspoeth.xldr.spec.InputSpec;
import io.github.ralfspoeth.xldr.spec.MappingSpec;
import io.github.ralfspoeth.xldr.spec.io.JsonMappingSpecReader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.sql.DriverManager.getConnection;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The loader against a real PostgreSQL, which exists for one reason: PostgreSQL
 * is the product whose metadata answers differ from every other target this
 * build runs against, and the code branches on exactly those answers.
 * <p>
 * Two of them matter. {@code supportsCatalogsInDataManipulation} is false - it
 * cannot qualify across databases - so a {@code catalog} in a deployment's
 * target is a load that can never happen and has to be refused before the first
 * record. And {@code storesLowerCaseIdentifiers} is true, where Oracle and H2
 * fold up, so an unquoted name written in a spec reaches a differently-cased
 * table than it would anywhere else.
 * <p>
 * <strong>Why this is not covered by the unit tests.</strong> Both paths already
 * have tests - {@code QualifyingTest} and {@code LoaderTest} drive them through
 * {@code AnsweringConnection}, a stub whose answers are what we
 * <em>believe</em> PostgreSQL says. That is a fine way to test the branching and
 * no way at all to test the belief: if the belief is wrong the stub is wrong the
 * same way, and the tests agree with the bug. This class is the one that asks
 * the database.
 * <p>
 * It runs only when {@code XLDR_PG_URL} is set, which CI does by way of a
 * service container; without it the test skips and a build with no PostgreSQL
 * anywhere is unaffected. That is also why nothing here is in the default
 * {@code mvn clean verify} a contributor runs locally.
 */
@EnabledIfEnvironmentVariable(named = "XLDR_PG_URL", matches = ".+",
        disabledReason = "no PostgreSQL to talk to; CI supplies XLDR_PG_URL")
class PostgresIT {

    private static final String URL = System.getenv("XLDR_PG_URL");
    private static final String USER = envOr("XLDR_PG_USER", "xldr");
    private static final String PASSWORD = envOr("XLDR_PG_PASSWORD", "xldr");

    private static final String CSV = """
            sku,qty
            S1,5
            S2,2
            S3,11
            """;

    /**
     * Deliberately all lower case and unquoted, both in the DDL and in the spec
     * below, because that is the ordinary way to write PostgreSQL and the case
     * the folding has to get right. The loader upper-cases an unquoted name on
     * its way into the statement; PostgreSQL folds it back down; the two have to
     * meet here.
     */
    private static final String DDL = "create table shipment(sku varchar(10), qty integer)";

    private static final String SPEC = """
            {
              "input": {
                "mimeType": "text/csv",
                "properties": { "fieldSeparator": ",", "header": true },
                "recordSelectors": [
                  { "name": "line", "fieldSelectors": [
                      { "name": "sku", "selector": "sku" },
                      { "name": "qty", "selector": "qty", "type": "INTEGRAL" } ] }
                ]
              },
              "mapping": [
                { "recordSelector": "line", "table": "shipment", "fieldMapping": [
                    { "fieldSelector": "sku", "column": "sku" },
                    { "fieldSelector": "qty", "column": "qty" } ] }
              ]
            }
            """;

    @BeforeAll
    static void sayWhereWeAre() throws Exception {
        try (var conn = connect()) {
            var meta = conn.getMetaData();
            // if this is ever not PostgreSQL, every assertion below is testing
            // something other than what it says it is
            assertEquals("PostgreSQL", meta.getDatabaseProductName(),
                    "XLDR_PG_URL points at " + meta.getDatabaseProductName());
        }
    }

    // ---- the assumptions the stub encodes -------------------------------------

    /**
     * The two answers {@code AnsweringConnection} hard-codes, read off the real
     * driver. If this test ever fails, the stub is lying and both unit tests
     * that use it are passing for the wrong reason - which is a bigger finding
     * than a failing load.
     */
    @Test
    void theMetadataAnswersAreWhatWeBuiltOn() throws Exception {
        try (var conn = connect()) {
            var meta = conn.getMetaData();
            assertAll(
                    () -> assertFalse(meta.supportsCatalogsInDataManipulation(),
                            "PostgreSQL cannot qualify across databases; the loader's refusal depends on this"),
                    () -> assertTrue(meta.storesLowerCaseIdentifiers(),
                            "an unquoted identifier folds down here, which is what Check compensates for"));
        }
    }

    // ---- what the loader does with them ---------------------------------------

    /**
     * A catalog is refused, before anything is read, and the message names the
     * catalog, the product, and PostgreSQL's own word for the thing.
     */
    @Test
    void aCatalogIsRefusedAndTheMessageSaysWhy() throws Exception {
        try (var conn = connect()) {
            var thrown = assertThrows(SQLException.class,
                    () -> new Loader(empty(), conn, Map.of(), new Target("warehouse", null)));
            assertAll(
                    () -> assertTrue(thrown.getMessage().contains("warehouse"), thrown.getMessage()),
                    () -> assertTrue(thrown.getMessage().contains("PostgreSQL"), thrown.getMessage()),
                    () -> assertTrue(thrown.getMessage().contains("target.properties"), thrown.getMessage()));
        }
    }

    /**
     * And the ordinary path: an unquoted lower-case table named by an unquoted
     * spec, loaded end to end and committed. This is the test that the folding
     * meets in the middle - it is written as a plain load rather than as an
     * assertion about strings because a wrong answer here shows up as
     * "relation does not exist", which is the failure a user would get.
     */
    @Test
    void anUnquotedNameReachesTheLowerCaseTable() throws Exception {
        schema();
        load();

        try (var conn = connect();
             var stmt = conn.createStatement();
             var rs = stmt.executeQuery("select count(*), sum(qty) from shipment")) {
            assertTrue(rs.next());
            assertAll(
                    () -> assertEquals(3, rs.getInt(1), "every record committed"),
                    () -> assertEquals(18, rs.getInt(2), "and INTEGRAL arrived as a number, not as text"));
        }
    }

    // ---- plumbing --------------------------------------------------------------

    private static Connection connect() throws SQLException {
        return getConnection(URL, USER, PASSWORD);
    }

    private static void schema() throws Exception {
        try (var conn = connect();
             var stmt = conn.createStatement()) {
            stmt.execute("drop table if exists shipment");
            stmt.execute(DDL);
        }
    }

    private static void load() throws Exception {
        var mappingSpec = new JsonMappingSpecReader().read(stream(SPEC));
        var adapter = adapterFor(mappingSpec.inputSpec());
        try (var connection = connect();
             var loader = new Loader(mappingSpec, connection, Map.of())) {
            for (var mapping : mappingSpec.recordMappingSpecs()) {
                loader.loadInput(adapter, new ByteArrayInputStream(CSV.getBytes(UTF_8)), mapping);
            }
        }
    }

    /** the smallest spec there is: the target is decided before any mapping is read */
    private static MappingSpec empty() {
        return new MappingSpec(new InputSpec("text/csv", List.of(), List.of(), Map.of()), List.of());
    }

    private static InputAdapter adapterFor(InputSpec inputSpec) {
        return ServiceLoader.load(InputAdapterFactory.class)
                .stream()
                .map(ServiceLoader.Provider::get)
                .filter(f -> f.reads(inputSpec))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no adapter for " + inputSpec.mimeType()))
                .createInputAdapter(inputSpec);
    }

    private static InputStream stream(String string) {
        return new ByteArrayInputStream(string.getBytes(StandardCharsets.UTF_8));
    }

    private static String envOr(String name, String fallback) {
        var value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
