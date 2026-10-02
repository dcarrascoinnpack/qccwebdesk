window.FaretDataController = class FaretDataController {

    init() {
        console.log("FaretDataController iniciado");

        this._page = 1;
        this._pageSize = 50;
        this._totalCount = 0;

        document.getElementById("fd-refresh-btn")
            ?.addEventListener("click", () => this._loadAll());

        document.getElementById("fd-filtrar-btn")
            ?.addEventListener("click", () => { this._page = 1; this._loadAll(); });

        document.getElementById("fd-limpiar-btn")
            ?.addEventListener("click", () => this._limpiarFiltros());

        document.getElementById("fd-anterior-btn")
            ?.addEventListener("click", () => this._irPagina(this._page - 1));

        document.getElementById("fd-siguiente-btn")
            ?.addEventListener("click", () => this._irPagina(this._page + 1));

        document.getElementById("fd-exportar-btn")
            ?.addEventListener("click", () => this._exportar());

        document.getElementById("fd-imprimir-btn")
            ?.addEventListener("click", () => this._imprimir());

        this._filasFijas = window.TableUtils.init(
            document.getElementById("fd-tabla"),
            document.getElementById("fd-filas-fijadas")
        );

        this._loadAll();
    }

    destroy() {
        console.log("FaretDataController destruido");
    }

    _getFiltros() {
        return {
            cliente: document.getElementById("fd-filtro-cliente")?.value.trim() || "",
            tipoPnc: document.getElementById("fd-filtro-tipo-pnc")?.value.trim() || "",
            nivel: document.getElementById("fd-filtro-nivel")?.value || "",
            fechaDesde: document.getElementById("fd-filtro-fecha-desde")?.value || "",
            fechaHasta: document.getElementById("fd-filtro-fecha-hasta")?.value || "",
        };
    }

    _limpiarFiltros() {
        document.getElementById("fd-filtro-cliente").value = "";
        document.getElementById("fd-filtro-tipo-pnc").value = "";
        document.getElementById("fd-filtro-nivel").value = "";
        document.getElementById("fd-filtro-fecha-desde").value = "";
        document.getElementById("fd-filtro-fecha-hasta").value = "";
        this._page = 1;
        this._loadAll();
    }

    _irPagina(pagina) {
        if (pagina < 1) return;
        this._page = pagina;
        this._loadLista();
    }

    async _loadAll() {
        await Promise.all([this._loadResumen(), this._loadLista(), this._poblarFiltrosSelect()]);
    }

    // Arma las opciones de los <select> de Cliente/Tipo PNC con los valores reales de TODOS los
    // registros (sin filtros), no solo la página visible — reutiliza _traerTodosLosRegistros.
    async _poblarFiltrosSelect() {
        const items = await this._traerTodosLosRegistros({});

        const mapa = {
            "fd-filtro-cliente": "cliente",
            "fd-filtro-tipo-pnc": "tipoPnc",
        };

        Object.entries(mapa).forEach(([selectId, campo]) => {
            const select = document.getElementById(selectId);
            if (!select) return;

            const valorActual = select.value;
            const valores = new Set(
                items.map(r => (r[campo] || "").toString().trim()).filter(Boolean)
            );

            select.innerHTML = `<option value="">Todos</option>` +
                [...valores].sort().map(v => `<option value="${v}">${v}</option>`).join("");

            if (valorActual && valores.has(valorActual)) select.value = valorActual;
        });
    }

    async _loadResumen() {
        try {
            const res = await window.PhotinoBridge.send({
                action: "faret.data.resumen",
                ...this._getFiltros(),
            });

            if (!res.ok) return;
            this._renderResumen(res.data);
        } catch {
            // El error de la lista ya se muestra en _loadLista(); el resumen falla en silencio.
        }
    }

    _renderResumen(data) {
        document.getElementById("fd-total-registros").textContent = data.totalRegistros ?? 0;
        document.getElementById("fd-clientes-unicos").textContent = data.clientesUnicos ?? 0;
        document.getElementById("fd-registros-criticos").textContent = data.registrosCriticos ?? 0;
        document.getElementById("fd-ultima-importacion").textContent = data.ultimaImportacion
            ? new Date(data.ultimaImportacion).toLocaleString("es-CL")
            : "-";
    }

    async _loadLista() {
        const contenedor = document.getElementById("fd-tabla")?.closest(".table-container");
        await window.TableUtils.preservarScroll(contenedor, () => this._loadListaInterna());
    }

    async _loadListaInterna() {
        const loadingEl = document.getElementById("fd-loading");
        const errorEl = document.getElementById("fd-error");
        const tbody = document.getElementById("fd-tbody");

        loadingEl.style.display = "block";
        errorEl.style.display = "none";

        try {
            const res = await window.PhotinoBridge.send({
                action: "faret.data.list",
                page: this._page,
                pageSize: this._pageSize,
                ...this._getFiltros(),
            });

            if (!res.ok) {
                errorEl.textContent = res.error || "Error al cargar los registros";
                errorEl.style.display = "block";
                tbody.innerHTML = `<tr><td colspan="17" class="faret-empty">Sin datos</td></tr>`;
                return;
            }

            this._totalCount = res.data.totalCount ?? 0;
            this._page = res.data.page ?? this._page;
            this._renderTabla(Array.isArray(res.data.items) ? res.data.items : []);
            this._renderPaginacion();
        } catch {
            errorEl.textContent = "Error de comunicación con el backend";
            errorEl.style.display = "block";
            tbody.innerHTML = `<tr><td colspan="17" class="faret-empty">Sin datos</td></tr>`;
        } finally {
            loadingEl.style.display = "none";
        }
    }

    _renderTabla(items) {
        const tbody = document.getElementById("fd-tbody");

        if (!items.length) {
            tbody.innerHTML = `<tr><td colspan="17" class="faret-empty">Sin registros</td></tr>`;
            return;
        }

        tbody.innerHTML = items.map(r => `
            <tr data-id="${r.id}">
                <td>${r.id ?? "-"}</td>
                <td>${window.DateUtils.formatear(r.fechaIngreso)}</td>
                <td>${window.DateUtils.formatear(r.fechaSalida)}</td>
                <td>${r.npNv ?? "-"}</td>
                <td>${r.cliente ?? "-"}</td>
                <td>${r.codigo ?? "-"}</td>
                <td>${r.producto ?? "-"}</td>
                <td>${r.tipoPnc ?? "-"}</td>
                <td>${r.categoriaDefecto ?? "-"}</td>
                <td>${r.nivel ?? "-"}</td>
                <td>${r.tipoFalla ?? "-"}</td>
                <td>${r.cantRequerida ?? "-"}</td>
                <td>${r.cantRechazada ?? "-"}</td>
                <td>${r.cantRecuperada ?? "-"}</td>
                <td>${r.pctRecuperacion ?? "-"}</td>
                <td>${r.observacion ?? "-"}</td>
            </tr>
        `).join("");

        this._filasFijas?.refrescar();
    }

    _renderPaginacion() {
        const totalPaginas = Math.max(1, Math.ceil(this._totalCount / this._pageSize));
        document.getElementById("fd-pagina-info").textContent = `Página ${this._page} de ${totalPaginas}`;
        document.getElementById("fd-anterior-btn").disabled = this._page <= 1;
        document.getElementById("fd-siguiente-btn").disabled = this._page >= totalPaginas;
    }

    _hayFiltrosActivos() {
        const f = this._getFiltros();
        return Object.values(f).some(v => String(v || "").trim() !== "");
    }

    async _exportar() {
        if (this._hayFiltrosActivos()) {
            window.ExcelExporter.exportTable({
                tableSelector: "#fd-tabla",
                fileName: `faret_data_${Date.now()}.xlsx`,
                sheetName: "Data",
                title: "QCC Faret - Data"
            });
            return;
        }

        const items = await this._traerTodosLosRegistros();
        const tabla = this._construirTablaTemp(items);
        window.ExcelExporter.exportTable({
            tableSelector: "#fd-tabla-export-temp",
            fileName: `faret_data_todos_${Date.now()}.xlsx`,
            sheetName: "Data",
            title: "QCC Faret - Data"
        });
        tabla.remove();
    }

    async _imprimir() {
        if (this._hayFiltrosActivos()) {
            window.PrintExporter.printTable({
                tableSelector: "#fd-tabla",
                titulo: "Data",
                empresa: "FARET",
                subtitulo: this._resumenFiltrosTexto(),
            });
            return;
        }

        const items = await this._traerTodosLosRegistros();
        const tabla = this._construirTablaTemp(items);
        window.PrintExporter.printTable({
            tableSelector: "#fd-tabla-export-temp",
            titulo: "Data",
            empresa: "FARET",
            subtitulo: this._resumenFiltrosTexto(),
            totalRegistros: items.length,
        });
        tabla.remove();
    }

    _resumenFiltrosTexto() {
        const f = this._getFiltros();
        const partes = [];
        if (f.cliente) partes.push(`Cliente: ${f.cliente}`);
        if (f.tipoPnc) partes.push(`Tipo PNC: ${f.tipoPnc}`);
        if (f.nivel) partes.push(`Nivel: ${f.nivel}`);
        if (f.fechaDesde) partes.push(`Fecha desde: ${f.fechaDesde}`);
        if (f.fechaHasta) partes.push(`Fecha hasta: ${f.fechaHasta}`);
        return partes.length ? partes.join(" · ") : "Sin filtros — histórico completo";
    }

    async _traerTodosLosRegistros(filtros = this._getFiltros()) {
        const pageSize = 500;
        let page = 1;
        let total = Infinity;
        const items = [];

        while (items.length < total && page <= 200) {
            const res = await window.PhotinoBridge.send({
                action: "faret.data.list",
                page,
                pageSize,
                ...filtros,
            });

            if (!res.ok) break;

            const lote = Array.isArray(res.data.items) ? res.data.items : [];
            if (!lote.length) break;

            items.push(...lote);
            total = res.data.totalCount ?? items.length;
            page++;
        }

        return items;
    }

    _construirTablaTemp(items) {
        const tabla = document.createElement("table");
        tabla.id = "fd-tabla-export-temp";
        tabla.style.position = "absolute";
        tabla.style.left = "-99999px";
        tabla.style.top = "0";

        tabla.innerHTML = `
            <thead>
                <tr>
                    <th>ID</th>
                    <th>Fecha ingreso</th>
                    <th>Fecha salida</th>
                    <th>NP/NV</th>
                    <th>Cliente</th>
                    <th>Código</th>
                    <th>Producto</th>
                    <th>Tipo PNC</th>
                    <th>Categoría defecto</th>
                    <th>Nivel</th>
                    <th>Tipo falla</th>
                    <th>Cant. requerida</th>
                    <th>Cant. rechazada</th>
                    <th>Cant. recuperada</th>
                    <th>% recuperación</th>
                    <th>Observación</th>
                </tr>
            </thead>
            <tbody>
                ${items.map(r => `
                    <tr>
                        <td>${r.id ?? "-"}</td>
                        <td>${window.DateUtils.formatear(r.fechaIngreso)}</td>
                        <td>${window.DateUtils.formatear(r.fechaSalida)}</td>
                        <td>${r.npNv ?? "-"}</td>
                        <td>${r.cliente ?? "-"}</td>
                        <td>${r.codigo ?? "-"}</td>
                        <td>${r.producto ?? "-"}</td>
                        <td>${r.tipoPnc ?? "-"}</td>
                        <td>${r.categoriaDefecto ?? "-"}</td>
                        <td>${r.nivel ?? "-"}</td>
                        <td>${r.tipoFalla ?? "-"}</td>
                        <td>${r.cantRequerida ?? "-"}</td>
                        <td>${r.cantRechazada ?? "-"}</td>
                        <td>${r.cantRecuperada ?? "-"}</td>
                        <td>${r.pctRecuperacion ?? "-"}</td>
                        <td>${r.observacion ?? "-"}</td>
                    </tr>
                `).join("")}
            </tbody>
        `;

        document.body.appendChild(tabla);
        return tabla;
    }
};
