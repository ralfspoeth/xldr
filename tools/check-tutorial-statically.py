#!/usr/bin/env python3
"""What `xldr check` can be done to the tutorial without running Java.

Three of the four comparisons the command makes need no adapter: the spec
against the published JSON schema, a mapping's recordSelector against the input's
declarations, and a mapping's columns against the CREATE TABLE on the page. The
fourth - does the record selector match anything in the sample, and what do the
values parse to - needs the real adapters and is left to the command itself.

Tables accumulate across pages, as they do for a reader following the tutorial
in order.
"""
import glob
import json
import re
import sys

import jsonschema

SCHEMA = json.load(open("docs/schema/mapping-spec-0.35.json"))
VALIDATOR = jsonschema.validators.validator_for(SCHEMA)(SCHEMA)

CREATE = re.compile(
    r"create\s+table\s+(?:if\s+not\s+exists\s+)?([A-Za-z_][\w]*)\s*\((.*?)\)\s*;?\s*$",
    re.S | re.I)


def columns_of(ddl_body):
    """The column names of a CREATE TABLE body, ignoring types and constraints."""
    depth, current, parts = 0, [], []
    for ch in ddl_body:
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
        if ch == "," and depth == 0:
            parts.append("".join(current))
            current = []
        else:
            current.append(ch)
    parts.append("".join(current))
    names = []
    for part in parts:
        tokens = part.split()
        if not tokens:
            continue
        first = tokens[0].strip('"').lower()
        if first in {"primary", "foreign", "unique", "constraint", "check"}:
            continue
        names.append(first)
    return names


def blocks(text, lang):
    return [b for l, b in re.findall(r"```(\w*)\n(.*?)```", text, re.S) if l == lang]


def field_names(source):
    """The field selectors a value source reads."""
    if "fieldSelector" in source:
        return {source["fieldSelector"]}
    if "lookup" in source:
        return field_names(source["lookup"])
    return set()


tables = {}
findings = 0

for path in sorted(glob.glob("docs/tutorial/*.md")):
    page = path.split("/")[-1]
    text = open(path).read()

    for ddl in blocks(text, "sql"):
        for statement in re.split(r";\s*\n", ddl):
            m = CREATE.search(statement.strip())
            if m:
                tables[m.group(1).lower()] = columns_of(m.group(2))

    specs = blocks(text, "json")
    if not specs:
        continue

    for n, raw in enumerate(specs, 1):
        label = "%s spec %d" % (page, n)
        try:
            spec = json.loads(raw)
        except json.JSONDecodeError as e:
            print("  %-24s NOT JSON: %s" % (label, e))
            findings += 1
            continue
        if "input" not in spec:
            continue  # a fragment, not a whole spec

        for error in VALIDATOR.iter_errors(spec):
            print("  %-24s schema: %s at %s" % (
                label, error.message[:110], "/".join(str(p) for p in error.absolute_path)))
            findings += 1

        declared = {rs["name"]: rs for rs in spec["input"].get("recordSelectors", [])}
        for mapping in spec.get("mapping", []):
            rs_name = mapping.get("recordSelector")
            rs = declared.get(rs_name)
            if rs is None:
                print("  %-24s mapping names record selector %r, input declares %s" % (
                    label, rs_name, sorted(declared)))
                findings += 1
                continue

            fields = {fs["name"] for fs in rs.get("fieldSelectors", [])}
            for fm in mapping.get("fieldMapping", []):
                for name in field_names(fm):
                    if name not in fields:
                        print("  %-24s field mapping reads %r, which %r does not declare (%s)" % (
                            label, name, rs_name, sorted(fields)))
                        findings += 1

            table = mapping.get("table", "").lower()
            if table not in tables:
                print("  %-24s no CREATE TABLE for %r on this or an earlier page" % (label, table))
                findings += 1
                continue
            for fm in mapping.get("fieldMapping", []):
                column = fm.get("column", "").lower()
                if column not in tables[table]:
                    print("  %-24s table %r has no column %r; it has %s" % (
                        label, table, column, tables[table]))
                    findings += 1

            for var in spec["input"].get("vars", []):
                if "fieldSelector" in var:
                    print("  %-24s var %r reads a field selector, which a var may not" % (
                        label, var.get("name")))
                    findings += 1

print()
print("tables seen: %s" % ", ".join(sorted(tables)))
print("%d finding(s)" % findings)
sys.exit(1 if findings else 0)
