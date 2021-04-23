package com.pd.xldr.spec;

import java.io.Serializable;

public record FieldSelectorSpec(
        String name,
        String selector,
        Type type
) implements Serializable {
}
