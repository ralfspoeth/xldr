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

    /**
     * The rule this class's documentation always asserted, and which lived in no
     * code until 0.40: shared with {@link ProcedureCall}, whose arguments are
     * evaluated with the same nothing in hand at the other end of the load.
     */
    public VarSpec {
        RowIndependence.refuseFieldAnywhere("var '" + name + "'",
                "a var is evaluated once before the first record is read, so there is no record to take"
                        + " a field from. Map the column to the field directly instead",
                source);
    }
}
