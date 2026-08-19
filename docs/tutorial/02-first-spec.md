# 2. Your first spec

[← setting up](01-setup.md) · [index](README.md) · [next: the same spec in XML →](03-in-xml.md)

Here is the file that arrives. Save it somewhere outside the feed for now - `/tmp/customers.csv`:

```csv
id,name,city
1,Alice,Berlin
2,Bob,Hamburg
```

And here is the table you created on the last page:

```sql
create table customer(id varchar(10), name varchar(50), city varchar(50))
```

## The spec

Write this to `/var/lib/xldr/customers/spec.json`:

```json
{
  "input": {
    "mimeType": "text/csv",
    "recordSelectors": [
      {
        "name": "customers",
        "fieldSelectors": [
          {"name": "id",   "selector": "id"},
          {"name": "name", "selector": "name"},
          {"name": "city", "selector": "city"}
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

A spec always has exactly these two halves.

**`input` describes the file.** `mimeType` chooses the adapter - `text/csv` here - and everything below it is
written in that adapter's terms. A **record selector** says which records in the file are of one kind and gives
them a name; a CSV with a header holds one kind of record, so there is nothing to select and the name is all it
needs. Its **field selectors** say which values to read out of each record and what to call them.

**`mapping` describes the database.** It names a record selector, names a table, and says which field goes in
which column.

The split is the point. One file may hold several kinds of record going to several tables, and the same file may
be read differently by two feeds. Describing the file once, and then separately saying what to do with it, keeps
those two decisions apart.

Notice what is absent: no column order, no `create table`, no insert statement, no types. A value with no declared
type is loaded as text, which is what these three are.

### Three words that are not the same word

In this spec everything is called the same thing three times over, which is comfortable and hides the structure.
It is worth seeing the three jobs before a real file stops being so obliging:

| where | what it means |
|---|---|
| `selector`, in a field selector | **where the value sits in the file.** The adapter's own syntax - a column name for CSV, an XPath for XML, a character range for a fixed-length record, a pointer for JSON, a cell reference for a spreadsheet. |
| `name`, in a field selector | **what the spec calls it.** A label of your choosing, meaningful only inside this spec, and the only thing a mapping can refer to. |
| `column`, in a field *mapping* | **where the value goes.** The database column. |

They line up left to right - out of the file, through the spec, into the table. When the file calls it `cust_no`
and the table calls it `id`, the field selector is where the two are reconciled:

    {"name": "number", "selector": "cust_no"}       in the input
    {"fieldSelector": "number", "column": "id"}     in the mapping

## Delivery

The spec says what to do with a file. `delivery.properties` says *which* files. Write this to
`/var/lib/xldr/customers/delivery.properties`:

    accepts = glob:*.csv

That is the whole of the required content: a glob, matched against the name of a file appearing in `in/`. A name
that matches is loaded through the spec; a name that does not is left where it is.

This is deliberately not in the spec. Which files arrive, and under what names, is a property of the deployment -
a producer's naming differs between test and production while the mapping does not - so it lives in a file beside
the spec that no schema describes and that you can change without touching the mapping.

The other setting `delivery.properties` takes is `sentinel`, for producers that write a data file and then a small
marker file to say it is complete; see the [README](../../README.md#delivering-files).

## Load it

    mv /tmp/customers.csv /var/lib/xldr/customers/in/

**Move, do not copy.** A copy is visible in `in/` while it is still half-written, and the server will read the
half. A move within a filesystem is atomic, so the file appears complete or not at all.

Within a second or so the file is gone from `in/` and sitting in `archive/`, and the table holds:

```
sql> select id, name, city from customer order by id;

1 | Alice | Berlin
2 | Bob   | Hamburg
```

If instead the file landed in `hospital/`, there is a note beside it saying what went wrong. Page 11 is about
reading those.

---

[← setting up](01-setup.md) · [index](README.md) · [next: the same spec in XML →](03-in-xml.md)
