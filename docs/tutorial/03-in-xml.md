# 3. The same spec in XML

[← your first spec](02-first-spec.md) · [index](README.md) · [next: a file with no header →](04-no-header.md)

A spec may be written in either format, and the two are transliterations of each other: the same names, the same
rules, the same refusals. Which one a feed uses is decided by the file's extension - `spec.json` or `spec.xml` -
and nothing else. Rename the file and rewrite its contents and the feed carries on.

Here is page 2's spec again, written as `/var/lib/xldr/customers/spec.xml` - delete `spec.json` when you save it,
since a feed has one spec:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<mappingSpec>
    <input mimeType="text/csv">
        <recordSelector name="customers">
            <fieldSelector name="id" selector="id"/>
            <fieldSelector name="name" selector="name"/>
            <fieldSelector name="city" selector="city"/>
        </recordSelector>
    </input>
    <mapping recordSelector="customers" table="customer">
        <fieldMapping fieldSelector="id" column="id"/>
        <fieldMapping fieldSelector="name" column="name"/>
        <fieldMapping fieldSelector="city" column="city"/>
    </mapping>
</mappingSpec>
```

Move the file in again and it loads exactly as before:

```
sql> select id, name, city from customer order by id;

1 | Alice | Berlin
2 | Bob   | Hamburg
```

## Reading one format as the other

| JSON | XML |
|---|---|
| a member with a scalar value | an attribute of the same name |
| an array member - `recordSelectors`, `fieldSelectors`, `vars`, `mapping`, `fieldMapping` | repeated child elements, named in the singular - `<recordSelector>`, `<fieldSelector>`, `<var>`, `<mapping>`, `<fieldMapping>` |
| an object member - `properties`, `lookup`, `discriminator` | a child element of the same name, its members as attributes |
| a constant, whose type follows the literal | a constant, always text: an attribute carries no type |

The last row is the only real difference between the two formats, and page 6 comes back to it.

There is a second, subtler one, and it is the reason the format has some of the names it has. An XML attribute is
text and nothing else, so an attribute cannot be *either* a string or a number the way a JSON member can. Wherever
the spec needs to tell those two apart it uses two names rather than one - you will meet it on the very next page,
where a field either names a column or counts one, and the two are `selector` and `nth` rather than one attribute
read two ways. Keeping the formats able to say the same things is what drives that.

## Which to choose

Whichever your team reads more comfortably, and whichever your tooling handles. Both have a published schema and
both get validation and autocompletion in IntelliJ and VS Code - page 11 shows how to switch that on, and it is
the single most useful line you can add to a spec.

Two practical differences. XML is more verbose but its nesting is visible, which tells in a spec with a dozen
field selectors. JSON has real types, so a constant number stays a number without anyone saying so.

Everything from here on is shown in JSON, and translates by the table above.

---

[← your first spec](02-first-spec.md) · [index](README.md) · [next: a file with no header →](04-no-header.md)
