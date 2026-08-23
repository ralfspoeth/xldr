# 7. Variables

[← constants](06-constants.md) · [index](README.md) · [next: lookups →](08-lookups.md)

A constant is worked out before the load starts. A **variable** is worked out **once when the load starts** - so
every row of one file gets the same value, and the next file gets a fresh one.

Variables are declared under `input`, beside the record selectors, because they belong to the load rather than to
any one mapping. A field mapping then refers to one by name:

    "vars": [ {"name": "src", "constant": "PD"} ]        under input

    {"var": "src", "column": "source_cd"}                in the mapping

That one is a named constant and nothing more. It earns its keep when the same value fills several columns or
several mappings, where a variable gives it one name and one place to change - and not otherwise, since
`{"constant": "PD", "column": "source_cd"}` says the same thing in one line instead of two.

The case a variable is really for is a value that cannot be written in the spec because it is not known until the
load happens. Add a column for one:

```sql
create table customer(id varchar(10), name varchar(50), city varchar(50),
                      source_cd varchar(10), active integer, loaded_at timestamp with time zone)
```

and declare the variable:

```json
{
  "input": {
    "mimeType": "text/csv",
    "vars": [
      {"name": "loadedAt", "expr": "${now()}"}
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
        {"constant": "PD",        "column": "source_cd"},
        {"constant": 1,           "column": "active"},
        {"var": "loadedAt",       "column": "loaded_at"}
      ]
    }
  ]
}
```

`${now()}` is an expression, and expressions have [a page of their own](09-expressions.md). This is the only one
needed here: it yields the current instant, which is why the column is `with time zone` - an instant is a moment
rather than a reading off a wall clock, and a column without a zone would silently pick one.

## Once, not once per row

The claim worth checking is not what the timestamp *is* but that there is only one of it:

```
sql> select count(*), count(distinct loaded_at) from customer;

2 | 1
```

Two rows, one timestamp between them. Evaluated per row you would get two, or two hundred thousand for a real
file - and the rows of one batch would no longer be identifiable as one batch, which is usually the entire reason
for having the column.

The saving matters more when the value comes from the database, as the [next page](08-lookups.md) shows. A variable
reading a batch number out of a table runs that query once; the same thing written per row runs it for every
record. And correctness follows cost here: nothing guarantees a query asked twice answers the same, so a per-row
read could split one file across two batches.

## What a variable may be

The same sources a column has, minus the one that would make no sense, plus one only a variable may have:

| source | meaning |
|---|---|
| `constant` | a fixed value from the spec - [page 6](06-constants.md) |
| `lookup` | read from a reference table - [page 8](08-lookups.md) |
| `expr` | a `${...}` template - [page 9](09-expressions.md) |
| `var` | another variable |
| `fn` | a function called in the target database, e.g. a sequence or a batch opener |

A `fieldSelector` is refused, anywhere inside a variable: not as the source, not as a `lookup`'s key, not as an
argument to an `fn`. A variable is evaluated once, before any record has been read, so there is no record for it to
take a field from - and the spec is rejected when it is read, saying that a var must be row-independent, rather than
failing on the first row.

`fn` goes the other way: a column may not have one. A variable is evaluated once per load and a column is bound once
per record, so the same call in a field mapping would be a round trip a row. Write the call as a variable and map
the column to it.

    {"name": "loadId", "fn": {"name": "pkg_load.next_id", "type": "INTEGRAL", "args": []}}

`type` says what the function returns, and unlike a field selector's it is required: the call is prepared before it
is made, so nothing can infer it. Each entry of `args` is a source of its own, so an argument may be a constant, a
variable, an expression, a lookup, or another call.

Variables may refer to one another, and the order they are declared in is the order they are evaluated in.

---

[← constants](06-constants.md) · [index](README.md) · [next: lookups →](08-lookups.md)
