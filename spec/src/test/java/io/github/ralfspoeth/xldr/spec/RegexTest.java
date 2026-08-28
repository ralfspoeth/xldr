package io.github.ralfspoeth.xldr.spec;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The sixth value source, and the second to hold a compiled {@link Pattern}.
 * <p>
 * Both of the things that makes awkward are settled here: what a pattern that
 * will not compile does, and what equality means when the field's own is the
 * wrong one. {@link Discriminator.Matches} answered them first and this answers
 * them the same way, which is the point of testing it - two records with the
 * same problem should not have two solutions.
 */
class RegexTest {

    /**
     * A pattern that does not compile is refused when the spec is read, and the
     * complaint quotes the pattern rather than only the parser's opinion of it.
     * <p>
     * This is what makes the promise the feature was accepted on: a feed is
     * activated only if its patterns compile. Deferring the compile to the first
     * record would put the failure on the delivery instead of on the edit.
     */
    @Test
    void refusesApatternThatDoesNotCompile() {
        var thrown = assertThrows(IllegalArgumentException.class,
                () -> ValueSource.Regex.matching(new ValueSource.Field("f"), "([A-Z]", 1));
        assertAll(
                () -> assertTrue(thrown.getMessage().contains("([A-Z]"), thrown.getMessage()),
                () -> assertTrue(thrown.getMessage().contains("does not compile"), thrown.getMessage()),
                () -> assertInstanceOf(java.util.regex.PatternSyntaxException.class, thrown.getCause()));
    }

    /**
     * A group the pattern does not capture is the same kind of mistake, provable
     * from the document alone: no input could make it work.
     */
    @Test
    void refusesAgroupThePatternDoesNotCapture() {
        var thrown = assertThrows(IllegalArgumentException.class,
                () -> ValueSource.Regex.matching(new ValueSource.Field("f"), "(a)b", 2));
        assertAll(
                () -> assertTrue(thrown.getMessage().contains("captures 1"), thrown.getMessage()),
                () -> assertTrue(thrown.getMessage().contains("group 0 is the whole match"),
                        "and says what group 0 means, that being the likely intent: "
                                + thrown.getMessage()));
    }

    /** and a negative one is not a group number at all */
    @Test
    void refusesAnegativeGroup() {
        assertThrows(IllegalArgumentException.class,
                () -> ValueSource.Regex.matching(new ValueSource.Field("f"), "(a)", -1));
    }

    /**
     * Group 0 is the whole match and needs no parentheses, which is the default
     * a spec that says no group gets.
     */
    @Test
    void groupZeroNeedsNoParentheses() {
        assertDoesNotThrow(() -> ValueSource.Regex.matching(new ValueSource.Field("f"), "[0-9]{4}", 0));
    }

    /**
     * Equality is over what the pattern says, not over the {@link Pattern}
     * object, which has none of its own - so two readers reading one spec file
     * produce equal specs.
     * <p>
     * That is what {@code xldr check --same-as} rests on, and what
     * {@code TutorialTest} rests on when it asserts that the XML page says what
     * the JSON page said. Left to the record's generated equality, the same
     * document read twice would compare unequal.
     */
    @Test
    void twoRegexesOverTheSamePatternAreEqual() {
        var one = ValueSource.Regex.matching(new ValueSource.Field("f"), "(a)b", 1);
        var other = ValueSource.Regex.matching(new ValueSource.Field("f"), "(a)b", 1);

        assertAll(
                () -> assertNotSame(one.pattern(), other.pattern(),
                        "two compiles of one string, so the record's own equality would say no"),
                () -> assertEquals(one, other),
                () -> assertEquals(one.hashCode(), other.hashCode()),
                () -> assertEquals(1, new HashSet<>(List.of(one, other)).size(),
                        "and the two hash to one entry, which equality alone would not give"));
    }

    /** the group is part of it: the same pattern taking a different group is a different source */
    @Test
    void theGroupTellsTwoRegexesApart() {
        assertNotEquals(
                ValueSource.Regex.matching(new ValueSource.Field("f"), "(a)(b)", 1),
                ValueSource.Regex.matching(new ValueSource.Field("f"), "(a)(b)", 2));
    }

    /** as is the subject: the same pattern over a different value is a different source */
    @Test
    void theSubjectTellsTwoRegexesApart() {
        assertNotEquals(
                ValueSource.Regex.matching(new ValueSource.Field("f"), "(a)", 1),
                ValueSource.Regex.matching(new ValueSource.Var("f"), "(a)", 1));
    }

    /**
     * And the flags are, so an inline {@code (?i)} is not lost - a case-insensitive
     * pattern and its exact namesake match different inputs.
     */
    @Test
    void theFlagsTellTwoRegexesApart() {
        var insensitive = new ValueSource.Regex(
                new ValueSource.Field("f"), Pattern.compile("(a)", Pattern.CASE_INSENSITIVE), 1);
        var exact = new ValueSource.Regex(
                new ValueSource.Field("f"), Pattern.compile("(a)"), 1);
        assertNotEquals(insensitive, exact);
    }

    /**
     * It reads as a phrase, because it goes into the complaint raised when a
     * column may not hold one. {@code xldr check} renders the subject itself and
     * does not use this.
     */
    @Test
    void itSaysWhatItIs() {
        var subject = new ValueSource.Expr("${xldr.filename}");
        var shown = ValueSource.Regex.matching(subject, ".*_([A-Z]{3})_.*", 1).toString();
        assertAll(
                () -> assertTrue(shown.startsWith("group 1 of /.*_([A-Z]{3})_.*/ over "), shown),
                () -> assertTrue(shown.endsWith(subject.toString()),
                        "and ends in the subject, however that renders itself: " + shown));
    }
}
