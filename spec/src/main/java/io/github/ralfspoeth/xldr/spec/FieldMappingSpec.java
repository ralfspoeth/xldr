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
public record FieldMappingSpec(SqlIdentifier column, ValueSource source) implements Serializable {

    public FieldMappingSpec {
        refuseWhatAcolumnCannotHold(column, source);
    }

    /** the same with the column named as text, which is how a reader has it */
    public FieldMappingSpec(String column, ValueSource source) {
        this(new SqlIdentifier(column), source);
    }

    /**
     * The two things a column's source may not be, however deeply they are
     * buried: a call, and a regex over a lookup.
     * <p>
     * Recursive for the same reason the rule on a var is: a lookup's conditions
     * are evaluated wherever the lookup is, so a call hidden in one is a call in
     * a column. Not through a call's own arguments, since a call cannot be here
     * at all.
     * <p>
     * Both are provable from the document, which is what puts them here rather
     * than in the loader. The second used to be the loader's, thrown while
     * planning the insert - so a spec an editor called valid was read cleanly,
     * deployed, and failed on its first file. That is the failure this class
     * exists to move earlier, and it had one of its own.
     */
    private static void refuseWhatAcolumnCannotHold(SqlIdentifier column, ValueSource source) {
        switch (source) {
            case ValueSource.FunctionCall(var name, _, _) -> throw new IllegalArgumentException(
                    "column '" + column + "' calls '" + name + "', which it cannot: a function is called"
                            + " once per load and a column is bound once per record. Declare it as a var of"
                            + " the input and map this column to that var");
            case ValueSource.Regex(var over, var pattern, _) -> {
                if (over instanceof ValueSource.Lookup(var table, var of, _)) {
                    throw new IllegalArgumentException("column '" + column + "' matches /" + pattern
                            + "/ against " + of + " of " + table + ", which it cannot: a column's regex"
                            + " runs this side of the database, on a value bound as a parameter, and a"
                            + " lookup is a subquery of the insert whose value only exists once the"
                            + " statement runs. Read the lookup into a var and match against that, or"
                            + " match in the view the lookup reads");
                }
                refuseWhatAcolumnCannotHold(column, over);
            }
            case ValueSource.Lookup(_, _, var conditions) ->
                    conditions.values().forEach(key -> refuseWhatAcolumnCannotHold(column, key));
            case ValueSource.Constant _, ValueSource.Var _, ValueSource.Expr _, ValueSource.Field _ -> {
                // the four a column may be: read per record, or resolved once and
                // bound per record, and none of them reaching the database again
            }
        }
    }
}
