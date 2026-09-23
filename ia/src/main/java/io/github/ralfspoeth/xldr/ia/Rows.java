package io.github.ralfspoeth.xldr.ia;

import java.util.Collection;

/**
 * Reading a parsed record the way a load reads it.
 *
 * <p>There is one rule here and it is easy to get wrong: <strong>walking the rows
 * is not reading them.</strong> Several adapters convert a value inside
 * {@link Row#get}, so a record that cannot be read fails only when something
 * asks for the value. Code that drains the stream without asking passes over a
 * broken file in silence and reports success.
 *
 * <p>That is not a hypothetical. The conformance kit's check on obligation 7 did
 * exactly this when it was written at 0.51, and three of the four adapters'
 * deliberately broken samples went through it without complaint - the check
 * could not fail, whatever the adapter did. And {@code xldr check} read only the
 * first few records' values, the ones it printed, so a file whose forty
 * thousandth record held an unparseable date passed a command whose entire
 * purpose is to catch that before a deployment.
 *
 * <p>Both now call this, which is why it lives here rather than in either of
 * them: the obligation is part of the SPI's contract, so the discipline for
 * honouring it belongs beside the SPI and not in one of its two readers.
 */
public final class Rows {

    private Rows() {
    }

    /**
     * Reads every named field of one row, discarding the values.
     *
     * <p>For a caller that wants the values, asking for them is itself a read and
     * this method is unnecessary; it is for the rows a caller will not otherwise
     * touch, so that an adapter converting in {@code get} converts them now.
     *
     * @param row        the record to read
     * @param fieldNames the fields to ask for, normally the ones a mapping uses
     * @throws RuntimeException whatever the adapter throws for a value it cannot
     *                          convert, from the first such field
     */
    public static void read(Row row, Collection<String> fieldNames) {
        for (var name : fieldNames) {
            row.get(name);
        }
    }

    /**
     * Reads a whole result the way a load would: every field of every record.
     *
     * <p>Closes the stream, since a {@link Result}'s rows are lazy and usually
     * hold the input open.
     *
     * @param result what an adapter returned from {@code parse}
     * @return how many records there were
     * @throws RuntimeException whatever the adapter throws for the first value it
     *                          cannot convert, which by the SPI's seventh
     *                          obligation names the record it happened at
     */
    public static long readThrough(Result result) {
        var names = result.fields().stream().map(Field::name).toList();
        var counted = new long[1];
        try (var rows = result.rows()) {
            // forEach and a counter rather than peek().count(): since Java 9
            // count() may skip the pipeline altogether when it can determine the
            // size without running it, and peek is documented as possibly not
            // executing in that case. That would read no field of any row while
            // returning the right number - this class's own bug, in this class
            rows.forEach(row -> {
                read(row, names);
                counted[0]++;
            });
        }
        return counted[0];
    }
}
