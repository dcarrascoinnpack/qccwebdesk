if (!window.RegistrosProduccionController) {
    class RegistrosProduccionController {
        constructor() {
            this.data = null
            this.loading = false
            this._clickHandler = null
            this.charts = []
        }

        async init() {
            console.log("INIT DASHBOARD PRODUCCION")

            this.bindEvents()
            this.initDatePickers()

            await this.cargarFiltros()
            await this.cargarDatos()
        }

        initDatePickers() {
            if (!window.flatpickr) return

            flatpickr("#produccionFechaDesde", {
                dateFormat: "Y-m-d",
                altInput: true,
                altFormat: "d-m-Y",
                allowInput: true
            })

            flatpickr("#produccionFechaHasta", {
                dateFormat: "Y-m-d",
                altInput: true,
                altFormat: "d-m-Y",
                allowInput: true
            })
        }

        bindEvents() {
            if (this._clickHandler) return

            this._clickHandler = (e) => {
                if (e.target.id === "btnActualizarRegistrosProduccion") {
                    this.cargarDatos()
                    return
                }

                if (e.target.id === "btnFiltrarRegistrosProduccion") {
                    this.cargarDatos()
                    return
                }

                if (e.target.id === "btnLimpiarRegistrosProduccion") {
                    const desde = document.getElementById("produccionFechaDesde")
                    const hasta = document.getElementById("produccionFechaHasta")

                    if (desde?._flatpickr) desde._flatpickr.clear()
                    if (hasta?._flatpickr) hasta._flatpickr.clear()

                    document.getElementById("produccionInspector").value = ""
                    document.getElementById("produccionTurno").value = ""
                    document.getElementById("produccionProceso").value = ""

                    this.cargarDatos()
                    return
                }

                if (e.target.classList.contains("btn-validar-produccion")) {
                    const id = Number(e.target.dataset.id)

                    window.PhotinoBridge.send({
                        action: "registrosProduccion.validarRegistro",
                        id
                    }).then(() => {
                        this.cargarDatos()
                    })

                    return
                }

                if (e.target.classList.contains("btn-rechazar-produccion")) {
                    const id = Number(e.target.dataset.id)

                    window.PhotinoBridge.send({
                        action: "registrosProduccion.rechazarRegistro",
                        id
                    }).then(() => {
                        this.cargarDatos()
                    })

                    return
                }

                if (e.target.classList.contains("btn-eliminar-produccion")) {
                    const id = Number(e.target.dataset.id)

                    if (!confirm("¿Eliminar este registro? Esta acción no se puede deshacer desde la pantalla.")) {
                        return
                    }

                    window.PhotinoBridge.send({
                        action: "registrosProduccion.eliminarRegistro",
                        id
                    }).then(() => {
                        this.cargarDatos()
                    })

                    return
                }

                if (e.target.classList.contains("btn-ver-imagen-produccion")) {
                    const url = e.target.dataset.url || ""
                    this.mostrarImagenProduccion(url)
                    return
                }

                if (e.target.classList.contains("bobinas-badge")) {
                    this.abrirPopoverBobinas(e.target)
                    return
                }

                if (e.target.id === "btnCerrarImagenProduccion") {
                    this.cerrarImagenProduccion()
                    return
                }

                if (e.target.id === "modalImagenProduccion") {
                    this.cerrarImagenProduccion()
                    return
                }

                if (e.target.id === "btnValidarTodoProduccion") {
                    window.PhotinoBridge.send({
                        action: "registrosProduccion.validarTodo"
                    }).then(() => {
                        this.cargarDatos()
                    })

                    return
                }

                if (e.target.id === "btnRechazarTodoProduccion") {
                    window.PhotinoBridge.send({
                        action: "registrosProduccion.rechazarTodo"
                    }).then(() => {
                        this.cargarDatos()
                    })

                    return
                }

                if (e.target.id === "btnExportarRegistrosProduccion") {
                    window.ExcelExporter.exportTable({
                        tableSelector: "#tablaProduccionDesempeno",
                        fileName: "qcc_dashboard_produccion.xlsx",
                        sheetName: "Dashboard Produccion",
                        title: "QCC - Dashboard Producción"
                    })

                    return
                }
            }

            document.addEventListener("click", this._clickHandler)

            document.addEventListener("keydown", (e) => {
                if (e.key === "Enter") {
                    e.preventDefault()
                    this.cargarDatos()
                }

                if (e.key === "Escape") {
                    this.cerrarImagenProduccion()
                }
            })
        }

        async cargarFiltros() {
            try {
                const res = await window.PhotinoBridge.send({
                    action: "registrosProduccion.obtenerFiltros"
                })

                if (!res || res.ok === false) {
                    throw new Error(res?.error || "Error cargando filtros")
                }

                const usuarios = res.data?.usuarios || []
                const procesos = res.data?.procesos || []

                const cboInspector = document.getElementById("produccionInspector")
                const cboProceso = document.getElementById("produccionProceso")

                if (cboInspector) {
                    cboInspector.innerHTML =
                        `<option value="">Todos</option>` +
                        usuarios.map(x =>
                            `<option value="${x.id}">${x.nombre}</option>`
                        ).join("")
                }

                if (cboProceso) {
                    cboProceso.innerHTML =
                        `<option value="">Todos</option>` +
                        procesos.map(x =>
                            `<option value="${x.id}">${x.nombre}</option>`
                        ).join("")
                }

            } catch (err) {
                console.error("ERROR FILTROS PRODUCCION:", err)
            }
        }

        async cargarDatos() {
            if (this.loading) return
            const contenedor = document.getElementById("tbodyRegistrosProduccion")?.closest(".table-container")
            await window.TableUtils.preservarScroll(contenedor, () => this._cargarDatosInterno())
        }

        async _cargarDatosInterno() {
            this.loading = true
            this.renderLoading()

            try {
                const res = await window.PhotinoBridge.send({
                    action: "registrosProduccion.obtenerResumen",
                    data: {
                        fechaDesde: this.getVal("produccionFechaDesde"),
                        fechaHasta: this.getVal("produccionFechaHasta"),
                        inspector: this.getVal("produccionInspector"),
                        turno: this.getVal("produccionTurno"),
                        proceso: this.getVal("produccionProceso")
                    }
                })

                console.log("📊 DASHBOARD PRODUCCION:", res)

                if (!res || res.ok === false) {
                    throw new Error(res?.error || "Error cargando dashboard producción")
                }

                this.data = res.data || {}
                this.render()
            } catch (err) {
                console.error("DASHBOARD PRODUCCION ERROR:", err)
                this.renderError(err.message)
            } finally {
                this.loading = false
            }
        }

        render() {
            if (!this.data) return

            this.renderHistoricoBanner()
            this.renderKpis()
            this.renderCharts()
            this.renderDesempeno()
            this.renderUltimosRegistros()
        }

        renderHistoricoBanner() {
            const banner = document.getElementById("produccionHistoricoBanner")
            if (!banner) return

            if (!this.data?.mostrandoHistorico) {
                banner.style.display = "none"
                banner.innerHTML = ""
                return
            }

            const fecha = this.data?.fechaUltimoRegistro
                ? ` (último registro: ${this.escape(this.data.fechaUltimoRegistro)})`
                : ""

            banner.style.display = "block"
            banner.innerHTML = `
          <div class="card" style="
            border-left:4px solid #f59e0b;
            background:#fffbeb;
            margin-bottom:16px;
            display:flex;
            align-items:center;
            gap:10px;
          ">
            <strong>Sin datos recientes.</strong>
            <span>Mostrando resumen histórico completo${fecha}.</span>
          </div>
        `
        }

        renderKpis() {
            this.setText("prodControlesHoy", this.numero(this.data.controlesHoy))
            this.setText("prodControlesPeriodo", this.numero(this.data.controlesPeriodo))
            this.setText("prodCumplimientoGeneral", `${this.numero(this.data.cumplimientoGeneral)}%`)
            this.setText("prodNoConformidades", this.numero(this.data.noConformidadesDetectadas))
        }

        renderCharts() {
            this.destroyCharts()

            this.chartBarHorizontal(
                "chartProduccionCumplimientoInspector",
                this.data.cumplimientoPorInspector || [],
                "inspector",
                "porcentaje",
                "Cumplimiento %"
            )

            this.chartBarHorizontal(
                "chartProduccionNoConformidadesInspector",
                this.data.noConformidadesPorInspector || [],
                "inspector",
                "total",
                "No conformidades"
            )

            this.chartControlesPorProceso(
                "chartProduccionControlesProceso",
                this.data.controlesPorProceso || []
            )

            this.chartLine(
                "chartProduccionTendenciaCumplimiento",
                this.data.tendenciaCumplimiento || [],
                "fecha",
                "cumplimiento",
                "Cumplimiento %"
            )
        }

        renderDesempeno() {
            const tbody = document.getElementById("tbodyProduccionDesempeno")
            if (!tbody) return

            const rows = this.data.desempenoIndividual || []

            if (!rows.length) {
                tbody.innerHTML = `
            <tr>
              <td colspan="6">Sin datos de desempeño</td>
            </tr>
          `
                return
            }

            tbody.innerHTML = rows.map(r => `
          <tr>
            <td>${this.escape(r.inspector)}</td>
            <td>${this.numero(r.cumplimiento)}%</td>
            <td>${this.numero(r.controlesProgramados)}</td>
            <td>${this.numero(r.controlesRealizados)}</td>
            <td>${this.numero(r.noConformidades)}</td>
            <td>${this.renderEstadoDesempeno(r.estado)}</td>
          </tr>
        `).join("")
        }

        renderUltimosRegistros() {
            const tbody = document.getElementById("tbodyRegistrosProduccion")
            if (!tbody) return

            window.TableUtils.cerrarPopover()

            const registros = this.data?.ultimosRegistros || []

            if (!registros.length) {
                tbody.innerHTML = `
            <tr>
              <td colspan="22">Sin registros disponibles</td>
            </tr>
          `
                return
            }

            tbody.innerHTML = registros.map(r => {
                const bobinas = window.TableUtils.resumenBobinas(r.bobinaCodigo, r.bobinaDescripcion, r.bobinaLote, r.bobinaObservacion)

                return `
          <tr>
            <td>${this.numero(r.id)}</td>
            <td>${this.escape(r.fechaRegistro)}</td>
            <td>${this.escape(r.horaRegistro)}</td>
            <td>${this.escape(r.usuario)}</td>
            <td>${this.escape(r.proceso)}</td>
            <td>${this.escape(r.maquina)}</td>
            <td>${this.escape(r.np || "-")}</td>
            <td>${this.escape(r.codigoProducto || "-")}</td>
            <td>${this.escape(r.producto || "-")}</td>
            <td>${this.escape(r.turno || "-")}</td>
            <td>${this.escape(this.traducirEstado(r.estado))}</td>
            <td>${this.escape(r.observacion || "-")}</td>
            <td>${this.escape(r.cantidadMerma || "-")}</td>
            <td>${this.escape(r.tipoMerma || "-")}</td>
            <td>${this.escape(r.tipoDefecto || "-")}</td>
            <td>${this.escape(r.bobinaLote || "-")}</td>
            <td>${this.celdaBobina(bobinas, "codigo", r.id)}</td>
            <td>${this.celdaBobina(bobinas, "descripcion", r.id)}</td>
            <td>${this.escape(r.bobinaObservacion || "-")}</td>

            <td>
              ${this.renderEstadoValidacion(r.estadoValidacion)}
            </td>

            <td>
              <button
                class="btn-primary btn-validar-produccion"
                data-id="${r.id}">
                Validar
              </button>

              <button
                class="btn-secondary btn-rechazar-produccion"
                data-id="${r.id}">
                Rechazar
              </button>

              <button
                class="btn-danger btn-eliminar-produccion"
                data-id="${r.id}">
                Eliminar
              </button>
            </td>

            <td>
              ${r.imagenUrl && r.imagenUrl.trim() !== ""
                    ? `
                  <button
                    class="btn-secondary btn-ver-imagen-produccion"
                    data-url="${this.escape(r.imagenUrl)}">
                    Ver Imagen
                  </button>
                `
                    : "-"
                }
            </td>
          </tr>
        `
            }).join("")
        }

        chartBarHorizontal(canvasId, rows, labelKey, valueKey, label) {
            const ctx = document.getElementById(canvasId)
            if (!ctx) return

            const labels = rows.map(x => x[labelKey] || "-")
            const values = rows.map(x => Number(x[valueKey] || 0))

            const chart = new Chart(ctx, {
                type: "bar",
                data: {
                    labels,
                    datasets: [{
                        label,
                        data: values,
                        backgroundColor: [
                            "#2563eb",
                            "#16a34a",
                            "#f97316",
                            "#dc2626",
                            "#7c3aed",
                            "#0891b2"
                        ],
                        borderRadius: 8
                    }]
                },
                options: {
                    indexAxis: "y",
                    responsive: true,
                    maintainAspectRatio: false,
                    plugins: {
                        legend: { display: false }
                    },
                    scales: {
                        x: { beginAtZero: true },
                        y: { ticks: { font: { size: 11 } } }
                    }
                }
            })

            this.charts.push(chart)
        }

        chartControlesPorProceso(canvasId, rows) {
            const ctx = document.getElementById(canvasId)
            if (!ctx) return

            const procesos = [...new Set(rows.map(x => x.proceso || "-"))]
            const inspectores = [...new Set(rows.map(x => x.inspector || "-"))]

            const colores = [
                "#2563eb",
                "#16a34a",
                "#f97316",
                "#dc2626",
                "#7c3aed",
                "#0891b2",
                "#eab308",
                "#ec4899"
            ]

            const datasets = inspectores.map((inspector, index) => {
                return {
                    label: inspector,
                    backgroundColor: colores[index % colores.length],
                    borderColor: colores[index % colores.length],
                    data: procesos.map(proceso => {
                        const row = rows.find(x =>
                            (x.proceso || "-") === proceso &&
                            (x.inspector || "-") === inspector
                        )

                        return Number(row?.total || 0)
                    }),
                    borderRadius: 8
                }
            })

            const chart = new Chart(ctx, {
                type: "bar",
                data: {
                    labels: procesos,
                    datasets
                },
                options: {
                    responsive: true,
                    maintainAspectRatio: false,
                    plugins: {
                        legend: {
                            position: "bottom",
                            labels: { font: { size: 10 } }
                        }
                    },
                    scales: {
                        x: { stacked: true },
                        y: {
                            stacked: true,
                            beginAtZero: true
                        }
                    }
                }
            })

            this.charts.push(chart)
        }

        chartLine(canvasId, rows, labelKey, valueKey, label) {
            const ctx = document.getElementById(canvasId)
            if (!ctx) return

            const labels = rows.map(x => x[labelKey] || "-")
            const values = rows.map(x => Number(x[valueKey] || 0))

            const chart = new Chart(ctx, {
                type: "line",
                data: {
                    labels,
                    datasets: [{
                        label,
                        data: values,
                        borderColor: "#2563eb",
                        backgroundColor: "rgba(37, 99, 235, 0.12)",
                        pointBackgroundColor: "#2563eb",
                        pointRadius: 4,
                        tension: 0.35,
                        fill: true
                    }]
                },
                options: {
                    responsive: true,
                    maintainAspectRatio: false,
                    plugins: {
                        legend: { display: false }
                    },
                    scales: {
                        y: {
                            beginAtZero: true,
                            suggestedMax: 100
                        }
                    }
                }
            })

            this.charts.push(chart)
        }

        renderEstadoDesempeno(estado) {
            const value = estado || "-"

            let color = "#64748b"

            if (value === "Excelente") color = "#16a34a"
            if (value === "A mejorar") color = "#f97316"
            if (value === "Crítico") color = "#dc2626"

            return `
          <span style="
            font-weight:700;
            color:${color};
          ">
            ${this.escape(value)}
          </span>
        `
        }

        renderEstadoValidacion(estado) {
            const value = estado || "PENDIENTE"

            let color = "#f59e0b"

            if (value === "VALIDADO") color = "#16a34a"
            if (value === "RECHAZADO") color = "#dc2626"

            return `
          <span style="
            font-weight:700;
            color:${color};
          ">
            ${this.escape(value)}
          </span>
        `
        }

        renderLoading() {
            const tbody1 = document.getElementById("tbodyProduccionDesempeno")
            const tbody2 = document.getElementById("tbodyRegistrosProduccion")

            if (tbody1) {
                tbody1.innerHTML = `
            <tr>
              <td colspan="6">Cargando dashboard...</td>
            </tr>
          `
            }

            if (tbody2) {
                tbody2.innerHTML = `
            <tr>
              <td colspan="22">Cargando registros...</td>
            </tr>
          `
            }
        }

        renderError(message) {
            const tbody1 = document.getElementById("tbodyProduccionDesempeno")
            const tbody2 = document.getElementById("tbodyRegistrosProduccion")

            if (tbody1) {
                tbody1.innerHTML = `
            <tr>
              <td colspan="6">Error: ${this.escape(message)}</td>
            </tr>
          `
            }

            if (tbody2) {
                tbody2.innerHTML = `
            <tr>
              <td colspan="22">Error: ${this.escape(message)}</td>
            </tr>
          `
            }
        }

        mostrarImagenProduccion(url) {
            const imagenUrl = this.normalizarImagenUrl(url)

            if (!imagenUrl) {
                alert("No hay imagen disponible para este registro.")
                return
            }

            this.cerrarImagenProduccion()

            const modal = document.createElement("div")
            modal.id = "modalImagenProduccion"
            modal.style.position = "fixed"
            modal.style.left = "0"
            modal.style.top = "0"
            modal.style.width = "100%"
            modal.style.height = "100%"
            modal.style.background = "rgba(15, 23, 42, 0.75)"
            modal.style.zIndex = "9999"
            modal.style.display = "flex"
            modal.style.alignItems = "center"
            modal.style.justifyContent = "center"
            modal.style.padding = "24px"

            modal.innerHTML = `
          <div style="
            background:#ffffff;
            border-radius:12px;
            max-width:90%;
            max-height:90%;
            padding:16px;
            box-shadow:0 20px 60px rgba(0,0,0,0.35);
            position:relative;
          ">
            <div style="
              display:flex;
              justify-content:space-between;
              align-items:center;
              gap:12px;
              margin-bottom:12px;
            ">
              <strong>Imagen del registro</strong>

              <button
                id="btnCerrarImagenProduccion"
                class="btn-secondary"
                type="button">
                Cerrar
              </button>
            </div>

            <img
              src="${this.escape(imagenUrl)}"
              alt="Imagen del registro"
              style="
                display:block;
                max-width:100%;
                max-height:75vh;
                object-fit:contain;
                border-radius:8px;
              "
            />
          </div>
        `

            document.body.appendChild(modal)
        }

        cerrarImagenProduccion() {
            const modal = document.getElementById("modalImagenProduccion")
            if (modal) modal.remove()
        }

        normalizarImagenUrl(url) {
            const value = String(url || "").trim()

            if (!value) return ""

            if (value.startsWith("http://") || value.startsWith("https://")) {
                return value
            }

            if (value.startsWith("/")) {
                return `https://api.faret.cl/calidad${value}`
            }

            return `https://api.faret.cl/calidad/${value}`
        }

        setText(id, value) {
            const el = document.getElementById(id)
            if (el) el.textContent = value
        }

        numero(value) {
            return Number(value || 0).toLocaleString("es-CL", {
                minimumFractionDigits: 0,
                maximumFractionDigits: 2
            })
        }

        escape(value) {
            return String(value ?? "")
                .replaceAll("&", "&amp;")
                .replaceAll("<", "&lt;")
                .replaceAll(">", "&gt;")
                .replaceAll('"', "&quot;")
                .replaceAll("'", "&#039;")
        }

        // Celda compacta de Código/Descripción Bobina: 0 -> "-", 1 -> valor normal, 2+ -> primera + "+N"
        // (badge clickeable que abre el popover con todas las bobinas emparejadas).
        celdaBobina(bobinas, campo, rowId) {
            if (bobinas.cantidad === 0) return "-"

            const valor = this.escape((bobinas.primera && bobinas.primera[campo]) || "-")
            if (bobinas.cantidad === 1) return valor

            return `
        <span>${valor}</span>
        <button type="button" class="bobinas-badge" data-bobinas-row="${rowId}"
          style="margin-left:6px; padding:1px 7px; border-radius:999px; background:#EFF6FF; color:#2563EB; border:1px solid #DBEAFE; font-size:11px; font-weight:700; cursor:pointer; line-height:1.6; white-space:nowrap;">+${bobinas.restantes}</button>
      `
        }

        // Popover "Bobinas utilizadas" anclado al badge +N clickeado. Vive en document.body (vía
        // TableUtils.abrirPopover), así que nunca queda cortado por el overflow de .table-container.
        abrirPopoverBobinas(trigger) {
            const rowId = trigger.dataset.bobinasRow
            const registros = this.data?.ultimosRegistros || []
            const r = registros.find(item => String(item.id) === String(rowId))
            if (!r) return

            const bobinas = window.TableUtils.resumenBobinas(r.bobinaCodigo, r.bobinaDescripcion, r.bobinaLote, r.bobinaObservacion)

            const filas = bobinas.pares.map(p => `
        <tr>
          <td style="padding:6px 10px; border-bottom:1px solid #F1F5F9;">${this.escape(p.codigo || "-")}</td>
          <td style="padding:6px 10px; border-bottom:1px solid #F1F5F9;">${this.escape(p.descripcion || "-")}</td>
          <td style="padding:6px 10px; border-bottom:1px solid #F1F5F9;">${this.escape(p.lote || "-")}</td>
          <td style="padding:6px 10px; border-bottom:1px solid #F1F5F9;">${this.escape(p.observacion || "-")}</td>
        </tr>
      `).join("")

            const html = `
        <div style="display:flex; align-items:center; justify-content:space-between; gap:10px; padding:10px 12px; border-bottom:1px solid #E2E8F0; background:#F8FAFC;">
          <strong style="font-size:12px; color:#334155;">Bobinas utilizadas (${bobinas.cantidad})</strong>
          <button type="button" class="tu-popover-cerrar" style="border:none; background:transparent; color:#64748B; font-size:16px; line-height:1; cursor:pointer; padding:2px 4px;">&times;</button>
        </div>
        <table style="width:100%; border-collapse:collapse; font-size:12px;">
          <thead>
            <tr style="background:#F1F5F9;">
              <th style="text-align:left; padding:6px 10px;">Código</th>
              <th style="text-align:left; padding:6px 10px;">Descripción</th>
              <th style="text-align:left; padding:6px 10px;">Lote</th>
              <th style="text-align:left; padding:6px 10px;">Observación</th>
            </tr>
          </thead>
          <tbody>${filas}</tbody>
        </table>
      `

            const el = window.TableUtils.abrirPopover(trigger, html)
            el.querySelector(".tu-popover-cerrar")?.addEventListener("click", () => window.TableUtils.cerrarPopover())
        }

        traducirEstado(estado) {
            const mapa = {
                "Aprobado": "Conforme",
                "Rechazado": "No conforme"
            }

            return mapa[estado] || estado || "-"
        }

        getVal(id) {
            return document.getElementById(id)?.value || ""
        }

        destroyCharts() {
            this.charts.forEach(chart => {
                try {
                    chart.destroy()
                } catch (_) { }
            })

            this.charts = []
        }

        destroy() {
            console.log("DESTROY DASHBOARD PRODUCCION")

            if (this._clickHandler) {
                document.removeEventListener("click", this._clickHandler)
            }

            this._clickHandler = null
            this.loading = false
            this.destroyCharts()
        }
    }

    window.RegistrosProduccionController = RegistrosProduccionController
}
