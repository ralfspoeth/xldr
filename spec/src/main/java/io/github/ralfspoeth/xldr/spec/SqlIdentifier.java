package io.github.ralfspoeth.xldr.spec;

import java.io.Serializable;
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
 * @param name the identifier exactly as the spec wrote it, quotes and all
 */
public record SqlIdentifier(String name) implements Serializable {

    private static final Pattern QUOTED = Pattern.compile("\".*\"");

    public SqlIdentifier {
        requireNonNull(name, "an identifier needs a name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("an identifier may not be blank");
        }
    }

    /** whether the spec quoted this name, and so meant it exactly as written */
    public boolean quoted() {
        return QUOTED.matcher(name).matches();
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
     */
    public String unquoted() {
        return quoted() ? name.substring(1, name.length() - 1) : name;
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
