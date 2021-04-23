package com.pd.xldr.spec;

import java.io.Serializable;
import java.util.List;

public record RecordSelectorSpec(String name, String selector,
                                 List<FieldSelectorSpec> fieldSelectors) implements Serializable {
}
