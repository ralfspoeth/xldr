package io.github.ralfspoeth.xldr.server;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.regex.PatternSyntaxException;

/**
 * Delivery by atomic move: the data file appears complete or not at all.
 * <p>
 * Package-private, as {@link Delivery}'s other case is. A caller outside this
 * package reads a {@value Delivery#FILE} and asks {@link Delivery#claims}; which
 * of the two answered is no use to them.
 *
 * @param pattern the {@code glob:} or {@code regex:} form, kept for messages
 * @param matcher that pattern compiled, matched against the file name only
 */
record AtomicDelivery(String pattern, PathMatcher matcher) implements Delivery {

    static AtomicDelivery parse(String pattern) {
        if (!pattern.startsWith("glob:") && !pattern.startsWith("regex:")) {
            throw new IllegalArgumentException(
                    Delivery.ACCEPTS + " must start with 'glob:' or 'regex:', was: " + pattern);
        }
        try {
            return new AtomicDelivery(pattern, FileSystems.getDefault().getPathMatcher(pattern));
        } catch (PatternSyntaxException | UnsupportedOperationException e) {
            throw new IllegalArgumentException(
                    "invalid " + Delivery.ACCEPTS + " pattern: " + pattern, e);
        }
    }

    @Override
    public boolean claims(Path file) {
        return matcher.matches(file.getFileName());
    }

    @Override
    public String toString() {
        return Delivery.ACCEPTS + "=" + pattern;
    }
}
