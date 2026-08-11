package io.github.ralfspoeth.xldr.csv;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;

/**
 * Splits a separated-value record into its fields, honoring quoted fields.
 * <p>
 * A quote opens a quoted field only where a field begins - immediately after a
 * separator, or at the start of the record. Anywhere else it is an ordinary
 * character, so a value like {@code 5" pipe} or {@code he said "no"} reads as
 * it is written. Inside a quoted field the separator and the line break are
 * ordinary too, and a doubled quote stands for one literal quote.
 * <p>
 * That rule is deliberately the lenient one. A strict reading would call a bare
 * quote in an unquoted field an error, which would turn files that load today
 * into failed loads for no gain: a quote that is not at the start of a field
 * cannot be structural anyway.
 * <p>
 * A comment character, where the feed defines one, ends the record wherever it
 * appears outside a quoted field. Inside one, it is data like any other
 * character, which is the point of asking the scanner rather than the line.
 */
final class Csv {

    private Csv() {
    }

    /**
     * The fields of a record, and what became of it.
     *
     * @param fields the fields read so far
     * @param open   whether the text ended inside a quoted field, meaning the
     *               record continues on the next line
     * @param blank  whether nothing but whitespace was left once a comment was
     *               taken off - an empty line, or a line that was only a comment
     */
    record Scan(String[] fields, boolean open, boolean blank) {
    }

    /**
     * @param text      one record, or as much of it as has been read
     * @param separator what separates two fields
     * @param quote     what opens and closes a quoted field, or {@code null} to
     *                  read quotes as ordinary characters
     * @param comment   what begins a comment outside a quoted field, or
     *                  {@code null} where nothing does
     * @return the fields, whether a quoted field is still open, and whether the
     * record held anything at all
     */
    static Scan scan(String text, String separator, @Nullable Character quote, @Nullable Character comment) {
        var fields = new ArrayList<String>();
        var field = new StringBuilder();
        boolean atFieldStart = true;
        int i = 0;
        int n = text.length();
        // where the content ends: the start of a comment, or the end of the text
        int contentEnd = n;
        while (i < n) {
            if (atFieldStart && quote != null && text.charAt(i) == quote) {
                i = readQuoted(text, i + 1, quote, field);
                if (i > n) {
                    // the closing quote is on a later line: the fields read so
                    // far are of no use until the record is complete
                    return new Scan(new String[0], true, false);
                }
                atFieldStart = false;
            } else if (comment != null && text.charAt(i) == comment) {
                // a comment runs to the end of the record; inside a quoted field
                // the character never gets here, being read as data
                contentEnd = i;
                break;
            } else if (text.startsWith(separator, i)) {
                fields.add(field.toString());
                field.setLength(0);
                i += separator.length();
                atFieldStart = true;
            } else {
                field.append(text.charAt(i++));
                atFieldStart = false;
            }
        }
        fields.add(field.toString());
        return new Scan(fields.toArray(String[]::new), false, text.substring(0, contentEnd).isBlank());
    }

    /**
     * Reads the body of a quoted field into {@code field}.
     *
     * @return the position after the closing quote, or one past the end of the
     * text if there is none
     */
    private static int readQuoted(String text, int from, char quote, StringBuilder field) {
        int i = from;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (c != quote) {
                field.append(c);
                i++;
            } else if (i + 1 < n && text.charAt(i + 1) == quote) {
                // "" inside a quoted field is one literal quote
                field.append(quote);
                i += 2;
            } else {
                return i + 1;
            }
        }
        return n + 1;
    }
}
