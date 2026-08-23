package io.github.ralfspoeth.xldr.ia;

import java.util.List;
import java.util.stream.Stream;

/**
 * The outcome of parsing one record selector: the fields the adapter resolved,
 * and a lazy stream of the matching records. The {@code rows} stream is read
 * once and should be closed by the caller.
 *
 * @param fields the fields exposed on each row
 * @param rows   the selected records, as a lazy stream
 */
public record Result(List<Field> fields, Stream<Row> rows) {}
