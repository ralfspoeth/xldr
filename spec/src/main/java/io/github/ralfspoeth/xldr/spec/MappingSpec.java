package io.github.ralfspoeth.xldr.spec;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;

/**
 * A complete mapping specification: how to parse an input, and how its records
 * map onto database tables.
 *
 * @param inputSpec          how the file is parsed into records and fields
 * @param recordMappingSpecs the record-to-table mappings
 */
public record MappingSpec(
        InputSpec inputSpec,
        Collection<RecordMappingSpec> recordMappingSpecs
) implements Serializable {
    /**
     * Canonical constructor.
     */
    public MappingSpec {
        recordMappingSpecs = List.copyOf(recordMappingSpecs);
    }
}
