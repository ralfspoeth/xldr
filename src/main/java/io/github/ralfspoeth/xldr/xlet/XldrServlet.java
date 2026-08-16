package io.github.ralfspoeth.xldr.xlet;

import io.github.ralfspoeth.xldr.ia.InputAdapterFactory;
import io.github.ralfspoeth.xldr.ldr.Loader;
import io.github.ralfspoeth.xldr.spec.MappingSpec;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static java.lang.System.Logger.Level.ERROR;
import static java.lang.System.Logger.Level.INFO;
import static java.lang.System.Logger.Level.WARNING;
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
    private Semaphore permits;
    private long acquireTimeoutMillis;
    private long maxBytes;

    @Override
    public void init() throws ServletException {
        specs = SpecRegistry.read(getServletContext());
        dataSource = dataSource(parameter("dataSource", "java:comp/env/jdbc/xldr"));
        environment = environment();
        int concurrent = number("maxConcurrentLoads", 4);
        permits = new Semaphore(concurrent);
        acquireTimeoutMillis = number("acquireTimeoutMillis", 2_000);
        maxBytes = number("maxBytes", 64L * 1024 * 1024);
        LOG.log(INFO, () -> "xldr ready: " + specs.names()
                + ", at most " + concurrent + " concurrent load(s), at most " + maxBytes + " bytes each");
    }

    /**
     * The order of the checks is the order of their cost: everything answerable
     * from the request line and the headers is settled before a single byte of the
     * body is read, so a request that was never going to load anything is refused
     * without having been uploaded.
     */
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        var pathInfo = req.getPathInfo();
        if (pathInfo != null && !pathInfo.equals("/")) {
            // ignoring it would teach the next reader that the path means something
            reply(resp, 400, "no path expected after the servlet's mapping, got " + pathInfo);
            return;
        }
        var contentType = baseType(req.getContentType());
        if (FORM_ENCODED.equals(contentType)) {
            reply(resp, 415, FORM_ENCODED + " is never an input: send the data as its own"
                    + " content type, with " + SPEC_PARAMETER + " in the query string");
            return;
        }
        var name = req.getParameter(SPEC_PARAMETER);
        if (name == null || name.isBlank()) {
            reply(resp, 400, "no " + SPEC_PARAMETER + " parameter; this deployment carries "
                    + specs.names());
            return;
        }
        var spec = specs.get(name);
        if (spec == null) {
            reply(resp, 404, "no spec named '" + name + "'; this deployment carries " + specs.names());
            return;
        }
        // the spec chooses the adapter, as everywhere else in xldr; the request's
        // content type only has to be one that adapter reads. Asking the factory
        // rather than comparing strings lets a spec saying application/xml accept a
        // request saying text/xml, which is the adapter's business to know
        var factory = InputAdapterFactory.of(spec.inputSpec()).orElseThrow();
        if (contentType == null || !factory.reads(contentType)) {
            reply(resp, 415, "spec '" + name + "' reads " + spec.inputSpec().mimeType()
                    + ", the request offered " + contentType);
            return;
        }
        long declared = req.getContentLengthLong();
        if (declared > maxBytes) {
            reply(resp, 413, "declared " + declared + " bytes, the limit is " + maxBytes);
            return;
        }
        load(req, resp, name, spec);
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
            reply(resp, 503, "interrupted while waiting to start");
            return;
        }
        if (!acquired) {
            resp.setHeader("Retry-After", "1");
            reply(resp, 503, "too many loads in progress; try again");
            return;
        }
        Path spooled = null;
        try {
            spooled = spool(req.getInputStream());
            var rows = load(spec, name, spooled);
            LOG.log(INFO, () -> "loaded " + rows + " row(s) through '" + name + "'");
            reply(resp, 200, "loaded " + rows + " row(s)");
        } catch (BodyTooLarge e) {
            reply(resp, 413, e.getMessage());
        } catch (IllegalArgumentException e) {
            // the input did not parse: the caller's data, not our configuration
            LOG.log(WARNING, () -> "rejected input for '" + name + "': " + e);
            reply(resp, 400, String.valueOf(e.getMessage()));
        } catch (Exception e) {
            LOG.log(ERROR, () -> "load failed for '" + name + "': " + e);
            reply(resp, 500, "the load failed and was rolled back: " + e);
        } finally {
            permits.release();
            delete(spooled);
        }
    }

    private int load(MappingSpec spec, String name, Path input)
            throws Exception {
        var ambient = new HashMap<>(environment);
        ambient.put("xldr.spec", name);
        return Loader.load(spec, () -> Files.newInputStream(input), ambient, dataSource.getConnection());
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
    private static void delete(Path file) {
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
    private static String baseType(String contentType) {
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

    private DataSource dataSource(String jndiName) throws ServletException {
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
