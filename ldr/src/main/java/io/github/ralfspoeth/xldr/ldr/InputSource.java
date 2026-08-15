package io.github.ralfspoeth.xldr.ldr;

import java.io.IOException;
import java.io.InputStream;

/**
 * Something an input can be read from, more than once.
 * <p>
 * Not an {@link InputStream}, and not a {@code Supplier<InputStream>}. A mapping
 * spec may carry several record mappings, and each is run over the whole input, so
 * the input has to be opened once per mapping - a stream is read once and cannot
 * serve the second. And opening can fail, which a {@code Supplier} would have to
 * hide in an unchecked exception.
 * <p>
 * A file satisfies this by being reopened. Anything read from a socket has to be
 * spooled somewhere first, which is a real cost and the reason this interface says
 * "again" out loud rather than leaving a caller to discover it from a load that
 * silently imported one mapping's worth of rows.
 */
@FunctionalInterface
public interface InputSource {

    /**
     * @return a stream over the whole input, positioned at its start; the caller
     * closes it
     */
    InputStream open() throws IOException;
}
