window.FaretInspeccionesController = class FaretInspeccionesController {

    init() {
        console.log("FaretInspeccionesController iniciado");

        this._page = 1;
        this._pageSize = 50;
        this._totalCount = 0;
        this._items = [];
        this._sortCampo = null;
        this._sortAsc = true;

        document.getElementById("fi-refresh-btn")
            ?.addEventListener("click", () => this._loadAll());

        document.getElementById("fi-filtrar-btn")
            ?.addEventListener("click", () => { this._page = 1; this._loadAll(); });

        document.getElementById("fi-limpiar-btn")
            ?.addEventListener("click", () => this._limpiarFiltros());

        document.getElementById("fi-anterior-btn")
            ?.addEventListener("click", () => this._irPagina(this._page - 1));

        document.getElementById("fi-siguiente-btn")
            ?.addEventListener("click", () => this._irPagina(this._page + 1));

        document.querySelectorAll(".fi-th-ordenable").forEach(th =>
            th.addEventListener("click", () => this._ordenarPor(th.dataset.campo)));

        document.getElementById("fi-exportar-btn")
            ?.addEventListener("click", () => this._exportar());

        document.getElementById("fi-imprimir-btn")
            ?.addEventListener("click", () => this._imprimir());

        document.getElementById("fi-tbody")?.addEventListener("click", (e) => {
            const verBtn = e.target.closest(".fi-ver-adjuntos-btn");
            if (verBtn) this._verAdjuntos(verBtn.dataset.id);

            const eliminarBtn = e.target.closest(".fi-eliminar-btn");
            if (eliminarBtn) this._eliminar(eliminarBtn.dataset.id);
        });

        this._filasFijas = window.TableUtils.init(
            document.getElementById("fi-tabla"),
            document.getElementById("fi-filas-fijadas")
        );

        this._loadAll();
    }

    destroy() {
        console.log("FaretInspeccionesController destruido");
    }

    _getFiltros() {
        return {
            fechaDesde: document.getElementById("fi-filtro-fecha-desde")?.value || "",
            fechaHasta: document.getElementById("fi-filtro-fecha-hasta")?.value || "",
            nvFaret: document.getElementById("fi-filtro-nv-faret")?.value.trim() || "",
            areaControl: document.getElementById("fi-filtro-area")?.value || "",
            operador: document.getElementById("fi-filtro-operador")?.value.trim() || "",
            maquina: document.getElementById("fi-filtro-maquina")?.value.trim() || "",
            presentaDefectos: document.getElementById("fi-filtro-defectos")?.value || "",
        };
    }

    _limpiarFiltros() {
        document.getElementById("fi-filtro-fecha-desde").value = "";
        document.getElementById("fi-filtro-fecha-hasta").value = "";
        document.getElementById("fi-filtro-nv-faret").value = "";
        document.getElementById("fi-filtro-area").value = "";
        document.getElementById("fi-filtro-operador").value = "";
        document.getElementById("fi-filtro-maquina").value = "";
        document.getElementById("fi-filtro-defectos").value = "";
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

    // Arma las opciones de los <select> de Operador/Máquina con los valores reales de TODOS los
    // registros (sin filtros), no solo la página visible — reutiliza _traerTodosLosRegistros.
    async _poblarFiltrosSelect() {
        const items = await this._traerTodosLosRegistros({});

        const mapa = {
            "fi-filtro-operador": "operador",
            "fi-filtro-maquina": "maquina",
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
                action: "faret.inspecciones.resumen",
                ...this._getFiltros(),
            });

            this._renderResumen(res.ok ? res.data : null);
        } catch {
            this._renderResumen(null);
        }
    }

    _renderResumen(data) {
        document.getElementById("fi-kpi-hoy").textContent = data?.inspeccionesHoy ?? 0;
        document.getElementById("fi-kpi-periodo").textContent = data?.inspeccionesPeriodo ?? 0;
        document.getElementById("fi-kpi-con-defectos").textContent = data?.conDefectos ?? 0;
        document.getElementById("fi-kpi-sin-defectos").textContent = data?.sinDefectos ?? 0;
    }

    async _loadLista() {
        const contenedor = document.getElementById("fi-tabla")?.closest(".table-container");
        await window.TableUtils.preservarScroll(contenedor, () => this._loadListaInterna());
    }

    async _loadListaInterna() {
        const loadingEl = document.getElementById("fi-loading");
        const errorEl = document.getElementById("fi-error");

        loadingEl.style.display = "block";
        errorEl.style.display = "none";

        try {
            const res = await window.PhotinoBridge.send({
                action: "faret.inspecciones.list",
                page: this._page,
                pageSize: this._pageSize,
                ...this._getFiltros(),
            });

            if (!res.ok) {
                this._aplicarListaVacia();
                return;
            }

            this._totalCount = res.data.totalCount ?? 0;
            this._page = res.data.page ?? this._page;
            this._items = Array.isArray(res.data.items) ? res.data.items : [];
            this._sortCampo = null;
            this._renderTabla(this._items);
            this._renderPaginacion();
        } catch {
            this._aplicarListaVacia();
        } finally {
            loadingEl.style.display = "none";
        }
    }

    _aplicarListaVacia() {
        this._totalCount = 0;
        this._items = [];
        this._renderTabla([]);
        this._renderPaginacion();
    }

    _ordenarPor(campo) {
        if (!campo || !this._items.length) return;

        this._sortAsc = this._sortCampo === campo ? !this._sortAsc : true;
        this._sortCampo = campo;

        const ordenados = [...this._items].sort((a, b) => {
            const valorA = a[campo] ?? "";
            const valorB = b[campo] ?? "";
            if (valorA < valorB) return this._sortAsc ? -1 : 1;
            if (valorA > valorB) return this._sortAsc ? 1 : -1;
            return 0;
        });

        this._renderTabla(ordenados);
        this._actualizarIconosOrden();
    }

    _actualizarIconosOrden() {
        document.querySelectorAll(".fi-th-ordenable").forEach(th => {
            const activo = th.dataset.campo === this._sortCampo;
            th.classList.toggle("fi-th-activo", activo);
            const icono = th.querySelector(".fi-sort-icon");
            if (icono) icono.textContent = activo ? (this._sortAsc ? "↑" : "↓") : "↕";
        });
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
        const tbody = document.getElementById("fi-tbody");

        if (!items.length) {
            tbody.innerHTML = `<tr><td colspan="13">${this._emptyStateHtml()}</td></tr>`;
            this._filasFijas?.refrescar();
            return;
        }

        tbody.innerHTML = items.map(i => `
            <tr data-id="${i.id}">
                <td>${this._formatoFecha(i.fechaRegistro)}</td>
                <td>${i.horaRegistro ?? "-"}</td>
                <td>${i.operadorRegistro ?? "-"}</td>
                <td>${i.nvFaret ?? "-"}</td>
                <td>${i.areaControl ?? "-"}</td>
                <td>${i.maquina ?? "-"}</td>
                <td>${i.operador === "Otros" ? (i.operadorOtro || "Otros") : (i.operador ?? "-")}</td>
                <td>${this._defectosBadge(i.presentaDefectos)}</td>
                <td>${i.defectos ?? "-"}</td>
                <td>${i.accionCorrectiva ?? "-"}</td>
                <td>${this._celdaAdjuntos(i)}</td>
                <td><button class="btn-secondary fi-eliminar-btn" data-id="${i.id}">Eliminar</button></td>
            </tr>
        `).join("");

        this._filasFijas?.refrescar();
    }

    _celdaAdjuntos(i) {
        const cantidad = i.cantidadAdjuntos ?? 0;
        if (!cantidad) return "0";
        return `<button class="btn-secondary fi-ver-adjuntos-btn" data-id="${i.id}">Ver adjuntos (${cantidad})</button>`;
    }

    async _eliminar(id) {
        if (!confirm("¿Eliminar este registro de inspección? Esta acción no se puede deshacer desde la pantalla.")) return;

        try {
            const res = await window.PhotinoBridge.send({ action: "faret.inspecciones.eliminar", id: Number(id) });
            if (!res.ok) {
                alert(res.error || "Error al eliminar el registro");
                return;
            }
            this._loadAll();
        } catch {
            alert("Error de comunicación con el backend");
        }
    }

    _emptyStateHtml() {
        return `
            <div class="fi-empty-state">
                <div class="fi-empty-state-icon">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6"><rect x="3" y="5" width="18" height="16" rx="2"/><path d="M16 3v4M8 3v4M3 10h18"/><path d="M8 15h3M8 18h5"/></svg>
                </div>
                <div class="fi-empty-state-title">No hay inspecciones para los filtros aplicados</div>
                <div class="fi-empty-state-subtitle">Las inspecciones enviadas desde la aplicación móvil FARET (formulario Calidad/Producción) aparecerán aquí.</div>
            </div>
        `;
    }

    _renderPaginacion() {
        const totalPaginas = Math.max(1, Math.ceil(this._totalCount / this._pageSize));
        document.getElementById("fi-pagina-info").textContent = `Página ${this._page} de ${totalPaginas}`;
        document.getElementById("fi-anterior-btn").disabled = this._page <= 1;
        document.getElementById("fi-siguiente-btn").disabled =
            this._page >= totalPaginas || this._totalCount === 0;
    }

    _hayFiltrosActivos() {
        const f = this._getFiltros();
        return Object.values(f).some(v => String(v || "").trim() !== "");
    }

    async _exportar() {
        if (this._hayFiltrosActivos()) {
            window.ExcelExporter.exportTable({
                tableSelector: "#fi-tabla",
                fileName: `faret_inspecciones_${Date.now()}.xlsx`,
                sheetName: "Inspecciones",
                title: "QCC Faret - Inspecciones"
            });
            return;
        }

        const items = await this._traerTodosLosRegistros();
        const tabla = this._construirTablaTemp(items);
        window.ExcelExporter.exportTable({
            tableSelector: "#fi-tabla-export-temp",
            fileName: `faret_inspecciones_todos_${Date.now()}.xlsx`,
            sheetName: "Inspecciones",
            title: "QCC Faret - Inspecciones"
        });
        tabla.remove();
    }

    async _imprimir() {
        if (this._hayFiltrosActivos()) {
            window.PrintExporter.printTable({
                tableSelector: "#fi-tabla",
                titulo: "Inspecciones",
                empresa: "FARET",
                subtitulo: this._resumenFiltrosTexto(),
            });
            return;
        }

        const items = await this._traerTodosLosRegistros();
        const tabla = this._construirTablaTemp(items);
        window.PrintExporter.printTable({
            tableSelector: "#fi-tabla-export-temp",
            titulo: "Inspecciones",
            empresa: "FARET",
            subtitulo: this._resumenFiltrosTexto(),
            totalRegistros: items.length,
        });
        tabla.remove();
    }

    _resumenFiltrosTexto() {
        const f = this._getFiltros();
        const partes = [];
        if (f.nvFaret) partes.push(`NV Faret: ${f.nvFaret}`);
        if (f.areaControl) partes.push(`Área de control: ${f.areaControl}`);
        if (f.operador) partes.push(`Operador: ${f.operador}`);
        if (f.maquina) partes.push(`Máquina: ${f.maquina}`);
        if (f.presentaDefectos) partes.push(`¿Defectos?: ${f.presentaDefectos === "true" ? "Sí" : "No"}`);
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
                action: "faret.inspecciones.list",
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
        tabla.id = "fi-tabla-export-temp";
        tabla.style.position = "absolute";
        tabla.style.left = "-99999px";
        tabla.style.top = "0";

        tabla.innerHTML = `
            <thead>
                <tr>
                    <th>Fecha</th>
                    <th>Hora</th>
                    <th>Operador Calidad</th>
                    <th>NV Faret</th>
                    <th>Área de control</th>
                    <th>Máquina</th>
                    <th>Operador Máquina</th>
                    <th>¿Defectos?</th>
                    <th>Defectos</th>
                    <th>Acción correctiva</th>
                    <th>Adjuntos</th>
                </tr>
            </thead>
            <tbody>
                ${items.map(i => `
                    <tr>
                        <td>${this._formatoFecha(i.fechaRegistro)}</td>
                        <td>${i.horaRegistro ?? "-"}</td>
                        <td>${i.operadorRegistro ?? "-"}</td>
                        <td>${i.nvFaret ?? "-"}</td>
                        <td>${i.areaControl ?? "-"}</td>
                        <td>${i.maquina ?? "-"}</td>
                        <td>${i.operador === "Otros" ? (i.operadorOtro || "Otros") : (i.operador ?? "-")}</td>
                        <td>${i.presentaDefectos ? "Sí" : "No"}</td>
                        <td>${i.defectos ?? "-"}</td>
                        <td>${i.accionCorrectiva ?? "-"}</td>
                        <td>${i.cantidadAdjuntos ?? 0}</td>
                    </tr>
                `).join("")}
            </tbody>
        `;

        document.body.appendChild(tabla);
        return tabla;
    }

    // ---------- Ver adjuntos (fotos subidas desde la app móvil) ----------

    async _verAdjuntos(id) {
        this._cerrarAdjuntos();

        const modal = document.createElement("div");
        modal.id = "fi-modal-adjuntos";
        modal.style.position = "fixed";
        modal.style.left = "0";
        modal.style.top = "0";
        modal.style.width = "100%";
        modal.style.height = "100%";
        modal.style.background = "rgba(15, 23, 42, 0.75)";
        modal.style.zIndex = "9999";
        modal.style.display = "flex";
        modal.style.alignItems = "center";
        modal.style.justifyContent = "center";
        modal.style.padding = "24px";

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
                <div style="display:flex;justify-content:space-between;align-items:center;gap:12px;margin-bottom:12px;">
                    <strong>Adjuntos del registro</strong>
                    <button id="fi-cerrar-adjuntos-btn" class="btn-secondary" type="button">Cerrar</button>
                </div>
                <div id="fi-adjuntos-contenido" style="min-width:280px;min-height:120px;display:flex;align-items:center;justify-content:center;color:#64748B;">
                    Cargando...
                </div>
            </div>
        `;

        document.body.appendChild(modal);
        modal.addEventListener("click", (e) => { if (e.target === modal) this._cerrarAdjuntos(); });
        document.getElementById("fi-cerrar-adjuntos-btn")
            ?.addEventListener("click", () => this._cerrarAdjuntos());

        try {
            const res = await window.PhotinoBridge.send({ action: "faret.inspecciones.adjuntos", id: Number(id) });
            const contenido = document.getElementById("fi-adjuntos-contenido");
            if (!contenido) return; // el modal ya se cerró antes de que llegara la respuesta

            if (!res.ok) {
                contenido.innerHTML = `<div class="faret-error">${this._escape(res.error || "Error al cargar los adjuntos")}</div>`;
                return;
            }

            const adjuntos = Array.isArray(res.data) ? res.data : [];
            if (!adjuntos.length) {
                contenido.innerHTML = `<div>Sin adjuntos disponibles.</div>`;
                return;
            }

            this._renderAdjuntos(adjuntos);
        } catch {
            const contenido = document.getElementById("fi-adjuntos-contenido");
            if (contenido) contenido.innerHTML = `<div class="faret-error">Error de comunicación con el backend</div>`;
        }
    }

    _renderAdjuntos(adjuntos) {
        const contenido = document.getElementById("fi-adjuntos-contenido");
        if (!contenido) return;

        contenido.style.display = "block";
        contenido.innerHTML = `
            <img id="fi-adjunto-imagen-principal"
                 src="${this._escape(this._normalizarImagenUrl(adjuntos[0].rutaArchivo))}"
                 alt="Adjunto"
                 style="display:block;max-width:100%;max-height:65vh;object-fit:contain;border-radius:8px;margin:0 auto;">
            ${adjuntos.length > 1 ? `
                <div style="display:flex;gap:8px;margin-top:12px;overflow-x:auto;padding-bottom:4px;">
                    ${adjuntos.map((a, idx) => `
                        <img src="${this._escape(this._normalizarImagenUrl(a.rutaArchivo))}"
                             data-index="${idx}"
                             class="fi-adjunto-miniatura"
                             style="width:64px;height:64px;object-fit:cover;border-radius:6px;cursor:pointer;border:2px solid ${idx === 0 ? "#2563EB" : "transparent"};">
                    `).join("")}
                </div>
            ` : ""}
        `;

        contenido.querySelectorAll(".fi-adjunto-miniatura").forEach(thumb => {
            thumb.addEventListener("click", () => {
                const idx = Number(thumb.dataset.index);
                const principal = document.getElementById("fi-adjunto-imagen-principal");
                if (principal) principal.src = this._normalizarImagenUrl(adjuntos[idx].rutaArchivo);
                contenido.querySelectorAll(".fi-adjunto-miniatura").forEach(t => t.style.borderColor = "transparent");
                thumb.style.borderColor = "#2563EB";
            });
        });
    }

    _cerrarAdjuntos() {
        const modal = document.getElementById("fi-modal-adjuntos");
        if (modal) modal.remove();
    }

    _normalizarImagenUrl(url) {
        const value = String(url || "").trim();

        if (!value) return "";
        if (value.startsWith("http://") || value.startsWith("https://")) return value;
        if (value.startsWith("/")) return `https://api.faret.cl/calidad${value}`;
        return `https://api.faret.cl/calidad/${value}`;
    }

    _escape(value) {
        return String(value ?? "")
            .replaceAll("&", "&amp;")
            .replaceAll("<", "&lt;")
            .replaceAll(">", "&gt;")
            .replaceAll('"', "&quot;")
            .replaceAll("'", "&#039;");
    }
};
