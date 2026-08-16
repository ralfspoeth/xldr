package io.github.ralfspoeth.xldr.xlet.test;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.DriverManager;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Just enough of the servlet API to call the servlet with, out of dynamic proxies.
 * <p>
 * Mockito was tried here and abandoned. Not on principle - a test-scope dependency
 * costs nothing at runtime - but because it did not work: its subclass mock maker
 * defines mocks by reflective class injection, which JPMS refuses, and its inline
 * maker needs a java agent that the JVM increasingly will not let it attach. Both
 * of those exist to mock final classes and static methods, which nothing here does.
 * The compatibility tax was for powers we had already declined.
 * <p>
 * {@link Proxy} has none of that: it makes proxies for interfaces, which is all
 * that is wanted, it is in the JDK, and it is forty lines. What has to be stood in
 * for is small anyway - five methods of a request, three of a response - because the
 * servlet was written to ask the container for as little as possible.
 * <p>
 * Every method not named here returns the type's default: {@code null}, zero or
 * false. Deliberately, not lazily. A test that comes to depend on some other part
 * of the request will see that default and fail, which is the moment to decide
 * whether the servlet ought to be asking.
 */
final class Proxies {

    static final String SPECS = "/WEB-INF/specs/";

    private Proxies() {
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Map<String, Object> byMethodName) {
        InvocationHandler handler = (_, method, args) -> {
            var answer = byMethodName.get(method.getName());
            if (answer instanceof Answer a) {
                return a.to(args);
            }
            return answer != null ? answer : defaultOf(method.getReturnType());
        };
        return (T) Proxy.newProxyInstance(Proxies.class.getClassLoader(), new Class<?>[]{type}, handler);
    }

    /**
     * For the methods whose answer depends on the argument, like
     * {@code getParameter}.
     * <p>
     * {@code throws Throwable} because some of them do - opening a connection
     * throws {@code SQLException} - and {@link InvocationHandler#invoke} is
     * declared to let anything through, so wrapping it here would gain nothing and
     * lose a stack trace.
     */
    @FunctionalInterface
    interface Answer {
        Object to(Object[] args) throws Throwable;
    }

    private static Object defaultOf(Class<?> returnType) {
        if (!returnType.isPrimitive()) {
            return null;
        }
        return switch (returnType.getName()) {
            case "boolean" -> false;
            case "long" -> 0L;
            case "int" -> 0;
            case "void" -> null;
            default -> 0;
        };
    }

    // ---- context and config -------------------------------------------------

    /**
     * A context whose {@value #SPECS} holds the given resources, keyed by their
     * full resource path.
     * <p>
     * {@code getResourceAsStream} answers with a new stream each time rather than
     * a fixed one: a stream handed out twice is empty the second time.
     */
    static ServletContext context(Map<String, String> specsByResourcePath, Map<String, String> initParams) {
        var paths = new LinkedHashSet<>(specsByResourcePath.keySet());
        return proxy(ServletContext.class, Map.of(
                "getResourcePaths", (Answer) args -> SPECS.equals(args[0]) ? paths : null,
                "getResourceAsStream", (Answer) args -> {
                    var body = specsByResourcePath.get((String) args[0]);
                    return body == null ? null : new ByteArrayInputStream(body.getBytes(UTF_8));
                },
                "getInitParameterNames", (Answer) _ -> Collections.enumeration(initParams.keySet()),
                "getInitParameter", (Answer) args -> initParams.get((String) args[0])
        ));
    }

    static ServletConfig config(ServletContext context, Map<String, String> initParams) {
        return proxy(ServletConfig.class, Map.of(
                "getServletContext", context,
                "getServletName", "xldr",
                "getInitParameterNames", (Answer) _ -> Collections.enumeration(initParams.keySet()),
                "getInitParameter", (Answer) args -> initParams.get((String) args[0])
        ));
    }

    // ---- request ------------------------------------------------------------

    static HttpServletRequest post(String contentType, Map<String, String> parameters, byte[] body) {
        return post(contentType, parameters, body, null, body == null ? -1L : body.length);
    }

    static HttpServletRequest post(String contentType, Map<String, String> parameters, byte[] body,
                                   String pathInfo, long declaredLength) {
        var bytes = body == null ? new byte[0] : body;
        var answers = new HashMap<String, Object>();
        answers.put("getMethod", "POST");
        answers.put("getPathInfo", pathInfo);
        answers.put("getContentType", contentType);
        answers.put("getContentLengthLong", declaredLength);
        answers.put("getParameter", (Answer) args -> parameters.get((String) args[0]));
        answers.put("getInputStream", (Answer) _ -> body(bytes));
        return proxy(HttpServletRequest.class, answers);
    }

    /**
     * A real stream. {@link ServletInputStream} is an abstract class over an actual
     * read loop, and the servlet drains it in 8k blocks - answering that call by
     * call would be restating {@code InputStream}'s contract rather than testing
     * anything.
     */
    private static ServletInputStream body(byte[] bytes) {
        var source = new ByteArrayInputStream(bytes);
        return new ServletInputStream() {
            @Override
            public int read() {
                return source.read();
            }

            @Override
            public int read(byte @NonNull [] buffer, int offset, int length) {
                return source.read(buffer, offset, length);
            }

            @Override
            public boolean isFinished() {
                return source.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener listener) {
                throw new UnsupportedOperationException("this servlet reads the body itself");
            }
        };
    }

    // ---- response -----------------------------------------------------------

    /**
     * A response that keeps what was written to it, which is the whole of what the
     * tests ask about.
     */
    static final class Recorded {
        private int status = 200;
        private final Map<String, String> headers = new LinkedHashMap<>();
        private final StringWriter written = new StringWriter();
        private final PrintWriter writer = new PrintWriter(written);
        private final HttpServletResponse response;

        Recorded() {
            response = proxy(HttpServletResponse.class, Map.of(
                    "setStatus", (Answer) args -> {
                        status = (int) args[0];
                        return null;
                    },
                    "setHeader", (Answer) args -> {
                        headers.put((String) args[0], (String) args[1]);
                        return null;
                    },
                    "getWriter", (Answer) _ -> writer
            ));
        }

        HttpServletResponse response() {
            return response;
        }

        int status() {
            return status;
        }

        String header(String name) {
            return headers.get(name);
        }

        String body() {
            writer.flush();
            return written.toString();
        }
    }

    // ---- data source --------------------------------------------------------

    /**
     * A {@code DataSource} that counts what it hands out.
     * <p>
     * The count is what the refusal tests assert on: every refusal is settled
     * before a connection is wanted, and one that quietly took a connection anyway
     * would be a leak nobody noticed until the pool ran dry. Asserting the status
     * alone would not catch it.
     *
     * @param jdbcUrl where connections come from, or {@code null} for one that is
     *                not expected to be asked - it then fails loudly rather than
     *                handing back a null the servlet would report as a 500
     */
    static Counting dataSource(String jdbcUrl) {
        return new Counting(jdbcUrl);
    }

    static final class Counting {
        private final AtomicInteger taken = new AtomicInteger();
        private final DataSource dataSource;

        private Counting(String jdbcUrl) {
            dataSource = proxy(DataSource.class, Map.of(
                    "getConnection", (Answer) _ -> {
                        taken.incrementAndGet();
                        if (jdbcUrl == null) {
                            throw new IllegalStateException("no database was configured for this test");
                        }
                        return DriverManager.getConnection(jdbcUrl);
                    }
            ));
        }

        DataSource dataSource() {
            return dataSource;
        }

        int connectionsTaken() {
            return taken.get();
        }
    }
}
