package io.github.ralfspoeth.xldr.ia;

import org.jspecify.annotations.Nullable;

/**
 * One parsed record: a lookup from field name to value. An adapter returns a
 * {@code Row} per record, and the loader binds the values named by the mapping.
 */
@FunctionalInterface
public interface Row {

    /**
     * @return the value of the named field, or {@code null} if the record has none
     */
    @Nullable Object get(String name);

    /**
     * The named field's value, cast to {@code type}.
     */
    default <T> @Nullable T get(String name, Class<T> type) {
        return type.cast(get(name));
    }

    /**
     * The value of {@code fld}, cast to the field's declared type.
     */
    default @Nullable Object get(Field fld) {
        return get(fld.name(), fld.type());
    }
}
