// Talleres Externos (Faret) — listado + formulario, mismo patrón que faret-nc (tabla HTML +
// modal de alta/edición con guardado inmediato), en vez de la planilla tipo Excel (Tabulator) de
// la versión anterior. Backend: API "qualitycontrol" (api/talleres-externos), vía FaretHandler
// (acciones faret.talleresExternos.*) — sin cambios de backend en esta revisión.

const TET_ESTADO_LABELS = {
    PENDIENTE_ASIGNACION: "Pendiente asignación",
    ASIGNADO: "Asignado",
    EN_PROCESO: "En proceso",
    ENTREGADO: "Entregado",
    ANULADO: "Anulado",
};

function tetFormatNumero(n) {
    const valor = Number(n);
    if (Number.isNaN(valor)) return "0";
    return valor.toLocaleString("es-CL", { maximumFractionDigits: 2 });
}

function tetNumeroOCero(v) {
    if (v === "" || v === null || v === undefined) return 0;
    const numero = Number(String(v).trim().replace(",", "."));
    return Number.isNaN(numero) ? 0 : numero;
}

// "Precio Taller" es texto libre (ej: "$150 por unidad", "120.000 + IVA") — no un número limpio.
// Extrae el primer número del texto con convención es-CL (punto=miles, coma=decimales) para poder
// calcular "Total Taller"; si el texto no trae un número reconocible, retorna null (la columna
// muestra "-" en vez de arriesgar un cálculo incorrecto).
function tetParsePrecioNumero(texto) {
    if (texto === null || texto === undefined) return null;
    const match = String(texto).match(/[\d.,]+/);
    if (!match) return null;

    let token = match[0].replace(/^[.,]+|[.,]+$/g, "");
    if (!token) return null;

    if (token.includes(".") && token.includes(",")) {
        token = token.replace(/\./g, "").replace(",", ".");
    } else if (token.includes(".")) {
        const grupos = token.split(".");
        const esMiles = grupos.length > 1 && grupos.slice(1).every((g) => g.length === 3);
        token = esMiles ? grupos.join("") : token;
    } else if (token.includes(",")) {
        token = token.replace(",", ".");
    }

    const numero = Number(token);
    return Number.isNaN(numero) ? null : numero;
}

// Cant. revisada/entregada × Precio Taller (interpretado como precio por unidad). null si el
// precio no se pudo interpretar como número. Esta es la columna "Precio Total Final Trabajo Realizado".
function tetCalcularTotalTaller(it) {
    const precio = tetParsePrecioNumero(it.precioTaller);
    if (precio === null) return null;
    return tetNumeroOCero(it.cantidadRevisadaEntregada) * precio;
}

// Precio Cotización × Cant. a revisar (cantidad programada) — columna "Precio Total Cotizado".
// Distinto de tetCalcularTotalTaller: ese usa la cantidad realmente revisada/entregada.
function tetCalcularTotalCotizado(it) {
    const precio = tetParsePrecioNumero(it.precioCotizacion);
    if (precio === null) return null;
    return tetNumeroOCero(it.cantidadARevisar) * precio;
}

// Antepone "$" al texto libre de Precio Cotización/Precio Taller para que se lea como valor
// monetario en la tabla, sin duplicar el símbolo si el usuario ya lo escribió.
function tetFormatoMoneda(texto) {
    const valor = (texto ?? "").toString().trim();
    if (!valor) return "";
    return valor.startsWith("$") ? valor : `$${valor}`;
}

function tetBadge(texto, claseColor) {
    if (!texto) return "";
    return `<span class="tet-badge ${claseColor}">${texto}</span>`;
}

function tetBadgePrioridad(valor) {
    const clase = valor === "ALTA" ? "tet-badge-alta" : valor === "BAJA" ? "tet-badge-baja" : "tet-badge-media";
    return tetBadge(valor || "-", clase);
}

function tetBadgeEstado(valor) {
    const clase = "tet-badge-estado-" + String(valor || "").toLowerCase();
    return tetBadge(TET_ESTADO_LABELS[valor] || valor || "-", clase);
}

window.FaretTalleresExternosController = class {
    init() {
        this._items = [];
        this._catalogos = { talleres: [], procesos: [], responsables: [] };
        this._editingId = null;
        this._editingVersion = null;
        this._page = 1;
        this._pageSize = 50;

        document.getElementById("tet-nuevo-btn")?.addEventListener("click", () => this._abrirNuevo());
        document.getElementById("tet-form-cerrar-btn")?.addEventListener("click", () => this._cerrarForm());
        document.getElementById("tet-form-cancelar-btn")?.addEventListener("click", () => this._cerrarForm());
        document.getElementById("tet-form-guardar-btn")?.addEventListener("click", () => this._guardar());
        document.getElementById("tet-exportar-btn")?.addEventListener("click", () => this._exportar());
        document.getElementById("tet-actualizar-btn")?.addEventListener("click", () => this._cargarTodo());
        document.getElementById("tet-filtrar-btn")?.addEventListener("click", () => { this._page = 1; this._renderTabla(); });
        document.getElementById("tet-limpiar-btn")?.addEventListener("click", () => this._limpiarFiltros());
        document.getElementById("tet-anterior-btn")?.addEventListener("click", () => this._irPagina(this._page - 1));
        document.getElementById("tet-siguiente-btn")?.addEventListener("click", () => this._irPagina(this._page + 1));
        document.getElementById("tet-busqueda-rapida")?.addEventListener("input", this._debounce(() => {
            this._page = 1;
            this._renderTabla();
        }, 200));
        document.getElementById("tet-gestionar-catalogos-btn")?.addEventListener("click", () => this._abrirGestionCatalogos());
        document.getElementById("tet-catalogos-cerrar-btn")?.addEventListener("click", () => this._cerrarGestionCatalogos());

        ["tet-form-cant-revisar", "tet-form-cant-entregada"].forEach((id) =>
            document.getElementById(id)?.addEventListener("input", () => this._recalcularCantidadFaltante())
        );

        this._wireCombos();
        this._cargarTodo();
    }

    destroy() {
        // Sin listeners en document/window: a diferencia de la versión Tabulator, este módulo no
        // engancha nada fuera de su propio DOM, no requiere limpieza explícita.
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
        return window.PhotinoBridge.send({ action, ...data });
    }

    // ---------- Carga ----------

    async _cargarTodo() {
        const loadingEl = document.getElementById("tet-loading");
        const errorEl = document.getElementById("tet-error");
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
        const res = await this._send("faret.talleresExternos.catalogos", {});
        if (res.ok) this._catalogos = res.data || { talleres: [], procesos: [], responsables: [] };
    }

    // Recorre todas las páginas (mismo patrón que faret-data/faret-nc): sin filtros de servidor,
    // el filtrado/búsqueda queda 100% en memoria una vez cargado el listado completo.
    async _cargarListaCompleta() {
        const errorEl = document.getElementById("tet-error");
        const pageSize = 200;
        let page = 1;
        let items = [];
        let totalCount = Infinity;
        let safety = 0;

        while (items.length < totalCount && safety < 200) {
            safety++;
            const res = await this._send("faret.talleresExternos.list", { page, pageSize });
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
            busqueda: (document.getElementById("tet-busqueda-rapida")?.value || "").trim().toLowerCase(),
            taller: document.getElementById("tet-filtro-taller")?.value || "",
            proceso: document.getElementById("tet-filtro-proceso")?.value || "",
            responsable: document.getElementById("tet-filtro-responsable")?.value || "",
            prioridad: document.getElementById("tet-filtro-prioridad")?.value || "",
            estado: document.getElementById("tet-filtro-estado")?.value || "",
            fechaAsigDesde: document.getElementById("tet-filtro-fecha-asig-desde")?.value || "",
            fechaAsigHasta: document.getElementById("tet-filtro-fecha-asig-hasta")?.value || "",
        };
    }

    _limpiarFiltros() {
        [
            "tet-busqueda-rapida", "tet-filtro-taller", "tet-filtro-proceso", "tet-filtro-responsable",
            "tet-filtro-prioridad", "tet-filtro-estado", "tet-filtro-fecha-asig-desde", "tet-filtro-fecha-asig-hasta",
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

        poblar("tet-filtro-taller", unicos("tallerExternoTexto"));
        poblar("tet-filtro-proceso", unicos("procesoTexto"));
        poblar("tet-filtro-responsable", unicos("responsableInternoTexto"));
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

        const tbody = document.getElementById("tet-tbody");

        if (!items.length) {
            tbody.innerHTML = `<tr><td colspan="21" class="faret-empty">Sin registros</td></tr>`;
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
                <td>${tetBadgePrioridad(it.prioridad)}</td>
                <td>${it.fechaCompromiso
                    ? (it.atrasado
                        ? `<span class="tet-fecha-vencida">${window.DateUtils.formatear(it.fechaCompromiso)} ⚠</span>`
                        : window.DateUtils.formatear(it.fechaCompromiso))
                    : "-"}</td>
                <td>${tetBadgeEstado(it.estado)}</td>
                <td style="text-align:right;">${tetFormatNumero(it.cantidadARevisar)}</td>
                <td style="text-align:right;">${tetFormatNumero(it.cantidadRevisadaEntregada)}</td>
                <td style="text-align:right;">${tetFormatNumero(it.cantidadFaltante)}</td>
                <td class="tet-precio-celda">${this._esc(tetFormatoMoneda(it.precioCotizacion))}</td>
                <td class="tet-precio-celda">${this._esc(tetFormatoMoneda(it.precioTaller))}</td>
                <td class="tet-precio-celda">${(() => {
                    const total = tetCalcularTotalCotizado(it);
                    return total === null ? "-" : `$${tetFormatNumero(total)}`;
                })()}</td>
                <td class="tet-precio-celda">${(() => {
                    const total = tetCalcularTotalTaller(it);
                    return total === null ? "-" : `$${tetFormatNumero(total)}`;
                })()}</td>
                <td class="tet-obs-celda" title="${this._esc(it.observaciones)}">${this._esc(it.observaciones)}</td>
                <td>
                    <button class="btn-secondary tet-editar-btn" data-id="${it.id}">Editar</button>
                    <button class="btn-danger tet-eliminar-btn" data-id="${it.id}">Eliminar</button>
                </td>
            </tr>
        `).join("");

        tbody.querySelectorAll(".tet-editar-btn").forEach((btn) =>
            btn.addEventListener("click", () => this._abrirEditar(Number(btn.dataset.id))));
        tbody.querySelectorAll(".tet-eliminar-btn").forEach((btn) =>
            btn.addEventListener("click", () => this._eliminarFila(Number(btn.dataset.id))));

        this._renderPaginacion(filtrados.length);
    }

    _renderResumen(items) {
        const set = (id, valor) => {
            const el = document.getElementById(id);
            if (el) el.textContent = valor;
        };
        set("tet-kpi-activos", items.filter((i) => !["ENTREGADO", "ANULADO"].includes(i.estado)).length);
        set("tet-kpi-por-asignar", items.filter((i) => i.estado === "PENDIENTE_ASIGNACION").length);
        set("tet-kpi-atrasados", items.filter((i) => i.atrasado).length);
        set("tet-kpi-entregados", items.filter((i) => i.estado === "ENTREGADO").length);
        const faltanteTotal = items
            .filter((i) => i.estado !== "ANULADO")
            .reduce((acc, i) => acc + tetNumeroOCero(i.cantidadFaltante), 0);
        set("tet-kpi-faltante-total", tetFormatNumero(faltanteTotal));
    }

    _renderPaginacion(totalFiltrado) {
        const totalPaginas = Math.max(1, Math.ceil(totalFiltrado / this._pageSize));
        document.getElementById("tet-pagina-info").textContent = `Página ${this._page} de ${totalPaginas}`;
        document.getElementById("tet-anterior-btn").disabled = this._page <= 1;
        document.getElementById("tet-siguiente-btn").disabled = this._page >= totalPaginas;
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
        document.getElementById("tet-form-titulo").textContent = "Nuevo trabajo";
        document.getElementById("tet-form-error").style.display = "none";

        document.getElementById("tet-form-nv").value = "";
        document.getElementById("tet-form-producto").value = "";
        document.getElementById("tet-form-codigo").value = "";
        document.getElementById("tet-form-item").value = "";
        document.getElementById("tet-form-cliente").value = "";
        document.getElementById("tet-form-fecha-asignacion").value = "";
        document.getElementById("tet-form-taller").value = "";
        document.getElementById("tet-form-proceso").value = "";
        document.getElementById("tet-form-responsable").value = "";
        document.getElementById("tet-form-prioridad").value = "MEDIA";
        document.getElementById("tet-form-fecha-compromiso").value = "";
        document.getElementById("tet-form-estado").value = "PENDIENTE_ASIGNACION";
        document.getElementById("tet-form-cant-revisar").value = "";
        document.getElementById("tet-form-cant-entregada").value = "";
        document.getElementById("tet-form-cant-faltante").value = "";
        document.getElementById("tet-form-precio-cotizacion").value = "";
        document.getElementById("tet-form-precio-taller").value = "";
        document.getElementById("tet-form-observaciones").value = "";

        document.getElementById("tet-form-modal").style.display = "flex";
    }

    _abrirEditar(id) {
        const it = this._items.find((i) => i.id === id);
        if (!it) return;

        this._editingId = it.id;
        this._editingVersion = it.version;
        document.getElementById("tet-form-titulo").textContent = `Editar trabajo NV ${it.nv || ""}`;
        document.getElementById("tet-form-error").style.display = "none";

        document.getElementById("tet-form-nv").value = it.nv || "";
        document.getElementById("tet-form-producto").value = it.producto || "";
        document.getElementById("tet-form-codigo").value = it.codigoProducto || "";
        document.getElementById("tet-form-item").value = it.item || "";
        document.getElementById("tet-form-cliente").value = it.cliente || "";
        document.getElementById("tet-form-fecha-asignacion").value = it.fechaAsignacion ? String(it.fechaAsignacion).substring(0, 10) : "";
        document.getElementById("tet-form-taller").value = it.tallerExternoTexto || "";
        document.getElementById("tet-form-proceso").value = it.procesoTexto || "";
        document.getElementById("tet-form-responsable").value = it.responsableInternoTexto || "";
        document.getElementById("tet-form-prioridad").value = it.prioridad || "MEDIA";
        document.getElementById("tet-form-fecha-compromiso").value = it.fechaCompromiso ? String(it.fechaCompromiso).substring(0, 10) : "";
        document.getElementById("tet-form-estado").value = it.estado || "PENDIENTE_ASIGNACION";
        document.getElementById("tet-form-cant-revisar").value = it.cantidadARevisar ?? "";
        document.getElementById("tet-form-cant-entregada").value = it.cantidadRevisadaEntregada ?? "";
        this._recalcularCantidadFaltante();
        document.getElementById("tet-form-precio-cotizacion").value = it.precioCotizacion ?? "";
        document.getElementById("tet-form-precio-taller").value = it.precioTaller ?? "";
        document.getElementById("tet-form-observaciones").value = it.observaciones || "";

        document.getElementById("tet-form-modal").style.display = "flex";
    }

    _cerrarForm() {
        document.getElementById("tet-form-modal").style.display = "none";
        this._editingId = null;
        this._editingVersion = null;
    }

    // Mismo cálculo que el backend (CantidadARevisar - CantidadRevisadaEntregada, ver
    // TalleresExternosRepository.CalcularCantidadFaltante), solo para feedback visual — el valor
    // real que persiste lo calcula la API.
    _recalcularCantidadFaltante() {
        const revisar = tetNumeroOCero(document.getElementById("tet-form-cant-revisar").value);
        const entregada = tetNumeroOCero(document.getElementById("tet-form-cant-entregada").value);
        document.getElementById("tet-form-cant-faltante").value = tetFormatNumero(revisar - entregada);
    }

    async _guardar() {
        const errorEl = document.getElementById("tet-form-error");
        errorEl.style.display = "none";

        const val = (id) => document.getElementById(id).value.trim();

        const nv = val("tet-form-nv");
        const producto = val("tet-form-producto");
        const item = val("tet-form-item");

        if (!nv || !producto || !item) {
            errorEl.textContent = "NV, Producto e Ítem son obligatorios";
            errorEl.style.display = "block";
            return;
        }

        const precioCotizacion = val("tet-form-precio-cotizacion");
        const precioTaller = val("tet-form-precio-taller");
        if (precioCotizacion.length > 200 || precioTaller.length > 200) {
            errorEl.textContent = "El precio no puede superar 200 caracteres.";
            errorEl.style.display = "block";
            return;
        }

        const payload = {
            nv,
            producto,
            codigoProducto: val("tet-form-codigo"),
            item,
            cliente: val("tet-form-cliente"),
            fechaAsignacion: val("tet-form-fecha-asignacion") || null,
            // Siempre se manda el texto y se deja que el backend resuelva/cree el catálogo por
            // nombre (mismo patrón ya usado antes de esta reescritura).
            tallerExternoNombre: val("tet-form-taller"),
            procesoNombre: val("tet-form-proceso"),
            responsableInternoNombre: val("tet-form-responsable"),
            prioridad: document.getElementById("tet-form-prioridad").value,
            fechaCompromiso: val("tet-form-fecha-compromiso") || null,
            estado: document.getElementById("tet-form-estado").value,
            cantidadARevisar: tetNumeroOCero(val("tet-form-cant-revisar")),
            cantidadRevisadaEntregada: tetNumeroOCero(val("tet-form-cant-entregada")),
            // Cantidad faltante siempre auto-calculada por el backend (revisar - entregada); no se
            // expone el ajuste manual en este formulario simplificado.
            cantidadFaltanteAjusteManual: false,
            precioCotizacion,
            precioTaller,
            observaciones: val("tet-form-observaciones"),
        };

        const btn = document.getElementById("tet-form-guardar-btn");
        btn.disabled = true;
        try {
            const accion = this._editingId ? "faret.talleresExternos.update" : "faret.talleresExternos.create";
            const res = await this._send(accion, {
                ...(this._editingId ? { id: this._editingId, version: this._editingVersion } : {}),
                ...payload,
            });

            if (!res.ok) {
                errorEl.textContent = res.conflict
                    ? "El registro fue modificado por otro usuario. Actualiza la tabla e inténtalo de nuevo."
                    : (res.error || "Error al guardar el trabajo");
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

        const res = await this._send("faret.talleresExternos.eliminar", { id: it.id, version: it.version });
        if (!res.ok) {
            alert(res.conflict
                ? "No se pudo eliminar: el registro fue modificado por otro usuario. Actualiza la tabla e inténtalo de nuevo."
                : (res.error || "No se pudo eliminar el registro."));
            return;
        }

        this._mostrarMensaje(`Trabajo NV ${it.nv} eliminado.`);
        await this._cargarTodo();
    }

    // ---------- Sugerencias con "x" (Taller/Proceso/Responsable/Cliente/Producto/Código) ----------
    // Mismo patrón que el combo de "Responsable" de faret-nc: sugiere valores ya usados (más los
    // del catálogo real para Taller/Proceso), con una "x" que oculta la sugerencia (localStorage,
    // por equipo/PC) sin borrar ningún dato real.

    _combosConfig() {
        return [
            { campo: "tallerExternoTexto", clave: "taller", inputId: "tet-form-taller", dropdownId: "tet-form-taller-dropdown",
                extra: () => this._catalogos.talleres.map((t) => t.nombre) },
            { campo: "procesoTexto", clave: "proceso", inputId: "tet-form-proceso", dropdownId: "tet-form-proceso-dropdown",
                extra: () => this._catalogos.procesos.map((p) => p.nombre) },
            { campo: "responsableInternoTexto", clave: "responsable", inputId: "tet-form-responsable", dropdownId: "tet-form-responsable-dropdown" },
            { campo: "cliente", clave: "cliente", inputId: "tet-form-cliente", dropdownId: "tet-form-cliente-dropdown" },
            { campo: "producto", clave: "producto", inputId: "tet-form-producto", dropdownId: "tet-form-producto-dropdown" },
            { campo: "codigoProducto", clave: "codigo", inputId: "tet-form-codigo", dropdownId: "tet-form-codigo-dropdown" },
        ];
    }

    _wireCombos() {
        this._combosConfig().forEach((cfg) => {
            const input = document.getElementById(cfg.inputId);
            const dropdown = document.getElementById(cfg.dropdownId);
            if (!input || !dropdown) return;

            input.addEventListener("focus", () => this._renderComboDropdown(cfg));
            input.addEventListener("input", () => this._renderComboDropdown(cfg));
            // "blur" con timeout (no click en document): el mousedown+preventDefault de cada item
            // evita que el input pierda foco al hacer click adentro, así que blur solo dispara al
            // hacer click realmente afuera.
            input.addEventListener("blur", () => {
                setTimeout(() => {
                    dropdown.style.display = "none";
                }, 150);
            });
        });
    }

    _sugerenciasOcultas(clave) {
        try {
            const raw = localStorage.getItem(`tetSugerenciasOcultas_${clave}`);
            return new Set(raw ? JSON.parse(raw) : []);
        } catch {
            return new Set();
        }
    }

    _ocultarSugerencia(clave, valor) {
        const ocultas = this._sugerenciasOcultas(clave);
        ocultas.add(valor);
        localStorage.setItem(`tetSugerenciasOcultas_${clave}`, JSON.stringify([...ocultas]));
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
            dropdown.innerHTML = `<div class="fnc-combo-empty">Sin sugerencias</div>`;
        } else {
            dropdown.innerHTML = opciones.map((v) => `
                <div class="fnc-combo-item" data-valor="${this._esc(v)}">
                    <span class="fnc-combo-item-nombre">${this._esc(v)}</span>
                    <span class="fnc-combo-item-x" data-accion="eliminar" title="Quitar de las sugerencias">×</span>
                </div>
            `).join("");

            dropdown.querySelectorAll(".fnc-combo-item-x").forEach((x) =>
                x.addEventListener("mousedown", (e) => {
                    e.preventDefault();
                    e.stopPropagation();
                    this._ocultarSugerencia(cfg.clave, x.closest(".fnc-combo-item").dataset.valor);
                    this._renderComboDropdown(cfg);
                })
            );

            dropdown.querySelectorAll(".fnc-combo-item-nombre").forEach((span) =>
                span.addEventListener("mousedown", (e) => {
                    e.preventDefault();
                    input.value = span.closest(".fnc-combo-item").dataset.valor;
                    dropdown.style.display = "none";
                })
            );
        }

        dropdown.style.display = "block";
    }

    // ---------- Gestionar catálogos (eliminar taller/proceso) ----------

    _abrirGestionCatalogos() {
        const modal = document.getElementById("tet-catalogos-modal");
        if (!modal) return;
        const err = document.getElementById("tet-catalogos-error");
        if (err) err.style.display = "none";
        this._renderCatalogosModal();
        modal.style.display = "flex";
    }

    _cerrarGestionCatalogos() {
        const modal = document.getElementById("tet-catalogos-modal");
        if (modal) modal.style.display = "none";
    }

    _renderCatalogosModal() {
        const render = (contenedorId, items, tipo) => {
            const el = document.getElementById(contenedorId);
            if (!el) return;
            if (items.length === 0) {
                el.innerHTML = '<div class="tet-catalogos-vacio">Sin valores registrados.</div>';
                return;
            }
            el.innerHTML = items.map((item) => `
                <div class="tet-catalogo-item" data-id="${item.id}">
                    <span>${this._esc(item.nombre)}</span>
                    <button type="button" class="tet-catalogo-eliminar-btn" data-tipo="${tipo}" data-id="${item.id}" title="Eliminar">✕</button>
                </div>
            `).join("");

            el.querySelectorAll(".tet-catalogo-eliminar-btn").forEach((btn) =>
                btn.addEventListener("click", () => this._eliminarCatalogo(btn.dataset.tipo, Number(btn.dataset.id)))
            );
        };

        render("tet-catalogos-talleres-lista", this._catalogos.talleres, "taller");
        render("tet-catalogos-procesos-lista", this._catalogos.procesos, "proceso");
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
            ? "faret.talleresExternos.catalogos.eliminarTaller"
            : "faret.talleresExternos.catalogos.eliminarProceso";

        const res = await this._send(accion, { id });

        const err = document.getElementById("tet-catalogos-error");
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
        const el = document.getElementById("tet-mensaje");
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
        tabla.id = "tet-tabla-export-temp";
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
                    <th>Precio Cotización</th>
                    <th>Precio Taller</th>
                    <th>Precio Total Cotizado</th>
                    <th>Precio Total Final Trabajo Realizado</th>
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
                        <td>${this._esc(TET_ESTADO_LABELS[it.estado] || it.estado)}</td>
                        <td>${tetFormatNumero(it.cantidadARevisar)}</td>
                        <td>${tetFormatNumero(it.cantidadRevisadaEntregada)}</td>
                        <td>${tetFormatNumero(it.cantidadFaltante)}</td>
                        <td>${this._esc(it.precioCotizacion)}</td>
                        <td>${this._esc(it.precioTaller)}</td>
                        <td>${(() => {
                            const total = tetCalcularTotalCotizado(it);
                            return total === null ? "-" : tetFormatNumero(total);
                        })()}</td>
                        <td>${(() => {
                            const total = tetCalcularTotalTaller(it);
                            return total === null ? "-" : tetFormatNumero(total);
                        })()}</td>
                        <td>${this._esc(it.observaciones)}</td>
                    </tr>
                `).join("")}
            </tbody>
        `;

        document.body.appendChild(tabla);

        window.ExcelExporter.exportTable({
            tableSelector: "#tet-tabla-export-temp",
            fileName: `faret_talleres_externos_${Date.now()}.xlsx`,
            sheetName: "Talleres Externos",
            title: "QCC Faret - Talleres Externos",
        });

        tabla.remove();
    }
};
