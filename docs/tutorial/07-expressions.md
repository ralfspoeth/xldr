# 7. Expressions

[← lookups](06-lookups.md) · [index](README.md) · [next: types and notation →](08-types.md)

The fourth and last source. An **expression** is a `${...}` template evaluated in the JVM, and it is the one that
can combine things: literal text with a field, two fields together, a value the server supplies about the load
itself.

You have already used one. `${now()}` on [page 5](05-vars.md) was an expression with a single built-in call in it.

Three more columns - a number within the file, a label built from two fields, and where the row came from:

```sql
create table region(city varchar(50), id integer);
insert into region values ('Berlin', 10), ('Hamburg', 20);
create table load_batch(feed varchar(20), id integer);
insert into load_batch values ('customers', 7);
create table customer(record_no integer, id varchar(10), name varchar(50), city varchar(50),
                      loaded_at timestamp with time zone, source_cd varchar(10), active integer,
                      region_id integer, batch_id integer,
                      label varchar(100), loaded_from varchar(100))
```

and three expressions to fill them:

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
        {"expr": "${nextval('row')}", "column": "record_no"},
        {"fieldSelector": "id",   "column": "id"},
        {"fieldSelector": "name", "column": "name"},
        {"fieldSelector": "city", "column": "city"},
        {"var": "loadedAt",       "column": "loaded_at"},
        {"constant": "PD",        "column": "source_cd"},
        {"constant": 1,           "column": "active"},
        {"var": "batch",          "column": "batch_id"},
        {"column": "region_id",
         "lookup": {"table": "region", "column": "id",
                    "keyColumn": "city", "fieldSelector": "city"}},
        {"expr": "${name} (${city})",   "column": "label"},
        {"expr": "${xldr.filename}",    "column": "loaded_from"}
      ]
    }
  ]
}
```

which gives:

```
sql> select record_no, id, label, loaded_from from customer order by id;

1 | 1 | Alice (Berlin) | customers.csv
2 | 2 | Bob (Hamburg)  | customers.csv
```

## What goes in a hole

`${name}` and `${city}` are the field selectors of the record being loaded - which is why an expression is the one
source a variable cannot use freely, there being no record when a variable is evaluated.

`${xldr.filename}` is **ambient**: a value the server supplies about the load rather than about the record. The
`xldr.` prefix is reserved for these and `env.` for environment variables, so an ambient name can never be shadowed
by a field or a var you happen to name the same thing. `${xldr.filename}` is far and away the most used of them,
because a row that cannot say which file it came from is a row nobody can reconcile.

A var is referred to by its bare name, exactly as a field is.

## The built-ins

`now()` yields the current instant. `nextval(name)` counts, once per row, and takes an optional start and
increment - `${nextval('row', 100, 10)}` gives 100, 110, 120.

**`nextval` is a counter held in memory for the duration of one load, not a database sequence.** It restarts at its
start value for the next file. So it is the right thing for a position within the file - the `record_no` above,
which is what makes a row traceable to a line - and the wrong thing for a primary key, because the second file
would generate the numbers the first one already used. For a key, use the database's own sequence or identity
column and leave it out of the spec, or pair the counter with a batch variable so that the two together are unique.

There are two more, for moving between text and the date types: `format(value, 'pattern')` renders a date or
timestamp as text in a pattern you control, which is how to put a timestamp into a `varchar` column and know what
it will say rather than letting the driver decide; and `parse(text, 'pattern')` goes the other way.

## One hole, or several

The rule is short and worth stating exactly:

* a template that is **a single hole** yields that value with its own type - `${nextval('row')}` is a number,
  `${now()}` is an instant, and both reach the column as such;
* **anything else** is the pieces concatenated as a string - `${name} (${city})` is text, and so is `x${n}` even
  though `${n}` alone would be a number.

So `${amount}` into a numeric column is fine, and `${amount} EUR` is text and will not be.

## What an expression is not

It is not SQL and cannot become SQL. The template is evaluated in the JVM and the result is bound as a parameter,
the same as every other value; there is no arrangement of `${...}` that reaches the database as text to be parsed.
Nor is it a general expression language - there is no arithmetic, no conditionals, no string functions. When you
find yourself wanting those, the answer is a generated column or a view on the database side, which is the place
that has a language for it.

---

[← lookups](06-lookups.md) · [index](README.md) · [next: types and notation →](08-types.md)
