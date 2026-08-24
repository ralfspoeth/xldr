package io.github.ralfspoeth.xldr.spec;

import java.io.Serializable;
import java.util.List;

/**
 * A procedure in the target database, called once after the input has been
 * loaded and before the load is committed - closing a batch, reconciling what
 * arrived, refreshing what depends on it.
 * <p>
 * <strong>Not a {@link ValueSource}</strong>, and that is the whole difference
 * from {@link ValueSource.FunctionCall}. A function yields a value and therefore
 * belongs wherever a value belongs; a procedure yields nothing, so putting it in
 * that hierarchy would add a case every exhaustive switch has to name and then
 * discard. It stands beside {@link VarSpec} instead: a thing a spec declares
 * rather than a thing a column can be. It has no return type for the same
 * reason - there is no OUT parameter to register, and {@code {call name(?)}} is
 * the whole statement.
 * <p>
 * <strong>Inside the transaction.</strong> The loader runs these after the last
 * record and before the commit, on the load's own connection, so a procedure
 * sees the new rows before anyone else does and a procedure that throws rolls
 * the whole file back. That keeps the file the unit of work: all of it happened
 * or none of it did, which is the promise the loader already makes about
 * records and would otherwise have to qualify.
 * <p>
 * <strong>Arguments are var sources.</strong> Each is a {@link ValueSource} of
 * its own and each is evaluated with no record in hand, so a {@link
 * ValueSource.Field} is refused at any depth - the same rule a {@link VarSpec}
 * is held to, shared with it, and reached from the other end of the load. A
 * {@link ValueSource.Var} among them resolves to the value the var was given at
 * the <em>start</em>: the batch a transform closes is the one the load opened.
 * <p>
 * The dependency this creates is on the target's <em>schema</em> - the procedure
 * has to be there - and on no dialect, {@code {call name(?)}} being JDBC's own
 * escape for exactly this and the name being checked to be a name.
 *
 * @param name      the procedure, optionally qualified. Each dot-separated part
 *                  must be an identifier, this being one of the two places where
 *                  what a spec says reaches the text of a statement
 * @param arguments the arguments, in order, each evaluated once after the load
 */
public record ProcedureCall(String name, List<ValueSource> arguments) implements Serializable {

    public ProcedureCall {
        CallableName.refuseUncallable("procedure", name);
        arguments = List.copyOf(arguments);
        for (var argument : arguments) {
            RowIndependence.refuseFieldAnywhere("transform '" + name + "'",
                    "a transform runs after the last record has been read, so there is no record to take"
                            + " a field from. Declare a var of the input and pass that instead",
                    argument);
        }
    }
}
