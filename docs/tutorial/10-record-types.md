# 10. Several kinds of record

[← a file with no header](09-no-header.md) · [index](README.md) · [next: when it goes wrong →](11-when-it-goes-wrong.md)

A headerless feed often interleaves several kinds of record in one file, the first field naming the kind and the
ones after it differing in number and meaning per kind. Here an order is followed by its lines:

```csv
O,1001,2026-03-01,1
L,1001,widget,5
L,1001,sprocket,2
O,1002,2026-03-02,2
L,1002,flange,1
```

Two kinds of record, so two tables:

```sql
create table orders(id varchar(10), ordered_on date, customer_id varchar(10));
create table order_line(order_id varchar(10), sku varchar(30), qty integer)
```

Two record selectors partition the file, each saying which lines are its own with a **discriminator**, and each
mapped to its own table:

```json
{
  "input": {
    "mimeType": "text/csv",
    "properties": { "header": "absent" },
    "recordSelectors": [
      {
        "name": "orders",
        "discriminator": { "nth": 1, "equals": "O" },
        "fieldSelectors": [
          {"name": "id",       "nth": 2},
          {"name": "ordered",  "nth": 3, "type": "DATE"},
          {"name": "customer", "nth": 4}
        ]
      },
      {
        "name": "lines",
        "discriminator": { "nth": 1, "equals": "L" },
        "fieldSelectors": [
          {"name": "order", "nth": 2},
          {"name": "sku",   "nth": 3},
          {"name": "qty",   "nth": 4, "type": "INTEGRAL"}
        ]
      }
    ]
  },
  "mapping": [
    {
      "recordSelector": "orders",
      "table": "orders",
      "fieldMapping": [
        {"fieldSelector": "id",       "column": "id"},
        {"fieldSelector": "ordered",  "column": "ordered_on"},
        {"fieldSelector": "customer", "column": "customer_id"}
      ]
    },
    {
      "recordSelector": "lines",
      "table": "order_line",
      "fieldMapping": [
        {"fieldSelector": "order", "column": "order_id"},
        {"fieldSelector": "sku",   "column": "sku"},
        {"fieldSelector": "qty",   "column": "qty"}
      ]
    }
  ]
}
```

which gives, joined back together:

```
sql> select o.id, o.ordered_on, l.sku, l.qty from orders o join order_line l on l.order_id = o.id order by o.id, l.sku;

1001 | 2026-03-01 | sprocket | 2
1001 | 2026-03-01 | widget   | 5
1002 | 2026-03-02 | flange   | 1
```

Both halves of the spec grew together, and this is the case the two-part shape exists for: one arriving file, two
tables, one transaction. If either mapping fails the whole file is rolled back, so there is no state in which the
orders are loaded and their lines are not.

## What a discriminator says

Two things, each of them exactly once.

**Where to look** - `nth` to count the components, or `selector` to name one. It is the same pair a field selector
offers, and for the same reason. So where the file *does* have a header, the discriminating column can be named:

    "discriminator": { "selector": "kind", "equals": "O" }

which is worth knowing, because a headed file with a type column is common and used to be unreadable here.

**What to look for** - `equals` for a literal, or `matches` for a regular expression:

    "discriminator": { "nth": 1, "matches": "O.*" }

A pattern matches the value **whole**, so there is no anchoring to remember, and it is compiled when the adapter is
built. A pattern that will not compile is therefore a spec that does not deploy, rather than a load that dies
partway through a file at four in the morning.

## Details that catch people

**Counting stays absolute within the line.** Component 1 is the discriminator itself, so a kind's payload usually
starts at 2 - as it does above.

**No discriminator means every line.** That is what pages 2 to 9 were doing, and it is what a feed with a header
almost always wants.

**A record selector never carries both a `selector` and a `discriminator`.** A tree or a sheet has to be *pointed
at* - an XPath, a pointer, a range - while in a flat file every line is a candidate and the question is which to
keep. No input is read both ways, and a spec saying both is refused.

**Discriminators are for separated files.** A fixed-length file is flat too and has the same need - a record type
in columns 1 to 2 is the classic layout - but the fixed-length adapter has no discriminator yet. It takes one
record selector and reads every line as one kind.

---

[← a file with no header](09-no-header.md) · [index](README.md) · [next: when it goes wrong →](11-when-it-goes-wrong.md)
