@echo off
setlocal
set HERE=%~dp0..
java %JAVA_OPTS% -p "%HERE%\lib" -m io.github.ralfspoeth.xldr.app/io.github.ralfspoeth.xldr.app.Main %*
