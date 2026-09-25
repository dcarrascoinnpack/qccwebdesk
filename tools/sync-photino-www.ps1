<#
.SYNOPSIS
    Genera el snapshot del frontend Photino que sirve el gateway web.

.DESCRIPTION
    1. Extrae src/UI/www de un COMMIT del repo Photino (git archive; nunca del working tree, así
       cambios sin commitear en Photino no se filtran a la web).
    2. Copia web-shim/web-bridge.js a <snapshot>/web/web-bridge.js.
    3. Inyecta UNA línea <script src="web/web-bridge.js"> en el index.html del snapshot, justo
       antes de shared/utils.js (después de la definición inline de PhotinoBridge).
    4. Contract check (tools/contract/photino_contract.py) del MISMO commit contra la ActionPolicy
       Java exportada (huella C# + huella del llamado JS de cada acción habilitada).
    5. Solo si el contrato NO es bloqueante, PROMUEVE el candidato: reemplaza web-dist/photino-www,
       web-dist/contract/ y escribe web-dist/web-manifest.json. Si es bloqueante (o -SoloVerificar),
       el snapshot vigente queda INTACTO y el candidato queda en web-dist/_candidato/ para revisión.

    Así Photino puede publicar una versión nueva aunque Web aún no sea compatible: Web sigue
    sirviendo el último commit Photino validado (manifest.photinoValidado) y nunca declara una
    compatibilidad falsa.

    Código de salida: 0 = listo (o verificación sin bloqueos); 2 = contract check BLOQUEANTE
    (acciones REVISAR / SOLO_WEB): no se promueve. Un bloqueo aquí NUNCA afecta a Photino.

    Solo LEE el repo Photino (git rev-parse / show / archive / cat-file). No modifica nada allí.
    Solo escribe dentro de <repo web>/web-dist/ y backend/target/.

.PARAMETER PhotinoRepo
    Ruta al repo Photino. Por defecto ..\qualitycontrol_desktop_faret (hermano de este repo).

.PARAMETER Commit
    Commit, rama o tag de Photino a sincronizar. Por defecto HEAD.

.PARAMETER SoloVerificar
    Evalúa el commit (p. ej. una versión nueva de Photino) sin tocar el snapshot vigente: deja el
    candidato y su contract-report en web-dist/_candidato/.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File tools\sync-photino-www.ps1 -Commit 6c42e05
    powershell -ExecutionPolicy Bypass -File tools\sync-photino-www.ps1 -Commit HEAD -SoloVerificar
#>
[CmdletBinding()]
param(
    [string]$PhotinoRepo,
    [string]$Commit = "HEAD",
    # Ejecutable de Python (solo herramienta de build para el contract check; no es runtime).
    [string]$Python = "python",
    # Solo desarrollo: genera el snapshot sin contract check (/version mostrará NO_EVALUADO).
    [switch]$SinContrato,
    [switch]$SoloVerificar
)

$ErrorActionPreference = "Stop"

$webRepo = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
if (-not $PhotinoRepo) {
    $PhotinoRepo = Join-Path (Split-Path $webRepo -Parent) "qualitycontrol_desktop_faret"
}
$PhotinoRepo = (Resolve-Path $PhotinoRepo).Path
$outDir = Join-Path $webRepo "web-dist"
$wwwOut = Join-Path $outDir "photino-www"
$contractOut = Join-Path $outDir "contract"
$shimSrc = Join-Path $webRepo "web-shim\web-bridge.js"
$tmpDir = Join-Path $outDir "_tmp_sync"
$candidato = Join-Path $outDir "_candidato"

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
Remove-Item -LiteralPath $zip -Force
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

# Hash del snapshot FINAL (original + shim + inyección): mismo commit + mismo shim => mismo valor.
$webDistSha256 = Get-TreeSha256 $extracted

$webCommit = $null
try { $webCommit = (& git -C $webRepo rev-parse HEAD).Trim() } catch { }
# Versión del gateway (pom): la web siempre se declara junto a la versión/commit Photino.
$pomXml = [System.IO.File]::ReadAllText((Join-Path $webRepo "backend\pom.xml"))
$gw = [regex]::Match($pomXml, '<artifactId>qcc-api</artifactId>[\s\S]*?<version>\s*([^<]+?)\s*</version>')
$gatewayVersion = if ($gw.Success) { $gw.Groups[1].Value } else { $null }
$producto = "QCC Web $gatewayVersion $([char]0x00B7) Photino $photinoVersion ($($sha.Substring(0,7)))"  # [char]: PS 5.1 lee este .ps1 como ANSI
$webDirty = [bool](& git -C $webRepo status --porcelain)

# --- 4. Contract check Photino <-> Web (mismo commit), escrito en el candidato ------------------
# Nunca afecta a Photino: solo decide si esta versión WEB puede promoverse/empaquetarse.
$contrato = $null
$acciones = $null
$tmpContract = Join-Path $tmpDir "contract"
if ($SinContrato) {
    Write-Warning "Contract check OMITIDO (-SinContrato): /version mostrara NO_EVALUADO."
} else {
    Write-Host "Exportando ActionPolicy (mvnw -Dtest=ActionPolicyExportTest test)..."
    $backend = Join-Path $webRepo "backend"
    Push-Location $backend
    try {
        & .\mvnw.cmd -q "-Dtest=ActionPolicyExportTest" test | Out-Host
        if ($LASTEXITCODE -ne 0) { throw "El export de la ActionPolicy fallo (exit $LASTEXITCODE)" }
    } finally { Pop-Location }

    $webActions = Join-Path $backend "target\contract\web-actions.json"
    & $Python (Join-Path $PSScriptRoot "contract\photino_contract.py") `
        --photino-repo $PhotinoRepo --commit $sha `
        --web-actions $webActions `
        --web-shim $shimSrc `
        --baseline (Join-Path $webRepo "contract\baseline.json") `
        --out $tmpContract | Out-Host
    $contractExit = $LASTEXITCODE
    if ($contractExit -eq 1) { throw "El contract check no pudo ejecutarse (ver error arriba)." }

    $reporte = [System.IO.File]::ReadAllText((Join-Path $tmpContract "contract-report.json"), [System.Text.Encoding]::UTF8) | ConvertFrom-Json
    $r = $reporte.resumen
    $estado = if ($reporte.bloqueante) { "BLOQUEADO" } elseif ($r.compatibles -eq $r.accionesFrontend) { "COMPLETA" } else { "PARCIAL" }
    $contrato = [ordered]@{
        estado           = $estado
        texto            = $r.texto
        compatibles      = $r.compatibles
        accionesFrontend = $r.accionesFrontend
        pendientes       = $r.pendientes
        revisar          = $r.revisar
        soloWeb          = $r.soloWeb
        bloqueante       = [bool]$reporte.bloqueante
        motivosBloqueo   = @($reporte.motivosBloqueo)
        photinoCommit    = $reporte.photino.commit
        generado         = $reporte.generado
        reporte          = "contract/contract-report.md"
    }
    # Listas por estado (solo nombres de acciones: sin secretos) para /version.
    $acciones = [ordered]@{}
    foreach ($e in @("COMPATIBLE", "PENDIENTE", "REVISAR", "SOLO_WEB")) {
        $acciones[$e] = @($reporte.acciones | Where-Object { $_.estado -eq $e } | ForEach-Object { $_.accion } | Sort-Object)
    }
}

$bloqueado = $contrato -and $contrato.bloqueante

# --- 5. Promover o dejar el candidato --------------------------------------------------------
if ($SoloVerificar -or $bloqueado) {
    if (Test-Path $candidato) { Remove-Item -LiteralPath $candidato -Recurse -Force }
    New-Item -ItemType Directory -Path $candidato | Out-Null
    Move-Item -LiteralPath $extracted -Destination (Join-Path $candidato "photino-www")
    if (Test-Path $tmpContract) { Move-Item -LiteralPath $tmpContract -Destination (Join-Path $candidato "contract") }
    Remove-Item -LiteralPath $tmpDir -Recurse -Force
    $vigente = $null
    $manifestPath = Join-Path $outDir "web-manifest.json"
    if (Test-Path $manifestPath) {
        $vigente = [System.IO.File]::ReadAllText($manifestPath, [System.Text.Encoding]::UTF8) | ConvertFrom-Json
    }
    Write-Host "Snapshot vigente SIN CAMBIOS: $(if ($vigente) { "Photino $($vigente.photinoVersion) @ $($vigente.photinoCommit.Substring(0,7))" } else { '(no hay)' })"
    Write-Host "Candidato evaluado: Photino $photinoVersion @ $($sha.Substring(0,7)) -> $candidato"
    if ($contrato) { Write-Host "Contrato del candidato: $($contrato.texto) [$($contrato.estado)]" }
    if ($bloqueado) {
        Write-Host "RELEASE WEB BLOQUEADO para este commit (Photino no se ve afectado):" -ForegroundColor Red
        $contrato.motivosBloqueo | ForEach-Object { Write-Host "  - $_" -ForegroundColor Red }
        exit 2
    }
    exit 0
}

if (Test-Path $wwwOut) { Remove-Item -LiteralPath $wwwOut -Recurse -Force }
Move-Item -LiteralPath $extracted -Destination $wwwOut
if (Test-Path $contractOut) { Remove-Item -LiteralPath $contractOut -Recurse -Force }
if (Test-Path $tmpContract) { Move-Item -LiteralPath $tmpContract -Destination $contractOut }
Remove-Item -LiteralPath $tmpDir -Recurse -Force
if (Test-Path $candidato) { Remove-Item -LiteralPath $candidato -Recurse -Force }

$manifest = [ordered]@{
    producto          = $producto
    gatewayVersion    = $gatewayVersion
    photinoVersion    = $photinoVersion
    photinoCommit     = $sha
    photinoCommitDate = $commitDate
    # Último commit Photino completamente validado que sirve esta web (contrato evaluado y sin bloqueos).
    photinoValidado   = if ($contrato) { [ordered]@{ version = $photinoVersion; commit = $sha; commitFecha = $commitDate } } else { $null }
    wwwSha256         = $wwwSha256
    shimSha256        = $shimSha256
    webDistSha256     = $webDistSha256
    syncedAt          = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
    webRepoCommit     = $webCommit
    webRepoDirty      = $webDirty
    contrato          = $contrato
    acciones          = $acciones
}
$json = $manifest | ConvertTo-Json -Depth 6
[System.IO.File]::WriteAllText((Join-Path $outDir "web-manifest.json"), $json, (New-Object System.Text.UTF8Encoding($false)))

Write-Host "Snapshot listo: $wwwOut"
Write-Host "Manifest:      $(Join-Path $outDir 'web-manifest.json')"
Write-Host "wwwSha256 (original Photino): $wwwSha256"
Write-Host "webDistSha256 (snapshot final): $webDistSha256"
if ($contrato) {
    Write-Host "Contrato:      $($contrato.texto) [$($contrato.estado)] -> $(Join-Path $contractOut 'contract-report.md')"
}
