package io.github.ralfspoeth.xldr.spec;

import java.util.Locale;
import java.util.regex.Pattern;

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
 * Here rather than in the loader, where it began, because two other places need
 * the same answer and a rule about identifiers is a rule about the vocabulary a
 * spec is written in. {@link RecordMappingSpec} compares columns to refuse a
 * mapping that writes one twice, which it cannot do by string equality - a
 * spec naming {@code name} and {@code NAME} names one column, and would build an
 * insert that mentions it twice.
 */
public final class SqlIdentifier {

    private static final Pattern QUOTED = Pattern.compile("\".*\"");

    private SqlIdentifier() {
    }

    /**
     * The name as it should be written into SQL, and the form in which two names
     * may be compared for being the same column.
     * <p>
     * {@link Locale#ROOT} is required rather than tidy: under a Turkish default
     * locale {@code "id".toUpperCase()} yields {@code "İD"}, and a server would
     * then address a different column depending on where it was started.
     */
    public static String folded(String name) {
        return quoted(name) ? name : name.toUpperCase(Locale.ROOT);
    }

    /** whether the spec quoted this name, and so meant it exactly as written */
    public static boolean quoted(String name) {
        return QUOTED.matcher(name).matches();
    }
}
