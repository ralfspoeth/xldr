package io.github.ralfspoeth.xldr.spec;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;

public record RecordSelectorSpec(String name, String selector,
                                 Collection<FieldSelectorSpec> fieldSelectors) implements Serializable
{
    public RecordSelectorSpec {
        fieldSelectors = List.copyOf(fieldSelectors);
    }
}
