package io.github.ralfspoeth.xldr.spec;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;

import static java.util.Objects.requireNonNullElse;

public record MappingSpec(
        InputSpec inputSpec,
        Collection<RecordMappingSpec> recordMappingSpecs,
        LoadSpec loadSpec
) implements Serializable {
    public MappingSpec {
        recordMappingSpecs = requireNonNullElse(recordMappingSpecs, List.of());
        loadSpec = requireNonNullElse(loadSpec, new LoadSpec());
    }
}
