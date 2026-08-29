package io.github.ralfspoeth.xldr.it;

import io.github.ralfspoeth.xldr.ia.InputAdapter;
import io.github.ralfspoeth.xldr.ia.InputAdapterFactory;
import io.github.ralfspoeth.xldr.ldr.Loader;
import io.github.ralfspoeth.xldr.spec.InputSpec;
import io.github.ralfspoeth.xldr.spec.io.JsonMappingSpecReader;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.Map;
import java.util.ServiceLoader;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.sql.DriverManager.getConnection;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A transform against a real database, which is the only place its two claims
 * can be checked at all: that it runs after the last record and before the
 * commit, and that a failing one takes the load down with it.
 * <p>
 * Both are claims about a transaction, so nothing short of a database can hold
 * the loader to them. The procedures are H2's {@code CREATE ALIAS} over static
 * Java methods, which is H2's way of having one - no more xldr's business than
 * the vendor syntax on the tutorial page is, and the reason they live in
 * {@link TransformProcedures} rather than here.
 */
class TransformIT {

    private static final String JDBC_URL = "jdbc:h2:mem:transform;DB_CLOSE_DELAY=-1";

    private static final String CSV = """
            sku
            S1
            S2
            S3
            """;

    /**
     * H2's way of having a stored procedure: an alias for a static Java method,
     * named rather than written out, so nothing is compiled at run time. What
     * the two of them do is {@link TransformProcedures}.
     */
    private static final String PROCEDURE =
            "create alias close_batch for 'io.github.ralfspoeth.xldr.it.TransformProcedures.closeBatch'";

    private static final String FAILING_PROCEDURE =
            "create alias explode for 'io.github.ralfspoeth.xldr.it.TransformProcedures.explode'";

    private static String spec(String transform) {
        return """
                {
                  "input": {
                    "mimeType": "text/csv",
                    "properties": { "fieldSeparator": ",", "header": true },
                    "vars": [ { "name": "feed", "constant": "shipments" } ],
                    "recordSelectors": [
                      { "name": "line", "fieldSelectors": [ { "name": "sku", "selector": "sku" } ] }
                    ]
                  },
                  "mapping": [
                    { "recordSelector": "line", "table": "shipment", "fieldMapping": [
                        { "fieldSelector": "sku", "column": "sku" } ] }
                  ],
                  "transform": [ %s ]
                }
                """.formatted(transform);
    }

    /**
     * The rows are there when the procedure looks, and the count it is handed is
     * the count the loader inserted.
     */
    @Test
    void runsAfterTheRecordsAndSeesThem() throws Exception {
        schema();
        load(spec("""
                { "name": "close_batch", "args": [ { "var": "feed" }, { "expr": "${xldr.rowsLoaded}" } ] }
                """));

        try (var conn = getConnection(JDBC_URL);
             var stmt = conn.createStatement();
             var rs = stmt.executeQuery("select feed, rows_loaded, rows_seen from batch_log")) {
            assertTrue(rs.next(), "the transform did not run");
            assertAll(
                    () -> assertEquals("shipments", rs.getString("feed"),
                            "a var argument is the value the var had at the start of the load"),
                    () -> assertEquals(3, rs.getInt("rows_loaded"),
                            "${xldr.rowsLoaded} is what the loader inserted"),
                    () -> assertEquals(3, rs.getInt("rows_seen"),
                            "and the rows are visible on this connection, so it ran before the commit"));
            assertFalse(rs.next(), "once per load, not once per record");
        }
    }

    /**
     * And the other half of running inside the transaction: a procedure that
     * throws rolls back the rows the mappings inserted, so the file stays the
     * unit of work rather than becoming a load plus an afterthought.
     */
    @Test
    void aFailingTransformRollsTheLoadBack() throws Exception {
        schema();
        assertThrows(Exception.class, () -> load(spec("{ \"name\": \"explode\" }")));

        try (var conn = getConnection(JDBC_URL);
             var stmt = conn.createStatement();
             var rs = stmt.executeQuery("select count(*) from shipment")) {
            rs.next();
            assertEquals(0, rs.getInt(1),
                    "the transform failed, so nothing the load inserted may survive");
        }
    }

    /** the tables and the two procedures, fresh for each test */
    private static void schema() throws Exception {
        try (var conn = getConnection(JDBC_URL);
             var stmt = conn.createStatement()) {
            stmt.execute("drop alias if exists close_batch");
            stmt.execute("drop alias if exists explode");
            stmt.execute("drop table if exists shipment");
            stmt.execute("drop table if exists batch_log");
            stmt.execute("create table shipment(sku varchar(10))");
            stmt.execute("create table batch_log(feed varchar(20), rows_loaded int, rows_seen int)");
            stmt.execute(PROCEDURE);
            stmt.execute(FAILING_PROCEDURE);
        }
    }

    /**
     * The manual path - a loader and a loop - rather than {@code Loader.load},
     * because that is the path {@code xlet} takes and the one on which a
     * transform would have been easiest to forget.
     */
    private static void load(String spec) throws Exception {
        var mappingSpec = new JsonMappingSpecReader().read(stream(spec));
        var adapter = csvAdapterFor(mappingSpec.inputSpec());
        try (Connection connection = getConnection(JDBC_URL);
             var loader = new Loader(mappingSpec, connection, Map.of())) {
            for (var mapping : mappingSpec.recordMappingSpecs()) {
                loader.loadInput(adapter, new ByteArrayInputStream(CSV.getBytes(UTF_8)), mapping);
            }
        }
    }

    private static InputAdapter csvAdapterFor(InputSpec inputSpec) {
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
}
