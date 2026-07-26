package io.github.ralfspoeth.xldr.ia;

import io.github.ralfspoeth.xldr.spec.InputSpec;

/**
 * Creates {@link InputAdapter}s for a MIME type. Discovered through {@link
 * java.util.ServiceLoader}: each format module provides one, and the application
 * picks the factory whose {@link #reads(String)} accepts the input spec's MIME
 * type.
 * <p>
 * A factory holds no state of its own. Everything an adapter needs is in the
 * spec it is created from - the format-specific settings among it, as {@link
 * InputSpec#properties()} - so one factory instance can serve any number of
 * feeds, in any order and at the same time.
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
     * Creates an adapter reading the input {@code spec} describes, configured
     * from that spec's {@linkplain InputSpec#properties() properties}.
     */
    InputAdapter createInputAdapter(InputSpec spec);
}
