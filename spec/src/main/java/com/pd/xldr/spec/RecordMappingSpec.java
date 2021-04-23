package com.pd.xldr.spec;

import java.io.Serializable;
import java.util.List;

public record RecordMappingSpec(
        String recordSelector,
        String databaseTable,
        List<FieldMappingSpec> fieldMappings
) implements Serializable {
}
