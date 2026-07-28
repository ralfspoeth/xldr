package io.github.ralfspoeth.xldr.csv;

import io.github.ralfspoeth.xldr.ia.Field;
import io.github.ralfspoeth.xldr.ia.InputAdapter;
import io.github.ralfspoeth.xldr.ia.Result;
import io.github.ralfspoeth.xldr.ia.Formats;
import io.github.ralfspoeth.xldr.ia.Row;
import io.github.ralfspoeth.xldr.spec.DataType;
import io.github.ralfspoeth.xldr.spec.FieldSelectorSpec;
import io.github.ralfspoeth.xldr.spec.InputSpec;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToIntFunction;
import java.util.stream.Gatherer;
import java.util.stream.Stream;

/**
 * Reads records out of a separated-value file.
 * <p>
 * A record is a line as {@link BufferedReader} sees one, so a file may be
 * terminated with {@code \n}, {@code \r\n} or {@code \r} and is read the same
 * way whatever platform it was written on - unless a quoted field holds a line
 * break, in which case the record runs on until the field closes. The lines are
 * read lazily, so a file is never held in memory as a whole; only the record
 * being assembled is.
 */
class CsvFileHandler implements InputAdapter {

    /**
     * How many physical lines one record may span before an unterminated quote
     * is called what it is. Without a limit a single stray quote would swallow
     * the rest of the file into one record and the load would report one row.
     */
    private static final int MAX_LINES_PER_RECORD = 1_000;

    private final String fieldSeparator;
    private final Charset charset;
    private final boolean header;
    private final Formats formats;
    /** what opens and closes a quoted field, or null where quotes are ordinary characters */
    private final Character quote;
    /** what begins a comment outside a quoted field, or null where nothing does */
    private final Character comment;
    /** what an empty line means */
    private final EmptyLine emptyLine;

    private final InputSpec inputSpec;

    CsvFileHandler(String fieldSeparator, Charset charset, boolean header, Character quote,
                   Character comment, EmptyLine emptyLine, Formats formats, InputSpec spec) {
        this.fieldSeparator = fieldSeparator;
        this.charset = charset;
        this.header = header;
        this.quote = quote;
        this.comment = comment;
        this.emptyLine = emptyLine;
        this.formats = formats;
        this.inputSpec = spec;
    }

    /**
     * One data line. A column the line does not have at all is null; one it has
     * is converted according to the field's declared {@link DataType}, which
     * strips it and treats a blank as absent.
     */
    private record Line(String[] values, ToIntFunction<String> index, Map<String, DataType> types,
                        Formats formats) implements Row {
        @Override
        public Object get(String name) {
            var i = index.applyAsInt(name);
            if (i < 0 || i >= values.length) {
                return null;
            }
            return formats.parse(types.get(name), values[i]);
        }
    }

    @Override
    public Result parse(InputStream source, String recordSelector, Set<String> fieldSelectors) throws IOException {
        var record = inputSpec.recordSelectors()
                .stream()
                .filter(rss -> rss.name().equals(recordSelector))
                .findFirst()
                .orElse(null);
        if (record == null) {
            return new Result(List.of(), Stream.empty());
        }
        var fields = fields(recordSelector, fieldSelectors);
        // the reader is not closed here: the stream belongs to the caller, who
        // closes it once every record mapping of the file has been read
        var lines = new BufferedReader(new InputStreamReader(source, charset));

        ToIntFunction<String> index;
        // what the header cost, so that a later complaint can name the line of
        // the file rather than the line of the remainder
        long linesRead = 0;
        if (header) {
            var head = headerLine(lines);
            linesRead = head.linesRead();
            if (head.line() == null) {
                return new Result(fields, Stream.empty());
            }
            index = indexOfHeader(head.line());
        } else {
            index = positionalIndex();
        }

        var types = typesOf(record.fieldSelectors());
        // the record selector's selector, if set, is the value the first column
        // must equal for a line to belong to this record type - the discriminator
        // of an interleaved, headerless file; absent means every line matches
        var discriminator = record.selector();
        var rows = lines.lines()
                .gather(records(linesRead))
                .filter(values -> matches(discriminator, values))
                .map(values -> (Row) new Line(values, index, types, formats));
        return new Result(fields, rows);
    }

    /**
     * Assembles physical lines into records: a quoted field may hold a line
     * break, so a record is a line only until one does.
     * <p>
     * A line that leaves a quoted field open is held and continued, which is why
     * an empty line is only empty between records - inside a quoted field it is
     * part of the value, and the same goes for a comment character. Nothing
     * accumulates beyond the record in hand, so the reading stays as lazy as it
     * was.
     */
    private Gatherer<String, ?, String[]> records(long linesAlreadyRead) {
        return Gatherer.ofSequential(() -> new Partial(linesAlreadyRead), (partial, line, downstream) -> {
            partial.append(line);
            var scan = Csv.scan(partial.text(), fieldSeparator, quote, comment);
            if (scan.open()) {
                if (partial.longerThan(MAX_LINES_PER_RECORD)) {
                    throw new IllegalArgumentException(partial.unterminated(MAX_LINES_PER_RECORD));
                }
                return true;
            }
            var text = partial.text();
            partial.clear();
            if (scan.blank()) {
                // a comment is never the end of anything; an empty line is,
                // where the feed says a trailer follows the data
                return !(text.isBlank() && emptyLine == EmptyLine.STOP);
            }
            return downstream.push(scan.fields());
        }, (partial, _) -> {
            if (!partial.isEmpty()) {
                throw new IllegalArgumentException(partial.unterminated(null));
            }
        });
    }

    /**
     * The record being assembled. It remembers where it started so that an
     * unterminated quote names the line that opened it - the line an author has
     * to look at - rather than the end of the file, where the trouble only
     * becomes apparent.
     */
    private static final class Partial {
        private final StringBuilder text = new StringBuilder();
        private int lines;
        /** lines of the file read so far, the header among them */
        private long read;
        private long startedAt;

        Partial(long linesAlreadyRead) {
            this.read = linesAlreadyRead;
        }

        boolean isEmpty() {
            return lines == 0;
        }

        boolean longerThan(int limit) {
            return lines > limit;
        }

        void append(String line) {
            read++;
            if (lines == 0) {
                startedAt = read;
            } else {
                text.append('\n');
            }
            text.append(line);
            lines++;
        }

        String text() {
            return text.toString();
        }

        void clear() {
            text.setLength(0);
            lines = 0;
        }

        String unterminated(Integer limit) {
            return "unterminated quoted field: the record starting on line " + startedAt
                    + " is still open after " + lines + " line(s)"
                    + (limit == null ? " at the end of the file" : ", the limit being " + limit)
                    + ". A stray quote at the start of a field would do this.";
        }
    }

    /**
     * The declared type of every field of a record selector; a field without one
     * is read as text.
     */
    private static Map<String, DataType> typesOf(Collection<FieldSelectorSpec> fieldSelectors) {
        Map<String, DataType> types = new HashMap<>();
        for (var fs : fieldSelectors) {
            types.putIfAbsent(fs.name(), typeOf(fs));
        }
        return types;
    }

    private static DataType typeOf(FieldSelectorSpec fs) {
        return fs.dataType() == null ? DataType.STRING : fs.dataType();
    }

    private static boolean matches(String discriminator, String[] values) {
        return discriminator == null || discriminator.isBlank()
                || (values.length > 0 && discriminator.strip().equals(values[0].strip()));
    }

    private List<Field> fields(String recordSelector, Set<String> fieldSelectors) {
        return inputSpec.recordSelectors()
                .stream()
                .filter(rs -> rs.name().equals(recordSelector))
                .flatMap(rs -> rs.fieldSelectors().stream())
                .filter(fs -> fieldSelectors.contains(fs.name()))
                .map(fs -> new Field(fs.name(), typeOf(fs).clazz()))
                .toList();
    }

    /**
     * The header row and what it cost to reach it.
     *
     * @param line      the header line, or {@code null} where there is none
     * @param linesRead how many lines of the file were consumed getting there,
     *                  so that the records that follow can still be numbered
     *                  from the top of the file
     */
    private record Header(String line, long linesRead) {
    }

    /**
     * The header row, looked for past any banner of comments and empty lines -
     * the shape of a generated file, which says when it was produced before it
     * says what its columns are.
     *
     * @return the header, whose line is {@code null} where the file has none, or
     * where an empty line ends the data before one is reached
     */
    private Header headerLine(BufferedReader lines) throws IOException {
        long read = 0;
        for (var line = lines.readLine(); line != null; line = lines.readLine()) {
            read++;
            if (!Csv.scan(line, fieldSeparator, quote, comment).blank()) {
                return new Header(line, read);
            }
            if (line.isBlank() && emptyLine == EmptyLine.STOP) {
                return new Header(null, read);
            }
        }
        return new Header(null, read);
    }

    /**
     * The header is read as one line: a column name is quoted often enough, but
     * one that runs over a line break is not a thing a producer writes.
     */
    private ToIntFunction<String> indexOfHeader(String headerLine) {
        Map<String, Integer> index = new HashMap<>();
        var headers = Csv.scan(headerLine, fieldSeparator, quote, comment).fields();
        for (int i = 0; i < headers.length; i++) {
            index.putIfAbsent(headers[i], i);
        }
        return name -> index.getOrDefault(name, -1);
    }

    private static ToIntFunction<String> positionalIndex() {
        return name -> {
            try {
                return Integer.parseInt(name.strip()) - 1;
            } catch (NumberFormatException e) {
                return -1;
            }
        };
    }
}
