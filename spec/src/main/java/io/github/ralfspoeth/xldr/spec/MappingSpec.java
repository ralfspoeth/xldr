package io.github.ralfspoeth.xldr.spec;

import java.util.Collection;
import java.util.List;

/**
 * A complete mapping specification: how to parse an input, how its records map
 * onto database tables, and what to run once the input has been loaded.
 *
 * @param inputSpec          how the file is parsed into records and fields
 * @param recordMappingSpecs the record-to-table mappings
 * @param transforms         procedures called once after every mapping has run
 *                           and before the load is committed, in the order
 *                           written. Usually empty; see {@link ProcedureCall}
 */
public record MappingSpec(
        InputSpec inputSpec,
        Collection<RecordMappingSpec> recordMappingSpecs,
        List<ProcedureCall> transforms
) {
    /**
     * Canonical constructor.
     */
    public MappingSpec {
        recordMappingSpecs = List.copyOf(recordMappingSpecs);
        transforms = List.copyOf(transforms);
    }

    /**
     * A spec that loads and does nothing afterwards, which is nearly all of them.
     * <p>
     * Here because {@code transforms} is optional in both file formats and empty
     * in every spec written before 0.41, so requiring an empty list at every
     * construction site would be ceremony rather than information.
     */
    public MappingSpec(InputSpec inputSpec, Collection<RecordMappingSpec> recordMappingSpecs) {
        this(inputSpec, recordMappingSpecs, List.of());
    }
}
