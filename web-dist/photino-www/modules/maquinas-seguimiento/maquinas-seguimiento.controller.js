if (!window.MaquinasSeguimientoController) {
    class MaquinasSeguimientoController {
        constructor() {
            this.data = null
            this.loading = false
            this.maquinaId = ""
            this._clickHandler = null
            this._changeHandler = null
        }

        async init() {
            console.log("INIT MAQUINAS SEGUIMIENTO")
            this.bindEvents()
            await this.cargarDatos()
        }

        bindEvents() {
            if (!this._clickHandler) {
                this._clickHandler = (e) => {
                    if (e.target.id === "btnBuscarMaquinaSeguimiento") {
                        this.maquinaId = this.getVal("filtroMaquinaSeguimiento")
                        this.cargarDatos()
                    }
                    if (e.target.id === "btnExportarMaquinasSeguimiento") {
                        this.exportarMaquinasSeguimiento()
                    }

                    if (e.target.id === "btnActualizarMaquinasSeguimiento") {
                        this.maquinaId = this.getVal("filtroMaquinaSeguimiento")
                        this.cargarDatos()
                    }

                    if (e.target.id === "btnLimpiarMaquinaSeguimiento") {
                        this.maquinaId = ""
                        const select = document.getElementById("filtroMaquinaSeguimiento")
                        if (select) select.value = ""
                        this.cargarDatos()
                    }
                }

                document.addEventListener("click", this._clickHandler)
            }

            if (!this._changeHandler) {
                this._changeHandler = (e) => {
                    if (e.target.id === "filtroMaquinaSeguimiento") {
                        this.maquinaId = e.target.value
                    }
                }

                document.addEventListener("change", this._changeHandler)
            }
        }

        async cargarDatos() {
            if (this.loading) return

            this.loading = true
            this.renderLoading()

            try {
                const res = await window.PhotinoBridge.send({
                    action: "maquinasSeguimiento.obtenerResumen",
                    data: {
                        maquinaId: this.maquinaId
                    }
                })

                if (!res || res.ok === false) {
                    throw new Error(res?.error || "Error cargando seguimiento por máquina")
                }

                this.data = res.data

                this.renderKpis()
                this.renderSelector()
                this.renderRegistros()
            } catch (err) {
                console.error("MAQUINAS SEGUIMIENTO ERROR:", err)
                this.renderError(err.message)
            } finally {
                this.loading = false
            }
        }

        renderKpis() {
            if (!this.data) return

            this.setText("maqTotalMaquinas", this.data.totalMaquinas ?? 0)
            this.setText("maqConRegistros", this.data.maquinasConRegistros ?? 0)
            this.setText("maqRegistrosSeleccionada", this.data.registrosMaquinaSeleccionada ?? 0)
            this.setText("maqRechazosSeleccionada", this.data.rechazosMaquinaSeleccionada ?? 0)
        }

        renderSelector() {
            const select = document.getElementById("filtroMaquinaSeguimiento")
            if (!select || !this.data) return

            const maquinas = this.data.maquinas || []
            const selected = this.maquinaId

            select.innerHTML = `
          <option value="">Selecciona una máquina</option>
          ${maquinas.map(m => `
            <option value="${m.id}" ${String(m.id) === String(selected) ? "selected" : ""}>
              ${this.escape(m.proceso)} - ${this.escape(m.nombre)}
            </option>
          `).join("")}
        `
        }

        renderRegistros() {
            const tbody = document.getElementById("tbodyMaquinasSeguimiento")
            if (!tbody) return

            if (!this.maquinaId) {
                tbody.innerHTML = `
            <tr>
              <td colspan="12">Selecciona una máquina para ver sus registros</td>
            </tr>
          `
                return
            }

            const registros = this.data?.registros || []

            if (!registros.length) {
                tbody.innerHTML = `
            <tr>
              <td colspan="12">La máquina seleccionada no tiene registros</td>
            </tr>
          `
                return
            }

            tbody.innerHTML = registros.map(r => `
          <tr>
            <td>${r.id}</td>
            <td>${this.escape(r.fechaRegistro)}</td>
            <td>${this.escape(r.horaRegistro)}</td>
            <td>${this.escape(r.usuario)}</td>
            <td>${this.escape(r.proceso)}</td>
            <td>${this.escape(r.maquina)}</td>
            <td>${this.escape(r.formulario || "-")}</td>
            <td>${this.escape(r.np || "-")}</td>
            <td>${this.escape(r.producto || "-")}</td>
            <td>${this.escape(r.turno || "-")}</td>
            <td>${this.renderEstado(r.estado)}</td>
            <td>${this.escape(r.observacion || "-")}</td>
          </tr>
        `).join("")
        }

        async exportarMaquinasSeguimiento() {
            if (!this.maquinaId) {
                alert("Selecciona una máquina para exportar sus registros")
                return
            }

            const res = await window.PhotinoBridge.send({
                action: "maquinasSeguimiento.obtenerResumen",
                data: {
                    maquinaId: this.maquinaId,
                    sinLimite: true
                }
            })

            if (!res || res.ok === false) {
                alert(res?.error || "Error exportando seguimiento por máquina")
                return
            }

            const registros = res.data?.registros || []
            this.exportarRegistrosMaquinaDesdeDatos(registros)
        }

        exportarRegistrosMaquinaDesdeDatos(registros) {
            const tabla = document.createElement("table")
            tabla.id = "tablaMaquinasSeguimientoExportTemp"
            tabla.style.position = "absolute"
            tabla.style.left = "-99999px"
            tabla.style.top = "0"

            tabla.innerHTML = `
          <thead>
            <tr>
              <th>ID</th>
              <th>Fecha</th>
              <th>Hora</th>
              <th>Usuario</th>
              <th>Proceso</th>
              <th>Máquina</th>
              <th>Formulario</th>
              <th>NP</th>
              <th>Producto</th>
              <th>Turno</th>
              <th>Estado</th>
              <th>Observación</th>
            </tr>
          </thead>
          <tbody>
            ${registros.map(r => `
              <tr>
                <td>${r.id}</td>
                <td>${this.escape(r.fechaRegistro)}</td>
                <td>${this.escape(r.horaRegistro)}</td>
                <td>${this.escape(r.usuario)}</td>
                <td>${this.escape(r.proceso)}</td>
                <td>${this.escape(r.maquina)}</td>
                <td>${this.escape(r.formulario || "-")}</td>
                <td>${this.escape(r.np || "-")}</td>
                <td>${this.escape(r.producto || "-")}</td>
                <td>${this.escape(r.turno || "-")}</td>
                <td>${this.escape(r.estado || "-")}</td>
                <td>${this.escape(r.observacion || "-")}</td>
              </tr>
            `).join("")}
          </tbody>
        `

            document.body.appendChild(tabla)

            window.ExcelExporter.exportTable({
                tableSelector: "#tablaMaquinasSeguimientoExportTemp",
                fileName: `qcc_seguimiento_maquina_${Date.now()}.xlsx`,
                sheetName: "Seguimiento",
                title: "QCC - Seguimiento por Máquina"
            })

            tabla.remove()
        }

        renderEstado(estado) {
            const value = estado || "-"
            return `<span style="font-weight:600;">${this.escape(value)}</span>`
        }

        renderLoading() {
            const tbody = document.getElementById("tbodyMaquinasSeguimiento")
            if (tbody) {
                tbody.innerHTML = `
            <tr>
              <td colspan="12">Cargando seguimiento por máquina...</td>
            </tr>
          `
            }
        }

        renderError(message) {
            const tbody = document.getElementById("tbodyMaquinasSeguimiento")
            if (tbody) {
                tbody.innerHTML = `
            <tr>
              <td colspan="12">Error: ${this.escape(message)}</td>
            </tr>
          `
            }
        }

        setText(id, value) {
            const el = document.getElementById(id)
            if (el) el.textContent = value
        }

        getVal(id) {
            return document.getElementById(id)?.value || ""
        }

        escape(value) {
            return String(value ?? "")
                .replaceAll("&", "&amp;")
                .replaceAll("<", "&lt;")
                .replaceAll(">", "&gt;")
                .replaceAll('"', "&quot;")
                .replaceAll("'", "&#039;")
        }

        destroy() {
            console.log("DESTROY MAQUINAS SEGUIMIENTO")

            if (this._clickHandler) {
                document.removeEventListener("click", this._clickHandler)
            }

            if (this._changeHandler) {
                document.removeEventListener("change", this._changeHandler)
            }

            this._clickHandler = null
            this._changeHandler = null
            this.loading = false
        }
    }

    window.MaquinasSeguimientoController = MaquinasSeguimientoController
}
