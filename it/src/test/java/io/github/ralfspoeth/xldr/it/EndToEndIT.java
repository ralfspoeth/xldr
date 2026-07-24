package io.github.ralfspoeth.xldr.it;

import io.github.ralfspoeth.xldr.ia.InputAdapter;
import io.github.ralfspoeth.xldr.ia.InputAdapterFactory;
import io.github.ralfspoeth.xldr.ldr.Loader;
import io.github.ralfspoeth.xldr.spec.InputSpec;
import io.github.ralfspoeth.xldr.spec.MappingSpec;
import io.github.ralfspoeth.xldr.spec.io.JsonMappingSpecReader;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.ServiceLoader;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.sql.DriverManager.getConnection;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The whole toolkit end to end against a local H2 database: a JSON mapping spec
 * is read, the XML input adapter is discovered through {@code ServiceLoader},
 * {@code test1.xml} is parsed with XPath selectors, and the records are inserted
 * by the {@code Loader} - fields, a spec constant and a database function all in
 * one insert.
 */
public class EndToEndIT {

    private static final String JDBC_URL = "jdbc:h2:mem:it;DB_CLOSE_DELAY=-1";

    @Test
    public void loadsXmlThroughTheAdapterIntoH2() throws Exception {
        var spec = readSpec("spec1.json");

        try (var conn = getConnection(JDBC_URL);
             var stmt = conn.createStatement()) {
            stmt.execute("drop table if exists fund");
            stmt.execute("""
                    create table fund(
                        id_txt    varchar(20),
                        desc_txt  varchar(100),
                        source_cd varchar(10),
                        loaded_at timestamp
                    )""");
        }

        // one adapter for the file, discovered by MIME type through the service
        var adapter = adapterFor(spec.inputSpec());

        try (var loader = new Loader(spec, getConnection(JDBC_URL))) {
            for (var mapping : spec.recordMappingSpecs()) {
                try (var in = resource("test1.xml")) {
                    loader.loadInput(adapter, in, mapping);
                }
            }
        }

        var rows = new ArrayList<List<String>>();
        try (var conn = getConnection(JDBC_URL);
             var stmt = conn.createStatement();
             var rs = stmt.executeQuery(
                     "select id_txt, desc_txt, source_cd, loaded_at from fund order by id_txt, desc_txt")) {
            while (rs.next()) {
                rows.add(List.of(rs.getString(1), rs.getString(2)));
                // every row carries the constant and the function-populated column
                assertEquals("PD", rs.getString(3));
                assertNotNull(rs.getTimestamp(4));
            }
        }

        assertAll(
                () -> assertEquals(4, rows.size()),
                // /root/fund selects all four funds; a string field keeps "" for
                // the fund whose text is only whitespace around <position>
                () -> assertEquals(List.of("1234", ""), rows.get(0)),
                () -> assertEquals(List.of("1234", "interesting text"), rows.get(1)),
                () -> assertEquals(List.of("555", "Europa"), rows.get(2)),
                () -> assertEquals(List.of("777", "Asien"), rows.get(3))
        );
    }

    private static InputAdapter adapterFor(InputSpec inputSpec) {
        var factory = ServiceLoader.load(InputAdapterFactory.class)
                .stream()
                .map(ServiceLoader.Provider::get)
                .filter(f -> f.accepts(inputSpec))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no adapter for " + inputSpec.mimeType()));
        return factory.createInputAdapter(inputSpec);
    }

    private static MappingSpec readSpec(String name) throws IOException {
        try (var in = new InputStreamReader(resource(name), UTF_8)) {
            return new JsonMappingSpecReader().readFrom(in);
        }
    }

    private static InputStream resource(String name) {
        return Objects.requireNonNull(EndToEndIT.class.getResourceAsStream(name), name);
    }
}
