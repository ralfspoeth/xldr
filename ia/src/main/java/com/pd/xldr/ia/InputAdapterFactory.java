package com.pd.xldr.ia;

import com.pd.xldr.spec.InputSpec;

import java.util.Properties;

public interface InputAdapterFactory {
    boolean accepts(String mimeType);

    default boolean accepts(InputSpec spec) {
        return accepts(spec.mimeType());
    }

    void setProperty(String property, String value);

    default void setProperties(Properties properties) {
            for(String p: properties.stringPropertyNames()) {
                setProperty(p, properties.getProperty(p));
            }
    }

    InputAdapter createInputAdapter(InputSpec spec);
}
