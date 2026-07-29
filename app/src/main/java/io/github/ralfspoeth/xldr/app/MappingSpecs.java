package io.github.ralfspoeth.xldr.app;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Locating the mapping spec of a feed directory. Reading it is
 * {@link io.github.ralfspoeth.xldr.spec.io.MappingSpecReader#readSpec(Path)}.
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

}
