package io.github.ralfspoeth.xldr.spec;

import java.util.Locale;
import java.util.regex.Pattern;

import static java.util.Objects.requireNonNull;

/**
 * A table or column name as a spec writes it, and what it means to a database.
 * <p>
 * Unquoted SQL identifiers are case-insensitive in every target database; they
 * only disagree on the case they fold to - Oracle and H2 fold up, PostgreSQL
 * folds down. Folding up here is portable precisely because this toolkit never
 * adds quotes to what it sends: each database then folds our upper-case name
 * onto whatever it stored. A quoted name is case-sensitive by definition and is
 * passed through verbatim, which is also what keeps {@code "t1"} and {@code t1}
 * distinct - they are two different columns and a spec is entitled to mean
 * either.
 * <p>
 * <strong>Two of these are equal when the database would call them one
 * column.</strong> That is the whole reason this is a type rather than the
 * static helper it was until 0.44: {@code ccy} and {@code CCY} are one unquoted
 * column, and a {@code Map} or {@code Set} keyed by the name as written cannot
 * see it - it holds two entries and is content. Keyed by this, it cannot hold
 * both, so a rule that used to be a scan looking for collisions is now a thing
 * the collection makes impossible. {@link ValueSource.Lookup}'s conditions and
 * {@link RecordMappingSpec}'s columns are both that rule.
 * <p>
 * Equality therefore ignores part of the state, which for a record is worth
 * saying out loud: {@code name()} gives back the spelling this was built from,
 * while two instances spelled differently may still be equal. A map keyed by
 * these keeps whichever spelling was put in first, so that is the one a message
 * or {@code xldr check} shows. The precedent is {@link Discriminator.Matches},
 * which compares on {@code pattern.pattern()} for the same reason: the field's
 * natural equality is not the equality the domain has.
 *
 * <h2>Why the shape is checked</h2>
 *
 * An identifier is the one thing a spec contributes to a statement that is not
 * bound as a parameter. {@link #folded()} is concatenated into the text of the
 * insert and of every lookup subquery, so a name free to be anything is a name
 * free to be a fragment of SQL. {@link ValueSource.FunctionCall} has been held to
 * being a name since 0.40 for exactly this reason, on the grounds that a
 * function name was "the only part of a value source that reaches the statement
 * text" - which was never true. A table and a column reach it too, and until
 * 0.50 nothing looked.
 * <p>
 * So a name is either a plain one - a letter or underscore, then letters,
 * digits, underscore, {@code $} or {@code #} - or a delimited one in double
 * quotes. The plain set is the union of what the target databases accept
 * unquoted rather than the intersection: {@code $} and {@code #} are Oracle's,
 * and a letter is any letter because PostgreSQL takes them. What it excludes is
 * everything that could end a token and start another - whitespace, quotes,
 * semicolons, parentheses, operators, and the dot. Anything outside it is
 * written in quotes, which is what quotes are for.
 * <p>
 * A dot is refused rather than folded through, so {@code "table":
 * "reporting.orders"} says what it means in {@code target.properties} instead.
 * That is the reversible direction: allowing a qualified name later breaks
 * nothing, and refusing one later would.
 *
 * @param name the identifier exactly as the spec wrote it, quotes and all
 */
public record SqlIdentifier(String name) {

    /**
     * An unquoted name. Deliberately a superset of any one database's rule and a
     * subset of what could alter a statement.
     */
    private static final Pattern PLAIN = Pattern.compile("[\\p{L}_][\\p{L}\\p{N}_$#]*");

    /**
     * A delimited name: quoted at both ends, not empty, and any quote inside it
     * doubled, which is how SQL escapes one. So the column {@code a"b} is written
     * {@code "a""b"}.
     */
    private static final Pattern DELIMITED = Pattern.compile("\"([^\"]|\"\")+\"");

    public SqlIdentifier {
        requireNonNull(name, "an identifier needs a name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("an identifier may not be blank");
        }
        if (name.startsWith("\"") || name.endsWith("\"")) {
            if (!DELIMITED.matcher(name).matches()) {
                throw new IllegalArgumentException("'" + name + "' is not a quoted identifier:"
                        + " it is quoted at both ends, holds at least one character, and doubles any"
                        + " quote inside it - the column a\"b is written \"a\"\"b\"");
            }
        } else if (name.indexOf('.') >= 0) {
            throw new IllegalArgumentException("'" + name + "' is qualified, and a name here is one"
                    + " part: say the catalog and the schema in target.properties, which is where a"
                    + " deployment says where its tables are");
        } else if (!PLAIN.matcher(name).matches()) {
            throw new IllegalArgumentException("'" + name + "' is not an identifier: unquoted, it is"
                    + " a letter or underscore followed by letters, digits, underscore, $ or #. This"
                    + " is written into the statement rather than bound to it, so it is held to being"
                    + " a name; quote it to mean anything else");
        }
    }

    /** whether the spec quoted this name, and so meant it exactly as written */
    public boolean quoted() {
        return name.startsWith("\"");
    }

    /**
     * The name as it should be written into SQL, and the form in which two names
     * are the same column.
     * <p>
     * {@link Locale#ROOT} is required rather than tidy: under a Turkish default
     * locale {@code "id".toUpperCase()} yields {@code "İD"}, and a server would
     * then address a different column depending on where it was started.
     */
    public String folded() {
        return quoted() ? name : name.toUpperCase(Locale.ROOT);
    }

    /**
     * The name with its quotes taken off, which is the form a database's own
     * metadata reports - {@code DatabaseMetaData.getColumns} gives the stored
     * name and never the quotes a statement would need.
     * <p>
     * The doubling comes off with them: {@code "a""b"} is the one column
     * {@code a"b}, and comparing the stored name against {@code a""b} would have
     * matched nothing. Rare, and wrong in a way that reads as the column being
     * absent.
     */
    public String unquoted() {
        return quoted()
                ? name.substring(1, name.length() - 1).replace("\"\"", "\"")
                : name;
    }

    /**
     * Equal when a database would resolve the two to one column. See the class
     * documentation: this is the point of the type, not a convenience.
     */
    @Override
    public boolean equals(Object other) {
        return other instanceof SqlIdentifier that && folded().equals(that.folded());
    }

    @Override
    public int hashCode() {
        return folded().hashCode();
    }

    /**
     * The spelling the spec used, so that a message naming an identifier reads
     * as the author wrote it rather than as a record's default rendering.
     */
    @Override
    public String toString() {
        return name;
    }
}
