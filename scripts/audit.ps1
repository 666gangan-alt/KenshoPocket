[CmdletBinding()]
param(
    [switch]$SkipGradle
)

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location -LiteralPath $repo

$findings = [System.Collections.Generic.List[string]]::new()
$blocked = [System.Collections.Generic.List[string]]::new()

Write-Output "AUDIT_REPO=$repo"
Write-Output "AUDIT_HEAD=$((git rev-parse --short HEAD).Trim())"

$status = @(git status --short)
if ($status.Count -gt 0) {
    Write-Output 'WORKTREE=DIRTY'
    $status | ForEach-Object { Write-Output "WORKTREE_ENTRY=$_" }
} else {
    Write-Output 'WORKTREE=CLEAN'
}

& git diff --check
if ($LASTEXITCODE -ne 0) { $findings.Add('git diff --check が失敗しました。空白・競合マーカーを修正してください。') }

$rgArgs = @(
    '--hidden', '--no-heading', '--line-number',
    '--glob', '!.git/**', '--glob', '!**/build/**', '--glob', '!**/.gradle/**',
    '--glob', '!**/.build-cache/**', '--glob', '!**/.build-user/**', '--glob', '!**/.android-user/**',
    '(AKIA[0-9A-Z]{16}|-----BEGIN (RSA|EC|OPENSSH|PRIVATE) KEY-----|gh[pousr]_[A-Za-z0-9_]{20,}|AIza[0-9A-Za-z_-]{20,})', '.'
)
$secretMatches = @(& rg @rgArgs 2>$null)
if ($secretMatches.Count -gt 0) {
    Write-Output 'SECRETS=FOUND'
    $secretMatches | ForEach-Object { Write-Output "SECRET_MATCH=$_" }
    $findings.Add('秘密情報らしき文字列が検出されました。値を公開リポジトリへ置かないでください。')
} else {
    Write-Output 'SECRETS=NONE'
}

$forbiddenFiles = @(git ls-files | rg '(^|/)\.kotlin/|\.jks$|\.keystore$|(^|/)local\.properties$|(^|/)\.env($|\.)')
if ($forbiddenFiles.Count -gt 0) {
    Write-Output 'FORBIDDEN_TRACKED=FOUND'
    $forbiddenFiles | ForEach-Object { Write-Output "FORBIDDEN_FILE=$_" }
    $findings.Add('署名鍵・local.properties・環境ファイル等が追跡されています。')
} else {
    Write-Output 'FORBIDDEN_TRACKED=NONE'
}

if ($SkipGradle) {
    $blocked.Add('Gradle検証はSkipGradle指定で未実行です。')
} elseif (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    $blocked.Add('JDKがPATHにありません。Gradle検証を実行できません。')
} else {
    & cmd /c gradlew.bat testDebugUnitTest lintDebug assembleDebug --stacktrace --no-daemon
    if ($LASTEXITCODE -ne 0) {
        $findings.Add("Gradle検証が失敗しました（exit=$LASTEXITCODE）。ログを保存して原因を修正してください。")
    } else {
        Write-Output 'GRADLE=PASS'
    }
}

if ($blocked.Count -gt 0) {
    Write-Output 'GRADLE=BLOCKED'
    $blocked | ForEach-Object { Write-Output "BLOCKED_REASON=$_" }
}

if ($findings.Count -gt 0) {
    Write-Output "AUDIT=FAIL findings=$($findings.Count)"
    $findings | ForEach-Object { Write-Output "FINDING=$_" }
    exit 2
}
if ($blocked.Count -gt 0) {
    Write-Output 'AUDIT=BLOCKED'
    exit 3
}
Write-Output 'AUDIT=PASS'
exit 0
