#!/usr/bin/env python3
"""Run `xldr check` over every spec in the tutorial.

The half of the sweep that needs the real adapters: whether each record selector
matches anything in the page's own sample, and what the values parse to. The
static half - schema, record selector names, columns against the DDL - is
TutorialTest in the spec module, and runs on every build.

    python3 tools/check-tutorial.py <path-to-xldr-repo> <path-to-bin/xldr>

A database per page, not one for the tutorial. Each page redefines the tables it
needs for its own lesson - page 2's customer has a city and a varchar id, page
8's has a balance and an integer one - so there is no accumulated schema to
build, and running them all into one database is what the first version of this
got wrong. A page that shows no DDL inherits the tables as the page before it
left them, which is what a reader following in order has.

Throwaway. It writes to a scratch directory and touches nothing in the repo.
"""
import glob
import os
import re
import subprocess
import sys
import tempfile

if len(sys.argv) != 3:
    sys.exit(__doc__)
REPO, XLDR = sys.argv[1], sys.argv[2]
TUTORIAL = os.path.join(REPO, "docs", "tutorial")

CREATE = re.compile(r"create\s+table\s+(?:if\s+not\s+exists\s+)?([A-Za-z_]\w*)", re.I)


def blocks(text, lang):
    return [b for l, b in re.findall(r"```(\w*)\n(.*?)```", text, re.S) if l == lang]


def statements(sql):
    return [s.strip() for s in re.split(r";\s*\n", sql) if s.strip()]


def h2_jar():
    found = subprocess.run(
        ["find", os.path.expanduser("~/.m2/repository/com/h2database"), "-name", "h2-*.jar"],
        capture_output=True, text=True).stdout.split()
    if not found:
        sys.exit("no h2 jar under ~/.m2; run a build first")
    return sorted(found)[-1]


H2 = h2_jar()
scratch = tempfile.mkdtemp(prefix="xldr-tutorial-")

# what each page has to hand, carried forward and overridden per page
tables = {}      # table name -> its create statement, as this page defines it
seeds = []       # the insert statements a lookup page needs
sample = None    # (filename, content) - the most recent file the tutorial showed

findings = []
checked = 0

for path in sorted(glob.glob(os.path.join(TUTORIAL, "*.md"))):
    page = os.path.basename(path)[:-3]
    text = open(path).read()

    page_seeds = []
    for sql in blocks(text, "sql"):
        for statement in statements(sql):
            m = CREATE.search(statement)
            if m:
                tables[m.group(1).lower()] = statement
            else:
                page_seeds.append(statement)
    if page_seeds:
        seeds = page_seeds

    if blocks(text, "csv"):
        sample = ("data.csv", blocks(text, "csv")[0])
    elif blocks(text, "xml") and not blocks(text, "json"):
        # page 3 is the same feed written as XML; its block is the spec, not a
        # sample, so the sample from the page before still stands
        pass

    specs = [("spec.json", s) for s in blocks(text, "json") if '"input"' in s]
    specs += [("spec.xml", s) for s in blocks(text, "xml") if "<mappingSpec" in s]
    if not specs:
        continue

    for n, (filename, spec) in enumerate(specs, 1):
        checked += 1
        label = "%s spec %d" % (page, n)
        d = os.path.join(scratch, "%s-%d" % (page, n))
        os.makedirs(d, exist_ok=True)

        # a database of this page's own, with this page's tables
        url = "jdbc:h2:" + os.path.join(d, "db")
        script = os.path.join(d, "schema.sql")
        with open(script, "w") as f:
            for statement in list(tables.values()) + seeds:
                f.write(statement.rstrip(";") + ";\n")
        subprocess.run(["java", "-cp", H2, "org.h2.tools.RunScript",
                        "-url", url, "-script", script], check=True)

        spec_file = os.path.join(d, filename)
        open(spec_file, "w").write(spec)
        args = [XLDR, "check", spec_file, "--url", url]
        if sample:
            sample_file = os.path.join(d, sample[0])
            open(sample_file, "w").write(sample[1])
            args += ["--sample", sample_file]

        print("=" * 72)
        print("%s   tables: %s   sample: %s"
              % (label, ", ".join(sorted(tables)), sample[0] if sample else "none"))
        print("=" * 72)
        result = subprocess.run(args, capture_output=True, text=True)
        print(result.stdout)
        if result.stderr.strip():
            print("stderr:", result.stderr)
        if result.returncode != 0:
            findings.append((label, result.returncode))

print()
print("%d spec(s) checked" % checked)
if findings:
    print("pages with findings:")
    for label, code in findings:
        print("  %-26s %d finding(s)" % (label, code))
else:
    print("no findings on any page")
print("scratch kept at", scratch)
