package io.github.ralfspoeth.xldr.spec;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public enum DataType {

    DATE(LocalDateTime.class),
    STRING(String.class),
    INTEGER(Long.class),
    FLOAT(Double.class),
    DECIMAL(BigDecimal.class);

    private final Class<?> clazz;

    DataType(Class<?> clazz) {
        this.clazz = clazz;
    }

    public Class<?> clazz() {
        return clazz;
    }
}
