# 9. A file with no header

[← types and notation](08-types.md) · [index](README.md) · [next: several kinds of record →](10-record-types.md)

Plenty of feeds arrive with no header row. There are then no column names for a `selector` to name, so a field
**counts** instead. The same file as the last page, with its header row taken away:

```csv
1,Alice,01.03.2026,"1.234,56"
2,Bob,15.03.2026,"98,00"
```

The table is unchanged; only the input side of the spec differs:

```json
{
  "input": {
    "mimeType": "text/csv",
    "properties": {
      "header": "absent",
      "dateFormat": "dd.MM.yyyy",
      "numberFormat": "#,##0.00",
      "locale": "de-DE"
    },
    "recordSelectors": [
      {
        "name": "customers",
        "fieldSelectors": [
          {"name": "id",      "nth": 1, "type": "INTEGRAL"},
          {"name": "name",    "nth": 2},
          {"name": "since",   "nth": 3, "type": "DATE"},
          {"name": "balance", "nth": 4, "type": "DECIMAL"}
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

which loads exactly what the last page loaded:

```
sql> select id, name, since, balance from customer order by id;

1 | Alice | 2026-03-01 | 1234.56
2 | Bob   | 2026-03-15 | 98.00
```

Two changes. `header: "absent"` tells the CSV adapter there is no header row to skip - the default is `present`,
which is why the earlier pages did not mention it. And each field says `nth` rather than `selector`.

## Exactly one of the two

A field selector says **`selector` or `nth`, never both and never neither**. A spec that says both is refused when
it is read, because they are two answers to one question.

`nth` counts from **one**, and it means *the n-th component of the record the record selector identified*. Each
adapter only has to say what its records are made of:

| input | the n-th component |
|---|---|
| CSV, TSV | the n-th field of the line |
| JSON | the n-th element, where the record is an array |
| XML | the n-th child element |
| Excel | the n-th column of the record's **range**, counted from the range's own first column |
| fixed length | nothing - a fixed-length record has offsets and no components, so `nth` there is refused |

The last two rows are worth a second look if you use those formats. In Excel, `nth: 1` is the first column of the
record rather than column A of the sheet, so for a range at `data!C2:D9` it is column C; the two agree only for a
range starting at A. In a fixed-length file `nth` means nothing at all and is rejected when the adapter is built,
rather than at some later hour.

Where the *data* has no n-th component - a line with fewer fields than that, a JSON record that turns out to be an
object - the value is null, because only the document can say so and the next record may differ. Where the
*format* has none, the spec is refused, because the spec alone already proves it wrong.

## Why two names

Because the XML format cannot express one attribute of two types. An attribute is text, `selector="3"` is the only
thing writable, and a reader deciding by *looks like a number* would guess wrong on a file whose header genuinely
names a column `3` - which is precisely the case that made this necessary. Two names cost nothing and let both
schemas type `nth` as an integer, so your editor rejects `nth: "first"` before any file is read.

And it is not called `column`, though it counts columns in a CSV. A field *mapping* has used that word for the
database column since long before this existed, and the two would have sat three lines apart meaning opposite ends
of the same value. `nth` is what CSS calls the same idea in `:nth-child`.

---

[← types and notation](08-types.md) · [index](README.md) · [next: several kinds of record →](10-record-types.md)
