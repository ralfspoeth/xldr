package io.github.ralfspoeth.xldr.xlet;

import io.github.ralfspoeth.xldr.ia.InputAdapterFactory;
import io.github.ralfspoeth.xldr.ldr.Loader;
import io.github.ralfspoeth.xldr.ldr.Statistics;
import io.github.ralfspoeth.xldr.ldr.Target;
import io.github.ralfspoeth.xldr.spec.MappingSpec;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;

import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static java.lang.System.Logger.Level.*;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Loads one input, named by the {@code spec} parameter, from the request body.
 *
 * <pre>
 * POST &lt;context&gt;/&lt;servlet&gt;?spec=statements
 * Content-Type: text/csv
 * </pre>
 * <p>
 * The counterpart of moving a file into a feed's {@code in/} directory, and the
 * differences from doing so are in the README. In short: the request is the
 * delivery, so nothing here waits for a marker or claims by moving; and there is a
 * caller waiting, so a failure is a status code rather than a file in a hospital.
 * <p>
 * Deliberately not annotated with {@code @WebServlet}: where it answers is the
 * deployer's decision, made in {@code web.xml} along with whatever security
 * constraint belongs in front of it. Deliberately not {@code @MultipartConfig}
 * either - see {@link #doPost}.
 * <p>
 * One instance serves every request. Everything read in {@link #init()} is
 * immutable afterwards and the semaphore is thread-safe, so there is no per-request
 * state on this object and none is wanted.
 */
public class XldrServlet extends HttpServlet {

    private static final System.Logger LOG = System.getLogger(XldrServlet.class.getName());

    /**
     * The one content type refused outright, and the reason the {@code spec}
     * parameter is safe to read. For a form-encoded request the container answers
     * {@code getParameter} from the <em>body</em>, which it must read to do so,
     * leaving {@code getInputStream} empty and the load importing nothing at all.
     * Refusing it costs nothing: no adapter reads form-encoded data, so such a
     * request could never have loaded anything.
     */
    private static final String FORM_ENCODED = "application/x-www-form-urlencoded";

    static final String SPEC_PARAMETER = "spec";

    private SpecRegistry specs;
    private DataSource dataSource;
    private Map<String, Object> environment;
    /** the schema and catalog every load through this servlet writes into */
    private Target target;
    private Semaphore permits;
    private long acquireTimeoutMillis;
    private long maxBytes;
    private Statistics statistics;
    private XletStatus status;
    private AutoCloseable unregister;

    @Override
    public void init() throws ServletException {
        specs = SpecRegistry.read(getServletContext());
        dataSource = dataSource();
        environment = environment();
        target = target();
        refuseUnusableTarget();
        int concurrent = number("maxConcurrentLoads", 4);
        permits = new Semaphore(concurrent);
        acquireTimeoutMillis = number("acquireTimeoutMillis", 2_000);
        maxBytes = number("maxBytes", 64L * 1024 * 1024);
        statistics = new Statistics();
        status = new XletStatus(specs, statistics, concurrent, acquireTimeoutMillis, maxBytes);
        unregister = status.register(getServletContext().getContextPath(), getServletName());
        LOG.log(INFO, () -> "xldr ready: " + specs.names() + ", loading " + target
                + ", at most " + concurrent + " concurrent load(s), at most " + maxBytes + " bytes each");
    }

    /**
     * Unregisters the MXBean, which is the whole of what this servlet has to undo.
     * <p>
     * Not housekeeping: the platform {@code MBeanServer} outlives the web
     * application, so a bean left behind holds a strong reference to a class
     * loaded by this application's class loader, and every redeploy would then
     * leak that loader and everything under it. Nothing else here needs undoing -
     * the {@code DataSource} belongs to the container, the specs are immutable,
     * and a load in flight holds no state on this object.
     */
    @Override
    public void destroy() {
        try {
            unregister.close();
        } catch (Exception e) {
            LOG.log(WARNING, () -> "could not unregister the statistics bean: " + e);
        }
    }

    /**
     * What {@link #examine} made of a request: either the load it asks for, or the
     * refusal and the status to say it with.
     * <p>
     * A type for two cases is more than they would seem to need, and it earns that
     * in the checks rather than here. Every branch of {@code examine} has to produce
     * one of these, so the compiler settles what a chain of {@code reply(…); return;}
     * left to the reader - that each refusal really does stop the request. A missing
     * {@code return} used to be a 415 followed by a load.
     */
    private sealed interface Outcome {
        record Refused(int status, String message) implements Outcome {}

        record Accepted(String name, MappingSpec spec) implements Outcome {}
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        switch (examine(req)) {
            case Outcome.Refused(int code, String message) -> {
                // counted here and only here, which the sealed pair is what makes
                // possible: every refusal arrives at this one arm
                status.requestRefused();
                reply(resp, code, message);
            }
            case Outcome.Accepted(String name, MappingSpec spec) -> load(req, resp, name, spec);
        }
    }

    /**
     * The order of the checks is the order of their cost: everything answerable
     * from the request line and the headers is settled before a single byte of the
     * body is read, so a request that was never going to load anything is refused
     * without having been uploaded.
     * <p>
     * Nothing here touches the response, which is what lets the order be argued
     * about on its own.
     */
    private Outcome examine(HttpServletRequest req) {
        var pathInfo = req.getPathInfo();
        if (pathInfo != null && !pathInfo.equals("/")) {
            // only reachable under a wildcard mapping - an exact one leaves
            // .../load/extra to the container's default servlet - which is
            // exactly the deployment where ignoring a trailing path would teach
            // the next reader that the path means something
            return new Outcome.Refused(400,
                    "no path expected after the servlet's mapping, got " + pathInfo);
        }
        var contentType = baseType(req.getContentType());
        if (FORM_ENCODED.equals(contentType)) {
            return new Outcome.Refused(415, FORM_ENCODED + " is never an input: send the data as its own"
                    + " content type, with " + SPEC_PARAMETER + " in the query string");
        }
        var name = req.getParameter(SPEC_PARAMETER);
        if (name == null || name.isBlank()) {
            return new Outcome.Refused(400, "no " + SPEC_PARAMETER + " parameter; this deployment carries "
                    + specs.names());
        }
        var spec = specs.get(name);
        if (spec == null) {
            return new Outcome.Refused(404,
                    "no spec named '" + name + "'; this deployment carries " + specs.names());
        }
        // the spec chooses the adapter, as everywhere else in xldr; the request's
        // content type only has to be one that adapter reads. Asking the factory
        // rather than comparing strings lets a spec saying application/xml accept a
        // request saying text/xml, which is the adapter's business to know
        var factory = InputAdapterFactory.of(spec.inputSpec()).orElseThrow();
        if (contentType == null) {
            // a different mistake from offering the wrong one, and worth its own
            // sentence: "the request offered null" told nobody anything
            return new Outcome.Refused(415, "no Content-Type; spec '" + name + "' reads "
                    + spec.inputSpec().mimeType());
        }
        if (!factory.reads(contentType)) {
            return new Outcome.Refused(415, "spec '" + name + "' reads " + spec.inputSpec().mimeType()
                    + ", the request offered " + contentType);
        }
        long declared = req.getContentLengthLong();
        if (declared > maxBytes) {
            return new Outcome.Refused(413, "declared " + declared + " bytes, the limit is " + maxBytes);
        }
        return new Outcome.Accepted(name, spec);
    }

    /**
     * Under a permit, and only under a permit.
     * <p>
     * {@code tryAcquire} with a short wait rather than {@code acquire}: the file
     * server blocks indefinitely because a file in {@code in/} does not mind
     * waiting, while here a caller is waiting with a timeout of its own, and a
     * caller that gives up retries - which, loads being at-least-once, would put
     * the same data through twice. Better to say "busy" while somebody is still
     * listening.
     */
    private void load(HttpServletRequest req, HttpServletResponse resp, String name,
                      MappingSpec spec) throws IOException {
        boolean acquired;
        try {
            acquired = permits.tryAcquire(acquireTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // counted with the timeouts: both are this deployment turning a
            // request away rather than the caller having sent anything wrong,
            // and a 503 that appeared in no counter would make the totals lie
            status.loadRejected();
            reply(resp, 503, "interrupted while waiting to start");
            return;
        }
        if (!acquired) {
            status.loadRejected();
            resp.setHeader("Retry-After", "1");
            reply(resp, 503, "too many loads in progress; try again");
            return;
        }
        statistics.loadStarted();
        Path spooled = null;
        try {
            spooled = spool(req.getInputStream());
            var rows = load(spec, name, spooled);
            statistics.loaded(name, rows);
            LOG.log(INFO, () -> "loaded " + rows + " row(s) through '" + name + "'");
            reply(resp, 200, "loaded " + rows + " row(s)");
        } catch (BodyTooLarge e) {
            // refused rather than failed, and for the same reason the declared
            // length is: the body was never acceptable, and no load was attempted
            // - this one only found out while reading it
            status.requestRefused();
            reply(resp, 413, e.getMessage());
        } catch (IllegalArgumentException e) {
            // the input did not parse: the caller's data, not our configuration.
            // A load was attempted and did not finish, so it counts as a failure -
            // the counter says a load went wrong, not whose fault it was
            statistics.failed(name);
            LOG.log(WARNING, () -> "rejected input for '" + name + "': " + e);
            reply(resp, 400, String.valueOf(e.getMessage()));
        } catch (Exception e) {
            statistics.failed(name);
            LOG.log(ERROR, () -> "load failed for '" + name + "': " + e);
            reply(resp, 500, "the load failed and was rolled back: " + e);
        } finally {
            statistics.loadFinished();
            permits.release();
            delete(spooled);
        }
    }

    private int load(MappingSpec spec, String name, Path input)
            throws Exception {
        var ambient = new HashMap<>(environment);
        ambient.put("xldr.spec", name);
        return Loader.load(spec, () -> Files.newInputStream(input), ambient, target,
                dataSource.getConnection());
    }

    /**
     * The body, on disk, because a spec may carry several record mappings and each
     * is run over the whole input - a file can be reopened, a socket cannot.
     * <p>
     * Counted as it is written rather than trusted from {@code Content-Length}: a
     * client that declares nothing, or declares a lie, would otherwise fill the
     * disk. The declared length is checked too, but only as the cheap refusal that
     * saves the upload.
     */
    private Path spool(InputStream body) throws IOException {
        var file = Files.createTempFile("xlet-", ".input");
        try (var out = Files.newOutputStream(file)) {
            var buffer = new byte[8192];
            long total = 0;
            for (int read = body.read(buffer); read > 0; read = body.read(buffer)) {
                total += read;
                if (total > maxBytes) {
                    throw new BodyTooLarge("body exceeds the limit of " + maxBytes + " bytes");
                }
                out.write(buffer, 0, read);
            }
        } catch (IOException | RuntimeException e) {
            delete(file);
            throw e;
        }
        return file;
    }

    /**
     * Never left behind - not when the load fails, not when the body is too large,
     * and not when the client vanishes half way through the upload.
     */
    private static void delete(@Nullable Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOG.log(WARNING, () -> "could not delete " + file + ": " + e);
        }
    }

    /**
     * {@code text/csv; charset=UTF-8} is {@code text/csv} to an adapter.
     */
    private static @Nullable String baseType(@Nullable String contentType) {
        if (contentType == null) {
            return null;
        }
        int semicolon = contentType.indexOf(';');
        var base = semicolon < 0 ? contentType : contentType.substring(0, semicolon);
        return base.strip().toLowerCase(Locale.ROOT);
    }

    /**
     * What a spec's {@code ${env.…}} expressions resolve against: the container's
     * environment, which is the servlet's counterpart of the feed's
     * {@code env.properties}. Context-params first so that a servlet's own
     * init-params override them, the more specific winning.
     */
    private Map<String, Object> environment() {
        var env = new HashMap<String, Object>();
        var context = getServletContext();
        context.getInitParameterNames().asIterator().forEachRemaining(parameter -> {
            if (parameter.startsWith("env.")) {
                env.put(parameter, context.getInitParameter(parameter));
            }
        });
        getInitParameterNames().asIterator().forEachRemaining(parameter -> {
            if (parameter.startsWith("env.")) {
                env.put(parameter, getInitParameter(parameter));
            }
        });
        LOG.log(INFO, () -> "environment supplies " + new TreeSet<>(env.keySet()));
        return Map.copyOf(env);
    }

    /**
     * Where this deployment's tables live, from the {@code schema} and
     * {@code catalog} init-params - the servlet's counterpart of a feed's
     * {@code target.properties}, and named with the same two words on purpose.
     *
     * <pre>
     * &lt;init-param&gt;
     *     &lt;param-name&gt;schema&lt;/param-name&gt;
     *     &lt;param-value&gt;staging&lt;/param-value&gt;
     * &lt;/init-param&gt;
     * </pre>
     *
     * Both are optional and usually absent: a {@code DataSource} configured for
     * one application generally connects as a user whose search path already
     * finds its tables, which is why this went unmissed until the file server
     * grew the same setting.
     * <p>
     * Context-params first and a servlet's own init-params over them, as
     * {@link #environment} does - several xldr servlets in one application share
     * a database and usually a schema, and the one that does not says so itself.
     */
    private Target target() {
        return new Target(inherited("catalog"), inherited("schema"));
    }

    /**
     * Refuses at startup a {@code schema} or {@code catalog} this database will
     * not take, so that the servlet does not come up half-configured.
     * <p>
     * Everything else here is settled at initialisation or not at all, and this
     * was the one exception: whether a database accepts a catalog in an insert is
     * something only a connection can answer, and the loader gets one per
     * request - so PostgreSQL and a {@code catalog} init-param used to be a
     * {@code 500} on the first load, reported to a caller who had done nothing
     * wrong.
     * <p>
     * <strong>Only when a target is configured.</strong> A deployment that names
     * neither is the common one and asks the database nothing, which matters
     * beyond saving a round trip: the servlet has never taken a connection at
     * startup, so making it do so unconditionally would mean a database that
     * happens to be down at deploy time keeps the application from coming up at
     * all. That is a real change in behaviour and not one this setting is
     * entitled to make on everyone's behalf.
     */
    private void refuseUnusableTarget() throws ServletException {
        if (!target.isEmpty()) {
            try (var connection = dataSource.getConnection()) {
                Loader.refuseUnusableTarget(target, connection);
            } catch (SQLException e) {
                throw new ServletException(e.getMessage(), e);
            }
        }
    }

    /**
     * An init-param of this servlet, or of the context if the servlet is silent.
     * Blank counts as absent, so a half-edited {@code <param-value/>} is no
     * setting rather than a name made of nothing.
     */
    private @Nullable String inherited(String name) {
        var value = getInitParameter(name);
        if (value == null || value.isBlank()) {
            value = getServletContext().getInitParameter(name);
        }
        return value == null || value.isBlank() ? null : value.strip();
    }

    /**
     * The database this servlet loads into, by default the {@code DataSource} at
     * the JNDI name the {@code dataSource} init-param gives.
     * <p>
     * Protected because JNDI is the container's way and not the only way: a
     * deployment that has its {@code DataSource} from Spring, from a CDI producer
     * or from anywhere else overrides this and never touches a directory. It is
     * also the seam the tests use, which is worth admitting - but a method that
     * exists only for tests would be a different thing from one that happens to
     * suit them.
     * @throws ServletException whenever fromJndi throws
     */
    protected DataSource dataSource() throws ServletException {
        return fromJndi(parameter("dataSource", "java:comp/env/jdbc/xldr"));
    }

    private DataSource fromJndi(String jndiName) throws ServletException {
        try {
            if (new InitialContext().lookup(jndiName) instanceof DataSource found) {
                return found;
            }
            throw new ServletException(jndiName + " is not a DataSource");
        } catch (NamingException e) {
            throw new ServletException("no DataSource at " + jndiName
                    + "; name it with the 'dataSource' init-param", e);
        }
    }

    private String parameter(String name, String fallback) {
        var value = getInitParameter(name);
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    private long number(String name, long fallback) throws ServletException {
        var value = getInitParameter(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(value.strip());
        } catch (NumberFormatException e) {
            throw new ServletException(name + " must be a number, was: " + value);
        }
    }

    private int number(String name, int fallback) throws ServletException {
        return Math.toIntExact(number(name, (long) fallback));
    }

    private static void reply(HttpServletResponse resp, int status, String message) throws IOException {
        // setStatus and a plain body rather than sendError: the caller is a
        // program, and a container's HTML error page is of no use to it
        resp.setStatus(status);
        resp.setContentType("text/plain; charset=UTF-8");
        resp.setCharacterEncoding(UTF_8.name());
        resp.getWriter().println(message);
    }

    private static final class BodyTooLarge extends RuntimeException {
        BodyTooLarge(String message) {
            super(message);
        }
    }
}
