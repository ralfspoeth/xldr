package io.github.ralfspoeth.xldr.spec;

import java.io.Serializable;

/**
 * An input-level variable: a named value computed once at the start of a load
 * and then reused wherever a {@link ValueSource.Var} references it - a code
 * looked up once and stamped onto every row, or a constant named for reuse.
 * <p>
 * Because it is evaluated with no record in hand, a variable's {@code source}
 * must be row-independent: anything but a {@link ValueSource.Field}, at any
 * depth. A {@link ValueSource.Constant}, an {@link ValueSource.Expr}, a {@link
 * ValueSource.Lookup}, a {@link ValueSource.FunctionCall}, or another {@link
 * ValueSource.Var} declared earlier - and a lookup's key or a call's arguments
 * are held to the same rule, since they are evaluated at the same moment and
 * with the same nothing in hand.
 *
 * @param name   how a {@link ValueSource.Var} refers to this variable
 * @param source how the variable's value is produced
 */
public record VarSpec(String name, ValueSource source) implements Serializable {

    public VarSpec {
        refuseFieldAnywhere(name, source);
    }

    /**
     * Refuses a field, however deeply it is buried.
     * <p>
     * The rule was written here in prose and enforced nowhere: a spec naming a
     * field in a var read cleanly, deployed, and failed on the first file with an
     * exception from the loader. It is provable from the document alone - a var
     * is evaluated before any record exists, so no arrangement of the file could
     * make it work - and this project's habit is to refuse such a thing when the
     * thing is written rather than when it is run.
     * <p>
     * Recursive, because a field can hide one level down: as a lookup's key, or
     * as an argument to a call. Both are evaluated in the same breath as the var
     * itself and have exactly as little to read from.
     */
    private static void refuseFieldAnywhere(String name, ValueSource source) {
        switch (source) {
            case ValueSource.Field(var fieldName) -> throw new IllegalArgumentException(
                    "var '" + name + "' reads the input field '" + fieldName + "', which it cannot:"
                            + " a var is evaluated once before the first record is read, so there is no"
                            + " record to take a field from. Map the column to the field directly instead");
            case ValueSource.Lookup(_, _, _, var key) -> refuseFieldAnywhere(name, key);
            case ValueSource.FunctionCall(_, _, var arguments) ->
                    arguments.forEach(argument -> refuseFieldAnywhere(name, argument));
            case ValueSource.Constant _, ValueSource.Var _, ValueSource.Expr _ -> {
                // nothing that could hold a field: a constant is a literal, a var
                // reference is a name resolved among the vars, and an expression's
                // names are resolved by the loader - which, with no record in
                // hand, resolves them against the vars and the ambient values
            }
        }
    }
}
