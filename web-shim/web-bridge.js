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
    window.QCC_WEB = { bridge: "web", bridgeUrl: BRIDGE_URL };
})();
