<#
.SYNOPSIS
    Genera el snapshot del frontend Photino que sirve el gateway web.

.DESCRIPTION
    1. Extrae src/UI/www de un COMMIT del repo Photino (git archive; nunca del working tree, así
       cambios sin commitear en Photino no se filtran a la web).
    2. Copia web-shim/web-bridge.js a <snapshot>/web/web-bridge.js.
    3. Inyecta UNA línea <script src="web/web-bridge.js"> en el index.html del snapshot, justo
       antes de shared/utils.js (después de la definición inline de PhotinoBridge).
    4. Escribe web-dist/web-manifest.json (versión/commit Photino, hashes, fecha).

    Solo LEE el repo Photino (git rev-parse / show / archive). No modifica nada allí.
    Solo escribe dentro de <repo web>/web-dist/.

.PARAMETER PhotinoRepo
    Ruta al repo Photino. Por defecto ..\qualitycontrol_desktop_faret (hermano de este repo).

.PARAMETER Commit
    Commit, rama o tag de Photino a sincronizar. Por defecto HEAD.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File tools\sync-photino-www.ps1 -Commit 6c42e05
#>
[CmdletBinding()]
param(
    [string]$PhotinoRepo,
    [string]$Commit = "HEAD"
)

$ErrorActionPreference = "Stop"

$webRepo = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
if (-not $PhotinoRepo) {
    $PhotinoRepo = Join-Path (Split-Path $webRepo -Parent) "qualitycontrol_desktop_faret"
}
$PhotinoRepo = (Resolve-Path $PhotinoRepo).Path
$outDir = Join-Path $webRepo "web-dist"
$wwwOut = Join-Path $outDir "photino-www"
$shimSrc = Join-Path $webRepo "web-shim\web-bridge.js"
$tmpDir = Join-Path $outDir "_tmp_sync"

function Invoke-Git {
    param([string]$Repo, [string[]]$GitArgs)
    $out = & git -C $Repo @GitArgs
    if ($LASTEXITCODE -ne 0) { throw "git $($GitArgs -join ' ') fallo en $Repo (exit $LASTEXITCODE)" }
    return $out
}

function Get-TreeSha256 {
    # Hash determinista de una carpeta: rutas relativas ordenadas + hash de cada archivo.
    param([string]$Root)
    $rootFull = (Resolve-Path $Root).Path.TrimEnd('\')
    $lines = Get-ChildItem -Path $rootFull -Recurse -File |
        ForEach-Object {
            $rel = $_.FullName.Substring($rootFull.Length + 1).Replace('\', '/')
            $h = (Get-FileHash -Algorithm SHA256 -LiteralPath $_.FullName).Hash.ToLowerInvariant()
            "$rel $h"
        } | Sort-Object { $_ } -CaseSensitive
    $sha = [System.Security.Cryptography.SHA256]::Create()
    $bytes = [System.Text.Encoding]::UTF8.GetBytes(($lines -join "`n"))
    return ([BitConverter]::ToString($sha.ComputeHash($bytes)) -replace '-', '').ToLowerInvariant()
}

# --- 1. Resolver commit y versión de Photino -------------------------------------------------
$sha = (Invoke-Git $PhotinoRepo @("rev-parse", "--verify", "$Commit^{commit}")).Trim()
$commitDate = (Invoke-Git $PhotinoRepo @("show", "-s", "--format=%cI", $sha)).Trim()
$csproj = (Invoke-Git $PhotinoRepo @("show", "${sha}:QualityControlCenter.csproj")) -join "`n"
$m = [regex]::Match($csproj, '<Version>\s*([^<]+?)\s*</Version>')
if (-not $m.Success) { throw "No se encontro <Version> en QualityControlCenter.csproj del commit $sha" }
$photinoVersion = $m.Groups[1].Value
Write-Host "Photino $photinoVersion @ $sha ($commitDate)"

# --- 2. Extraer src/UI/www del commit -----------------------------------------------------------
if (-not (Test-Path $outDir)) { New-Item -ItemType Directory -Path $outDir | Out-Null }
if (Test-Path $tmpDir) { Remove-Item -LiteralPath $tmpDir -Recurse -Force }
New-Item -ItemType Directory -Path $tmpDir | Out-Null
$zip = Join-Path $tmpDir "www.zip"
# core.autocrlf=false: bytes exactos del commit, sin conversión de finales de línea de este equipo
# (así wwwSha256 es el mismo en cualquier máquina).
Invoke-Git $PhotinoRepo @("-c", "core.autocrlf=false", "archive", "--format=zip", "-o", $zip, $sha, "src/UI/www") | Out-Null
Expand-Archive -LiteralPath $zip -DestinationPath $tmpDir
$extracted = Join-Path $tmpDir "src\UI\www"
if (-not (Test-Path (Join-Path $extracted "index.html"))) { throw "El commit $sha no contiene src/UI/www/index.html" }

$wwwSha256 = Get-TreeSha256 $extracted

# --- 3. Copiar shim e inyectar una linea en index.html -----------------------------------------
$webFolder = Join-Path $extracted "web"
if (Test-Path $webFolder) { throw "El frontend Photino ya tiene una carpeta 'web/'; el shim colisionaria. Revisar." }
New-Item -ItemType Directory -Path $webFolder | Out-Null
Copy-Item -LiteralPath $shimSrc -Destination (Join-Path $webFolder "web-bridge.js")
$shimSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $shimSrc).Hash.ToLowerInvariant()

$indexPath = Join-Path $extracted "index.html"
$indexBytes = [System.IO.File]::ReadAllBytes($indexPath)
$hasBom = $indexBytes.Length -ge 3 -and $indexBytes[0] -eq 0xEF -and $indexBytes[1] -eq 0xBB -and $indexBytes[2] -eq 0xBF
$utf8 = New-Object System.Text.UTF8Encoding($hasBom)
$index = [System.IO.File]::ReadAllText($indexPath, $utf8)
$ancla = '<script src="shared/utils.js"></script>'
$ocurrencias = ([regex]::Matches($index, [regex]::Escape($ancla))).Count
if ($ocurrencias -ne 1) { throw "Se esperaba exactamente 1 '$ancla' en index.html, hay $ocurrencias. El shim no se inyecto." }
$nl = if ($index.Contains("`r`n")) { "`r`n" } else { "`n" }
$inyeccion = '<script src="web/web-bridge.js"></script><!-- QCC Web: inyectado por tools/sync-photino-www.ps1 -->' + $nl + '    ' + $ancla
$index = $index.Replace($ancla, $inyeccion)
[System.IO.File]::WriteAllText($indexPath, $index, $utf8)

# --- 4. Reemplazar snapshot anterior y escribir manifest ---------------------------------------
if (Test-Path $wwwOut) { Remove-Item -LiteralPath $wwwOut -Recurse -Force }
Move-Item -LiteralPath $extracted -Destination $wwwOut
Remove-Item -LiteralPath $tmpDir -Recurse -Force

$webCommit = $null
try { $webCommit = (& git -C $webRepo rev-parse HEAD).Trim() } catch { }
$webDirty = [bool](& git -C $webRepo status --porcelain)

$manifest = [ordered]@{
    photinoVersion    = $photinoVersion
    photinoCommit     = $sha
    photinoCommitDate = $commitDate
    wwwSha256         = $wwwSha256
    shimSha256        = $shimSha256
    syncedAt          = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
    webRepoCommit     = $webCommit
    webRepoDirty      = $webDirty
}
$json = $manifest | ConvertTo-Json
[System.IO.File]::WriteAllText((Join-Path $outDir "web-manifest.json"), $json, (New-Object System.Text.UTF8Encoding($false)))

Write-Host "Snapshot listo: $wwwOut"
Write-Host "Manifest:      $(Join-Path $outDir 'web-manifest.json')"
Write-Host "wwwSha256 (original Photino): $wwwSha256"
