# Matriz de autorización de escrituras — QCC Web

**Estado del documento:** política técnica inicial de la web, **aprobada por el usuario el 2026-09-25**.
**Validación funcional de roles: `PENDIENTE_VALIDACION_NEGOCIO`** (aplica a TODAS las filas, incluida la ya
implementada): los roles pueden cambiar cuando el negocio confirme los permisos definitivos.

Escrituras habilitadas en la web: **1** (`noConformidades.seguimiento.crear`, estado VALIDADA). Las otras 80
siguen denegadas por `ActionPolicy` (deny-by-default) hasta su propia fase.

- Evidencia: Photino `6c42e05` (v1.8.12) — `MessageRouter`, handlers, `InnpackApi/*ApiService`, controllers JS;
  API INNPACK `qualitycontrolinnpack_sqlserver_port` (atributos `[Authorize]`, servicios). Extraída con el
  inventario del contract check y revisada a mano en los casos dudosos.
- Generada el 2026-09-25. Toda modificación posterior se hace editando este archivo (y el estado de cada fila).

## Principios de la política Web (más estricta que la actual)

La autorización de Photino y de la API **no se copia**: hoy cualquier sesión INNPACK puede ejecutar cualquier
escritura salvo `usuarios.*` (backlog Photino SEC-11), la identidad del autor llega del cliente (SEC-12) y
varias operaciones no registran autor (SEC-26 propuesto). La web aplica, por acción:

1. **Rol explícito en `ActionPolicy`** (lista cerrada; un rol nuevo no entra solo):
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
6. **Nunca más permisivo que Photino/API**; puede ser más restrictivo. Web nunca bloquea a Photino.

**Estados:** `PENDIENTE` (propuesta) → `APROBADA` (política técnica aprobada; roles sujetos a
`PENDIENTE_VALIDACION_NEGOCIO`) → `IMPLEMENTADA` (regla + handler + tests en el gateway) → `VALIDADA` (E2E +
contract check C#/JS + gate de release).

## Resumen

| | Cantidad |
|---|---|
| Escrituras INNPACK pendientes | **81** |
| Riesgo BAJO / MEDIO / ALTO | **36 / 26 / 19** |
| Roles web `operador, admin, admin_ti` / `admin, admin_ti` / `admin` | 39 / 38 / 4 |
| Sin autor registrado por la API (solo auditoría gateway) | 29 |
| Autor tomado del cliente (a sobrescribir) | 25 |
| Estado | 80 APROBADA · 1 VALIDADA — roles `PENDIENTE_VALIDACION_NEGOCIO` |

## Matriz por módulo

Columnas: método/endpoint (API INNPACK) · empresa · **roles Web propuestos** · autorización actual en Photino y en
la API · identidad que hoy llega del cliente · qué fija el gateway desde `SessionUser` · efecto · rollback · riesgo ·
justificación · estado.

### controlDocumental (5)

| action | método · endpoint | empresa | roles Web | Photino hoy | API hoy | identidad desde cliente | Web fija desde SessionUser | efecto | rollback | riesgo | justificación | estado |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `controlDocumental.adjunto.subir` | POST `api/control-documental/adjunto/{versionId}` | INNPACK; `alcanceEmpresa` = dato de negocio (validar valores) | **admin, admin_ti** | cualquier sesión INNPACK (sin gating de rol) | `[Authorize]` (cualquier JWT) | cliente: `subidoPor` | `subidoPor` ← nombreCompleto | adjunta archivo a una versión (≤ 25 MB) | sí: reemplazar/nueva versión | **MEDIO** | cambia el documento vigente que ven todos; validar tipo/tamaño en gateway | APROBADA |
| `controlDocumental.create` | POST `api/control-documental` | INNPACK; `alcanceEmpresa` = dato de negocio (validar valores) | **admin, admin_ti** | cualquier sesión INNPACK (sin gating de rol) | `[Authorize]` (cualquier JWT) | cliente: `creadoPor` | `creadoPor` ← nombreCompleto; payload completo pasa a la API → lista blanca de campos | crea documento/versión controlada | sí: nueva versión o eliminar (admin) | **MEDIO** | documentos del sistema de calidad: solo quien administra el control documental | APROBADA |
| `controlDocumental.update` | PUT `api/control-documental/{id}` | INNPACK; `alcanceEmpresa` = dato de negocio (validar valores) | **admin, admin_ti** | cualquier sesión INNPACK (sin gating de rol) | `[Authorize]` (cualquier JWT) | cliente: `actualizadoPor` | `actualizadoPor` ← nombreCompleto; payload completo pasa a la API → lista blanca de campos | edita metadatos del documento | sí: volver a editar | **MEDIO** | documento controlado | APROBADA |
| `controlDocumental.version.crear` | POST `api/control-documental/{documentoId}/version` | INNPACK; `alcanceEmpresa` = dato de negocio (validar valores) | **admin, admin_ti** | cualquier sesión INNPACK (sin gating de rol) | `[Authorize]` (cualquier JWT) | cliente: `creadoPor` | `creadoPor` ← nombreCompleto | crea documento/versión controlada | sí: nueva versión o eliminar (admin) | **MEDIO** | documentos del sistema de calidad: solo quien administra el control documental | APROBADA |
| `controlDocumental.eliminar` | DELETE `api/control-documental/{id}` | INNPACK; `alcanceEmpresa` = dato de negocio (validar valores) | **admin, admin_ti** | cualquier sesión INNPACK (sin gating de rol) | `[Authorize]` (cualquier JWT) | cliente: `actualizadoPor` | `actualizadoPor` ← nombreCompleto | elimina el documento (lógico, por verificar) | no desde la UI | **ALTO** | destructivo sobre registro controlado | APROBADA |

### dashboard (5)

| action | método · endpoint | empresa | roles Web | Photino hoy | API hoy | identidad desde cliente | Web fija desde SessionUser | efecto | rollback | riesgo | justificación | estado |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `dashboard.rechazarRegistro` | PUT `api/dashboard/{id}/rechazar` | INNPACK (sin parámetro) | **admin, admin_ti** | cualquier sesión INNPACK (sin gating de rol) | `[Authorize]` (cualquier JWT) | ninguna (la API no registra autor) | — (auditoría del gateway obligatoria) | cambia estado de una inspección de calidad | re-operando; sin historial de autor | **MEDIO** | decisión de supervisión; la API no guarda quién → auditoría obligatoria en gateway (SEC-26) | APROBADA |
| `dashboard.validarRegistro` | PUT `api/dashboard/{id}/validar` | INNPACK (sin parámetro) | **admin, admin_ti** | cualquier sesión INNPACK (sin gating de rol) | `[Authorize]` (cualquier JWT) | ninguna (la API no registra autor) | — (auditoría del gateway obligatoria) | cambia estado de una inspección de calidad | re-operando; sin historial de autor | **MEDIO** | decisión de supervisión; la API no guarda quién → auditoría obligatoria en gateway (SEC-26) | APROBADA |
| `dashboard.eliminarRegistro` | DELETE `api/dashboard/{id}` | INNPACK (sin parámetro) | **admin, admin_ti** | cualquier sesión INNPACK (sin gating de rol) | `[Authorize]` (cualquier JWT) | ninguna (la API no registra autor) | — (auditoría del gateway obligatoria) | elimina una inspección | no desde la UI | **ALTO** | destructivo y sin autor registrado | APROBADA |
| `dashboard.rechazarTodo` | PUT `api/dashboard/rechazar-todo` | INNPACK (sin parámetro) | **admin** | cualquier sesión INNPACK (sin gating de rol) | `[Authorize]` (cualquier JWT) | ninguna (la API no registra autor) | — (auditoría del gateway obligatoria) | valida/rechaza TODOS los registros pendientes filtrados | no (masivo) | **ALTO** | masivo e irreversible en la práctica; solo admin y con confirmación explícita | APROBADA |
| `dashboard.validarTodo` | PUT `api/dashboard/validar-todo` | INNPACK (sin parámetro) | **admin** | cualquier sesión INNPACK (sin gating de rol) | `[Authorize]` (cualquier JWT) | ninguna (la API no registra autor) | — (auditoría del gateway obligatoria) | valida/rechaza TODOS los registros pendientes filtrados | no (masivo) | **ALTO** | masivo e irreversible en la práctica; solo admin y con confirmación explícita | APROBADA |

### inicio (1)

| action | método · endpoint | empresa | roles Web | Photino hoy | API hoy | identidad desde cliente | Web fija desde SessionUser | efecto | rollback | riesgo | justificación | estado |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `inicio.frecuencias.actualizar` | PUT `api/home/frecuencias/{id}` | INNPACK (sin parámetro) | **admin, admin_ti** | cualquier sesión INNPACK (sin gating de rol) | `[Authorize]` (cualquier JWT) | ninguna (la API no registra autor) | — (auditoría del gateway obligatoria) | cambia la frecuencia objetivo de control por proceso | sí: volver a editar | **MEDIO** | configuración global; sin autor en API | APROBADA |

### muestraLab (25)

| action | método · endpoint | empresa | roles Web | Photino hoy | API hoy | identidad desde cliente | Web fija desde SessionUser | efecto | rollback | riesgo | justificación | estado |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `muestraLab.adjunto.subir` | POST `api/muestra-laboratorio/{muestraId}/adjunto` | INNPACK (sin parámetro) | **operador, admin, admin_ti** | cualquier sesión INNPACK (sin gating de rol) | `[Authorize]` (cualquier JWT) | sesión C# de Photino: NombreCompleto | inyectar nombreCompleto (Photino lo toma de su sesión) | adjunta archivo/foto a la muestra (≤ 10 MB) | sí: eliminar adjunto (admin) | **BAJO** | evidencia aditiva | APROBADA |
| `muestraLab.bctMedido.guardar` | POST `api/muestra-laboratorio/bct-medido` | INNPACK (sin parámetro) | **operador, admin, admin_ti** | cualquier sesión INNPACK (sin gating de rol) | `[Authorize]` (cualquier JWT) | sesión C# de Photino: Id, NombreCompleto | inyectar userId (Photino lo toma de su sesión); inyectar nombreCompleto (Photino lo toma de su sesión) | registra un ensayo sobre una muestra | sí: anular ensayo (admin) | **BAJO** | registro operativo; la evaluación la calcula la API | APROBADA |
| `muestraLab.bctTeorico.guardar` | POST `api/muestra-laboratorio/bct-teorico` | INNPACK (sin parámetro) | **operador, admin, admin_ti** | cualquier sesión INNPACK (sin gating de rol) | `[Authorize]` (cualquier JWT) | sesión C# de Photino: Id, NombreCompleto | inyectar userId (Photino lo toma de su sesión); inyectar nombreCompleto (Photino lo toma de su sesión) | registra un ensayo sobre una muestra | sí: anular ensayo (admin) | **BAJO** | registro operativo; la evaluación la calcula la API | APROBADA |
| `muestraLab.cobb.guardar` | POST `api/muestra-laboratorio/cobb` | INNPACK (sin parámetro) | **operador, admin, admin_ti** | cualquier sesión INNPACK (sin gating de rol) | `[Authorize]` (cualquier JWT) | sesión C# de Photino: Id, NombreCompleto | inyectar userId (Photino lo toma de su sesión); inyectar nombreCompleto (Photino lo toma de su sesión) | registra un ensayo sobre una muestra | sí: anular ensayo (admin) | **BAJO** | registro operativo; la evaluación la calcula la API | APROBADA |
| `muestraLab.crear` | POST `api/muestra-laboratorio` | INNPACK (sin parámetro) | **operador, admin, admin_ti** | cualquier sesión INNPACK (sin gating de rol) | `[Authorize]` (cualquier JWT) | sesión C# de Photino: Id, NombreCompleto | inyectar userId (Photino lo toma de su sesión); inyectar nombreCompleto (Photino lo toma de su sesión) | crea una muestra de laboratorio | sí: anular/eliminar (admin) | **BAJO** | registro operativo del analista; autor desde sesión | APROBADA |
| `muestraLab.ect.guardar` | POST `api/muestra-laboratorio/ect` | INNPACK (sin parámetro) | **operador, admin, admin_ti** | cualquier sesión INNPACK (sin gating de rol) | `[Authorize]` (cualquier JWT) | sesión C# de Photino: Id, NombreCompleto | inyectar userId (Photino lo toma de su sesión); inyectar nombreCompleto (Photino lo toma de su sesión) | registra un ensayo sobre una muestra | sí: anular ensayo (admin) | **BAJO** | registro operativo; la evaluación la calcula la API | APROBADA |
| `muestraLab.espesor.guardar` | POST `api/muestra-laboratorio/espesor` | INNPACK (sin parámetro) | **operador, admin, admin_ti** | cualquier sesión INNPACK (sin gating de rol) | `[Authorize]` (cualquier JWT) | sesión C# de Photino: Id, NombreCompleto | inyectar userId (Photino lo toma de su sesión); inyectar nombreCompleto (Photino lo toma de su sesión) | registra un ensayo sobre una muestra | sí: anular ensayo (admin) | **BAJO** | registro operativo; la evaluación la calcula la API | APROBADA |
| `muestraLab.fct.guardar` | POST; POST `api/muestra-laboratorio/fct; api/muestra-laboratorio/rct` | INNPACK (sin parámetro) | **operador, admin, admin_ti** | cualquier sesión INNPACK (sin gating de rol) | `[Authorize]` (cualquier JWT) | sesión C# de Photino: Id, NombreCompleto | inyectar userId (Photino lo toma de su sesión); inyectar nombreCompleto (Photino lo toma de su sesión) | registra un ensayo sobre una muestra | sí: anular ensayo (admin) | **BAJO** | registro operativo; la evaluación la calcula la API | APROBADA |
| `muestraLab.gramaje.guardar` | POST `api/muestra-laboratorio/gramaje` | INNPACK (sin parámetro) | **operador, admin, admin_ti** | cualquier sesión INNPACK (sin gating de rol) | `[Authorize]` (cualquier JWT) | sesión C# de Photino: Id, NombreCompleto | inyectar userId (Photino lo toma de su sesión); inyectar nombreCompleto (Photino lo toma de su sesión) | registra un ensayo sobre una muestra | sí: anular ensayo (admin) | **BAJO** | registro operativo; la evaluación la calcula la API | APROBADA |
| `muestraLab.humedad.guardar` | POST `api/muestra-laboratorio/humedad` | INNPACK (sin parámetro) | **operador, admin, admin_ti** | cualquier sesión INNPACK (sin gating de rol) | `[Authorize]` (cualquier JWT) | sesión C# de Photino: Id, NombreCompleto | inyectar userId (Photino lo toma de su sesión); inyectar nombreCompleto (Photino lo toma de su sesión) | registra un ensayo sobre una muestra | sí: anular ensayo (admin) | **BAJO** | registro operativo; la evaluación la calcula la API | APROBADA |
| `muestraLab.lugol.guardar` | POST `api/muestra-laboratorio/lugol` | INNPACK (sin parámetro) | **operador, admin, admin_ti** | cualquier sesión INNPACK (sin gating de rol) | `[Authorize]` (cualquier JWT) | sesión C# de Photino: Id, NombreCompleto | inyectar userId (Photino lo toma de su sesión); inyectar nombreCompleto (Photino lo toma de su sesión) | registra un ensayo sobre una muestra | sí: anular ensayo (admin) | **BAJO** | registro operativo; la evaluación la calcula la API | APROBADA |
| `muestraLab.nc.crear` | POST `api/muestra-laboratorio/{muestraId}/nc` | INNPACK (sin parámetro) | **operador, admin, admin_ti** | cualquier sesión INNPACK (sin gating de rol) | `[Authorize]` (cualquier JWT) | sesión C# de Photino: NombreCompleto | inyectar nombreCompleto (Photino lo toma de su sesión) | crea una NC vinculada a la muestra | sí: eliminar NC (admin) | **BAJO** | aditivo; autor desde sesión | APROBADA |
| `muestraLab.ph.guardar` | POST `api/muestra-laboratorio/ph` | INNPACK (sin parámetro) | **operador, admin, admin_ti** | cualquier sesión INNPACK (sin gating de rol) | `[Authorize]` (cualquier JWT) | sesión C# de Photino: Id, NombreCompleto | inyectar userId (Photino lo toma de su sesión); inyectar nombreCompleto (Photino lo toma de su sesión) | registra un ensayo sobre una muestra | sí: anular ensayo (admin) | **BAJO** | registro operativo; la evaluación la calcula la API | APROBADA |
| `muestraLab.rct.guardar` | POST; POST `api/muestra-laboratorio/fct; api/muestra-laboratorio/rct` | INNPACK (sin parámetro) | **operador, admin, admin_ti** | cualquier sesión INNPACK (sin gating de rol) | `[Authorize]` (cualquier JWT) | sesión C# de Photino: Id, NombreCompleto | inyectar userId (Photino lo toma de su sesión); inyectar nombreCompleto (Photino lo toma de su sesión) | registra un ensayo sobre una muestra | sí: anular ensayo (admin) | **BAJO** | registro operativo; la evaluación la calcula la API | APROBADA |
| `muestraLab.solidos.guardar` | POST `api/muestra-laboratorio/solidos` | INNPACK (sin parámetro) | **operador, admin, admin_ti** | cualquier sesión INNPACK (sin gating de rol) | `[Authorize]` (cualquier JWT) | sesión C# de Photino: Id, NombreCompleto | inyectar userId (Photino lo toma de su sesión); inyectar nombreCompleto (Photino lo toma de su sesión) | registra un ensayo sobre una muestra | sí: anular ensayo (admin) | **BAJO** | registro operativo; la evaluación la calcula la API | APROBADA |
| `muestraLab.viscosidad.guardar` | POST `api/muestra-laboratorio/viscosidad` | INNPACK (sin parámetro) | **operador, admin, admin_ti** | cualquier sesión INNPACK (sin gating de rol) | `[Authorize]` (cualquier JWT) | sesión C# de Photino: Id, NombreCompleto | inyectar userId (Photino lo toma de su sesión); inyectar nombreCompleto (Photino lo toma de su sesión) | registra un ensayo sobre una muestra | sí: anular ensayo (admin) | **BAJO** | registro operativo; la evaluación la calcula la API | APROBADA |
| `muestraLab.actualizarFechaEnsayo` | PUT `api/muestra-laboratorio/{id}/fecha-ensayo` | INNPACK (sin parámetro) | **admin, admin_ti** | cualquier sesión INNPACK (sin gating de rol) | `[Authorize]` (cualquier JWT) | sesión C# de Photino: NombreCompleto | inyectar nombreCompleto (Photino lo toma de su sesión) | corrige la fecha efectiva de ensayo (auditada por la API) | sí: volver a corregir | **MEDIO** | altera trazabilidad temporal | APROBADA |
| `muestraLab.anular` | POST `api/muestra-laboratorio/{muestraId}/anular` | INNPACK (sin parámetro) | **admin, admin_ti** | cualquier sesión INNPACK (sin gating de rol) | `[Authorize]` (cualquier JWT) | sesión C# de Photino: NombreCompleto | inyectar nombreCompleto (Photino lo toma de su sesión) | anula el registro completo (conserva historial) | no hay 'desanular' en la UI | **MEDIO** | cambio de estado controlado, con motivo | APROBADA |
| `muestraLab.ensayo.anular` | POST `api/muestra-laboratorio/ensayos/{ensayoId}/anular` | INNPACK (sin parámetro) | **admin, admin_ti** | cualquier sesión INNPACK (sin gating de rol) | `[Authorize]` (cualquier JWT) | ninguna (la API no registra autor) | — (auditoría del gateway obligatoria) | anula un ensayo puntual | no hay 'desanular' | **MEDIO** | afecta la evaluación de la muestra; sin autor | APROBADA |
| `muestraLab.especificacion.activar` | PATCH `api/muestra-laboratorio/especificaciones/{id}/activo` | INNPACK (sin parámetro) | **admin, admin_ti** | cualquier sesión INNPACK (sin gating de rol) | `[Authorize]` (cualquier JWT) | ninguna (la API no registra autor) | — (auditoría del gateway obligatoria) | edita maestro de métodos/especificaciones | sí: volver a editar/activar | **MEDIO** | maestro que cambia evaluaciones futuras | APROBADA |
| `muestraLab.especificacion.guardar` | POST `api/muestra-laboratorio/especificaciones` | INNPACK (sin parámetro) | **admin, admin_ti** | cualquier sesión INNPACK (sin gating de rol) | `[Authorize]` (cualquier JWT) | ninguna (la API no registra autor) | — (auditoría del gateway obligatoria) | edita maestro de métodos/especificaciones | sí: volver a editar/activar | **MEDIO** | maestro que cambia evaluaciones futuras | APROBADA |
| `muestraLab.metodo.activar` | PATCH `api/muestra-laboratorio/metodos/{id}/activo` | INNPACK (sin parámetro) | **admin, admin_ti** | cualquier sesión INNPACK (sin gating de rol) | `[Authorize]` (cualquier JWT) | ninguna (la API no registra autor) | — (auditoría del gateway obligatoria) | edita maestro de métodos/especificaciones | sí: volver a editar/activar | **MEDIO** | maestro que cambia evaluaciones futuras | APROBADA |
| `muestraLab.metodo.guardar` | POST `api/muestra-laboratorio/metodos` | INNPACK (sin parámetro) | **admin, admin_ti** | cualquier sesión INNPACK (sin gating de rol) | `[Authorize]` (cualquier JWT) | sesión C# de Photino: NombreCompleto | inyectar nombreCompleto (Photino lo toma de su sesión) | edita maestro de métodos/especificaciones | sí: volver a editar/activar | **MEDIO** | maestro que cambia evaluaciones futuras | APROBADA |
| `muestraLab.adjunto.eliminar` | DELETE `api/muestra-laboratorio/adjunto/{adjuntoId}` | INNPACK (sin parámetro) | **admin, admin_ti** | cualquier sesión INNPACK (sin gating de rol) | `[Authorize]` (cualquier JWT) | ninguna (la API no registra autor) | — (auditoría del gateway obligatoria) | elimina un adjunto | no desde la UI | **ALTO** | destructivo; sin autor | APROBADA |
| `muestraLab.eliminar` | DELETE `api/muestra-laboratorio/{muestraId}` | INNPACK (sin parámetro) | **admin, admin_ti** | cualquier sesión INNPACK (sin gating de rol) | `[Authorize]` (cualquier JWT) | ninguna (la API no registra autor) | — (auditoría del gateway obligatoria) | borrado lógico de la muestra | no desde la UI | **ALTO** | destructivo; sin autor | APROBADA |

### noConformidades (20)

| action | método · endpoint | empresa | roles Web | Photino hoy | API hoy | identidad desde cliente | Web fija desde SessionUser | efecto | rollback | riesgo | justificación | estado |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `noConformidades.acciones.crear` | POST `api/no-conformidades/{id}/acciones` | INNPACK (sin parámetro) | **operador, admin, admin_ti** | cualquier sesión INNPACK (sin gating de rol) | `[Authorize]` (cualquier JWT) | cliente: `creadoPor` | `creadoPor` ← nombreCompleto; NO tocar: responsable (dato de negocio) | crea una acción correctiva | sí: actualizar estado | **BAJO** | aditivo; responsable = dato de negocio | APROBADA |
| `noConformidades.adjuntos.subir` | POST `api/no-conformidades/{id}/adjuntos` | INNPACK (sin parámetro) | **operador, admin, admin_ti** | cualquier sesión INNPACK (sin gating de rol) | `[Authorize]` (cualquier JWT) | cliente: `subidoPor` | `subidoPor` ← nombreCompleto | adjunta PDF de causa raíz / fotos (API valida MIME y tamaño) | sí: eliminar adjunto (admin) | **BAJO** | evidencia aditiva | APROBADA |
| `noConformidades.analisis.guardar` | PUT `api/no-conformidades/{id}/analisis` | INNPACK (sin parámetro) | **operador, admin, admin_ti** | cualquier sesión INNPACK (sin gating de rol) | `[Authorize]` (cualquier JWT) | cliente: `usuario` | `usuario` ← nombreCompleto | guarda el análisis de causa raíz (5 por qué) | sí: volver a guardar | **BAJO** | no cambia estado | APROBADA |
| `noConformidades.catalogos.areas.crear` | POST `api/nc-catalogos/{catalogo}` | INNPACK (sin parámetro) | **operador, admin, admin_ti** | cualquier sesión INNPACK (sin gating de rol) | `[Authorize]` (cualquier JWT) | cliente: `creadoPor` | `creadoPor` ← nombreCompleto | agrega un valor a un catálogo de NC | desactivar (API; no usado por la UI) | **BAJO** | aditivo, inline desde el formulario | APROBADA |
| `noConformidades.catalogos.categoriasDefecto.crear` | POST `api/nc-catalogos/{catalogo}` | INNPACK (sin parámetro) | **operador, admin, admin_ti** | cualquier sesión INNPACK (sin gating de rol) | `[Authorize]` (cualquier JWT) | cliente: `creadoPor` | `creadoPor` ← nombreCompleto | agrega un valor a un catálogo de NC | desactivar (API; no usado por la UI) | **BAJO** | aditivo, inline desde el formulario | APROBADA |
| `noConformidades.catalogos.clientes.crear` | POST `api/nc-catalogos/{catalogo}` | INNPACK (sin parámetro) | **operador, admin, admin_ti** | cualquier sesión INNPACK (sin gating de rol) | `[Authorize]` (cualquier JWT) | cliente: `creadoPor` | `creadoPor` ← nombreCompleto | agrega un valor a un catálogo de NC | desactivar (API; no usado por la UI) | **BAJO** | aditivo, inline desde el formulario | APROBADA |
| `noConformidades.catalogos.familiasProducto.crear` | POST `api/nc-catalogos/{catalogo}` | INNPACK (sin parámetro) | **operador, admin, admin_ti** | cualquier sesión INNPACK (sin gating de rol) | `[Authorize]` (cualquier JWT) | cliente: `creadoPor` | `creadoPor` ← nombreCompleto | agrega un valor a un catálogo de NC | desactivar (API; no usado por la UI) | **BAJO** | aditivo, inline desde el formulario | APROBADA |
| `noConformidades.catalogos.impactos.crear` | POST `api/nc-catalogos/{catalogo}` | INNPACK (sin parámetro) | **operador, admin, admin_ti** | cualquier sesión INNPACK (sin gating de rol) | `[Authorize]` (cualquier JWT) | cliente: `creadoPor` | `creadoPor` ← nombreCompleto | agrega un valor a un catálogo de NC | desactivar (API; no usado por la UI) | **BAJO** | aditivo, inline desde el formulario | APROBADA |
| `noConformidades.catalogos.niveles.crear` | POST `api/nc-catalogos/{catalogo}` | INNPACK (sin parámetro) | **operador, admin, admin_ti** | cualquier sesión INNPACK (sin gating de rol) | `[Authorize]` (cualquier JWT) | cliente: `creadoPor` | `creadoPor` ← nombreCompleto | agrega un valor a un catálogo de NC | desactivar (API; no usado por la UI) | **BAJO** | aditivo, inline desde el formulario | APROBADA |
| `noConformidades.catalogos.revisores.crear` | POST `api/nc-catalogos/{catalogo}` | INNPACK (sin parámetro) | **operador, admin, admin_ti** | cualquier sesión INNPACK (sin gating de rol) | `[Authorize]` (cualquier JWT) | cliente: `creadoPor` | `creadoPor` ← nombreCompleto | agrega un valor a un catálogo de NC | desactivar (API; no usado por la UI) | **BAJO** | aditivo, inline desde el formulario | APROBADA |
| `noConformidades.catalogos.supervisores.crear` | POST `api/nc-catalogos/{catalogo}` | INNPACK (sin parámetro) | **operador, admin, admin_ti** | cualquier sesión INNPACK (sin gating de rol) | `[Authorize]` (cualquier JWT) | cliente: `creadoPor` | `creadoPor` ← nombreCompleto | agrega un valor a un catálogo de NC | desactivar (API; no usado por la UI) | **BAJO** | aditivo, inline desde el formulario | APROBADA |
| `noConformidades.catalogos.tiposFalla.crear` | POST `api/nc-catalogos/{catalogo}` | INNPACK (sin parámetro) | **operador, admin, admin_ti** | cualquier sesión INNPACK (sin gating de rol) | `[Authorize]` (cualquier JWT) | cliente: `creadoPor` | `creadoPor` ← nombreCompleto | agrega un valor a un catálogo de NC | desactivar (API; no usado por la UI) | **BAJO** | aditivo, inline desde el formulario | APROBADA |
| `noConformidades.create` | POST `api/no-conformidades` | INNPACK (sin parámetro) | **operador, admin, admin_ti** | cualquier sesión INNPACK (sin gating de rol) | `[Authorize]` (cualquier JWT) | cliente: `creadoPor` | `creadoPor` ← nombreCompleto; payload completo pasa a la API → lista blanca de campos | crea una No Conformidad | sí: editar / eliminar (admin) | **BAJO** | registro operativo; creadoPor desde sesión | APROBADA |
| `noConformidades.seguimiento.crear` | POST `api/no-conformidades/{id}/seguimiento` | INNPACK (sin parámetro) | **operador, admin, admin_ti** | cualquier sesión INNPACK (sin gating de rol) | `[Authorize]` (cualquier JWT) | cliente: `autor` | `autor` ← nombreCompleto | agrega un comentario de seguimiento (append-only) | no se borra; se corrige con otro comentario | **BAJO** | aditivo, sin cambio de estado; autor desde sesión | VALIDADA (Fase 3a, gateway 0.4.0) |
| `noConformidades.acciones.actualizar` | PUT `api/no-conformidades/acciones/{accionId}` | INNPACK (sin parámetro) | **operador, admin, admin_ti** | cualquier sesión INNPACK (sin gating de rol) | `[Authorize]` (cualquier JWT) | cliente: `actualizadoPor` | `actualizadoPor` ← nombreCompleto; NO tocar: responsable (dato de negocio) | cambia estado/datos de una acción correctiva | sí: volver a editar | **MEDIO** | puede cerrar acciones; actualizadoPor desde sesión | APROBADA |
| `noConformidades.cerrar` | POST `api/no-conformidades/{id}/cerrar` | INNPACK (sin parámetro) | **admin, admin_ti** | cualquier sesión INNPACK (sin gating de rol) | `[Authorize]` (cualquier JWT) | cliente: `cerradoPor` | `cerradoPor` ← nombreCompleto | cierra la NC (CERRADA) | no hay 'reabrir' en la UI | **MEDIO** | cambio de estado final; cerradoPor desde sesión | APROBADA |
| `noConformidades.gestion.actualizar` | PATCH `api/no-conformidades/{id}/gestion` | INNPACK (sin parámetro) | **admin, admin_ti** | cualquier sesión INNPACK (sin gating de rol) | `[Authorize]` (cualquier JWT) | cliente: `actualizadoPor` | `actualizadoPor` ← nombreCompleto; NO tocar: responsable (dato de negocio) | asigna responsable, estado de gestión y fecha compromiso | sí: volver a editar | **MEDIO** | decisión de gestión | APROBADA |
| `noConformidades.update` | PUT `api/no-conformidades/{id}` | INNPACK (sin parámetro) | **operador, admin, admin_ti** | cualquier sesión INNPACK (sin gating de rol) | `[Authorize]` (cualquier JWT) | cliente: `actualizadoPor` | `actualizadoPor` ← nombreCompleto; payload completo pasa a la API → lista blanca de campos | edita campos de la NC (actualización parcial) | sí: volver a editar | **MEDIO** | payload pasa entero a la API: lista blanca de campos en gateway | APROBADA |
| `noConformidades.adjuntos.eliminar` | DELETE `api/no-conformidades/{id}/adjuntos/{adjuntoId}` | INNPACK (sin parámetro) | **admin, admin_ti** | cualquier sesión INNPACK (sin gating de rol) | `[Authorize]` (cualquier JWT) | ninguna (la API no registra autor) | — (auditoría del gateway obligatoria) | elimina un adjunto | no desde la UI | **ALTO** | destructivo; sin autor | APROBADA |
| `noConformidades.eliminar` | DELETE `api/no-conformidades/{id}` | INNPACK (sin parámetro) | **admin, admin_ti** | cualquier sesión INNPACK (sin gating de rol) | `[Authorize]` (cualquier JWT) | cliente: `actualizadoPor` | `actualizadoPor` ← nombreCompleto | borrado lógico de la NC | no desde la UI | **ALTO** | destructivo | APROBADA |

### productoTerminado (2)

| action | método · endpoint | empresa | roles Web | Photino hoy | API hoy | identidad desde cliente | Web fija desde SessionUser | efecto | rollback | riesgo | justificación | estado |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `productoTerminado.actualizarFecha` | PUT `api/producto-terminado/{id}/fecha` | INNPACK de sesión (`IdentityOverride` EMPRESA) | **admin, admin_ti** | cualquier sesión INNPACK (sin gating de rol) | `[Authorize]` (cualquier JWT) | cliente: `usuarioNombre` | `usuarioNombre` ← nombreCompleto | corrige la fecha de una inspección de producto terminado | sí: volver a corregir | **MEDIO** | altera trazabilidad; empresa de sesión | APROBADA |
| `productoTerminado.eliminar` | DELETE `api/producto-terminado/{id}?empresa={Uri.EscapeDataString(empresa)}` | INNPACK de sesión (`IdentityOverride` EMPRESA) | **admin, admin_ti** | cualquier sesión INNPACK (sin gating de rol) | `[Authorize]` (cualquier JWT) | ninguna (la API no registra autor) | — (auditoría del gateway obligatoria) | elimina una inspección de producto terminado | no desde la UI | **ALTO** | destructivo; sin autor | APROBADA |

### recepcion (6)

| action | método · endpoint | empresa | roles Web | Photino hoy | API hoy | identidad desde cliente | Web fija desde SessionUser | efecto | rollback | riesgo | justificación | estado |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `recepcion.bobinas.muestrear` | POST `api/recepcion-calidad/{id}/bobinas-muestreadas` | INNPACK de sesión (`IdentityOverride` EMPRESA) | **operador, admin, admin_ti** | cualquier sesión INNPACK (sin gating de rol) | `[Authorize]` (cualquier JWT) | sesión C# de Photino: NombreCompleto | inyectar nombreCompleto (Photino lo toma de su sesión) | genera plan AQL / registra bobinas muestreadas | sí: regenerar / volver a guardar | **BAJO** | operativo; sin autor en plan | APROBADA |
| `recepcion.crear` | POST `api/recepcion-calidad` | INNPACK de sesión (`IdentityOverride` EMPRESA) | **operador, admin, admin_ti** | cualquier sesión INNPACK (sin gating de rol) | `[Authorize]` (cualquier JWT) | sesión C# de Photino: NombreCompleto | inyectar nombreCompleto (Photino lo toma de su sesión) | crea un lote de recepción (con foto opcional) | no hay eliminar en la UI | **BAJO** | registro operativo; empresa y autor desde sesión | APROBADA |
| `recepcion.muestra.crear` | POST `api/recepcion-calidad/{id}/muestra-laboratorio` | INNPACK de sesión (`IdentityOverride` EMPRESA) | **operador, admin, admin_ti** | cualquier sesión INNPACK (sin gating de rol) | `[Authorize]` (cualquier JWT) | sesión C# de Photino: Id, NombreCompleto | inyectar userId (Photino lo toma de su sesión); inyectar nombreCompleto (Photino lo toma de su sesión) | crea muestra de laboratorio / NC desde el lote | sí: anular/eliminar en su módulo (admin) | **BAJO** | aditivo; autor y empresa desde sesión | APROBADA |
| `recepcion.nc.crear` | POST `api/recepcion-calidad/{id}/nc` | INNPACK de sesión (`IdentityOverride` EMPRESA) | **operador, admin, admin_ti** | cualquier sesión INNPACK (sin gating de rol) | `[Authorize]` (cualquier JWT) | sesión C# de Photino: NombreCompleto | inyectar nombreCompleto (Photino lo toma de su sesión) | crea muestra de laboratorio / NC desde el lote | sí: anular/eliminar en su módulo (admin) | **BAJO** | aditivo; autor y empresa desde sesión | APROBADA |
| `recepcion.plan.generar` | POST `api/recepcion-calidad/{id}/plan` | INNPACK de sesión (`IdentityOverride` EMPRESA) | **operador, admin, admin_ti** | cualquier sesión INNPACK (sin gating de rol) | `[Authorize]` (cualquier JWT) | ninguna (la API no registra autor) | — (auditoría del gateway obligatoria) | genera plan AQL / registra bobinas muestreadas | sí: regenerar / volver a guardar | **BAJO** | operativo; sin autor en plan | APROBADA |
| `recepcion.estado.actualizar` | PATCH `api/recepcion-calidad/{id}/estado` | INNPACK de sesión (`IdentityOverride` EMPRESA) | **admin, admin_ti** | cualquier sesión INNPACK (sin gating de rol) | `[Authorize]` (cualquier JWT) | ninguna (la API no registra autor) | — (auditoría del gateway obligatoria) | decide el estado del lote (conforme / no conforme) | sí: volver a cambiar | **MEDIO** | decisión de calidad; sin autor en API | APROBADA |

### registrosControl (3)

| action | método · endpoint | empresa | roles Web | Photino hoy | API hoy | identidad desde cliente | Web fija desde SessionUser | efecto | rollback | riesgo | justificación | estado |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `registrosControl.rechazarRegistro` | PUT `api/registros-control/{id}/rechazar` | INNPACK (sin parámetro) | **admin, admin_ti** | cualquier sesión INNPACK (sin gating de rol) | `[Authorize]` (cualquier JWT) | ninguna (la API no registra autor) | — (auditoría del gateway obligatoria) | valida/rechaza un registro de control | re-operando; sin historial de autor | **MEDIO** | decisión de supervisión; sin autor en API | APROBADA |
| `registrosControl.validarRegistro` | PUT `api/registros-control/{id}/validar` | INNPACK (sin parámetro) | **admin, admin_ti** | cualquier sesión INNPACK (sin gating de rol) | `[Authorize]` (cualquier JWT) | ninguna (la API no registra autor) | — (auditoría del gateway obligatoria) | valida/rechaza un registro de control | re-operando; sin historial de autor | **MEDIO** | decisión de supervisión; sin autor en API | APROBADA |
| `registrosControl.eliminarRegistro` | DELETE `api/registros-control/{id}` | INNPACK (sin parámetro) | **admin, admin_ti** | cualquier sesión INNPACK (sin gating de rol) | `[Authorize]` (cualquier JWT) | ninguna (la API no registra autor) | — (auditoría del gateway obligatoria) | elimina un registro de control | no desde la UI | **ALTO** | destructivo; sin autor | APROBADA |

### registrosProduccion (5)

| action | método · endpoint | empresa | roles Web | Photino hoy | API hoy | identidad desde cliente | Web fija desde SessionUser | efecto | rollback | riesgo | justificación | estado |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `registrosProduccion.rechazarRegistro` | PUT `api/registros-produccion/{id}/rechazar` | INNPACK (sin parámetro) | **admin, admin_ti** | cualquier sesión INNPACK (sin gating de rol) | `[Authorize]` (cualquier JWT) | ninguna (la API no registra autor) | — (auditoría del gateway obligatoria) | valida/rechaza una inspección de producción | re-operando; sin historial de autor | **MEDIO** | decisión de supervisión; sin autor en API | APROBADA |
| `registrosProduccion.validarRegistro` | PUT `api/registros-produccion/{id}/validar` | INNPACK (sin parámetro) | **admin, admin_ti** | cualquier sesión INNPACK (sin gating de rol) | `[Authorize]` (cualquier JWT) | ninguna (la API no registra autor) | — (auditoría del gateway obligatoria) | valida/rechaza una inspección de producción | re-operando; sin historial de autor | **MEDIO** | decisión de supervisión; sin autor en API | APROBADA |
| `registrosProduccion.eliminarRegistro` | DELETE `api/registros-produccion/{id}` | INNPACK (sin parámetro) | **admin, admin_ti** | cualquier sesión INNPACK (sin gating de rol) | `[Authorize]` (cualquier JWT) | ninguna (la API no registra autor) | — (auditoría del gateway obligatoria) | elimina una inspección de producción | no desde la UI | **ALTO** | destructivo; sin autor | APROBADA |
| `registrosProduccion.rechazarTodo` | PUT `api/registros-produccion/rechazar-todo` | INNPACK (sin parámetro) | **admin** | cualquier sesión INNPACK (sin gating de rol) | `[Authorize]` (cualquier JWT) | ninguna (la API no registra autor) | — (auditoría del gateway obligatoria) | valida/rechaza TODAS las inspecciones filtradas | no (masivo) | **ALTO** | masivo; solo admin con confirmación | APROBADA |
| `registrosProduccion.validarTodo` | PUT `api/registros-produccion/validar-todo` | INNPACK (sin parámetro) | **admin** | cualquier sesión INNPACK (sin gating de rol) | `[Authorize]` (cualquier JWT) | ninguna (la API no registra autor) | — (auditoría del gateway obligatoria) | valida/rechaza TODAS las inspecciones filtradas | no (masivo) | **ALTO** | masivo; solo admin con confirmación | APROBADA |

### talleresExternos (6)

| action | método · endpoint | empresa | roles Web | Photino hoy | API hoy | identidad desde cliente | Web fija desde SessionUser | efecto | rollback | riesgo | justificación | estado |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `talleresExternos.create` | POST `api/talleres-externos` | INNPACK (sin parámetro) | **operador, admin, admin_ti** | cualquier sesión INNPACK (sin gating de rol) | `[Authorize]` (cualquier JWT) | sesión C# de Photino: Id | inyectar userId (Photino lo toma de su sesión); payload completo pasa a la API → lista blanca de campos | crea un trabajo de taller externo | sí: editar/eliminar (admin) | **BAJO** | operativo; usuarioId desde sesión | APROBADA |
| `talleresExternos.sincronizarFps` | POST `api/talleres-externos/sincronizar-fps` | INNPACK (sin parámetro) | **admin, admin_ti** | cualquier sesión INNPACK (sin gating de rol) | `[Authorize]` (cualquier JWT) | sesión C# de Photino: Id | inyectar userId (Photino lo toma de su sesión) | sincroniza liberaciones desde FPS (API → FPS) y actualiza cantidades | idempotente según la API (por verificar) | **MEDIO** | operación masiva disparada contra un sistema externo | APROBADA |
| `talleresExternos.update` | PUT `api/talleres-externos/{id}` | INNPACK (sin parámetro) | **operador, admin, admin_ti** | cualquier sesión INNPACK (sin gating de rol) | `[Authorize]` (cualquier JWT) | sesión C# de Photino: Id | inyectar userId (Photino lo toma de su sesión); payload completo pasa a la API → lista blanca de campos | edita un trabajo (concurrencia optimista: version → 409) | sí: volver a editar | **MEDIO** | precios/cantidades; usuarioId desde sesión | APROBADA |
| `talleresExternos.catalogos.eliminarProceso` | DELETE `api/talleres-externos/catalogos/procesos/{id}` | INNPACK (sin parámetro) | **admin, admin_ti** | cualquier sesión INNPACK (sin gating de rol) | `[Authorize]` (cualquier JWT) | ninguna (la API no registra autor) | — (auditoría del gateway obligatoria) | elimina un taller/proceso del catálogo | no desde la UI | **ALTO** | maestro compartido; sin autor | APROBADA |
| `talleresExternos.catalogos.eliminarTaller` | DELETE `api/talleres-externos/catalogos/talleres/{id}` | INNPACK (sin parámetro) | **admin, admin_ti** | cualquier sesión INNPACK (sin gating de rol) | `[Authorize]` (cualquier JWT) | ninguna (la API no registra autor) | — (auditoría del gateway obligatoria) | elimina un taller/proceso del catálogo | no desde la UI | **ALTO** | maestro compartido; sin autor | APROBADA |
| `talleresExternos.eliminar` | DELETE `api/talleres-externos/{id}?version=&usuarioId=` | INNPACK (sin parámetro) | **admin, admin_ti** | cualquier sesión INNPACK (sin gating de rol) | `[Authorize]` (cualquier JWT) | sesión C# de Photino: Id | inyectar userId (Photino lo toma de su sesión) | elimina un trabajo (con version) | no desde la UI | **ALTO** | destructivo | APROBADA |

### usuarios (3)

| action | método · endpoint | empresa | roles Web | Photino hoy | API hoy | identidad desde cliente | Web fija desde SessionUser | efecto | rollback | riesgo | justificación | estado |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `usuarios.create` | POST `api/usuarios` | INNPACK (sin parámetro) | **admin, admin_ti** | usuarios.*: admin/admin_ti (handler + menú) | `[Authorize(Roles=admin,admin_ti)]` | ninguna (la API no registra autor) | — (auditoría del gateway obligatoria) | crea una cuenta (con rol elegido) | sí: eliminar (admin) | **ALTO** | gestión de cuentas; ya restringido en Photino y API | APROBADA |
| `usuarios.delete` | DELETE `api/usuarios/{id}` | INNPACK (sin parámetro) | **admin, admin_ti** | usuarios.*: admin/admin_ti (handler + menú) | `[Authorize(Roles=admin,admin_ti)]` | ninguna (la API no registra autor) | — (auditoría del gateway obligatoria) | elimina/desactiva una cuenta | no desde la UI | **ALTO** | gestión de cuentas; la API impide eliminarse a sí mismo (JWT) | APROBADA |
| `usuarios.resetPassword` | PUT `api/usuarios/{id}/password` | INNPACK (sin parámetro) | **admin, admin_ti** | usuarios.*: admin/admin_ti (handler + menú) | `[Authorize(Roles=admin,admin_ti)]` | ninguna (la API no registra autor) | — (auditoría del gateway obligatoria) | fija una contraseña nueva a otra cuenta | sí: nuevo reset | **ALTO** | credenciales; nunca loguear la contraseña | APROBADA |

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

Diseño aprobado (referencia):

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
