window.AuthController = class AuthController {

    init() {
        console.log("🔐 AuthController iniciado");

        this.form = document.getElementById("auth-login-form");
        this.message = document.getElementById("auth-message");
        const savedCodigoUsuario = localStorage.getItem("lcc_codigoUsuario");
        const codigoInput = document.getElementById("codigoUsuario");

        if (savedCodigoUsuario && codigoInput) {
            codigoInput.value = savedCodigoUsuario;
        }
        const savedPassword = localStorage.getItem("lcc_password");
        const passwordInput = document.getElementById("password");
        const rememberInput = document.getElementById("rememberMe");

        if (savedPassword && passwordInput) {
            passwordInput.value = savedPassword;
        }

        if (savedCodigoUsuario && savedPassword && rememberInput) {
            rememberInput.checked = true;
        }
        if (!this.form) {
            console.error("❌ Formulario no encontrado");
            return;
        }

        this.bindEvents();
    }

    bindEvents() {
        this.form.addEventListener("submit", async (e) => {
            e.preventDefault();

            const codigoUsuario = document.getElementById("codigoUsuario").value.trim();
            const password = document.getElementById("password").value;
            const rememberMe = document.getElementById("rememberMe")?.checked === true;

            if (!codigoUsuario || !password) {
                this.showMessage("Completa todos los campos", false);
                return;
            }

            try {
                const response = await window.PhotinoBridge.send({
                    action: "auth.login",
                    data: {
                        CodigoUsuario: codigoUsuario,
                        Password: password
                    }
                });

                console.log("🔐 LOGIN RESPONSE:", response);

                if (response.ok) {
                    const meResponse = await window.PhotinoBridge.send({
                        action: "auth.me",
                        data: {}
                    });
                    if (meResponse.ok && meResponse.data) {
                        const codigo = meResponse.data.CodigoUsuario || "";
                        const nombre = meResponse.data.NombreCompleto || "";
                        const rol = meResponse.data.Rol || "";
                        const activo = String(meResponse.data.Activo ?? true);

                        sessionStorage.setItem("isLoggedIn", "true");
                        sessionStorage.setItem("codigoUsuario", codigo);
                        sessionStorage.setItem("nombreUsuario", nombre);
                        sessionStorage.setItem("rolUsuario", rol);
                        sessionStorage.setItem("usuarioActivo", activo);

                        if (rememberMe) {
                            localStorage.setItem("lcc_remember_login", "true");
                            localStorage.setItem("lcc_codigoUsuario", codigo);
                            localStorage.setItem("lcc_nombreUsuario", nombre);
                            localStorage.setItem("lcc_rolUsuario", rol);
                            localStorage.setItem("lcc_usuarioActivo", activo);
                            localStorage.setItem("lcc_password", password);
                        } else {
                            localStorage.removeItem("lcc_remember_login");
                            localStorage.removeItem("lcc_nombreUsuario");
                            localStorage.removeItem("lcc_rolUsuario");
                            localStorage.removeItem("lcc_usuarioActivo");
                            localStorage.removeItem("lcc_password");                        }

                    } else {
                        sessionStorage.setItem("isLoggedIn", "true");
                        sessionStorage.setItem("codigoUsuario", codigoUsuario);

                        if (rememberMe) {
                            localStorage.setItem("lcc_remember_login", "true");
                            localStorage.setItem("lcc_codigoUsuario", codigoUsuario);
                            localStorage.setItem("lcc_nombreUsuario", codigoUsuario);
                            localStorage.setItem("lcc_rolUsuario", "");
                        }
                        else {
                            localStorage.removeItem("lcc_remember_login");
                            localStorage.removeItem("lcc_nombreUsuario");
                            localStorage.removeItem("lcc_rolUsuario");
                            localStorage.removeItem("lcc_usuarioActivo");
                            localStorage.removeItem("lcc_password");
                        }
                    }

                    this.showMessage("Ingreso correcto", true);

                    setTimeout(() => {
                        window.App.loadModule("inicio");
                    }, 500);

                } else {
                    this.showMessage(response.error || "Credenciales inválidas", false);
                }

            } catch (error) {
                console.error("❌ Error login:", error);
                this.showMessage("Error de conexión", false);
            }
        });
    }

    showMessage(text, success) {
        if (!this.message) return;

        this.message.innerText = text;
        this.message.style.color = success ? "#16a34a" : "#dc2626";
    }

    destroy() {
        console.log("🧹 AuthController destruido");
    }
};