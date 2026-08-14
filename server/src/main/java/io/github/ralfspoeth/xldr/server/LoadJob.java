package io.github.ralfspoeth.xldr.server;

import io.github.ralfspoeth.xldr.ia.InputAdapter;
import io.github.ralfspoeth.xldr.ia.InputAdapterFactory;
import io.github.ralfspoeth.xldr.ldr.Loader;
import io.github.ralfspoeth.xldr.spec.InputSpec;
import io.github.ralfspoeth.xldr.spec.MappingSpec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;

import static java.lang.System.Logger.Level.DEBUG;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Loads one file according to one {@link MappingSpec}.
 * <p>
 * The sequence is: pick the input adapter factory that accepts the input spec's
 * MIME type, create a single adapter for the file, and then run every record
 * mapping against it - each with a freshly opened stream, since a stream is
 * read only once.
 * <p>
 * The whole file is one transaction: {@link Loader#close()} commits, or rolls
 * back if any mapping failed.
 * <p>
 * Expressions are given two sets of ambient values: {@code xldr.} for what this
 * job knows about the load, and {@code env.} for what the feed's deployment
 * supplies through {@value #ENV_FILE}.
 */
class LoadJob {

    private static final System.Logger LOG = System.getLogger(LoadJob.class.getName());

    /** read from the feed directory, beside the spec */
    static final String ENV_FILE = "env.properties";

    private final MappingSpec mappingSpec;
    private final Path feedDirectory;
    private final ConnectionSource connectionSource;

    public LoadJob(MappingSpec mappingSpec, Path feedDirectory, ConnectionSource connectionSource) {
        this.mappingSpec = mappingSpec;
        this.feedDirectory = feedDirectory;
        this.connectionSource = connectionSource;
    }

    /**
     * @return the total number of rows inserted across all record mappings
     */
    public int load(Path file) throws IOException, SQLException {
        var adapter = createInputAdapter(mappingSpec.inputSpec());
        var ambient = new HashMap<String, Object>();
        ambient.put("xldr.filename", file.getFileName().toString());
        ambient.putAll(environment());

        try (var connection = connectionSource.getConnection();
             var loader = new Loader(mappingSpec, connection, ambient)) {
            int total = 0;
            for (var mapping : mappingSpec.recordMappingSpecs()) {
                try (var in = Files.newInputStream(file)) {
                    total += loader.loadInput(adapter, in, mapping);
                }
            }
            return total;
        }
    }

    /**
     * The feed's {@code env.properties}, if it has one, with every key moved
     * under the {@code env.} prefix that expressions address it by.
     * <p>
     * The file holds what differs between deployments - a client number, a
     * source-system code, a default currency - which is why it lives beside the
     * spec instead of in it: the spec describes the file being read and is meant
     * to travel from test to production unchanged, and anything that must differ
     * between the two cannot be in it. It is not a second home for what the spec
     * could say itself.
     * <p>
     * Read per load rather than cached on the feed. It is a small file, read
     * once per loaded file, and caching it would mean a second staleness check
     * beside the spec's for no gain. An edit therefore reaches the next load,
     * with no reload of the feed in between.
     * <p>
     * Absent is normal and silent: a feed that needs nothing from its deployment
     * has no such file, and a spec referring to {@code env.} names then fails at
     * the load with the name it could not resolve - the honest error, since the
     * file being missing is the cause.
     *
     * @throws IOException if the file exists but cannot be read; the load then
     *                     fails and the input goes to the hospital rather than
     *                     being loaded with a value silently missing
     */
    private Map<String, String> environment() throws IOException {
        var envFile = feedDirectory.resolve(ENV_FILE);
        if (!Files.isRegularFile(envFile)) {
            return Map.of();
        }
        var props = new Properties();
        // read as UTF-8, not as the ISO-8859-1 that the stream overload assumes:
        // these values are written by hand and reach a database column verbatim
        try (var in = Files.newBufferedReader(envFile, UTF_8)) {
            props.load(in);
        }
        var env = new HashMap<String, String>();
        for (var name : props.stringPropertyNames()) {
            env.put("env." + name, props.getProperty(name));
        }
        LOG.log(DEBUG, () -> envFile + " supplied " + new TreeSet<>(env.keySet()));
        return env;
    }

    /**
     * One adapter serves every record mapping of the file - {@code parse} takes
     * the record selector as a parameter, so there is no reason to rebuild it
     * (and, for XML, recompile every XPath) per mapping.
     */
    private InputAdapter createInputAdapter(InputSpec inputSpec) {
        // the loader that defined the service, not the thread context one: see
        // MappingSpecReader#of, where the same lookup done the other way sent a
        // feed into a timeout with nothing wrong but the calling thread
        var factory = ServiceLoader.load(InputAdapterFactory.class, InputAdapterFactory.class.getClassLoader())
                .stream()
                .map(ServiceLoader.Provider::get)
                .filter(iaf -> iaf.reads(inputSpec))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "no input adapter for mime type " + inputSpec.mimeType()));
        return factory.createInputAdapter(inputSpec);
    }
}
