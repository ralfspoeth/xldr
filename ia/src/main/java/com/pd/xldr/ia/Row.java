package com.pd.xldr.ia;

@FunctionalInterface
public interface Row {
    Object get(String name);

    default <T> T get(String name, Class<T> type) {
        return type.cast(get(name));
    }

    default <T> T get(Field fld) {
        return get(fld.name(), (Class<T>) fld.type());
    }
}
