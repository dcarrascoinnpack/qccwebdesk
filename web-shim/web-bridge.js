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
    var AUTH_URL = "api/v1/auth/";
    var TIMEOUT_MS = 30000;
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

    var ACCIONES_NAVEGADOR = {
        // Photino: MessageRouter.GuardarExcel escribe el .xlsx en Descargas y lo abre (Process.Start).
        // Web: el mismo archivo (lo genera core/excel-exporter.js con SheetJS) se descarga con un Blob.
        "excel.guardar": guardarExcelEnNavegador
    };

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
    function nombreArchivoSeguro(nombre) {
        var n = String(nombre == null ? "" : nombre);
        n = n.split(/[\\/]/).pop();
        n = n.replace(/[\u200e\u200f\u202a-\u202e\u2066-\u2069\ufeff]/g, "");
        n = n.replace(/[\u0000-\u001f\u007f<>:"|?*]/g, "_");
        n = n.replace(/^[\s.]+|[\s.]+$/g, "");
        if (!n || /^\.?xlsx$/i.test(n)) {
            n = "qcc_export_" + fechaCompacta() + ".xlsx";
        }
        if (!/\.xlsx$/i.test(n)) {
            n += ".xlsx";
        }
        if (n.length > 150) {
            n = n.slice(0, 145) + ".xlsx";
        }
        if (/^(con|prn|aux|nul|com[0-9]|lpt[0-9])(\.|$)/i.test(n)) {
            n = "_" + n;
        }
        return n;
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
        var blob = new Blob([bytes], {
            type: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        });
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
                return peticion("POST", BRIDGE_URL, payload).then(function (r) {
                    if (r.status === 401) {
                        sesionPerdida();
                    }
                    if (r.status === 403) {
                        // Algunos controllers de Photino no muestran el error (p. ej. botones de
                        // validar/rechazar que solo recargan): el aviso lo pone el shim.
                        avisoNoDisponible(r.body && r.body.error);
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
    window.QCC_WEB = { bridge: "web", bridgeUrl: BRIDGE_URL, accionesNavegador: Object.keys(ACCIONES_NAVEGADOR) };
})();
