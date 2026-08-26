package io.github.ralfspoeth.xldr.spec;

/**
 * What a value source may hold where there is no record to read.
 * <p>
 * Two places in a spec are evaluated with nothing in hand: a {@link VarSpec},
 * before the first record, and a {@link ProcedureCall}'s arguments, after the
 * last one. Neither may name a field, and the rule is the same rule rather than
 * two that resemble each other - which is why it is here and not copied into
 * both.
 * <p>
 * {@link FieldMappingSpec} keeps its own mirror of this, refusing a call where a
 * column stands. That one is not shared: it has one home, and a rule with one
 * caller belongs where its subject is.
 */
final class RowIndependence {

    private RowIndependence() {
    }

    /**
     * Refuses a field, however deeply it is buried.
     * <p>
     * Recursive, because a field can hide a level down: as any of a lookup's
     * conditions, or as an argument to a call. Both are evaluated in the same
     * breath as whatever holds them and have exactly as little to read from.
     * <p>
     * Provable from the document alone - no arrangement of the input could make
     * such a spec work - and this project's habit is to refuse such a thing when
     * it is written rather than when it is run. The rule used to live in
     * {@link VarSpec}'s prose and in no code, so a spec naming a field in a var
     * read cleanly, deployed, and failed on the first file.
     *
     * @param subject what to call the offender, e.g. {@code "var 'batch'"}
     * @param reason  why it cannot read a field, in the subject's own terms
     * @param source  the source to walk
     */
    static void refuseFieldAnywhere(String subject, String reason, ValueSource source) {
        switch (source) {
            case ValueSource.Field(var fieldName) -> throw new IllegalArgumentException(
                    subject + " reads the input field '" + fieldName + "', which it cannot: " + reason);
            case ValueSource.Lookup(_, _, var conditions) ->
                    conditions.values().forEach(key -> refuseFieldAnywhere(subject, reason, key));
            case ValueSource.FunctionCall(_, _, var arguments) ->
                    arguments.forEach(argument -> refuseFieldAnywhere(subject, reason, argument));
            case ValueSource.Constant _, ValueSource.Var _, ValueSource.Expr _ -> {
                // nothing that could hold a field: a constant is a literal, a var
                // reference is a name resolved among the vars, and an expression's
                // names are resolved by the loader - which, with no record in
                // hand, resolves them against the vars and the ambient values
            }
        }
    }
}
