@echo off
rem xldr server launcher.
rem
rem The server reads xldr.properties from the working directory, or from the one
rem named by --dir:
rem   cd C:\xldr\feeds && C:\xldr\bin\xldr.cmd
rem   C:\xldr\bin\xldr.cmd --dir C:\xldr\feeds
rem
rem lib\ holds the application and the toolkit - what has to be there. The other
rem three hold what a deployment chooses: modules\ the input adapters, xl\ the
rem Excel adapter with the Apache POI libraries it needs, drivers\ the JDBC
rem drivers. All four go on the module path, where JPMS service binding finds
rem the adapters and the driver. Add and remove by moving jars: your own driver
rem into drivers\, a format out of modules\, xl\ deleted whole if you read no
rem spreadsheets.
rem
rem java is taken from JAVA_HOME when that is set, and from PATH otherwise.
rem JAVA_OPTS may carry extra VM options.
rem
rem An AOT cache, if aot\xldr.aot is there, is used for every run. Writing one is
rem   bin\xldr.cmd training check spec.json --sample orders.csv --url jdbc:...
rem which runs exactly that command and records the classes it loads on the way.
rem See aot\README.txt for when it has to be written again.
setlocal
set "HERE=%~dp0.."

if not exist "%HERE%\lib" (
    echo xldr: no lib\ in %HERE% - is the distribution complete? 1>&2
    echo xldr: unpack it again; if a published archive comes out this way, that is ours: https://github.com/ralfspoeth/xldr/issues 1>&2
    exit /b 1
)

if defined JAVA_HOME (
    set "JAVA=%JAVA_HOME%\bin\java.exe"
) else (
    set "JAVA=java.exe"
)
if defined JAVA_HOME if not exist "%JAVA_HOME%\bin\java.exe" (
    echo xldr: JAVA_HOME is set to %JAVA_HOME%, but %JAVA_HOME%\bin\java.exe does not exist 1>&2
    exit /b 1
)

rem modules\, xl\ and drivers\ may each be absent, or empty, and all of that is
rem fine: choosing none of something is a choice. Only lib\ has to be there.
set "MODULEPATH=%HERE%\lib"
if exist "%HERE%\modules" set "MODULEPATH=%MODULEPATH%;%HERE%\modules"
if exist "%HERE%\xl" set "MODULEPATH=%MODULEPATH%;%HERE%\xl"
if exist "%HERE%\drivers" set "MODULEPATH=%MODULEPATH%;%HERE%\drivers"

rem The AOT cache. -XX:AOTCacheOutput has to be on the command line that starts
rem the JVM, so `training` cannot be a subcommand - nothing inside the
rem application can set a flag for its own process. It is a prefix instead,
rem stripped here, and what follows runs as it normally would, so the classes
rem recorded are the ones that command actually loads.
rem
rem XLDR_AOT_CACHE names the file where the installation is read-only.
if defined XLDR_AOT_CACHE (
    set "AOTFILE=%XLDR_AOT_CACHE%"
) else (
    set "AOTFILE=%HERE%\aot\xldr.aot"
)

rem Labels rather than a parenthesised if/else, and `for /f` rather than `shift`.
rem Inside a block cmd expands %~1 when it parses the block, so a shift and a
rem test of the shifted argument in one block silently reads the pre-shift value;
rem and reassembling %* after a shift loses the original quoting. Splitting the
rem first token off %* avoids both.
set "AOT="
set "ARGS=%*"
if /i "%~1"=="training" goto training
rem A cache that no longer matches this JVM or this module path is a warning and
rem not a failure - the default AOTMode=auto loads classes the ordinary way
rem instead - so leaving this switched on is safe.
if exist "%AOTFILE%" set "AOT=-XX:AOTCache=%AOTFILE%"
goto run

:training
for /f "tokens=1,*" %%a in ("%*") do set "ARGS=%%b"
if not defined ARGS (
    echo xldr: training needs a command to train on, e.g. 1^>^&2
    echo xldr: bin\xldr.cmd training check spec.json --sample orders.csv --url jdbc:h2:.\db 1^>^&2
    exit /b 1
)
for %%D in ("%AOTFILE%") do if not exist "%%~dpD" mkdir "%%~dpD" 2>nul
set "AOT=-XX:AOTCacheOutput=%AOTFILE%"
echo xldr: training, and writing %AOTFILE% when this run finishes 1>&2

:run
"%JAVA%" %JAVA_OPTS% %AOT% -Dxldr.home="%HERE%" ^
    -p "%MODULEPATH%" ^
    -m io.github.ralfspoeth.xldr.app/io.github.ralfspoeth.xldr.app.App %ARGS%
