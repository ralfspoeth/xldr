package io.github.ralfspoeth.xldr.spec.io;

import io.github.ralfspoeth.xldr.spec.MappingSpec;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * Reads a {@link MappingSpec} from text in one format. Implementations are
 * chosen by the spec file's extension, one per supported format (JSON, XML).
 */
public interface MappingSpecReader {

    /**
     * The reader for a spec file, if any format claims it.
     * <p>
     * Which reader reads a file is knowledge about readers, so it lives with
     * them rather than with each caller. Readers are found as services, and
     * asked in whatever order the service loader hands them over, so the
     * {@link #accepts} of the readers on the module path have to partition the
     * names between them; a name no reader claims yields nothing, and the
     * caller says what that means.
     * <p/>
     * The loader is named explicitly, and it is the one that defined this
     * interface. The one-argument {@link ServiceLoader#load(Class)} would
     * resolve against the <em>thread context</em> class loader instead - whatever
     * that happens to be on the calling thread, which for a library is nobody's
     * business but is everybody's problem. It is set by servlet containers, by
     * test runners and by application frameworks, each to something of their own,
     * and when it is set to a loader that cannot see this module the lookup
     * quietly finds nothing: {@code of} returns empty, {@code readSpec} refuses
     * every spec with "unsupported mapping spec format", and a feed never comes
     * up for a reason that has nothing to do with the file. Asking the loader
     * that defined the service is the answer, since it is the one that defined
     * the providers too.
     *
     * @param path the spec file, which need not exist - only its name is read
     * @return the first reader that accepts it
     */
    static Optional<MappingSpecReader> of(Path path) {
        return ServiceLoader.load(MappingSpecReader.class, MappingSpecReader.class.getClassLoader())
                .stream()
                .map(ServiceLoader.Provider::get)
                .filter(reader -> reader.accepts(path))
                .findFirst();
    }

    /**
     * Reads the spec in {@code path}, with the reader its name calls for.
     * <p>
     * Where {@link #of} answers whether a file can be read at all,
     * this insists that it be: a caller that has a spec file in hand and
     * nothing to fall back on wants the reason it could not be read, not an
     * empty result it has to invent a message for.
     *
     * @param path the spec file
     * @return the spec it holds
     * @throws IllegalArgumentException if no reader claims the file's format
     * @throws IOException              if the file cannot be read, or does not
     *                                  hold a spec of that format
     */
    static MappingSpec readSpec(Path path) throws IOException {
        var reader = of(path).orElseThrow(() -> new IllegalArgumentException(
                "unsupported mapping spec format: " + path));
        try (var in = new BufferedInputStream(Files.newInputStream(path))) {
            return reader.read(in);
        }
    }

    /**
     * Whether this reader reads files of that name - the discriminator by which
     * {@link #of} picks one. A reader says for itself which files are its own,
     * so adding a format is adding a reader and nothing else.
     * <p>
     * Only the name is looked at, so the file need not exist.
     *
     * @param path the path to a spec file
     * @return whether this reader claims it
     */
    boolean accepts(Path path);

    /**
     * Reads a mapping spec from {@code source}, which the caller opens and
     * closes.
     * <p>
     * Bytes rather than characters: which encoding a spec file is in is the
     * format's to know - JSON is UTF-8 by definition, an XML document declares
     * its own - and a {@code Reader} would have taken that decision away from
     * the reader that is entitled to make it.
     *
     * @param source the spec file's content
     * @return the spec it holds
     * @throws IOException if the source cannot be read, or does not hold a spec
     *                     of this reader's format
     */
    MappingSpec read(InputStream source) throws IOException;
}
