package io.github.ralfspoeth.xldr.spec;

import java.util.regex.Pattern;

/**
 * The name of something a spec asks the database to run - a {@link
 * ValueSource.FunctionCall}'s function, a {@link ProcedureCall}'s procedure.
 * <p>
 * Deliberately narrower than {@link SqlIdentifier}, which tolerates a quoted
 * name because a column may need one. A routine whose name has to be quoted is
 * beyond what this is for, and admitting quotes would mean admitting every
 * character they can contain. Narrower in the unquoted case too: no {@code $} or
 * {@code #}, which Oracle allows in a column and this has never needed.
 * <p>
 * The two are otherwise the same rule for the same reason, and until 0.50 only
 * this half of it existed. See {@link SqlIdentifier} for the other.
 */
final class CallableName {

    private static final Pattern PLAIN_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private CallableName() {
    }

    /**
     * Refuses a name that could be anything but a name.
     * <p>
     * A spec contributes two kinds of thing to a statement: values, which are
     * bound as parameters and can therefore be anything, and names, which are
     * written into the text and therefore cannot. A callable name goes into the
     * call escape and a table or column goes into the insert - both are the
     * second kind, and both are held to a shape.
     * <p>
     * This used to claim to be the only such place, which was wrong from the
     * day it was written: {@link SqlIdentifier} was concatenated into every
     * insert and every lookup subquery with nothing but a blank check in front
     * of it until 0.50. The claim was worth making and worth checking, and
     * nobody checked it.
     * <p>
     * A name here is one or more identifiers separated by dots -
     * {@code my_proc}, {@code app.my_proc}, {@code warehouse.app.my_proc} - and
     * anything carrying a bracket, a quote, a semicolon or whitespace is refused
     * rather than folded into something harmless-looking. The dots are the one
     * place this is wider than {@code SqlIdentifier}, which refuses them: a
     * routine may be qualified because a package member has no other spelling,
     * where a table says where it lives in {@code target.properties}.
     *
     * @param kind what to call the thing in the message, e.g. {@code "function"}
     * @param name the name as the spec wrote it
     */
    static void refuseUncallable(String kind, String name) {
        if (name.isBlank()) {
            throw new IllegalArgumentException("a " + kind + " call needs a name");
        }
        for (var part : name.split("\\.", -1)) {
            if (!PLAIN_IDENTIFIER.matcher(part).matches()) {
                throw new IllegalArgumentException("'" + name + "' is not a " + kind + " this may call: '"
                        + part + "' is not an identifier. A name is one or more identifiers separated by"
                        + " dots, each a letter or underscore followed by letters, digits or underscores");
            }
        }
    }
}
