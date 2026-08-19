# 4. Constants

[← the same spec in XML](03-in-xml.md) · [index](README.md) · [next: variables →](05-vars.md)

Not every column is filled from the file. The next four pages are about the ones that are not - four sources,
differing mainly in *when* the value is worked out.

A **constant** is the simplest of them: a fixed value, written in the spec, known before anything happens. It
appears in nearly every real spec - a source code saying which feed a row came from, the status every arriving
record starts in, a flag distinguishing two feeds that write to the same table.

Add two columns:

```sql
create table customer(id varchar(10), name varchar(50), city varchar(50),
                      source_cd varchar(10), active integer)
```

and fill them from the spec rather than from the file:

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
        {"fieldSelector": "city", "column": "city"},
        {"constant": "PD",        "column": "source_cd"},
        {"constant": 1,           "column": "active"}
      ]
    }
  ]
}
```

which gives:

```
sql> select id, name, source_cd, active from customer order by id;

1 | Alice | PD | 1
2 | Bob   | PD | 1
```

A field mapping carries **exactly one** source. Until now that was always a `fieldSelector`; these two say
`constant` instead, and no mapping ever says both. A spec that does is refused when it is read.

## In JSON a constant has a type

`"PD"` is a string and `1` is a number, and each reaches the database as that. Quote the second and you have
written the string `"1"`, which a numeric column may or may not accept depending on the driver's willingness to
coerce - a difference worth being deliberate about rather than discovering.

Three are worth knowing individually:

    {"constant": null,  "column": "closed_on"}     SQL NULL
    {"constant": true,  "column": "active"}        a boolean
    {"constant": 0.075, "column": "rate"}          exact, not a double

`null` is a value here rather than an omission: it writes SQL NULL into the column, which is not the same as
leaving the field mapping out. Leaving it out means the column is not in the insert at all and the database applies
its own default.

Numbers are read exactly, as decimals rather than as doubles, so `0.075` is `0.075` and not the nearest binary
approximation of it.

## In XML a constant is always text

Because an attribute is text:

    <fieldMapping constant="PD" column="source_cd"/>
    <fieldMapping constant="1" column="active"/>

The second writes the string `1` and leaves the driver to convert it. This is the one place where the two formats
genuinely differ in what they can express, and it is worth knowing before transliterating a spec that leans on
typed constants.

---

[← the same spec in XML](03-in-xml.md) · [index](README.md) · [next: variables →](05-vars.md)
