# 4. A file with no header

[← the same spec in XML](03-in-xml.md) · [index](README.md) · [next: several kinds of record →](05-record-types.md)

Plenty of feeds arrive with no header row - most of the ones that come off a mainframe, and most of the ones a
partner has been sending unchanged since before anyone thought to ask. There are then no column names for a
`selector` to name, so a field **counts** instead.

Here is page 2's file with its header row taken away:

```csv
1,Alice,Berlin
2,Bob,Hamburg
```

The table is unchanged:

```sql
create table customer(id varchar(10), name varchar(50), city varchar(50))
```

And only the input side of the spec differs from page 2's:

```json
{
  "input": {
    "mimeType": "text/csv",
    "properties": { "header": "absent" },
    "recordSelectors": [
      {
        "name": "customers",
        "fieldSelectors": [
          {"name": "id",   "nth": 1},
          {"name": "name", "nth": 2},
          {"name": "city", "nth": 3}
        ]
      }
    ]
  },
  "mapping": [
    {
      "recordSelector": "customers",
      "table": "customer",
      "fieldMapping": [
        {"fieldSelector": "id",   "column": "id"},
        {"fieldSelector": "name", "column": "name"},
        {"fieldSelector": "city", "column": "city"}
      ]
    }
  ]
}
```

which loads exactly what page 2 loaded:

```
sql> select id, name, city from customer order by id;

1 | Alice | Berlin
2 | Bob   | Hamburg
```

Two changes, and the mapping is not one of them. `header: "absent"` tells the CSV adapter there is no header row to
skip - the default is `present`, which is why page 2 did not mention it. And each field says `nth` rather than
`selector`.

That the *mapping* half is untouched is the point of the two-part shape. What the file looks like and what the rows
mean are different questions, and only the first one changed here.

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

[← the same spec in XML](03-in-xml.md) · [index](README.md) · [next: several kinds of record →](05-record-types.md)
