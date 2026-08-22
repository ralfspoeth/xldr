# 10. Types and notation

[← expressions](09-expressions.md) · [index](README.md) · [next: when it goes wrong →](11-when-it-goes-wrong.md)

The mapping side of a spec is now familiar - four sources, and a column for each. This page changes the file
instead, as pages 4 and 5 did, and keeps the mapping short so that the input side is what you are reading.

Everything so far has been loaded as text, which is what a spec means when it says nothing. Two separate questions
arise the moment it should not be: **what is this value**, and **how did the producer write it down**.

A file from a German source system:

```csv
id,name,since,balance
1,Alice,01.03.2026,"1.234,56"
2,Bob,15.03.2026,"98,00"
```

into a table whose columns are not all text:

```sql
create table customer(id integer, name varchar(50), since date, balance decimal(12,2))
```

The spec answers both questions - a `type` per field, and the notation once for the whole file:

```json
{
  "input": {
    "mimeType": "text/csv",
    "properties": {
      "dateFormat": "dd.MM.yyyy",
      "numberFormat": "#,##0.00",
      "locale": "de-DE"
    },
    "recordSelectors": [
      {
        "name": "customers",
        "fieldSelectors": [
          {"name": "id",      "selector": "id",      "type": "INTEGRAL"},
          {"name": "name",    "selector": "name"},
          {"name": "since",   "selector": "since",   "type": "DATE"},
          {"name": "balance", "selector": "balance", "type": "DECIMAL"}
        ]
      }
    ]
  },
  "mapping": [
    {
      "recordSelector": "customers",
      "table": "customer",
      "fieldMapping": [
        {"fieldSelector": "id",      "column": "id"},
        {"fieldSelector": "name",    "column": "name"},
        {"fieldSelector": "since",   "column": "since"},
        {"fieldSelector": "balance", "column": "balance"}
      ]
    }
  ]
}
```

which gives:

```
sql> select id, name, since, balance from customer order by id;

1 | Alice | 2026-03-01 | 1234.56
2 | Bob   | 2026-03-15 | 98.00
```

## What a value is

`type` on a field selector, and one of five:

| type | what it is |
|---|---|
| `TEXT` | text, and the default when no type is given |
| `INTEGRAL` | a whole number, up to 64 bits |
| `DECIMAL` | an exact decimal |
| `FP` | a floating-point number, allowed to be approximate |
| `DATE` | a date, or a date and time |

The names are deliberately none of Java's or SQL's, so that nobody reads `FP` as a `float` and infers a width from
it, or reads `INTEGRAL` as a 32-bit int. `DECIMAL` is exact - money belongs in it, never in `FP`.

`INTEGRAL` is a 64-bit whole number, so ±9223372036854775807. A value with a fraction or beyond that range is
refused rather than rounded or wrapped, and the message says so - an identifier too long for it belongs in a text
column, which is usually the right home for one anyway, since nothing arithmetic is ever done to it.

The question the type answers is what the *value* is, not what the column is. The driver takes it from there, and
a `DECIMAL` into a `numeric(12,2)` needs nothing said twice.

## How the producer wrote it

`dateFormat`, `numberFormat` and `locale` sit in `properties` and are understood by every text adapter, because
they describe the producer's notation rather than the file's structure.

The locale is the part that catches people. Without `de-DE`, `1.234,56` is a number written by someone whose
conventions the reader has not been told about - and the failure is not always loud, because `1.234` is a
perfectly plausible number in another convention. State the locale whenever the file uses grouping separators or a
decimal comma.

`dateFormat` is a
[`DateTimeFormatter`](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/time/format/DateTimeFormatter.html)
pattern and `numberFormat` a
[`DecimalFormat`](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/text/DecimalFormat.html) one.
Without either, ISO forms are expected: `2026-03-01` and `1234.56`.

## Stripping, blanks, and quotes

Every value is stripped before conversion, so the trailing padding of a fixed-length file and the indentation
inside an XML element need no mention anywhere. A value that is blank after stripping counts as absent, and an
absent value is SQL NULL - which is why an empty cell in a numeric column is a null rather than a failure.

Note the quoting in the file. `"1.234,56"` contains the field separator, so the producer quoted it and the adapter
reads it as one field; a quoted field may hold the separator, a doubled quote, or a line break. That is RFC 4180,
and the CSV adapter's defaults are the RFC's: comma, double quote, UTF-8.

---

[← expressions](09-expressions.md) · [index](README.md) · [next: when it goes wrong →](11-when-it-goes-wrong.md)
