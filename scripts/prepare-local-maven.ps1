param([Parameter(Mandatory=$true)][string]$CachePath)
$ErrorActionPreference = 'Stop'
$taskRepo = Join-Path (Split-Path $PSScriptRoot -Parent) '.build-cache/maven'
New-Item -ItemType Directory -Path $taskRepo -Force | Out-Null
# Materialize existing dependency artifacts in standard Maven layout for offline builds.
foreach ($taskGroup in Get-ChildItem -LiteralPath $CachePath -Directory) {
    foreach ($taskModule in Get-ChildItem -LiteralPath $taskGroup.FullName -Directory) {
        foreach ($taskVersion in Get-ChildItem -LiteralPath $taskModule.FullName -Directory) {
            $taskTarget = Join-Path $taskRepo ($taskGroup.Name.Replace('.', '/') + '/' + $taskModule.Name + '/' + $taskVersion.Name)
            New-Item -ItemType Directory -Path $taskTarget -Force | Out-Null
            foreach ($taskArtifact in Get-ChildItem -LiteralPath $taskVersion.FullName -Recurse -File) {
                $taskFile = Join-Path $taskTarget $taskArtifact.Name
                if (!(Test-Path -LiteralPath $taskFile)) { Copy-Item -LiteralPath $taskArtifact.FullName -Destination $taskFile }
            }
            foreach ($taskMetadata in Get-ChildItem -LiteralPath $taskTarget -Filter '*.module') {
                $taskModuleData = Get-Content -LiteralPath $taskMetadata.FullName -Raw | ConvertFrom-Json
                foreach ($taskVariant in $taskModuleData.variants) {
                    foreach ($taskDeclaredFile in $taskVariant.files) {
                        $taskOriginal = Join-Path $taskTarget ([IO.Path]::GetFileName($taskDeclaredFile.name))
                        $taskCanonical = Join-Path $taskTarget ([IO.Path]::GetFileName($taskDeclaredFile.url))
                        if ((Test-Path -LiteralPath $taskOriginal) -and !(Test-Path -LiteralPath $taskCanonical)) {
                            Copy-Item -LiteralPath $taskOriginal -Destination $taskCanonical
                        }
                    }
                }
            }
        }
    }
}
Write-Output $taskRepo
