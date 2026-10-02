window.FaretLoginController = class FaretLoginController {

    init() {
        console.log("FaretLoginController iniciado");

        const savedIdentificador = localStorage.getItem("lcc_faret_identificador");
        const identificadorInput = document.getElementById("faret-identificador");

        if (savedIdentificador && identificadorInput) {
            identificadorInput.value = savedIdentificador;
        }

        const savedPassword = localStorage.getItem("lcc_faret_password");
        const passwordInput = document.getElementById("faret-password");
        const rememberInput = document.getElementById("faret-recordar-checkbox");

        if (savedPassword && passwordInput) {
            passwordInput.value = savedPassword;
        }

        if (savedIdentificador && savedPassword && rememberInput) {
            rememberInput.checked = true;
        }

        this._bindEvents();
    }

    destroy() {
        console.log("FaretLoginController destruido");
    }

    _bindEvents() {
        const form = document.getElementById("faret-login-form");
        form?.addEventListener("submit", (e) => { e.preventDefault(); this._login(); });

        document.getElementById("faret-volver-btn")
            ?.addEventListener("click", () => window.App.loadModule("empresa-selector"));
    }

    async _login() {
        const identificador = document.getElementById("faret-identificador")?.value?.trim();
        const password = document.getElementById("faret-password")?.value;
        const rememberMe = document.getElementById("faret-recordar-checkbox")?.checked === true;
        const btn = document.getElementById("faret-login-btn");

        if (!identificador || !password) {
            this._showMsg("Completa todos los campos", false);
            return;
        }

        btn.disabled = true;
        btn.textContent = "Ingresando...";

        try {
            const res = await window.PhotinoBridge.send({
                action: "faret.login",
                identificador,
                password,
            });

            console.log("FARET LOGIN RESPONSE:", res);

            if (res.ok) {
                sessionStorage.setItem("faretLoggedIn", "true");
                sessionStorage.setItem("faretNombreUsuario", res.data?.username || identificador);
                sessionStorage.setItem("faretRol", res.data?.role || "");

                if (rememberMe) {
                    localStorage.setItem("lcc_faret_remember_login", "true");
                    localStorage.setItem("lcc_faret_identificador", identificador);
                    localStorage.setItem("lcc_faret_nombreUsuario", res.data?.username || identificador);
                    localStorage.setItem("lcc_faret_rol", res.data?.role || "");
                    localStorage.setItem("lcc_faret_password", password);
                } else {
                    localStorage.removeItem("lcc_faret_remember_login");
                    localStorage.removeItem("lcc_faret_identificador");
                    localStorage.removeItem("lcc_faret_nombreUsuario");
                    localStorage.removeItem("lcc_faret_rol");
                    localStorage.removeItem("lcc_faret_password");
                }

                this._showMsg("Ingreso correcto", true);
                setTimeout(() => window.App.loadModule("faret"), 400);
            } else {
                this._showMsg(res.error || "Credenciales incorrectas", false);
            }
        } catch (err) {
            console.error("Error login Faret:", err);
            this._showMsg("Error de conexión con el backend", false);
        } finally {
            btn.disabled = false;
            btn.textContent = "Ingresar a Faret";
        }
    }

    _showMsg(text, ok) {
        const el = document.getElementById("faret-login-message");
        if (!el) return;
        el.textContent = text;
        el.style.color = ok ? "#16a34a" : "#dc2626";
    }
};
