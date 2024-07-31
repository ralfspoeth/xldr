package com.pd.xldr.spec;

import java.io.Serializable;
import java.util.Map;

import static java.util.Objects.requireNonNullElse;

public record OutputSpec(String url, Map<String, String> info) implements Serializable {
    public OutputSpec {
        info = Map.copyOf(requireNonNullElse(info, Map.of()));
    }
}
