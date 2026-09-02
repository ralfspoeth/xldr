package io.github.ralfspoeth.xldr.spec.io;

import io.github.ralfspoeth.xldr.spec.Discriminator;
import io.github.ralfspoeth.xldr.spec.Locator;
import io.github.ralfspoeth.xldr.spec.Selector;
import io.github.ralfspoeth.xldr.spec.ValueSource;
import org.jspecify.annotations.Nullable;

import java.util.Optional;

/**
 * One element of a mapping spec, whichever format it was written in, and the
 * rules about what such an element may say.
 * <p>
 * The two readers used to carry a copy each of those rules - exactly one of
 * {@code selector} and {@code nth}, exactly one of {@code equals} and
 * {@code matches}, exactly one of the four value sources - which is three rules
 * living in six places, plus the schemas. The formats are meant to be
 * transliterations of each other, so a spec refused as JSON has to be refused as
 * XML and with the same complaint; kept in two places that is a promise
 * maintained by hand, and the last change to it needed three passes before both
 * copies said the same thing.
 * <p>
 * What actually differs between the formats is what sits above the line: how a
 * named value is reached, and whether it can carry a type of its own. The rules
 * below are written once against those five methods, so a format supplies the
 * five and inherits the rules.
 * <p>
 * The traversal - which member or which child element holds the record selectors
 * - stays in each reader, that being the part where the two really do differ and
 * where a mistake is a failing test rather than a slow divergence.
 */
interface SpecNode {

    /**
     * The named value where it is text.
     * <p>
     * A JSON number is not text, which is the point: {@code "nth": "1"} is a name
     * and {@code "nth": 1} is a count, and coercing between them would put back
     * the ambiguity the two names were introduced to remove. An XML attribute is
     * always text, so there the distinction is carried by the attribute chosen.
     */
    Optional<String> string(String name);

    /**
     * The named value as text, whatever scalar it was written as - so that a
     * record type written {@code 1} rather than {@code "1"} is a record type
     * rather than a puzzle. Only {@code equals} wants this: everywhere else a
     * value that is not text is a spec meaning something other than it says.
     */
    Optional<String> scalar(String name);

    /**
     * The named value as a whole number.
     *
     * @throws IllegalArgumentException where it is present and is not one, rather
     *                                  than reading as absent - a spec that says
     *                                  {@code nth} means to count
     */
    Optional<Integer> whole(String name);

    /**
     * The {@code constant} value source, whose Java type follows the format: JSON
     * carries a string, a number, a boolean or null; an XML attribute carries text
     * and nothing else. Present-and-null is a SQL NULL, which is why this yields a
     * {@link ValueSource.Constant} rather than an {@code Optional<Object>} that
     * could not tell the two apart.
     */
    Optional<ValueSource.Constant> constant();

    /** This element as a complaint should show it. */
    String shown();

    // ---- the rules -----------------------------------------------------------

    /**
     * Where a value sits: exactly one of {@code selector}, in the adapter's own
     * syntax, and {@code nth}, a component counted from one.
     *
     * @param what names the element in the complaint, a spec being read before
     *             anything of it is known
     */
    default Selector selector(String what) {
        var text = string("selector");
        var nth = whole("nth");
        if (text.isPresent() && nth.isPresent()) {
            throw new IllegalArgumentException(what + " has both a selector and an nth,"
                    + " which are two answers to one question: " + shown());
        } else if (text.isPresent()) {
            return Selector.text(text.get());
        } else if (nth.isPresent()) {
            return Selector.nth(nth.get());
        } else {
            throw new IllegalArgumentException(what + " needs a selector or an nth: " + shown());
        }
    }

    /**
     * Which records of the input are of one kind: a {@code selector} pointing at
     * them, a {@code discriminator} testing them, or neither, which is every
     * record there is.
     * <p>
     * Saying both is refused, and this is the only place left that can refuse it:
     * a {@link Locator} has three cases and both-at-once is not one of them, so
     * the combination can no longer be constructed in Java and survives only as
     * something a spec file might say. The check moved here from the record's
     * constructor for that reason, rather than being weakened - what used to be
     * a rule enforced at construction is now a shape that cannot be built.
     * <p>
     * The discriminator is passed in rather than looked up, the two formats
     * keeping it in different places - a JSON member, an XML child element - and
     * traversal being the part each reader owns.
     *
     * @param what          names the element in the complaint
     * @param discriminator the discriminator this element carries, or
     *                      {@code null} where it carries none
     */
    default Locator locator(String what, @Nullable Discriminator discriminator) {
        var selector = string("selector");
        if (selector.isPresent() && discriminator != null) {
            throw new IllegalArgumentException(what + " has both a selector and a discriminator: '"
                    + selector.get() + "' says where the records are, " + discriminator + " says"
                    + " which records are of this kind, and no input is read both ways: " + shown());
        } else if (selector.isPresent()) {
            return Locator.at(selector.get());
        } else if (discriminator != null) {
            return Locator.where(discriminator);
        } else {
            return Locator.every();
        }
    }

    /**
     * Which records are of a kind: where to look - exactly one of {@code nth} and
     * {@code selector} - and what to look for - exactly one of {@code equals} and
     * {@code matches}. Called on the discriminator itself, the reader having found
     * it where its format keeps it.
     */
    default Discriminator discriminator() {
        var where = selector("a discriminator");
        var literal = scalar("equals");
        var regex = string("matches");
        if (literal.isPresent() && regex.isPresent()) {
            throw new IllegalArgumentException(
                    "a discriminator tests equals or matches, not both: " + shown());
        } else if (literal.isPresent()) {
            return new Discriminator.Equals(where, literal.get());
        } else if (regex.isPresent()) {
            return Discriminator.matching(where, regex.get());
        } else {
            throw new IllegalArgumentException("a discriminator needs equals or matches; " + where
                    + " on its own says where to look and not what for: " + shown());
        }
    }

    /**
     * Exactly one of {@code fieldSelector}, {@code constant}, {@code var} and
     * {@code expr}. A {@code lookup} wraps one of these as its key and is found by
     * the reader, the two formats keeping a child in different places.
     */
    default ValueSource source() {
        var field = string("fieldSelector");
        var constant = constant();
        var varRef = string("var");
        var expr = string("expr");

        var present = (field.isPresent() ? 1 : 0) + (constant.isPresent() ? 1 : 0)
                + (varRef.isPresent() ? 1 : 0) + (expr.isPresent() ? 1 : 0);
        if (present != 1) {
            throw new IllegalArgumentException(
                    "needs exactly one of fieldSelector, constant, var, expr: " + shown());
        } else if (field.isPresent()) {
            return new ValueSource.Field(field.get());
        } else if (varRef.isPresent()) {
            return new ValueSource.Var(varRef.get());
        } else if (expr.isPresent()) {
            return new ValueSource.Expr(expr.get());
        } else {
            return constant.orElseThrow();
        }
    }

    /**
     * Whether this element carries any of the four, which is what a {@code lookup}
     * must not: the key belongs inside the lookup, and a source beside it would be
     * two answers with only one of them read.
     */
    default boolean hasSource() {
        return string("fieldSelector").isPresent()
                || constant().isPresent()
                || string("var").isPresent()
                || string("expr").isPresent();
    }
}
