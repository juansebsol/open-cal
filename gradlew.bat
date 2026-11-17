@echo off
setlocal
cd /d "%~dp0"
if not "%JAVA_HOME%"=="" (set JAVA_BIN=%JAVA_HOME%\bin\java.exe) else (set JAVA_BIN=java)
"%JAVA_BIN%" -cp "%~dp0gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
exit /b %ERRORLEVEL%
