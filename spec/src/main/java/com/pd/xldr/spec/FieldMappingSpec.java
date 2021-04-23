package com.pd.xldr.spec;

import java.io.Serializable;

public record FieldMappingSpec(String fieldName, String databaseColumnName) implements Serializable {
}
