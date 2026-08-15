package io.github.ralfspoeth.xldr.ia;

import io.github.ralfspoeth.xldr.spec.InputSpec;

import java.util.Optional;
import java.util.ServiceLoader;

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

    /**
     * The factory for an input spec, if a module on the path provides one.
     * <p>
     * Which factory reads a spec is knowledge about factories, so it lives with
     * them rather than being written out again by each caller - the same
     * arrangement, and for the same reason, as
     * {@code MappingSpecReader.of(Path)}. There were three copies of this loop
     * before it moved here.
     * <p>
     * The loader is named explicitly, and it is the one that defined this
     * interface. The one-argument {@link ServiceLoader#load(Class)} resolves
     * against the <em>thread context</em> class loader instead, which belongs to
     * whoever owns the calling thread - a servlet container, a test runner, an
     * application framework - and where that loader cannot see these modules the
     * lookup finds nothing and says nothing about it.
     *
     * @param spec the input to be read
     * @return the first factory that reads its MIME type
     */
    static Optional<InputAdapterFactory> of(InputSpec spec) {
        return ServiceLoader.load(InputAdapterFactory.class, InputAdapterFactory.class.getClassLoader())
                .stream()
                .map(ServiceLoader.Provider::get)
                .filter(factory -> factory.reads(spec))
                .findFirst();
    }
}
