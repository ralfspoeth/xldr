package com.pd.xldr.spec;

import java.io.Serializable;
import java.util.List;

public record MappingSpec(
        InputSpec input,
        List<RecordMappingSpec> recordMappingSpecs,
        OutputSpec output
) implements Serializable {
}
