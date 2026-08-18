package io.github.ralfspoeth.xldr.app;

import io.github.ralfspoeth.xldr.server.Config;
import io.github.ralfspoeth.xldr.server.Watcher;

import org.jspecify.annotations.Nullable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.logging.LogManager;

import static java.lang.System.Logger.Level.INFO;

/**
 * Entry point: starts the server and watches the configured roots until the
 * process is asked to stop.
 * <p>
 * Running the server is what the command does, and all it does: {@code xldr}
 * reads {@code xldr.properties} from the working directory, or from the one named
 * by {@code --dir}.
 * <p>
 * There was a {@code validate} subcommand, removed in 0.30. What it checked has
 * since moved to the places that know: an adapter refuses a selector naming no
 * column of the file, {@code SpecRegistry} refuses a spec the deployment cannot
 * load, and a feed that cannot activate says so. Those are earlier, or more
 * authoritative, than a command somebody has to remember to run. What was left
 * was one heuristic - a record selector's discriminator beside a header - and it
 * was wrong often enough to argue about, a headed file being perfectly entitled
 * to carry a type column.
 */
@Command(
        name = "xldr",
        mixinStandardHelpOptions = true,
        versionProvider = App.ManifestVersion.class,
        description = "Watches the configured roots and loads files that appear into the target database."
)
public class App implements Callable<Integer> {

    private static final System.Logger LOG = System.getLogger(App.class.getName());

    /**
     * The version the jar was built as, rather than one written down twice and
     * kept in step by hand. It is absent when the classes are run from a build
     * directory, which is exactly when there is no release to name.
     */
    static class ManifestVersion implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            var version = App.class.getPackage().getImplementationVersion();
            return new String[]{"xldr " + (version == null ? "(development build)" : version)};
        }
    }

    /**
     * the configuration a deployment writes, looked for in the working directory
     */
    private static final String CONFIG_FILE = "xldr.properties";

    /**
     * optional beside it, and shipped in {@code conf/} as the fallback
     */
    private static final String LOGGING_FILE = "logging.properties";

    /**
     * Where the distribution was unpacked, set by the launcher. Absent when the
     * classes are run from a build directory, where there is no {@code conf/}
     * to fall back on either.
     */
    private static final String HOME_PROPERTY = "xldr.home";

    @Spec
    @Nullable
    private CommandSpec spec;

    @Option(
            names = {"-d", "--dir"},
            paramLabel = "DIR",
            defaultValue = ".",
            description = "the directory holding " + CONFIG_FILE + ", and optionally "
                    + LOGGING_FILE + "; the working directory by default"
    )
    @Nullable
    private Path directory;

    void main(String[] args) {
        System.exit(new CommandLine(this).execute(args));
    }

    @Override
    public Integer call() throws Exception {
        assert directory != null;
        var configFile = directory.resolve(CONFIG_FILE);
        if (!Files.isRegularFile(configFile)) {
            assert spec != null;
            spec.commandLine().getErr().println("no " + CONFIG_FILE + " in "
                    + directory.toAbsolutePath().normalize()
                    + " - name another directory with --dir, or start the server from the one holding it");
            return CommandLine.ExitCode.USAGE;
        }
        initLogging(directory);
        var config = Config.load(configFile);
        // the watcher needs no name: it works on its own threads, and all this
        // block wants from it is that it be closed when the process stops
        try (var pool = new ConnectionPool(config);
             var _ = Watcher.watch(config, pool)) {
            awaitShutdown();
            LOG.log(INFO, "shutting down");
        }
        return 0;
    }

    /**
     * Blocks until the JVM is asked to terminate, so that the loads in flight
     * finish inside the try-with-resources above rather than being cut off.
     */
    private static void awaitShutdown() throws InterruptedException {
        var stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(stopped::countDown, "xldr-shutdown"));
        stopped.await();
    }

    /**
     * Configures logging from the first of these that exists: whatever the
     * deployment already pointed java.util.logging at, a {@code
     * logging.properties} beside the server configuration, the one shipped in
     * the distribution's {@code conf/}, and finally the copy bundled in the jar.
     * <p>
     * So a deployment tunes its logging by dropping a file next to its
     * {@code xldr.properties}, and one that does not gets what the distribution
     * ships. slf4j needs no setup of its own - {@code slf4j-jdk14} is discovered
     * as a service provider - so configuring JUL configures everything.
     */
    private static void initLogging(Path directory) {
        if (System.getProperty("java.util.logging.config.file") == null
                && System.getProperty("java.util.logging.config.class") == null) {
            for (var candidate : configuredLogging(directory)) {
                if (Files.isRegularFile(candidate)) {
                    try (var in = Files.newInputStream(candidate)) {
                        LogManager.getLogManager().readConfiguration(in);
                        return;
                    } catch (IOException e) {
                        System.err.println("could not read " + candidate + ": " + e);
                    }
                }
            }
            try (var in = App.class.getResourceAsStream("/" + LOGGING_FILE)) {
                if (in != null) {
                    LogManager.getLogManager().readConfiguration(in);
                }
            } catch (IOException e) {
                System.err.println("could not apply the bundled logging configuration: " + e);
            }
        }
    }

    /**
     * The files to try, nearest first: the deployment's own, then the
     * distribution's. The installation is only known when a launcher said where
     * it is.
     */
    private static List<Path> configuredLogging(Path directory) {
        var home = System.getProperty(HOME_PROPERTY);
        var beside = directory.resolve(LOGGING_FILE);
        return home == null
                ? List.of(beside)
                : List.of(beside, Path.of(home, "conf", LOGGING_FILE));
    }
}

