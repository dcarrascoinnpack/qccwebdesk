if (!window.InicioController) {

    class InicioController {

        constructor() {
            this.charts = []
        }

        async init() {
            console.log("🚀 INIT INICIO QCC NUEVO")

            this.bindModuleCards()

            const data = await this.cargarDashboard()

            if (!data) return

            this.renderKpis(data.kpis || {})
            this.renderGraficos(data)
            this.renderAlertas(data.alertas || [])
            this.renderMaquinas(data.maquinas || [])
            this.renderCumplimiento(data.cumplimiento || [])
            this.renderResumen(data.resumen || {})
            this.renderFrecuencias(data.frecuencias || [])
        }

        bindModuleCards() {
            document
                .querySelectorAll(".home-module-card")
                .forEach(card => {
                    card.addEventListener("click", () => {
                        const target = card.dataset.moduleTarget

                        if (!target) return

                        if (window.App?.loadModule) {
                            window.App.loadModule(target)
                        }
                    })
                })
        }

        async cargarDashboard() {
            try {
                const res = await window.PhotinoBridge.send({
                    action: "inicio.getDashboard"
                })

                console.log("📩 DASHBOARD INICIO:", res)

                if (!res || res.ok === false) {
                    console.error("❌ Error backend inicio:", res?.error)
                    return null
                }

                return res.data || null

            } catch (err) {
                console.error("❌ Error cargando inicio:", err)
                return null
            }
        }

        renderKpis(kpis) {
            this.setText("kpi-controles-hoy", this.numero(kpis.controlesHoy))
            this.setText("kpi-no-conformes-hoy", this.numero(kpis.noConformesHoy))
            this.setText("kpi-merma-hoy", this.numeroDecimal(kpis.mermaHoy))
            this.setText("kpi-laboratorio-pendiente", this.numero(kpis.laboratorioPendiente))
            this.setText("kpi-laboratorio-criticos", this.numero(kpis.laboratorioCriticos))

            this.setVariacion("kpi-controles-variacion", kpis.variacionControles)
            this.setVariacion("kpi-no-conformes-variacion", kpis.variacionNoConformes)
            this.setVariacion("kpi-merma-variacion", kpis.variacionMerma)
        }

        renderGraficos(data) {
            this.destroyCharts()

            this.chartBarHorizontal(
                "chart-desviaciones-proceso",
                data.desviaciones || [],
                "nombre",
                "total",
                "No conformes"
            )

            this.chartBarHorizontal(
                "chart-top-defectos",
                data.topDefectos || [],
                "nombre",
                "total",
                "Defectos"
            )

            this.chartDoughnut(
                "chart-merma-proceso",
                data.merma || [],
                "nombre",
                "total"
            )

            this.chartDoughnut(
                "chart-origen-problema",
                data.origen || [],
                "nombre",
                "total"
            )

            this.chartLine(
                "chart-tendencia-no-conformes",
                data.tendencia || [],
                "fecha",
                "total",
                "No conformes"
            )
        }

        renderAlertas(alertas) {
            const container = document.getElementById("alertas-activas")
            if (!container) return

            container.innerHTML = ""

            if (!alertas.length) {
                container.innerHTML = `
                    <div class="alert-ok">
                        ✔ Sistema sin alertas activas
                    </div>
                `
                return
            }

            alertas.forEach(alerta => {
                const div = document.createElement("div")
                div.className = "alert-item"

                const puedeGestionar = alerta.modulo && alerta.registroId

                div.innerHTML = `
                    <div style="display:flex;justify-content:space-between;gap:12px;align-items:center;">
                        <div>
                            <div style="display:flex;justify-content:space-between;gap:12px;">
                                <strong>${this.escape(alerta.titulo || "-")}</strong>
                                <span>${this.escape(alerta.hora || "")}</span>
                            </div>
                            <div>${this.escape(alerta.descripcion || "-")}</div>
                        </div>
                        ${puedeGestionar
                        ? `<button class="btn-secondary alerta-gestionar-btn" data-modulo="${this.escape(alerta.modulo)}" data-id="${alerta.registroId}" data-proceso-id="${alerta.procesoId ?? ""}" data-parametro-id="${alerta.parametroId ?? ""}" data-proceso="${this.escape(alerta.titulo || "")}" data-defecto="${this.escape(alerta.defecto || "")}" data-estado-filtro="${this.escape(alerta.estadoFiltro || "")}">Gestionar</button>`
                        : ""
                    }
                    </div>
                `

                container.appendChild(div)
            })

            container.querySelectorAll(".alerta-gestionar-btn").forEach(btn => {
                btn.addEventListener("click", () => {
                    const modulo = btn.dataset.modulo
                    const id = Number(btn.dataset.id)
                    // Si la alerta trae proceso+parametro (agrupación real detrás del conteo "N
                    // casos"), el deep-link filtra por esos dos campos en vez de un solo registro
                    // — así "Gestionar" muestra todos los casos que componen la alerta, no 1 solo.
                    const procesoId = btn.dataset.procesoId ? Number(btn.dataset.procesoId) : null
                    const parametroId = btn.dataset.parametroId ? Number(btn.dataset.parametroId) : null
                    const proceso = btn.dataset.proceso || ""
                    const defecto = btn.dataset.defecto || ""
                    const estadoFiltro = btn.dataset.estadoFiltro || null

                    sessionStorage.setItem("qccDeepLinkId", JSON.stringify({ modulo, id, procesoId, parametroId, proceso, defecto, estadoFiltro }))

                    if (window.App?.loadModule) {
                        window.App.loadModule(modulo)
                    }
                })
            })
        }

        renderMaquinas(maquinas) {
            const container = document.getElementById("maquinas-desviaciones-list")
            if (!container) return

            container.innerHTML = ""

            if (!maquinas.length) {
                container.innerHTML = `<div class="activity-item"><span>Sin desviaciones hoy</span><strong>0</strong></div>`
                return
            }

            maquinas.forEach((item, index) => {
                const div = document.createElement("div")
                div.className = "activity-item"

                div.innerHTML = `
                    <span>${index + 1}. ${this.escape(item.nombre || "-")}</span>
                    <strong>${this.numero(item.total)}</strong>
                `

                container.appendChild(div)
            })
        }

        renderCumplimiento(items) {
            const container = document.getElementById("cumplimiento-controles-list")
            if (!container) return

            container.innerHTML = ""

            if (!items.length) {
                container.innerHTML = `<div class="activity-item"><span>Sin controles hoy</span><strong>100%</strong></div>`
                return
            }

            items.forEach(item => {
                const div = document.createElement("div")
                div.className = "activity-item"

                div.innerHTML = `
                    <span>${this.escape(item.proceso || "-")}</span>
                    <strong>${this.numero(item.cumplimiento)}%</strong>
                `

                container.appendChild(div)
            })
        }

        renderFrecuencias(items) {
            const container = document.getElementById("frecuencias-inspeccion-list")
            if (!container) return

            container.innerHTML = ""

            if (!items.length) {
                container.innerHTML = `<div class="activity-item"><span>Sin áreas configuradas</span><strong>-</strong></div>`
                return
            }

            items.forEach(item => {
                const div = document.createElement("div")
                div.className = "activity-item"

                const tiempo = item.minutosSinControl === null || item.minutosSinControl === undefined
                    ? "Sin registros"
                    : this.formatoMinutos(item.minutosSinControl)

                const color = item.atrasada ? "#dc2626" : "#16a34a"
                const estado = item.atrasada ? "ATRASADA" : "AL DÍA"

                div.innerHTML = `
                    <span>${this.escape(item.nombre || "-")} <small style="color:#64748b;">(máx. ${this.formatoMinutos(item.frecuenciaMinutos)})</small></span>
                    <span style="display:flex;align-items:center;gap:10px;">
                        <span style="color:${color};font-weight:700;">${estado} · ${tiempo}</span>
                        <button class="btn-secondary frecuencia-editar-btn" data-id="${item.id}" data-minutos="${item.frecuenciaMinutos}" data-nombre="${this.escape(item.nombre || "")}">Editar</button>
                    </span>
                `

                container.appendChild(div)
            })

            container.querySelectorAll(".frecuencia-editar-btn").forEach(btn => {
                btn.addEventListener("click", () => this.editarFrecuencia(btn))
            })
        }

        async editarFrecuencia(btn) {
            const id = Number(btn.dataset.id)
            const actual = Number(btn.dataset.minutos)
            const nombre = btn.dataset.nombre

            const respuesta = prompt(`Frecuencia mínima para "${nombre}" (en minutos):`, actual)
            if (respuesta === null) return

            const minutos = Number(respuesta)
            if (!Number.isFinite(minutos) || minutos <= 0) {
                alert("Ingresa un número de minutos válido, mayor a 0.")
                return
            }

            const res = await window.PhotinoBridge.send({
                action: "inicio.frecuencias.actualizar",
                data: { id, frecuenciaMinutos: minutos }
            })

            if (!res || res.ok === false) {
                alert(res?.error || "No se pudo actualizar la frecuencia.")
                return
            }

            const data = await this.cargarDashboard()
            if (data) this.renderFrecuencias(data.frecuencias || [])
        }

        formatoMinutos(minutos) {
            const n = Number(minutos || 0)
            if (n < 60) return `${n} min`
            const horas = Math.floor(n / 60)
            const resto = n % 60
            return resto === 0 ? `${horas} h` : `${horas} h ${resto} min`
        }

        renderResumen(resumen) {
            this.setText("resumen-ordenes", this.numero(resumen.ordenesEnProduccion))
            this.setText("resumen-programados", this.numero(resumen.controlesProgramados))
            this.setText("resumen-realizados", this.numero(resumen.controlesRealizados))
            this.setText("resumen-cumplimiento", `${this.numero(resumen.cumplimientoGeneral)}%`)
            this.setText("resumen-no-conformes", this.numero(resumen.noConformesAbiertas))
            this.setText("resumen-ensayos", this.numero(resumen.ensayosPendientes))
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
                            "#ef4444",
                            "#f97316",
                            "#eab308",
                            "#22c55e",
                            "#16a34a",
                            "#3b82f6",
                            "#6366f1"
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

        chartDoughnut(canvasId, rows, labelKey, valueKey) {
            const ctx = document.getElementById(canvasId)
            if (!ctx) return

            const labels = rows.map(x => x[labelKey] || "-")
            const values = rows.map(x => Number(x[valueKey] || 0))

            const chart = new Chart(ctx, {
                type: "doughnut",
                data: {
                    labels,
                    datasets: [{
                        data: values,
                        backgroundColor: [
                            "#22c55e",
                            "#3b82f6",
                            "#60a5fa",
                            "#84cc16",
                            "#06b6d4",
                            "#f97316"
                        ],
                        borderWidth: 0
                    }]
                },
                options: {
                    responsive: true,
                    maintainAspectRatio: false,
                    plugins: {
                        legend: {
                            position: "right",
                            labels: { font: { size: 11 } }
                        }
                    },
                    cutout: "62%"
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
                        borderColor: "#65a30d",
                        backgroundColor: "rgba(101, 163, 13, 0.12)",
                        pointBackgroundColor: "#65a30d",
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
                        y: { beginAtZero: true }
                    }
                }
            })

            this.charts.push(chart)
        }

        setText(id, value) {
            const el = document.getElementById(id)
            if (el) el.innerText = value
        }

        setVariacion(id, value) {
            const el = document.getElementById(id)
            if (!el) return

            const n = Number(value || 0)
            const signo = n > 0 ? "+" : ""
            el.innerText = `${signo}${n}%`
            el.style.color = n > 0 ? "#dc2626" : "#16a34a"
        }

        numero(value) {
            return Number(value || 0).toLocaleString("es-CL")
        }

        numeroDecimal(value) {
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

        destroyCharts() {
            this.charts.forEach(chart => {
                try {
                    chart.destroy()
                } catch (_) { }
            })

            this.charts = []
        }

        destroy() {
            console.log("🧹 Destroy InicioController")
            this.destroyCharts()
        }
    }

    window.InicioController = InicioController
}
