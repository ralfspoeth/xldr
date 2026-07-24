package io.github.ralfspoeth.xldr.spec;

import java.io.Serializable;

public record FieldSelectorSpec(
        String name,
        String selector,
        DataType dataType
) implements Serializable {
}
