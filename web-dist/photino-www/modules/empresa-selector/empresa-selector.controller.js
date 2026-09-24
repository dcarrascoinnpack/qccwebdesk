window.EmpresaSelectorController = class EmpresaSelectorController {

    init() {
        console.log("EmpresaSelectorController iniciado");

        const cards = document.querySelectorAll(".empresa-card");

        if (!cards.length) {
            console.error("No se encontraron tarjetas de empresa");
            return;
        }

        cards.forEach(card => {
            card.addEventListener("click", () => {
                const empresa = card.getAttribute("data-empresa");
                if (!empresa) return;

                console.log("Empresa seleccionada:", empresa);
                sessionStorage.setItem("empresa", empresa);

                if (empresa === "FARET") {
                    this._entrarFaret();
                } else {
                    this._entrarInnpack();
                }
            });
        });
    }

    async _entrarFaret() {
        const isRemembered = localStorage.getItem("lcc_faret_remember_login") === "true";
        const identificador = localStorage.getItem("lcc_faret_identificador");
        const password = localStorage.getItem("lcc_faret_password");

        if (isRemembered && identificador && password) {
            try {
                const res = await window.PhotinoBridge.send({
                    action: "faret.login",
                    identificador,
                    password,
                });

                if (res.ok) {
                    sessionStorage.setItem("faretLoggedIn", "true");
                    sessionStorage.setItem("faretNombreUsuario", res.data?.username || identificador);
                    sessionStorage.setItem("faretRol", res.data?.role || "");
                    window.App.loadModule("faret");
                    return;
                }
            } catch {
                // sin conexión o credencial inválida: se cae al login manual
            }
        }

        window.App.loadModule("faret-login");
    }

    _entrarInnpack() {
        const isRemembered = localStorage.getItem("lcc_remember_login") === "true";
        const codigoUsuario = localStorage.getItem("lcc_codigoUsuario");

        if (isRemembered && codigoUsuario) {
            // Restaurar sesión recordada y entrar directo
            sessionStorage.setItem("isLoggedIn", "true");
            sessionStorage.setItem("codigoUsuario", codigoUsuario);
            sessionStorage.setItem("nombreUsuario", localStorage.getItem("lcc_nombreUsuario") || codigoUsuario);
            sessionStorage.setItem("rolUsuario", localStorage.getItem("lcc_rolUsuario") || "");
            window.App.loadModule("inicio");
        } else {
            window.App.loadModule("auth");
        }
    }

    destroy() {
        console.log("EmpresaSelectorController destruido");
    }
};
