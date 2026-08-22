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
        String table,
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
     * Compared as {@link SqlIdentifier#folded folded} identifiers rather than as
     * strings, because {@code name} and {@code NAME} are one column and would
     * produce exactly that insert. A quoted name is left alone, so a spec meaning
     * both {@code "name"} and {@code name} - two genuinely different columns -
     * still says so.
     * <p>
     * Refused rather than resolved. Nobody writes a duplicate on purpose: it is
     * a column that was meant to be a different one, so the column the author
     * intended is unwritten, and picking either of the two values would leave
     * that true and silent.
     */
    private static void refuseRepeatedColumns(String table, List<FieldMappingSpec> fieldMappings) {
        var seen = new HashSet<String>();
        var repeated = fieldMappings.stream()
                .map(FieldMappingSpec::column)
                .filter(column -> !seen.add(SqlIdentifier.folded(column)))
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
