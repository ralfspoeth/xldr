package io.github.ralfspoeth.xldr.ia;

@FunctionalInterface
public interface Row {
    Object get(String name);

    default <T> T get(String name, Class<T> type) {
        return type.cast(get(name));
    }

    default Object get(Field fld) {
        return get(fld.name(), fld.type());
    }
}
