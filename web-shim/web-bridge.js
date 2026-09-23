/*
 * QCC Web — adaptador de window.PhotinoBridge para navegador.
 *
 * Solo existe en la versión web: tools/sync-photino-www.ps1 lo copia al snapshot del frontend
 * Photino y lo carga justo después de la definición inline de PhotinoBridge (index.html).
 * Photino nunca carga este archivo.
 *
 * Contrato preservado: PhotinoBridge.send(payload) devuelve una Promise que SIEMPRE se resuelve
 * con { ok, success, data, error } (igual que NormalizeResponse de Photino), nunca rechaza.
 */
(function () {
    "use strict";

    // Si por algún motivo corre dentro de Photino, no tocar nada.
    if (window.external && window.external.sendMessage) {
        return;
    }

    var BRIDGE_URL = "api/v1/bridge";
    var TIMEOUT_MS = 30000;

    // Claves de "Recordar usuario" de Photino que en web NUNCA se guardan: contraseñas, rol y el
    // flag de autoingreso (en web siempre se inicia sesión contra el servidor). Solo se permite
    // recordar el identificador/nombre de usuario.
    var CLAVES_BLOQUEADAS = [
        "lcc_password",
        "lcc_faret_password",
        "lcc_remember_login",
        "lcc_faret_remember_login",
        "lcc_rolUsuario",
        "lcc_faret_rol",
        "lcc_usuarioActivo"
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

    function send(payload) {
        var controller = typeof AbortController === "function" ? new AbortController() : null;
        var timer = setTimeout(function () {
            if (controller) {
                controller.abort();
            }
        }, TIMEOUT_MS);

        return fetch(BRIDGE_URL, {
            method: "POST",
            credentials: "same-origin",
            headers: { "Content-Type": "application/json", "Accept": "application/json" },
            body: JSON.stringify(payload || {}),
            signal: controller ? controller.signal : undefined
        })
            .then(function (res) {
                if (res.status === 401) {
                    return respuestaError("Sesión no iniciada o expirada.");
                }
                if (res.status === 403) {
                    return respuestaError("Acción no disponible en la versión web.");
                }
                if (res.status === 429) {
                    return respuestaError("Demasiados intentos. Espera un momento e inténtalo nuevamente.");
                }
                if (!res.ok) {
                    return respuestaError("Error del servidor (" + res.status + ").");
                }
                return res.json().catch(function () {
                    return respuestaError("Respuesta inválida del servidor.");
                });
            })
            .catch(function (err) {
                return respuestaError(
                    err && err.name === "AbortError"
                        ? "Tiempo de espera agotado."
                        : "No se pudo conectar con el servidor."
                );
            })
            .finally(function () {
                clearTimeout(timer);
            });
    }

    if (!window.PhotinoBridge) {
        window.PhotinoBridge = { _callbacks: {}, _id: 0, receive: function () {} };
    }
    window.PhotinoBridge.send = send;
    window.QCC_WEB = { bridge: "web", bridgeUrl: BRIDGE_URL };
})();
