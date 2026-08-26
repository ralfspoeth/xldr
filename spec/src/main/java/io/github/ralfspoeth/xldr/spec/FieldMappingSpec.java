package io.github.ralfspoeth.xldr.spec;

import java.io.Serializable;

/**
 * Maps one database column to its {@link ValueSource}.
 * <p>
 * Any source but a {@link ValueSource.FunctionCall}, which belongs to a {@link
 * VarSpec}: a function is called once per load, and a column is bound once per
 * record. This is the mirror of the rule on a var, which may hold anything but a
 * {@link ValueSource.Field} - each is meaningless where the other belongs, and a
 * spec saying either is wrong in a way the document alone shows.
 *
 * @param column the target column
 * @param source where the column's value comes from
 */
public record FieldMappingSpec(String column, ValueSource source) implements Serializable {

    public FieldMappingSpec {
        refuseCallAnywhere(column, source);
    }

    /**
     * Refuses a call, however deeply it is buried - as the source itself, or as a
     * lookup's key.
     * <p>
     * Recursive for the same reason the rule on a var is: a lookup's conditions
     * are evaluated wherever the lookup is, so a call hidden in one is a call in
     * a column. Not through a call's own arguments, since a call cannot be here
     * at all.
     */
    private static void refuseCallAnywhere(String column, ValueSource source) {
        switch (source) {
            case ValueSource.FunctionCall(var name, _, _) -> throw new IllegalArgumentException(
                    "column '" + column + "' calls '" + name + "', which it cannot: a function is called"
                            + " once per load and a column is bound once per record. Declare it as a var of"
                            + " the input and map this column to that var");
            case ValueSource.Lookup(_, _, var conditions) ->
                    conditions.values().forEach(key -> refuseCallAnywhere(column, key));
            case ValueSource.Constant _, ValueSource.Var _, ValueSource.Expr _, ValueSource.Field _ -> {
                // the four a column may be: read per record, or resolved once and
                // bound per record, and none of them reaching the database again
            }
        }
    }
}
