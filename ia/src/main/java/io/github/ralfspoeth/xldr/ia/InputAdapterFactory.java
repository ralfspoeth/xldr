package io.github.ralfspoeth.xldr.ia;

import io.github.ralfspoeth.xldr.spec.InputSpec;

import java.util.Properties;

public interface InputAdapterFactory {
    boolean reads(String mimeType);

    default boolean reads(InputSpec spec) {
        return reads(spec.mimeType());
    }

    void setProperty(String property, String value);

    default void setProperties(Properties properties) {
            for(String p: properties.stringPropertyNames()) {
                setProperty(p, properties.getProperty(p));
            }
    }

    InputAdapter createInputAdapter(InputSpec spec);
}
