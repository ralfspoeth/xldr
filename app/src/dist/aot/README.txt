The AOT cache, and the one file that belongs in here: xldr.aot.

An AOT cache holds classes already loaded and linked, so a JVM that is given one
skips work it would otherwise repeat on every start. It is a startup
optimisation and nothing else - it makes no load faster once one is running.

This directory is empty as shipped, and deliberately. A cache is tied to the
exact JVM that wrote it and to the exact module path it saw, and this
distribution is built around a deployment choosing its own module path: which
adapters are in modules/, whether xl/ is there, which driver is in drivers/. A
cache we built could not match yours, so writing one is a step you take after
your jars have settled, like installing a driver.


WRITING ONE

Put `training` in front of any ordinary command:

    bin/xldr training check spec.json --sample orders.csv --url jdbc:h2:./db

That runs exactly the command you wrote - it checks the spec, reads the sample,
talks to the database - and writes xldr.aot when it finishes. The classes
recorded are therefore the classes that command actually loads. Train on the
command you really run, with a spec and a sample of your own, and prefer a spec
that uses the formats you feed: a cache trained on a CSV check knows nothing
about the XML reader.


WHO IT HELPS

`xldr check`, which is the command run from a prompt, over and over, while
somebody works on a spec. It pays the whole cost of resolving the module graph,
binding the adapters as services and reflecting over the command line every
time, and that is what a cache removes.

Not the server. It starts once and watches files for months, so the saving is
spread over so long a run that it disappears - and training it would mean
starting it and stopping it again, which writes a cache only if the JVM exits
normally. If a restart ever does become the thing you are waiting for, a
container being the usual reason, the same prefix applies and the caveat is the
exit.


WHEN IT HAS TO BE WRITTEN AGAIN

Two things invalidate a cache, and both are ordinary:

  - upgrading the JVM, since a cache belongs to the build that wrote it;
  - changing the module path, which means adding or removing anything in
    modules/, xl/ or drivers/.

Neither breaks anything. A cache that does not match is a warning on stderr and
a run that loads its classes the ordinary way, which is exactly what happens
without a cache at all - so the cost of a stale one is the speed you had before,
not a failure. Delete the file to stop the warning, or write a new one.


A READ-ONLY INSTALLATION

/opt/xldr usually is. Set XLDR_AOT_CACHE to a file the account running xldr can
write, and both the training run and every later run will use it:

    XLDR_AOT_CACHE=/var/lib/xldr/xldr.aot bin/xldr training check spec.json
