package io.github.ralfspoeth.xldr.server;

import io.github.ralfspoeth.xldr.ldr.Loader;
import io.github.ralfspoeth.xldr.spec.MappingSpec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;

import static java.lang.System.Logger.Level.DEBUG;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Loads one file of one feed.
 * <p>
 * The loading itself is {@link Loader#load}, which both front ends share. What is
 * left here is what makes it a <em>feed's</em> file: the file's own name and
 * whatever the deployment put beside the spec - the ambient values of
 * {@value #ENV_FILE}, and the schema and catalog of
 * {@value TargetFile#FILE}.
 * <p>
 * All three are read per load rather than held on the feed. They are small files
 * read once per loaded file, and caching them would mean staleness checks beside
 * the spec's for no gain - so an edit reaches the next load with no reload of the
 * feed in between.
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
        var ambient = new HashMap<String, Object>();
        ambient.put("xldr.filename", file.getFileName().toString());
        ambient.putAll(environment());
        // read per load, as env.properties is and for the same reason: it is a
        // small file, and caching it would mean a second staleness check beside
        // the spec's for no gain
        var target = TargetFile.read(feedDirectory);
        // reopened per record mapping, which is the whole reason this takes a
        // source rather than a stream
        return Loader.load(mappingSpec, () -> Files.newInputStream(file),
                ambient, target, connectionSource.getConnection());
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

}
