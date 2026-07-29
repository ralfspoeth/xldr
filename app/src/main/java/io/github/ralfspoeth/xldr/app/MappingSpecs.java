package io.github.ralfspoeth.xldr.app;

import io.github.ralfspoeth.xldr.spec.MappingSpec;
import io.github.ralfspoeth.xldr.spec.io.MappingSpecReader;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * Locating and reading the mapping spec of a feed directory.
 */
final class MappingSpecs {

    /**
     * The accepted spec file names. A feed directory must contain exactly one of
     * them; zero means "not a feed (yet)", more than one is ambiguous.
     */
    static final List<String> SPEC_NAMES = List.of("spec.json", "spec.xml");

    private MappingSpecs() {
    }

    /**
     * @return the single spec file of {@code feedDir}
     * @throws IllegalStateException if the directory holds more than one
     */
    static Optional<Path> find(Path feedDir) {
        var candidates = SPEC_NAMES.stream()
                .map(feedDir::resolve)
                .filter(Files::isRegularFile)
                .toList();
        return switch (candidates.size()) {
            case 0 -> Optional.empty();
            case 1 -> Optional.of(candidates.getFirst());
            default -> throw new IllegalStateException(
                    "ambiguous mapping spec in " + feedDir + ": " + candidates);
        };
    }

    static MappingSpec read(Path specFile) throws IOException {
        var reader = readerFor(specFile);
        try (var in = Files.newInputStream(specFile);
             var bis = new BufferedInputStream(in))
        {
            return reader.readFrom(bis);
        }
    }

    /**
     * Picks the reader utilizing {@link MappingSpecReader#accepts(Path)}
     * discriminator.
     *
     * @throws IllegalArgumentException if no reader can be found
     */
    private static MappingSpecReader readerFor(Path specFile) {
        for (var sl : ServiceLoader.load(MappingSpecReader.class)) {
            if (sl.accepts(specFile)) {
                return sl;
            }
        }
        throw new IllegalArgumentException("unsupported mapping spec format: " + specFile);
    }
}
