package io.github.ralfspoeth.xldr.spec;

import java.io.Serializable;

/**
 * Maps one database column to its {@link ValueSource}.
 *
 * @param source where the column's value comes from
 * @param column the target column
 */
public record FieldMappingSpec(ValueSource source, String column) implements Serializable {}
