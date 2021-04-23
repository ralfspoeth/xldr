package com.pd.xldr.spec;

import java.io.Serializable;
import java.util.List;

public record MappingSpec(
        InputSpec inputSpec,
        List<RecordMappingSpec> recordMappingSpecs,
        OutputSpec outputSpec
) implements Serializable {
}
