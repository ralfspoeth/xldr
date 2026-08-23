package io.github.ralfspoeth.xldr.spec.io;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Turns the text of a spec into what a {@link MappingSpecReader} reads.
 * <p>
 * A spec in a test is a text block; a reader takes a stream. UTF-8 is not a
 * choice here but the encoding a spec file is written in, so the tests do not
 * ask about it.
 */
final class Streams {

    private Streams() {
    }

    static InputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(UTF_8));
    }
}
