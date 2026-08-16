package io.github.ralfspoeth.xldr.xlet;

import io.github.ralfspoeth.xldr.ia.InputAdapterFactory;
import io.github.ralfspoeth.xldr.spec.MappingSpec;
import io.github.ralfspoeth.xldr.spec.io.MappingSpecReader;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static java.lang.System.Logger.Level.INFO;

/**
 * The specs a deployment carries, read once from {@code /WEB-INF/specs/}.
 * <p>
 * This is the counterpart of the file server's {@code FeedRegistry}, minus almost
 * all of it. There a spec may appear, change or vanish at any moment, so the
 * registry is derived state kept in step with the file system by a watch and a
 * periodic scan. Here the specs arrive with the deployment and change only when it
 * is replaced, so the same state is read once and never reconciled - and a redeploy
 * is the reload.
 * <p>
 * They are read from the war rather than accepted over the wire on purpose. Table
 * and column names from a spec are concatenated into the SQL rather than bound, so
 * installing a spec is as privileged as editing the application's configuration;
 * under {@code /WEB-INF/} the container's own access control covers it, and a socket
 * would not.
 */
final class SpecRegistry {

    private static final System.Logger LOG = System.getLogger(SpecRegistry.class.getName());

    static final String DIRECTORY = "/WEB-INF/specs/";

    private final Map<String, MappingSpec> specs;

    private SpecRegistry(Map<String, MappingSpec> specs) {
        this.specs = specs;
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
    static SpecRegistry read(ServletContext context) throws ServletException {
        var resources = context.getResourcePaths(DIRECTORY);
        if (resources == null || resources.isEmpty()) {
            throw new ServletException("no mapping specs in " + DIRECTORY
                    + ": the deployment carries nothing to load with");
        }
        var specs = new LinkedHashMap<String, MappingSpec>();
        for (var resource : new TreeSet<>(resources)) {
            if (resource.endsWith("/")) {
                continue;
            }
            var name = nameOf(resource);
            // the resource path ends in .json or .xml, which is what the reader
            // dispatches on - so no content-type lookup is needed here
            var reader = MappingSpecReader.of(Path.of(resource)).orElseThrow(
                    () -> new ServletException(resource + ": no reader for this format;"
                            + " a spec is spec.json or spec.xml"));
            try (var in = context.getResourceAsStream(resource)) {
                if (in == null) {
                    throw new ServletException(resource + ": listed but not readable");
                }
                var spec = reader.read(in);
                requireAnAdapter(resource, spec);
                if (specs.put(name, spec) != null) {
                    throw new ServletException(name + ": two specs of that name in " + DIRECTORY
                            + "; the base name is the spec's name, so they collide");
                }
            } catch (IOException | RuntimeException e) {
                throw new ServletException(resource + ": " + e.getMessage(), e);
            }
        }
        LOG.log(INFO, () -> "loaded " + specs.size() + " mapping spec(s): " + specs.keySet());
        return new SpecRegistry(Map.copyOf(specs));
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
