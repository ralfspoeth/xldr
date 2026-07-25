package io.github.ralfspoeth.xldr.ia;

import io.github.ralfspoeth.xldr.spec.InputSpec;

import java.util.Properties;

/**
 * Creates {@link InputAdapter}s for a MIME type. Discovered through {@link
 * java.util.ServiceLoader}: each format module provides one, and the application
 * picks the factory whose {@link #reads(String)} accepts the input spec's MIME
 * type. Format-specific settings are handed over as properties before an adapter
 * is created.
 */
public interface InputAdapterFactory {

    /**
     * Whether this factory handles {@code mimeType}.
     */
    boolean reads(String mimeType);

    /**
     * Whether this factory handles the spec's MIME type.
     */
    default boolean reads(InputSpec spec) {
        return reads(spec.mimeType());
    }

    /**
     * Sets one format-specific setting, e.g. {@code fieldSeparator} for CSV.
     */
    void setProperty(String property, String value);

    /**
     * Applies every entry in {@code properties} through {@link #setProperty}.
     */
    default void setProperties(Properties properties) {
        for (String p : properties.stringPropertyNames()) {
            setProperty(p, properties.getProperty(p));
        }
    }

    /**
     * Creates an adapter for {@code spec}, using the settings applied so far.
     */
    InputAdapter createInputAdapter(InputSpec spec);
}
