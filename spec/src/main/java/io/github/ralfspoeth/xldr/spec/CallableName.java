package io.github.ralfspoeth.xldr.spec;

import java.util.regex.Pattern;

/**
 * The name of something a spec asks the database to run - a {@link
 * ValueSource.FunctionCall}'s function, a {@link ProcedureCall}'s procedure.
 * <p>
 * Deliberately narrower than {@link SqlIdentifier}, which tolerates a quoted
 * name because a column may need one. A routine whose name has to be quoted is
 * beyond what this is for, and admitting quotes would mean admitting every
 * character they can contain.
 */
final class CallableName {

    private static final Pattern PLAIN_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private CallableName() {
    }

    /**
     * Refuses a name that could be anything but a name.
     * <p>
     * Every other value a spec contributes is bound as a parameter or folded as
     * an identifier; a callable name is written into the call escape, so it is
     * the only place where what a spec says becomes part of a statement's text.
     * A name is therefore one or more identifiers separated by dots -
     * {@code my_proc}, {@code app.my_proc}, {@code warehouse.app.my_proc} - and
     * anything carrying a bracket, a quote, a semicolon or whitespace is refused
     * rather than folded into something harmless-looking.
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
