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
setlocal
set "HERE=%~dp0.."

if not exist "%HERE%\lib" (
    echo xldr: no lib\ in %HERE% - is the distribution complete? 1>&2
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

"%JAVA%" %JAVA_OPTS% -Dxldr.home="%HERE%" -p "%MODULES%" ^
    -m io.github.ralfspoeth.xldr.app/io.github.ralfspoeth.xldr.app.App %*
