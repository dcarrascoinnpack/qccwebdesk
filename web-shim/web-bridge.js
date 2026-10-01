/*
 * QCC Web — adaptador de window.PhotinoBridge para navegador.
 *
 * Solo existe en la versión web: tools/sync-photino-www.ps1 lo copia al snapshot del frontend
 * Photino y lo carga justo después de la definición inline de PhotinoBridge (index.html).
 * Photino nunca carga este archivo.
 *
 * Contrato preservado: PhotinoBridge.send(payload) devuelve una Promise que SIEMPRE se resuelve
 * con { ok, success, data, error } (igual que NormalizeResponse de Photino), nunca rechaza.
 *
 * Seguridad (Fase 1b):
 * - La sesión vive en el servidor (cookie HttpOnly QCC_SESSION, invisible para este JS).
 * - La identidad (usuario/rol/empresa) que muestra la UI se re-sincroniza desde el servidor al
 *   cargar la página; lo que se edite en sessionStorage no otorga permisos (el servidor decide).
 * - Nunca se guardan contraseñas, roles ni tokens en localStorage: solo el código de usuario.
 */
(function () {
    "use strict";

    // Si por algún motivo corre dentro de Photino, no tocar nada.
    if (window.external && window.external.sendMessage) {
        return;
    }

    var BRIDGE_URL = "api/v1/bridge";
    // Acciones con archivo en base64 (Fase 3k): ruta propia con tope de cuerpo mayor en el gateway.
    var BRIDGE_ARCHIVO_URL = "api/v1/bridge/archivo";
    // Fase 3z: el alta de lote de Recepción lleva la foto (PVA/Pliego) en base64 en el mismo payload.
    // Fase 4d: alta de documento/versión de Control Documental lleva el adjunto inicial opcional en base64.
    var ACCIONES_ARCHIVO = {
        "noConformidades.adjuntos.subir": true, "recepcion.crear": true,
        "controlDocumental.create": true, "controlDocumental.version.crear": true, "controlDocumental.adjunto.subir": true,
    };
    var AUTH_URL = "api/v1/auth/";
    var TIMEOUT_MS = 35000; // > read-timeout del gateway (30 s, = Photino): el gateway responde primero
    var EMPRESA_WEB = "INNPACK"; // Fase 1b: solo login INNPACK

    // Claves de "Recordar usuario" de Photino que en web NUNCA se guardan: contraseñas, rol,
    // nombre y el flag de autoingreso. Solo se permite recordar el identificador de usuario
    // (lcc_codigoUsuario / lcc_faret_identificador).
    var CLAVES_BLOQUEADAS = [
        "lcc_password",
        "lcc_faret_password",
        "lcc_remember_login",
        "lcc_faret_remember_login",
        "lcc_rolUsuario",
        "lcc_faret_rol",
        "lcc_usuarioActivo",
        "lcc_nombreUsuario",
        "lcc_faret_nombreUsuario"
    ];

    // Datos de sesión que la UI de Photino lee de sessionStorage. Son solo de presentación.
    var CLAVES_SESION_UI = [
        "isLoggedIn", "codigoUsuario", "nombreUsuario", "rolUsuario", "usuarioActivo",
        "faretLoggedIn", "faretNombreUsuario", "faretRol", "empresa"
    ];

    function claveBloqueada(clave) {
        return CLAVES_BLOQUEADAS.indexOf(String(clave)) !== -1;
    }

    try {
        CLAVES_BLOQUEADAS.forEach(function (clave) {
            window.localStorage.removeItem(clave);
        });
        var storageProto = Object.getPrototypeOf(window.localStorage);
        var setItemOriginal = storageProto.setItem;
        storageProto.setItem = function (clave, valor) {
            if (this === window.localStorage && claveBloqueada(clave)) {
                return;
            }
            return setItemOriginal.apply(this, arguments);
        };
    } catch (e) {
        // localStorage no disponible (modo privado estricto): no hay nada que proteger.
    }

    function respuestaError(mensaje) {
        return { ok: false, success: false, data: null, error: mensaje };
    }

    function leerCookie(nombre) {
        var partes = document.cookie ? document.cookie.split("; ") : [];
        for (var i = 0; i < partes.length; i++) {
            var idx = partes[i].indexOf("=");
            if (partes[i].substring(0, idx) === nombre) {
                return decodeURIComponent(partes[i].substring(idx + 1));
            }
        }
        return null;
    }

    function limpiarSesionUi() {
        try {
            CLAVES_SESION_UI.forEach(function (clave) {
                window.sessionStorage.removeItem(clave);
            });
        } catch (e) { /* sin sessionStorage */ }
    }

    /** Refleja en sessionStorage (solo para la UI) la identidad que dice el servidor. */
    function aplicarSesionUi(data) {
        try {
            limpiarSesionUi();
            window.sessionStorage.setItem("empresa", data.Empresa || EMPRESA_WEB);
            window.sessionStorage.setItem("isLoggedIn", "true");
            window.sessionStorage.setItem("codigoUsuario", data.CodigoUsuario || "");
            window.sessionStorage.setItem("nombreUsuario", data.NombreCompleto || "");
            window.sessionStorage.setItem("rolUsuario", data.Rol || "");
            window.sessionStorage.setItem("usuarioActivo", String(data.Activo !== false));
        } catch (e) { /* sin sessionStorage */ }
    }

    function peticion(metodo, url, cuerpo) {
        var controller = typeof AbortController === "function" ? new AbortController() : null;
        var timer = setTimeout(function () {
            if (controller) {
                controller.abort();
            }
        }, TIMEOUT_MS);

        var headers = { "Accept": "application/json" };
        if (metodo !== "GET") {
            headers["Content-Type"] = "application/json";
            var csrf = leerCookie("XSRF-TOKEN");
            if (csrf) {
                headers["X-XSRF-TOKEN"] = csrf;
            }
        }

        return fetch(url, {
            method: metodo,
            credentials: "same-origin",
            cache: "no-store",
            headers: headers,
            body: metodo === "GET" ? undefined : JSON.stringify(cuerpo || {}),
            signal: controller ? controller.signal : undefined
        })
            .then(function (res) {
                return res.json()
                    .catch(function () { return null; })
                    .then(function (json) {
                        // Respuestas con el contrato de Photino se devuelven tal cual (incluye errores
                        // de login con su mensaje genérico).
                        if (json && typeof json.ok === "boolean") {
                            return { status: res.status, body: json };
                        }
                        return { status: res.status, body: respuestaPorEstado(res.status) };
                    });
            })
            .catch(function (err) {
                return {
                    status: 0,
                    body: respuestaError(err && err.name === "AbortError"
                        ? "Tiempo de espera agotado."
                        : "No se pudo conectar con el servidor.")
                };
            })
            .finally(function () {
                clearTimeout(timer);
            });
    }

    function respuestaPorEstado(status) {
        if (status === 401) {
            return respuestaError("Sesión no iniciada o expirada.");
        }
        if (status === 403) {
            return respuestaError("Acción no disponible en la versión web.");
        }
        if (status === 429) {
            return respuestaError("Demasiados intentos. Espera un momento e inténtalo nuevamente.");
        }
        if (status >= 200 && status < 300) {
            return respuestaError("Respuesta inválida del servidor.");
        }
        return respuestaError("Error del servidor (" + status + ").");
    }

    // ------------------------------------------------------------------ capacidades de navegador
    // Acciones de Photino que en web se resuelven 100% en el navegador (nunca llegan al gateway).
    // tools/contract/photino_contract.py lee las claves de este objeto para el contract check.
    var MAX_EXCEL_BYTES = 50 * 1024 * 1024;
    var MAX_PDF_BYTES = 15 * 1024 * 1024;

    var ACCIONES_NAVEGADOR = {
        // Photino: MessageRouter.GuardarExcel escribe el .xlsx en Descargas y lo abre (Process.Start).
        // Web: el mismo archivo (lo genera core/excel-exporter.js con SheetJS) se descarga con un Blob.
        "excel.guardar": guardarExcelEnNavegador
    };

    // Acciones que SÍ pasan por el gateway (la API exige el JWT, que vive solo en el servidor) y
    // cuya respuesta {fileName, base64} se convierte en una descarga del navegador. Photino escribía
    // el archivo en Descargas y lo abría con Process.Start; aquí no hay archivos temporales ni rutas.
    var ACCIONES_DESCARGA = {
        "certificadosLiberacion.calidadPdf.descargar": descargarPdfEnNavegador,
        // Photino: imágenes/PDF se previsualizan en la modal (data: URI) y el resto se escribía en
        // %TEMP% y se abría con Process.Start. Web: previsualizable → mismo contrato; el resto se
        // descarga con un Blob.
        "controlDocumental.adjunto.abrir": abrirAdjuntoEnNavegador,
        // Mismo contrato y mismo flujo de Photino en Laboratorio (%TEMP%\QCC_MuestraLaboratorio).
        "muestraLab.adjunto.abrir": abrirAdjuntoEnNavegador
    };
    var MAX_ADJUNTO_BYTES = 25 * 1024 * 1024;
    var MIME_PREVISUALIZABLES = ["application/pdf", "image/png", "image/jpeg", "image/jpg", "image/gif", "image/bmp", "image/webp"];
    var MIME_DESCARGA = MIME_PREVISUALIZABLES.concat([
        "application/msword", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.ms-excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.ms-powerpoint", "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "text/plain", "text/csv"
    ]);

    function respuestaOk(data) {
        return { ok: true, success: true, data: data, error: null };
    }

    function fechaCompacta() {
        var d = new Date();
        var p = function (n) { return (n < 10 ? "0" : "") + n; };
        return "" + d.getFullYear() + p(d.getMonth() + 1) + p(d.getDate()) + "_" + p(d.getHours()) + p(d.getMinutes()) + p(d.getSeconds());
    }

    /**
     * Equivalente endurecido de Path.GetFileName + ".xlsx" de Photino: solo el último segmento, sin
     * caracteres inválidos en Windows ni de control, sin marcas Unicode que disfrazan la extensión
     * (RTLO), sin nombres reservados (CON, NUL...), largo acotado y siempre terminado en .xlsx.
     */
    function nombreArchivoSeguro(nombre, extension, porDefecto) {
        var ext = extension || ".xlsx";
        var extRe = new RegExp("\\" + ext + "$", "i");
        var n = String(nombre == null ? "" : nombre);
        n = n.split(/[\\/]/).pop();
        n = n.replace(/[\u200e\u200f\u202a-\u202e\u2066-\u2069\ufeff]/g, "");
        n = n.replace(/[\u0000-\u001f\u007f<>:"|?*]/g, "_");
        n = n.replace(/^[\s.]+|[\s.]+$/g, "");
        if (!n || new RegExp("^\\.?" + ext.slice(1) + "$", "i").test(n)) {
            n = porDefecto || ("qcc_export_" + fechaCompacta() + ext);
        }
        if (!extRe.test(n)) {
            n += ext;
        }
        if (n.length > 150) {
            n = n.slice(0, 150 - ext.length) + ext;
        }
        if (/^(con|prn|aux|nul|com[0-9]|lpt[0-9])(\.|$)/i.test(n)) {
            n = "_" + n;
        }
        return n;
    }

    /** Como nombreArchivoSeguro pero conservando la extensión original (adjuntos de cualquier tipo). */
    function nombreArchivoGenerico(nombre, porDefecto) {
        var n = String(nombre == null ? "" : nombre);
        n = n.split(/[\\/]/).pop();
        n = n.replace(/[‎‏‪-‮⁦-⁩﻿]/g, "");
        n = n.replace(/[\u0000-\u001f\u007f<>:"|?*]/g, "_");
        n = n.replace(/^[\s.]+|[\s.]+$/g, "");
        if (!n) {
            n = porDefecto;
        }
        if (n.length > 150) {
            n = n.slice(0, 150);
        }
        if (/^(con|prn|aux|nul|com[0-9]|lpt[0-9])(\.|$)/i.test(n)) {
            n = "_" + n;
        }
        return n;
    }

    function bytesDeBase64(base64) {
        var binario = window.atob(base64);
        var bytes = new Uint8Array(binario.length);
        for (var i = 0; i < binario.length; i++) {
            bytes[i] = binario.charCodeAt(i);
        }
        return bytes;
    }

    /**
     * Respuesta ok del gateway para controlDocumental.adjunto.abrir: {previsualizable, nombreArchivo,
     * tipoMime, contenidoBase64} ya validados en el servidor (firma real vs. MIME). Previsualizable →
     * se devuelve tal cual (el controller de Photino lo muestra en la modal con data: URI). Si no →
     * descarga con Blob (MIME de la lista segura o application/octet-stream) y responde
     * {previsualizable:false, nombreArchivo} como Photino.
     */
    function abrirAdjuntoEnNavegador(respuesta) {
        var data = respuesta && respuesta.data;
        var base64 = data && typeof data.contenidoBase64 === "string" ? data.contenidoBase64.trim() : "";
        if (!base64) {
            return respuestaError("El adjunto no trae contenido");
        }
        if (base64.length > Math.ceil(MAX_ADJUNTO_BYTES / 3) * 4) {
            return respuestaError("El adjunto excede el tamaño máximo permitido.");
        }
        var tipoMime = String(data.tipoMime || "").toLowerCase();
        var nombre = nombreArchivoGenerico(data.nombreArchivo, "adjunto_" + fechaCompacta());
        if (data.previsualizable === true && MIME_PREVISUALIZABLES.indexOf(tipoMime) >= 0) {
            return respuestaOk({ previsualizable: true, nombreArchivo: nombre, tipoMime: tipoMime, contenidoBase64: base64 });
        }
        var bytes;
        try {
            bytes = bytesDeBase64(base64);
        } catch (e) {
            return respuestaError("El adjunto no es válido.");
        }
        descargarBlob(bytes, MIME_DESCARGA.indexOf(tipoMime) >= 0 ? tipoMime : "application/octet-stream", nombre);
        return respuestaOk({ previsualizable: false, nombreArchivo: nombre });
    }

    function descargarBlob(bytes, tipoMime, nombre) {
        var blob = new Blob([bytes], { type: tipoMime });
        var url = URL.createObjectURL(blob);
        var enlace = document.createElement("a");
        enlace.href = url;
        enlace.download = nombre;
        enlace.rel = "noopener";
        enlace.style.display = "none";
        document.body.appendChild(enlace);
        enlace.click();
        enlace.remove();
        setTimeout(function () { URL.revokeObjectURL(url); }, 60000);
    }

    /**
     * Respuesta ok del gateway para certificadosLiberacion.calidadPdf.descargar: {fileName, base64}
     * ya validados en el servidor; aqu\u00ed se revalida (base64, tama\u00f1o, firma %PDF-, nombre) y se
     * descarga como application/pdf. Mismos mensajes de error que Photino/el gateway.
     */
    function descargarPdfEnNavegador(respuesta) {
        var data = respuesta && respuesta.data;
        var base64 = data && typeof data.base64 === "string" ? data.base64.trim() : "";
        if (!base64) {
            return respuestaError("El certificado no trae contenido");
        }
        if (base64.length > Math.ceil(MAX_PDF_BYTES / 3) * 4) {
            return respuestaError("El certificado excede el tama\u00f1o m\u00e1ximo permitido.");
        }
        var bytes;
        try {
            var binario = window.atob(base64);
            bytes = new Uint8Array(binario.length);
            for (var i = 0; i < binario.length; i++) {
                bytes[i] = binario.charCodeAt(i);
            }
        } catch (e) {
            return respuestaError("El certificado no es un PDF v\u00e1lido.");
        }
        // Firma "%PDF-": evita descargar otro contenido (HTML, ejecutables...) disfrazado de PDF.
        if (bytes.length < 5 || bytes[0] !== 0x25 || bytes[1] !== 0x50 || bytes[2] !== 0x44 || bytes[3] !== 0x46 || bytes[4] !== 0x2d) {
            return respuestaError("El certificado no es un PDF v\u00e1lido.");
        }
        var nombre = nombreArchivoSeguro(data.fileName, ".pdf", "certificado_" + fechaCompacta() + ".pdf");
        descargarBlob(bytes, "application/pdf", nombre);
        return respuestaOk({ fileName: nombre });
    }

    function guardarExcelEnNavegador(payload) {
        var data = payload && payload.data;
        if (!data || typeof data !== "object") {
            return Promise.resolve(respuestaError("Falta data para guardar Excel"));
        }
        var base64 = typeof data.base64 === "string" ? data.base64.trim() : "";
        if (!base64) {
            return Promise.resolve(respuestaError("Excel vacío"));
        }
        if (base64.length > Math.ceil(MAX_EXCEL_BYTES / 3) * 4) {
            return Promise.resolve(respuestaError("El archivo excede el tamaño máximo permitido."));
        }
        var bytes;
        try {
            var binario = window.atob(base64);
            bytes = new Uint8Array(binario.length);
            for (var i = 0; i < binario.length; i++) {
                bytes[i] = binario.charCodeAt(i);
            }
        } catch (e) {
            return Promise.resolve(respuestaError("Archivo Excel inválido."));
        }
        // Un .xlsx es un ZIP: debe empezar con "PK\x03\x04". Evita descargar otro contenido
        // (HTML, ejecutables...) disfrazado de Excel.
        if (bytes.length < 4 || bytes[0] !== 0x50 || bytes[1] !== 0x4b || bytes[2] !== 0x03 || bytes[3] !== 0x04) {
            return Promise.resolve(respuestaError("Archivo Excel inválido."));
        }

        var nombre = nombreArchivoSeguro(data.fileName);
        descargarBlob(bytes, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", nombre);
        return Promise.resolve(respuestaOk({ fileName: nombre }));
    }

    var avisoTimer = null;

    /** Aviso no bloqueante (sin alert) cuando una acción aún no existe en la versión web. */
    function avisoNoDisponible(mensaje) {
        try {
            var aviso = document.getElementById("qccWebAvisoNoDisponible");
            if (!aviso) {
                aviso = document.createElement("div");
                aviso.id = "qccWebAvisoNoDisponible";
                aviso.setAttribute("role", "status");
                aviso.style.cssText = "position:fixed;bottom:20px;right:20px;background:#7c2d12;color:#fff;"
                    + "padding:12px 18px;border-radius:8px;font-size:14px;box-shadow:0 4px 12px rgba(0,0,0,.25);"
                    + "z-index:999999;opacity:0;transition:opacity .25s ease;";
                document.body.appendChild(aviso);
            }
            aviso.textContent = mensaje || "Acción no disponible en la versión web.";
            aviso.style.opacity = "1";
            clearTimeout(avisoTimer);
            avisoTimer = setTimeout(function () { aviso.style.opacity = "0"; }, 3500);
        } catch (e) { /* sin DOM */ }
    }

    /** La sesión del servidor terminó (expirada/cerrada): volver al inicio sin datos de sesión. */
    function sesionPerdida() {
        limpiarSesionUi();
        if (window.App && typeof window.App.loadModule === "function") {
            window.App.loadModule("empresa-selector");
        }
    }

    function send(payload) {
        payload = payload || {};
        var data = payload.data || {};

        if (Object.prototype.hasOwnProperty.call(ACCIONES_NAVEGADOR, payload.action)) {
            try {
                return ACCIONES_NAVEGADOR[payload.action](payload);
            } catch (e) {
                return Promise.resolve(respuestaError("No se pudo completar la acción en el navegador."));
            }
        }

        switch (payload.action) {
            case "auth.login":
                return peticion("POST", AUTH_URL + "login", {
                    codigoUsuario: data.CodigoUsuario,
                    password: data.Password
                }).then(function (r) { return r.body; });

            case "auth.me":
                return peticion("GET", AUTH_URL + "session").then(function (r) {
                    if (r.body.ok && r.body.data) {
                        aplicarSesionUi(r.body.data);
                    }
                    return r.body;
                });

            case "auth.logout":
                return peticion("POST", AUTH_URL + "logout").then(function (r) {
                    limpiarSesionUi();
                    return r.body;
                });

            default:
                var url = Object.prototype.hasOwnProperty.call(ACCIONES_ARCHIVO, payload.action) ? BRIDGE_ARCHIVO_URL : BRIDGE_URL;
                // _modulo: módulo abierto, igual que el PhotinoBridge de Photino 1.8.14 (el gateway valida permisos
                // por módulo y lo quita antes de los handlers). Nunca otorga permisos: los decide la sesión.
                var conModulo = Object.assign({}, payload, {
                    _modulo: (window.App && window.App.currentModule) || null
                });
                return peticion("POST", url, conModulo).then(function (r) {
                    if (r.status === 401) {
                        sesionPerdida();
                    }
                    if (r.status === 403) {
                        // Algunos controllers de Photino no muestran el error (p. ej. botones de
                        // validar/rechazar que solo recargan): el aviso lo pone el shim.
                        avisoNoDisponible(r.body && r.body.error);
                    }
                    if (r.body && r.body.ok === true && Object.prototype.hasOwnProperty.call(ACCIONES_DESCARGA, payload.action)) {
                        try {
                            return ACCIONES_DESCARGA[payload.action](r.body);
                        } catch (e) {
                            return respuestaError("No se pudo completar la descarga en el navegador.");
                        }
                    }
                    return r.body;
                });
        }
    }

    // Logout de Photino (INNPACK) solo limpia el storage local: aquí se invalida además la sesión
    // del servidor. keepalive para que la petición salga aunque la UI cambie de módulo.
    document.addEventListener("click", function (e) {
        var boton = e.target && e.target.closest ? e.target.closest("#logout-btn") : null;
        if (!boton) {
            return;
        }
        var csrf = leerCookie("XSRF-TOKEN");
        var headers = { "Content-Type": "application/json" };
        if (csrf) {
            headers["X-XSRF-TOKEN"] = csrf;
        }
        fetch(AUTH_URL + "logout", {
            method: "POST", credentials: "same-origin", keepalive: true, headers: headers, body: "{}"
        }).catch(function () { /* la sesión igual expira en el servidor */ });
    }, true);

    // Al cargar: la identidad de la UI sale del servidor, nunca de lo que haya quedado (o se haya
    // editado) en sessionStorage. Síncrono a propósito: debe terminar antes de que corra core/app.js.
    (function sincronizarSesionInicial() {
        try {
            var xhr = new XMLHttpRequest();
            xhr.open("GET", AUTH_URL + "session", false);
            xhr.setRequestHeader("Accept", "application/json");
            xhr.send(null);
            var json = xhr.status === 200 ? JSON.parse(xhr.responseText) : null;
            if (json && json.ok && json.data) {
                aplicarSesionUi(json.data);
            } else {
                limpiarSesionUi();
            }
        } catch (e) {
            limpiarSesionUi();
        }
    })();

    if (!window.PhotinoBridge) {
        window.PhotinoBridge = { _callbacks: {}, _id: 0, receive: function () {} };
    }
    window.PhotinoBridge.send = send;
    window.QCC_WEB = {
        bridge: "web",
        bridgeUrl: BRIDGE_URL,
        accionesNavegador: Object.keys(ACCIONES_NAVEGADOR),
        accionesDescarga: Object.keys(ACCIONES_DESCARGA)
    };
})();
