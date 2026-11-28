$ErrorActionPreference = 'Stop'

$templates = git -C $PSScriptRoot ls-files -- ':(glob)**/.env.example'
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

foreach ($template in $templates) {
    $source = Join-Path $PSScriptRoot $template
    $envPath = $template -replace '\.example$', ''
    $target = Join-Path $PSScriptRoot $envPath
    if (-not (Test-Path -LiteralPath $target)) {
        [IO.File]::Copy($source, $target)
        Write-Output "Creado $envPath"
    }
}
