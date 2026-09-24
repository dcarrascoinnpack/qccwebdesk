# Quality Control Center Web

Migración web **en paralelo** del Quality Control Center de TI Faret (hoy Photino .NET 8, en
`C:\Users\dcarrasco\Desktop\Proyectos\qualitycontrol_desktop_faret`). Photino sigue siendo el
sistema principal y productivo — esta versión web no lo reemplaza ni lo modifica.

> **Estado (24-09-2026):** en desarrollo activo, sin deploy todavía. Arquitectura base + 4 módulos
> de solo lectura funcionando de punta a punta contra una API INNPACK simulada. Ver
> [`contex.md`](contex.md) (no versionado) para el detalle completo sesión a sesión.

## Arquitectura (vigente desde el 23-09-2026 — reemplaza el diseño original)

El diseño original de este repo (React + JPA/MySQL directo, ver commits `48815e2`..`58ab035`)
quedó **descartado**: producción migró a una API REST (`qualitycontrolinnpack`, SQL Server) y
duplicar la lógica de negocio en Java habría creado una segunda fuente de verdad. El diseño actual:

```
Navegador ──HTTPS──> Spring Boot "gateway" (cl.faret.qccweb)
                        │
                        ├─ sirve el MISMO frontend de Photino (src/UI/www, snapshot de un commit)
                        │  + web/web-bridge.js (shim inyectado solo en la build web)
                        │
                        ├─ POST /api/v1/bridge   → ActionPolicy deny-by-default → misma API que Photino
                        ├─ /api/v1/auth/*        → login delegado a la API INNPACK, sesión server-side
                        └─ GET /version          → versión Photino + compatibilidad del contrato
```

- **Frontend:** no se reescribe. `tools/sync-photino-www.ps1` copia `src/UI/www` de Photino desde
  un **commit** (nunca el working tree) y le inyecta una sola línea (`web/web-bridge.js`). Así las
  vistas, CSS y controllers son exactamente los mismos que en el desktop.
- **`web-bridge.js`** (`web-shim/`) reemplaza `window.PhotinoBridge.send` por `fetch`, preservando
  el contrato `{ok, success, data, error}`. Algunas acciones (`excel.guardar`) se resuelven **100%
  en el navegador** (descarga con Blob), sin tocar el servidor.
- **Backend `cl.faret.qccweb`** (paquete nuevo, aislado del legacy `cl.faret.qcc`): es un gateway
  sin base de datos. Nunca usa JPA/MySQL como fuente de negocio — llama a las mismas APIs que ya
  usa Photino (`InnpackApiClient`, sin estado, JWT por sesión).
- **`ActionPolicy`** es una lista explícita y **deny-by-default**: solo las acciones registradas
  ahí se ejecutan; todo lo demás responde 403 "Acción no disponible en la versión web."
- **Contract check** (`tools/contract/photino_contract.py`, herramienta de build en Python, no
  forma parte del runtime): compara qué acciones usa el frontend de Photino contra las que maneja
  la web, con una **huella** (hash) de la lógica C# real — si Photino cambia esa lógica, la acción
  pasa a `REVISAR` aunque el nombre no cambie. Bloquea el release **web** (nunca el de Photino).

El backend legacy (`cl.faret.qcc`, JPA + MySQL directo) **se conserva sin borrar** como referencia
histórica, pero no se usa ni se sigue desarrollando.

## Estructura

```
backend/        Spring Boot. cl.faret.qccweb = gateway (activo). cl.faret.qcc = legacy (congelado).
frontend/       React + Vite — solo login/placeholder, congelado (no es la UI activa; ver arriba).
web-shim/       web-bridge.js (el shim) + su test (Node, node:test).
tools/
  sync-photino-www.ps1     snapshot de Photino + shim + contract check → web-dist/
  contract/photino_contract.py + test_photino_contract.py   (Python, solo build)
contract/
  baseline.json   huellas de C# ya validadas por acción (versionado)
web-dist/       GENERADO por sync-photino-www.ps1 (gitignored): photino-www/, web-manifest.json, contract/
docs/           architecture.md (histórico — desactualizado respecto a esta sección, ver arriba)
```

## Requisitos

- Java 17, Maven (`backend/mvnw.cmd` incluido).
- Python 3.10+ (solo para el contract check — herramienta de build, nunca en producción).
- Node.js (solo para probar `web-shim/web-bridge.test.mjs`).
- Acceso de red a `https://api.faret.cl/innpack` (o una API INNPACK simulada para desarrollo).

## Cómo levantar el gateway localmente

```powershell
# 1. Snapshot del frontend Photino (de un commit) + contract check
powershell -ExecutionPolicy Bypass -File tools\sync-photino-www.ps1 -Commit <sha-de-photino>
#   -SinContrato   para saltarse el contract check en desarrollo (no usar antes de un release)
#   -Python <ruta> si "python" no está en el PATH

# 2. Empaquetar y ejecutar (arranca el GATEWAY, no la app legacy)
cd backend
.\mvnw.cmd -DskipTests package
$env:QCC_INNPACK_API_BASE_URL = "https://api.faret.cl/innpack"   # o tu API simulada
java -jar target\qcc-api-0.0.1-SNAPSHOT.jar
# → http://localhost:8086
```

Variables de entorno relevantes (todas con default seguro, ver `backend/src/main/resources/gateway.yaml`):

| Variable | Para qué | Default |
|---|---|---|
| `QCC_INNPACK_API_BASE_URL` | API INNPACK real (misma que usa Photino) | `https://api.faret.cl/innpack` |
| `QCC_WEB_PORT` | Puerto del gateway | `8086` |
| `QCC_WEB_WWW_DIR` / `QCC_WEB_MANIFEST_FILE` | Ruta al snapshot generado | `../web-dist/...` |
| `QCC_WEB_COOKIE_SECURE` | `Secure` en cookies (poner `false` solo en `http://localhost` sin TLS) | `true` |
| `QCC_WEB_SESSION_IDLE` / `QCC_WEB_SESSION_MAX` | Expiración de sesión | `30m` / `8h` |
| `QCC_WEB_TRUSTED_PROXIES` | IPs del reverse proxy (IIS) para confiar en `X-Forwarded-For` | vacío |
| `QCC_WEB_CONTRACT_BLOQUEAR` | Si `false`, arranca aunque el contrato esté BLOQUEANTE (solo dev) | `true` |
| `QCC_WEB_API_MAX_BODY` | Límite de tamaño de `/api/**` en bytes | `262144` (256 KB) |

Para arrancar el **backend legacy** (JPA/MySQL, solo referencia histórica):
`mvnw.cmd spring-boot:run -Dspring-boot.run.main-class=cl.faret.qcc.QccApiApplication` (requiere
`QCC_DB_*`, ver `.env.example`).

## Tests

```bash
cd backend && .\mvnw.cmd test                              # Java (JUnit) — gateway + legacy
python -m unittest discover -s tools/contract               # Python — analizador del contrato
node --test web-shim/web-bridge.test.mjs                    # Node — web-bridge.js
```

Los tests del gateway usan una **API INNPACK simulada** (`FakeInnpackApi`, HttpServer del JDK
dentro de los tests) — nunca credenciales ni endpoints reales.

## Seguridad (resumen — ver `contex.md` para el detalle completo)

- **Mismas credenciales que Photino**, sin segunda base de usuarios: el login delega en la API
  INNPACK real (`POST api/auth/login`); la contraseña vive solo durante esa llamada, nunca se
  persiste ni se loguea.
- **Sesión 100% server-side**: cookie `QCC_SESSION` `HttpOnly + Secure + SameSite=Strict`; el JWT
  de la API nunca llega al navegador. Nueva sesión en cada login (protección contra session
  fixation). Expira por inactividad y por el `exp` del JWT upstream.
- **CSRF** (patrón SPA de Spring Security, cookie `XSRF-TOKEN` + header `X-XSRF-TOKEN`).
- **Rate limiting** de login en memoria (backoff por usuario+IP + límite por IP) y mensaje
  genérico anti-enumeración de usuarios.
- **Identidad nunca confiada del navegador**: `usuarioId`/`rol`/`empresa`/etc. que llegan en el
  payload se sobrescriben server-side (`IdentityOverride`) según lo que declare cada acción.
- **`localStorage` nunca guarda contraseñas, roles ni tokens** — solo el código de usuario
  ("recordar usuario" es solo eso).
- **Límite de tamaño** en `POST /api/**` (413/411) antes de llegar a Spring Security.
- Vulnerabilidades encontradas en **Photino/las APIs existentes** (no de este repo) están
  registradas, sin corregir todavía, en
  `..\qualitycontrol_desktop_faret\docs\SECURITY_HARDENING_BACKLOG.md` (rama `fase-1a-gateway` de
  ese repo — no está en su `main`).

## Progreso por fases (detalle completo en `contex.md`)

| Fase | Qué | Estado |
|---|---|---|
| 1a | Gateway + frontend Photino compartido + `/version` | ✅ commit `6e3bc13` |
| 1b | Login INNPACK delegado + sesión segura + rate limit + auditoría | ✅ commit `489cff8` |
| 1c | `POST /api/v1/bridge` + `ActionPolicy` + `inicio.getDashboard` | ✅ commit `ceb2b5b` |
| 1d | Contract check automático (`tools/contract/`) + límite de tamaño | ✅ commit `55414df` |
| 2a | Módulo **Máquinas y Procesos** (`maquinasSeguimiento.obtenerResumen`) | ✅ commit `01b2dd3` |
| 2b | `excel.guardar` resuelto en el navegador (sin tocar el servidor) | ✅ commit `accc142` |
| 2c | Módulo **Inspecciones Calidad**, solo lectura (`dashboard.obtenerFiltros`/`obtenerResumen`) | ✅ commit `d84cc0a` |
| 2d | Módulo **Inspecciones Producción**, solo lectura (`registrosProduccion.obtenerFiltros`/`obtenerResumen`) | 🟡 implementado y probado, **pendiente de aprobación/commit** |

**Compatibilidad actual con Photino** (`GET /version` → `compatibilidad.texto`): con lo ya
commiteado, **Photino 1.8.12 · Web compatible 7/236**; con el trabajo de la Fase 2d aplicado (sin
commitear todavía), **9/236**.

## Próximo paso sugerido

Revisar si conviene sumar `registros-control` (filtros + exportación "traer todo sin límite",
patrón ya conocido) en solo lectura. Después, una fase dedicada a las primeras escrituras
(validar/rechazar/eliminar en Dashboard, Producción y Máquinas), que requiere definir la matriz de
roles permitidos — hoy la API INNPACK no restringe por rol en esos módulos.

## Reglas de trabajo

Modo seguro: un paso a la vez, plan antes de implementar, aprobación explícita antes de cada
cambio, resumen después. Ver `CLAUDE.md`/`AGENTS.md`.
