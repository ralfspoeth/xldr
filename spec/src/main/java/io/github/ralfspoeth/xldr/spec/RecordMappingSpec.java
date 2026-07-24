package io.github.ralfspoeth.xldr.spec;

import java.io.Serializable;
import java.util.List;

import static java.util.Objects.requireNonNullElse;

/**
 * Maps the records of one record selector to one database table.
 *
 * @param recordSelector the record selector whose records are mapped
 * @param databaseTable  the target table
 * @param fieldMappings  the column mappings
 * @param limit          at most this many records are inserted; {@code null}
 *                       means no limit
 */
public record RecordMappingSpec(
        String recordSelector,
        String databaseTable,
        List<FieldMappingSpec> fieldMappings,
        Integer limit
) implements Serializable {

    public RecordMappingSpec {
        fieldMappings = List.copyOf(requireNonNullElse(fieldMappings, List.of()));
        if (limit != null && limit < 0) {
            throw new IllegalArgumentException("limit must not be negative: " + limit);
        }
    }

    /**
     * No row limit.
     */
    public RecordMappingSpec(String recordSelector, String databaseTable, List<FieldMappingSpec> fieldMappings) {
        this(recordSelector, databaseTable, fieldMappings, null);
    }
}
