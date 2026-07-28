package io.github.ralfspoeth.xldr.spec;

import org.jspecify.annotations.Nullable;

import java.io.Serializable;
import java.util.List;

/**
 * Maps the records of one record selector to one database table.
 *
 * @param recordSelector the record selector whose records are mapped
 * @param table          the target table
 * @param fieldMappings  the column mappings
 * @param limit          at most this many records are inserted; {@code null}
 *                       means no limit
 */
public record RecordMappingSpec(
        String recordSelector,
        String table,
        List<FieldMappingSpec> fieldMappings,
        @Nullable Integer limit
) implements Serializable {

    /**
     * Canonical constructor.
     */
    public RecordMappingSpec {
        fieldMappings = List.copyOf(fieldMappings);
        if (limit != null && limit < 0) {
            throw new IllegalArgumentException("limit must not be negative: " + limit);
        }
    }

    /**
     * No row limit.
     */
    public RecordMappingSpec(String recordSelector, String table, List<FieldMappingSpec> fieldMappings) {
        this(recordSelector, table, fieldMappings, null);
    }
}
