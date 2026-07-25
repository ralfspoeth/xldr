package io.github.ralfspoeth.xldr.spec;

import java.io.Serializable;

/**
 * An input-level variable: a named value computed once at the start of a load
 * and then reused wherever a {@link ValueSource.Var} references it - a code
 * looked up once and stamped onto every row, or a constant named for reuse.
 * <p>
 * Because it is evaluated with no record in hand, a variable's {@code source}
 * must be row-independent: a {@link ValueSource.Constant}, {@link
 * ValueSource.Lookup} or another {@link ValueSource.Var} declared earlier -
 * never a {@link ValueSource.Field}.
 *
 * @param name   how a {@link ValueSource.Var} refers to this variable
 * @param source how the variable's value is produced
 */
public record VarSpec(String name, ValueSource source) implements Serializable {}
