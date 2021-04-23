package com.pd.xldr.spec;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public enum Type {

    DATE(LocalDateTime.class),
    STRING(String.class),
    INTEGER(Integer.class),
    DECIMAL(BigDecimal.class);

    private final Class<?> clazz;

    Type(Class<?> clazz) {
        this.clazz = clazz;
    }

    public Class<?> clazz() {
        return clazz;
    }
}
