param([string]$MavenPath, [string]$JavaDirectory)
$ErrorActionPreference = 'Stop'
$taskProject = Split-Path $PSScriptRoot -Parent
if (!$MavenPath) { $MavenPath = Join-Path $taskProject '.build-cache/maven' }
if (!$JavaDirectory) { $JavaDirectory = Join-Path (Split-Path $taskProject -Parent) '.tools/java/jdk-17.0.20.1+1' }
function Get-Artifact([string]$relative) {
    $taskArtifact = Join-Path $MavenPath $relative
    if (!(Test-Path -LiteralPath $taskArtifact)) { throw "Missing cached artifact: $relative" }
    return $taskArtifact
}
$taskStdlib = Get-Artifact 'org/jetbrains/kotlin/kotlin-stdlib/2.3.21/kotlin-stdlib-2.3.21.jar'
$taskJunit = Get-Artifact 'junit/junit/4.13.2/junit-4.13.2.jar'
$taskHamcrest = Get-Artifact 'org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar'
$taskAnnotations = Get-Artifact 'org/jetbrains/annotations/13.0/annotations-13.0.jar'
$taskCompiler = @(
    (Get-Artifact 'org/jetbrains/kotlin/kotlin-compiler-embeddable/2.3.21/kotlin-compiler-embeddable-2.3.21.jar'),
    $taskStdlib,
    (Get-Artifact 'org/jetbrains/kotlin/kotlin-script-runtime/2.3.21/kotlin-script-runtime-2.3.21.jar'),
    (Get-Artifact 'org/jetbrains/kotlin/kotlin-reflect/1.6.10/kotlin-reflect-1.6.10.jar'),
    (Get-Artifact 'org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.8.0/kotlinx-coroutines-core-jvm-1.8.0.jar'),
    $taskAnnotations
) -join [IO.Path]::PathSeparator
$taskLibraries = @($taskStdlib, $taskJunit, $taskHamcrest, $taskAnnotations) -join [IO.Path]::PathSeparator
$taskSources = @(
    'app/src/main/java/jp/kenshopocket/app/domain/reminder/ReminderLogic.kt',
    'app/src/main/java/jp/kenshopocket/app/domain/importer/ImportParser.kt',
    'app/src/test/java/jp/kenshopocket/app/domain/reminder/ReminderLogicTest.kt',
    'app/src/test/java/jp/kenshopocket/app/domain/reminder/PeriodRegressionTest.kt',
    'app/src/test/java/jp/kenshopocket/app/domain/importer/ImportParserTest.kt'
) | ForEach-Object { Join-Path $taskProject $_ }
$taskOutput = Join-Path $taskProject ('.build-cache/domain-tests-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $taskOutput | Out-Null
$taskJava = Join-Path $JavaDirectory 'bin/java.exe'
& $taskJava -cp $taskCompiler org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -classpath $taskLibraries -d $taskOutput @taskSources
if ($LASTEXITCODE -ne 0) { throw 'Domain test compilation failed' }
& $taskJava -cp ($taskOutput + [IO.Path]::PathSeparator + $taskLibraries) org.junit.runner.JUnitCore jp.kenshopocket.app.domain.reminder.ReminderLogicTest jp.kenshopocket.app.domain.reminder.PeriodRegressionTest jp.kenshopocket.app.domain.importer.ImportParserTest
if ($LASTEXITCODE -ne 0) { throw 'Domain tests failed' }
# This diagnostic suite does not replace the Android/Room/OS test and APK build tasks.
