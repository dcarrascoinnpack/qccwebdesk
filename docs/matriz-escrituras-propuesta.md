# Matriz de autorización de escrituras — QCC Web

**Estado del documento:** política técnica de la web aprobada por el usuario el 2026-09-25; **regla de paridad fijada
el 2026-09-28 (Fase 3o)**.

> **Regla arquitectónica:** Photino Desktop y Web son dos versiones paralelas del MISMO sistema; Web NO reemplaza a
> Photino. Misma lógica funcional, reglas de negocio, estados, operaciones disponibles, flujos y comportamiento
> observable. Web puede tener seguridad técnica más fuerte SIEMPRE que sea transparente para el usuario y no cambie una
> regla funcional de Photino (identidad desde `SessionUser`, JWT solo server-side, CSRF, lista blanca, tipos/largos
> reales, protección XSS, auditoría, aislamiento de sesiones, validación de archivos, detección de concurrencia que
> evita pérdidas accidentales sin alterar el flujo normal). **Gestión de usuarios** es la excepción explícita.
>
> **Roles:** mientras el negocio no apruebe una matriz definitiva, `ROL_ACTUAL_WEB` = `ROL_ACTUAL_PHOTINO` en toda
> acción habilitada. `ROL_PROPUESTO_NEGOCIO` es la política FUTURA del sistema completo (Photino/API + Web, aplicada de
> forma coordinada), no una restricción de la web. `ESTADO_VALIDACION_NEGOCIO` = `PENDIENTE_VALIDACION_NEGOCIO` en todas.

Escrituras habilitadas en la web: **21** (`recepcion.bobinas.muestrear`, `noConformidades.create`, `noConformidades.update`, `noConformidades.eliminar`,
`noConformidades.adjuntos.eliminar`, `noConformidades.gestion.actualizar`,
`noConformidades.cerrar`, `noConformidades.acciones.actualizar`, `noConformidades.adjuntos.subir`, `noConformidades.seguimiento.crear`,
`noConformidades.acciones.crear`, `noConformidades.analisis.guardar` y `noConformidades.catalogos.{clientes,
categoriasDefecto,tiposFalla,supervisores,revisores,areas,familiasProducto,impactos,niveles}.crear`, estado VALIDADA;
mismos roles que Photino). Las otras 60 siguen denegadas por `ActionPolicy`
(deny-by-default) hasta su propia fase.

- Evidencia: Photino `6c42e05` (v1.8.12; referencia web actualizada a `dd147ad` en la Fase 3r) — `MessageRouter`, handlers, `InnpackApi/*ApiService`, controllers JS;
  API INNPACK `qualitycontrolinnpack_sqlserver_port` (atributos `[Authorize]`, servicios). Extraída con el
  inventario del contract check y revisada a mano en los casos dudosos.
- Generada el 2026-09-25. Toda modificación posterior se hace editando este archivo (y el estado de cada fila).

## Principios de la política Web

Photino y la API hoy: cualquier sesión INNPACK puede ejecutar cualquier escritura salvo `usuarios.*` (backlog Photino
SEC-11), la identidad del autor llega del cliente (SEC-12) y varias operaciones no registran autor (SEC-26 propuesto).
La web **reproduce la disponibilidad funcional de Photino** y agrega seguridad transparente, por acción:

1. **Roles** — `ROL_ACTUAL_WEB` = `ROL_ACTUAL_PHOTINO` (Fase 3o). La propuesta más restrictiva que sigue queda en
   `ROL_PROPUESTO_NEGOCIO` para evaluarla en TODO el sistema cuando el negocio la apruebe:
   - `operador, admin, admin_ti` — registro operativo **aditivo** (crear, registrar ensayos, comentar, adjuntar).
   - `admin, admin_ti` — decisiones de supervisión (validar/rechazar, cerrar, cambiar estado), correcciones de
     fecha, maestros/configuración, anulaciones y **todo borrado**.
   - `admin` — operaciones **masivas** (`validarTodo`/`rechazarTodo`), además con confirmación explícita.
   Los roles propuestos requieren **confirmación del negocio** antes de pasar a APROBADA.
2. **Identidad server-side**: todo campo de autor que viaje en el payload se **sobrescribe** con `SessionUser`
   (`IdentityOverride`, raíz y `data`); lo que Photino toma de su sesión C# lo **inyecta** el gateway. El navegador
   nunca decide actor, empresa ni rol. Campos como `responsable`/`supervisor` son datos de negocio: no se tocan.
3. **Empresa de sesión** donde la API filtra por empresa (`recepcion.*`, `productoTerminado.*`).
4. **Payload con lista blanca** cuando Photino reenvía el objeto completo (`BuildBody`/`GetRawText`).
5. **Auditoría del gateway obligatoria** en toda escritura (`qcc.audit`: usuario, empresa, acción, id afectado,
   resultado, duración; nunca contraseñas, tokens ni contenido de archivos). Es el único registro de autor donde la
   API no lo guarda.
6. **Nunca más permisivo que Photino/API ni más restrictivo en lo funcional** (paridad de experiencia); solo más
   estricto en seguridad transparente. Web nunca bloquea a Photino.

**Estados:** `PENDIENTE` (propuesta) → `APROBADA` (política técnica aprobada; roles sujetos a
`PENDIENTE_VALIDACION_NEGOCIO`) → `IMPLEMENTADA` (regla + handler + tests en el gateway) → `VALIDADA` (E2E +
contract check C#/JS + gate de release).

## Resumen

| | Cantidad |
|---|---|
| Escrituras INNPACK pendientes | **81** |
| Riesgo BAJO / MEDIO / ALTO | **33 / 29 / 19** (niveles.crear 3i, create 3j y adjuntos.subir 3k pasaron de BAJO a MEDIO) |
| `ROL_PROPUESTO_NEGOCIO` `operador, admin, admin_ti` / `admin, admin_ti` / `admin` / `admin_ti` | 38 / 38 / 4 / 1 (propuesta; `ROL_ACTUAL_WEB` = Photino) |
| Sin autor registrado por la API (solo auditoría gateway) | 29 |
| Autor tomado del cliente (a sobrescribir) | 25 |
| Estado | 60 APROBADA · 21 VALIDADA — roles `PENDIENTE_VALIDACION_NEGOCIO` |

## Matriz por módulo

Columnas: método/endpoint (API INNPACK) · empresa · `ROL_ACTUAL_PHOTINO` · `ROL_ACTUAL_WEB` · `ROL_PROPUESTO_NEGOCIO`
(política futura del sistema completo) · `ESTADO_VALIDACION_NEGOCIO` · autorización de la API · identidad que hoy
llega del cliente · qué fija el gateway desde `SessionUser` · efecto · rollback · riesgo · justificación · estado.

### controlDocumental (5)

| action | método · endpoint | empresa | ROL_ACTUAL_PHOTINO | ROL_ACTUAL_WEB | ROL_PROPUESTO_NEGOCIO | ESTADO_VALIDACION_NEGOCIO | API hoy | identidad desde cliente | Web fija desde SessionUser | efecto | rollback | riesgo | justificación | estado |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `controlDocumental.adjunto.subir` | POST `api/control-documental/adjunto/{versionId}` | INNPACK; `alcanceEmpresa` = dato de negocio (validar valores) | cualquier sesión INNPACK (sin gating de rol) | — (deshabilitada) | **admin, admin_ti** | PENDIENTE_VALIDACION_NEGOCIO | `[Authorize]` (cualquier JWT) | cliente: `subidoPor` | `subidoPor` ← nombreCompleto | adjunta archivo a una versión (≤ 25 MB) | sí: reemplazar/nueva versión | **MEDIO** | cambia el documento vigente que ven todos; validar tipo/tamaño en gateway | APROBADA |
| `controlDocumental.create` | POST `api/control-documental` | INNPACK; `alcanceEmpresa` = dato de negocio (validar valores) | cualquier sesión INNPACK (sin gating de rol) | — (deshabilitada) | **admin, admin_ti** | PENDIENTE_VALIDACION_NEGOCIO | `[Authorize]` (cualquier JWT) | cliente: `creadoPor` | `creadoPor` ← nombreCompleto; payload completo pasa a la API → lista blanca de campos | crea documento/versión controlada | sí: nueva versión o eliminar (admin) | **MEDIO** | documentos del sistema de calidad: solo quien administra el control documental | APROBADA |
| `controlDocumental.update` | PUT `api/control-documental/{id}` | INNPACK; `alcanceEmpresa` = dato de negocio (validar valores) | cualquier sesión INNPACK (sin gating de rol) | — (deshabilitada) | **admin, admin_ti** | PENDIENTE_VALIDACION_NEGOCIO | `[Authorize]` (cualquier JWT) | cliente: `actualizadoPor` | `actualizadoPor` ← nombreCompleto; payload completo pasa a la API → lista blanca de campos | edita metadatos del documento | sí: volver a editar | **MEDIO** | documento controlado | APROBADA |
| `controlDocumental.version.crear` | POST `api/control-documental/{documentoId}/version` | INNPACK; `alcanceEmpresa` = dato de negocio (validar valores) | cualquier sesión INNPACK (sin gating de rol) | — (deshabilitada) | **admin, admin_ti** | PENDIENTE_VALIDACION_NEGOCIO | `[Authorize]` (cualquier JWT) | cliente: `creadoPor` | `creadoPor` ← nombreCompleto | crea documento/versión controlada | sí: nueva versión o eliminar (admin) | **MEDIO** | documentos del sistema de calidad: solo quien administra el control documental | APROBADA |
| `controlDocumental.eliminar` | DELETE `api/control-documental/{id}` | INNPACK; `alcanceEmpresa` = dato de negocio (validar valores) | cualquier sesión INNPACK (sin gating de rol) | — (deshabilitada) | **admin, admin_ti** | PENDIENTE_VALIDACION_NEGOCIO | `[Authorize]` (cualquier JWT) | cliente: `actualizadoPor` | `actualizadoPor` ← nombreCompleto | elimina el documento (lógico, por verificar) | no desde la UI | **ALTO** | destructivo sobre registro controlado | APROBADA |

### dashboard (5)

| action | método · endpoint | empresa | ROL_ACTUAL_PHOTINO | ROL_ACTUAL_WEB | ROL_PROPUESTO_NEGOCIO | ESTADO_VALIDACION_NEGOCIO | API hoy | identidad desde cliente | Web fija desde SessionUser | efecto | rollback | riesgo | justificación | estado |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `dashboard.rechazarRegistro` | PUT `api/dashboard/{id}/rechazar` | INNPACK (sin parámetro) | cualquier sesión INNPACK (sin gating de rol) | — (deshabilitada) | **admin, admin_ti** | PENDIENTE_VALIDACION_NEGOCIO | `[Authorize]` (cualquier JWT) | ninguna (la API no registra autor) | — (auditoría del gateway obligatoria) | cambia estado de una inspección de calidad | re-operando; sin historial de autor | **MEDIO** | decisión de supervisión; la API no guarda quién → auditoría obligatoria en gateway (SEC-26) | APROBADA |
| `dashboard.validarRegistro` | PUT `api/dashboard/{id}/validar` | INNPACK (sin parámetro) | cualquier sesión INNPACK (sin gating de rol) | — (deshabilitada) | **admin, admin_ti** | PENDIENTE_VALIDACION_NEGOCIO | `[Authorize]` (cualquier JWT) | ninguna (la API no registra autor) | — (auditoría del gateway obligatoria) | cambia estado de una inspección de calidad | re-operando; sin historial de autor | **MEDIO** | decisión de supervisión; la API no guarda quién → auditoría obligatoria en gateway (SEC-26) | APROBADA |
| `dashboard.eliminarRegistro` | DELETE `api/dashboard/{id}` | INNPACK (sin parámetro) | cualquier sesión INNPACK (sin gating de rol) | — (deshabilitada) | **admin, admin_ti** | PENDIENTE_VALIDACION_NEGOCIO | `[Authorize]` (cualquier JWT) | ninguna (la API no registra autor) | — (auditoría del gateway obligatoria) | elimina una inspección | no desde la UI | **ALTO** | destructivo y sin autor registrado | APROBADA |
| `dashboard.rechazarTodo` | PUT `api/dashboard/rechazar-todo` | INNPACK (sin parámetro) | cualquier sesión INNPACK (sin gating de rol) | — (deshabilitada) | **admin** | PENDIENTE_VALIDACION_NEGOCIO | `[Authorize]` (cualquier JWT) | ninguna (la API no registra autor) | — (auditoría del gateway obligatoria) | valida/rechaza TODOS los registros pendientes filtrados | no (masivo) | **ALTO** | masivo e irreversible en la práctica; solo admin y con confirmación explícita | APROBADA |
| `dashboard.validarTodo` | PUT `api/dashboard/validar-todo` | INNPACK (sin parámetro) | cualquier sesión INNPACK (sin gating de rol) | — (deshabilitada) | **admin** | PENDIENTE_VALIDACION_NEGOCIO | `[Authorize]` (cualquier JWT) | ninguna (la API no registra autor) | — (auditoría del gateway obligatoria) | valida/rechaza TODOS los registros pendientes filtrados | no (masivo) | **ALTO** | masivo e irreversible en la práctica; solo admin y con confirmación explícita | APROBADA |

### inicio (1)

| action | método · endpoint | empresa | ROL_ACTUAL_PHOTINO | ROL_ACTUAL_WEB | ROL_PROPUESTO_NEGOCIO | ESTADO_VALIDACION_NEGOCIO | API hoy | identidad desde cliente | Web fija desde SessionUser | efecto | rollback | riesgo | justificación | estado |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `inicio.frecuencias.actualizar` | PUT `api/home/frecuencias/{id}` | INNPACK (sin parámetro) | cualquier sesión INNPACK (sin gating de rol) | — (deshabilitada) | **admin, admin_ti** | PENDIENTE_VALIDACION_NEGOCIO | `[Authorize]` (cualquier JWT) | ninguna (la API no registra autor) | — (auditoría del gateway obligatoria) | cambia la frecuencia objetivo de control por proceso | sí: volver a editar | **MEDIO** | configuración global; sin autor en API | APROBADA |

### muestraLab (25)

| action | método · endpoint | empresa | ROL_ACTUAL_PHOTINO | ROL_ACTUAL_WEB | ROL_PROPUESTO_NEGOCIO | ESTADO_VALIDACION_NEGOCIO | API hoy | identidad desde cliente | Web fija desde SessionUser | efecto | rollback | riesgo | justificación | estado |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `muestraLab.adjunto.subir` | POST `api/muestra-laboratorio/{muestraId}/adjunto` | INNPACK (sin parámetro) | cualquier sesión INNPACK (sin gating de rol) | — (deshabilitada) | **operador, admin, admin_ti** | PENDIENTE_VALIDACION_NEGOCIO | `[Authorize]` (cualquier JWT) | sesión C# de Photino: NombreCompleto | inyectar nombreCompleto (Photino lo toma de su sesión) | adjunta archivo/foto a la muestra (≤ 10 MB) | sí: eliminar adjunto (admin) | **BAJO** | evidencia aditiva | APROBADA |
| `muestraLab.bctMedido.guardar` | POST `api/muestra-laboratorio/bct-medido` | INNPACK (sin parámetro) | cualquier sesión INNPACK (sin gating de rol) | — (deshabilitada) | **operador, admin, admin_ti** | PENDIENTE_VALIDACION_NEGOCIO | `[Authorize]` (cualquier JWT) | sesión C# de Photino: Id, NombreCompleto | inyectar userId (Photino lo toma de su sesión); inyectar nombreCompleto (Photino lo toma de su sesión) | registra un ensayo sobre una muestra | sí: anular ensayo (admin) | **BAJO** | registro operativo; la evaluación la calcula la API | APROBADA |
| `muestraLab.bctTeorico.guardar` | POST `api/muestra-laboratorio/bct-teorico` | INNPACK (sin parámetro) | cualquier sesión INNPACK (sin gating de rol) | — (deshabilitada) | **operador, admin, admin_ti** | PENDIENTE_VALIDACION_NEGOCIO | `[Authorize]` (cualquier JWT) | sesión C# de Photino: Id, NombreCompleto | inyectar userId (Photino lo toma de su sesión); inyectar nombreCompleto (Photino lo toma de su sesión) | registra un ensayo sobre una muestra | sí: anular ensayo (admin) | **BAJO** | registro operativo; la evaluación la calcula la API | APROBADA |
| `muestraLab.cobb.guardar` | POST `api/muestra-laboratorio/cobb` | INNPACK (sin parámetro) | cualquier sesión INNPACK (sin gating de rol) | — (deshabilitada) | **operador, admin, admin_ti** | PENDIENTE_VALIDACION_NEGOCIO | `[Authorize]` (cualquier JWT) | sesión C# de Photino: Id, NombreCompleto | inyectar userId (Photino lo toma de su sesión); inyectar nombreCompleto (Photino lo toma de su sesión) | registra un ensayo sobre una muestra | sí: anular ensayo (admin) | **BAJO** | registro operativo; la evaluación la calcula la API | APROBADA |
| `muestraLab.crear` | POST `api/muestra-laboratorio` | INNPACK (sin parámetro) | cualquier sesión INNPACK (sin gating de rol) | — (deshabilitada) | **operador, admin, admin_ti** | PENDIENTE_VALIDACION_NEGOCIO | `[Authorize]` (cualquier JWT) | sesión C# de Photino: Id, NombreCompleto | inyectar userId (Photino lo toma de su sesión); inyectar nombreCompleto (Photino lo toma de su sesión) | crea una muestra de laboratorio | sí: anular/eliminar (admin) | **BAJO** | registro operativo del analista; autor desde sesión | APROBADA |
| `muestraLab.ect.guardar` | POST `api/muestra-laboratorio/ect` | INNPACK (sin parámetro) | cualquier sesión INNPACK (sin gating de rol) | — (deshabilitada) | **operador, admin, admin_ti** | PENDIENTE_VALIDACION_NEGOCIO | `[Authorize]` (cualquier JWT) | sesión C# de Photino: Id, NombreCompleto | inyectar userId (Photino lo toma de su sesión); inyectar nombreCompleto (Photino lo toma de su sesión) | registra un ensayo sobre una muestra | sí: anular ensayo (admin) | **BAJO** | registro operativo; la evaluación la calcula la API | APROBADA |
| `muestraLab.espesor.guardar` | POST `api/muestra-laboratorio/espesor` | INNPACK (sin parámetro) | cualquier sesión INNPACK (sin gating de rol) | — (deshabilitada) | **operador, admin, admin_ti** | PENDIENTE_VALIDACION_NEGOCIO | `[Authorize]` (cualquier JWT) | sesión C# de Photino: Id, NombreCompleto | inyectar userId (Photino lo toma de su sesión); inyectar nombreCompleto (Photino lo toma de su sesión) | registra un ensayo sobre una muestra | sí: anular ensayo (admin) | **BAJO** | registro operativo; la evaluación la calcula la API | APROBADA |
| `muestraLab.fct.guardar` | POST; POST `api/muestra-laboratorio/fct; api/muestra-laboratorio/rct` | INNPACK (sin parámetro) | cualquier sesión INNPACK (sin gating de rol) | — (deshabilitada) | **operador, admin, admin_ti** | PENDIENTE_VALIDACION_NEGOCIO | `[Authorize]` (cualquier JWT) | sesión C# de Photino: Id, NombreCompleto | inyectar userId (Photino lo toma de su sesión); inyectar nombreCompleto (Photino lo toma de su sesión) | registra un ensayo sobre una muestra | sí: anular ensayo (admin) | **BAJO** | registro operativo; la evaluación la calcula la API | APROBADA |
| `muestraLab.gramaje.guardar` | POST `api/muestra-laboratorio/gramaje` | INNPACK (sin parámetro) | cualquier sesión INNPACK (sin gating de rol) | — (deshabilitada) | **operador, admin, admin_ti** | PENDIENTE_VALIDACION_NEGOCIO | `[Authorize]` (cualquier JWT) | sesión C# de Photino: Id, NombreCompleto | inyectar userId (Photino lo toma de su sesión); inyectar nombreCompleto (Photino lo toma de su sesión) | registra un ensayo sobre una muestra | sí: anular ensayo (admin) | **BAJO** | registro operativo; la evaluación la calcula la API | APROBADA |
| `muestraLab.humedad.guardar` | POST `api/muestra-laboratorio/humedad` | INNPACK (sin parámetro) | cualquier sesión INNPACK (sin gating de rol) | — (deshabilitada) | **operador, admin, admin_ti** | PENDIENTE_VALIDACION_NEGOCIO | `[Authorize]` (cualquier JWT) | sesión C# de Photino: Id, NombreCompleto | inyectar userId (Photino lo toma de su sesión); inyectar nombreCompleto (Photino lo toma de su sesión) | registra un ensayo sobre una muestra | sí: anular ensayo (admin) | **BAJO** | registro operativo; la evaluación la calcula la API | APROBADA |
| `muestraLab.lugol.guardar` | POST `api/muestra-laboratorio/lugol` | INNPACK (sin parámetro) | cualquier sesión INNPACK (sin gating de rol) | — (deshabilitada) | **operador, admin, admin_ti** | PENDIENTE_VALIDACION_NEGOCIO | `[Authorize]` (cualquier JWT) | sesión C# de Photino: Id, NombreCompleto | inyectar userId (Photino lo toma de su sesión); inyectar nombreCompleto (Photino lo toma de su sesión) | registra un ensayo sobre una muestra | sí: anular ensayo (admin) | **BAJO** | registro operativo; la evaluación la calcula la API | APROBADA |
| `muestraLab.nc.crear` | POST `api/muestra-laboratorio/{muestraId}/nc` | INNPACK (sin parámetro) | cualquier sesión INNPACK (sin gating de rol) | — (deshabilitada) | **operador, admin, admin_ti** | PENDIENTE_VALIDACION_NEGOCIO | `[Authorize]` (cualquier JWT) | sesión C# de Photino: NombreCompleto | inyectar nombreCompleto (Photino lo toma de su sesión) | crea una NC vinculada a la muestra | sí: eliminar NC (admin) | **BAJO** | aditivo; autor desde sesión | APROBADA |
| `muestraLab.ph.guardar` | POST `api/muestra-laboratorio/ph` | INNPACK (sin parámetro) | cualquier sesión INNPACK (sin gating de rol) | — (deshabilitada) | **operador, admin, admin_ti** | PENDIENTE_VALIDACION_NEGOCIO | `[Authorize]` (cualquier JWT) | sesión C# de Photino: Id, NombreCompleto | inyectar userId (Photino lo toma de su sesión); inyectar nombreCompleto (Photino lo toma de su sesión) | registra un ensayo sobre una muestra | sí: anular ensayo (admin) | **BAJO** | registro operativo; la evaluación la calcula la API | APROBADA |
| `muestraLab.rct.guardar` | POST; POST `api/muestra-laboratorio/fct; api/muestra-laboratorio/rct` | INNPACK (sin parámetro) | cualquier sesión INNPACK (sin gating de rol) | — (deshabilitada) | **operador, admin, admin_ti** | PENDIENTE_VALIDACION_NEGOCIO | `[Authorize]` (cualquier JWT) | sesión C# de Photino: Id, NombreCompleto | inyectar userId (Photino lo toma de su sesión); inyectar nombreCompleto (Photino lo toma de su sesión) | registra un ensayo sobre una muestra | sí: anular ensayo (admin) | **BAJO** | registro operativo; la evaluación la calcula la API | APROBADA |
| `muestraLab.solidos.guardar` | POST `api/muestra-laboratorio/solidos` | INNPACK (sin parámetro) | cualquier sesión INNPACK (sin gating de rol) | — (deshabilitada) | **operador, admin, admin_ti** | PENDIENTE_VALIDACION_NEGOCIO | `[Authorize]` (cualquier JWT) | sesión C# de Photino: Id, NombreCompleto | inyectar userId (Photino lo toma de su sesión); inyectar nombreCompleto (Photino lo toma de su sesión) | registra un ensayo sobre una muestra | sí: anular ensayo (admin) | **BAJO** | registro operativo; la evaluación la calcula la API | APROBADA |
| `muestraLab.viscosidad.guardar` | POST `api/muestra-laboratorio/viscosidad` | INNPACK (sin parámetro) | cualquier sesión INNPACK (sin gating de rol) | — (deshabilitada) | **operador, admin, admin_ti** | PENDIENTE_VALIDACION_NEGOCIO | `[Authorize]` (cualquier JWT) | sesión C# de Photino: Id, NombreCompleto | inyectar userId (Photino lo toma de su sesión); inyectar nombreCompleto (Photino lo toma de su sesión) | registra un ensayo sobre una muestra | sí: anular ensayo (admin) | **BAJO** | registro operativo; la evaluación la calcula la API | APROBADA |
| `muestraLab.actualizarFechaEnsayo` | PUT `api/muestra-laboratorio/{id}/fecha-ensayo` | INNPACK (sin parámetro) | cualquier sesión INNPACK (sin gating de rol) | — (deshabilitada) | **admin, admin_ti** | PENDIENTE_VALIDACION_NEGOCIO | `[Authorize]` (cualquier JWT) | sesión C# de Photino: NombreCompleto | inyectar nombreCompleto (Photino lo toma de su sesión) | corrige la fecha efectiva de ensayo (auditada por la API) | sí: volver a corregir | **MEDIO** | altera trazabilidad temporal | APROBADA |
| `muestraLab.anular` | POST `api/muestra-laboratorio/{muestraId}/anular` | INNPACK (sin parámetro) | cualquier sesión INNPACK (sin gating de rol) | — (deshabilitada) | **admin, admin_ti** | PENDIENTE_VALIDACION_NEGOCIO | `[Authorize]` (cualquier JWT) | sesión C# de Photino: NombreCompleto | inyectar nombreCompleto (Photino lo toma de su sesión) | anula el registro completo (conserva historial) | no hay 'desanular' en la UI | **MEDIO** | cambio de estado controlado, con motivo | APROBADA |
| `muestraLab.ensayo.anular` | POST `api/muestra-laboratorio/ensayos/{ensayoId}/anular` | INNPACK (sin parámetro) | cualquier sesión INNPACK (sin gating de rol) | — (deshabilitada) | **admin, admin_ti** | PENDIENTE_VALIDACION_NEGOCIO | `[Authorize]` (cualquier JWT) | ninguna (la API no registra autor) | — (auditoría del gateway obligatoria) | anula un ensayo puntual | no hay 'desanular' | **MEDIO** | afecta la evaluación de la muestra; sin autor | APROBADA |
| `muestraLab.especificacion.activar` | PATCH `api/muestra-laboratorio/especificaciones/{id}/activo` | INNPACK (sin parámetro) | cualquier sesión INNPACK (sin gating de rol) | — (deshabilitada) | **admin, admin_ti** | PENDIENTE_VALIDACION_NEGOCIO | `[Authorize]` (cualquier JWT) | ninguna (la API no registra autor) | — (auditoría del gateway obligatoria) | edita maestro de métodos/especificaciones | sí: volver a editar/activar | **MEDIO** | maestro que cambia evaluaciones futuras | APROBADA |
| `muestraLab.especificacion.guardar` | POST `api/muestra-laboratorio/especificaciones` | INNPACK (sin parámetro) | cualquier sesión INNPACK (sin gating de rol) | — (deshabilitada) | **admin, admin_ti** | PENDIENTE_VALIDACION_NEGOCIO | `[Authorize]` (cualquier JWT) | ninguna (la API no registra autor) | — (auditoría del gateway obligatoria) | edita maestro de métodos/especificaciones | sí: volver a editar/activar | **MEDIO** | maestro que cambia evaluaciones futuras | APROBADA |
| `muestraLab.metodo.activar` | PATCH `api/muestra-laboratorio/metodos/{id}/activo` | INNPACK (sin parámetro) | cualquier sesión INNPACK (sin gating de rol) | — (deshabilitada) | **admin, admin_ti** | PENDIENTE_VALIDACION_NEGOCIO | `[Authorize]` (cualquier JWT) | ninguna (la API no registra autor) | — (auditoría del gateway obligatoria) | edita maestro de métodos/especificaciones | sí: volver a editar/activar | **MEDIO** | maestro que cambia evaluaciones futuras | APROBADA |
| `muestraLab.metodo.guardar` | POST `api/muestra-laboratorio/metodos` | INNPACK (sin parámetro) | cualquier sesión INNPACK (sin gating de rol) | — (deshabilitada) | **admin, admin_ti** | PENDIENTE_VALIDACION_NEGOCIO | `[Authorize]` (cualquier JWT) | sesión C# de Photino: NombreCompleto | inyectar nombreCompleto (Photino lo toma de su sesión) | edita maestro de métodos/especificaciones | sí: volver a editar/activar | **MEDIO** | maestro que cambia evaluaciones futuras | APROBADA |
| `muestraLab.adjunto.eliminar` | DELETE `api/muestra-laboratorio/adjunto/{adjuntoId}` | INNPACK (sin parámetro) | cualquier sesión INNPACK (sin gating de rol) | — (deshabilitada) | **admin, admin_ti** | PENDIENTE_VALIDACION_NEGOCIO | `[Authorize]` (cualquier JWT) | ninguna (la API no registra autor) | — (auditoría del gateway obligatoria) | elimina un adjunto | no desde la UI | **ALTO** | destructivo; sin autor | APROBADA |
| `muestraLab.eliminar` | DELETE `api/muestra-laboratorio/{muestraId}` | INNPACK (sin parámetro) | cualquier sesión INNPACK (sin gating de rol) | — (deshabilitada) | **admin, admin_ti** | PENDIENTE_VALIDACION_NEGOCIO | `[Authorize]` (cualquier JWT) | ninguna (la API no registra autor) | — (auditoría del gateway obligatoria) | borrado lógico de la muestra | no desde la UI | **ALTO** | destructivo; sin autor | APROBADA |

### noConformidades (20)

| action | método · endpoint | empresa | ROL_ACTUAL_PHOTINO | ROL_ACTUAL_WEB | ROL_PROPUESTO_NEGOCIO | ESTADO_VALIDACION_NEGOCIO | API hoy | identidad desde cliente | Web fija desde SessionUser | efecto | rollback | riesgo | justificación | estado |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `noConformidades.acciones.crear` | POST `api/no-conformidades/{id}/acciones` | INNPACK (sin parámetro) | cualquier sesión INNPACK (sin gating de rol) | cualquier sesión INNPACK (= Photino; `operador, admin, admin_ti`) | **operador, admin, admin_ti** | PENDIENTE_VALIDACION_NEGOCIO | `[Authorize]` (cualquier JWT) | cliente: `creadoPor` | `creadoPor` ← nombreCompleto; NO tocar: responsable (dato de negocio) | crea una acción correctiva | sí: actualizar estado | **BAJO** | aditivo; responsable = dato de negocio | VALIDADA (Fase 3b, gateway 0.4.0) |
| `noConformidades.adjuntos.subir` | POST `api/no-conformidades/{id}/adjuntos` | INNPACK (sin parámetro) | cualquier sesión INNPACK (sin gating de rol) | cualquier sesión INNPACK (= Photino; `operador, admin, admin_ti`) | **operador, admin, admin_ti** | PENDIENTE_VALIDACION_NEGOCIO | `[Authorize]` (cualquier JWT) | cliente: `subidoPor` | `subidoPor` ← nombreCompleto | adjunta PDF de causa raíz / fotos (API valida MIME y tamaño) | sí: eliminar adjunto (admin) | **MEDIO** | reemplazar el PDF oculta el anterior; archivo y nombre llegan a Photino escritorio | VALIDADA (Fase 3k, gateway 0.4.0) |
| `noConformidades.analisis.guardar` | PUT `api/no-conformidades/{id}/analisis` | INNPACK (sin parámetro) | cualquier sesión INNPACK (sin gating de rol) | cualquier sesión INNPACK (= Photino; `operador, admin, admin_ti`) | **operador, admin, admin_ti** | PENDIENTE_VALIDACION_NEGOCIO | `[Authorize]` (cualquier JWT) | cliente: `usuario` | `usuario` ← nombreCompleto | guarda el análisis de causa raíz (5 por qué) | NO recuperable: la API sobrescribe en sitio sin historial (SEC-28); se corrige volviendo a guardar | **BAJO** | no cambia estado | VALIDADA (Fase 3c, gateway 0.4.0) |
| `noConformidades.catalogos.areas.crear` | POST `api/nc-catalogos/{catalogo}` | INNPACK (sin parámetro) | cualquier sesión INNPACK (sin gating de rol) | cualquier sesión INNPACK (= Photino; `operador, admin, admin_ti`) | **operador, admin, admin_ti** | PENDIENTE_VALIDACION_NEGOCIO | `[Authorize]` (cualquier JWT) | cliente: `creadoPor` | `creadoPor` ← nombreCompleto | agrega un valor a un catálogo de NC | desactivar (API; no usado por la UI) | **BAJO** | aditivo, inline desde el formulario | VALIDADA (Fase 3g, gateway 0.4.0) |
| `noConformidades.catalogos.categoriasDefecto.crear` | POST `api/nc-catalogos/{catalogo}` | INNPACK (sin parámetro) | cualquier sesión INNPACK (sin gating de rol) | cualquier sesión INNPACK (= Photino; `operador, admin, admin_ti`) | **operador, admin, admin_ti** | PENDIENTE_VALIDACION_NEGOCIO | `[Authorize]` (cualquier JWT) | cliente: `creadoPor` | `creadoPor` ← nombreCompleto | agrega un valor a un catálogo de NC | desactivar (API; no usado por la UI) | **BAJO** | aditivo, inline desde el formulario | VALIDADA (Fase 3e, gateway 0.4.0) |
| `noConformidades.catalogos.clientes.crear` | POST `api/nc-catalogos/{catalogo}` | INNPACK (sin parámetro) | cualquier sesión INNPACK (sin gating de rol) | cualquier sesión INNPACK (= Photino; `operador, admin, admin_ti`) | **operador, admin, admin_ti** | PENDIENTE_VALIDACION_NEGOCIO | `[Authorize]` (cualquier JWT) | cliente: `creadoPor` | `creadoPor` ← nombreCompleto | agrega un valor a un catálogo de NC | desactivar (API; no usado por la UI) | **BAJO** | aditivo, inline desde el formulario | VALIDADA (Fase 3d, gateway 0.4.0) |
| `noConformidades.catalogos.familiasProducto.crear` | POST `api/nc-catalogos/{catalogo}` | INNPACK (sin parámetro) | cualquier sesión INNPACK (sin gating de rol) | cualquier sesión INNPACK (= Photino; `operador, admin, admin_ti`) | **operador, admin, admin_ti** | PENDIENTE_VALIDACION_NEGOCIO | `[Authorize]` (cualquier JWT) | cliente: `creadoPor` | `creadoPor` ← nombreCompleto | agrega un valor a un catálogo de NC | desactivar (API; no usado por la UI) | **BAJO** | aditivo, inline desde el formulario | VALIDADA (Fase 3h, gateway 0.4.0) |
| `noConformidades.catalogos.impactos.crear` | POST `api/nc-catalogos/{catalogo}` | INNPACK (sin parámetro) | cualquier sesión INNPACK (sin gating de rol) | cualquier sesión INNPACK (= Photino; `operador, admin, admin_ti`) | **operador, admin, admin_ti** | PENDIENTE_VALIDACION_NEGOCIO | `[Authorize]` (cualquier JWT) | cliente: `creadoPor` | `creadoPor` ← nombreCompleto | agrega un valor a un catálogo de NC | desactivar (API; no usado por la UI) | **BAJO** | aditivo, inline desde el formulario | VALIDADA (Fase 3h, gateway 0.4.0) |
| `noConformidades.catalogos.niveles.crear` | POST `api/nc-catalogos/{catalogo}` | INNPACK (sin parámetro) | cualquier sesión INNPACK (sin gating de rol) | cualquier sesión INNPACK (= Photino; `operador, admin, admin_ti`) | **admin_ti** (inicial; objetivo: operador, admin, admin_ti) | PENDIENTE_VALIDACION_NEGOCIO | `[Authorize]` (cualquier JWT) | cliente: `creadoPor` | `creadoPor` ← nombreCompleto | agrega un nivel (define la severidad de la NC) | desactivar (API; no usado por la UI) | **MEDIO** | el nivel pilota severidad/color y el filtro fijo Crítico/Mayor/Menor | VALIDADA (Fase 3i/3o, gateway 0.4.0; roles = Photino) |
| `noConformidades.catalogos.revisores.crear` | POST `api/nc-catalogos/{catalogo}` | INNPACK (sin parámetro) | cualquier sesión INNPACK (sin gating de rol) | cualquier sesión INNPACK (= Photino; `operador, admin, admin_ti`) | **operador, admin, admin_ti** | PENDIENTE_VALIDACION_NEGOCIO | `[Authorize]` (cualquier JWT) | cliente: `creadoPor` | `creadoPor` ← nombreCompleto | agrega un valor a un catálogo de NC | desactivar (API; no usado por la UI) | **BAJO** | aditivo, inline desde el formulario | VALIDADA (Fase 3f, gateway 0.4.0) |
| `noConformidades.catalogos.supervisores.crear` | POST `api/nc-catalogos/{catalogo}` | INNPACK (sin parámetro) | cualquier sesión INNPACK (sin gating de rol) | cualquier sesión INNPACK (= Photino; `operador, admin, admin_ti`) | **operador, admin, admin_ti** | PENDIENTE_VALIDACION_NEGOCIO | `[Authorize]` (cualquier JWT) | cliente: `creadoPor` | `creadoPor` ← nombreCompleto | agrega un valor a un catálogo de NC | desactivar (API; no usado por la UI) | **BAJO** | aditivo, inline desde el formulario | VALIDADA (Fase 3f, gateway 0.4.0) |
| `noConformidades.catalogos.tiposFalla.crear` | POST `api/nc-catalogos/{catalogo}` | INNPACK (sin parámetro) | cualquier sesión INNPACK (sin gating de rol) | cualquier sesión INNPACK (= Photino; `operador, admin, admin_ti`) | **operador, admin, admin_ti** | PENDIENTE_VALIDACION_NEGOCIO | `[Authorize]` (cualquier JWT) | cliente: `creadoPor` | `creadoPor` ← nombreCompleto | agrega un valor a un catálogo de NC | desactivar (API; no usado por la UI) | **BAJO** | aditivo, inline desde el formulario | VALIDADA (Fase 3f, gateway 0.4.0) |
| `noConformidades.create` | POST `api/no-conformidades` | INNPACK (sin parámetro) | cualquier sesión INNPACK (sin gating de rol) | cualquier sesión INNPACK (= Photino; `operador, admin, admin_ti`) | **operador, admin, admin_ti** | PENDIENTE_VALIDACION_NEGOCIO | `[Authorize]` (cualquier JWT) | cliente: `creadoPor` | `creadoPor` ← nombreCompleto; payload completo pasa a la API → lista blanca de campos | crea una No Conformidad | sí: editar / eliminar (admin) | **MEDIO** | registro principal del módulo: lista blanca de claves de Photino, cabecera recalculada, largos del esquema | VALIDADA (Fase 3j, gateway 0.4.0) |
| `noConformidades.seguimiento.crear` | POST `api/no-conformidades/{id}/seguimiento` | INNPACK (sin parámetro) | cualquier sesión INNPACK (sin gating de rol) | cualquier sesión INNPACK (= Photino; `operador, admin, admin_ti`) | **operador, admin, admin_ti** | PENDIENTE_VALIDACION_NEGOCIO | `[Authorize]` (cualquier JWT) | cliente: `autor` | `autor` ← nombreCompleto | agrega un comentario de seguimiento (append-only) | no se borra; se corrige con otro comentario | **BAJO** | aditivo, sin cambio de estado; autor desde sesión | VALIDADA (Fase 3a, gateway 0.4.0) |
| `noConformidades.acciones.actualizar` | PUT `api/no-conformidades/acciones/{accionId}` | INNPACK (sin parámetro) | cualquier sesión INNPACK (sin gating de rol) | cualquier sesión INNPACK (= Photino; `operador, admin, admin_ti`) | **operador, admin, admin_ti** | PENDIENTE_VALIDACION_NEGOCIO | `[Authorize]` (cualquier JWT) | cliente: `actualizadoPor` | `actualizadoPor` ← nombreCompleto; NO tocar: responsable (dato de negocio) | cambia estado/datos de una acción correctiva | sí: volver a editar | **MEDIO** | puede cerrar acciones; actualizadoPor desde sesión | VALIDADA (Fase 3n, gateway 0.4.0; solo `estado` del navegador, resto original) |
| `noConformidades.cerrar` | POST `api/no-conformidades/{id}/cerrar` | INNPACK (sin parámetro) | cualquier sesión INNPACK (sin gating de rol) | cualquier sesión INNPACK (= Photino; `operador, admin, admin_ti`) | **admin, admin_ti** | PENDIENTE_VALIDACION_NEGOCIO | `[Authorize]` (cualquier JWT) | cliente: `cerradoPor` | `cerradoPor` ← nombreCompleto | cierra la NC (CERRADA) | no hay 'reabrir' en la UI | **MEDIO** | igual que Photino (cerrar otra vez vuelve a registrar quién/cuándo); cerradoPor de sesión | VALIDADA (Fase 3m/3o, gateway 0.4.0) |
| `noConformidades.gestion.actualizar` | PATCH `api/no-conformidades/{id}/gestion` | INNPACK (sin parámetro) | cualquier sesión INNPACK (sin gating de rol) | cualquier sesión INNPACK (= Photino; `operador, admin, admin_ti`) | **admin, admin_ti** | PENDIENTE_VALIDACION_NEGOCIO | `[Authorize]` (cualquier JWT) | cliente: `actualizadoPor` | `actualizadoPor` ← nombreCompleto; NO tocar: responsable (dato de negocio) | asigna responsable, estado de gestión y fecha compromiso | sí: volver a editar | **MEDIO** | igual que Photino (acepta CERRADA y reabre); seguridad: lost update, identidad | VALIDADA (Fase 3m/3o, gateway 0.4.0) |
| `noConformidades.update` | PUT `api/no-conformidades/{id}` | INNPACK (sin parámetro) | cualquier sesión INNPACK (sin gating de rol) | cualquier sesión INNPACK (= Photino; `operador, admin, admin_ti`) | **operador, admin, admin_ti** | PENDIENTE_VALIDACION_NEGOCIO | `[Authorize]` (cualquier JWT) | cliente: `actualizadoPor` | `actualizadoPor` ← nombreCompleto; payload completo pasa a la API → lista blanca de campos | edita campos de la NC (actualización parcial) | sí: volver a editar | **MEDIO** | sobrescribe sin historial: lista blanca, cabecera recalculada, lost update (NC cerrada editable como Photino) | VALIDADA (Fase 3l/3o, gateway 0.4.0) |
| `noConformidades.adjuntos.eliminar` | DELETE `api/no-conformidades/{id}/adjuntos/{adjuntoId}` | INNPACK (sin parámetro) | cualquier sesión INNPACK (sin gating de rol) | cualquier sesión INNPACK (= Photino; `operador, admin, admin_ti`) | **admin, admin_ti** | PENDIENTE_VALIDACION_NEGOCIO | `[Authorize]` (cualquier JWT) | ninguna (la API no registra autor) | — (auditoría del gateway obligatoria) | elimina un adjunto | no desde la UI | **ALTO** | destructivo; sin autor (auditoría del gateway `nc:<id>:adjunto:<id>:eliminado`) | VALIDADA (Fase 3r, gateway 0.4.0; roles = Photino) |
| `noConformidades.eliminar` | DELETE `api/no-conformidades/{id}` | INNPACK (sin parámetro) | cualquier sesión INNPACK (sin gating de rol) | cualquier sesión INNPACK (= Photino; `operador, admin, admin_ti`) | **admin, admin_ti** | PENDIENTE_VALIDACION_NEGOCIO | `[Authorize]` (cualquier JWT) | cliente: `actualizadoPor` | `actualizadoPor` ← nombreCompleto | borrado lógico de la NC | no desde la UI | **ALTO** | destructivo; `actualizadoPor` de sesión, relectura previa (inexistente → error) | VALIDADA (Fase 3r, gateway 0.4.0; roles = Photino) |

### productoTerminado (2)

| action | método · endpoint | empresa | ROL_ACTUAL_PHOTINO | ROL_ACTUAL_WEB | ROL_PROPUESTO_NEGOCIO | ESTADO_VALIDACION_NEGOCIO | API hoy | identidad desde cliente | Web fija desde SessionUser | efecto | rollback | riesgo | justificación | estado |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `productoTerminado.actualizarFecha` | PUT `api/producto-terminado/{id}/fecha` | INNPACK de sesión (`IdentityOverride` EMPRESA) | cualquier sesión INNPACK (sin gating de rol) | — (deshabilitada) | **admin, admin_ti** | PENDIENTE_VALIDACION_NEGOCIO | `[Authorize]` (cualquier JWT) | cliente: `usuarioNombre` | `usuarioNombre` ← nombreCompleto | corrige la fecha de una inspección de producto terminado | sí: volver a corregir | **MEDIO** | altera trazabilidad; empresa de sesión | APROBADA |
| `productoTerminado.eliminar` | DELETE `api/producto-terminado/{id}?empresa={Uri.EscapeDataString(empresa)}` | INNPACK de sesión (`IdentityOverride` EMPRESA) | cualquier sesión INNPACK (sin gating de rol) | — (deshabilitada) | **admin, admin_ti** | PENDIENTE_VALIDACION_NEGOCIO | `[Authorize]` (cualquier JWT) | ninguna (la API no registra autor) | — (auditoría del gateway obligatoria) | elimina una inspección de producto terminado | no desde la UI | **ALTO** | destructivo; sin autor | APROBADA |

### recepcion (6)

| action | método · endpoint | empresa | ROL_ACTUAL_PHOTINO | ROL_ACTUAL_WEB | ROL_PROPUESTO_NEGOCIO | ESTADO_VALIDACION_NEGOCIO | API hoy | identidad desde cliente | Web fija desde SessionUser | efecto | rollback | riesgo | justificación | estado |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `recepcion.bobinas.muestrear` | POST `api/recepcion-calidad/{id}/bobinas-muestreadas` | INNPACK de sesión (`IdentityOverride` EMPRESA) | cualquier sesión INNPACK (sin gating de rol) | cualquier sesión INNPACK (= Photino; `operador, admin, admin_ti`) | **operador, admin, admin_ti** | PENDIENTE_VALIDACION_NEGOCIO | `[Authorize]` (cualquier JWT) | sesión C# de Photino: NombreCompleto | inyectar nombreCompleto (Photino lo toma de su sesión) | genera plan AQL / registra bobinas muestreadas | sí: regenerar / volver a guardar | **BAJO** | operativo; sin autor en plan | VALIDADA (Fase 3q, gateway 0.4.0) |
| `recepcion.crear` | POST `api/recepcion-calidad` | INNPACK de sesión (`IdentityOverride` EMPRESA) | cualquier sesión INNPACK (sin gating de rol) | — (deshabilitada) | **operador, admin, admin_ti** | PENDIENTE_VALIDACION_NEGOCIO | `[Authorize]` (cualquier JWT) | sesión C# de Photino: NombreCompleto | inyectar nombreCompleto (Photino lo toma de su sesión) | crea un lote de recepción (con foto opcional) | no hay eliminar en la UI | **BAJO** | registro operativo; empresa y autor desde sesión | APROBADA |
| `recepcion.muestra.crear` | POST `api/recepcion-calidad/{id}/muestra-laboratorio` | INNPACK de sesión (`IdentityOverride` EMPRESA) | cualquier sesión INNPACK (sin gating de rol) | — (deshabilitada) | **operador, admin, admin_ti** | PENDIENTE_VALIDACION_NEGOCIO | `[Authorize]` (cualquier JWT) | sesión C# de Photino: Id, NombreCompleto | inyectar userId (Photino lo toma de su sesión); inyectar nombreCompleto (Photino lo toma de su sesión) | crea muestra de laboratorio / NC desde el lote | sí: anular/eliminar en su módulo (admin) | **BAJO** | aditivo; autor y empresa desde sesión | APROBADA |
| `recepcion.nc.crear` | POST `api/recepcion-calidad/{id}/nc` | INNPACK de sesión (`IdentityOverride` EMPRESA) | cualquier sesión INNPACK (sin gating de rol) | — (deshabilitada) | **operador, admin, admin_ti** | PENDIENTE_VALIDACION_NEGOCIO | `[Authorize]` (cualquier JWT) | sesión C# de Photino: NombreCompleto | inyectar nombreCompleto (Photino lo toma de su sesión) | crea muestra de laboratorio / NC desde el lote | sí: anular/eliminar en su módulo (admin) | **BAJO** | aditivo; autor y empresa desde sesión | APROBADA |
| `recepcion.plan.generar` | POST `api/recepcion-calidad/{id}/plan` | INNPACK de sesión (`IdentityOverride` EMPRESA) | cualquier sesión INNPACK (sin gating de rol) | — (deshabilitada) | **operador, admin, admin_ti** | PENDIENTE_VALIDACION_NEGOCIO | `[Authorize]` (cualquier JWT) | ninguna (la API no registra autor) | — (auditoría del gateway obligatoria) | genera plan AQL / registra bobinas muestreadas | sí: regenerar / volver a guardar | **BAJO** | operativo; sin autor en plan | APROBADA |
| `recepcion.estado.actualizar` | PATCH `api/recepcion-calidad/{id}/estado` | INNPACK de sesión (`IdentityOverride` EMPRESA) | cualquier sesión INNPACK (sin gating de rol) | — (deshabilitada) | **admin, admin_ti** | PENDIENTE_VALIDACION_NEGOCIO | `[Authorize]` (cualquier JWT) | ninguna (la API no registra autor) | — (auditoría del gateway obligatoria) | decide el estado del lote (conforme / no conforme) | sí: volver a cambiar | **MEDIO** | decisión de calidad; sin autor en API | APROBADA |

### registrosControl (3)

| action | método · endpoint | empresa | ROL_ACTUAL_PHOTINO | ROL_ACTUAL_WEB | ROL_PROPUESTO_NEGOCIO | ESTADO_VALIDACION_NEGOCIO | API hoy | identidad desde cliente | Web fija desde SessionUser | efecto | rollback | riesgo | justificación | estado |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `registrosControl.rechazarRegistro` | PUT `api/registros-control/{id}/rechazar` | INNPACK (sin parámetro) | cualquier sesión INNPACK (sin gating de rol) | — (deshabilitada) | **admin, admin_ti** | PENDIENTE_VALIDACION_NEGOCIO | `[Authorize]` (cualquier JWT) | ninguna (la API no registra autor) | — (auditoría del gateway obligatoria) | valida/rechaza un registro de control | re-operando; sin historial de autor | **MEDIO** | decisión de supervisión; sin autor en API | APROBADA |
| `registrosControl.validarRegistro` | PUT `api/registros-control/{id}/validar` | INNPACK (sin parámetro) | cualquier sesión INNPACK (sin gating de rol) | — (deshabilitada) | **admin, admin_ti** | PENDIENTE_VALIDACION_NEGOCIO | `[Authorize]` (cualquier JWT) | ninguna (la API no registra autor) | — (auditoría del gateway obligatoria) | valida/rechaza un registro de control | re-operando; sin historial de autor | **MEDIO** | decisión de supervisión; sin autor en API | APROBADA |
| `registrosControl.eliminarRegistro` | DELETE `api/registros-control/{id}` | INNPACK (sin parámetro) | cualquier sesión INNPACK (sin gating de rol) | — (deshabilitada) | **admin, admin_ti** | PENDIENTE_VALIDACION_NEGOCIO | `[Authorize]` (cualquier JWT) | ninguna (la API no registra autor) | — (auditoría del gateway obligatoria) | elimina un registro de control | no desde la UI | **ALTO** | destructivo; sin autor | APROBADA |

### registrosProduccion (5)

| action | método · endpoint | empresa | ROL_ACTUAL_PHOTINO | ROL_ACTUAL_WEB | ROL_PROPUESTO_NEGOCIO | ESTADO_VALIDACION_NEGOCIO | API hoy | identidad desde cliente | Web fija desde SessionUser | efecto | rollback | riesgo | justificación | estado |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `registrosProduccion.rechazarRegistro` | PUT `api/registros-produccion/{id}/rechazar` | INNPACK (sin parámetro) | cualquier sesión INNPACK (sin gating de rol) | — (deshabilitada) | **admin, admin_ti** | PENDIENTE_VALIDACION_NEGOCIO | `[Authorize]` (cualquier JWT) | ninguna (la API no registra autor) | — (auditoría del gateway obligatoria) | valida/rechaza una inspección de producción | re-operando; sin historial de autor | **MEDIO** | decisión de supervisión; sin autor en API | APROBADA |
| `registrosProduccion.validarRegistro` | PUT `api/registros-produccion/{id}/validar` | INNPACK (sin parámetro) | cualquier sesión INNPACK (sin gating de rol) | — (deshabilitada) | **admin, admin_ti** | PENDIENTE_VALIDACION_NEGOCIO | `[Authorize]` (cualquier JWT) | ninguna (la API no registra autor) | — (auditoría del gateway obligatoria) | valida/rechaza una inspección de producción | re-operando; sin historial de autor | **MEDIO** | decisión de supervisión; sin autor en API | APROBADA |
| `registrosProduccion.eliminarRegistro` | DELETE `api/registros-produccion/{id}` | INNPACK (sin parámetro) | cualquier sesión INNPACK (sin gating de rol) | — (deshabilitada) | **admin, admin_ti** | PENDIENTE_VALIDACION_NEGOCIO | `[Authorize]` (cualquier JWT) | ninguna (la API no registra autor) | — (auditoría del gateway obligatoria) | elimina una inspección de producción | no desde la UI | **ALTO** | destructivo; sin autor | APROBADA |
| `registrosProduccion.rechazarTodo` | PUT `api/registros-produccion/rechazar-todo` | INNPACK (sin parámetro) | cualquier sesión INNPACK (sin gating de rol) | — (deshabilitada) | **admin** | PENDIENTE_VALIDACION_NEGOCIO | `[Authorize]` (cualquier JWT) | ninguna (la API no registra autor) | — (auditoría del gateway obligatoria) | valida/rechaza TODAS las inspecciones filtradas | no (masivo) | **ALTO** | masivo; solo admin con confirmación | APROBADA |
| `registrosProduccion.validarTodo` | PUT `api/registros-produccion/validar-todo` | INNPACK (sin parámetro) | cualquier sesión INNPACK (sin gating de rol) | — (deshabilitada) | **admin** | PENDIENTE_VALIDACION_NEGOCIO | `[Authorize]` (cualquier JWT) | ninguna (la API no registra autor) | — (auditoría del gateway obligatoria) | valida/rechaza TODAS las inspecciones filtradas | no (masivo) | **ALTO** | masivo; solo admin con confirmación | APROBADA |

### talleresExternos (6)

| action | método · endpoint | empresa | ROL_ACTUAL_PHOTINO | ROL_ACTUAL_WEB | ROL_PROPUESTO_NEGOCIO | ESTADO_VALIDACION_NEGOCIO | API hoy | identidad desde cliente | Web fija desde SessionUser | efecto | rollback | riesgo | justificación | estado |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `talleresExternos.create` | POST `api/talleres-externos` | INNPACK (sin parámetro) | cualquier sesión INNPACK (sin gating de rol) | — (deshabilitada) | **operador, admin, admin_ti** | PENDIENTE_VALIDACION_NEGOCIO | `[Authorize]` (cualquier JWT) | sesión C# de Photino: Id | inyectar userId (Photino lo toma de su sesión); payload completo pasa a la API → lista blanca de campos | crea un trabajo de taller externo | sí: editar/eliminar (admin) | **BAJO** | operativo; usuarioId desde sesión | APROBADA |
| `talleresExternos.sincronizarFps` | POST `api/talleres-externos/sincronizar-fps` | INNPACK (sin parámetro) | cualquier sesión INNPACK (sin gating de rol) | — (deshabilitada) | **admin, admin_ti** | PENDIENTE_VALIDACION_NEGOCIO | `[Authorize]` (cualquier JWT) | sesión C# de Photino: Id | inyectar userId (Photino lo toma de su sesión) | sincroniza liberaciones desde FPS (API → FPS) y actualiza cantidades | idempotente según la API (por verificar) | **MEDIO** | operación masiva disparada contra un sistema externo | APROBADA |
| `talleresExternos.update` | PUT `api/talleres-externos/{id}` | INNPACK (sin parámetro) | cualquier sesión INNPACK (sin gating de rol) | — (deshabilitada) | **operador, admin, admin_ti** | PENDIENTE_VALIDACION_NEGOCIO | `[Authorize]` (cualquier JWT) | sesión C# de Photino: Id | inyectar userId (Photino lo toma de su sesión); payload completo pasa a la API → lista blanca de campos | edita un trabajo (concurrencia optimista: version → 409) | sí: volver a editar | **MEDIO** | precios/cantidades; usuarioId desde sesión | APROBADA |
| `talleresExternos.catalogos.eliminarProceso` | DELETE `api/talleres-externos/catalogos/procesos/{id}` | INNPACK (sin parámetro) | cualquier sesión INNPACK (sin gating de rol) | — (deshabilitada) | **admin, admin_ti** | PENDIENTE_VALIDACION_NEGOCIO | `[Authorize]` (cualquier JWT) | ninguna (la API no registra autor) | — (auditoría del gateway obligatoria) | elimina un taller/proceso del catálogo | no desde la UI | **ALTO** | maestro compartido; sin autor | APROBADA |
| `talleresExternos.catalogos.eliminarTaller` | DELETE `api/talleres-externos/catalogos/talleres/{id}` | INNPACK (sin parámetro) | cualquier sesión INNPACK (sin gating de rol) | — (deshabilitada) | **admin, admin_ti** | PENDIENTE_VALIDACION_NEGOCIO | `[Authorize]` (cualquier JWT) | ninguna (la API no registra autor) | — (auditoría del gateway obligatoria) | elimina un taller/proceso del catálogo | no desde la UI | **ALTO** | maestro compartido; sin autor | APROBADA |
| `talleresExternos.eliminar` | DELETE `api/talleres-externos/{id}?version=&usuarioId=` | INNPACK (sin parámetro) | cualquier sesión INNPACK (sin gating de rol) | — (deshabilitada) | **admin, admin_ti** | PENDIENTE_VALIDACION_NEGOCIO | `[Authorize]` (cualquier JWT) | sesión C# de Photino: Id | inyectar userId (Photino lo toma de su sesión) | elimina un trabajo (con version) | no desde la UI | **ALTO** | destructivo | APROBADA |

### usuarios (3)

| action | método · endpoint | empresa | ROL_ACTUAL_PHOTINO | ROL_ACTUAL_WEB | ROL_PROPUESTO_NEGOCIO | ESTADO_VALIDACION_NEGOCIO | API hoy | identidad desde cliente | Web fija desde SessionUser | efecto | rollback | riesgo | justificación | estado |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `usuarios.create` | POST `api/usuarios` | INNPACK (sin parámetro) | usuarios.*: admin/admin_ti (handler + menú) | — (deshabilitada; gestión de usuarios = excepción, se definirá aparte) | **admin, admin_ti** | PENDIENTE_VALIDACION_NEGOCIO | `[Authorize(Roles=admin,admin_ti)]` | ninguna (la API no registra autor) | — (auditoría del gateway obligatoria) | crea una cuenta (con rol elegido) | sí: eliminar (admin) | **ALTO** | gestión de cuentas; ya restringido en Photino y API | APROBADA |
| `usuarios.delete` | DELETE `api/usuarios/{id}` | INNPACK (sin parámetro) | usuarios.*: admin/admin_ti (handler + menú) | — (deshabilitada; gestión de usuarios = excepción, se definirá aparte) | **admin, admin_ti** | PENDIENTE_VALIDACION_NEGOCIO | `[Authorize(Roles=admin,admin_ti)]` | ninguna (la API no registra autor) | — (auditoría del gateway obligatoria) | elimina/desactiva una cuenta | no desde la UI | **ALTO** | gestión de cuentas; la API impide eliminarse a sí mismo (JWT) | APROBADA |
| `usuarios.resetPassword` | PUT `api/usuarios/{id}/password` | INNPACK (sin parámetro) | usuarios.*: admin/admin_ti (handler + menú) | — (deshabilitada; gestión de usuarios = excepción, se definirá aparte) | **admin, admin_ti** | PENDIENTE_VALIDACION_NEGOCIO | `[Authorize(Roles=admin,admin_ti)]` | ninguna (la API no registra autor) | — (auditoría del gateway obligatoria) | fija una contraseña nueva a otra cuenta | sí: nuevo reset | **ALTO** | credenciales; nunca loguear la contraseña | APROBADA |

## Primera escritura (vertical slice) — `noConformidades.seguimiento.crear`

**Estado: VALIDADA** (Fase 3a, gateway `0.4.0`, `QCC Web 0.4.0 · Photino 1.8.12 (6c42e05)`). Patrón oficial de
escrituras de la web; lo implementado respecto del diseño original:

- Regla con roles `ROLES_ESCRITURA_OPERATIVA_PENDIENTE_VALIDACION_NEGOCIO` (`operador, admin, admin_ti`),
  `IdentityOverride` `autor ← nombreCompleto` y **recurso auditado** (`ActionPolicy.Regla` con extractor de
  recurso = escritura). El handler arma el cuerpo SOLO con `comentario` validado + `autor` de sesión.
- **Límite de escrituras por usuario**: 30/min (`qcc.web.bridge.escrituras-por-minuto`) → 429 sin tocar la API.
- **Marcado HTML**: se rechaza `<` seguido inmediatamente de letra ASCII, `/`, `!` o `?` (lo único que abre
  etiqueta en el parser HTML); "a < b", "5<6", "<3", "->", acentos y emojis pasan intactos. Se rechaza en vez
  de neutralizar para no guardar texto alterado. Límite 2000 caracteres (code points); control chars rechazados.
- **Lectura del seguimiento**: `seguimiento.list` ahora escapa `comentario`/`autor` (`HtmlUtils.htmlEscape`,
  UTF-8) para que un comentario malicioso guardado desde Photino no se ejecute en el navegador web; innerHTML
  decodifica las entidades, así que el texto se ve idéntico.
- Auditoría `evento=ESCRITURA usuario=<sesión> empresa=INNPACK accion=... recurso=nc:<id> resultado=OK|ERROR ms=..`
  (sin el comentario). Tests: `BridgeFase3aTest` (17). E2E CDP 21/21.

### Segunda escritura — `noConformidades.acciones.crear` — VALIDADA (Fase 3b)

Mismo patrón, con **separación explícita identidad / negocio**:
- **Identidad (siempre `SessionUser`)**: `creadoPor` ← nombre de la sesión (lo que mande el navegador se descarta).
- **Datos de negocio (validados y conservados)**: `responsable` (persona a cargo, texto libre ≤ 150, una línea — puede
  ser OTRA persona, incluso otro usuario), `descripcion` (≤ 500, una línea), `fechaLimite` (DATE `AAAA-MM-DD` válida),
  `prioridad` (`ALTA|MEDIA|BAJA` o vacía → null), `analisisId` (null o el análisis de ESA NC).
- **Lista blanca estricta** de claves (`action, id, analisisId, creadoPor, descripcion, responsable, fechaLimite,
  prioridad`): cualquier otra → `Campo no permitido: <campo>` sin tocar la API. Mensajes de obligatorios = los de la API.
- Sin marcado HTML ni controles en los textos; la lectura `acciones.list` escapa `descripcion`/`responsable`/`prioridad`.
- NC inexistente → sin POST. Photino no bloquea agregar acciones a una NC CERRADA: la web tampoco.
- Auditoría `recurso=nc:<id>` (+ `:accion:<id>` si la API devolviera el id; hoy responde `{}`).
- Tests `BridgeFase3bTest` (15), E2E CDP 23/23.

### Tercera escritura — `noConformidades.analisis.guardar` — VALIDADA (Fase 3c) — SOBRESCRIBE

- **Semántica real**: la API hace upsert del ÚLTIMO análisis de la NC (UPDATE en sitio o INSERT); **no hay historial**
  ni versión/ETag/rowversion (en el port SQL Server `actualizado_en` ni se actualiza) → el valor anterior **no es
  recuperable** (SEC-28). No se inventó rollback.
- **Identidad**: `usuario` (la API lo guarda como `creado_por`/`actualizado_por`) ← sesión.
- **Negocio (lista blanca exacta de Photino)**: `metodologia` ENUM obligatoria (CINCO_PORQUES/ISHIKAWA/MIXTA);
  `problemaDetectado` obligatorio, TEXT ≤ 65.535 bytes UTF-8, multilínea; `porque1..5` opcionales, ≤ 500 unidades
  UTF-16 (VARCHAR/NVARCHAR(500)), una línea; `causaRaiz`/`conclusion` opcionales, TEXT, multilínea. "" se conserva
  (así lo manda Photino); null/ausente → null. Sin marcado HTML ni controles. `analisisId` y cualquier otra clave →
  rechazo. La relectura (`analisis.get`) NO se escapa: la vista usa `.value` (no interpreta HTML).
- **Lost update (web, sin cambiar el contrato)**: `analisis.get` registra en la sesión una huella SHA-256 del análisis
  leído (`LecturasDeSesion`); `analisis.guardar` exige esa lectura y relee antes del PUT: si cambió → conflicto sin
  escribir ("El análisis fue modificado por otra persona..."); tras guardar, la huella se olvida (la vista relee al
  instante). **Límites**: ventana mínima entre relectura y PUT; pestañas de la misma sesión comparten huella;
  Photino no tiene protección (SEC-28). Patrón a reutilizar en toda escritura que sobrescriba.
- **Auditoría**: `recurso=nc:<id>:analisis:<analisisId>:NUEVO|REEMPLAZO` (sin textos del análisis).
- Tests `BridgeFase3cTest` (18), E2E CDP 23/23 con escena A/B real (conflicto en UI, relectura, guardado a sabiendas).

### Cuarta escritura — `noConformidades.catalogos.clientes.crear` — VALIDADA (Fase 3d) — acción DINÁMICA

- **Acción dinámica**: el literal vive en el mapa `_catalogosPlanosConfig()` (`crearAction`) y el payload lo arma
  `_catalogoCrear(action, nombre)` (`{action, nombre, creadoPor: _usuarioActual()}`), invocado por el callback `crear`
  de `CatalogCombo` (`shared/utils.js`: `opciones.crear(input.value.trim())`). El contract check (huella JS) cubre
  ahora: la entrada del mapa de ESTA acción, los usos de la clave (`cfg.crearAction`), el método generador, los
  métodos usados dentro de su `send` (`_usuarioActual`) y el sitio del componente compartido que invoca el callback
  con la declaración de su argumento (el `trim`). Otras entradas del mapa y código visual no alteran la huella.
- **Contrato real (API `NoConformidadesCatalogosService.CrearAsync` + `cat_nc_clientes`)**: `nombre` obligatorio
  ("Falta el nombre"); `Trim` + espacios internos colapsados (`\s+` → " "); ≤ 150 unidades UTF-16 (`string.Length`;
  NVARCHAR(150)/VARCHAR(150)); `UNIQUE(nombre)` con la collation de la BD (no distingue mayúsculas). **Duplicado**:
  la API devuelve el valor existente con su id (y lo **reactiva** si estaba inactivo) con `ok`; el gateway no
  inventa otra política. La respuesta trae `{id, nombre, activo}`.
- **Gateway**: lista blanca `{action, nombre, creadoPor}`; `creadoPor` del navegador se descarta (← sesión); cualquier
  otra clave → rechazo; `nombre` string; mismo trim/colapso que la API antes de medir; una línea sin controles (tab se
  colapsa como en la API) ni sustitutos UTF-16 sueltos; sin marcado HTML (el valor termina en `no_conformidades` y
  vistas con innerHTML, SEC-27). Solo `clientes`: crear en los otros 8 catálogos, desactivar/editar/eliminar siguen
  denegados.
- **Refresco**: el combo agrega el ítem devuelto a su caché y lo selecciona (sin relectura); otra sesión lo ve al
  cargar el catálogo. La UI no muestra `creadoPor`.
- **Límites**: `creado_por` es ≤ 150 y se toma del nombre de la sesión (igual que Photino; un nombre mayor lo
  rechazaría la BD). La reactivación de un valor inactivo ocurre sin aviso (comportamiento de la API).
- **Auditoría**: `recurso=catalogo:clientes:<id>` (sin el nombre).
- Tests `BridgeFase3dTest` (15), contract check Python (+7), E2E CDP 26/26 (combo real, dos sesiones, duplicado,
  HTML/longitud/campo extra, otro catálogo y desactivar denegados, rol consulta).

### Quinta escritura — `noConformidades.catalogos.categoriasDefecto.crear` — VALIDADA (Fase 3e)

- **Diferencias reales con `clientes.crear`: ninguna de contrato.** Entrada del mapa `{campo: "categoria-defecto",
  cacheKey: "ncq-cat-categoria-defecto", listAction/crearAction: categoriasDefecto}`; mismo `_catalogoCrear`, mismo
  `HandleCatalogoCrear("categoriasDefecto", data)` en C#, mismo endpoint/DTO/servicio/repositorio de la API; tabla
  `cat_nc_categorias_defecto` con `nombre` NVARCHAR/VARCHAR(150) `UNIQUE` y `creado_por` (150); mismos duplicados
  (devuelve el existente y lo reactiva), misma respuesta `{id, nombre, activo}`, mismo refresco del combo, mismos roles.
  El valor termina en `no_conformidades.categoria_defecto` y en la descripción armada por Photino; la lista lo pinta
  con innerHTML (SEC-27) → el rechazo de marcado HTML aplica igual.
- **Reutilización**: mismo handler `catalogoCrear`; `BridgeConfig.CATALOGOS_CREAR_HABILITADOS` (lista explícita,
  deny-by-default para el resto). Tests: contrato común `CatalogoCrearBase` (15 casos) instanciado por
  `BridgeFase3dTest` (clientes) y `BridgeFase3eTest` (categoriasDefecto).
- Contract check: huella propia (entrada del mapa de esta acción + método generador + callback compartido); test real
  en 6c42e05 verifica que no incluye la entrada de clientes. E2E CDP 26/26 (y regresión de clientes 26/26).

### Sexta a octava escrituras — `noConformidades.catalogos.{tiposFalla,supervisores,revisores}.crear` — VALIDADAS (Fase 3f)

- **Diferencias reales con `clientes.crear`: ninguna de contrato.** Entradas del mapa `tipo-falla`, `supervisor` y
  `revisado-por` (inputs de texto libre sin `maxlength`); mismo `_catalogoCrear`, mismo `HandleCatalogoCrear` en C#,
  mismo endpoint/servicio/repositorio de la API; tablas `cat_nc_tipos_falla`, `cat_nc_supervisores`, `cat_nc_revisores`
  con `nombre` (150) `UNIQUE` y `creado_por` (150).
- **Identidad vs negocio:** "Supervisor" y "Revisado por" son datos de negocio (como `responsable` en 3b): el `nombre`
  se conserva; solo `creadoPor` ← sesión. Los filtros Supervisor/Revisado por salen de `noConformidades.filtrosOpciones` (NC
  registradas), no de estos catálogos: un valor nuevo aparece en filtros recién cuando una NC lo usa (igual en Photino).
- Implementación: solo `BridgeConfig.CATALOGOS_CREAR_HABILITADOS` + una subclase de `CatalogoCrearBase` por catálogo
  (`BridgeFase3fTiposFallaTest`, `…SupervisoresTest`, `…RevisoresTest`, 15 casos c/u). Huella propia por acción; el
  test real en 6c42e05 verifica que el fragmento de cada catálogo no incluye la entrada de los otros habilitados.

### Novena escritura — `noConformidades.catalogos.areas.crear` — VALIDADA (Fase 3g)

- **Sospecha de alcance descartada:** el combo `area` no tiene `onSeleccionar` (no filtra ni recarga otros combos);
  `#ncq-f-area` es texto libre sin `maxlength` ni valor por defecto. El valor solo se usa al guardar la NC (columna
  `area` y respaldo de `proceso` en `noConformidades.create`, que sigue denegada), en el filtro Área
  (`filtrosOpciones` = DISTINCT sobre NC) y en el indicador "por área" (filas de NC). Sin tablas dependientes de
  `cat_nc_areas` (`nombre` 150 `UNIQUE`, `creado_por` 150).
- Contrato idéntico a 3d–3f: `CATALOGOS_CREAR_HABILITADOS` + `BridgeFase3gAreasTest` (15 casos de `CatalogoCrearBase`);
  el caso "otro catálogo denegado" de la base pasa a `niveles`. Huella propia.

### Décima y undécima escrituras — `noConformidades.catalogos.{familiasProducto,impactos}.crear` — VALIDADAS (Fase 3h)

- Contrato idéntico a 3d–3g salvo el **largo: 50** (API `NoConformidadesCatalogosService`, `cat_nc_*.nombre`
  NVARCHAR(50) y columnas `no_conformidades.familia_producto`/`impacto` NVARCHAR(50)); el gateway ya lo aplicaba.
  Familia alimenta el indicador "por familia" (filas de NC); impacto es texto libre (columna opcional). El valor inicial
  "Calidad" de impacto solo se escribe en el formulario: no crea valores de catálogo.
- Tests: `CatalogoCrearBase` recibe el límite por catálogo (150 por defecto; casos límite, emojis y colapso calculados;
  textos de prueba que caben en 20) y el `FakeInnpackApi` aplica el largo de la API por catálogo.
  `BridgeFase3hFamiliasProductoTest` / `BridgeFase3hImpactosTest` (15 c/u). Huella propia por acción.

### Duodécima escritura — `noConformidades.catalogos.niveles.crear` — VALIDADA con restricción (Fase 3i)

- **Decisión del usuario (2026-09-28): habilitar inicialmente SOLO para `admin_ti`** (Photino: cualquier sesión INNPACK).
  Implementación: `BridgeConfig.ROLES_CATALOGO_CREAR_RESTRINGIDOS = {niveles: [admin_ti]}`; operador/admin reciben
  403 "no disponible" (el combo sigue mostrando "+ Crear" como en Photino y muestra ese mensaje).
- Motivo: el nivel no es texto libre de negocio. `_mapNivelASeveridad` lo convierte en severidad por substring
  (CRIT→ALTA, MAYOR→MEDIA, MENOR→BAJA, **otro→MEDIA**), `_colorSeveridad` pinta gris lo no reconocido y el filtro
  Nivel de la lista tiene opciones FIJAS (Crítico/Mayor/Menor): un nivel nuevo ("Urgente") queda con severidad MEDIA y
  no se puede filtrar. El valor inicial "Mayor" del formulario no crea valores de catálogo.
- Contrato idéntico a 3d–3h, largo **20** (`cat_nc_niveles.nombre` y `no_conformidades.nivel` NVARCHAR(20)).
  Tests: `BridgeFase3iNivelesTest` (15 casos de `CatalogoCrearBase` con roles por catálogo: escritores adminti1/adminti2,
  operador1/admin1/consulta1 → 403).

**Pendiente — alinear con Photino de forma limpia y segura (no implementado):**
1. Acotar el valor a una severidad conocida antes de abrir el rol: validar en el gateway que el nombre normalizado
   mapee a CRIT/MAYOR/MENOR (o exigir la severidad explícita si la API/BD la incorpora en `cat_nc_niveles`), con
   mensaje claro; sin eso un nivel nuevo degrada silenciosamente a MEDIA.
2. Filtro Nivel de la lista alimentado por el catálogo (o por `filtrosOpciones`) en vez de opciones fijas — cambio de
   Photino (backlog, sin tocar `main`) o del shim web, a decidir.
3. Con 1 y 2 resueltos y validados con negocio: pasar `niveles` a la política operativa (operador, admin, admin_ti),
   quitar la entrada de `ROLES_CATALOGO_CREAR_RESTRINGIDOS` y ajustar `BridgeFase3iNivelesTest` a los roles por defecto.

### Decimotercera escritura — `noConformidades.create` ("Nueva NC") — VALIDADA (Fase 3j)

- Photino (`_guardarForm`): 30 campos de `_camposMap` + cabecera armada en el navegador (tipo INTERNA, origen
  AUDITORIA_INTERNA, título `PNC <np> - <producto|cliente>`, descripción `<categoría> - <descripción defecto>`,
  severidad por `_mapNivelASeveridad`, proceso `tipoPnc || area || "PNC Nueva"`, fechaDeteccion = fechaIngreso) +
  `creadoPor`. C# reenvía todo; la API (`POST api/no-conformidades`, `JsonElement`) acepta además `empresa`, `ambito`,
  `reportadoPor`, `norma`, `areasSecundarias`, `tiempoPerdidoHoras` y NO valida largos (exceso → 500 de SQL).
- **Web (más estricta):** lista blanca EXACTA de las 38 claves de Photino (cualquier otra → error sin llamar a la API;
  sin `ambito` no se puede crear una NC INTERNA desde DevTools); `creadoPor` ← sesión; **cabecera recalculada en el
  gateway** (lo recibido se descarta: no se puede mandar severidad BAJA con nivel Crítico); largos de columna de
  `no_conformidades` (título compuesto ≤ 255), textareas ≤ 65.535 bytes, sin controles ni HTML, `tipoPnc`/`disposicion`
  con las opciones exactas de los `<select>`, fechas AAAA-MM-DD, cantidades ≥ 0 dentro de DECIMAL(12,2).
- **Empresa (decisión 1a):** no se envía `empresa` (queda NULL/PRODUCTO como en Photino; el listado INNPACK incluye
  `empresa IS NULL`) y el navegador no puede mandarla. Auditoría `recurso=nc:<id creado>` (`nc:nueva` si falla).
- **Adjuntos (decisión 2a):** Photino sube PDF/fotos DESPUÉS del alta con `adjuntos.subir`, que sigue denegada: la NC
  se crea y la vista informa "hubo un problema subiendo adjuntos" (Photino no revierte la NC). Pendiente Fase 3k.
- **Hallazgo de paridad (no corregido):** `_mapNivelASeveridad("Crítico")` → `"CRÍTICO".includes("CRIT")` es falso por
  la tilde → severidad **MEDIA**, no ALTA. El gateway replica exactamente a Photino; corregirlo es un cambio funcional a
  decidir (backlog Photino + web a la vez).
- Contract check: el literal está en `const action = this._editingId ? "…update" : "…create"`; la huella ahora cubre
  el método hasta el `send` que usa esa variable y los métodos que llama (`_camposMap`, `_leerCampo`,
  `_mapNivelASeveridad`, `_usuarioActual`, `_validarAdjuntosNuevaNc`): un cambio en el armado del payload → REVISAR.

### Decimocuarta escritura — `noConformidades.adjuntos.subir` — VALIDADA (Fase 3k)

- Photino sube desde 4 envíos: alta de NC (PDF + fotos) y modal de análisis (Adjuntar/Reemplazar PDF, Fotos), con
  `{id, tipo, nombreArchivo, tipoMime, contenidoBase64, subidoPor}`. C# reenvía; la API valida NC existente/no cerrada,
  tipo, MIME DECLARADO, base64, tamaño (PDF 10 MB, foto 5 MB) y máx. 10 fotos; reemplazar el PDF marca `eliminado=1`
  el anterior (sin UI para recuperarlo).
- **Tope de cuerpo (decisión 3k):** ruta dedicada `POST /api/v1/bridge/archivo` con `qcc.web.api.max-body-bytes-archivo`
  (14 MB por defecto) que acepta SOLO `ACCIONES_ARCHIVO`; esas acciones se rechazan por `/api/v1/bridge` (256 KB) y el
  shim elige la ruta por acción. Sin cookie de sesión, un cuerpo sobre 256 KB en la ruta de archivos → 401 sin leerlo.
  Máximo `qcc.web.bridge.subidas-simultaneas` (2) subidas en curso → 503 "Hay otras subidas…" (memoria acotada).
- **Web (más estricta):** lista blanca de claves; `subidoPor` ← sesión; tipo/MIME coherentes (PDF → application/pdf;
  foto → image/jpeg|png); base64 estricto; tamaño DECODIFICADO; **firma real** (%PDF-, PNG, JPEG) coherente con el MIME.
- **Nombre (decisión 3k-a, saneo):** Photino lo pinta con innerHTML y Photino escritorio lo lee directo de la API → se
  sanea al SUBIR: nombre base, sin controles/bidi/reservados de Windows, `< > " ' ` & : | ? *` → `_`, ≤ 150 (la lectura
  web ya lo saneaba en adjuntos.list/abrir).
- Auditoría `recurso=nc:<id>:adjunto:<id>` sin nombre ni contenido. `adjuntos.eliminar` sigue denegada (ALTO).
- Tests: `BridgeFase3kTest` (9) + `BridgeFase3kSubidasOcupadasTest` (1), Node (ruta del shim), E2E CDP.

### Decimoquinta escritura — `noConformidades.update` (editar NC) — VALIDADA (Fase 3l)

- Photino: "Ver" (`noConformidades.get`) → "Editar" → `_guardarForm` con `_editingId`: `{action, id, actualizadoPor,
  ...30 campos, ...cabecera}` (misma cabecera recalculada que el alta). El botón Editar aparece también en NC cerradas.
- API (`PUT api/no-conformidades/{id}`): actualización parcial, `empresa` editable, `UPDATE ... WHERE id` SIN verificar
  existencia/borrado/cierre (responde OK aunque no actualice), no toca `fecha_actualizacion` (observación para la API),
  sobrescribe sin historial.
- **Web:** validación y armado COMUNES con create (`cuerpoNc`: lista blanca, largos, cabecera recalculada);
  `actualizadoPor` ← sesión; **lost update** como 3c: `noConformidades.get` registra la huella SHA-256 del detalle leído
  en la sesión (`LecturasDeSesion`, recurso `nc-detalle:<id>`); `update` exige esa lectura ("Abre la no conformidad
  antes de editarla"), relee antes del PUT (404/eliminada → error sin escribir) y rechaza si cambió ("fue modificada por
  otra persona…"); tras guardar hay que reabrir. **NC CERRADA no editable (decisión 3l-a, más estricta que Photino):**
  "La no conformidad está cerrada; no se puede editar." Auditoría `recurso=nc:<id>`.
- Tests: `BridgeFase3lTest` (9: cuerpo exacto, identidad, sin abrir / otra sesión / otra NC, conflicto entre dos
  sesiones, reapertura tras guardar, cerrada/inexistente, lista blanca y validación común, roles, auditoría), E2E CDP.

### Escrituras 16 y 17 — `noConformidades.gestion.actualizar` y `noConformidades.cerrar` (modal "Gestionar") — VALIDADAS (Fase 3m)

- Photino: "Gestionar" abre con `noConformidades.get`; `_guardarGestion` → `{id, responsable, estadoGestion,
  fechaCompromiso, actualizadoPor}` (el `<select>` incluye CERRADA; el modal queda abierto); `_cerrarNc` (confirm) →
  `{id, cerradoPor, comentarioCierre}`. Roles web según la matriz: admin, admin_ti (operador ve el botón y recibe
  "no disponible").
- API: `PATCH …/gestion` actualiza sin verificar existencia ni estado, acepta CERRADA (cierre sin cerradoPor/fecha/
  comentario), permite reabrir una NC cerrada y con estado nulo intenta NULL en columna NOT NULL (500); `POST …/cerrar`
  exige cerradoPor y cierra sin verificar estado (cerrar dos veces sobrescribe quién/cuándo).
- **Web:** identidad de sesión (`actualizadoPor`/`cerradoPor`); listas blancas; gestión: responsable una línea ≤ 150 sin
  HTML, fecha AAAA-MM-DD o vacía, estado obligatorio **sin CERRADA** (decisión 3m-1a: "usa Cerrar NC"), **nunca sobre una
  NC cerrada** (decisión 3m-2a: no se reabre), lost update con la huella de `get` y huella RENOVADA tras guardar (el modal
  sigue abierto); cierre: comentario opcional multilínea ≤ 65.535 bytes sin HTML, relee la NC (404 → error) y rechaza
  si ya está cerrada. `InnpackApiClient.patchJson` (sin reintentos). Auditoría `nc:<id>:gestion:<ESTADO>` / `nc:<id>:cierre`.
- Tests: `BridgeFase3mTest` (7), E2E CDP.

### Escritura 18 — `noConformidades.acciones.actualizar` (estado de acción correctiva) — VALIDADA (Fase 3n)

- **Regla del usuario (2026-09-28): para el usuario la web debe ser exactamente igual que Photino.** Aquí: el usuario
  solo cambia el ESTADO desde el `<select>` de la tabla de acciones (como en Photino), también en NC cerradas
  (decisión 3n-b, paridad).
- Photino (`_actualizarEstadoAccion`) reenvía descripción/responsable/fecha/prioridad copiados de `acciones.list` y NO
  manda el id de la NC; la API (`PUT api/no-conformidades/acciones/{accionId}`) sobrescribe todo sin verificar
  existencia ni pertenencia. Desde 3b la web ESCAPA `acciones.list` → reenviarlo tal cual guardaría `&lt;` (doble
  escape).
- **Web:** `acciones.list` registra en la sesión, ANTES de escapar, cada acción original (NC + JSON); `acciones.actualizar`
  exige esa lectura, toma del navegador SOLO `estado` (PENDIENTE/EN_PROCESO/COMPLETADA/CANCELADA, mismo mensaje que la
  API) y envía los valores ORIGINALES (sin doble escape ni falsificación); `actualizadoPor` ← sesión; relee las acciones
  de la NC antes del PUT (acción inexistente o cambiada → error). Auditoría `nc:<id>:accion:<id>:<ESTADO>`.
- Tests: `BridgeFase3nTest` (7), E2E CDP.

## Fase 3o — auditoría global de paridad (2026-09-28)

Objetivo: que COMPATIBLE/VALIDADA signifique **misma funcionalidad observable que Photino, con seguridad Web
adicional por debajo**. Se auditaron todas las acciones habilitadas (escrituras 3a–3n a mano; lecturas 2a–2m y
transformaciones del shim con un agente de solo lectura) contra Photino `6c42e05` + API. Clases: **A** seguridad
transparente (mantener) · **B** diferencia técnica Desktop vs navegador (mantener y documentar) · **C** divergencia
funcional (corregida) · **D** gestión de usuarios (excepción).

**Corregidas (C):**

| Acción | Divergencia introducida por la web | Ahora (= Photino) |
|---|---|---|
| `noConformidades.update` | NC cerrada no editable (3l-a) | editable |
| `noConformidades.gestion.actualizar` | no aceptaba CERRADA (3m-1a) ni reabría (3m-2a); solo admin | acepta CERRADA, reabre; cualquier usuario INNPACK |
| `noConformidades.cerrar` | rechazaba cerrar una NC ya cerrada; solo admin | vuelve a registrar quién/cuándo/comentario (API); cualquier usuario INNPACK |
| `noConformidades.catalogos.niveles.crear` | solo admin_ti (3i) | cualquier usuario INNPACK |
| `noConformidades.seguimiento.crear` | límite inventado de 2000 caracteres (columna NVARCHAR(MAX)) | tope anti-abuso de 65.535 bytes, como los demás textos largos |
| `noConformidades.create` / `update` | rechazaba cantidades negativas (Photino `min=0` no se valida; la API las acepta) | negativas aceptadas; solo el rango real de DECIMAL(12,2) |
| `noConformidades.adjuntos.abrir` | rechazaba un adjunto si la firma no coincidía con el MIME (p. ej. PNG guardado como .jpg); Photino lo muestra | se muestra, con el tipo REAL detectado por la firma (PDF/PNG/JPEG/GIF/WEBP/BMP); HTML u otros siguen rechazados (A) |
| `controlDocumental.adjunto.abrir`, `muestraLab.adjunto.abrir` | sin vista previa si la firma no coincidía con el MIME | vista previa en los mismos casos que Photino (MIME image/* o PDF), servida con el tipo real |
| Todas las lecturas/escrituras | timeout hacia la API de 15 s (Photino 30 s) | `read-timeout: 30s`; el shim espera 35 s |

**Se mantienen como seguridad transparente (A):** identidad desde `SessionUser` (autor/creadoPor/actualizadoPor/
cerradoPor/subidoPor); JWT solo server-side; CSRF; listas blancas de claves; tipos y largos REALES de columna (evitan
el 500 de SQL); rechazo de marcado HTML/controles en textos (XSS, SEC-27; texto normal como "5<6" pasa); escape HTML en
seguimiento/acciones.list (innerHTML lo muestra idéntico; 3n envía los originales); saneo de nombres de archivo;
firma real de archivos al subir; tope de cuerpo por ruta y ruta dedicada de archivos; auditoría; límite de 30
escrituras/minuto por usuario (NC + PDF + 10 fotos = 12); detección de concurrencia (lost update) que solo actúa si
otra persona cambió el dato; relectura previa (404 → error en vez del OK vacío de la API); empresa de sesión (siempre
INNPACK, igual al valor fijo de Photino); valores de filtros/selects limitados a las opciones de la vista (solo afecta
payloads manipulados); cabecera de la NC recalculada con la lógica de Photino; `ROLES_INNPACK` = roles reales de la BD
(`operador`, `admin`) + `admin_ti`.

**Diferencias técnicas necesarias (B):** descargas/Excel/PDF en el navegador en vez de guardar en disco y abrir la
aplicación; archivos no previsualizables se descargan; sesión web con expiración (30 min inactiva / 8 h); un 401 de la
API cierra la sesión web; mensajes de error de red genéricos; máximo 2 subidas de archivos simultáneas en el gateway
(503 "inténtalo de nuevo"); Excel con tope de 50 MB.

**Gestión de usuarios (D):** `usuarios.list` solo admin/admin_ti (igual a `UsuariosHandler.IsAdmin`); el resto de
`usuarios.*` se definirá aparte (el usuario está cambiando la gestión de usuarios en Photino).

**Sin confirmar (backlog, requiere datos reales):** CSP `img-src` (`'self' data: blob: https://api.faret.cl`) bloquearía
imágenes de inspección con URL absoluta de otro origen en `ruta_archivo` (revisar con un SELECT de solo lectura);
`recepcion.foto.abrir` con formatos BMP/HEIC o > 10 MB. **Acciones aún no habilitadas visibles en pantallas de lectura**
(no son divergencias de lo COMPATIBLE): `muestraLab.materialesFps`, módulo Trazabilidad, `recepcion.sap.*`,
`muestraLab.consultarNp/consultarRegistroProduccion/resolverBobina`.

## NC Internas — paridad con Photino `dd147ad` (Fase 3r, 2026-09-28)

Photino `6c42e05 → dd147ad` (sigue v1.8.12) agrega **NC Internas** (INNPACK `nc-internas` y FARET `faret-nc-internas`)
sobre las mismas acciones de No Conformidades con `ambito: "INTERNA"`. La web adopta `dd147ad` como referencia.

| Clase | Acciones | Web |
|---|---|---|
| A — lectura existente con parámetros nuevos | `noConformidades.list/resumen/exportar` (filtros `ambito`, `empresa`, `area`, `categoriaDefecto` en el orden de Photino), `filtrosOpciones` (`ambito`, `empresa`) | adaptadas; `empresa` siempre la de la sesión |
| B — lectura nueva | `noConformidades.catalogos.nciAreas.list`, `noConformidades.catalogos.nciTiposDesviacion.list` | habilitadas (`ROLES_INNPACK`) |
| C — escritura existente con payload nuevo | `noConformidades.create` (ámbito INTERNA), `noConformidades.update` (edición interna) | lista blanca propia `CAMPOS_NCI`, largos del esquema, horas 0–9999,99, título `tipo - NP npNv` recalculado, `ambito`/`empresa`/`creadoPor`/`actualizadoPor` de sesión, lost update igual que PNC |
| D — escritura ya usada por Photino y ahora visible en la web | `noConformidades.eliminar`, `noConformidades.adjuntos.eliminar` | VALIDADAS (ver matriz); gestión/cierre/análisis/acciones/seguimiento/adjuntos.subir reutilizan las escrituras ya validadas |
| E — solo FARET | `faret-nc-internas` (API FaretApi) | **pendiente**: la web no tiene sesión FARET |

- **Escape**: la vista NCI escapa con `_esc` y la PNC no; la web solo escapa textos con marcado HTML (`MARCADO_HTML`), así
  el texto normal (`R&D 5<6`) se ve igual que en Photino en ambas vistas, sin doble escape.
- **Supuesto**: el soporte de la API para NC Internas (`ambito`, `empresa`, catálogos `nci*`, `Sql/2026_09_nc_internas.sql`)
  está hoy **sin commitear** en `qualitycontrolinnpack_sqlserver_port`; la web replica Photino `dd147ad` asumiendo esa API
  desplegada. Sin ella, las lecturas internas devuelven PNC/errores de la API igual que Photino.
- Catálogos `nciAreas`/`nciTiposDesviacion`: solo lectura; `.crear/.desactivar` siguen denegadas (Photino no los crea inline).
- Hallazgos Recepción R1–R6 (Fase 3q) siguen documentados, sin cambios.
- Pruebas: `BridgeFase3rTest` (8), E2E CDP NC Internas 24/24 (×3), regresión PNC 3o 20/20 y Recepción 19/19.

## Recepción de Calidad — primera escritura y hallazgos (Fase 3q, 2026-09-28)

### `recepcion.bobinas.muestrear` — VALIDADA (Fase 3q)

- **Photino (= Web para el usuario):** en el detalle del lote (Bobina) se marcan las bobinas (o "Selección aleatoria",
  que exige plan) y "Guardar" envía `{action, data:{loteId, bobinas[{numeroBobina, seleccionTipo, criterioManual}]}}`;
  si la selección manual no calza con el plan se pide el motivo (`prompt`). C# arma `{bobinas, usuario}` con el usuario de
  su sesión; la API (`POST api/recepcion-calidad/{id}/bobinas-muestreadas`) valida que cada bobina pertenezca al lote,
  **REEMPLAZA** la selección (DELETE + INSERT, sin transacción) y pasa `PendienteMuestreo → PendienteLaboratorio`
  (otros estados no cambian). Se puede volver a guardar en cualquier estado y re-seleccionar bobinas ya muestreadas.
  Respuesta `{muestreadas: n}`; la vista reabre el detalle y recarga la lista. Rollback: volver a guardar (el estado no
  retrocede). Roles: cualquier usuario INNPACK (Photino sin gating; API `[Authorize]`).
- **Web — seguridad transparente:** `usuario` ← `SessionUser`; lista blanca (raíz, `data` y cada bobina); tipos;
  número ≤ 100, tipo ∈ {Manual, Aleatoria} (vacío → Manual, como C#), motivo ≤ 255 una línea sin controles ni HTML
  (columnas reales); bobina repetida rechazada (sin UNIQUE en la tabla; solo la manda un payload manipulado); el lote se
  confirma con el detalle de la **empresa de sesión** (id ajeno → no existe); la pertenencia de cada bobina la valida la
  API (mismo mensaje). **Concurrencia:** `recepcion.detalle` registra en la sesión la huella de la selección vigente; el
  guardado exige esa lectura, relee el detalle y, si otra sesión cambió la selección, responde "fue modificada por otra
  persona… vuelve a abrirlo" en vez de pisarla. **Ventana residual (hardening futuro en la API):** entre la relectura y el
  POST (milisegundos) y el DELETE+INSERT no transaccional de la API (dos guardados simultáneos pueden intercalarse).
  Auditoría `recurso=recepcion:<lote>:muestreadas:<n>` (sin números de bobina ni motivo).
- Contract check: la huella cubre el `send` y la declaración de `data`; no sigue la declaración de `tipo`/motivo
  (límite documentado del contract check).
- Tests: `BridgeFase3qTest` (9), E2E CDP.

### Hallazgos en la API INNPACK (backlog, NO corregidos; no bloquean `bobinas.muestrear`)

| # | Hallazgo | Evidencia | Impacto |
|---|---|---|---|
| R1 | `REPLACE INTO recepcion_plan_muestreo` no es T-SQL (la API corre sobre SQL Server) | `RecepcionCalidadRepository.cs:534` | `recepcion.plan.generar` fallaría siempre (también en Photino) |
| R2 | `(foto IS NOT NULL) AS tiene_foto` no es T-SQL | `RecepcionCalidadRepository.cs:331, 358` | el detalle de lotes PVA/PliegoFaret fallaría (y con él `foto.abrir` en web y el refresco tras crearlos) |
| R3 | Sin transacciones en escrituras multi-sentencia | crear (lote + bobinas + PVA/Pliego), muestrear (DELETE + INSERT), muestra.crear y nc.crear (INSERT + UPDATE) | datos parciales ante un error intermedio |
| R4 | Carreras / duplicados | muestrear (dos guardados intercalados), nc.crear (verificación → escritura: 2 NC), muestra.crear (duplica) | mitigado en web solo para muestrear (detección de cambios) |
| R5 | La API no filtra por empresa ni `eliminado` en nc/plan/muestrear/estado/foto | `RecepcionCalidadRepository.cs` | la web confirma el lote con el detalle de la empresa de sesión antes de escribir |
| R6 | `foto_mime` siempre `image/jpeg` | `RecepcionCalidadRepository.cs:83, 113` | la web detecta el tipo real (ajuste BMP/ICO/AVIF y 25 MB propuesto, pendiente) |

Confirmar R1/R2 en producción requiere acceso de solo lectura a SQL Server `calidad_db` (o el log de la API).

Diseño aprobado de la primera escritura (referencia):

### Por qué esta
- **Aditiva y append-only**: inserta un comentario en `nc_seguimiento`; no cambia estados, no borra, no toca
  cantidades ni maestros. Si algo sale mal, el daño máximo es un comentario de más.
- **Demuestra exactamente lo que hay que probar**: el autor (`autor`) hoy lo manda el navegador (SEC-12) y la
  pantalla lo muestra en la lista → se puede ver en E2E que el gateway lo fija desde la sesión aunque se
  manipule `sessionStorage.nombreUsuario` o el payload.
- **Refresco de UI inmediato y verificable**: tras guardar, la vista vuelve a pedir `seguimiento.list`
  (ya habilitada) y pinta comentario + autor + fecha.
- **Una sola llamada upstream**, sin archivos, sin empresa en el payload, sin concurrencia optimista.
- Descartadas como primera: `muestraLab.*.guardar` (13 variantes, lógica de bobinas), `recepcion.crear` (foto +
  SAP), `noConformidades.create` (payload completo → lista blanca grande), catálogos (el JS arma la acción de forma
  dinámica), `talleresExternos.create` (payload grande + versión).

### Diseño
| Aspecto | Propuesta |
|---|---|
| **Autorización** | Regla `noConformidades.seguimiento.crear` en `ActionPolicy`: empresa `INNPACK`, roles `operador, admin, admin_ti`. Sesión obligatoria, CSRF obligatorio (ya existentes). Otro rol/empresa → 403 sin llamar a la API + `ACCION_DENEGADA` en auditoría. |
| **IdentityOverride** | `autor` ← `SessionUser.nombreCompleto` (raíz y `data`; el payload de NC es plano). Cualquier `autor`, `usuarioId`, `rol`, `empresa` enviados por el navegador se ignoran o sobrescriben. |
| **Validaciones (gateway)** | `id`: `TryGetInt` de Photino, > 0 → si no, `Falta el id de la no conformidad`. `comentario`: string, `trim` no vacío (`Falta el comentario de seguimiento`, mismo texto que la API), ≤ 2000 caracteres. **Defensiva**: rechazar `<` y `>` (`El comentario no puede contener < ni >.`) porque la vista de Photino pinta el comentario con `innerHTML` sin escapar (XSS almacenado, ver SEC-27). **Existencia**: `GET api/no-conformidades/{id}` antes del POST (evita el 500 por FK y comentar una NC eliminada → `No conformidad no encontrada`). Tipos inválidos (bool/objeto/array) → `Parámetro inválido.` |
| **API llamada** | `POST api/no-conformidades/{id}/seguimiento` `{ comentario, autor }` → `ApiResponse { id }`; la vista espera `ok` y recarga. |
| **Auditoría** | Nuevo evento `qcc.audit` `evento=ESCRITURA usuario=<codigo> empresa=INNPACK accion=noConformidades.seguimiento.crear recurso=nc:<id> resultado=OK/ERROR ms=..` (sin el texto del comentario). Es el registro de autor confiable mientras la API siga aceptando el autor del body. |
| **Concurrencia** | Append-only: dos sesiones pueden comentar la misma NC sin conflicto. Doble clic → dos comentarios (igual que Photino); el gateway no reintenta automáticamente ante timeout (evita duplicados silenciosos). Opcional: deduplicar mismo usuario + NC + texto en 10 s. |
| **Error / rollback** | Error de negocio de la API → su mensaje; 401 upstream → invalida solo esa sesión; timeout/5xx → `Error al comunicarse con la API Innpack` sin reintento. Sin rollback automático: el comentario es inmutable por diseño; se corrige con otro comentario. Borrado solo por DBA (no hay endpoint). |
| **Contract check** | Aprobar huella C# (`HandleSeguimientoCrear` + `SeguimientoCrearAsync`) y huella JS (`_agregarSeguimiento`: claves `id, comentario, autor`). Si Photino cambia el payload → REVISAR. |
| **Tests (MockMvc + FakeInnpackApi)** | autor falsificado en raíz/`data` → la API recibe el nombre de la sesión; rol `consulta` y empresa FARET → 403 sin POST; id/comentario inválidos, vacío, > 2000, con HTML → error sin POST; NC inexistente → sin POST; CSRF; 401 invalida solo esa sesión; 2 sesiones concurrentes → cada comentario con su autor; auditoría con `recurso=nc:<id>` y sin el comentario; body del POST exacto (`{comentario, autor}`). |
| **E2E (CDP)** | login operador → No Conformidades → Gestión → escribir comentario → Agregar: POST con `autor` = nombre de sesión aunque `sessionStorage.nombreUsuario` se cambie a "Hacker" en DevTools; la lista se refresca con el comentario y el autor real; `PhotinoBridge.send` forzado con `autor:"X"` → la API recibe el de sesión; comentario con `<img onerror>` → rechazado; usuario `consulta` → "no disponible"; sin token en storage; auditoría presente en el log del gateway. |

## Hallazgos de seguridad para el backlog de hardening de Photino/APIs

Referenciados contra `docs/SECURITY_HARDENING_BACKLOG.md` del repo Photino (rama `fase-1a-gateway`, commit `27f75fd`).
**No se corrigen en esta fase**; la web ya los mitiga en su propio perímetro.

| Hallazgo | Backlog Photino | Mitigación en la web |
|---|---|---|
| APIs INNPACK sin autorización por rol (solo `[Authorize]`, salvo Usuarios) | **SEC-11** (ya registrado) | roles explícitos por acción en `ActionPolicy` (esta matriz) |
| Autor/identidad tomado del payload en vez del JWT (`creadoPor`, `actualizadoPor`, `cerradoPor`, `autor`, `subidoPor`, `usuario`, `usuarioNombre`, `usuarioId`) | **SEC-12** (ya registrado) | `IdentityOverride` desde `SessionUser` en cada escritura |
| Operaciones que **no registran quién** las ejecutó (29 escrituras: validar/rechazar/eliminar inspecciones y registros, masivos, frecuencias, eliminar/anular en Laboratorio, maestros, estado de recepción, catálogos de talleres, `usuarios.create/resetPassword`) | **SEC-26** (registrado 2026-09-25, rama `fase-1a-gateway` `70c3fb1`) | evento `ESCRITURA` obligatorio en la auditoría del gateway |
| Escrituras que sobrescriben sin versión/ETag ni historial (lost update; p. ej. `analisis.guardar`) | **SEC-28** (registrado 2026-09-25, `fase-1a-gateway` `da868a0`) | huella de lectura por sesión + relectura antes del PUT (`LecturasDeSesion`) |
| Texto de usuario pintado con `innerHTML` sin escapar en No Conformidades (p. ej. comentario de seguimiento, cliente/campos de la grilla, nombres de adjuntos) → XSS almacenado que afecta a Photino (WebView2) y a la web | **SEC-27** (registrado 2026-09-25, rama `fase-1a-gateway` `70c3fb1`) | nombres de adjuntos ya saneados (2i); en escrituras, rechazo de marcado HTML en textos libres; en `seguimiento.list`, escape de `comentario`/`autor` |

Registrados en `docs/SECURITY_HARDENING_BACKLOG.md` del repo Photino (rama de documentación `fase-1a-gateway`,
commit `70c3fb1`; `main` sin tocar, solo documentación, estado PENDIENTE). Resumen:

- **SEC-26 — Operaciones de escritura sin registro de autor — Media — PENDIENTE.** Componente: API INNPACK
  (`DashboardController`, `RegistrosControlController`, `RegistrosProduccionController`, `HomeController`
  frecuencias, `MuestraLaboratorioController` eliminar/anular ensayo/maestros, `RecepcionCalidadController` estado,
  `TalleresExternosController` catálogos, `ProductoTerminadoController` eliminar). La API no recibe ni deduce
  (JWT) el usuario: no queda traza de quién validó, rechazó o eliminó. Recomendación: tomar el `sub` del JWT y
  persistir usuario + fecha en cada operación de escritura.
- **SEC-27 — XSS almacenado en vistas que pintan datos con `innerHTML` — Media — PENDIENTE.** Componente: frontend
  Photino `modules/no-conformidades/no-conformidades.controller.js` (`_cargarSeguimiento`: `${c.comentario}`,
  `${c.autor}`; grilla y adjuntos) y otras vistas con el mismo patrón. Un texto guardado con HTML se ejecuta al
  mostrarse (WebView2/navegador). Recomendación: escapar todo dato antes de `innerHTML` (ya existe `_esc` en otros
  controllers) o usar `textContent`.
