package io.github.ralfspoeth.xldr.spec;

import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Which records of a flat input belong to one record selector: which component
 * of the record to look at, and what its value has to be.
 * <p>
 * A record selector's {@code selector} does two unrelated jobs. For XML, JSON and
 * Excel it <em>locates</em> records - an XPath, a pointer, a sheet range - while
 * for a flat file every line is a candidate and the question is which ones to
 * keep. That second job used to be done by the same attribute, and it compressed
 * three decisions into one string: which component (always the first), which test
 * (always equality), and against what. Two of the three could not be said, so a
 * file marking its record type in the second column could not be read.
 *
 * <pre>
 * "discriminator": { "nth": 1, "equals": "O" }
 * "discriminator": { "selector": "type", "matches": "^O.*" }
 * </pre>
 * <p>
 * Sealed over the two tests rather than a record with two nullable fields, as
 * {@code Delivery} is over its two ways of knowing a file has arrived: exactly one
 * of {@code equals} and {@code matches} is then the type saying so, and neither
 * the constructor nor the reader has to.
 */
public sealed interface Discriminator {

    /**
     * Which component of the record to test. A {@link Selector.Nth} counts them;
     * a {@link Selector.Text} names one, and so needs a header.
     */
    Selector at();

    /**
     * Whether a value belongs to this record selector.
     *
     * @param value the content of the component tested, or {@code null} where
     *              the record has no such component - which matches nothing, a record
     *              that could not be asked not being one that answered
     */
    boolean accepts(@Nullable String value);

    /**
     * The component holds this, exactly.
     */
    record Equals(Selector at, String literal) implements Discriminator {
        @Override
        public boolean accepts(@Nullable String value) {
            // stripped on both sides: a flat file pads, and a spec should not
            // have to say so
            return value != null && literal.equals(value.strip());
        }

        @Override
        public String toString() {
            return at + " = '" + literal + "'";
        }
    }

    /**
     * The component matches this pattern, in full - {@code matches} rather than
     * {@code find}, so that a pattern says what a whole value looks like and an
     * anchor is not something to remember.
     */
    record Matches(Selector at, Pattern pattern) implements Discriminator {
        @Override
        public boolean accepts(@Nullable String value) {
            return value != null && pattern.matcher(value.strip()).matches();
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof Matches(var mat, var mpat)
                    && this.at.equals(mat)
                    && this.pattern.pattern().equals(mpat.pattern())
                    && this.pattern.flags() == mpat.flags();
        }

        @Override
        public int hashCode() {
            return Objects.hash(at, pattern.pattern(), pattern.flags());
        }

        @Override
        public String toString() {
            return at + " matches /" + pattern + "/";
        }
    }

    /**
     * Compiles the pattern now rather than per record, so that one that will not
     * compile is a spec that does not deploy rather than a load that dies half
     * way through a file - the same moment an XPath is refused at.
     *
     * @throws IllegalArgumentException naming the pattern and what is wrong with it
     */
    static Discriminator matching(Selector at, String regex) {
        try {
            return new Matches(at, Pattern.compile(regex));
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("the discriminator pattern /" + regex
                    + "/ does not compile: " + e.getMessage(), e);
        }
    }
}
