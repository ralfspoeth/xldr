package io.github.ralfspoeth.xldr.spec;

import org.jspecify.annotations.Nullable;

import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import static java.util.Objects.requireNonNull;

/**
 * Where the value of a mapped database column comes from. Exactly one of:
 * <ul>
 *   <li>{@link Field} - a field of the input record, resolved by the adapter and
 *       bound as a statement parameter (the ordinary case).</li>
 *   <li>{@link Constant} - a fixed value from the spec, bound as a parameter. Its
 *       Java type follows the JSON literal: a {@code String}, a
 *       {@code BigDecimal} for a number, or a {@code Boolean}.</li>
 *   <li>{@link Lookup} - the value read from a reference table, emitted as an
 *       inline scalar subquery {@code (select column from table where a = ? and
 *       b = ?)}, where each condition's value is itself a {@code Field},
 *       {@code Constant} or {@code Var}. A key that matches no row yields SQL
 *       NULL.</li>
 *   <li>{@link Var} - a reference to an input-level variable, evaluated once per
 *       load and then bound as a parameter. See {@link VarSpec}.</li>
 *   <li>{@link Expr} - a {@code ${...}} template evaluated in the JVM to a value
 *       that is bound as a parameter. It interpolates variables and a small set
 *       of built-in functions ({@code nextval}, {@code now}, {@code coalesce},
 *       {@code recode}); it never emits
 *       SQL.</li>
 *   <li>{@link Regex} - part of another source's value, picked out by a regular
 *       expression. No match yields NULL; the pattern is compiled when the spec
 *       is read.</li>
 *   <li>{@link FunctionCall} - a function in the target database, called through
 *       JDBC's {@code {? = call name(?)}} escape once per load and bound as a
 *       parameter thereafter. A {@link VarSpec} source only.</li>
 * </ul>
 * <p>
 * A spec contributes no SQL of its own: a value is a bound parameter, an
 * identifier that is normalized and where needed quoted, or - for a function
 * call - a name checked to be an identifier, placed in an escape the driver
 * translates. What a spec may depend on is the target's <em>schema</em>: that a
 * {@code Lookup}'s table is there, and that a {@code FunctionCall}'s function is.
 * What it never depends on is a dialect.
 */
public sealed interface ValueSource {

    /**
     * A field of the input record.
     *
     * @param fieldName the name of a field selector of the record selector being
     *                  mapped
     */
    record Field(String fieldName) implements ValueSource {}

    /**
     * A fixed value from the spec.
     *
     * @param value the value, whose Java type follows the literal it was read
     *              from: a {@code String}, a {@code BigDecimal} or a
     *              {@code Boolean}, or {@code null} for a JSON null, which loads
     *              a SQL NULL. An XML spec carries constants as attributes and
     *              so cannot express one
     */
    record Constant(@Nullable Object value) implements ValueSource {}

    /**
     * A value read from a reference table, matched on one column or on several.
     * <p>
     * The conditions are {@code and}ed, and their order is the order they were
     * written: it decides the text of the {@code where} clause and therefore the
     * order the parameters are bound in. That is why this is a {@link
     * SequencedMap} and not a {@code Map} - {@link Map#copyOf} randomises its
     * iteration order per JVM run, which would make the emitted SQL, the
     * statement cache key and {@code check}'s output differ between runs of the
     * same spec on the same file.
     * <p>
     * Two conditions on one column cannot be expressed: {@link SqlIdentifier}
     * is equal to another when the database would resolve the two to one column,
     * so {@code city} and {@code CITY} are one key here and the map holds one of
     * them. It keeps the spelling put in first, and a reader detecting the
     * collision - which it does, its {@code put} returning the previous value -
     * is what reports it to whoever wrote the spec.
     *
     * <p>
     * There may be no conditions at all, and then the whole table is read: a
     * single-row view, or Oracle's {@code dual}. That is not the hazard it looks
     * like, because it is the hazard a keyed lookup already has - a key matching
     * several rows takes the first one arbitrarily too, and nothing here has
     * ever refused that. A spec says it by writing an empty list rather than by
     * leaving the conditions out, so that forgetting a key stays an error
     * instead of quietly becoming this.
     *
     * @param table      the table to read from
     * @param column     the column whose value is taken
     * @param conditions the columns to match on, each against a value source of
     *                   its own, in the order they are written. Possibly none; a
     *                   key that matches no row yields SQL NULL, and so does a
     *                   condition whose own value is null
     */
    record Lookup(SqlIdentifier table, SqlIdentifier column,
                  SequencedMap<SqlIdentifier, ValueSource> conditions) implements ValueSource {

        public Lookup {
            // no scan for two conditions on one column: a SqlIdentifier is equal
            // to another when the database would call them one column, so this
            // map could not have been holding both
            conditions = Collections.unmodifiableSequencedMap(new LinkedHashMap<>(conditions));
        }

        /**
         * The one-condition lookup, which is nearly every lookup: this is the
         * shape a spec writes as {@code keyColumn} beside its source, and it
         * stays the short way of saying it in Java too.
         */
        public Lookup(String table, String column, String keyColumn, ValueSource key) {
            this(new SqlIdentifier(table), new SqlIdentifier(column),
                    oneCondition(new SqlIdentifier(keyColumn), key));
        }

        /** and the composite one with its table and column named as text */
        public Lookup(String table, String column, SequencedMap<SqlIdentifier, ValueSource> conditions) {
            this(new SqlIdentifier(table), new SqlIdentifier(column), conditions);
        }

        /** the key column of a lookup that has exactly one, for the many that do */
        public SqlIdentifier keyColumn() {
            if (conditions.size() != 1) {
                throw new IllegalStateException("this lookup matches on " + conditions.size()
                        + " columns, so it has no single key column: " + conditions.keySet());
            }
            return conditions.firstEntry().getKey();
        }

        /** likewise the key, where there is exactly one */
        public ValueSource key() {
            return conditions.get(keyColumn());
        }

        private static SequencedMap<SqlIdentifier, ValueSource> oneCondition(
                SqlIdentifier keyColumn, ValueSource key) {
            var one = new LinkedHashMap<SqlIdentifier, ValueSource>();
            one.put(keyColumn, requireNonNull(key, "key"));
            return one;
        }
    }

    /**
     * A reference to an input {@link VarSpec} by name; resolves to that
     * variable's value, which is computed once at the start of the load.
     *
     * @param name the name of a variable the input declares
     */
    record Var(String name) implements ValueSource {}

    /**
     * A {@code ${...}} template. Literal text is interleaved with holes, each a
     * variable reference ({@code ${xldr.filename}}, a var, or - in a field
     * mapping - a field) or a call to a built-in function ({@code ${nextval('s')}},
     * {@code ${now()}}, {@code ${coalesce(a, b)}}, {@code ${recode(s, 'C', 'credit')}}). A template that is a single hole yields that hole's
     * value with its native type; anything else yields the pieces concatenated
     * as a string.
     *
     * @param template the template text, holes and all
     */
    record Expr(String template) implements ValueSource {}

    /**
     * Part of another value, picked out by a regular expression.
     * <p>
     * What it is for is the file name. A feed delivering
     * {@code prices_EUR_20260101.csv} carries its currency in the name and
     * nowhere else, and this is how a spec says so:
     *
     * <pre>
     * {"name": "currency",
     *  "regex": {"pattern": ".*_([A-Z]{3})_.*", "group": 1, "expr": "${xldr.filename}"}}
     * </pre>
     * <p>
     * The subject is a source of its own rather than assumed to be the filename,
     * so the same thing reaches a field - and in a field mapping it is applied
     * per record, where in a var it is applied once.
     * <p>
     * <strong>No match is null.</strong> A file whose name does not fit yields
     * NULL in that column, which is visible in the data and can be reported on -
     * the choice a {@link Lookup} already makes for a key matching no row, and
     * for the same reason: one unrecognised name should not fail a file of a
     * hundred thousand records.
     * <p>
     * <strong>The pattern is compiled when the spec is read</strong>, by
     * {@link #matching}, so a regex that does not compile is a spec that does not
     * read - and a feed whose spec does not read is never activated. The failure
     * lands where the file was edited rather than on the first delivery. That is
     * the same bargain {@link Discriminator.Matches} makes, and this holds a
     * compiled {@link Pattern} for the same reason it does.
     *
     * @param subject where the text to match comes from
     * @param pattern the compiled expression
     * @param group   which capturing group to take; 0 is the whole match
     */
    record Regex(ValueSource subject, Pattern pattern, int group) implements ValueSource {

        public Regex {
            requireNonNull(subject, "subject");
            requireNonNull(pattern, "pattern");
            if (group < 0) {
                throw new IllegalArgumentException("a group number is not negative, but was " + group);
            }
            var declared = pattern.matcher("").groupCount();
            if (group > declared) {
                throw new IllegalArgumentException("group " + group + " of /" + pattern + "/, which captures "
                        + declared + ": a pattern captures with parentheses, and group 0 is the whole match");
            }
        }

        /**
         * The one to build with, since it is the one that compiles - and a
         * pattern that does not compile is a spec that cannot load, so it is
         * refused where the spec is read.
         */
        public static Regex matching(ValueSource subject, String regex, int group) {
            try {
                return new Regex(subject, Pattern.compile(regex), group);
            } catch (PatternSyntaxException e) {
                throw new IllegalArgumentException(
                        "the pattern /" + regex + "/ does not compile: " + e.getMessage(), e);
            }
        }

        /**
         * Compared on what the pattern says rather than on the object, two
         * {@code Pattern}s compiled from one string being distinct objects.
         * {@link Discriminator.Matches} does the same, and for the same reason.
         */
        @Override
        public boolean equals(Object other) {
            return other instanceof Regex(var subj, var pat, var grp)
                    && subject.equals(subj)
                    && pattern.pattern().equals(pat.pattern())
                    && pattern.flags() == pat.flags()
                    && group == grp;
        }

        @Override
        public int hashCode() {
            return Objects.hash(subject, pattern.pattern(), pattern.flags(), group);
        }

        @Override
        public String toString() {
            return "group " + group + " of /" + pattern + "/ over " + subject;
        }
    }

    /**
     * A function in the target database, called once per load and bound as a
     * parameter wherever the variable holding it is referenced.
     * <p>
     * A {@link VarSpec} source only. A var is evaluated once, before any record
     * is read, which is what makes a {@code CallableStatement} affordable here
     * and nowhere else: in a field mapping the same call would be a round trip
     * per row and would end the batching. The loader enforces it, refusing this
     * in a column exactly as it refuses a {@link Field} in a var - each is
     * meaningless where the other belongs.
     * <p>
     * The dependency this creates is on the target's <em>schema</em>, not on its
     * dialect: {@code {? = call name(?)}} is JDBC's own call escape, which the
     * driver translates, so what a spec relies on is that the database has the
     * function - the same kind of reliance a {@link Lookup} already has on a
     * table existing.
     *
     * @param name       the function, optionally qualified. Each dot-separated
     *                   part must be an identifier, since this is the one part of
     *                   a value source that reaches the statement text
     * @param returnType what the function returns. Required, unlike a field
     *                   selector's type, which may be left out and defaults to
     *                   {@code TEXT}: an OUT parameter has to be registered
     *                   before the call, so there is nothing to fall back to
     * @param parameters the arguments, each a value source of its own and each
     *                   evaluated with no record in hand - so a {@code Field}
     *                   among them is refused where every other var source is
     */
    record FunctionCall(String name, DataType returnType, List<ValueSource> parameters) implements ValueSource {

        public FunctionCall {
            CallableName.refuseUncallable("function", name);
            parameters = List.copyOf(parameters);
        }
    }
}
