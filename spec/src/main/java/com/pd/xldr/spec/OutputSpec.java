package com.pd.xldr.spec;

import java.io.Serializable;
import java.util.Properties;

public record OutputSpec(String url, Properties info) implements Serializable {
}
