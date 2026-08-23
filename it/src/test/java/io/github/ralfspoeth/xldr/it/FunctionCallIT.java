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
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.sql.DriverManager.getConnection;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An {@code fn} against a real database, which is the only place the call
 * mechanism can be exercised at all: everything below the JDBC boundary -
 * {@code prepareCall}, the {@code {? = call name(?)}} escape, registering the
 * OUT parameter, the argument indices starting at two - is the driver's, and no
 * unit test in {@code spec} or {@code ldr} touches any of it.
 * <p>
 * The function is H2's built-in {@code FORMATDATETIME(timestamp, pattern)},
 * chosen because it needs no DDL and is not something xldr knows: it is called
 * the same way a stored function of your own would be, and its two arguments
 * cover both halves of what an argument may be - one is another var, evaluated
 * before this one, and the other a constant from the spec.
 * <p>
 * It also exercises the typing. {@code ${now()}} is an {@code Instant}, which
 * the loader binds as an {@code OffsetDateTime} because JDBC 4.2 does not
 * require a driver to accept an instant; the call is declared {@code TEXT} and
 * the OUT parameter registered as {@code VARCHAR}, so a driver that took the
 * type from somewhere else would come back with the wrong thing rather than
 * with nothing.
 */
class FunctionCallIT {

    private static final String JDBC_URL = "jdbc:h2:mem:functioncall;DB_CLOSE_DELAY=-1";

    private static final String CSV = """
            sku
            S1
            S2
            S3
            """;

    private static final String SPEC = """
            {
              "input": {
                "mimeType": "text/csv",
                "properties": { "fieldSeparator": ",", "header": true },
                "vars": [
                  { "name": "now", "expr": "${now()}" },
                  { "name": "loadedOn", "fn": {
                      "name": "FORMATDATETIME", "type": "TEXT",
                      "args": [ { "var": "now" }, { "constant": "yyyy-MM-dd" } ] } }
                ],
                "recordSelectors": [
                  { "name": "line", "fieldSelectors": [ { "name": "sku", "selector": "sku" } ] }
                ]
              },
              "mapping": [
                { "recordSelector": "line", "table": "shipment", "fieldMapping": [
                    { "fieldSelector": "sku", "column": "sku" },
                    { "var": "loadedOn",      "column": "loaded_on" }
                ] }
              ]
            }
            """;

    @Test
    void callsAdatabaseFunctionOncePerLoad() throws Exception {
        try (var conn = getConnection(JDBC_URL);
             var stmt = conn.createStatement()) {
            stmt.execute("drop table if exists shipment");
            stmt.execute("create table shipment(sku varchar(10), loaded_on varchar(20))");
        }

        var spec = new JsonMappingSpecReader().read(stream(SPEC));
        var adapter = csvAdapterFor(spec.inputSpec());

        // the call runs somewhere between these two, and on the stroke of
        // midnight the two differ - which is the only reason to take both
        var before = LocalDate.now();
        try (var loader = new Loader(spec, getConnection(JDBC_URL), Map.of())) {
            for (var mapping : spec.recordMappingSpecs()) {
                loader.loadInput(adapter, new ByteArrayInputStream(CSV.getBytes(UTF_8)), mapping);
            }
        }
        var after = LocalDate.now();

        var rows = new ArrayList<List<Object>>();
        try (var conn = getConnection(JDBC_URL);
             var stmt = conn.createStatement();
             var rs = stmt.executeQuery("select sku, loaded_on from shipment order by sku")) {
            while (rs.next()) {
                rows.add(List.of(rs.getString("sku"), rs.getString("loaded_on")));
            }
        }

        assertEquals(List.of("S1", "S2", "S3"), rows.stream().map(List::getFirst).toList());

        // one call, not one per row: three rows carrying one answer between them
        var loadedOn = new LinkedHashSet<>(rows.stream().map(List::getLast).toList());
        assertEquals(1, loadedOn.size(), "a call is made once per load, so every row carries the same answer");

        var iso = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        var stamped = loadedOn.iterator().next();
        assertTrue(
                List.of(before.format(iso), after.format(iso)).contains(stamped),
                () -> "the pattern the spec passed is the pattern the database applied, to the instant"
                        + " the other var held - expected " + before.format(iso) + ", got " + stamped);
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
