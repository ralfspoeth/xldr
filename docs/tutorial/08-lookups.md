# 8. Lookups

[← variables](07-vars.md) · [index](README.md) · [next: expressions →](09-expressions.md)

The third source. A **lookup** reads the value out of another table: *this* column of *that* table, where *that*
key column matches a key you supply. It is how a code in the file becomes a foreign key in the row.

Two reference tables, and two new columns on `customer`:

```sql
create table region(city varchar(50), id integer);
insert into region values ('Berlin', 10), ('Hamburg', 20);
create table load_batch(feed varchar(20), id integer);
insert into load_batch values ('customers', 7);
create table customer(id varchar(10), name varchar(50), city varchar(50), loaded_at timestamp with time zone,
                      source_cd varchar(10), active integer, region_id integer, batch_id integer)
```

Both new columns are filled by a lookup, and the two are looked up at different moments:

```json
{
  "input": {
    "mimeType": "text/csv",
    "vars": [
      {"name": "loadedAt", "expr": "${now()}"},
      {"name": "batch", "lookup": {"table": "load_batch", "column": "id",
                                   "keyColumn": "feed", "constant": "customers"}}
    ],
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
        {"fieldSelector": "city", "column": "city"},
        {"var": "loadedAt",       "column": "loaded_at"},
        {"constant": "PD",        "column": "source_cd"},
        {"constant": 1,           "column": "active"},
        {"var": "batch",          "column": "batch_id"},
        {"column": "region_id",
         "lookup": {"table": "region", "column": "id",
                    "keyColumn": "city", "fieldSelector": "city"}}
      ]
    }
  ]
}
```

which gives:

```
sql> select id, name, region_id, batch_id from customer order by id;

1 | Alice | 10 | 7
2 | Bob   | 20 | 7
```

## The four parts

    "lookup": {
        "table":     "region",     which table to read
        "column":    "id",         which column of it is the value
        "keyColumn": "city",       which column the key is matched against
        "fieldSelector": "city"    and the key itself
    }

The key is a source in its own right, and any of the three you have already met: a `fieldSelector` for a value out
of the record, a `constant` for a fixed key, or a `var`. What it may not be is another lookup - a chain of them is a
join, and a join belongs in a view rather than in a mapping spec.

## When one column is not enough

A reference table is often keyed by more than one column - a rate by currency *and* date, a price by article *and*
price list. Then `keyColumn` becomes `conditions`, one entry per column:

    "lookup": {
        "table": "rate",
        "column": "factor",
        "conditions": [
            {"column": "ccy",  "fieldSelector": "currency"},
            {"column": "asof", "var": "valueDate"}
        ]
    }

    <lookup table="rate" column="factor">
        <conditions>
            <condition column="ccy" fieldSelector="currency"/>
            <condition column="asof" var="valueDate"/>
        </conditions>
    </lookup>

which becomes `(select factor from rate where ccy = ? and asof = ?)`. Each condition takes the same three sources a
single key takes, so they can differ - here one comes from the record and one from a variable.

The conditions are `and`ed, and a lookup either says `keyColumn` or says `conditions`, never both. Matching on one
column is still written the short way; there is no reason to wrap a single key in an array.

And `"conditions": []` - or `<conditions/>` - is a lookup that matches on nothing, which reads the whole table.
That is for a single-row view, or Oracle's `dual`. Say it explicitly: a lookup with neither `keyColumn` nor
`conditions` is an error, because forgetting the key should not quietly become this.

One thing to watch: **every condition must match, and a null in any of them yields NULL** - the same rule as the
single key, applied to all of them. A row whose currency is known and whose date is not gets a null factor, not a
factor for that currency.

Both forms appear above, and the difference is the one from [page 7](07-vars.md). `region_id` is looked up **per
row**, because the key is a field and every record has its own. `batch_id` is a variable, so its lookup runs
**once** - its key is a constant, and the answer cannot differ between rows.

## What it becomes

A lookup is emitted as a scalar subquery inside the insert:

    insert into customer (..., region_id) values (..., (select id from region where city = ?))

which has three consequences worth knowing. The resolution happens in the database, so the reference table is never
read into memory however large it is. It happens inside the same transaction as the load, so a reference row
inserted by an earlier mapping of the same file is visible. And a key matching no row yields SQL NULL rather than
failing - a city not in `region` gives a null `region_id` and the load continues.

That last one is a choice, and the opposite choice was available. Refusing would turn one unrecognised code into a
failed file, which for a reference table that is genuinely incomplete is the wrong trade; a NULL is visible in the
data and can be reported on. If you want it to fail instead, a foreign key constraint on the column does it, and
does it in the place that already knows.

The value never becomes text on the way through, and neither the table nor the column names are interpolated - they
are normalized and quoted as identifiers. A spec cannot inject SQL, whatever is written in it.

---

[← variables](07-vars.md) · [index](README.md) · [next: expressions →](09-expressions.md)
