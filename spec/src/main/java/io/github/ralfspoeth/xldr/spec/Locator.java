package io.github.ralfspoeth.xldr.spec;

/**
 * Which records of an input belong to one record selector.
 * <p>
 * There are three answers and no others, which is what this type is for. An
 * input that has somewhere to point is pointed at - an XPath, a JSON pointer, a
 * range of cells - and that is {@link At}. An input where every record is a
 * candidate is filtered, and that is {@link Where}. An input whose records are
 * all of one kind needs neither, and that is {@link Every}.
 * <p>
 * Which of the three a format offers is a property of the format rather than of
 * the spec, and the split is clean: XML, JSON and Excel are pointed at, CSV and
 * fixed-length are filtered, and all five allow {@code Every}. So every adapter
 * accepts two of the three cases and refuses the other, which is a total switch
 * over this type.
 *
 * <h2>Why a type</h2>
 * This used to be two nullable fields on {@link RecordSelectorSpec}, a
 * {@code String selector} and a {@code Discriminator}, with four states of which
 * one described no input at all. The constructor refused that fourth state and
 * each adapter then checked the remaining three by hand, through a pair of
 * {@code refuseSelector} and {@code refuseDiscriminator} methods that had to be
 * called in the right place to have any effect - and were not, in the JSON
 * adapter, where a discriminator was dropped in silence until someone noticed.
 * <p>
 * Three cases in a sealed type say the same thing without anyone having to
 * remember: both-at-once cannot be written, {@code Every} is a case to handle
 * rather than an absence to overlook, and a switch that forgets one does not
 * compile. The one place where both can still be <em>said</em> is a spec file,
 * and the one place that rule now lives is the reader that reads it.
 *
 * <h2>What did not change</h2>
 * Nothing about the written spec. {@code "selector": "//order"} is an
 * {@link At}, a {@code "discriminator"} object is a {@link Where}, and saying
 * neither is {@link Every}, exactly as before. This is the shape of the parsed
 * result, not of the file.
 */
public sealed interface Locator {

    /**
     * The complaint that this locator is not one the input can honour.
     * <p>
     * The message belongs to the case rather than to the adapter, reason what
     * is worth saying depends on what the author wrote: someone who wrote a
     * selector for a flat file wants to be told about discriminators, and
     * someone who wrote nothing at all for an XML document wants to be told that
     * a document has to be pointed at. Five adapters each had a version of this
     * and only the two of them that had met the mistake said anything useful.
     *
     * @param recordSelector the name to complain about, a spec being read before
     *                       anything else of it is known
     * @param reason         what the input is, phrased to follow "but" - for
     *                       instance "a flat file has no place to point at"
     */
    IllegalArgumentException wrongBecause(String recordSelector, String reason);

    /**
     * Where the records are, in the adapter's own syntax.
     *
     * @param selector never blank; a selector that says nothing selects nothing.
     *                 Refused here rather than by the adapters, which used to
     *                 disagree: XML and Excel refused a blank one, JSON resolved
     *                 it to the whole document, so one spelling meant two things
     *                 depending on who read it. There is now one way to say
     *                 "every record", which is {@link Every}
     */
    record At(String selector) implements Locator {

        public At {
            if (selector.isBlank()) {
                throw new IllegalArgumentException("""
                        a record selector's selector cannot be blank:\
                         it has to point somewhere. To mean every record of the input, leave it\
                         out altogether""");
            }
        }

        @Override
        public IllegalArgumentException wrongBecause(String recordSelector, String reason) {
            return new IllegalArgumentException("""
                    record selector '%s' has a selector, '%s', but %s.\
                     A value the records are recognised by is a discriminator:\
                     { "nth": 1, "equals": "%s" }"""
                    .formatted(recordSelector, selector, reason, selector)
            );
        }

        @Override
        public String toString() {
            return "at '" + selector + "'";
        }
    }

    /**
     * Which records are of this kind, for an input where every record is a
     * candidate.
     */
    record Where(Discriminator test) implements Locator {

        @Override
        public IllegalArgumentException wrongBecause(String recordSelector, String reason) {
            return new IllegalArgumentException("""
                    record selector '%s' has a discriminator, %s, but %s.\
                     Where the records are is a 'selector', in this adapter's own syntax;\
                     a discriminator is for a flat file, where every record is a candidate\
                     and the question is which to keep"""
                    .formatted(recordSelector, test, reason)
            );
        }

        @Override
        public String toString() {
            return "where " + test;
        }
    }

    /**
     * Every record of the input, the spec having said nothing to narrow them.
     * <p>
     * The common case, and a case rather than a null: a CSV file of one kind of
     * row, a fixed-length file that is not a multi-record format, a JSON
     * document that is itself the array. Saying nothing is a thing a spec does
     * on purpose, and an adapter that cannot honour it - an XML document has to
     * be pointed at - should say so as clearly as it says the rest.
     */
    record Every() implements Locator {

        @Override
        public IllegalArgumentException wrongBecause(String recordSelector, String reason) {
            return new IllegalArgumentException("""
                    record selector '%s' says nothing about where its records are,\
                     but %s. Give it a 'selector' in this adapter's own syntax"""
                    .formatted(recordSelector, reason)
            );
        }

        @Override
        public String toString() {
            return "every record";
        }
    }

    /**
     * Every record of the input: the answer for a file that holds one kind of
     * record, which a CSV with a header or a fixed-length file usually is.
     * <p>
     * A factory rather than a constant because a record with no components costs
     * nothing to make, and because the three below read as three answers to one
     * question where three constructor calls read as three different things.
     *
     * @return the locator that keeps every record
     */
    static Locator every() {
        return new Every();
    }

    /**
     * The records this selector points at, in the adapter's own syntax - an
     * XPath, a JSON pointer, a range of cells.
     * <p>
     * What the syntax means is the adapter's business and is not checked here;
     * this only refuses a selector that says nothing at all, since a locator that
     * points nowhere is a spec that meant {@link #every()} and did not say so.
     *
     * @param selector where the records are; never blank
     * @return the locator that points at them
     * @throws IllegalArgumentException if the selector is blank
     */
    static Locator at(String selector) {
        return new At(selector);
    }

    /**
     * The records a test picks out of a file where every record is a candidate -
     * a flat file interleaving several kinds of record, told apart by a value in
     * one of their components.
     *
     * @param test which records are of this kind
     * @return the locator that keeps the ones it accepts
     */
    static Locator where(Discriminator test) {
        return new Where(test);
    }
}
