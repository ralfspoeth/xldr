package io.github.ralfspoeth.xldr.spec;

import org.jspecify.annotations.Nullable;

import java.io.Serializable;
import java.util.HashSet;
import java.util.List;

/**
 * Maps the records of one record selector to one database table.
 *
 * @param recordSelector the record selector whose records are mapped
 * @param table          the target table
 * @param fieldMappings  the column mappings
 * @param limit          at most this many records are inserted; {@code null}
 *                       means no limit
 */
public record RecordMappingSpec(
        String recordSelector,
        SqlIdentifier table,
        List<FieldMappingSpec> fieldMappings,
        @Nullable Integer limit
) implements Serializable {

    /**
     * Canonical constructor.
     */
    public RecordMappingSpec {
        fieldMappings = List.copyOf(fieldMappings);
        if (limit != null && limit < 0) {
            throw new IllegalArgumentException("limit must not be negative: " + limit);
        }
        refuseRepeatedColumns(table, fieldMappings);
    }

    /**
     * The same with the table named as text, which is how a reader has it and
     * how a test writes it. The record selector is not an identifier - it names
     * something in the spec, not in the database - so it stays a string in both.
     */
    public RecordMappingSpec(String recordSelector, String table,
                             List<FieldMappingSpec> fieldMappings, @Nullable Integer limit) {
        this(recordSelector, new SqlIdentifier(table), fieldMappings, limit);
    }

    /**
     * One column is written once.
     * <p>
     * The loader builds an insert from these, in order, so two field mappings
     * onto one column produce {@code insert into t(name, name) values(?, ?)} -
     * which every database rejects, on the first record of the first file, with
     * the feed deployed and a producer waiting. It is the mirror of the rule
     * {@link RecordSelectorSpec} applies to field selector names, and refused
     * here for the same reason: a spec that cannot load is better refused when it
     * is read.
     * <p>
     * A {@link SqlIdentifier} is equal to another when the database would call
     * them one column, so the set below does the comparing: {@code name} and
     * {@code NAME} collide in it without anything here having to fold them. A
     * quoted name is a different identifier and so a different column, and a
     * spec meaning both {@code "name"} and {@code name} still says so.
     * <p>
     * Refused rather than resolved. Nobody writes a duplicate on purpose: it is
     * a column that was meant to be a different one, so the column the author
     * intended is unwritten, and picking either of the two values would leave
     * that true and silent.
     */
    private static void refuseRepeatedColumns(SqlIdentifier table, List<FieldMappingSpec> fieldMappings) {
        var seen = new HashSet<SqlIdentifier>();
        var repeated = fieldMappings.stream()
                .map(FieldMappingSpec::column)
                .filter(column -> !seen.add(column))
                .distinct()
                .toList();
        if (!repeated.isEmpty()) {
            throw new IllegalArgumentException("the mapping into '" + table + "' writes "
                    + repeated + " more than once; an insert names each column once, so this spec"
                    + " could not load. The usual cause is a column that was meant to be a"
                    + " different one, which is then not written at all");
        }
    }
}
