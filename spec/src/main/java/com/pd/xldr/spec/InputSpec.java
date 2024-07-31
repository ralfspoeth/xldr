package com.pd.xldr.spec;

import java.io.Serializable;
import java.util.List;

import static java.util.Objects.requireNonNullElse;


/**
 * Instances of class {@code InputSpec} provide the information
 * how to transform some inputSpec file into a stream of records
 * and how to extract fields from these records.
 */
public record InputSpec(String mimeType, List<RecordSelectorSpec> recordSelectors) implements Serializable {
    public InputSpec {
        recordSelectors = List.copyOf(requireNonNullElse(recordSelectors, List.of()));
    }
}
