<#
.SYNOPSIS
    Gate + artefacto de release de QCC Web para un commit explícito de Photino.

.DESCRIPTION
    Un único comando repetible. Solo LEE el repo Photino (vía git) y solo escribe en web-dist/ y
    backend/target/. Nunca hace deploy. Web nunca bloquea a Photino: si el gate falla, el snapshot
    vigente queda como estaba (lo garantiza sync-photino-www.ps1) y Photino sigue su propio release.

    Gate (cualquier fallo => exit != 0 y NO se arma artefacto):
      1. Repo web limpio (el artefacto declara un commit web exacto, sin cambios locales).
      2. Estado del repo Photino registrado (HEAD + status) para comprobar al final que quedó intacto.
      3. Tests Java del GATEWAY (cl.faret.qccweb.**). Excepción formal: el backend legacy congelado
         cl.faret.qcc (QccApiApplicationTests.contextLoads necesita MySQL) NO es parte del gateway ni
         del artefacto y no se ejecuta en el gate.
      4. Tests Python del contract check.
      5. Tests Node del shim.
      6. sync-photino-www.ps1 -Commit <c>: contract check sin REVISAR/SOLO_WEB (si no, no promueve).
      7. mvnw package (jar del gateway con build-info).
      8. Manifest consistente: commit Photino pedido, shim actual, webRepoCommit = HEAD, repo limpio,
         contrato no bloqueante, 0 REVISAR, 0 SOLO_WEB.
      9. Sin secretos en el snapshot, manifest ni reporte (patrones de credenciales/JWT/llaves).
     10. Photino intacto (mismo HEAD y mismo status que al inicio).

    Artefacto: web-dist/release/qcc-web_<photinoVersion>_<photino7>_<web7>/ con el jar, photino-www/,
    web-manifest.json, contract/, release.json (resultado del gate) y SHA256SUMS.txt.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File tools\release-web.ps1 -Commit 6c42e05
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Commit,
    [string]$PhotinoRepo,
    [string]$Python = "python",
    [string]$Node = "node"
)

$ErrorActionPreference = "Stop"
$webRepo = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
if (-not $PhotinoRepo) { $PhotinoRepo = Join-Path (Split-Path $webRepo -Parent) "qualitycontrol_desktop_faret" }
$PhotinoRepo = (Resolve-Path $PhotinoRepo).Path
$backend = Join-Path $webRepo "backend"
$outDir = Join-Path $webRepo "web-dist"
$pasos = [System.Collections.Generic.List[object]]::new()

function Paso([string]$nombre, [scriptblock]$accion) {
    Write-Host "== $nombre" -ForegroundColor Cyan
    $ini = Get-Date
    try {
        $detalle = & $accion
        $pasos.Add([ordered]@{ paso = $nombre; ok = $true; detalle = "$detalle"; segundos = [int]((Get-Date) - $ini).TotalSeconds })
    } catch {
        $pasos.Add([ordered]@{ paso = $nombre; ok = $false; detalle = "$($_.Exception.Message)" })
        Write-Host "GATE FALLIDO en '$nombre': $($_.Exception.Message)" -ForegroundColor Red
        Write-Host "No se genera artefacto. Photino no se ve afectado." -ForegroundColor Red
        exit 1
    }
}

function EstadoPhotino {
    $head = (& git -C $PhotinoRepo rev-parse HEAD).Trim()
    $status = (& git -C $PhotinoRepo status --porcelain) -join "`n"
    return "$head|$status"
}

Paso "repo web limpio" {
    $sucio = & git -C $webRepo status --porcelain
    if ($sucio) { throw "hay cambios sin commitear en el repo web:`n$($sucio -join "`n")" }
    (& git -C $webRepo rev-parse HEAD).Trim()
}
$webCommit = (& git -C $webRepo rev-parse HEAD).Trim()
$photinoAntes = EstadoPhotino
$shaPhotino = (& git -C $PhotinoRepo rev-parse --verify "$Commit^{commit}").Trim()
if ($LASTEXITCODE -ne 0) { throw "Commit Photino '$Commit' no existe" }

Paso "tests Java del gateway (cl.faret.qccweb)" {
    Push-Location $backend
    try {
        $reportes = Join-Path $backend "target\surefire-reports"
        if (Test-Path $reportes) { Remove-Item $reportes -Recurse -Force }
        & .\mvnw.cmd -q test "-Dtest=cl/faret/qccweb/**/*Test" "-Dsurefire.failIfNoSpecifiedTests=false" | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "mvnw test fallo (exit $LASTEXITCODE); ver backend/target/surefire-reports" }
        $total = 0
        Get-ChildItem $reportes -Filter "*.txt" | ForEach-Object {
            $m = Select-String -Path $_.FullName -Pattern "Tests run: (\d+), Failures: (\d+), Errors: (\d+)" | Select-Object -First 1
            $total += [int]$m.Matches[0].Groups[1].Value
        }
        if ($total -eq 0) { throw "no se ejecuto ningun test del gateway" }
        "$total tests OK"
    } finally { Pop-Location }
}

Paso "tests Python (contract check)" {
    $salida = & $Python -m unittest discover -s (Join-Path $webRepo "tools\contract") 2>&1
    if ($LASTEXITCODE -ne 0) { throw "unittest fallo:`n$($salida -join "`n")" }
    ($salida | Select-String "^Ran \d+ tests").Line
}

Paso "tests Node (shim)" {
    $salida = & $Node --test (Join-Path $webRepo "web-shim\web-bridge.test.mjs") 2>&1
    if ($LASTEXITCODE -ne 0) { throw "node --test fallo:`n$($salida -join "`n")" }
    (($salida | Select-String "(pass|fail) \d+\s*$").Line | ForEach-Object { $_.Trim() }) -join " "
}

Paso "sync frontend + contract check (Photino $Commit)" {
    & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "sync-photino-www.ps1") -Commit $shaPhotino -PhotinoRepo $PhotinoRepo -Python $Python | Out-Host
    if ($LASTEXITCODE -eq 2) { throw "contract check BLOQUEANTE (REVISAR/SOLO_WEB): ver web-dist/_candidato/contract/contract-report.md" }
    if ($LASTEXITCODE -ne 0) { throw "sync fallo (exit $LASTEXITCODE)" }
    "promovido"
}

Paso "empaquetar gateway" {
    Push-Location $backend
    try {
        & .\mvnw.cmd -q -DskipTests package | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "mvnw package fallo (exit $LASTEXITCODE)" }
    } finally { Pop-Location }
    $jar = Get-ChildItem (Join-Path $backend "target") -Filter "*.jar" | Where-Object { $_.Name -notlike "*.original" } | Select-Object -First 1
    if (-not $jar) { throw "no se encontro el jar" }
    $jar.Name
}

$manifest = [System.IO.File]::ReadAllText((Join-Path $outDir "web-manifest.json"), [System.Text.Encoding]::UTF8) | ConvertFrom-Json

Paso "manifest consistente" {
    $shim = (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $webRepo "web-shim\web-bridge.js")).Hash.ToLowerInvariant()
    $errores = @()
    if ($manifest.photinoCommit -ne $shaPhotino) { $errores += "photinoCommit $($manifest.photinoCommit) != $shaPhotino" }
    if ($manifest.shimSha256 -ne $shim) { $errores += "shimSha256 no coincide con web-shim/web-bridge.js" }
    if ($manifest.webRepoCommit -ne $webCommit) { $errores += "webRepoCommit $($manifest.webRepoCommit) != HEAD $webCommit" }
    if ($manifest.webRepoDirty) { $errores += "webRepoDirty=true" }
    if (-not $manifest.contrato) { $errores += "sin contrato evaluado" }
    elseif ($manifest.contrato.bloqueante -or $manifest.contrato.revisar -ne 0 -or $manifest.contrato.soloWeb -ne 0) { $errores += "contrato con REVISAR/SOLO_WEB" }
    if (-not $manifest.photinoValidado -or $manifest.photinoValidado.commit -ne $shaPhotino) { $errores += "photinoValidado no corresponde al commit" }
    if ($errores) { throw ($errores -join "; ") }
    $manifest.contrato.texto
}

Paso "sin secretos en el artefacto" {
    $patron = '(?i)(password|passwd|pwd|secret|api[_-]?key|token)\s*[:=]\s*["''][^"''\s]{6,}["'']|Server=[^;]+;.*(Pwd|Password)=|-----BEGIN [A-Z ]*PRIVATE KEY-----|eyJ[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{15,}|AKIA[0-9A-Z]{16}'
    $archivos = Get-ChildItem (Join-Path $outDir "photino-www"), (Join-Path $outDir "contract") -Recurse -File -Include *.js, *.html, *.json, *.md, *.css |
        Where-Object { $_.FullName -notmatch '\\libs\\' }
    $archivos += Get-Item (Join-Path $outDir "web-manifest.json")
    $hallazgos = @($archivos | Select-String -Pattern $patron | ForEach-Object { "$($_.Path):$($_.LineNumber)" })
    $prohibidos = @(Get-ChildItem (Join-Path $outDir "photino-www") -Recurse -File -Include "config*.json", "*.env", "appsettings*.json", "*.pfx", "*.key")
    if ($hallazgos -or $prohibidos) { throw "posibles secretos: $($hallazgos + ($prohibidos | ForEach-Object FullName) -join ', ')" }
    "$($archivos.Count) archivos revisados"
}

Paso "Photino intacto" {
    if ((EstadoPhotino) -ne $photinoAntes) { throw "el repo Photino cambio durante el release (HEAD o status)" }
    "HEAD y status sin cambios"
}

# --- Artefacto -------------------------------------------------------------------------------
$nombre = "qcc-web_$($manifest.photinoVersion)_$($shaPhotino.Substring(0,7))_$($webCommit.Substring(0,7))"
$dest = Join-Path $outDir "release\$nombre"
if (Test-Path $dest) { Remove-Item $dest -Recurse -Force }
New-Item -ItemType Directory -Path $dest | Out-Null
$jar = Get-ChildItem (Join-Path $backend "target") -Filter "*.jar" | Where-Object { $_.Name -notlike "*.original" } | Select-Object -First 1
Copy-Item $jar.FullName (Join-Path $dest "qcc-web-gateway.jar")
Copy-Item (Join-Path $outDir "photino-www") (Join-Path $dest "photino-www") -Recurse
Copy-Item (Join-Path $outDir "contract") (Join-Path $dest "contract") -Recurse
Copy-Item (Join-Path $outDir "web-manifest.json") $dest

$release = [ordered]@{
    nombre           = $nombre
    generado         = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
    photinoVersion   = $manifest.photinoVersion
    photinoCommit    = $shaPhotino
    photinoValidado  = $manifest.photinoValidado
    webRepoCommit    = $webCommit
    webDistSha256    = $manifest.webDistSha256
    compatibilidad   = $manifest.contrato.texto
    gate             = $pasos
    exclusionesGate  = @("cl.faret.qcc (backend legacy congelado, necesita MySQL: QccApiApplicationTests.contextLoads). No forma parte del gateway ni del artefacto.")
}
[System.IO.File]::WriteAllText((Join-Path $dest "release.json"), ($release | ConvertTo-Json -Depth 6), (New-Object System.Text.UTF8Encoding($false)))

$destFull = (Resolve-Path $dest).Path
$sumas = Get-ChildItem $destFull -Recurse -File | Where-Object Name -ne "SHA256SUMS.txt" | ForEach-Object {
    "$((Get-FileHash -Algorithm SHA256 -LiteralPath $_.FullName).Hash.ToLowerInvariant())  $($_.FullName.Substring($destFull.Length + 1).Replace('\', '/'))"
} | Sort-Object { $_.Substring(66) } -CaseSensitive
[System.IO.File]::WriteAllText((Join-Path $destFull "SHA256SUMS.txt"), (($sumas -join "`n") + "`n"), (New-Object System.Text.UTF8Encoding($false)))

Write-Host "RELEASE WEB LISTO (sin deploy): $destFull" -ForegroundColor Green
Write-Host "  $($manifest.contrato.texto) | Photino validado $($manifest.photinoValidado.version) @ $($shaPhotino.Substring(0,7)) | web $($webCommit.Substring(0,7))"
