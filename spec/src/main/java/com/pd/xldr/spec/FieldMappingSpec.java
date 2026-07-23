package com.pd.xldr.spec;

import java.io.Serializable;

/**
 * Maps one database column to its {@link ColumnSource}.
 *
 * @param source             where the column's value comes from
 * @param databaseColumnName the target column
 */
public record FieldMappingSpec(ColumnSource source, String databaseColumnName) implements Serializable {

    /**
     * The common case: the column is filled from an input field.
     */
    public FieldMappingSpec(String fieldName, String databaseColumnName) {
        this(new ColumnSource.Field(fieldName), databaseColumnName);
    }
}
