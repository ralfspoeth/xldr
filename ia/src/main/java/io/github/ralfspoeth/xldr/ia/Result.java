package io.github.ralfspoeth.xldr.ia;

import java.util.List;
import java.util.stream.Stream;

public record Result(List<Field> fields, Stream<Row> rows) {}
