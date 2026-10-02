// Talleres Externos (INNPACK) — listado + formulario, mismo patrón que el módulo homónimo de
// Faret (tabla HTML + modal de alta/edición con guardado inmediato), pero con backend propio
// (acciones talleresExternos.* → TalleresExternosHandler → MySQL calidad directo, sin API Faret
// ni tablas compartidas con esa implementación).

const TE_ESTADO_LABELS = {
    PENDIENTE_ASIGNACION: "Pendiente asignación",
    ASIGNADO: "Asignado",
    EN_PROCESO: "En proceso",
    ENTREGADO: "Entregado",
    ANULADO: "Anulado",
};

function teFormatNumero(n) {
    const valor = Number(n);
    if (Number.isNaN(valor)) return "0";
    return valor.toLocaleString("es-CL", { maximumFractionDigits: 2 });
}

function teNumeroOCero(v) {
    if (v === "" || v === null || v === undefined) return 0;
    const numero = Number(String(v).trim().replace(",", "."));
    return Number.isNaN(numero) ? 0 : numero;
}

function teBadge(texto, claseColor) {
    if (!texto) return "";
    return `<span class="te-badge ${claseColor}">${texto}</span>`;
}

function teBadgePrioridad(valor) {
    const clase = valor === "ALTA" ? "te-badge-alta" : valor === "BAJA" ? "te-badge-baja" : "te-badge-media";
    return teBadge(valor || "-", clase);
}

function teBadgeEstado(valor) {
    const clase = "te-badge-estado-" + String(valor || "").toLowerCase();
    return teBadge(TE_ESTADO_LABELS[valor] || valor || "-", clase);
}

window.TalleresExternosController = class {
    init() {
        this._items = [];
        this._catalogos = { talleres: [], procesos: [] };
        this._editingId = null;
        this._editingVersion = null;
        this._page = 1;
        this._pageSize = 50;

        document.getElementById("te-nuevo-btn")?.addEventListener("click", () => this._abrirNuevo());
        document.getElementById("te-form-cerrar-btn")?.addEventListener("click", () => this._cerrarForm());
        document.getElementById("te-form-cancelar-btn")?.addEventListener("click", () => this._cerrarForm());
        document.getElementById("te-form-guardar-btn")?.addEventListener("click", () => this._guardar());
        document.getElementById("te-exportar-btn")?.addEventListener("click", () => this._exportar());
        document.getElementById("te-sincronizar-fps-btn")?.addEventListener("click", () => this._sincronizarFps());
        document.getElementById("te-actualizar-btn")?.addEventListener("click", () => this._cargarTodo());
        document.getElementById("te-filtrar-btn")?.addEventListener("click", () => { this._page = 1; this._renderTabla(); });
        document.getElementById("te-limpiar-btn")?.addEventListener("click", () => this._limpiarFiltros());
        document.getElementById("te-anterior-btn")?.addEventListener("click", () => this._irPagina(this._page - 1));
        document.getElementById("te-siguiente-btn")?.addEventListener("click", () => this._irPagina(this._page + 1));
        document.getElementById("te-busqueda-rapida")?.addEventListener("input", this._debounce(() => {
            this._page = 1;
            this._renderTabla();
        }, 200));
        document.getElementById("te-gestionar-catalogos-btn")?.addEventListener("click", () => this._abrirGestionCatalogos());
        document.getElementById("te-catalogos-cerrar-btn")?.addEventListener("click", () => this._cerrarGestionCatalogos());

        ["te-form-cant-revisar", "te-form-cant-entregada"].forEach((id) =>
            document.getElementById(id)?.addEventListener("input", () => this._recalcularCantidadFaltante())
        );

        this._wireCombos();
        this._cargarTodo();
    }

    destroy() {
        // Sin listeners en document/window: este módulo no engancha nada fuera de su propio DOM.
    }

    _debounce(fn, ms) {
        let temporizador;
        return (...args) => {
            clearTimeout(temporizador);
            temporizador = setTimeout(() => fn(...args), ms);
        };
    }

    _esc(v) {
        if (v === null || v === undefined) return "";
        const div = document.createElement("div");
        div.textContent = String(v);
        return div.innerHTML;
    }

    _send(action, data) {
        return window.PhotinoBridge.send({ action, data });
    }

    // ---------- Carga ----------

    async _cargarTodo() {
        const loadingEl = document.getElementById("te-loading");
        const errorEl = document.getElementById("te-error");
        loadingEl.style.display = "block";
        errorEl.style.display = "none";

        try {
            await this._cargarCatalogos();
            await this._cargarListaCompleta();
            this._poblarFiltrosSelect();
            this._renderTabla();
        } catch {
            errorEl.textContent = "Error de comunicación con el backend";
            errorEl.style.display = "block";
        } finally {
            loadingEl.style.display = "none";
        }
    }

    async _cargarCatalogos() {
        const res = await this._send("talleresExternos.catalogos", {});
        if (res.ok) this._catalogos = res.data || { talleres: [], procesos: [] };
    }

    // Recorre todas las páginas: sin filtros de servidor, el filtrado/búsqueda queda 100% en
    // memoria una vez cargado el listado completo (mismo patrón que la versión Faret).
    async _cargarListaCompleta() {
        const errorEl = document.getElementById("te-error");
        const pageSize = 200;
        let page = 1;
        let items = [];
        let totalCount = Infinity;
        let safety = 0;

        while (items.length < totalCount && safety < 200) {
            safety++;
            const res = await this._send("talleresExternos.list", { page, pageSize });
            if (!res.ok) {
                errorEl.textContent = res.error || "No se pudo cargar el listado.";
                errorEl.style.display = "block";
                break;
            }
            const data = res.data || {};
            const pageItems = data.items || [];
            items = items.concat(pageItems);
            totalCount = typeof data.totalCount === "number" ? data.totalCount : items.length;
            if (pageItems.length === 0) break;
            page++;
        }

        this._items = items;
    }

    // ---------- Filtros ----------

    _getFiltros() {
        return {
            busqueda: (document.getElementById("te-busqueda-rapida")?.value || "").trim().toLowerCase(),
            taller: document.getElementById("te-filtro-taller")?.value || "",
            proceso: document.getElementById("te-filtro-proceso")?.value || "",
            responsable: document.getElementById("te-filtro-responsable")?.value || "",
            prioridad: document.getElementById("te-filtro-prioridad")?.value || "",
            estado: document.getElementById("te-filtro-estado")?.value || "",
            fechaAsigDesde: document.getElementById("te-filtro-fecha-asig-desde")?.value || "",
            fechaAsigHasta: document.getElementById("te-filtro-fecha-asig-hasta")?.value || "",
        };
    }

    _limpiarFiltros() {
        [
            "te-busqueda-rapida", "te-filtro-taller", "te-filtro-proceso", "te-filtro-responsable",
            "te-filtro-prioridad", "te-filtro-estado", "te-filtro-fecha-asig-desde", "te-filtro-fecha-asig-hasta",
        ].forEach((id) => {
            const el = document.getElementById(id);
            if (el) el.value = "";
        });
        this._page = 1;
        this._renderTabla();
    }

    _filtrarItems() {
        const f = this._getFiltros();
        return this._items.filter((it) => {
            if (f.taller && (it.tallerExternoTexto || "") !== f.taller) return false;
            if (f.proceso && (it.procesoTexto || "") !== f.proceso) return false;
            if (f.responsable && (it.responsableInternoTexto || "") !== f.responsable) return false;
            if (f.prioridad && it.prioridad !== f.prioridad) return false;
            if (f.estado && it.estado !== f.estado) return false;

            if (f.fechaAsigDesde || f.fechaAsigHasta) {
                const fecha = it.fechaAsignacion ? String(it.fechaAsignacion).substring(0, 10) : "";
                if (!fecha) return false;
                if (f.fechaAsigDesde && fecha < f.fechaAsigDesde) return false;
                if (f.fechaAsigHasta && fecha > f.fechaAsigHasta) return false;
            }

            if (f.busqueda) {
                const texto = [it.nv, it.producto, it.codigoProducto, it.cliente, it.observaciones]
                    .map((v) => (v == null ? "" : String(v)))
                    .join(" ")
                    .toLowerCase();
                if (!texto.includes(f.busqueda)) return false;
            }

            return true;
        });
    }

    _poblarFiltrosSelect() {
        const poblar = (id, valores) => {
            const select = document.getElementById(id);
            if (!select) return;
            const actual = select.value;
            select.innerHTML = '<option value="">Todos</option>' +
                valores.map((v) => `<option value="${this._esc(v)}">${this._esc(v)}</option>`).join("");
            if (actual && valores.includes(actual)) select.value = actual;
        };

        const unicos = (campo) =>
            [...new Set(this._items.map((it) => (it[campo] || "").toString().trim()).filter(Boolean))].sort();

        poblar("te-filtro-taller", unicos("tallerExternoTexto"));
        poblar("te-filtro-proceso", unicos("procesoTexto"));
        poblar("te-filtro-responsable", unicos("responsableInternoTexto"));
    }

    // ---------- Tabla ----------

    _renderTabla() {
        const filtrados = this._filtrarItems();
        this._renderResumen(filtrados);

        const totalPaginas = Math.max(1, Math.ceil(filtrados.length / this._pageSize));
        if (this._page > totalPaginas) this._page = totalPaginas;
        if (this._page < 1) this._page = 1;

        const inicio = (this._page - 1) * this._pageSize;
        const items = filtrados.slice(inicio, inicio + this._pageSize);

        const tbody = document.getElementById("te-tbody");

        if (!items.length) {
            tbody.innerHTML = `<tr><td colspan="20" class="te-empty">Sin registros</td></tr>`;
            this._renderPaginacion(filtrados.length);
            return;
        }

        tbody.innerHTML = items.map((it) => `
            <tr data-id="${it.id}">
                <td>${this._esc(it.nv)}</td>
                <td>${this._esc(it.producto)}</td>
                <td>${this._esc(it.codigoProducto)}</td>
                <td>${this._esc(it.item)}</td>
                <td>${this._esc(it.cliente)}</td>
                <td>${window.DateUtils.formatear(it.fechaAsignacion)}</td>
                <td>${this._esc(it.tallerExternoTexto)}</td>
                <td>${this._esc(it.procesoTexto)}</td>
                <td>${this._esc(it.responsableInternoTexto)}</td>
                <td>${teBadgePrioridad(it.prioridad)}</td>
                <td>${it.fechaCompromiso
                    ? (it.atrasado
                        ? `<span class="te-fecha-vencida">${window.DateUtils.formatear(it.fechaCompromiso)} ⚠</span>`
                        : window.DateUtils.formatear(it.fechaCompromiso))
                    : "-"}</td>
                <td>${teBadgeEstado(it.estado)}</td>
                <td style="text-align:right;">${teFormatNumero(it.cantidadARevisar)}</td>
                <td style="text-align:right;">${teFormatNumero(it.cantidadRevisadaEntregada)}</td>
                <td style="text-align:right;">${teFormatNumero(it.cantidadFaltante)}</td>
                <td><button class="btn-ghost te-historial-fps-btn" data-id="${it.id}">Ver historial (${it.totalLiberacionesFps || 0})</button></td>
                <td class="te-precio-celda">${this._esc(it.precioCotizacion)}</td>
                <td class="te-precio-celda">${this._esc(it.precioTaller)}</td>
                <td class="te-obs-celda" title="${this._esc(it.observaciones)}">${this._esc(it.observaciones)}</td>
                <td>
                    <button class="btn-secondary te-editar-btn" data-id="${it.id}">Editar</button>
                    <button class="btn-danger te-eliminar-btn" data-id="${it.id}">Eliminar</button>
                </td>
            </tr>
        `).join("");

        tbody.querySelectorAll(".te-editar-btn").forEach((btn) =>
            btn.addEventListener("click", () => this._abrirEditar(Number(btn.dataset.id))));
        tbody.querySelectorAll(".te-eliminar-btn").forEach((btn) =>
            btn.addEventListener("click", () => this._eliminarFila(Number(btn.dataset.id))));
        tbody.querySelectorAll(".te-historial-fps-btn").forEach((btn) =>
            btn.addEventListener("click", () => this._verHistorialFps(btn, Number(btn.dataset.id))));

        this._renderPaginacion(filtrados.length);
    }

    _renderResumen(items) {
        const set = (id, valor) => {
            const el = document.getElementById(id);
            if (el) el.textContent = valor;
        };
        set("te-kpi-activos", items.filter((i) => !["ENTREGADO", "ANULADO"].includes(i.estado)).length);
        set("te-kpi-por-asignar", items.filter((i) => i.estado === "PENDIENTE_ASIGNACION").length);
        set("te-kpi-atrasados", items.filter((i) => i.atrasado).length);
        set("te-kpi-entregados", items.filter((i) => i.estado === "ENTREGADO").length);
        const faltanteTotal = items
            .filter((i) => i.estado !== "ANULADO")
            .reduce((acc, i) => acc + teNumeroOCero(i.cantidadFaltante), 0);
        set("te-kpi-faltante-total", teFormatNumero(faltanteTotal));
    }

    _renderPaginacion(totalFiltrado) {
        const totalPaginas = Math.max(1, Math.ceil(totalFiltrado / this._pageSize));
        document.getElementById("te-pagina-info").textContent = `Página ${this._page} de ${totalPaginas}`;
        document.getElementById("te-anterior-btn").disabled = this._page <= 1;
        document.getElementById("te-siguiente-btn").disabled = this._page >= totalPaginas;
    }

    _irPagina(pagina) {
        if (pagina < 1) return;
        this._page = pagina;
        this._renderTabla();
    }

    // ---------- Formulario Nuevo / Editar ----------

    _abrirNuevo() {
        this._editingId = null;
        this._editingVersion = null;
        document.getElementById("te-form-titulo").textContent = "Nuevo trabajo";
        document.getElementById("te-form-error").style.display = "none";

        document.getElementById("te-form-nv").value = "";
        document.getElementById("te-form-producto").value = "";
        document.getElementById("te-form-codigo").value = "";
        document.getElementById("te-form-item").value = "";
        document.getElementById("te-form-cliente").value = "";
        document.getElementById("te-form-fecha-asignacion").value = "";
        document.getElementById("te-form-taller").value = "";
        document.getElementById("te-form-proceso").value = "";
        document.getElementById("te-form-responsable").value = "";
        document.getElementById("te-form-prioridad").value = "MEDIA";
        document.getElementById("te-form-fecha-compromiso").value = "";
        document.getElementById("te-form-estado").value = "PENDIENTE_ASIGNACION";
        document.getElementById("te-form-cant-revisar").value = "";
        document.getElementById("te-form-cant-entregada").value = "";
        document.getElementById("te-form-cant-faltante").value = "";
        document.getElementById("te-form-precio-cotizacion").value = "";
        document.getElementById("te-form-precio-taller").value = "";
        document.getElementById("te-form-observaciones").value = "";

        document.getElementById("te-form-modal").style.display = "flex";
    }

    _abrirEditar(id) {
        const it = this._items.find((i) => i.id === id);
        if (!it) return;

        this._editingId = it.id;
        this._editingVersion = it.version;
        document.getElementById("te-form-titulo").textContent = `Editar trabajo NV ${it.nv || ""}`;
        document.getElementById("te-form-error").style.display = "none";

        document.getElementById("te-form-nv").value = it.nv || "";
        document.getElementById("te-form-producto").value = it.producto || "";
        document.getElementById("te-form-codigo").value = it.codigoProducto || "";
        document.getElementById("te-form-item").value = it.item || "";
        document.getElementById("te-form-cliente").value = it.cliente || "";
        document.getElementById("te-form-fecha-asignacion").value = it.fechaAsignacion ? String(it.fechaAsignacion).substring(0, 10) : "";
        document.getElementById("te-form-taller").value = it.tallerExternoTexto || "";
        document.getElementById("te-form-proceso").value = it.procesoTexto || "";
        document.getElementById("te-form-responsable").value = it.responsableInternoTexto || "";
        document.getElementById("te-form-prioridad").value = it.prioridad || "MEDIA";
        document.getElementById("te-form-fecha-compromiso").value = it.fechaCompromiso ? String(it.fechaCompromiso).substring(0, 10) : "";
        document.getElementById("te-form-estado").value = it.estado || "PENDIENTE_ASIGNACION";
        document.getElementById("te-form-cant-revisar").value = it.cantidadARevisar ?? "";
        document.getElementById("te-form-cant-entregada").value = it.cantidadRevisadaEntregada ?? "";
        this._recalcularCantidadFaltante();
        document.getElementById("te-form-precio-cotizacion").value = it.precioCotizacion ?? "";
        document.getElementById("te-form-precio-taller").value = it.precioTaller ?? "";
        document.getElementById("te-form-observaciones").value = it.observaciones || "";

        document.getElementById("te-form-modal").style.display = "flex";
    }

    _cerrarForm() {
        document.getElementById("te-form-modal").style.display = "none";
        this._editingId = null;
        this._editingVersion = null;
    }

    // Mismo cálculo que el backend (CantidadARevisar - CantidadRevisadaEntregada), solo para
    // feedback visual — el valor real que persiste lo calcula el handler.
    _recalcularCantidadFaltante() {
        const revisar = teNumeroOCero(document.getElementById("te-form-cant-revisar").value);
        const entregada = teNumeroOCero(document.getElementById("te-form-cant-entregada").value);
        document.getElementById("te-form-cant-faltante").value = teFormatNumero(revisar - entregada);
    }

    async _guardar() {
        const errorEl = document.getElementById("te-form-error");
        errorEl.style.display = "none";

        const val = (id) => document.getElementById(id).value.trim();

        const nv = val("te-form-nv");
        const producto = val("te-form-producto");
        const item = val("te-form-item");

        if (!nv || !producto || !item) {
            errorEl.textContent = "NV, Producto e Ítem son obligatorios";
            errorEl.style.display = "block";
            return;
        }

        const precioCotizacion = val("te-form-precio-cotizacion");
        const precioTaller = val("te-form-precio-taller");
        if (precioCotizacion.length > 200 || precioTaller.length > 200) {
            errorEl.textContent = "El precio no puede superar 200 caracteres.";
            errorEl.style.display = "block";
            return;
        }

        const payload = {
            nv,
            producto,
            codigoProducto: val("te-form-codigo"),
            item,
            cliente: val("te-form-cliente"),
            fechaAsignacion: val("te-form-fecha-asignacion") || null,
            // Siempre se manda el texto y se deja que el backend resuelva/cree el catálogo por
            // nombre (mismo patrón que la versión Faret).
            tallerExternoNombre: val("te-form-taller"),
            procesoNombre: val("te-form-proceso"),
            responsableInternoNombre: val("te-form-responsable"),
            prioridad: document.getElementById("te-form-prioridad").value,
            fechaCompromiso: val("te-form-fecha-compromiso") || null,
            estado: document.getElementById("te-form-estado").value,
            cantidadARevisar: teNumeroOCero(val("te-form-cant-revisar")),
            cantidadRevisadaEntregada: teNumeroOCero(val("te-form-cant-entregada")),
            cantidadFaltanteAjusteManual: false,
            precioCotizacion,
            precioTaller,
            observaciones: val("te-form-observaciones"),
        };

        const btn = document.getElementById("te-form-guardar-btn");
        btn.disabled = true;
        try {
            const accion = this._editingId ? "talleresExternos.update" : "talleresExternos.create";
            const res = await this._send(accion, {
                ...(this._editingId ? { id: this._editingId, version: this._editingVersion } : {}),
                ...payload,
            });

            if (!res.ok) {
                errorEl.textContent = res.error || "Error al guardar el trabajo";
                errorEl.style.display = "block";
                return;
            }

            this._cerrarForm();
            this._mostrarMensaje(this._editingId ? "Trabajo actualizado" : "Trabajo creado");
            await this._cargarTodo();
        } catch {
            errorEl.textContent = "Error de comunicación con el backend";
            errorEl.style.display = "block";
        } finally {
            btn.disabled = false;
        }
    }

    async _eliminarFila(id) {
        const it = this._items.find((i) => i.id === id);
        if (!it) return;
        if (!confirm(`¿Eliminar el trabajo NV ${it.nv}? Esta acción anula el registro.`)) return;

        const res = await this._send("talleresExternos.eliminar", { id: it.id, version: it.version });
        if (!res.ok) {
            alert(res.error || "No se pudo eliminar el registro.");
            return;
        }

        this._mostrarMensaje(`Trabajo NV ${it.nv} eliminado.`);
        await this._cargarTodo();
    }

    // ---------- Sincronización FPS ----------

    async _sincronizarFps() {
        const btn = document.getElementById("te-sincronizar-fps-btn");
        const textoOriginal = btn.textContent;
        btn.disabled = true;
        btn.textContent = "Sincronizando...";

        try {
            const res = await this._send("talleresExternos.sincronizarFps", {});
            if (!res.ok) {
                alert(res.error || "No se pudo sincronizar con FPS.");
                return;
            }

            const r = res.data || {};
            const errores = r.errores || [];
            let mensaje = `Sincronización FPS: ${r.trabajosRevisados || 0} trabajo(s) revisado(s), ` +
                `${r.liberacionesNuevas || 0} liberación(es) nueva(s) aplicada(s) a ${r.trabajosActualizados || 0} trabajo(s).`;
            if (errores.length) {
                mensaje += ` ${errores.length} con error.`;
                console.warn("Errores de sincronización FPS:", errores);
                this._mostrarErroresFps(btn, errores);
            }
            this._mostrarMensaje(mensaje);

            if ((r.liberacionesNuevas || 0) > 0) await this._cargarTodo();
        } catch {
            alert("Error de comunicación con el backend al sincronizar con FPS.");
        } finally {
            btn.disabled = false;
            btn.textContent = textoOriginal;
        }
    }

    // Antes estos errores solo quedaban en console.warn (nadie los veía en el uso real) — ahora
    // se muestran en un popover, mismo mecanismo que _verHistorialFps, para que el usuario sepa
    // de inmediato por qué un trabajo puntual no sincronizó (ítem/código inválido para FPS, NV
    // sin datos todavía, etc.) sin tener que abrir la consola del navegador.
    _mostrarErroresFps(trigger, errores) {
        const html = `
            <div style="padding:10px 12px; font-weight:600; border-bottom:1px solid #E2E8F0; color:#B91C1C;">
                Trabajos con error al sincronizar (${errores.length})
            </div>
            <ul style="margin:0; padding:10px 12px 12px 28px;">
                ${errores.map((e) => `<li style="margin-bottom:6px;">${this._esc(e)}</li>`).join("")}
            </ul>
        `;
        window.TableUtils.abrirPopover(trigger, html);
    }

    async _verHistorialFps(trigger, id) {
        window.TableUtils.abrirPopover(trigger, `<div style="padding:12px;">Cargando...</div>`);

        const res = await this._send("talleresExternos.historialLiberaciones", { id });
        const historial = res.ok ? (res.data || []) : [];

        let html;
        if (!historial.length) {
            html = `<div style="padding:12px;">Sin liberaciones sincronizadas desde FPS todavía.</div>`;
        } else {
            html = `
                <div style="padding:10px 12px; font-weight:600; border-bottom:1px solid #E2E8F0;">Historial de liberaciones FPS</div>
                <table style="width:100%; border-collapse:collapse;">
                    <thead>
                        <tr style="text-align:left;">
                            <th style="padding:6px 12px;">Folio</th>
                            <th style="padding:6px 12px;">Fecha liberación</th>
                            <th style="padding:6px 12px; text-align:right;">Cantidad</th>
                        </tr>
                    </thead>
                    <tbody>
                        ${historial.map((h) => `
                            <tr style="border-top:1px solid #F1F5F9;">
                                <td style="padding:6px 12px;">${this._esc(h.folioFps)}</td>
                                <td style="padding:6px 12px;">${h.fechaLiberacion ? new Date(h.fechaLiberacion).toLocaleString("es-CL") : "-"}</td>
                                <td style="padding:6px 12px; text-align:right;">${teFormatNumero(h.cantidad)}</td>
                            </tr>
                        `).join("")}
                    </tbody>
                </table>
            `;
        }

        // Puede haber quedado abierto un popover distinto si el usuario hizo clic en otra fila
        // mientras cargaba; abrirPopover ya cierra cualquier popover previo antes de crear el
        // nuevo, así que reabrir con el mismo trigger es seguro.
        window.TableUtils.abrirPopover(trigger, html);
    }

    // ---------- Sugerencias con "x" (Taller/Proceso/Responsable/Cliente/Producto/Código) ----------

    _combosConfig() {
        return [
            { campo: "tallerExternoTexto", clave: "taller", inputId: "te-form-taller", dropdownId: "te-form-taller-dropdown",
                extra: () => this._catalogos.talleres.map((t) => t.nombre) },
            { campo: "procesoTexto", clave: "proceso", inputId: "te-form-proceso", dropdownId: "te-form-proceso-dropdown",
                extra: () => this._catalogos.procesos.map((p) => p.nombre) },
            { campo: "responsableInternoTexto", clave: "responsable", inputId: "te-form-responsable", dropdownId: "te-form-responsable-dropdown" },
            { campo: "cliente", clave: "cliente", inputId: "te-form-cliente", dropdownId: "te-form-cliente-dropdown" },
            { campo: "producto", clave: "producto", inputId: "te-form-producto", dropdownId: "te-form-producto-dropdown" },
            { campo: "codigoProducto", clave: "codigo", inputId: "te-form-codigo", dropdownId: "te-form-codigo-dropdown" },
        ];
    }

    _wireCombos() {
        this._combosConfig().forEach((cfg) => {
            const input = document.getElementById(cfg.inputId);
            const dropdown = document.getElementById(cfg.dropdownId);
            if (!input || !dropdown) return;

            input.addEventListener("focus", () => this._renderComboDropdown(cfg));
            input.addEventListener("input", () => this._renderComboDropdown(cfg));
            input.addEventListener("blur", () => {
                setTimeout(() => {
                    dropdown.style.display = "none";
                }, 150);
            });
        });
    }

    _sugerenciasOcultas(clave) {
        try {
            const raw = localStorage.getItem(`teSugerenciasOcultas_${clave}`);
            return new Set(raw ? JSON.parse(raw) : []);
        } catch {
            return new Set();
        }
    }

    _ocultarSugerencia(clave, valor) {
        const ocultas = this._sugerenciasOcultas(clave);
        ocultas.add(valor);
        localStorage.setItem(`teSugerenciasOcultas_${clave}`, JSON.stringify([...ocultas]));
    }

    _valoresHistoricos(campo, extra) {
        const valores = new Set(this._items.map((it) => (it[campo] || "").toString().trim()).filter(Boolean));
        (extra ? extra() : []).forEach((v) => {
            if (v) valores.add(v.toString().trim());
        });
        return [...valores].sort();
    }

    _renderComboDropdown(cfg) {
        const input = document.getElementById(cfg.inputId);
        const dropdown = document.getElementById(cfg.dropdownId);
        if (!input || !dropdown) return;

        const filtro = input.value.trim().toLowerCase();
        const ocultas = this._sugerenciasOcultas(cfg.clave);
        const opciones = this._valoresHistoricos(cfg.campo, cfg.extra)
            .filter((v) => !ocultas.has(v))
            .filter((v) => !filtro || v.toLowerCase().includes(filtro));

        if (!opciones.length) {
            dropdown.innerHTML = `<div class="te-combo-empty">Sin sugerencias</div>`;
        } else {
            dropdown.innerHTML = opciones.map((v) => `
                <div class="te-combo-item" data-valor="${this._esc(v)}">
                    <span class="te-combo-item-nombre">${this._esc(v)}</span>
                    <span class="te-combo-item-x" data-accion="eliminar" title="Quitar de las sugerencias">×</span>
                </div>
            `).join("");

            dropdown.querySelectorAll(".te-combo-item-x").forEach((x) =>
                x.addEventListener("mousedown", (e) => {
                    e.preventDefault();
                    e.stopPropagation();
                    this._ocultarSugerencia(cfg.clave, x.closest(".te-combo-item").dataset.valor);
                    this._renderComboDropdown(cfg);
                })
            );

            dropdown.querySelectorAll(".te-combo-item-nombre").forEach((span) =>
                span.addEventListener("mousedown", (e) => {
                    e.preventDefault();
                    input.value = span.closest(".te-combo-item").dataset.valor;
                    dropdown.style.display = "none";
                })
            );
        }

        dropdown.style.display = "block";
    }

    // ---------- Gestionar catálogos (eliminar taller/proceso) ----------

    _abrirGestionCatalogos() {
        const modal = document.getElementById("te-catalogos-modal");
        if (!modal) return;
        const err = document.getElementById("te-catalogos-error");
        if (err) err.style.display = "none";
        this._renderCatalogosModal();
        modal.style.display = "flex";
    }

    _cerrarGestionCatalogos() {
        const modal = document.getElementById("te-catalogos-modal");
        if (modal) modal.style.display = "none";
    }

    _renderCatalogosModal() {
        const render = (contenedorId, items, tipo) => {
            const el = document.getElementById(contenedorId);
            if (!el) return;
            if (items.length === 0) {
                el.innerHTML = '<div class="te-catalogos-vacio">Sin valores registrados.</div>';
                return;
            }
            el.innerHTML = items.map((item) => `
                <div class="te-catalogo-item" data-id="${item.id}">
                    <span>${this._esc(item.nombre)}</span>
                    <button type="button" class="te-catalogo-eliminar-btn" data-tipo="${tipo}" data-id="${item.id}" title="Eliminar">✕</button>
                </div>
            `).join("");

            el.querySelectorAll(".te-catalogo-eliminar-btn").forEach((btn) =>
                btn.addEventListener("click", () => this._eliminarCatalogo(btn.dataset.tipo, Number(btn.dataset.id)))
            );
        };

        render("te-catalogos-talleres-lista", this._catalogos.talleres, "taller");
        render("te-catalogos-procesos-lista", this._catalogos.procesos, "proceso");
    }

    async _eliminarCatalogo(tipo, id) {
        const lista = tipo === "taller" ? this._catalogos.talleres : this._catalogos.procesos;
        const item = lista.find((i) => i.id === id);
        const nombre = item ? item.nombre : "este valor";

        if (
            !confirm(
                `¿Eliminar "${nombre}" de ${tipo === "taller" ? "Talleres externos" : "Procesos requeridos"}? ` +
                    "Los registros ya guardados con este valor no se ven afectados, solo dejará de sugerirse."
            )
        )
            return;

        const accion = tipo === "taller"
            ? "talleresExternos.catalogos.eliminarTaller"
            : "talleresExternos.catalogos.eliminarProceso";

        const res = await this._send(accion, { id });

        const err = document.getElementById("te-catalogos-error");
        if (!res.ok) {
            if (err) {
                err.textContent = res.error || "No se pudo eliminar el valor del catálogo.";
                err.style.display = "block";
            }
            return;
        }
        if (err) err.style.display = "none";

        if (tipo === "taller") this._catalogos.talleres = this._catalogos.talleres.filter((i) => i.id !== id);
        else this._catalogos.procesos = this._catalogos.procesos.filter((i) => i.id !== id);

        this._renderCatalogosModal();
        this._poblarFiltrosSelect();
    }

    // ---------- Mensajes / exportar ----------

    _mostrarMensaje(texto) {
        const el = document.getElementById("te-mensaje");
        if (!el) return;
        el.textContent = texto;
        el.style.display = "block";
        setTimeout(() => {
            el.style.display = "none";
        }, 4000);
    }

    _exportar() {
        const items = this._filtrarItems();
        this._exportarFilasDesdeDatos(items);
    }

    _exportarFilasDesdeDatos(items) {
        const tabla = document.createElement("table");
        tabla.id = "te-tabla-export-temp";
        tabla.style.position = "absolute";
        tabla.style.left = "-99999px";
        tabla.style.top = "0";

        tabla.innerHTML = `
            <thead>
                <tr>
                    <th>NV</th>
                    <th>Producto</th>
                    <th>Código producto</th>
                    <th>Ítem</th>
                    <th>Cliente</th>
                    <th>Fecha asignación</th>
                    <th>Taller externo</th>
                    <th>Proceso requerido</th>
                    <th>Responsable interno</th>
                    <th>Prioridad</th>
                    <th>Fecha compromiso</th>
                    <th>Estado</th>
                    <th>Cant. a revisar</th>
                    <th>Cant. revisada/entregada</th>
                    <th>Cant. faltante</th>
                    <th>Liberaciones FPS</th>
                    <th>Precio Cotización</th>
                    <th>Precio Taller</th>
                    <th>Observaciones</th>
                </tr>
            </thead>
            <tbody>
                ${items.map((it) => `
                    <tr>
                        <td>${this._esc(it.nv)}</td>
                        <td>${this._esc(it.producto)}</td>
                        <td>${this._esc(it.codigoProducto)}</td>
                        <td>${this._esc(it.item)}</td>
                        <td>${this._esc(it.cliente)}</td>
                        <td>${window.DateUtils.formatear(it.fechaAsignacion)}</td>
                        <td>${this._esc(it.tallerExternoTexto)}</td>
                        <td>${this._esc(it.procesoTexto)}</td>
                        <td>${this._esc(it.responsableInternoTexto)}</td>
                        <td>${this._esc(it.prioridad)}</td>
                        <td>${window.DateUtils.formatear(it.fechaCompromiso)}</td>
                        <td>${this._esc(TE_ESTADO_LABELS[it.estado] || it.estado)}</td>
                        <td>${teFormatNumero(it.cantidadARevisar)}</td>
                        <td>${teFormatNumero(it.cantidadRevisadaEntregada)}</td>
                        <td>${teFormatNumero(it.cantidadFaltante)}</td>
                        <td>${it.totalLiberacionesFps || 0}</td>
                        <td>${this._esc(it.precioCotizacion)}</td>
                        <td>${this._esc(it.precioTaller)}</td>
                        <td>${this._esc(it.observaciones)}</td>
                    </tr>
                `).join("")}
            </tbody>
        `;

        document.body.appendChild(tabla);

        window.ExcelExporter.exportTable({
            tableSelector: "#te-tabla-export-temp",
            fileName: `talleres_externos_${Date.now()}.xlsx`,
            sheetName: "Talleres Externos",
            title: "QCC INNPACK - Talleres Externos",
        });

        tabla.remove();
    }
};
