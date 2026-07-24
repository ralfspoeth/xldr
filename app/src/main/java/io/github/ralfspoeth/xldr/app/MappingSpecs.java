package io.github.ralfspoeth.xldr.app;

import io.github.ralfspoeth.xldr.spec.MappingSpec;
import io.github.ralfspoeth.xldr.spec.io.JsonMappingSpecReader;
import io.github.ralfspoeth.xldr.spec.io.MappingSpecReader;
import io.github.ralfspoeth.xldr.spec.io.PropertiesMappingSpecReader;
import io.github.ralfspoeth.xldr.spec.io.XmlMappingSpecReader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Locating and reading the mapping spec of a feed directory.
 */
final class MappingSpecs {

    /**
     * The accepted spec file names. A feed directory must contain exactly one of
     * them; zero means "not a feed (yet)", more than one is ambiguous.
     */
    static final List<String> SPEC_NAMES = List.of("spec.json", "spec.xml", "spec.properties");

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
        try (var in = Files.newBufferedReader(specFile)) {
            return reader.readFrom(in);
        }
    }

    /**
     * Picks the reader by file name.
     * <p>
     * {@code MappingSpecReader} carries no discriminator - no {@code accepts(...)}
     * the way {@code InputAdapterFactory} has one - so the implementations cannot
     * be told apart through {@code ServiceLoader} even though {@code spec}
     * declares them as providers.
     */
    private static MappingSpecReader readerFor(Path specFile) {
        var name = specFile.getFileName().toString();
        if (name.endsWith(".json")) {
            return new JsonMappingSpecReader();
        } else if (name.endsWith(".xml")) {
            return new XmlMappingSpecReader();
        } else if (name.endsWith(".properties")) {
            return new PropertiesMappingSpecReader();
        } else {
            throw new IllegalArgumentException("unsupported mapping spec format: " + specFile);
        }
    }
}
