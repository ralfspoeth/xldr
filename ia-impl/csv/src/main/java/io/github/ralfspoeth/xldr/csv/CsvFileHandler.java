package io.github.ralfspoeth.xldr.csv;

import io.github.ralfspoeth.xldr.ia.*;
import io.github.ralfspoeth.xldr.spec.*;
import org.jspecify.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.*;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;
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
 * being assembled is held in memory.
 */
class CsvFileHandler implements InputAdapter {

    /**
     * How many physical lines one record may span before an unterminated quote
     * is called what it is. Without a limit a single stray quote would swallow
     * the rest of the file into one record and the load would report one row.
     */
    private static final int MAX_LINES_PER_RECORD = 256;

    private final String fieldSeparator;
    private final Charset charset;
    private final boolean header;
    private final Formats formats;
    /**
     * what opens and closes a quoted field, or null where quotes are ordinary characters
     */
    private final @Nullable Character quote;
    /**
     * what begins a comment outside a quoted field, or null where nothing does
     */
    private final @Nullable Character comment;
    /**
     * what an empty line means
     */
    private final EmptyLine emptyLine;
    /**
     * whether a field the spec does not declare may be a column of that name
     */
    private final boolean fieldsFromHeader;

    private final InputSpec inputSpec;

    CsvFileHandler(
            String fieldSeparator,
            Charset charset,
            boolean header,
            @Nullable Character quote,
            @Nullable Character comment,
            EmptyLine emptyLine,
            boolean fieldsFromHeader,
            Formats formats,
            InputSpec spec
    ) {
        // The other four adapters key their record selectors by name and so
        // refuse a repeat as a side effect of building the map. This one keeps
        // the spec and looks a name up by scanning, which has no such moment -
        // so a spec naming two record selectors 'records' loaded whichever came
        // first, silently and for as long as the feed ran. Found by the
        // conformance kit at 0.51, the first time anything asked all five
        // adapters the same question.
        var named = new HashSet<String>();
        for (var rss : spec.recordSelectors()) {
            if (!named.add(rss.name())) {
                throw new IllegalArgumentException("two record selectors are named '" + rss.name()
                        + "'; a mapping names one of them and could not say which");
            }
        }
        this.fieldSeparator = fieldSeparator;
        this.charset = charset;
        this.header = header;
        this.quote = quote;
        this.comment = comment;
        this.emptyLine = emptyLine;
        this.fieldsFromHeader = fieldsFromHeader;
        this.formats = formats;
        this.inputSpec = spec;
    }

    /**
     * One data line, read by field name: a mapping names a field, and the field
     * says which column it is. A column this particular line stops short of is
     * null - lines are ragged in real files, and that is not the spec's fault;
     * a selector that matched no column of the header at all was refused long
     * before here. One the line has is converted according to the field's
     * declared {@link DataType}, which strips it and treats a blank as absent.
     */
    private record Line(String[] values, Map<String, Integer> positions, Map<String, DataType> types,
                        Formats formats) implements Row {
        @Override
        public @Nullable Object get(String name) {
            int i = positions.getOrDefault(name, -1);
            if (i < 0 || i >= values.length) {
                return null;
            }
            return formats.parse(types.get(name), values[i]);
        }
    }

    /**
     * The record selectors the spec declares, for a complaint about one it does
     * not. Ordered as written, a spec being easier to check against a list in the
     * order its author typed it.
     */
    private List<String> declaredNames() {
        return inputSpec.recordSelectors().stream().map(RecordSelectorSpec::name).toList();
    }

    /**
     * A name the spec does not declare is refused, as it is by every other
     * adapter. This used to answer with an empty result, which reads as a
     * successful load of a file that happened to hold nothing - so a mapping
     * naming {@code peple} would report success over zero rows on a CSV feed and
     * be refused outright on any other. Nothing cross-checks a mapping's
     * {@code recordSelector} against the declared ones, which makes this the
     * place the typo has to surface.
     */
    @Override
    public Result parse(InputStream source, String recordSelector, Set<String> fieldSelectors) throws IOException {
        var record = inputSpec.recordSelectors()
                .stream()
                .filter(rss -> rss.name().equals(recordSelector))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no record selector named "
                        + recordSelector + "; the input spec declares " + declaredNames()));
        var fields = fields(recordSelector, fieldSelectors);
        // the reader is not closed here: the stream belongs to the caller, who
        // closes it once every record mapping of the file has been read
        var lines = new BufferedReader(new InputStreamReader(source, charset));

        Index index;
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
        var positions = positions(record.fieldSelectors(), fieldSelectors, index);
        // which lines are of this kind. Every means all of them, which is what a
        // file holding one record type looks like; a selector is a spec written
        // for a format that has somewhere to point, and there is nowhere here
        var rows = switch (record.locator()) {
            case Locator.Every _ -> lines.lines().gather(records(linesRead));
            case Locator.Where(var test) ->
                    filtered(lines, linesRead, test, index.require(test.at()));
            case Locator.At at -> throw at.wrongBecause(
                    record.name(), "a flat file has no place to point at");
        };
        return new Result(fields, rows.map(values -> new Line(values, positions, types, formats)));
    }

    /**
     * The lines of one kind.
     * <p>
     * The discriminating column is resolved once, through the same {@link Index}
     * the field selectors use - so it may be named or counted, it works with a
     * header or without one, and one that names no column of the file is refused
     * here rather than quietly matching nothing for the length of a load.
     */
    private Stream<String[]> filtered(BufferedReader lines, long linesRead,
                                      Discriminator discriminator, int at) {
        return lines.lines()
                .gather(records(linesRead))
                .filter(values -> discriminator.accepts(at < values.length ? values[at] : null));
    }

    /**
     * Which column each field sits in, resolved once for the file.
     * <p>
     * A field says where to read it with a {@code selector}, which names a column
     * and so wants a header, or with an {@code nth}, which counts the fields of
     * the line and works either way. Its {@code name} is what a mapping calls it
     * by, exactly as in every other adapter - the two are the same word often
     * enough that a spec can leave them alike, but they need not be.
     * <p>
     * Where {@code fieldsFromHeader} is set, a name the spec does not declare is
     * looked for among the columns as it stands, as if the spec had declared it
     * with a selector equal to its name.
     * <p>
     * A declared selector that resolves to no column at all is refused, because
     * there is no reading of the file under which that spec is right: every row
     * would carry a null for it and the load would report success over a column
     * of nothing. The commonest cause is the separator - read a tab-separated
     * file with commas and the whole header is one column - which is why the
     * message says which separator was in use. A column merely missing from
     * <em>some line</em> is still null: that is a short line, not a spec that
     * does not fit its file.
     * <p>
     * The {@code fieldsFromHeader} names are exempt. They are by definition the
     * ones the spec did not declare, so asking for one the header has not got is
     * a question rather than a claim, and null is the answer.
     *
     * @param wanted the field names the mapping uses
     * @param index  resolves a selector to a column position
     * @throws IllegalArgumentException naming a declared selector that matches no
     *                                  column, and what the file offered instead
     */
    private Map<String, Integer> positions(
            Collection<FieldSelectorSpec> fieldSelectors, Set<String> wanted, Index index) {
        Map<String, Integer> positions = new HashMap<>();
        for (var fs : fieldSelectors) {
            // no tie-break needed: RecordSelectorSpec refuses two field selectors
            // of one name. This used to keep the first and say the second was
            // never read from, which is the same excuse we refuse elsewhere for a
            // selector that matches nothing
            positions.put(fs.name(), index.require(fs.selector()));
        }
        if (fieldsFromHeader) {
            for (var name : wanted) {
                positions.computeIfAbsent(name, index.of()::applyAsInt);
            }
        }
        return positions;
    }

    /**
     * How a selector finds its column, and what there was to choose from.
     *
     * @param of        resolves a column <em>name</em>, -1 where the header has
     *                  none such - or where there is no header, names being
     *                  something only a header has
     * @param columns   the header's columns, or {@code null} where the file has no
     *                  header
     * @param separator what the columns were split on, which is the setting a
     *                  reader will want to check first
     */
    private record Index(ToIntFunction<String> of, @Nullable List<String> columns, String separator) {

        /**
         * The column a selector means, or a refusal saying why the file has no
         * such thing.
         * <p>
         * A {@link Selector.Nth} is arithmetic and needs no header - which is the
         * point of having it - though where there <em>is</em> a header it is
         * checked against its width, the header being what says how wide the file
         * is. A {@link Selector.Text} names a column, so it needs a header to name
         * one in.
         */
        int require(Selector selector) {
            return switch (selector) {
                case Selector.Nth nth -> {
                    if (columns != null && nth.n() > columns.size()) {
                        throw new IllegalArgumentException(nth + " of a line whose header carries only "
                                + columns.size() + ": " + shown());
                    }
                    yield nth.index();
                }
                case Selector.Text(var name) -> named(name);
            };
        }

        private int named(String name) {
            if (columns == null) {
                throw new IllegalArgumentException("selector '" + name + "' names a column, but this"
                        + " input is read without a header and so has no column names."
                        + " Count the field instead: \"nth\": <n>");
            }
            int column = of.applyAsInt(name);
            if (column < 0) {
                throw new IllegalArgumentException("selector '" + name + "' names no column of this"
                        + " file. Its header carries " + columns.size() + " column(s): " + shown()
                        + ", split on fieldSeparator '" + visible(separator) + "'");
            }
            return column;
        }

        private String shown() {
            return columns == null ? "[]"
                    : columns.stream().map(Index::visible).collect(Collectors.joining(", ", "[", "]"));
        }

        /**
         * A separator gone wrong puts the whole header in one column, and a
         * message printing that column raw would hide the very characters that
         * explain it.
         */
        private static String visible(String column) {
            return column.replace("\t", "\\t").replace("\r", "\\r").replace("\n", "\\n");
        }
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
                if (partial.longerThanMaxLinesPerRecord()) {
                    throw new IllegalArgumentException(partial.unterminated(MAX_LINES_PER_RECORD));
                }
                return true;
            }
            var text = partial.text();
            partial.clear();
            if (scan.blank()) {
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
        /**
         * lines of the file read so far, the header among them
         */
        private long read;
        private long startedAt;

        Partial(long linesAlreadyRead) {
            this.read = linesAlreadyRead;
        }

        boolean isEmpty() {
            return lines == 0;
        }

        boolean longerThanMaxLinesPerRecord() {
            return lines > MAX_LINES_PER_RECORD;
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

        String unterminated(@Nullable Integer limit) {
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
        return fs.dataType() == null ? DataType.TEXT : fs.dataType();
    }

    /**
     * The fields this parse resolves: those the record selector declares and the
     * mapping asks for, and - where the header supplies them - those it asks for
     * without declaring, which carry no type and so arrive as text.
     */
    private List<Field> fields(String recordSelector, Set<String> fieldSelectors) {
        var declared = inputSpec.recordSelectors()
                .stream()
                .filter(rs -> rs.name().equals(recordSelector))
                .flatMap(rs -> rs.fieldSelectors().stream())
                .filter(fs -> fieldSelectors.contains(fs.name()))
                .map(fs -> new Field(fs.name(), typeOf(fs).clazz()))
                .toList();
        if (!fieldsFromHeader) {
            return declared;
        }
        var named = declared.stream().map(Field::name).collect(Collectors.toSet());
        return Stream.concat(
                        declared.stream(),
                        fieldSelectors.stream()
                                .filter(name -> !named.contains(name))
                                .sorted()
                                .map(name -> new Field(name, DataType.TEXT.clazz())))
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
    private record Header(@Nullable String line, long linesRead) {}

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
    private Index indexOfHeader(String headerLine) {
        Map<String, Integer> index = new HashMap<>();
        var headers = Csv.scan(headerLine, fieldSeparator, quote, comment).fields();
        for (int i = 0; i < headers.length; i++) {
            index.putIfAbsent(headers[i], i);
        }
        return new Index(selector -> index.getOrDefault(selector, -1), List.of(headers), fieldSeparator);
    }

    /**
     * Without a header there are no column names, so nothing resolves one and
     * every field has to count instead. The function is here only to keep
     * {@link Index} one shape; it is never consulted, {@code require} refusing a
     * name before it would be.
     */
    private Index positionalIndex() {
        return new Index(_ -> -1, null, fieldSeparator);
    }
}
