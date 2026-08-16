package io.github.ralfspoeth.xldr.xlet.test;

import io.github.ralfspoeth.xldr.xlet.XldrServlet;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The servlet, without a container.
 * <p>
 * A container would be the wrong instrument for nearly all of this. What matters
 * here is the decisions - which requests are refused, and with which status - and
 * every one of them is made from five methods of the request, none of which needs a
 * socket, a deployment or a web server to answer. Only the two tests that really
 * load need a database, and they get an in-memory one.
 * <p>
 * Every refusal also asserts that no connection was taken. That is not ceremony:
 * each of them is settled before a connection is wanted, and one that quietly took
 * a connection anyway would be a leak nobody noticed until the pool ran dry. The
 * status code alone would not catch it.
 */
class XldrServletTest {

    private static final String JDBC_URL = "jdbc:h2:mem:xlettest;DB_CLOSE_DELAY=-1";

    private static final String PEOPLE_SPEC = """
            {
              "input": {
                "mimeType": "text/csv",
                "properties": { "fieldSeparator": "," },
                "recordSelectors": [
                  { "name": "people", "fieldSelectors": [
                      {"name": "id",   "selector": "id",   "type": "TEXT"},
                      {"name": "name", "selector": "name", "type": "TEXT"}
                  ] }
                ]
              },
              "mapping": [
                { "recordSelector": "people", "table": "person", "fieldMapping": [
                    {"fieldSelector": "id",   "column": "id"},
                    {"fieldSelector": "name", "column": "name"}
                ] }
              ]
            }
            """;

    /**
     * The same name in the other format, and valid as XML - a file holding JSON
     * under a {@code .xml} name would fail at the parse, and the collision test
     * would then pass for the wrong reason.
     */
    private static final String PEOPLE_SPEC_XML = """
            <?xml version='1.0'?>
            <mappingSpec>
                <input mimeType="text/csv"/>
            </mappingSpec>
            """;

    private static final byte[] TWO_PEOPLE = """
            id,name
            1,Alice
            2,Bob
            """.getBytes(UTF_8);

    /**
     * Exposes {@code doPost}, which is protected, and supplies the
     * {@code DataSource} through the seam {@link XldrServlet#dataSource()} exists
     * for. Calling {@code doPost} directly rather than {@code service} keeps these
     * tests about this servlet instead of about {@code HttpServlet}'s dispatch.
     */
    private static class Testable extends XldrServlet {
        private final DataSource dataSource;

        Testable(DataSource dataSource) {
            this.dataSource = dataSource;
        }

        @Override
        protected DataSource dataSource() {
            return dataSource;
        }

        void post(HttpServletRequest request, Fakes.Recorded response) throws IOException {
            doPost(request, response.response());
        }
    }

    @BeforeEach
    void freshDatabase() throws SQLException {
        try (var conn = DriverManager.getConnection(JDBC_URL);
             var stmt = conn.createStatement()) {
            stmt.execute("drop table if exists person");
            stmt.execute("create table person(id varchar(10), name varchar(50))");
        }
    }

    private static ServletConfig configWith(Map<String, String> specs, Map<String, String> initParams) {
        return Fakes.config(Fakes.context(specs, Map.of()), initParams);
    }

    private static Testable started(Map<String, String> initParams, Fakes.Counting dataSource) throws ServletException {
        var servlet = new Testable(dataSource.dataSource());
        servlet.init(configWith(Map.of(Fakes.SPECS + "people.json", PEOPLE_SPEC), initParams));
        return servlet;
    }

    // ---- what stops it starting ---------------------------------------------

    /**
     * Everything is refused at initialisation or not at all. A servlet that came up
     * half-configured would report the same problem as a 500 on the first request
     * needing the broken part - at the worst moment, and to the wrong person.
     */
    @Test
    void refusesToStartWithoutSpecs() {
        var servlet = new Testable(Fakes.dataSource(null).dataSource());
        var thrown = assertThrows(ServletException.class,
                () -> servlet.init(configWith(Map.of(), Map.of())));
        assertTrue(thrown.getMessage().contains(Fakes.SPECS), thrown.getMessage());
    }

    @Test
    void refusesASpecThatWillNotParse() {
        var servlet = new Testable(Fakes.dataSource(null).dataSource());
        var thrown = assertThrows(ServletException.class, () -> servlet.init(configWith(
                Map.of(Fakes.SPECS + "broken.json", "{ \"input\": "), Map.of())));
        assertTrue(thrown.getMessage().contains("broken.json"), thrown.getMessage());
    }

    /**
     * A spec whose format no module on the path reads would load nothing, and would
     * say so once per request forever. Better to refuse to deploy.
     */
    @Test
    void refusesASpecNoAdapterReads() {
        var servlet = new Testable(Fakes.dataSource(null).dataSource());
        var thrown = assertThrows(ServletException.class, () -> servlet.init(configWith(
                Map.of(Fakes.SPECS + "exotic.json", PEOPLE_SPEC.replace("text/csv", "application/x-nonesuch")),
                Map.of())));
        assertAll(
                () -> assertTrue(thrown.getMessage().contains("application/x-nonesuch"), thrown.getMessage()),
                () -> assertTrue(thrown.getMessage().contains("module path"), thrown.getMessage()));
    }

    /**
     * The base name is the spec's name, so {@code people.json} and
     * {@code people.xml} are one name claimed twice. Settling it by precedence
     * would mean loading through whichever the container happened to list first.
     */
    @Test
    void refusesTwoSpecsOfTheSameName() {
        var servlet = new Testable(Fakes.dataSource(null).dataSource());
        var thrown = assertThrows(ServletException.class, () -> servlet.init(configWith(
                Map.of(Fakes.SPECS + "people.json", PEOPLE_SPEC,
                        Fakes.SPECS + "people.xml", PEOPLE_SPEC_XML),
                Map.of())));
        assertAll(
                () -> assertTrue(thrown.getMessage().contains("people"), thrown.getMessage()),
                // for the collision, and not for a parse failure that came first
                () -> assertTrue(thrown.getMessage().contains("two specs"), thrown.getMessage()));
    }

    // ---- what it refuses to load --------------------------------------------

    @Test
    void refusesAPathBelowItsMapping() throws Exception {
        var dataSource = Fakes.dataSource(null);
        var response = new Fakes.Recorded();
        started(Map.of(), dataSource).post(
                Fakes.post("text/csv", Map.of("spec", "people"), TWO_PEOPLE, "/people", 20), response);
        assertEquals(400, response.status(), response.body());
        assertEquals(0, dataSource.connectionsTaken(), "no connection should have been taken");
        assertTrue(response.body().contains("/people"), response.body());
    }

    /**
     * The refusal that keeps {@code getParameter} honest: for a form-encoded
     * request the container reads the body to answer it, and the load would then
     * see an empty stream and report success over nothing at all.
     */
    @Test
    void refusesAFormEncodedRequest() throws Exception {
        var dataSource = Fakes.dataSource(null);
        var response = new Fakes.Recorded();
        started(Map.of(), dataSource).post(
                Fakes.post("application/x-www-form-urlencoded", Map.of("spec", "people"), TWO_PEOPLE), response);
        assertEquals(415, response.status(), response.body());
        assertEquals(0, dataSource.connectionsTaken(), "no connection should have been taken");
    }

    @Test
    void refusesARequestNamingNoSpec() throws Exception {
        var dataSource = Fakes.dataSource(null);
        var response = new Fakes.Recorded();
        started(Map.of(), dataSource).post(Fakes.post("text/csv", Map.of(), TWO_PEOPLE), response);
        assertEquals(400, response.status(), response.body());
        assertEquals(0, dataSource.connectionsTaken(), "no connection should have been taken");
        // the message lists what there is, so the caller can fix it
        assertTrue(response.body().contains("people"), response.body());
    }

    @Test
    void refusesASpecItDoesNotHave() throws Exception {
        var dataSource = Fakes.dataSource(null);
        var response = new Fakes.Recorded();
        started(Map.of(), dataSource).post(
                Fakes.post("text/csv", Map.of("spec", "salaries"), TWO_PEOPLE), response);
        assertEquals(404, response.status(), response.body());
        assertEquals(0, dataSource.connectionsTaken(), "no connection should have been taken");
        assertAll(
                () -> assertTrue(response.body().contains("salaries"), response.body()),
                () -> assertTrue(response.body().contains("people"), response.body()));
    }

    /**
     * The spec chooses the adapter; the request only has to offer something that
     * adapter reads. A CSV spec sent JSON is a mistake worth naming, rather than a
     * parse failure half way through the body.
     */
    @Test
    void refusesAContentTypeTheAdapterDoesNotRead() throws Exception {
        var dataSource = Fakes.dataSource(null);
        var response = new Fakes.Recorded();
        started(Map.of(), dataSource).post(
                Fakes.post("application/json", Map.of("spec", "people"), TWO_PEOPLE), response);
        assertEquals(415, response.status(), response.body());
        assertEquals(0, dataSource.connectionsTaken(), "no connection should have been taken");
        assertAll(
                () -> assertTrue(response.body().contains("text/csv"), response.body()),
                () -> assertTrue(response.body().contains("application/json"), response.body()));
    }

    @Test
    void refusesABodyThatDeclaresItselfTooLarge() throws Exception {
        var dataSource = Fakes.dataSource(null);
        var response = new Fakes.Recorded();
        started(Map.of("maxBytes", "10"), dataSource).post(
                Fakes.post("text/csv", Map.of("spec", "people"), TWO_PEOPLE, null, 5_000), response);
        assertEquals(413, response.status(), response.body());
        assertEquals(0, dataSource.connectionsTaken(), "no connection should have been taken");
    }

    /**
     * And one that declares nothing, or lies. The limit is enforced as the body is
     * written, so a client streaming forever fills no disk - and the refusal still
     * arrives before a connection is taken.
     */
    @Test
    void refusesABodyThatTurnsOutTooLarge() throws Exception {
        var dataSource = Fakes.dataSource(null);
        var response = new Fakes.Recorded();
        started(Map.of("maxBytes", "10"), dataSource).post(
                Fakes.post("text/csv", Map.of("spec", "people"), TWO_PEOPLE, null, -1L), response);
        assertEquals(413, response.status(), response.body());
        assertEquals(0, dataSource.connectionsTaken(), "no connection should have been taken");
        assertEquals(List.of(), rows(), "and nothing was loaded");
    }

    /**
     * With no permits at all every request is refused at once, which makes the
     * overload path deterministic. A caller is told to come back rather than left
     * to time out - a timeout becomes a retry, and a retry a second copy of the
     * same data.
     */
    @Test
    void refusesToQueueWhenThereIsNoRoom() throws Exception {
        var dataSource = Fakes.dataSource(null);
        var response = new Fakes.Recorded();
        started(Map.of("maxConcurrentLoads", "0", "acquireTimeoutMillis", "0"), dataSource)
                .post(Fakes.post("text/csv", Map.of("spec", "people"), TWO_PEOPLE), response);
        assertEquals(503, response.status(), response.body());
        assertEquals("1", response.header("Retry-After"));
        assertEquals(0, dataSource.connectionsTaken(), "no connection should have been taken");
    }

    // ---- and what it loads ---------------------------------------------------

    @Test
    void loadsTheBodyThroughTheNamedSpec() throws Exception {
        var response = new Fakes.Recorded();
        started(Map.of(), Fakes.dataSource(JDBC_URL))
                .post(Fakes.post("text/csv", Map.of("spec", "people"), TWO_PEOPLE), response);
        assertEquals(200, response.status(), response.body());
        assertAll(
                () -> assertTrue(response.body().contains("2"), response.body()),
                () -> assertEquals(List.of("1:Alice", "2:Bob"), rows()));
    }

    /**
     * The charset is the request's business and not the spec's, so a content type
     * carrying one is still the type the adapter was asked about.
     */
    @Test
    void ignoresTheCharsetOnTheContentType() throws Exception {
        var response = new Fakes.Recorded();
        started(Map.of(), Fakes.dataSource(JDBC_URL))
                .post(Fakes.post("text/csv; charset=UTF-8", Map.of("spec", "people"), TWO_PEOPLE), response);
        assertEquals(200, response.status(), response.body());
        assertEquals(2, rows().size(), response.body());
    }

    private static List<String> rows() throws SQLException {
        var found = new ArrayList<String>();
        try (var conn = DriverManager.getConnection(JDBC_URL);
             var stmt = conn.createStatement();
             var rs = stmt.executeQuery("select id, name from person order by id")) {
            while (rs.next()) {
                found.add(rs.getString(1) + ":" + rs.getString(2));
            }
        }
        return found;
    }
}
