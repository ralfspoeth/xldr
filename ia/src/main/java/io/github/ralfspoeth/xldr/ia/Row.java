package io.github.ralfspoeth.xldr.ia;

/**
 * One parsed record: a lookup from field name to value. An adapter returns a
 * {@code Row} per record, and the loader binds the values named by the mapping.
 */
@FunctionalInterface
public interface Row {

    /**
     * @return the value of the named field, or {@code null} if the record has none
     */
    Object get(String name);

    /**
     * The named field's value, cast to {@code type}.
     */
    default <T> T get(String name, Class<T> type) {
        return type.cast(get(name));
    }

    /**
     * The value of {@code fld}, cast to the field's declared type.
     */
    default Object get(Field fld) {
        return get(fld.name(), fld.type());
    }
}
