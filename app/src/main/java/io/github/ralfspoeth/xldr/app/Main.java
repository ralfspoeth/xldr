package io.github.ralfspoeth.xldr.app;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.logging.LogManager;

import static java.lang.System.Logger.Level.INFO;

/**
 * Entry point: starts the server and watches the configured roots until the
 * process is asked to stop.
 * <p>
 * Running the server is what the command does on its own, so a deployment keeps
 * invoking {@code xldr <config>}; {@code xldr validate <spec>} checks specs
 * instead, without a database or a server.
 */
@Command(
        name = "xldr",
        mixinStandardHelpOptions = true,
        versionProvider = Main.ManifestVersion.class,
        description = "Watches the configured roots and loads files that appear into the target database.",
        subcommands = Validate.class
)
public class Main implements Callable<Integer> {

    private static final System.Logger LOG = System.getLogger(Main.class.getName());

    /**
     * The version the jar was built as, rather than one written down twice and
     * kept in step by hand. It is absent when the classes are run from a build
     * directory, which is exactly when there is no release to name.
     */
    static class ManifestVersion implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            var version = Main.class.getPackage().getImplementationVersion();
            return new String[]{"xldr " + (version == null ? "(development build)" : version)};
        }
    }

    @Spec
    private CommandSpec spec;

    /**
     * Optional only so that a subcommand may be invoked without it; running the
     * server without a configuration is an error.
     */
    @Parameters(
            index = "0",
            arity = "0..1",
            paramLabel = "CONFIG",
            description = "the server configuration properties file (xldr.roots, jdbc.url, ...)"
    )
    private Path configFile;

    static void main(String[] args) {
        initLogging();
        System.exit(new CommandLine(new Main()).execute(args));
    }

    @Override
    public Integer call() throws Exception {
        if (configFile == null) {
            spec.commandLine().usage(System.err);
            return CommandLine.ExitCode.USAGE;
        }
        var config = AppConfig.load(configFile);
        try (var pool = new ConnectionPool(config);
             var watcher = new Watcher(config, pool)) {
            watcher.start();
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
     * Applies the bundled {@code logging.properties} unless the deployment
     * already points java.util.logging at a configuration of its own. slf4j
     * itself needs no setup - {@code slf4j-jdk14} is discovered as a service
     * provider - so configuring JUL configures everything.
     */
    private static void initLogging() {
        if (System.getProperty("java.util.logging.config.file") != null
                || System.getProperty("java.util.logging.config.class") != null) {
            return;
        }
        try (var in = Main.class.getResourceAsStream("/logging.properties")) {
            if (in != null) {
                LogManager.getLogManager().readConfiguration(in);
            }
        } catch (IOException e) {
            System.err.println("could not apply the bundled logging configuration: " + e);
        }
    }
}
