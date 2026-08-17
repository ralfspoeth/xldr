package io.github.ralfspoeth.xldr.xlet.it;

import io.github.ralfspoeth.xldr.xlet.XldrServlet;
import org.eclipse.jetty.ee11.servlet.ServletContextHandler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The servlet in a real container, over a real socket.
 * <p>
 * {@code XldrServletTest} covers the decisions; this covers the assumptions
 * underneath them, which are the things no proxy can settle on the container's
 * behalf:
 * <ul>
 *   <li>that {@code /WEB-INF/specs/} is found through
 *       {@link jakarta.servlet.ServletContext#getResourcePaths}, from a directory
 *       laid out as a web application rather than a map the test wrote;</li>
 *   <li>that {@code getParameter} answers from the query string without touching a
 *       body that is not form-encoded - the whole reason the spec is named the way
 *       it is;</li>
 *   <li>that a body arrives whole through a socket and a real
 *       {@code ServletInputStream}, in as many reads as the container feels like;</li>
 *   <li>and that {@code getPathInfo} is null at the servlet's own mapping and set
 *       below it, which is what the 400 depends on - and that it is a wildcard
 *       mapping which makes that so, an exact one leaving the request to the
 *       default servlet and a 405. That is the sort of thing a proxy returning
 *       whatever the test told it to return will never point out.</li>
 * </ul>
 * <p>
 * Jetty rather than Tomcat: it is built to be embedded, a server on an ephemeral
 * port is three lines, and {@code ServletContextHandler} takes a resource base
 * directly - which is exactly the thing being tested here.
 * <p>
 * The {@code DataSource} still comes from {@link XldrServlet}'s
 * {@code dataSource()} seam rather than from JNDI, and deliberately so: a JNDI environment inside Jetty
 * would test Jetty's naming, and an {@code InitialContextFactory} of our own would test the
 * two {@code throw} statements in the lookup at the price of a global system
 * property. The lookup is three lines with no branch of its own - the container
 * either has the name or it does not, and it says which at startup.
 */
class XldrServletIT {

    private static final String JDBC_URL = "jdbc:h2:mem:xletit;DB_CLOSE_DELAY=-1";
    private static final String CONTEXT = "/xldr";

    private static Server server;
    private static URI endpoint;

    /**
     * A servlet whose database is this test's, everything else being the real
     * thing the container builds.
     */
    public static class Deployed extends XldrServlet {
        @Override
        protected DataSource dataSource() {
            return (DataSource) java.lang.reflect.Proxy.newProxyInstance(
                    Deployed.class.getClassLoader(),
                    new Class<?>[]{DataSource.class},
                    (_, method, _) -> "getConnection".equals(method.getName())
                            ? DriverManager.getConnection(JDBC_URL)
                            : null);
        }
    }

    @BeforeAll
    static void startServer() throws Exception {
        createTable();
        server = new Server();
        var connector = new ServerConnector(server);
        connector.setPort(0);   // whatever is free; the test asks afterwards
        server.addConnector(connector);

        var context = new ServletContextHandler(CONTEXT);
        // the point of this test: a real web application layout, so that
        // /WEB-INF/specs/ is resolved by the container and not by a proxy
        context.setBaseResourceAsPath(Path.of("src/test/webapp").toAbsolutePath());
        // a wildcard mapping, because an exact one makes the path-info check
        // untestable and pointless at once: with /load, a request to /load/extra
        // never reaches this servlet at all - it falls to the container's default
        // servlet, which answers a POST with 405. Path info exists only under a
        // mapping like this one, which is also the mapping the check is for
        context.addServlet(Deployed.class, "/load/*");
        server.setHandler(context);

        server.start();
        endpoint = URI.create("http://localhost:" + connector.getLocalPort() + CONTEXT + "/load");
    }

    @AfterAll
    static void stopServer() throws Exception {
        if (server != null) {
            server.stop();
        }
    }

    @BeforeEach
    void emptyTable() throws SQLException {
        try (var conn = DriverManager.getConnection(JDBC_URL);
             var stmt = conn.createStatement()) {
            stmt.execute("delete from person");
        }
    }

    private static void createTable() throws SQLException {
        try (var conn = DriverManager.getConnection(JDBC_URL);
             var stmt = conn.createStatement()) {
            stmt.execute("drop table if exists person");
            stmt.execute("create table person(id varchar(10), name varchar(50))");
        }
    }

    private static HttpResponse<String> post(String query, String contentType, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(endpoint + query))
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofString(body, UTF_8))
                .build();
        try (var client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString(UTF_8));
        }
    }

    /**
     * The whole chain: a spec the container found in the war, a body over a
     * socket, rows in the database.
     */
    @Test
    void loadsAPostedBodyThroughASpecFoundInTheWar() throws Exception {
        var response = post("?spec=people", "text/csv", """
                id,name
                1,Alice
                2,Bob
                """);
        assertAll(
                () -> assertEquals(200, response.statusCode(), response.body()),
                () -> assertTrue(response.body().contains("2"), response.body()),
                () -> assertEquals(List.of("1:Alice", "2:Bob"), rows()));
    }

    /**
     * Big enough to arrive in more than one read, which a {@code ByteArrayInputStream}
     * never is. If the spooling loop mishandled a partial read this is where it
     * would show.
     */
    @Test
    void loadsABodyLargerThanOneRead() throws Exception {
        var many = new StringBuilder("id,name\n");
        for (int i = 1; i <= 5_000; i++) {
            many.append(i).append(",name").append(i).append('\n');
        }
        var response = post("?spec=people", "text/csv", many.toString());
        assertAll(
                () -> assertEquals(200, response.statusCode(), response.body()),
                () -> assertEquals(5_000, rows().size()));
    }

    /**
     * The assumption the whole parameter-versus-path decision rests on: for a body
     * that is not form-encoded, the container answers {@code getParameter} from the
     * query string and leaves the body alone.
     */
    @Test
    void readsTheSpecFromTheQueryStringWithoutEatingTheBody() throws Exception {
        var response = post("?spec=people", "text/csv", "id,name\n7,Solo\n");
        assertAll(
                () -> assertEquals(200, response.statusCode(), response.body()),
                () -> assertEquals(List.of("7:Solo"), rows(), "the body survived being asked for a parameter"));
    }

    @Test
    void refusesAFormEncodedRequest() throws Exception {
        var response = post("?spec=people", "application/x-www-form-urlencoded", "id,name\n1,Alice\n");
        assertAll(
                () -> assertEquals(415, response.statusCode(), response.body()),
                () -> assertEquals(List.of(), rows()));
    }

    /**
     * Path info is null at the servlet's own mapping and set below it - the
     * assumption the 400 is built on, and one only a container can confirm.
     */
    @Test
    void refusesAPathBelowTheMapping() throws Exception {
        var response = post("/extra?spec=people", "text/csv", "id,name\n1,Alice\n");
        assertAll(
                () -> assertEquals(400, response.statusCode(), response.body()),
                () -> assertTrue(response.body().contains("/extra"), response.body()),
                () -> assertEquals(List.of(), rows()));
    }

    @Test
    void refusesASpecTheWarDoesNotCarry() throws Exception {
        var response = post("?spec=salaries", "text/csv", "id,name\n1,Alice\n");
        assertAll(
                () -> assertEquals(404, response.statusCode(), response.body()),
                () -> assertTrue(response.body().contains("people"), response.body()));
    }

    private static List<String> rows() throws SQLException {
        var found = new ArrayList<String>();
        try (var conn = DriverManager.getConnection(JDBC_URL);
             var stmt = conn.createStatement();
             var rs = stmt.executeQuery("select id, name from person order by cast(id as int)")) {
            while (rs.next()) {
                found.add(rs.getString(1) + ":" + rs.getString(2));
            }
        }
        return found;
    }
}
