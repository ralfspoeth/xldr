package io.github.ralfspoeth.xldr.spec;

import java.io.Serializable;

/**
 * Maps one database column to its {@link ValueSource}.
 *
 * @param column the target column
 * @param source where the column's value comes from
 */
public record FieldMappingSpec(String column, ValueSource source) implements Serializable {}
