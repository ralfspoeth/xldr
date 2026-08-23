package io.github.ralfspoeth.xldr.ia;

/**
 * A field an adapter exposes on a {@link Row}: the field selector's name and the
 * Java type its values are delivered as.
 *
 * @param name the field selector name, matching the mapping's {@code fieldSelector}
 * @param type the Java type of the field's values
 */
public record Field(String name, Class<?> type) {}
