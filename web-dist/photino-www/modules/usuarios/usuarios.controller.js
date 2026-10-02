window.UsuariosController = class UsuariosController {
    init() {
        console.log("👥 UsuariosController iniciado");

        this.tableBody = document.getElementById("usuarios-table-body");
        this.message = document.getElementById("usuarios-message");

        this.formCard = document.getElementById("usuarios-form-card");

        this.btnNuevoUsuario = document.getElementById("btnNuevoUsuario");
        this.btnGuardarUsuario = document.getElementById("btnGuardarUsuario");
        this.btnCancelarUsuario = document.getElementById("btnCancelarUsuario");

        this.inputCodigo = document.getElementById("nuevoCodigoUsuario");
        this.inputNombre = document.getElementById("nuevoNombreCompleto");
        this.inputPassword = document.getElementById("nuevoPassword");

        this.selectRol = document.getElementById("nuevoRol");
        this.selectActivo = document.getElementById("nuevoActivo");

        this.bindEvents();
        this.loadUsuarios();
    }

    bindEvents() {
        if (this.btnNuevoUsuario) {
            this.btnNuevoUsuario.addEventListener("click", () => {
                this.showForm();
            });
        }

        if (this.btnCancelarUsuario) {
            this.btnCancelarUsuario.addEventListener("click", () => {
                this.hideForm();
            });
        }

        if (this.btnGuardarUsuario) {
            this.btnGuardarUsuario.addEventListener("click", async () => {
                await this.createUsuario();
            });
        }

        const btnExportar = document.getElementById("btnExportarUsuarios");

        if (btnExportar) {
            btnExportar.addEventListener("click", () => {
                if (window.__qccExportingExcel === true) {
                    console.warn("⛔ Exportación ya en curso");
                    return;
                }

                window.__qccExportingExcel = true;

                try {
                    window.ExcelExporter.exportTable({
                        tableSelector: "#tablaUsuarios",
                        fileName: `qcc_usuarios_${Date.now()}.xlsx`,
                        sheetName: "Usuarios",
                        title: "QCC - Gestión de Usuarios"
                    });

                    if (window.showToast) {
                        window.showToast("Excel exportado correctamente", "success");
                    }
                } catch (err) {
                    console.error("❌ Error exportando usuarios:", err);

                    if (window.showToast) {
                        window.showToast("Error exportando Excel", "error");
                    }
                } finally {
                    setTimeout(() => {
                        window.__qccExportingExcel = false;
                    }, 1200);
                }
            });
        }
    }

    async loadUsuarios() {
        try {
            const response = await window.PhotinoBridge.send({
                action: "usuarios.list",
                data: {}
            });

            console.log("👥 usuarios.list response:", response);

            if (!response || response.ok !== true) {
                this.showMessage(
                    response?.error || "No se pudieron cargar los usuarios",
                    false
                );

                this.renderEmpty("Error al cargar usuarios");
                return;
            }

            this.renderTable(response.data || []);
        } catch (error) {
            console.error("❌ Error cargando usuarios:", error);

            this.showMessage(
                "Error de conexión al cargar usuarios",
                false
            );

            this.renderEmpty("Error de conexión");
        }
    }

    renderTable(usuarios) {
        if (!this.tableBody) return;

        if (!usuarios || !usuarios.length) {
            this.renderEmpty("No hay usuarios registrados");
            return;
        }

        console.log("USUARIOS DATA:", usuarios);

        this.tableBody.innerHTML = usuarios.map(usuario => `
            <tr>
                <td>${usuario.Id ?? "-"}</td>

                <td>
                    ${this.escapeHtml(usuario.CodigoUsuario || "-")}
                </td>

                <td>
                    ${this.escapeHtml(usuario.NombreCompleto || "-")}
                </td>

                <td>
                    ${this.escapeHtml(usuario.Rol || "-")}
                </td>

                <td>
                    ${usuario.Activo ? "Sí" : "No"}
                </td>

                <td>
                    <div style="display:flex; gap:8px; flex-wrap:wrap;">
                        <button
                            class="btn-primary btn-reset-password"
                            data-id="${usuario.Id}"
                            data-nombre="${this.escapeHtml(
                                usuario.NombreCompleto ||
                                usuario.CodigoUsuario ||
                                ""
                            )}"
                        >
                            Resetear clave
                        </button>

                        <button
                            class="btn-secondary btn-eliminar-usuario"
                            data-id="${usuario.Id}"
                            data-nombre="${this.escapeHtml(
                                usuario.NombreCompleto ||
                                usuario.CodigoUsuario ||
                                ""
                            )}"
                        >
                            Eliminar
                        </button>
                    </div>
                </td>
            </tr>
        `).join("");

        this.bindTableEvents();
    }

    bindTableEvents() {
        const resetButtons =
            this.tableBody.querySelectorAll(".btn-reset-password");

        const deleteButtons =
            this.tableBody.querySelectorAll(".btn-eliminar-usuario");

        resetButtons.forEach(btn => {
            btn.addEventListener("click", async () => {
                const id = btn.getAttribute("data-id");

                const nombre =
                    btn.getAttribute("data-nombre") || "este usuario";

                await this.resetPassword(id, nombre);
            });
        });

        deleteButtons.forEach(btn => {
            btn.addEventListener("click", async () => {
                const id = btn.getAttribute("data-id");

                const nombre =
                    btn.getAttribute("data-nombre") || "este usuario";

                await this.deleteUsuario(id, nombre);
            });
        });
    }

    renderEmpty(text) {
        if (!this.tableBody) return;

        this.tableBody.innerHTML = `
            <tr>
                <td colspan="6" style="text-align:center; opacity:0.6;">
                    ${this.escapeHtml(text)}
                </td>
            </tr>
        `;
    }

    async createUsuario() {
        const codigoUsuario =
            this.inputCodigo?.value.trim() || "";

        const nombreCompleto =
            this.inputNombre?.value.trim() || "";

        const password =
            this.inputPassword?.value || "";

        const rol =
            this.selectRol?.value || "operador";

        const activo =
            this.selectActivo?.value === "true";

        if (!codigoUsuario || !nombreCompleto || !password) {
            this.showMessage(
                "Completa todos los campos obligatorios",
                false
            );
            return;
        }

        if (password.length < 6) {
            this.showMessage(
                "La contraseña debe tener al menos 6 caracteres",
                false
            );
            return;
        }

        try {
            const response = await window.PhotinoBridge.send({
                action: "usuarios.create",
                data: {
                    codigoUsuario,
                    nombreCompleto,
                    password,
                    rol,
                    activo
                }
            });

            console.log("👥 usuarios.create response:", response);

            if (!response || response.ok !== true) {
                this.showMessage(
                    response?.error || "No se pudo crear el usuario",
                    false
                );
                return;
            }

            this.showMessage(
                "Usuario creado correctamente",
                true
            );

            this.clearForm();
            this.hideForm();

            await this.loadUsuarios();
        } catch (error) {
            console.error("❌ Error creando usuario:", error);

            this.showMessage(
                "Error de conexión al crear usuario",
                false
            );
        }
    }

    async deleteUsuario(id, nombre) {
        if (!id) return;

        const confirmado =
            window.confirm(`¿Eliminar al usuario "${nombre}"?`);

        if (!confirmado) return;

        try {
            const response = await window.PhotinoBridge.send({
                action: "usuarios.delete",
                data: {
                    id: Number(id)
                }
            });

            console.log("👥 usuarios.delete response:", response);

            if (!response || response.ok !== true) {
                this.showMessage(
                    response?.error || "No se pudo eliminar el usuario",
                    false
                );
                return;
            }

            this.showMessage(
                "Usuario eliminado correctamente",
                true
            );

            await this.loadUsuarios();
        } catch (error) {
            console.error("❌ Error eliminando usuario:", error);

            this.showMessage(
                "Error de conexión al eliminar usuario",
                false
            );
        }
    }

    async resetPassword(id, nombre) {
        if (!id) return;

        const nuevaPassword = window.prompt(
            `Ingresa la nueva contraseña para "${nombre}":`
        );

        if (nuevaPassword === null) return;

        const passwordLimpia = nuevaPassword.trim();

        if (!passwordLimpia) {
            this.showMessage(
                "La nueva contraseña es obligatoria",
                false
            );
            return;
        }

        if (passwordLimpia.length < 6) {
            this.showMessage(
                "La nueva contraseña debe tener al menos 6 caracteres",
                false
            );
            return;
        }

        const confirmado = window.confirm(
            `¿Confirmas cambiar la contraseña del usuario "${nombre}"?`
        );

        if (!confirmado) return;

        try {
            const response = await window.PhotinoBridge.send({
                action: "usuarios.resetPassword",
                data: {
                    id: Number(id),
                    nuevaPassword: passwordLimpia
                }
            });

            console.log("👥 usuarios.resetPassword response:", response);

            if (!response || response.ok !== true) {
                this.showMessage(
                    response?.error || "No se pudo actualizar la contraseña",
                    false
                );
                return;
            }

            this.showMessage(
                "Contraseña actualizada correctamente",
                true
            );
        } catch (error) {
            console.error("❌ Error actualizando contraseña:", error);

            this.showMessage(
                "Error de conexión al actualizar la contraseña",
                false
            );
        }
    }

    showForm() {
        if (this.formCard) {
            this.formCard.style.display = "block";
        }
    }

    hideForm() {
        if (this.formCard) {
            this.formCard.style.display = "none";
        }

        this.clearForm();
    }

    clearForm() {
        if (this.inputCodigo) this.inputCodigo.value = "";
        if (this.inputNombre) this.inputNombre.value = "";
        if (this.inputPassword) this.inputPassword.value = "";

        if (this.selectRol) {
            this.selectRol.value = "operador";
        }

        if (this.selectActivo) {
            this.selectActivo.value = "true";
        }
    }

    showMessage(text, success) {
        if (!this.message) return;

        this.message.innerText = text;
        this.message.style.marginBottom = "12px";
        this.message.style.fontSize = "14px";
        this.message.style.fontWeight = "600";

        this.message.style.color =
            success ? "#16a34a" : "#dc2626";
    }

    escapeHtml(text) {
        return String(text ?? "")
            .replaceAll("&", "&amp;")
            .replaceAll("<", "&lt;")
            .replaceAll(">", "&gt;")
            .replaceAll('"', "&quot;")
            .replaceAll("'", "&#039;");
    }

    destroy() {
        console.log("🧹 UsuariosController destruido");
    }
};
