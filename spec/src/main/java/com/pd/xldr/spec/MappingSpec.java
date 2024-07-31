package com.pd.xldr.spec;

import java.io.Serializable;
import java.util.List;

import static java.util.Objects.requireNonNullElse;

public record MappingSpec(
        InputSpec inputSpec,
        List<RecordMappingSpec> recordMappingSpecs,
        OutputSpec outputSpec
) implements Serializable {
    public MappingSpec {
        recordMappingSpecs = requireNonNullElse(recordMappingSpecs, List.of());
    }
}
