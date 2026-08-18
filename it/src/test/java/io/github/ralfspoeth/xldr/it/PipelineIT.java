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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.sql.DriverManager.getConnection;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The value-source machinery end to end against a local H2 database, in one
 * realistic load: a JSON spec is read, the CSV adapter is discovered through
 * {@code ServiceLoader}, and a discriminated headerless CSV is loaded with every
 * kind of source in play at once -
 * <ul>
 *   <li>a positional {@code fieldSelector} for the record's own columns;</li>
 *   <li>a {@code constant};</li>
 *   <li>a {@code var} evaluated once per load - itself an expression that
 *       interpolates {@code xldr.filename} and a {@code nextval} batch id;</li>
 *   <li>a per-row {@code expr} numbering the rows with {@code nextval};</li>
 *   <li>a per-row {@code expr} stamping {@code xldr.filename} onto each row;</li>
 *   <li>a {@code lookup} translating a code field to a surrogate key.</li>
 * </ul>
 */
public class PipelineIT {

    private static final String JDBC_URL = "jdbc:h2:mem:pipeline;DB_CLOSE_DELAY=-1";

    // record type 'L' in column 1; then sku, iso country code, quantity
    private static final String CSV = """
            L,S1,DE,10
            L,S2,US,5
            L,S3,DE,7
            """;

    private static final String SPEC = """
            {
              "input": {
                "mimeType": "text/csv",
                "properties": { "fieldSeparator": ",", "header": false },
                "vars": [
                  { "name": "loadId", "expr": "${xldr.filename}#${nextval('batch')}" }
                ],
                "recordSelectors": [
                  { "name": "line",
                    "discriminator": { "column": 1, "equals": "L" },
                    "fieldSelectors": [
                      { "name": "2", "column": 2 },
                      { "name": "3", "column": 3 },
                      { "name": "4", "column": 4 }
                  ] }
                ]
              },
              "mapping": [
                { "recordSelector": "line", "table": "order_line", "fieldMapping": [
                    { "var": "loadId",              "column": "load_ref" },
                    { "expr": "${nextval('line')}", "column": "line_no" },
                    { "constant": "PD",             "column": "source_cd" },
                    { "fieldSelector": "2",         "column": "sku" },
                    { "fieldSelector": "4",         "column": "qty" },
                    { "lookup": { "table": "country", "column": "id", "keyColumn": "iso",
                                  "fieldSelector": "3" }, "column": "country_id" },
                    { "expr": "${xldr.filename}",   "column": "loaded_from" }
                ] }
              ]
            }
            """;

    @Test
    public void loadsCsvWithVarsExpressionsAndLookups() throws Exception {
        try (var conn = getConnection(JDBC_URL);
             var stmt = conn.createStatement()) {
            stmt.execute("drop table if exists order_line");
            stmt.execute("drop table if exists country");
            stmt.execute("create table country(iso varchar(2), id int)");
            stmt.execute("insert into country values ('DE', 49), ('US', 1)");
            stmt.execute("""
                    create table order_line(
                        load_ref    varchar(40),
                        line_no     int,
                        source_cd   varchar(10),
                        sku         varchar(10),
                        qty         varchar(10),
                        country_id  int,
                        loaded_from varchar(40)
                    )""");
        }

        var spec = new JsonMappingSpecReader().read(stream(SPEC));
        var adapter = csvAdapterFor(spec.inputSpec());
        var ambient = Map.<String, Object>of("xldr.filename", "orders.csv");

        try (var loader = new Loader(spec, getConnection(JDBC_URL), ambient)) {
            for (var mapping : spec.recordMappingSpecs()) {
                loader.loadInput(adapter, new ByteArrayInputStream(CSV.getBytes(UTF_8)), mapping);
            }
        }

        var rows = new ArrayList<List<Object>>();
        try (var conn = getConnection(JDBC_URL);
             var stmt = conn.createStatement();
             var rs = stmt.executeQuery("""
                     select load_ref, line_no, source_cd, sku, qty, country_id, loaded_from
                     from order_line order by line_no""")) {
            while (rs.next()) {
                rows.add(List.of(
                        rs.getString("load_ref"),
                        rs.getInt("line_no"),
                        rs.getString("source_cd"),
                        rs.getString("sku"),
                        rs.getString("qty"),
                        rs.getInt("country_id"),
                        rs.getString("loaded_from")));
            }
        }

        assertEquals(
                List.of(
                        // load_ref is the once-per-load id (one batch draw), shared by every row;
                        // line_no counts rows; country_id is the looked-up surrogate key
                        List.of("orders.csv#1", 1, "PD", "S1", "10", 49, "orders.csv"),
                        List.of("orders.csv#1", 2, "PD", "S2", "5", 1, "orders.csv"),
                        List.of("orders.csv#1", 3, "PD", "S3", "7", 49, "orders.csv")
                ),
                rows);
    }

    /**
     * The dialect is in the spec, so the factory only has to be found - it holds
     * no state of its own.
     */
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
