package io.github.ralfspoeth.xldr.server;

import io.github.ralfspoeth.filews.DirectoryWatchService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Building feed directories on disk, for the tests that need one.
 * <p>
 * The names are written out here rather than read from {@code Feed}, which is
 * package-private and stays that way. That is not a workaround: {@code in/},
 * {@code work/}, {@code archive/} and {@code hospital/} are what a producer and
 * an operator see, so a test that spells them out is pinning the layout a
 * deployment depends on. Reading them from the class under test would assert
 * only that it agrees with itself.
 */
final class Feeds {

    static final String DELIVERY = "delivery.properties";
    static final List<String> SUBDIRECTORIES = List.of("in", "work", "archive", "hospital");

    private Feeds() {
    }

    /** a watch service that watches nothing until asked, and never runs its loop */
    static DirectoryWatchService watchService() throws IOException {
        return new DirectoryWatchService(_ -> {
        }, List.of());
    }

    /** a directory that is not a feed: no {@code delivery.properties} */
    static Path bare(Path root, String name) throws IOException {
        return Files.createDirectories(root.resolve(name));
    }

    /** a feed with a delivery but no spec, which leaves it pending */
    static Path pending(Path root, String name) throws IOException {
        var dir = bare(root, name);
        Files.writeString(dir.resolve(DELIVERY), "accepts = glob:*.csv\n");
        return dir;
    }

    /** a feed with both, which is one that can load */
    static Path active(Path root, String name) throws IOException {
        var dir = pending(root, name);
        Files.writeString(dir.resolve("spec.json"), SPEC);
        return dir;
    }

    /** the smallest spec that reads, which is all these tests need of one */
    static final String SPEC = """
            {
              "input": {
                "mimeType": "text/csv",
                "recordSelectors": [
                  { "name": "people",
                    "fieldSelectors": [ { "name": "id", "selector": "id" } ] }
                ]
              },
              "mapping": [
                { "recordSelector": "people", "table": "person",
                  "fieldMapping": [ { "fieldSelector": "id", "column": "id" } ] }
              ]
            }
            """;

    static void file(Path directory, String name) throws IOException {
        Files.writeString(directory.resolve(name), "id\n1\n");
    }
}
