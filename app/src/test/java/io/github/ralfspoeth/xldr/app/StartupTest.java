package io.github.ralfspoeth.xldr.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where the server looks for its configuration. A deployment is a directory -
 * the one the server is started in, or the one {@code --dir} names - and the
 * file in it is always called {@code xldr.properties}.
 * <p>
 * Only the refusals are exercised here: a configuration that is found starts a
 * server that runs until it is asked to stop, which is what {@code ServerIT}
 * does the long way round.
 * <p>
 * Both cases stop before a server exists - no roots to watch, no database, no
 * threads - so this belongs beside the command it tests rather than among the
 * integration tests, and runs under surefire.
 */
public class StartupTest {

    private static int run(StringWriter err, String... args) {
        var cmd = new CommandLine(new App()).setErr(new PrintWriter(err));
        return cmd.execute(args);
    }

    /**
     * A directory with no {@code xldr.properties} is refused by name, so an
     * operator who started the server in the wrong place is told which place
     * that was rather than being shown the usage text.
     */
    @Test
    void namesTheDirectoryItFoundNoConfigurationIn(@TempDir Path dir) {
        var err = new StringWriter();
        var exit = run(err, "--dir", dir.toString());

        assertEquals(CommandLine.ExitCode.USAGE, exit);
        assertTrue(err.toString().contains("xldr.properties"), err.toString());
        assertTrue(err.toString().contains(dir.toAbsolutePath().normalize().toString()), err.toString());
    }

    /**
     * The configuration is read from the named directory - far enough to report
     * what is wrong with it, which is proof the file was found and parsed.
     */
    @Test
    void readsTheConfigurationFromTheNamedDirectory(@TempDir Path dir) throws IOException {
        // no xldr.roots, so Config refuses it - and says so about this file
        Files.writeString(dir.resolve("xldr.properties"), "jdbc.url = jdbc:h2:mem:startupit\n");

        var err = new StringWriter();
        var exit = run(err, "-d", dir.toString());

        assertEquals(CommandLine.ExitCode.SOFTWARE, exit);
        assertTrue(err.toString().contains("xldr.roots"), err.toString());
    }
}
