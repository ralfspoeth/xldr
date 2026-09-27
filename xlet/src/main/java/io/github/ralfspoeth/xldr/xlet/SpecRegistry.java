package io.github.ralfspoeth.xldr.xlet;

import io.github.ralfspoeth.xldr.ia.InputAdapterFactory;
import io.github.ralfspoeth.xldr.spec.MappingSpec;
import io.github.ralfspoeth.xldr.spec.io.MappingSpecReader;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static java.lang.System.Logger.Level.INFO;
import static java.util.Objects.requireNonNullElse;

/**
 * The specs a deployment carries, read once at startup.
 * <p>
 * This is the counterpart of the file server's {@code FeedRegistry}, minus almost
 * all of it. There a spec may appear, change or vanish at any moment, so the
 * registry is derived state kept in step with the file system by a watch and a
 * periodic scan. Here the specs arrive with the deployment and change only when it
 * is replaced, so the same state is read once and never reconciled - and a redeploy
 * is the reload.
 *
 * <h2>Where they come from</h2>
 * {@code /WEB-INF/specs/} by default, and a directory named by the {@code specs}
 * parameter instead where a deployment gives one.
 * <p>
 * The default is the safer of the two and the reason is worth keeping in view.
 * Table and column names from a spec are concatenated into the SQL rather than
 * bound, so installing a spec is as privileged as editing the application's
 * configuration; under {@code /WEB-INF/} the container's own access control covers
 * it, and a socket would not.
 * <p>
 * <strong>A directory outside the war has no such cover</strong>, and the deployment
 * that names one takes that on: whatever can write there can decide what SQL this
 * servlet runs. It exists because {@code getResourcePaths} needs a war, and an
 * application packaged as an executable jar - Quarkus, Micronaut, a Spring Boot jar
 * - has no {@code /WEB-INF/} to put anything under, so without this the servlet
 * comes up carrying nothing and answers {@code 404} to everything. The same
 * delegation the {@code DataSource} already makes, with the same consequence: the
 * container's guarantee is exchanged for the deployer's.
 */
final class SpecRegistry {

    private static final System.Logger LOG = System.getLogger(SpecRegistry.class.getName());

    static final String DIRECTORY = "/WEB-INF/specs/";

    private final Map<String, MappingSpec> specs;

    private SpecRegistry(Map<String, MappingSpec> specs) {
        this.specs = Map.copyOf(specs);
    }

    /**
     * Reads every spec in {@value #DIRECTORY}, keyed by its base name.
     * <p>
     * Everything is refused here or not at all: a spec that will not parse, or one
     * whose MIME type no adapter on the module path reads, fails initialisation and
     * the servlet does not start. That is deliberate, and it is the same choice the
     * file server makes when it refuses to activate a feed - except that a feed is
     * one directory among many while a servlet is the whole application, so the
     * blast radius is larger and the message has to be worth reading. Coming up
     * half-configured would mean discovering the missing adapter as a 500 at three
     * in the morning instead of as a failure to deploy.
     *
     * @throws ServletException naming the resource and what was wrong with it
     */
    static SpecRegistry read(ServletContext context, @Nullable String directory)
            throws ServletException {
        var specs = directory == null
                ? fromWebInf(context)
                : fromDirectory(Path.of(directory));
        LOG.log(INFO, () -> "loaded " + specs.size() + " mapping spec(s) from "
                + requireNonNullElse(directory, DIRECTORY) + ": " + specs.keySet());
        return new SpecRegistry(specs);
    }

    /** the war's own, through the container, where the container protects them */
    private static Map<String, MappingSpec> fromWebInf(ServletContext context)
            throws ServletException {
        var resources = requireNonNullElse(context.getResourcePaths(DIRECTORY), Set.<String>of());
        if (resources.isEmpty()) {
            throw new ServletException("no mapping specs in " + DIRECTORY
                    + ": the deployment carries nothing to load with."
                    + " An application with no /WEB-INF/ - an executable jar rather than a war -"
                    + " names a directory in the 'specs' parameter instead");
        }
        var specs = new LinkedHashMap<String, MappingSpec>();
        for (var resource : new TreeSet<>(resources)) {
            if (resource.endsWith("/")) {
                continue;
            }
            try (var in = context.getResourceAsStream(resource)) {
                if (in == null) {
                    throw new ServletException(resource + ": listed but not readable");
                }
                put(specs, resource, in, DIRECTORY);
            } catch (IOException | RuntimeException e) {
                throw new ServletException(resource + ": " + e.getMessage(), e);
            }
        }
        return specs;
    }

    /**
     * A directory on disk, for a deployment that has no war to put them in.
     * <p>
     * Only the files directly in it, and only those a reader recognises.
     * Descending would make a spec's name ambiguous - the base name is the name -
     * and silently skipping an unreadable file would let a typo in an extension
     * become a {@code 404} much later.
     */
    private static Map<String, MappingSpec> fromDirectory(Path directory)
            throws ServletException {
        if (!Files.isDirectory(directory)) {
            throw new ServletException("the 'specs' parameter names " + directory
                    + ", which is not a directory");
        }
        var specs = new LinkedHashMap<String, MappingSpec>();
        try (var files = Files.list(directory)) {
            for (var file : files.filter(Files::isRegularFile).sorted().toList()) {
                try (var in = Files.newInputStream(file)) {
                    put(specs, file.toString(), in, directory.toString());
                }
            }
        } catch (IOException | RuntimeException e) {
            throw new ServletException(directory + ": " + e.getMessage(), e);
        }
        if (specs.isEmpty()) {
            throw new ServletException("no mapping specs in " + directory
                    + ": the deployment has nothing to load with");
        }
        return specs;
    }

    /**
     * One spec, read and checked the same way whichever it came from.
     * <p>
     * Everything is refused here or not at all: a spec that will not parse, or one
     * whose MIME type no adapter reads, fails initialisation and the servlet does
     * not start. That is deliberate, and it is the same choice the file server makes
     * when it refuses to activate a feed - except that a feed is one directory among
     * many while a servlet is the whole application, so the blast radius is larger
     * and the message has to be worth reading. Coming up half-configured would mean
     * discovering the missing adapter as a 500 at three in the morning instead of as
     * a failure to deploy.
     */
    private static void put(Map<String, MappingSpec> specs, String resource,
                            InputStream in, String where) throws ServletException {
        // the path ends in .json or .xml, which is what the reader dispatches on,
        // so no content-type lookup is needed here
        var reader = MappingSpecReader.of(Path.of(resource)).orElseThrow(
                () -> new ServletException(resource + ": no reader for this format;"
                        + " a spec is spec.json or spec.xml"));
        try {
            var spec = reader.read(in);
            requireAnAdapter(resource, spec);
            if (specs.put(nameOf(resource), spec) != null) {
                throw new ServletException(nameOf(resource) + ": two specs of that name in "
                        + where + "; the base name is the spec's name, so they collide");
            }
        } catch (IOException | RuntimeException e) {
            throw new ServletException(resource + ": " + e.getMessage(), e);
        }
    }

    private static void requireAnAdapter(String resource, MappingSpec spec) throws ServletException {
        if (InputAdapterFactory.of(spec.inputSpec()).isEmpty()) {
            throw new ServletException(resource + ": no input adapter reads "
                    + spec.inputSpec().mimeType() + "; is its module on the module path?");
        }
    }

    /**
     * {@code /WEB-INF/specs/statements.json} is the spec named {@code statements}.
     */
    private static String nameOf(String resource) {
        var file = resource.substring(resource.lastIndexOf('/') + 1);
        var dot = file.lastIndexOf('.');
        return dot > 0 ? file.substring(0, dot) : file;
    }

    /**
     * @return the spec of that name, or {@code null} if the deployment carries none
     */
    @Nullable MappingSpec get(String name) {
        return specs.get(name);
    }

    Set<String> names() {
        return specs.keySet();
    }
}
