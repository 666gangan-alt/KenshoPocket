param(
    [string[]]$Tasks = @('testDebugUnitTest', 'lintDebug', 'assembleDebug'),
    [switch]$CheckOnly,
    [switch]$Offline
)
$ErrorActionPreference = 'Stop'
$taskWorkspace = Split-Path -Parent $PSScriptRoot
$taskBuildRoot = $taskWorkspace
if ($taskWorkspace -match '[^\x00-\x7F]') {
    $taskDigest = [BitConverter]::ToString([Security.Cryptography.SHA256]::Create().ComputeHash([Text.Encoding]::UTF8.GetBytes($taskWorkspace))).Replace('-', '').Substring(0, 12)
    $taskJunction = Join-Path $env:LOCALAPPDATA "kensho-build-$taskDigest"
    # Reuse only the already-established alias. Never create a link outside the workspace.
    if (Test-Path -LiteralPath $taskJunction) {
        $taskExisting = Get-Item -LiteralPath $taskJunction
        if ($taskExisting.LinkType -ne 'Junction' -or [IO.Path]::GetFullPath($taskExisting.Target) -ne [IO.Path]::GetFullPath($taskWorkspace)) { throw 'Build junction target mismatch' }
        $taskBuildRoot = $taskJunction
    }
}
$taskJava = Join-Path $taskBuildRoot '.tools/java/jdk-17.0.20.1+1'
$taskSdk = Join-Path $taskBuildRoot '.tools/android-sdk'
$taskProject = Join-Path $taskBuildRoot 'KenshoPocket'
$taskJavaExecutable = Join-Path $taskJava 'bin/java.exe'
if (!(Test-Path -LiteralPath $taskJavaExecutable)) { throw "JDK 17 is missing: $taskJavaExecutable" }
if (!(Test-Path -LiteralPath (Join-Path $taskSdk 'platforms/android-36/android.jar'))) { throw 'Android SDK platform 36 is missing' }

$taskEnvironmentNames = @('JAVA_HOME', 'ANDROID_HOME', 'ANDROID_USER_HOME', 'GRADLE_USER_HOME', 'JAVA_TOOL_OPTIONS', 'KENSHO_DEBUG_KEYSTORE', 'KENSHO_MAVEN_REPO')
$taskOriginalEnvironment = @{}
foreach ($taskName in $taskEnvironmentNames) { $taskOriginalEnvironment[$taskName] = [Environment]::GetEnvironmentVariable($taskName, 'Process') }
$taskLocationPushed = $false
try {
    $taskJavaUser = Join-Path $taskProject '.build-user'
    $taskJavaTemp = Join-Path $taskJavaUser 'tmp'
    $taskAndroidUser = Join-Path $taskProject '.android-user'
    $taskGradleUser = Join-Path $taskProject '.build-cache'
    foreach ($taskDirectory in @($taskJavaUser, $taskJavaTemp, $taskAndroidUser, $taskGradleUser)) {
        New-Item -ItemType Directory -Path $taskDirectory -Force | Out-Null
    }
    $env:JAVA_HOME = $taskJava
    $env:ANDROID_HOME = $taskSdk
    $env:ANDROID_USER_HOME = $taskAndroidUser
    $env:GRADLE_USER_HOME = $taskGradleUser
    # JVM children (including Gradle workers) receive the same writable home/temp directories.
    $taskJvmOptions = '"-Duser.home={0}" "-Djava.io.tmpdir={1}" -Dfile.encoding=UTF-8' -f $taskJavaUser, $taskJavaTemp
    $env:JAVA_TOOL_OPTIONS = (@($taskOriginalEnvironment['JAVA_TOOL_OPTIONS'], $taskJvmOptions) | Where-Object { $_ }) -join ' '

    # Do not silently generate a new signing identity when user.home changes.
    if (!$env:KENSHO_DEBUG_KEYSTORE) {
        $taskExistingKey = Join-Path $env:USERPROFILE '.android/debug.keystore'
        if (Test-Path -LiteralPath $taskExistingKey) { $env:KENSHO_DEBUG_KEYSTORE = $taskExistingKey }
        elseif (Test-Path -LiteralPath (Join-Path $taskProject 'app/build/outputs/apk/debug/app-debug.apk')) {
            throw 'Existing APK found but its debug key is unavailable. Set KENSHO_DEBUG_KEYSTORE to the original key before building.'
        }
    }
    if ($env:KENSHO_DEBUG_KEYSTORE -and !(Test-Path -LiteralPath $env:KENSHO_DEBUG_KEYSTORE)) { throw 'KENSHO_DEBUG_KEYSTORE does not exist' }

    $taskGradleArguments = @($Tasks) + @('--console=plain', '-Pkotlin.compiler.execution.strategy=in-process')
    if ($Offline) {
        if (!$env:KENSHO_MAVEN_REPO) { $env:KENSHO_MAVEN_REPO = Join-Path $taskProject '.build-cache/maven' }
        if (!(Test-Path -LiteralPath $env:KENSHO_MAVEN_REPO)) { throw 'Offline Maven cache is missing. See scripts/prepare-local-maven.ps1.' }
        $taskGradleArguments += '--offline'
    }

    Push-Location $taskProject
    $taskLocationPushed = $true
    & $taskJavaExecutable (Join-Path $taskProject 'scripts/BuildPreflight.java') $taskProject $taskSdk
    if ($LASTEXITCODE -ne 0) {
        throw 'BUILD_ENVIRONMENT_BLOCKED: Java real-path access failed. No build was started. See docs/BUILD_ENVIRONMENT.md. Do not skip kapt/javac or widen filesystem permissions in this script.'
    }
    if ($CheckOnly) { return }
    & .\gradlew.bat @taskGradleArguments
    if ($LASTEXITCODE -ne 0) { throw 'Build or test failed. No success or updated APK is claimed.' }
} finally {
    if ($taskLocationPushed) { Pop-Location }
    foreach ($taskName in $taskEnvironmentNames) {
        if ($null -eq $taskOriginalEnvironment[$taskName]) {
            Remove-Item -LiteralPath "Env:$taskName" -ErrorAction SilentlyContinue
        } else {
            [Environment]::SetEnvironmentVariable($taskName, $taskOriginalEnvironment[$taskName], 'Process')
        }
    }
}
