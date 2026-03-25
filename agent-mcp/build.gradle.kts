import org.gradle.jvm.application.tasks.CreateStartScripts
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import java.io.File

plugins {
    application
    alias(libs.plugins.kotlinJvm)
}

dependencies {
    implementation(project(":bridge-contract"))
    implementation(libs.kotlinStdLib)
    implementation(libs.jacksonDatabind)
    implementation(libs.jacksonKotlin)
}

application {
    mainClass.set("com.molibrary.rider.nativerename.agent.AgentMcpMainKt")
    applicationName = "rider-native-rename-mcp"
}

tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

fun shellScript(template: String): String = template.replace("__DOLLAR__", "$")

fun patchUnixStartScript(scriptFile: File) {
    val original = scriptFile.readText().replace("\r\n", "\n")
    val startMarker = "# Determine the Java command to use to start the JVM.\n"
    val endMarker = "\n# Increase the maximum file descriptors if we can.\n"
    val start = original.indexOf(startMarker)
    val end = original.indexOf(endMarker, start)
    check(start >= 0 && end >= 0) { "Could not find the Java launcher block in the generated Unix start script." }

    val replacement = shellScript("""
        # Determine the Java command to use to start the JVM.
        # Ignore a stale JAVA_HOME when it points to an invalid or too-old runtime.
        if [ -n "__DOLLAR__JAVA_HOME" ] ; then
            JAVA_HOME_STRIPPED=__DOLLAR__{JAVA_HOME#\"}
            JAVA_HOME_STRIPPED=__DOLLAR__{JAVA_HOME_STRIPPED%\"}
            JAVA_HOME_CANDIDATE=__DOLLAR__JAVA_HOME_STRIPPED/bin/java
            if [ ! -x "__DOLLAR__JAVA_HOME_CANDIDATE" ] && [ -x "__DOLLAR__JAVA_HOME_STRIPPED/jre/sh/java" ] ; then
                JAVA_HOME_CANDIDATE=__DOLLAR__JAVA_HOME_STRIPPED/jre/sh/java
            fi
            if [ ! -x "__DOLLAR__JAVA_HOME_CANDIDATE" ] ; then
                unset JAVA_HOME
            else
                JAVA_HOME_VERSION=__DOLLAR__("__DOLLAR__JAVA_HOME_CANDIDATE" -version 2>&1 | sed -n '1{s/.*version \"\(1\.\)\?\([0-9][0-9]*\).*/\2/p;}')
                if [ -z "__DOLLAR__JAVA_HOME_VERSION" ] || [ "__DOLLAR__JAVA_HOME_VERSION" -lt 21 ] ; then
                    unset JAVA_HOME
                fi
            fi
        fi

        if [ -n "__DOLLAR__JAVA_HOME" ] ; then
            if [ -x "__DOLLAR__JAVA_HOME/jre/sh/java" ] ; then
                # IBM's JDK on AIX uses strange locations for the executables
                JAVACMD=__DOLLAR__JAVA_HOME/jre/sh/java
            else
                JAVACMD=__DOLLAR__JAVA_HOME/bin/java
            fi
            if [ ! -x "__DOLLAR__JAVACMD" ] ; then
                die "ERROR: JAVA_HOME is set to an invalid directory: __DOLLAR__JAVA_HOME

        Please set the JAVA_HOME variable in your environment to match the
        location of your Java installation."
            fi
        else
            JAVACMD=java
            if ! command -v java >/dev/null 2>&1
            then
                die "ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.

        Please set the JAVA_HOME variable in your environment to match the
        location of your Java installation."
            fi
        fi

        JAVA_VERSION=__DOLLAR__("__DOLLAR__JAVACMD" -version 2>&1 | sed -n '1{s/.*version \"\(1\.\)\?\([0-9][0-9]*\).*/\2/p;}')
        if [ -z "__DOLLAR__JAVA_VERSION" ] || [ "__DOLLAR__JAVA_VERSION" -lt 21 ] ; then
            die "ERROR: rider-native-rename-mcp requires Java 21 or newer.
        Detected runtime: __DOLLAR__("__DOLLAR__JAVACMD" -version 2>&1 | sed -n '1p')"
        fi
    """.trimIndent()) + "\n"

    val patched = original.substring(0, start) + replacement + original.substring(end)

    scriptFile.writeText(patched)
}

fun patchWindowsStartScript(scriptFile: File) {
    val replacement = """
@rem Find java.exe
if defined JAVA_HOME goto findJavaFromJavaHome
goto findJavaFromPath

:findJavaFromJavaHome
set JAVA_HOME=%JAVA_HOME:"=%
set JAVA_EXE=%JAVA_HOME%/bin/java.exe
if exist "%JAVA_EXE%" call :validateJavaVersion
if exist "%JAVA_EXE%" if %ERRORLEVEL% equ 0 goto execute

:findJavaFromPath
set JAVA_EXE=java.exe
%JAVA_EXE% -version >NUL 2>&1
if %ERRORLEVEL% neq 0 goto noJava
call :validateJavaVersion
if %ERRORLEVEL% equ 0 goto execute

echo. 1>&2
echo ERROR: rider-native-rename-mcp requires Java 21 or newer. 1>&2
echo Detected runtime: %JAVA_VERSION_RAW% 1>&2

goto fail

:noJava
echo. 1>&2
echo ERROR: Java 21 or newer was not found. Check JAVA_HOME or PATH. 1>&2
echo. 1>&2

goto fail

:validateJavaVersion
set JAVA_VERSION_RAW=
set JAVA_VERSION_MAJOR=
set JAVA_VERSION_MINOR=
for /f "tokens=3 delims=\" %%v in ('"%JAVA_EXE%" -version 2^>^&1') do (
    set JAVA_VERSION_RAW=%%v
    goto determineJavaMajor
)
exit /b 1

:determineJavaMajor
for /f "tokens=1,2 delims=." %%a in ("%JAVA_VERSION_RAW%") do (
    set JAVA_VERSION_MAJOR=%%a
    set JAVA_VERSION_MINOR=%%b
)
if "%JAVA_VERSION_MAJOR%"=="1" set JAVA_VERSION_MAJOR=%JAVA_VERSION_MINOR%
if not defined JAVA_VERSION_MAJOR exit /b 1
if %JAVA_VERSION_MAJOR% lss 21 exit /b 1
exit /b 0

:execute
""".trimIndent()

    val original = scriptFile.readText().replace("\r\n", "\n")
    val patched = original.replace(
        """
@rem Find java.exe
if defined JAVA_HOME goto findJavaFromJavaHome

set JAVA_EXE=java.exe
%JAVA_EXE% -version >NUL 2>&1
if %ERRORLEVEL% equ 0 goto execute

echo. 1>&2
echo ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH. 1>&2
echo. 1>&2
echo Please set the JAVA_HOME variable in your environment to match the 1>&2
echo location of your Java installation. 1>&2

goto fail

:findJavaFromJavaHome
set JAVA_HOME=%JAVA_HOME:"=%
set JAVA_EXE=%JAVA_HOME%/bin/java.exe

if exist "%JAVA_EXE%" goto execute

echo. 1>&2
echo ERROR: JAVA_HOME is set to an invalid directory: %JAVA_HOME% 1>&2
echo. 1>&2
echo Please set the JAVA_HOME variable in your environment to match the 1>&2
echo location of your Java installation. 1>&2

goto fail

:execute
""".trimIndent().replace("\r\n", "\n"),
        replacement
    )

    scriptFile.writeText(patched.replace("\n", "\r\n"))
}

tasks.named<CreateStartScripts>("startScripts") {
    doLast {
        patchUnixStartScript(unixScript)
        patchWindowsStartScript(windowsScript)
    }
}
