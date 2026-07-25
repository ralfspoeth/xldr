package io.github.ralfspoeth.xldr.spec;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * The types a field value may be delivered as, each mapped to its Java class.
 */
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

    /**
     * The Java class values of this type are delivered as.
     */
    public Class<?> clazz() {
        return clazz;
    }
}
