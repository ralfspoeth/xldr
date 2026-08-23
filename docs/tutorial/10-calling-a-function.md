# 10. Calling a function

[← expressions](09-expressions.md) · [index](README.md) · [next: types and notation →](11-types.md)

The fifth source, and the only one that makes the database *do* something rather than read something. An **`fn`**
calls a function in the target database and uses what it returns.

The last page ended by saying `nextval` is a counter in memory, restarted for every file, and therefore the wrong
thing for a key. This is the right thing: if your database already hands out ids - a sequence, a stored function
that opens a load and returns its number - `fn` is how a spec asks it to.

One more column, for the number the database gives us:

```sql
create table region(city varchar(50), id integer);
insert into region values ('Berlin', 10), ('Hamburg', 20);
create table load_batch(feed varchar(20), id integer);
insert into load_batch values ('customers', 7);
create table customer(record_no integer, id varchar(10), name varchar(50), city varchar(50),
                      loaded_at timestamp with time zone, source_cd varchar(10), active integer,
                      region_id integer, batch_id integer,
                      label varchar(100), loaded_from varchar(100), load_id bigint)
```

and a function in the database to fill it. This part is not xldr's and its syntax is your vendor's; in PostgreSQL:

    create sequence load_id_seq;
    create function next_load_id() returns bigint
        language sql as $$ select nextval('load_id_seq') $$;

Oracle, SQL Server and the rest each spell that differently, and none of it appears in the spec. What the spec knows
is the name:

```json
{
  "input": {
    "mimeType": "text/csv",
    "vars": [
      {"name": "loadedAt", "expr": "${now()}"},
      {"name": "batch", "lookup": {"table": "load_batch", "column": "id",
                                   "keyColumn": "feed", "constant": "customers"}},
      {"name": "loadId", "fn": {"name": "next_load_id", "type": "INTEGRAL"}}
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
        {"expr": "${xldr.filename}",    "column": "loaded_from"},
        {"var": "loadId",               "column": "load_id"}
      ]
    }
  ]
}
```

which gives:

```
sql> select record_no, id, load_id from customer order by id;

1 | 1 | 1
2 | 2 | 1
```

Both rows carry the same `load_id`, and the next file to arrive will carry 2. That is the difference from
`record_no` beside it: one counts within the file, the other identifies the file.

## The three parts

    "fn": {
        "name": "next_load_id",    which function to call
        "type": "INTEGRAL",        what it gives back
        "args": []                 and what to pass it
    }

`name` may be qualified - `pkg_load.next_id`, `reporting.next_load_id` - and is one or more identifiers separated by
dots. Nothing else: no arguments in the string, no expression, no punctuation.

`type` is one of the five from [page 11](11-types.md), and unlike a field selector's it is **required**. The reason
is mechanical: the call is prepared before it is made, and the slot the answer comes back in has to be declared as
something. Nothing in the spec could tell xldr what the function returns.

`args` may be left out when there are none, as it is above.

## Once per load, never per row

A call is a var source and cannot be a field mapping. The spec above says `{"var": "loadId", "column": "load_id"}`,
and writing the call there instead is refused when the spec is read:

    column 'load_id' calls 'next_load_id', which it cannot: a function is called once per load
    and a column is bound once per record. Declare it as a var of the input and map this column
    to that var

which is the rule of [page 7](07-vars.md) seen from the other side. A variable is evaluated once, before the first
record; a column is bound once per record. Put a call in a column and a file of fifty thousand rows makes fifty
thousand round trips for a number that was never going to change - and, if the function has a side effect like
drawing from a sequence, draws fifty thousand times.

The mirror of it holds too, and the same reading refuses it: a call may not read a `fieldSelector`, in its arguments
or anywhere below them, because there is no record in hand when it is made.

## Arguments

Each entry of `args` is a source of its own, the same shapes a variable may have - a constant, a var, an expression,
a lookup, or another call:

    {"name": "batch", "fn": {"name": "open_batch", "type": "INTEGRAL", "args": [
        {"constant": "customers"},
        {"var": "loadedAt"},
        {"fn": {"name": "current_site", "type": "TEXT"}}
    ]}}

Nesting is free because an argument is read by exactly the code that reads a field mapping's source. A call taking
the answer of another call needs no new syntax and no temporary variable - though a variable is often clearer, and
variables may refer to each other in declaration order.

## What it becomes

    {? = call next_load_id(?)}

JDBC's escape for calling a function, prepared as a `CallableStatement`. xldr registers the return slot as the type
you declared, binds each argument as a parameter, and reads the answer back. The function name is the only part of
any of this that reaches the text of the statement, which is why it is held to being a name - everything else,
arguments included, goes in bound.

So a spec with a call in it depends on the target **schema**: the function has to be there, the way a `lookup`
already needs its table to be there. It depends on no **dialect**. There is no arrangement of `fn` that puts SQL of
your own into the statement, and none is coming; `prepareCall` would not take it.

A call may return null, and the load carries on with a null - a function saying "no such thing" by returning nothing
is answering the question, and a loader has no business overruling it.

## What `check` will and will not tell you

`xldr check` verifies that a lookup's table and columns exist. It does not verify that a called function exists: the
first thing that finds a misspelled name is the load. Until it does, a call is one of the few things on a page where
the schema, the reader and `check` are all content and the database still is not.

---

[← expressions](09-expressions.md) · [index](README.md) · [next: types and notation →](11-types.md)
