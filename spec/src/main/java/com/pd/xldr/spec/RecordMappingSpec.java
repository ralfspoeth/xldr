package com.pd.xldr.spec;

import java.io.Serializable;
import java.util.List;

import static java.util.Objects.requireNonNullElse;

public record RecordMappingSpec(
        String recordSelector,
        String databaseTable,
        List<FieldMappingSpec> fieldMappings
) implements Serializable {
    public RecordMappingSpec {
        fieldMappings = List.copyOf(requireNonNullElse(fieldMappings, List.of()));
    }
}
