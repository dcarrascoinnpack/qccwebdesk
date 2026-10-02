window.FaretMaquinasController = class FaretMaquinasController {

    init() {
        console.log("FaretMaquinasController iniciado");

        this._maquinas = [];
        this._maquinaSeleccionada = "";

        document.getElementById("fm-refresh-btn")
            ?.addEventListener("click", () => this._loadResumen());

        document.getElementById("fm-buscar-btn")
            ?.addEventListener("click", () => this._buscar());

        document.getElementById("fm-limpiar-btn")
            ?.addEventListener("click", () => this._limpiar());

        this._filasFijas = window.TableUtils.init(
            document.getElementById("fm-tabla"),
            document.getElementById("fm-filas-fijadas")
        );

        this._loadResumen();
    }

    destroy() {
        console.log("FaretMaquinasController destruido");
    }

    async _loadResumen() {
        const contenedor = document.getElementById("fm-tabla")?.closest(".table-container");
        await window.TableUtils.preservarScroll(contenedor, () => this._loadResumenInterna());
    }

    async _loadResumenInterna() {
        try {
            const res = await window.PhotinoBridge.send({
                action: "faret.maquinas.resumen",
                maquina: this._maquinaSeleccionada,
            });

            if (!res.ok) {
                this._renderVacio();
                return;
            }

            this._maquinas = Array.isArray(res.data.maquinas) ? res.data.maquinas : [];
            this._renderSelector();
            this._renderKpis(res.data);
            this._renderTabla(Array.isArray(res.data.registros) ? res.data.registros : []);
        } catch {
            this._renderVacio();
        }
    }

    _renderSelector() {
        const select = document.getElementById("fm-filtro-maquina");
        const valorActual = this._maquinaSeleccionada;

        select.innerHTML = `<option value="">Selecciona una máquina</option>` +
            this._maquinas.map(m => `
                <option value="${m.maquina}" ${m.maquina === valorActual ? "selected" : ""}>
                    ${m.maquina} (${m.areaControl}) — ${m.totalRegistros}
                </option>
            `).join("");
    }

    _renderKpis(data) {
        document.getElementById("fm-kpi-total").textContent = data.totalMaquinas ?? 0;
        document.getElementById("fm-kpi-registros").textContent = data.registrosMaquinaSeleccionada ?? 0;
        document.getElementById("fm-kpi-defectos").textContent = data.conDefectosMaquinaSeleccionada ?? 0;
    }

    _renderVacio() {
        this._maquinas = [];
        this._renderSelector();
        this._renderKpis({});
        this._renderTabla([]);
    }

    _buscar() {
        this._maquinaSeleccionada = document.getElementById("fm-filtro-maquina").value || "";
        this._loadResumen();
    }

    _limpiar() {
        this._maquinaSeleccionada = "";
        this._loadResumen();
    }

    _formatoFecha(fecha) {
        return window.DateUtils.formatear(fecha);
    }

    _defectosBadge(presentaDefectos) {
        return presentaDefectos
            ? `<span style="color:#B91C1C;font-weight:600;">Sí</span>`
            : `<span style="color:#059669;font-weight:600;">No</span>`;
    }

    _renderTabla(items) {
        const tbody = document.getElementById("fm-tbody");

        if (!this._maquinaSeleccionada) {
            tbody.innerHTML = `<tr><td colspan="9">Selecciona una máquina para ver sus registros</td></tr>`;
            this._filasFijas?.refrescar();
            return;
        }

        if (!items.length) {
            tbody.innerHTML = `<tr><td colspan="9">Sin registros para esta máquina</td></tr>`;
            this._filasFijas?.refrescar();
            return;
        }

        tbody.innerHTML = items.map(i => `
            <tr data-id="${i.id}">
                <td>${this._formatoFecha(i.fechaRegistro)}</td>
                <td>${i.horaRegistro ?? "-"}</td>
                <td>${i.nvFaret ?? "-"}</td>
                <td>${i.areaControl ?? "-"}</td>
                <td>${i.operador === "Otros" ? (i.operadorOtro || "Otros") : (i.operador ?? "-")}</td>
                <td>${this._defectosBadge(i.presentaDefectos)}</td>
                <td>${i.defectos ?? "-"}</td>
                <td>${i.accionCorrectiva ?? "-"}</td>
            </tr>
        `).join("");

        this._filasFijas?.refrescar();
    }
};
