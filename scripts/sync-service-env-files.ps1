[CmdletBinding()]
param(
    [switch]$Check
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$utf8WithoutBom = New-Object System.Text.UTF8Encoding($false)

function Read-DotEnv {
    param([Parameter(Mandatory = $true)][string]$Path)

    $values = [ordered]@{}
    if (-not (Test-Path -LiteralPath $Path)) {
        return $values
    }

    foreach ($line in [System.IO.File]::ReadAllLines($Path)) {
        $trimmed = $line.Trim()
        if ($trimmed.Length -eq 0 -or $trimmed.StartsWith("#")) {
            continue
        }

        $separator = $line.IndexOf("=")
        if ($separator -le 0) {
            continue
        }

        $name = $line.Substring(0, $separator).Trim()
        $value = $line.Substring($separator + 1)
        if ($name -match "^[A-Z][A-Z0-9_]*$") {
            $values[$name] = $value
        }
    }

    return $values
}

function Test-SensitiveSetting {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [AllowEmptyString()][string]$ExampleValue
    )

    $sensitiveName = $Name -match "(?i)(PASSWORD|PASS$|SECRET|TOKEN|API_KEY|ACCESS_KEY|PRIVATE_KEY|SIGNING_KEY|ADMIN_(EMAIL|USERNAME|PASSWORD)|SMTP_USERNAME)"
    $placeholderValue = $ExampleValue -match "(?i)(change[_-]?me|replace-with|your[_-])"
    return $sensitiveName -or $placeholderValue
}

function Convert-ToMilliseconds {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Value
    )

    if ($Value -match "^[0-9]+$") {
        return $Value
    }
    if ($Value -match "^([0-9]+)ms$") {
        return $Matches[1]
    }
    if ($Value -match "^([0-9]+)s$") {
        return ([long]$Matches[1] * 1000).ToString()
    }
    if ($Value -match "^([0-9]+)m$") {
        return ([long]$Matches[1] * 60000).ToString()
    }
    throw "$Name debe expresarse como milisegundos, ms, s o m."
}

function Render-EnvironmentFile {
    param(
        [Parameter(Mandatory = $true)][string]$Title,
        [Parameter(Mandatory = $true)][System.Collections.IDictionary]$Values,
        [Parameter(Mandatory = $true)][string[]]$OrderedKeys,
        [Parameter(Mandatory = $true)][string[]]$HeaderLines
    )

    $lines = [System.Collections.Generic.List[string]]::new()
    $lines.Add("# Configuracion local para $Title.")
    foreach ($headerLine in $HeaderLines) {
        $lines.Add("# $headerLine")
    }
    $lines.Add("")
    foreach ($name in $OrderedKeys) {
        $lines.Add("$name=$($Values[$name])")
    }
    $lines.Add("")
    return [string]::Join("`n", $lines)
}

function Save-OrCheckFile {
    param(
        [Parameter(Mandatory = $true)][string]$RelativePath,
        [Parameter(Mandatory = $true)][string]$Content
    )

    $path = Join-Path $repositoryRoot $RelativePath
    if ($Check) {
        if (-not (Test-Path -LiteralPath $path)) {
            throw "Falta el archivo generado: $RelativePath"
        }
        if ([System.IO.File]::ReadAllText($path) -ne $Content) {
            throw "El archivo generado no esta sincronizado: $RelativePath"
        }
        return
    }

    [System.IO.Directory]::CreateDirectory((Split-Path -Parent $path)) | Out-Null
    [System.IO.File]::WriteAllText($path, $Content, $utf8WithoutBom)
}

$targets = @(
    [pscustomobject]@{ Name = "Scraper API"; ExamplePath = "api/scraper/.env.example"; OutputPath = "api/scraper/.env" },
    [pscustomobject]@{ Name = "Scraper Scheduler"; ExamplePath = "api/scraper/.env.scheduler.example"; OutputPath = "api/scraper/.env.scheduler" },
    [pscustomobject]@{ Name = "Core API"; ExamplePath = "services/core-api/.env.example"; OutputPath = "services/core-api/.env" },
    [pscustomobject]@{ Name = "Download Worker"; ExamplePath = "services/download-worker/.env.example"; OutputPath = "services/download-worker/.env" },
    [pscustomobject]@{ Name = "Notification Service"; ExamplePath = "services/notification-service/.env.example"; OutputPath = "services/notification-service/.env" },
    [pscustomobject]@{ Name = "Semantic API"; ExamplePath = "services/semantic-service/.env.example"; OutputPath = "services/semantic-service/.env" },
    [pscustomobject]@{ Name = "Semantic Indexer"; ExamplePath = "services/semantic-service/.env.indexer.example"; OutputPath = "services/semantic-service/.env.indexer" },
    [pscustomobject]@{ Name = "Semantic Model Worker"; ExamplePath = "services/semantic-service/.env.model-worker.example"; OutputPath = "services/semantic-service/.env.model-worker" },
    [pscustomobject]@{ Name = "Semantic Trainer"; ExamplePath = "services/semantic-service/.env.trainer.example"; OutputPath = "services/semantic-service/.env.trainer" },
    [pscustomobject]@{ Name = "Translation Service"; ExamplePath = "services/translation-service/.env.example"; OutputPath = "services/translation-service/.env" },
    [pscustomobject]@{ Name = "Webapp Frontend"; ExamplePath = "services/webapp/src/main/resources/frontend/.env.example"; OutputPath = "services/webapp/src/main/resources/frontend/.env" }
)

$globalExamplePath = Join-Path $repositoryRoot ".env.example"
$globalEnvironmentPath = Join-Path $repositoryRoot ".env"
$globalExample = Read-DotEnv -Path $globalExamplePath
$globalCurrent = Read-DotEnv -Path $globalEnvironmentPath
if ($globalExample.Count -eq 0) {
    throw "No se ha podido leer la configuracion global de .env.example"
}
if ($globalCurrent.Count -eq 0) {
    throw "Falta .env en la raiz. Copia .env.example y configura primero los secretos globales."
}

$globalValues = [ordered]@{}
foreach ($name in $globalExample.Keys) {
    if ($globalCurrent.Contains($name)) {
        $globalValues[$name] = $globalCurrent[$name]
        continue
    }
    if ((Test-SensitiveSetting -Name $name -ExampleValue ([string]$globalExample[$name])) -and
        -not [string]::IsNullOrEmpty([string]$globalExample[$name])) {
        throw "Falta el valor sensible $name en el .env global. Debe configurarse manualmente."
    }
    $globalValues[$name] = $globalExample[$name]
}

$globalContent = Render-EnvironmentFile `
    -Title "Batch Downloader (valores globales)" `
    -Values $globalValues `
    -OrderedKeys @($globalExample.Keys) `
    -HeaderLines @(
        "Solo contiene infraestructura compartida, puertos y credenciales.",
        "Los ajustes operativos pertenecen a los .env de cada servicio."
    )
Save-OrCheckFile -RelativePath ".env" -Content $globalContent

foreach ($target in $targets) {
    $examplePath = Join-Path $repositoryRoot $target.ExamplePath
    $exampleValues = Read-DotEnv -Path $examplePath
    if ($exampleValues.Count -eq 0) {
        throw "La plantilla local esta vacia: $($target.ExamplePath)"
    }

    foreach ($name in $exampleValues.Keys) {
        if (Test-SensitiveSetting -Name $name -ExampleValue ([string]$exampleValues[$name])) {
            throw "La clave sensible $name debe permanecer exclusivamente en el .env global."
        }
        if ($name -match "_HOST_PORT$") {
            throw "El puerto global $name no puede estar en $($target.ExamplePath)."
        }
    }

    $currentValues = Read-DotEnv -Path (Join-Path $repositoryRoot $target.OutputPath)
    $localValues = [ordered]@{}
    foreach ($name in $exampleValues.Keys) {
        if ($currentValues.Contains($name)) {
            $localValues[$name] = $currentValues[$name]
        } else {
            $localValues[$name] = $exampleValues[$name]
        }
        if ($name -eq "CORE_API_DB_POOL_TIMEOUT") {
            $localValues[$name] = Convert-ToMilliseconds -Name $name -Value ([string]$localValues[$name])
        }
    }

    $localContent = Render-EnvironmentFile `
        -Title $target.Name `
        -Values $localValues `
        -OrderedKeys @($exampleValues.Keys) `
        -HeaderLines @(
            "Ignorado por Git y sincronizado desde su .env.example local.",
            "No contiene contrasenas, tokens, claves de firma ni API keys."
        )
    Save-OrCheckFile -RelativePath $target.OutputPath -Content $localContent
}

$verb = if ($Check) { "Validados" } else { "Sincronizados" }
Write-Output "$verb el entorno global y $($targets.Count) entornos locales sin duplicar credenciales."
