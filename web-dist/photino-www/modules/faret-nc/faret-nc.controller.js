window.FaretNcController = class FaretNcController {

    init() {
        console.log("FaretNcController iniciado");

        this._statsCharts = [];
        this._dataItems = [];
        this._inspeccionItems = [];
        this._ncItems = [];
        this._items = []; // alias de _ncItems, usado por Ver/Editar/Analizar (búsqueda por id de NC)
        this._combinados = [];
        this._combinadosPorKey = new Map();
        this._editingId = null;
        this._gestionContext = null;
        this._page = 1;
        this._pageSize = 50;

        // Columnas opcionales del listado (campos de Data que no vienen fijos en la tabla base).
        // Solo tienen valor real en filas con origen Data (fila.data); en Inspección/Manual muestran "-".
        this._columnasDisponibles = [
            { key: "tipoFalla", label: "Tipo de falla" },
            { key: "impacto", label: "Impacto" },
            { key: "cantRequerida", label: "Cant. requerida", tipo: "numero" },
            { key: "cantRechazada", label: "Cant. rechazada", tipo: "numero" },
            { key: "cantRecuperada", label: "Cant. recuperada", tipo: "numero" },
            { key: "pncReal", label: "PNC real", tipo: "numero" },
            { key: "pctRecuperacion", label: "% Recup.", tipo: "porcentaje" },
            { key: "area", label: "Área" },
            { key: "maquina", label: "Máquina" },
            { key: "operador", label: "Operador" },
            { key: "supervisor", label: "Supervisor" },
            { key: "revisadoPor", label: "Revisado por" },
            { key: "fechaFabricacion", label: "Fecha fabricación", tipo: "fecha" },
        ];
        this._columnasVisibles = this._cargarColumnasVisibles();

        document.getElementById("fnc-refresh-btn")
            ?.addEventListener("click", () => this._loadLista());

        document.getElementById("fnc-nuevo-btn")
            ?.addEventListener("click", () => this._abrirNuevoPnc());

        document.getElementById("fnc-cancelar-btn")
            ?.addEventListener("click", () => this._cerrarForm());

        document.getElementById("fnc-guardar-btn")
            ?.addEventListener("click", () => this._guardar());

        document.getElementById("fnc-filtrar-btn")
            ?.addEventListener("click", () => { this._page = 1; this._renderTabla(); });

        document.getElementById("fnc-limpiar-btn")
            ?.addEventListener("click", () => this._limpiarFiltros());

        document.getElementById("fnc-anterior-btn")
            ?.addEventListener("click", () => this._irPagina(this._page - 1));

        document.getElementById("fnc-siguiente-btn")
            ?.addEventListener("click", () => this._irPagina(this._page + 1));

        document.getElementById("fnc-detalle-cerrar-btn")
            ?.addEventListener("click", () => this._cerrarDetalle());

        document.getElementById("fnc-reg-editar-btn")
            ?.addEventListener("click", () => this._habilitarEdicionRegistro());

        document.getElementById("fnc-reg-cancelar-btn")
            ?.addEventListener("click", () => this._cancelarEdicionRegistro());

        document.getElementById("fnc-reg-guardar-cambio-btn")
            ?.addEventListener("click", () => this._guardarCambioRegistro());

        document.getElementById("fnc-reg-guardar-todo-btn")
            ?.addEventListener("click", () => this._guardarTodoRegistro());

        document.getElementById("fnc-analisis-cerrar-btn")
            ?.addEventListener("click", () => this._cerrarAnalisis());

        document.getElementById("fnc-analisis-guardar-btn")
            ?.addEventListener("click", () => this._guardarAnalisis());

        document.getElementById("fnc-adjunto-pdf-input")
            ?.addEventListener("change", (e) => this._subirPdfSeleccionado(e));

        document.getElementById("fnc-adjunto-fotos-btn")
            ?.addEventListener("click", () => document.getElementById("fnc-adjunto-fotos-input")?.click());

        document.getElementById("fnc-adjunto-fotos-input")
            ?.addEventListener("change", (e) => this._subirFotosSeleccionadas(e));

        document.getElementById("fnc-accion-agregar-btn")
            ?.addEventListener("click", () => this._agregarAccion());

        document.getElementById("fnc-exportar-btn")
            ?.addEventListener("click", () => this._exportar());
        document.getElementById("fnc-imprimir-btn")
            ?.addEventListener("click", () => this._imprimir());
        document.getElementById("fnc-imprimir-reporte-btn")
            ?.addEventListener("click", () => this._imprimirReporteEstadistico());

        document.getElementById("fnc-columnas-btn")
            ?.addEventListener("click", (e) => {
                e.stopPropagation();
                const dd = document.getElementById("fnc-columnas-dropdown");
                if (dd) dd.style.display = dd.style.display === "none" ? "block" : "none";
            });

        document.addEventListener("click", (e) => {
            const wrap = document.getElementById("fnc-columnas-btn")?.closest(".fnc-combo-wrap");
            if (wrap && !wrap.contains(e.target)) {
                const dd = document.getElementById("fnc-columnas-dropdown");
                if (dd) dd.style.display = "none";
            }
        });

        this._renderColumnasDropdown();

        document.getElementById("fnc-gestion-cerrar-btn")
            ?.addEventListener("click", () => this._cerrarGestion());

        document.getElementById("fnc-gcrear-confirmar-btn")
            ?.addEventListener("click", () => this._confirmarCrearGestion());

        document.getElementById("fnc-gestion-guardar-btn")
            ?.addEventListener("click", () => this._guardarGestion());

        document.getElementById("fnc-gdetalle-editar-btn")
            ?.addEventListener("click", () => this._habilitarEdicionGestionDetalle());

        document.getElementById("fnc-gdetalle-cancelar-btn")
            ?.addEventListener("click", () => this._cancelarEdicionGestionDetalle());

        document.getElementById("fnc-gdetalle-guardar-btn")
            ?.addEventListener("click", () => this._guardarGestionDetalle());

        document.getElementById("fnc-seguimiento-agregar-btn")
            ?.addEventListener("click", () => this._agregarSeguimiento());

        document.getElementById("fnc-cerrar-nc-btn")
            ?.addEventListener("click", () => this._cerrarNc());

        document.getElementById("fnc-npnc-cerrar-btn")
            ?.addEventListener("click", () => this._cerrarNuevoPnc());

        document.getElementById("fnc-npnc-cancelar-btn")
            ?.addEventListener("click", () => this._cerrarNuevoPnc());

        document.getElementById("fnc-npnc-guardar-btn")
            ?.addEventListener("click", () => this._guardarNuevoPnc());

        ["fnc-npnc-cant-rechazada", "fnc-npnc-cant-recuperada"].forEach(id =>
            document.getElementById(id)?.addEventListener("input", () => this._recalcularPctRecup()));

        document.getElementById("fnc-npnc-tipo-pnc")?.addEventListener("change", () =>
            this._actualizarVisibilidadDisposicion("fnc-npnc-tipo-pnc", "fnc-npnc-disposicion-row"));

        this._ncAnalisisId = null;
        this._analisisActual = null;
        this._acciones = [];
        this._responsablesBase = this._responsablesDefault();

        const responsableInput = document.getElementById("fnc-gestion-responsable");
        responsableInput?.addEventListener("focus", () => this._renderResponsableDropdown());
        responsableInput?.addEventListener("input", () => this._renderResponsableDropdown());
        // "blur" (no un listener de click en document) para no depender de nodos del dropdown que
        // se recrean en cada render (ej. al usar la "x") — el mousedown+preventDefault de los
        // items evita que el input pierda foco al hacer click adentro, así que blur solo dispara
        // al hacer click realmente afuera.
        responsableInput?.addEventListener("blur", () => {
            setTimeout(() => {
                const dropdown = document.getElementById("fnc-gestion-responsable-dropdown");
                if (dropdown) dropdown.style.display = "none";
            }, 150);
        });

        this._filasFijas = window.TableUtils.init(
            document.getElementById("fnc-tabla"),
            document.getElementById("fnc-filas-fijadas"),
            { obtenerId: tr => tr.dataset.id }
        );

        // Catálogos administrables (Cliente/Categoría defecto/Tipo de falla/Supervisor/Revisado
        // por + Área→Máquina/Operador). Se engancha una sola vez: los <input> de ambos
        // formularios (Nueva NC / Registro completo) son nodos fijos del DOM, no se recrean al
        // abrir/cerrar los modales.
        this._attachCatalogosCombos("npnc");
        this._attachCatalogosCombos("reg");

        this._loadLista();
    }

    destroy() {
        console.log("FaretNcController destruido");
        this._destroyStatsCharts();
    }

    // ---------- Carga y fusión Data + NC ----------

    async _loadLista() {
        const contenedor = document.getElementById("fnc-tabla")?.closest(".table-container");
        await window.TableUtils.preservarScroll(contenedor, () => this._loadListaInterna());
    }

    async _loadListaInterna() {
        const loadingEl = document.getElementById("fnc-loading");
        const errorEl = document.getElementById("fnc-error");
        const tbody = document.getElementById("fnc-tbody");

        loadingEl.style.display = "block";
        errorEl.style.display = "none";

        try {
            const [dataItems, inspeccionItems, ncRes] = await Promise.all([
                this._cargarDataCompleta(),
                Promise.resolve([]), // Inspecciones deshabilitadas en este módulo (ver _cargarInspeccionesCompleta)
                window.PhotinoBridge.send({ action: "faret.nc.list" }),
            ]);

            if (!ncRes.ok) {
                errorEl.textContent = ncRes.error || "Error al cargar las no conformidades";
                errorEl.style.display = "block";
                tbody.innerHTML = `<tr><td colspan="${this._totalColumnasTabla()}" class="faret-empty">Sin datos</td></tr>`;
                return;
            }

            this._dataItems = dataItems;
            this._inspeccionItems = inspeccionItems;
            this._ncItems = Array.isArray(ncRes.data) ? ncRes.data : [];
            this._items = this._ncItems;

            this._combinar();
            this._poblarFiltrosSelect();
            this._actualizarResponsablesBase();
            this._renderTabla();
        } catch {
            errorEl.textContent = "Error de comunicación con el backend";
            errorEl.style.display = "block";
            tbody.innerHTML = `<tr><td colspan="${this._totalColumnasTabla()}" class="faret-empty">Sin datos</td></tr>`;
        } finally {
            loadingEl.style.display = "none";
        }
    }

    // Recorre todas las páginas de Data (tope real de 200 filas por página en la API).
    async _cargarDataCompleta() {
        const pageSize = 200;
        let page = 1;
        let total = Infinity;
        const items = [];

        while (items.length < total && page <= 200) {
            const res = await window.PhotinoBridge.send({
                action: "faret.data.list",
                page,
                pageSize,
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

    // Recorre todas las páginas de Inspecciones (misma API `calidad`, mismo tope de seguridad).
    async _cargarInspeccionesCompleta() {
        const pageSize = 200;
        let page = 1;
        let total = Infinity;
        const items = [];

        while (items.length < total && page <= 200) {
            const res = await window.PhotinoBridge.send({
                action: "faret.inspecciones.list",
                page,
                pageSize,
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

    // Une Data e Inspecciones (fuentes base) con la gestión de NC vinculada por
    // sistemaOrigen="DATA_FARET"/"INSPECCION_FARET" + origenId=String(id). Las NC que no calzan
    // con ninguna fila de Data/Inspecciones (manuales, o con un origenId que ya no existe) se
    // agregan igual al final para no perder información.
    _combinar() {
        const ncPorOrigenId = new Map();
        this._ncItems.forEach(nc => {
            if ((nc.sistemaOrigen === "DATA_FARET" || nc.sistemaOrigen === "INSPECCION_FARET") && nc.origenId) {
                ncPorOrigenId.set(`${nc.sistemaOrigen}:${nc.origenId}`, nc);
            }
        });

        const usados = new Set();

        const filasData = this._dataItems.map(d => {
            const nc = ncPorOrigenId.get(`DATA_FARET:${d.id}`) || null;
            if (nc) usados.add(nc.id);
            return this._normalizarFila(d, nc, null);
        });

        const filasInspecciones = this._inspeccionItems.map(i => {
            const nc = ncPorOrigenId.get(`INSPECCION_FARET:${i.id}`) || null;
            if (nc) usados.add(nc.id);
            return this._normalizarFila(null, nc, i);
        });

        const filasManuales = this._ncItems
            .filter(nc => !usados.has(nc.id))
            .map(nc => this._normalizarFila(null, nc, null));

        this._combinados = [...filasData, ...filasInspecciones, ...filasManuales];
        this._combinados.sort((a, b) => {
            const fa = a.fechaIngreso ? new Date(a.fechaIngreso).getTime() : -Infinity;
            const fb = b.fechaIngreso ? new Date(b.fechaIngreso).getTime() : -Infinity;
            return fb - fa; // más reciente primero; sin fecha queda al final
        });
        this._combinadosPorKey = new Map(this._combinados.map(f => [f.key, f]));
    }

    _normalizarFila(dataRow, ncRow, inspRow) {
        const fuente = dataRow ? "DATA" : (inspRow ? "INSPECCION" : "MANUAL");

        return {
            key: dataRow ? `data-${dataRow.id}` : (inspRow ? `insp-${inspRow.id}` : `nc-${ncRow.id}`),
            fuente,
            dataId: dataRow ? dataRow.id : null,
            inspeccionId: inspRow ? inspRow.id : null,
            data: dataRow,
            inspeccion: inspRow,
            nc: ncRow,
            tieneNc: !!ncRow,
            codigo: ncRow?.codigo || (dataRow ? `Data #${dataRow.id}` : (inspRow ? `Inspección #${inspRow.id}` : "-")),
            fechaIngreso: dataRow?.fechaIngreso || inspRow?.fechaRegistro || ncRow?.fechaCreacion || null,
            fechaSalida: dataRow?.fechaSalida || null,
            npNv: dataRow?.npNv || inspRow?.nvFaret || "-",
            cliente: dataRow?.cliente || "-",
            codigoProducto: dataRow?.codigo || "-",
            producto: dataRow?.producto || "-",
            tipoPnc: dataRow?.tipoPnc || inspRow?.areaControl || "-",
            categoriaDefecto: dataRow?.categoriaDefecto || inspRow?.defectos || "-",
            nivelSeveridad: dataRow?.nivel || ncRow?.severidad || "-",
            estadoGestion: ncRow?.estadoGestion || "SIN_GESTION",
            responsable: ncRow?.responsable || "-",
            area: dataRow?.area || "-",
            fechaCompromiso: ncRow?.fechaCompromiso || null,
        };
    }

    // ---------- Filtros ----------

    // Arma las opciones de los <select> de Cliente/Tipo PNC/Área con los valores reales
    // ya presentes en this._combinados (dataset completo en memoria) — nada hardcodeado, salvo
    // Tipo PNC que a pedido explícito del usuario (27-08-2026) queda con una lista FIJA y cerrada
    // (Todos/Cuarentena/Rechazo Cliente/Reclamo), sin mezclar con los valores reales del dataset
    // (que pueden traer "Rechazo" a secas, "Interna" u otros históricos) — es solo el filtro de
    // esta vista, no toca el catálogo completo del formulario "Nueva NC" ni el backend.
    _poblarFiltrosSelect() {
        const TIPO_PNC_FILTRO = ["Cuarentena", "Rechazo Cliente", "Reclamo"];
        const mapa = {
            "fnc-filtro-cliente": "cliente",
            "fnc-filtro-area": "area",
        };

        const selectTipoPnc = document.getElementById("fnc-filtro-tipo-pnc");
        if (selectTipoPnc) {
            const valorActual = selectTipoPnc.value;
            selectTipoPnc.innerHTML = `<option value="">Todos</option>` +
                TIPO_PNC_FILTRO.map(v => `<option value="${v}">${v}</option>`).join("");
            if (valorActual && TIPO_PNC_FILTRO.includes(valorActual)) selectTipoPnc.value = valorActual;
        }

        Object.entries(mapa).forEach(([selectId, campo]) => {
            const select = document.getElementById(selectId);
            if (!select) return;

            const valorActual = select.value;
            const valores = new Set(
                this._combinados
                    .map(f => (f[campo] || "").toString().trim())
                    .filter(v => v && v !== "-")
            );

            select.innerHTML = `<option value="">Todos</option>` +
                [...valores].sort().map(v => `<option value="${v}">${v}</option>`).join("");

            if (valorActual && valores.has(valorActual)) select.value = valorActual;
        });
    }

    // Combo editable de "Responsable" del modal Gestionar: precarga 2 nombres fijos y suma
    // cualquier otro valor ya guardado en NC reales (this._ncItems) — no requiere API nueva, un
    // nombre escrito a mano queda "recordado" en cuanto se guarda una gestión con ese responsable
    // y vuelve a aparecer en el próximo refresco de la lista. La "x" de cada sugerencia no borra
    // datos reales, solo la oculta de la lista (persistido en localStorage, por equipo/PC).
    _responsablesDefault() {
        return ["Rodrigo Bastías", "Mónica Valdivia"];
    }

    _actualizarResponsablesBase() {
        const valores = new Set(this._responsablesDefault());
        this._ncItems.forEach(nc => {
            const v = (nc.responsable || "").toString().trim();
            if (v) valores.add(v);
        });
        this._responsablesBase = [...valores].sort();
    }

    _responsablesOcultos() {
        try {
            const raw = localStorage.getItem("faretNcResponsablesOcultos");
            return new Set(raw ? JSON.parse(raw) : []);
        } catch {
            return new Set();
        }
    }

    _ocultarResponsable(nombre) {
        const ocultos = this._responsablesOcultos();
        ocultos.add(nombre);
        localStorage.setItem("faretNcResponsablesOcultos", JSON.stringify([...ocultos]));
    }

    _renderResponsableDropdown() {
        const dropdown = document.getElementById("fnc-gestion-responsable-dropdown");
        const input = document.getElementById("fnc-gestion-responsable");
        if (!dropdown || !input) return;

        const filtro = input.value.trim().toLowerCase();
        const ocultos = this._responsablesOcultos();
        const opciones = (this._responsablesBase || [])
            .filter(v => !ocultos.has(v))
            .filter(v => !filtro || v.toLowerCase().includes(filtro));

        if (!opciones.length) {
            dropdown.innerHTML = `<div class="fnc-combo-empty">Sin sugerencias</div>`;
        } else {
            dropdown.innerHTML = opciones.map(v => `
                <div class="fnc-combo-item" data-nombre="${v}">
                    <span class="fnc-combo-item-nombre">${v}</span>
                    <span class="fnc-combo-item-x" data-accion="eliminar" title="Quitar de las sugerencias">×</span>
                </div>
            `).join("");

            dropdown.querySelectorAll(".fnc-combo-item-x").forEach(x =>
                x.addEventListener("mousedown", e => {
                    e.preventDefault();
                    e.stopPropagation();
                    this._ocultarResponsable(x.closest(".fnc-combo-item").dataset.nombre);
                    this._renderResponsableDropdown();
                }));

            dropdown.querySelectorAll(".fnc-combo-item-nombre").forEach(span =>
                span.addEventListener("mousedown", e => {
                    e.preventDefault();
                    input.value = span.closest(".fnc-combo-item").dataset.nombre;
                    dropdown.style.display = "none";
                }));
        }

        dropdown.style.display = "block";
    }

    _getFiltros() {
        return {
            cliente: document.getElementById("fnc-filtro-cliente")?.value.trim().toLowerCase() || "",
            tipoPnc: document.getElementById("fnc-filtro-tipo-pnc")?.value.trim().toLowerCase() || "",
            nivel: document.getElementById("fnc-filtro-nivel")?.value || "",
            estadoGestion: document.getElementById("fnc-filtro-estado-gestion")?.value || "",
            area: document.getElementById("fnc-filtro-area")?.value.trim().toLowerCase() || "",
            fuente: document.getElementById("fnc-filtro-fuente")?.value || "",
            fechaDesde: document.getElementById("fnc-filtro-fecha-desde")?.value || "",
            fechaHasta: document.getElementById("fnc-filtro-fecha-hasta")?.value || "",
        };
    }

    _limpiarFiltros() {
        document.getElementById("fnc-filtro-cliente").value = "";
        document.getElementById("fnc-filtro-tipo-pnc").value = "";
        document.getElementById("fnc-filtro-nivel").value = "";
        document.getElementById("fnc-filtro-estado-gestion").value = "";
        document.getElementById("fnc-filtro-area").value = "";
        document.getElementById("fnc-filtro-fuente").value = "";
        document.getElementById("fnc-filtro-fecha-desde").value = "";
        document.getElementById("fnc-filtro-fecha-hasta").value = "";
        this._page = 1;
        this._renderTabla();
    }

    _irPagina(pagina) {
        if (pagina < 1) return;
        this._page = pagina;
        this._renderTabla();
    }

    _filtrarItems() {
        const f = this._getFiltros();

        return this._combinados.filter(fila => {
            if (f.cliente && !fila.cliente.toLowerCase().includes(f.cliente)) return false;
            if (f.tipoPnc && !fila.tipoPnc.toLowerCase().includes(f.tipoPnc)) return false;
            if (f.nivel && fila.nivelSeveridad !== f.nivel) return false;
            if (f.estadoGestion && fila.estadoGestion !== f.estadoGestion) return false;
            if (f.area && !fila.area.toLowerCase().includes(f.area)) return false;
            if (f.fuente && fila.fuente !== f.fuente) return false;

            if (fila.fechaIngreso) {
                const fecha = String(fila.fechaIngreso).substring(0, 10);
                if (f.fechaDesde && fecha < f.fechaDesde) return false;
                if (f.fechaHasta && fecha > f.fechaHasta) return false;
            }

            return true;
        });
    }

    // ---------- Columnas opcionales del listado ----------

    _cargarColumnasVisibles() {
        try {
            const raw = localStorage.getItem("faretNcColumnasVisibles");
            return new Set(raw ? JSON.parse(raw) : []);
        } catch {
            return new Set();
        }
    }

    _guardarColumnasVisibles() {
        try {
            localStorage.setItem("faretNcColumnasVisibles", JSON.stringify([...this._columnasVisibles]));
        } catch {
            // localStorage no disponible; el toggle sigue funcionando en memoria para esta sesión
        }
    }

    _renderColumnasDropdown() {
        const dd = document.getElementById("fnc-columnas-dropdown");
        if (!dd) return;

        dd.innerHTML = this._columnasDisponibles.map(col => `
            <label class="fnc-columnas-item">
                <input type="checkbox" data-col="${col.key}" ${this._columnasVisibles.has(col.key) ? "checked" : ""}>
                ${col.label}
            </label>
        `).join("");

        dd.querySelectorAll("input[type=checkbox]").forEach(chk =>
            chk.addEventListener("change", () => {
                if (chk.checked) this._columnasVisibles.add(chk.dataset.col);
                else this._columnasVisibles.delete(chk.dataset.col);
                this._guardarColumnasVisibles();
                this._renderTabla();
            }));
    }

    _columnasOpcionalesVisibles() {
        return this._columnasDisponibles.filter(col => this._columnasVisibles.has(col.key));
    }

    // Solo las filas con origen Data (fila.data) tienen estos campos; Inspección/Manual muestran "-".
    _formatearColumnaOpcional(fila, col) {
        const valor = fila.data ? fila.data[col.key] : null;
        if (valor === null || valor === undefined || valor === "") return "-";
        if (col.tipo === "fecha") return window.DateUtils.formatear(valor);
        if (col.tipo === "porcentaje") return `${(valor * 100).toFixed(2)}%`;
        return valor;
    }

    // Inserta/quita los <th> de las columnas opcionales activas justo antes de "Fuente", sin tocar
    // el resto del thead (columnas base fijas).
    _actualizarThead() {
        const theadRow = document.querySelector("#fnc-tabla thead tr");
        const fuenteTh = document.getElementById("fnc-th-fuente");
        if (!theadRow || !fuenteTh) return;

        theadRow.querySelectorAll('[data-opcional="true"]').forEach(th => th.remove());

        this._columnasOpcionalesVisibles().forEach(col => {
            const th = document.createElement("th");
            th.textContent = col.label;
            th.dataset.opcional = "true";
            theadRow.insertBefore(th, fuenteTh);
        });
    }

    // Total de columnas del thead (14 base visibles + 1 "Fecha compromiso" oculta + opcionales
    // activas), usado para el colspan de las filas de estado (Cargando/Sin registros/Error).
    _totalColumnasTabla() {
        return 15 + this._columnasOpcionalesVisibles().length;
    }

    // ---------- Tabla ----------

    _renderTabla() {
        this._actualizarThead();
        const colsOpcionales = this._columnasOpcionalesVisibles();

        const filtrados = this._filtrarItems();
        this._renderResumen(filtrados);
        this._renderIndicadores(this._calcularIndicadores(filtrados));

        const totalPaginas = Math.max(1, Math.ceil(filtrados.length / this._pageSize));
        if (this._page > totalPaginas) this._page = totalPaginas;
        if (this._page < 1) this._page = 1;

        const inicio = (this._page - 1) * this._pageSize;
        const items = filtrados.slice(inicio, inicio + this._pageSize);

        const tbody = document.getElementById("fnc-tbody");

        if (!items.length) {
            tbody.innerHTML = `<tr><td colspan="${this._totalColumnasTabla()}" class="faret-empty">Sin registros</td></tr>`;
            this._filasFijas?.refrescar();
            this._renderPaginacion(filtrados.length);
            return;
        }

        tbody.innerHTML = items.map(fila => `
            <tr data-id="${fila.key}">
                <td>${fila.codigo}</td>
                <td>${window.DateUtils.formatear(fila.fechaIngreso)}</td>
                <td>${window.DateUtils.formatear(fila.fechaSalida)}</td>
                <td>${fila.npNv}</td>
                <td>${fila.cliente}</td>
                <td>${fila.codigoProducto}</td>
                <td>${fila.producto}</td>
                <td>${fila.tipoPnc}</td>
                <td>${fila.categoriaDefecto}</td>
                <td>${this._badge(fila.nivelSeveridad, this._colorSeveridad(fila.nivelSeveridad))}</td>
                <td>${this._badge(this._labelEstadoGestion(fila.estadoGestion), this._colorEstadoGestion(fila.estadoGestion))}</td>
                <td>${fila.responsable}</td>
                <td style="display:none;">${window.DateUtils.formatear(fila.fechaCompromiso)}</td>
                ${colsOpcionales.map(col => `<td>${this._formatearColumnaOpcional(fila, col)}</td>`).join("")}
                <td>${this._badge(this._labelFuente(fila.fuente), this._colorFuente(fila.fuente))}</td>
                <td>
                    ${fila.tieneNc ? `
                        <button class="btn-ghost fnc-ver-btn" data-key="${fila.key}">Ver</button>
                        <button class="btn-secondary fnc-editar-btn" data-key="${fila.key}" style="display:none;">Editar</button>
                        <button class="btn-primary fnc-analizar-btn" data-key="${fila.key}">Analizar</button>
                    ` : ""}
                    <button class="btn-secondary fnc-gestionar-btn" data-key="${fila.key}">Gestionar</button>
                    <button class="btn-danger fnc-eliminar-btn" data-key="${fila.key}">Eliminar</button>
                </td>
            </tr>
        `).join("");

        tbody.querySelectorAll(".fnc-ver-btn").forEach(btn =>
            btn.addEventListener("click", () => this._verDetallePorKey(btn.dataset.key)));

        tbody.querySelectorAll(".fnc-editar-btn").forEach(btn =>
            btn.addEventListener("click", () => this._abrirFormEditarPorKey(btn.dataset.key)));

        tbody.querySelectorAll(".fnc-analizar-btn").forEach(btn =>
            btn.addEventListener("click", () => this._abrirAnalisisPorKey(btn.dataset.key)));

        tbody.querySelectorAll(".fnc-gestionar-btn").forEach(btn =>
            btn.addEventListener("click", () => this._abrirGestion(btn.dataset.key)));

        tbody.querySelectorAll(".fnc-eliminar-btn").forEach(btn =>
            btn.addEventListener("click", () => this._eliminarFila(btn.dataset.key)));

        this._filasFijas?.refrescar();
        this._renderPaginacion(filtrados.length);
    }

    _renderPaginacion(totalFiltrado) {
        const totalPaginas = Math.max(1, Math.ceil(totalFiltrado / this._pageSize));
        document.getElementById("fnc-pagina-info").textContent = `Página ${this._page} de ${totalPaginas}`;
        document.getElementById("fnc-anterior-btn").disabled = this._page <= 1;
        document.getElementById("fnc-siguiente-btn").disabled = this._page >= totalPaginas;
    }

    _verDetallePorKey(key) {
        const fila = this._combinadosPorKey.get(key);
        if (fila?.nc) this._verDetalle(fila.nc.id, fila.dataId);
    }

    _abrirFormEditarPorKey(key) {
        const fila = this._combinadosPorKey.get(key);
        if (fila?.nc) this._abrirFormEditar(fila.nc.id);
    }

    _abrirAnalisisPorKey(key) {
        const fila = this._combinadosPorKey.get(key);
        if (fila?.nc) this._abrirAnalisis(fila.nc.id);
    }

    _renderResumen(items) {
        const esCerrada = estado => (estado || "").toUpperCase() === "CERRADA";
        const esCritica = valor => {
            const v = (valor || "").toUpperCase();
            return v === "ALTA" || v.includes("CRIT");
        };

        const conNc = items.filter(i => i.tieneNc);

        document.getElementById("fnc-total").textContent = conNc.length;
        document.getElementById("fnc-cerradas").textContent = conNc.filter(i => esCerrada(i.estadoGestion)).length;
        document.getElementById("fnc-abiertas").textContent = conNc.filter(i => !esCerrada(i.estadoGestion)).length;
        document.getElementById("fnc-criticas").textContent = items.filter(i => esCritica(i.nivelSeveridad)).length;
        document.getElementById("fnc-sin-gestion").textContent = items.filter(i => !i.tieneNc).length;
    }

    // ---------- Indicadores estadísticos ----------
    // Se calculan exclusivamente sobre el mismo array que ya alimenta la tabla (`filtrados`,
    // resultado de _filtrarItems()) — nunca se hace un fetch adicional ni se llama a la API
    // `indicadores-calidad` (esa API solo filtra por fecha, no por los 8 filtros de este módulo;
    // usarla mostraría un universo distinto al de la tabla). Solo se consideran filas con
    // fuente==="DATA" (fila.data), porque tipo PNC/familia/área/categoría defecto son campos
    // nativos de importacion_pnc — las filas MANUAL/INSPECCION no los tienen.

    _calcularIndicadores(filtrados) {
        const dataRows = filtrados
            .filter(f => f.fuente === "DATA" && f.data)
            .map(f => f.data);

        const porTipo = tipo => dataRows.filter(d => (d.tipoPnc || "").trim() === tipo);

        // `cantidadTotalUnidades` usa cantRechazada (la cantidad que efectivamente entró en el
        // estado del tipo PNC, ej. cuarentena) — distinto de recuperados/destruidos, que ahora
        // solo se calculan mes a mes para el gráfico de evolución (_agruparPorMes), no como total.
        const resumenTipo = tipo => {
            const rows = porTipo(tipo);
            return {
                total: rows.length,
                cantidadTotalUnidades: rows.reduce((s, d) => s + (Number(d.cantRechazada) || 0), 0),
                evolucionMensual: this._agruparPorMes(rows),
            };
        };

        const agruparCategoria = campo => {
            const mapa = new Map();
            dataRows.forEach(d => {
                const valor = (d[campo] || "").toString().trim();
                if (!valor) return;
                mapa.set(valor, (mapa.get(valor) || 0) + 1);
            });
            return [...mapa.entries()]
                .map(([categoria, total]) => ({ categoria, total }))
                .sort((a, b) => b.total - a.total);
        };

        // Conteo de valores DISTINTOS (no de NC) — Set sobre el mismo dataRows ya filtrado.
        const contarDistintos = campo => new Set(
            dataRows.map(d => (d[campo] || "").toString().trim()).filter(Boolean)
        ).size;

        return {
            cuarentenas: resumenTipo("Cuarentena"),
            rechazosCliente: resumenTipo("Rechazo Cliente"),
            // "Rechazo" (genérico) y "Rechazo Cliente" son valores de catálogo distintos desde el
            // Paso 49 — "Rechazados totales" combina ambos, a diferencia de "Rechazos cliente —
            // total" de arriba, que solo cuenta el tipo "Rechazo Cliente".
            rechazosTotales: porTipo("Rechazo").length + porTipo("Rechazo Cliente").length,
            totalReclamos: porTipo("Reclamo").length,
            porFamilia: agruparCategoria("familiaProducto"),
            porArea: agruparCategoria("area"),
            porMaquina: agruparCategoria("maquina"),
            porOperador: agruparCategoria("operador"),
            porCliente: agruparCategoria("cliente"),
            porTipoPnc: agruparCategoria("tipoPnc"),
            maquinasInvolucradas: contarDistintos("maquina"),
            operadoresInvolucrados: contarDistintos("operador"),
            clientesInvolucrados: contarDistintos("cliente"),
            pareto: this._calcularPareto(dataRows),
            reposicionDestruccion: this._calcularReposicionDestruccion(dataRows),
        };
    }

    // A diferencia de la evolución mensual de Cuarentenas/Rechazos de cliente (que suma
    // cantRecuperada/cantDestruida mes a mes sin mirar la disposición elegida), esto usa el campo
    // `disposicion` en sí (Reposición/Destrucción/Reposición y destrucción/No aplica) como la
    // fuente real de verdad
    // — es el campo que el usuario llena explícitamente al cerrar una NC de tipo Cuarentena o
    // Rechazo Cliente (ver _esDisposicionAplicable). `cantRepuesta` no se usaba en ningún
    // indicador hasta ahora.
    _calcularReposicionDestruccion(dataRows) {
        const disposicion = d => (d.disposicion || "").trim();
        const conReposicion = dataRows.filter(d => ["Reposición", "Reposición y destrucción"].includes(disposicion(d)));
        const conDestruccion = dataRows.filter(d => ["Destrucción", "Reposición y destrucción"].includes(disposicion(d)));
        const conAmbas = dataRows.filter(d => disposicion(d) === "Reposición y destrucción");

        return {
            ncConReposicion: conReposicion.length,
            ncConDestruccion: conDestruccion.length,
            ncConAmbas: conAmbas.length,
            cantidadRepuestaTotal: conReposicion.reduce((s, d) => s + (Number(d.cantRepuesta) || 0), 0),
            cantidadDestruidaTotal: conDestruccion.reduce((s, d) => s + (Number(d.cantDestruida) || 0), 0),
        };
    }

    _agruparPorMes(rows) {
        const mapa = new Map();
        rows.forEach(d => {
            if (!d.fechaIngreso) return;
            const mes = String(d.fechaIngreso).substring(0, 7); // yyyy-MM
            if (!mapa.has(mes)) mapa.set(mes, { recuperados: 0, destruidos: 0 });
            const acc = mapa.get(mes);
            acc.recuperados += Number(d.cantRecuperada) || 0;
            acc.destruidos += Number(d.cantDestruida) || 0;
        });
        // `mes` (yyyy-MM) se conserva para ordenar; `mesLabel`/`mesLargo` son solo presentación
        // (eje corto / tooltip completo) — no reemplazan ni recalculan ningún dato.
        return [...mapa.entries()]
            .sort(([a], [b]) => a.localeCompare(b))
            .map(([mes, v]) => ({
                mes,
                mesLabel: this._formatearMesCorto(mes),
                mesLargo: this._formatearMesLargo(mes),
                ...v,
            }));
    }

    _formatearMesCorto(mesIso) {
        const [anio, mes] = mesIso.split("-").map(Number);
        const meses = ["ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sep", "oct", "nov", "dic"];
        return `${meses[mes - 1] || mesIso} ${String(anio).slice(-2)}`;
    }

    _formatearMesLargo(mesIso) {
        const [anio, mes] = mesIso.split("-").map(Number);
        const meses = [
            "Enero", "Febrero", "Marzo", "Abril", "Mayo", "Junio",
            "Julio", "Agosto", "Septiembre", "Octubre", "Noviembre", "Diciembre",
        ];
        return `${meses[mes - 1] || mesIso} ${anio}`;
    }

    // Mismo cálculo que GetParetoDefectosAsync en la API (ImportacionesRepository.cs): orden
    // descendente por frecuencia, % acumulado con 2 decimales.
    _calcularPareto(rows) {
        const mapa = new Map();
        rows.forEach(d => {
            const valor = (d.categoriaDefecto || "").toString().trim();
            if (!valor) return;
            mapa.set(valor, (mapa.get(valor) || 0) + 1);
        });

        const total = [...mapa.values()].reduce((s, v) => s + v, 0);
        let acumulado = 0;

        return [...mapa.entries()]
            .sort((a, b) => b[1] - a[1])
            .map(([defecto, frecuencia]) => {
                acumulado += frecuencia;
                return {
                    defecto,
                    frecuencia,
                    porcentajeAcumulado: total > 0 ? Math.round((acumulado / total) * 10000) / 100 : 0,
                };
            });
    }

    _renderIndicadores(ind) {
        this._destroyStatsCharts();

        this._setText("fnc-stat-cuarentenas-total", ind.cuarentenas.total);
        this._setText("fnc-stat-cuarentenas-cant-unidades", ind.cuarentenas.cantidadTotalUnidades);

        this._setText("fnc-stat-rechazos-total", ind.rechazosCliente.total);
        this._setText("fnc-stat-rechazos-totales-general", ind.rechazosTotales);

        this._setText("fnc-stat-reclamos-total", ind.totalReclamos);

        this._setText("fnc-stat-repdes-reposicion", ind.reposicionDestruccion.ncConReposicion);
        this._setText("fnc-stat-repdes-destruccion", ind.reposicionDestruccion.ncConDestruccion);
        this._setText("fnc-stat-repdes-ambas", ind.reposicionDestruccion.ncConAmbas);
        this._setText("fnc-stat-repdes-cant-repuesta", ind.reposicionDestruccion.cantidadRepuestaTotal);
        this._setText("fnc-stat-repdes-cant-destruida", ind.reposicionDestruccion.cantidadDestruidaTotal);

        this._setText("fnc-stat-maquinas-total", ind.maquinasInvolucradas);
        this._setText("fnc-stat-operadores-total", ind.operadoresInvolucrados);
        this._setText("fnc-stat-clientes-total", ind.clientesInvolucrados);

        this._renderEvolucionMensual(
            "fnc-chart-cuarentenas", "fnc-nota-cuarentenas",
            ind.cuarentenas.evolucionMensual, "Cuarentenas — evolución mensual"
        );
        this._renderEvolucionMensual(
            "fnc-chart-rechazos", "fnc-nota-rechazos",
            ind.rechazosCliente.evolucionMensual, "Rechazos de cliente — evolución mensual"
        );

        this._chartBarHorizontalStats(
            "fnc-chart-familia", ind.porFamilia, "categoria", "total", "PNC",
            "PNC por familia de producto"
        );
        this._chartBarHorizontalStats(
            "fnc-chart-area", ind.porArea, "categoria", "total", "Incidentes",
            "Incidentes por área"
        );
        this._chartBarHorizontalStats(
            "fnc-chart-maquina", ind.porMaquina, "categoria", "total", "NC",
            "Máquinas involucradas", { scroll: true }
        );
        this._chartBarHorizontalStats(
            "fnc-chart-operador", ind.porOperador, "categoria", "total", "NC",
            "Operadores involucrados", { scroll: true }
        );
        this._chartBarHorizontalStats(
            "fnc-chart-cliente", ind.porCliente, "categoria", "total", "NC",
            "Clientes involucrados", { scroll: true }
        );
        this._chartDoughnutStats(
            "fnc-chart-tipo-pnc", ind.porTipoPnc, "categoria", "total",
            "Distribución por tipo de PNC"
        );
        this._chartParetoStats(
            "fnc-chart-pareto", this._aplicarTopNOtros(ind.pareto), "defecto", "frecuencia", "porcentajeAcumulado",
            "Pareto de defectos"
        );
    }

    // Limita el Pareto a las N categorías con mayor frecuencia (por defecto 10) y agrupa el resto
    // en una barra "Otros" — el % acumulado de esa barra usa el acumulado real ya calculado sobre
    // TODAS las categorías filtradas (último valor de `pareto`, calculado en _calcularPareto antes
    // de recortar), nunca se recalcula solo sobre el Top N. Si hay <= topN categorías, no hay
    // recorte: se devuelve el arreglo completo sin agregar "Otros".
    _aplicarTopNOtros(pareto, topN = 10) {
        if (pareto.length <= topN) return pareto;

        const top = pareto.slice(0, topN);
        const resto = pareto.slice(topN);
        const frecuenciaOtros = resto.reduce((s, r) => s + r.frecuencia, 0);
        const acumuladoReal = pareto[pareto.length - 1].porcentajeAcumulado;

        return [...top, {
            defecto: "Otros",
            frecuencia: frecuenciaOtros,
            porcentajeAcumulado: acumuladoReal,
            esOtros: true,
        }];
    }

    _renderEvolucionMensual(canvasId, notaId, evolucion, titulo) {
        const canvas = document.getElementById(canvasId);
        const nota = document.getElementById(notaId);
        const meses = evolucion.length;

        if (meses < 2) {
            if (canvas) canvas.style.display = "none";
            if (nota) {
                nota.textContent = meses === 1
                    ? "Rango de un solo mes — sin evolución mensual que mostrar."
                    : "Sin registros en el período para mostrar evolución.";
                nota.style.display = "block";
            }
            return;
        }

        if (canvas) canvas.style.display = "block";
        if (nota) nota.style.display = "none";

        this._chartBarAgrupadaStats(canvasId, evolucion, "mesLabel", [
            { key: "recuperados", label: "Recuperados", color: "#22c55e" },
            { key: "destruidos", label: "Destruidos", color: "#ef4444" },
        ], titulo, "mesLargo");
    }

    // ---------- Charts (mismo patrón visual/técnico que faret.controller.js / Inicio Faret) ----------

    // opts.scroll=true: el canvas muestra TODAS las categorías (sin Top-N ni "Otros" — no se
    // oculta ningún dato real), con un alto calculado según la cantidad de filas para que cada
    // barra quede legible; el contenedor `.fnc-chart-scroll` (ver CSS) limita el alto visible y
    // agrega scroll interno para que el bloque no crezca sin límite. Se fija con !important
    // inline porque la regla general `.fnc-stats-card canvas { height: 200px !important }` sería
    // más específica que un alto fijo en CSS y necesita un valor dinámico por gráfico.
    _chartBarHorizontalStats(canvasId, rows, labelKey, valueKey, label, titulo, opts = {}) {
        const ctx = document.getElementById(canvasId);
        if (!ctx) return;

        if (opts.scroll) {
            const alto = Math.max(180, rows.length * 26);
            ctx.style.setProperty("height", `${alto}px`, "important");
        }

        // Paleta cíclica: antes eran 7 colores fijos y con más de 7 barras (posible desde que
        // estos gráficos muestran todas las categorías, sin límite Top-N) las barras 8+ quedaban
        // sin color explícito y Chart.js les aplicaba un azul por defecto, indistinguible del
        // resto — con el módulo (%) cada barra siempre tiene un color real de la paleta.
        const paleta = ["#ef4444", "#f97316", "#eab308", "#22c55e", "#16a34a", "#3b82f6", "#6366f1", "#a855f7", "#ec4899", "#14b8a6"];

        const chart = new Chart(ctx, {
            type: "bar",
            data: {
                labels: rows.map(r => r[labelKey] || "-"),
                datasets: [{
                    label,
                    data: rows.map(r => Number(r[valueKey] || 0)),
                    backgroundColor: rows.map((_, i) => paleta[i % paleta.length]),
                    borderRadius: 8,
                }],
            },
            options: {
                indexAxis: "y",
                responsive: true,
                maintainAspectRatio: false,
                plugins: { legend: { display: false } },
                scales: { x: { beginAtZero: true }, y: { ticks: { font: { size: 11 } } } },
            },
        });

        this._statsCharts.push({ chart, canvas: ctx, titulo });
    }

    // Mismo patrón que _chartDoughnut en faret.controller.js / Inicio Faret.
    _chartDoughnutStats(canvasId, rows, labelKey, valueKey, titulo) {
        const ctx = document.getElementById(canvasId);
        if (!ctx) return;

        const chart = new Chart(ctx, {
            type: "doughnut",
            data: {
                labels: rows.map(r => r[labelKey] || "-"),
                datasets: [{
                    data: rows.map(r => Number(r[valueKey] || 0)),
                    backgroundColor: ["#ef4444", "#f97316", "#eab308", "#22c55e", "#3b82f6", "#6366f1", "#a855f7"],
                    borderWidth: 0,
                }],
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                plugins: { legend: { position: "right", labels: { font: { size: 11 } } } },
                cutout: "62%",
            },
        });

        this._statsCharts.push({ chart, canvas: ctx, titulo });
    }

    // `tooltipKey` es opcional: si se indica, el título del tooltip usa ese campo de `rows`
    // (ej. período completo "Enero 2026") en vez de la etiqueta corta del eje ("ene 26").
    // Sin rotación fija ni maxTicksLimit: se deja que Chart.js calcule la rotación (0-45°) y el
    // salteo de ticks según el ancho real disponible, para que nunca queden textos superpuestos
    // sin importar cuántos meses entren en el rango filtrado.
    _chartBarAgrupadaStats(canvasId, rows, labelKey, series, titulo, tooltipKey) {
        const ctx = document.getElementById(canvasId);
        if (!ctx) return;

        const chart = new Chart(ctx, {
            type: "bar",
            data: {
                labels: rows.map(r => r[labelKey] || "-"),
                datasets: series.map(s => ({
                    label: s.label,
                    data: rows.map(r => Number(r[s.key] || 0)),
                    backgroundColor: s.color,
                    borderRadius: 6,
                })),
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                plugins: {
                    legend: { position: "bottom", labels: { font: { size: 11 } } },
                    tooltip: tooltipKey ? {
                        callbacks: {
                            title: items => (items[0] ? rows[items[0].dataIndex]?.[tooltipKey] || "" : ""),
                        },
                    } : undefined,
                },
                scales: {
                    y: {
                        beginAtZero: true,
                        ticks: { callback: v => Number(v).toLocaleString("es-CL") },
                    },
                    x: {
                        ticks: {
                            autoSkip: true,
                            maxRotation: 45,
                            minRotation: 0,
                            font: { size: 11 },
                        },
                    },
                },
            },
        });

        this._statsCharts.push({ chart, canvas: ctx, titulo });
    }

    // Pareto real: barras = frecuencia por defecto, línea = % acumulado sobre eje secundario
    // (mismo patrón que _chartPareto en faret.controller.js / Inicio Faret). `rows` ya viene
    // recortado a Top N + "Otros" (_aplicarTopNOtros) — como máximo ~11 barras, así que se quita
    // autoSkip/maxTicksLimit (con pocas categorías no hace falta saltar etiquetas, y saltarlas
    // dejaría barras sin nombre visible). Los nombres largos se truncan solo en el eje (tooltip
    // conserva el nombre completo desde `rows`, el dato original nunca se modifica).
    _chartParetoStats(canvasId, rows, labelKey, freqKey, pctKey, titulo) {
        const ctx = document.getElementById(canvasId);
        if (!ctx) return;

        const truncarEtiqueta = (texto, max = 14) =>
            texto.length > max ? `${texto.slice(0, max - 1)}…` : texto;

        const chart = new Chart(ctx, {
            type: "bar",
            data: {
                labels: rows.map(r => r[labelKey] || "-"),
                datasets: [
                    {
                        label: "Frecuencia",
                        data: rows.map(r => Number(r[freqKey] || 0)),
                        backgroundColor: rows.map(r => r.esOtros ? "#94a3b8" : "#3b82f6"),
                        borderRadius: 6,
                        yAxisID: "y",
                    },
                    {
                        type: "line",
                        label: "% Acumulado",
                        data: rows.map(r => Number(r[pctKey] || 0)),
                        borderColor: "#ef4444",
                        backgroundColor: "#ef4444",
                        pointBackgroundColor: "#ef4444",
                        pointRadius: 3,
                        tension: 0.25,
                        yAxisID: "y1",
                    },
                ],
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                plugins: {
                    legend: { position: "bottom", labels: { font: { size: 11 } } },
                    tooltip: {
                        callbacks: {
                            // Nombre completo del defecto en el tooltip, aunque el eje muestre la
                            // versión truncada — el dato (`rows`) nunca se modifica.
                            title: items => (items[0] ? rows[items[0].dataIndex]?.[labelKey] || "" : ""),
                        },
                    },
                },
                scales: {
                    y: { beginAtZero: true, position: "left", title: { display: true, text: "Frecuencia" } },
                    y1: {
                        beginAtZero: true,
                        max: 100,
                        position: "right",
                        grid: { drawOnChartArea: false },
                        title: { display: true, text: "% Acumulado" },
                    },
                    x: {
                        ticks: {
                            maxRotation: 40,
                            minRotation: 40,
                            font: { size: 10 },
                            callback: function (value) {
                                return truncarEtiqueta(String(this.getLabelForValue(value)));
                            },
                        },
                    },
                },
            },
        });

        // `full: true` marca este gráfico para ocupar el ancho completo en el reporte impreso
        // (único con doble eje + más categorías, necesita más espacio horizontal).
        this._statsCharts.push({ chart, canvas: ctx, titulo, full: true });
    }

    _destroyStatsCharts() {
        (this._statsCharts || []).forEach(({ chart }) => {
            try { chart.destroy(); } catch { /* noop */ }
        });
        this._statsCharts = [];
    }

    _setText(id, value) {
        const el = document.getElementById(id);
        if (el) el.textContent = Number(value || 0).toLocaleString("es-CL");
    }

    // ---------- Reporte estadístico imprimible ----------

    // Toma el <canvas> YA renderizado en pantalla de cada gráfico (chart.canvas — mismas
    // instancias que ve el usuario con los filtros activos, sin crear ni recalcular ningún
    // Chart). Valida ancho/alto del canvas y el DataURL resultante antes de incluirlo; si algo
    // falla se omite ESE gráfico puntual (con detalle en consola) sin bloquear el resto del
    // reporte. El console.table queda como diagnóstico permanente de bajo costo (solo corre al
    // pulsar "Imprimir Reporte Estadístico", no en cada render) para poder auditar futuros
    // problemas de impresión sin tener que reintroducir logs.
    _capturarGraficosParaImpresion() {
        const diagnostico = [];
        const resultado = [];

        (this._statsCharts || []).forEach(({ chart, titulo, full }) => {
            const canvas = chart?.canvas || null;
            const fila = {
                grafico: titulo,
                "canvas.width": canvas?.width ?? "-",
                "canvas.height": canvas?.height ?? "-",
                dataUrlLength: 0,
                prefijoValido: false,
                incluido: false,
            };

            if (!canvas || !canvas.width || !canvas.height) {
                diagnostico.push(fila);
                console.warn(`[Reporte NC] "${titulo}" omitido: canvas inexistente o sin dimensiones.`);
                return;
            }

            let dataUrl = "";
            try {
                dataUrl = canvas.toDataURL("image/png");
            } catch (err) {
                diagnostico.push(fila);
                console.warn(`[Reporte NC] "${titulo}" omitido: canvas.toDataURL() lanzó un error.`, err);
                return;
            }

            fila.dataUrlLength = dataUrl.length;
            fila.prefijoValido = dataUrl.startsWith("data:image/png;base64,");

            if (!fila.prefijoValido || dataUrl.length < 200) {
                diagnostico.push(fila);
                console.warn(`[Reporte NC] "${titulo}" omitido: DataURL inválido o demasiado corto (longitud=${dataUrl.length}).`);
                return;
            }

            fila.incluido = true;
            diagnostico.push(fila);
            resultado.push({ titulo, imagen: dataUrl, full: !!full });
        });

        console.table(diagnostico);
        return resultado;
    }

    _imprimirReporteEstadistico() {
        const filtrados = this._filtrarItems();
        const ind = this._calcularIndicadores(filtrados);
        const paretoImpreso = this._aplicarTopNOtros(ind.pareto);

        // Captura EXACTAMENTE los gráficos ya renderizados en pantalla con los filtros activos
        // (this._statsCharts ya está sincronizado por el último _renderTabla()) — no se recrea
        // ningún Chart ni se recalcula nada solo para imprimir.
        const graficos = this._capturarGraficosParaImpresion();

        window.PrintExporter.printReport({
            empresa: "FARET",
            titulo: "Reporte Estadístico de No Conformidades",
            subtitulo: this._resumenFiltrosTexto(),
            totalRegistros: filtrados.length,
            resumen: [
                { label: "Cuarentenas — total", valor: ind.cuarentenas.total },
                { label: "Cantidad total unidades cuarentenas", valor: ind.cuarentenas.cantidadTotalUnidades },
                { label: "Rechazos cliente — total", valor: ind.rechazosCliente.total },
                { label: "Rechazados totales", valor: ind.rechazosTotales },
                { label: "Total reclamos", valor: ind.totalReclamos },
                { label: "NC con reposición", valor: ind.reposicionDestruccion.ncConReposicion },
                { label: "NC con destrucción", valor: ind.reposicionDestruccion.ncConDestruccion },
                { label: "NC con reposición y destrucción", valor: ind.reposicionDestruccion.ncConAmbas },
                { label: "Cantidad total unidades reposiciones", valor: ind.reposicionDestruccion.cantidadRepuestaTotal },
                { label: "Cantidad destruida total", valor: ind.reposicionDestruccion.cantidadDestruidaTotal },
                { label: "Máquinas involucradas", valor: ind.maquinasInvolucradas },
                { label: "Operadores involucrados", valor: ind.operadoresInvolucrados },
                { label: "Clientes involucrados", valor: ind.clientesInvolucrados },
            ],
            graficos,
            tablas: [
                {
                    titulo: "PNC por familia de producto",
                    columnas: ["Familia", "Total"],
                    filas: ind.porFamilia.map(r => [r.categoria, r.total]),
                },
                {
                    titulo: "Incidentes por área",
                    columnas: ["Área", "Total"],
                    filas: ind.porArea.map(r => [r.categoria, r.total]),
                },
                {
                    titulo: "Máquinas involucradas",
                    columnas: ["Máquina", "Total"],
                    filas: ind.porMaquina.map(r => [r.categoria, r.total]),
                },
                {
                    titulo: "Operadores involucrados",
                    columnas: ["Operador", "Total"],
                    filas: ind.porOperador.map(r => [r.categoria, r.total]),
                },
                {
                    titulo: "Clientes involucrados",
                    columnas: ["Cliente", "Total"],
                    filas: ind.porCliente.map(r => [r.categoria, r.total]),
                },
                {
                    titulo: "Pareto de defectos",
                    columnas: ["Defecto", "Frecuencia", "% Acumulado"],
                    filas: paretoImpreso.map(r => [r.defecto, r.frecuencia, `${r.porcentajeAcumulado}%`]),
                },
            ],
        });
    }

    // ---------- Detalle (Ver) ----------

    async _verDetalle(id, dataId) {
        const modal = document.getElementById("fnc-detalle-modal");
        const body = document.getElementById("fnc-detalle-body");
        const regSeccion = document.getElementById("fnc-detalle-registro");

        body.innerHTML = "Cargando...";
        modal.style.display = "flex";
        regSeccion.style.display = "none";
        this._detalleDataId = dataId || null;
        this._detalleDataRow = null;

        try {
            const res = await window.PhotinoBridge.send({ action: "faret.nc.get", id: Number(id) });

            if (!res.ok) {
                body.innerHTML = `<div class="faret-error">${res.error || "Error al obtener el detalle"}</div>`;
                return;
            }

            const nc = res.data;
            body.innerHTML = `
                <div class="fnc-detalle-grid">
                    <div><strong>Código:</strong> ${nc.codigo ?? "-"}</div>
                    <div><strong>Estado:</strong> ${nc.estado ?? "-"}</div>
                    <div><strong>Estado de gestión:</strong> ${this._labelEstadoGestion(nc.estadoGestion)}</div>
                    <div><strong>Responsable:</strong> ${nc.responsable ?? "-"}</div>
                    <div><strong>Tipo:</strong> ${nc.tipo ?? "-"}</div>
                    <div><strong>Origen:</strong> ${nc.origen ?? "-"}</div>
                    <div><strong>Severidad:</strong> ${nc.severidad ?? "-"}</div>
                    <div><strong>Proceso / Área:</strong> ${nc.proceso ?? "-"}</div>
                    <div><strong>Norma:</strong> ${nc.norma ?? "-"}</div>
                    <div><strong>Vínculo:</strong> ${
                        nc.sistemaOrigen === "DATA_FARET" && nc.origenId ? `Data #${nc.origenId}`
                        : nc.sistemaOrigen === "INSPECCION_FARET" && nc.origenId ? `Inspección #${nc.origenId}`
                        : "Manual"
                    }</div>
                    <div><strong>Fecha creación:</strong> ${nc.fechaCreacion ? new Date(nc.fechaCreacion).toLocaleString("es-CL") : "-"}</div>
                </div>
                <div class="fnc-detalle-titulo"><strong>Título:</strong> ${nc.titulo ?? "-"}</div>
                <div class="fnc-detalle-descripcion"><strong>Descripción:</strong><br>${nc.descripcion ?? "-"}</div>
            `;
        } catch {
            body.innerHTML = `<div class="faret-error">Error de comunicación con el backend</div>`;
            return;
        }

        if (this._detalleDataId) {
            const dataRow = this._dataItems.find(d => String(d.id) === String(this._detalleDataId));
            if (dataRow) {
                this._detalleDataRow = dataRow;
                this._renderRegistro(dataRow);
                regSeccion.style.display = "block";
            }
        }
    }

    _cerrarDetalle() {
        document.getElementById("fnc-detalle-modal").style.display = "none";
    }

    // ---------- Registro completo (Data/importacion_pnc) dentro del modal de detalle ----------

    _regCamposMap() {
        return {
            fechaIngreso: { id: "fnc-reg-fecha-ingreso", tipo: "fecha" },
            npNv: { id: "fnc-reg-np-nv", tipo: "texto" },
            cliente: { id: "fnc-reg-cliente", tipo: "texto" },
            codigo: { id: "fnc-reg-codigo", tipo: "texto" },
            producto: { id: "fnc-reg-producto", tipo: "texto" },
            familiaProducto: { id: "fnc-reg-familia-producto", tipo: "texto" },
            tipoPnc: { id: "fnc-reg-tipo-pnc", tipo: "texto" },
            nivel: { id: "fnc-reg-nivel", tipo: "texto" },
            categoriaDefecto: { id: "fnc-reg-categoria-defecto", tipo: "texto" },
            tipoFalla: { id: "fnc-reg-tipo-falla", tipo: "texto" },
            impacto: { id: "fnc-reg-impacto", tipo: "texto" },
            cantRequerida: { id: "fnc-reg-cant-requerida", tipo: "numero" },
            cantRechazada: { id: "fnc-reg-cant-rechazada", tipo: "numero" },
            cantRecuperada: { id: "fnc-reg-cant-recuperada", tipo: "numero" },
            pncReal: { id: "fnc-reg-pnc-real", tipo: "numero" },
            disposicion: { id: "fnc-reg-disposicion", tipo: "texto" },
            cantDestruida: { id: "fnc-reg-cant-destruida", tipo: "numero" },
            cantRepuesta: { id: "fnc-reg-cant-repuesta", tipo: "numero" },
            area: { id: "fnc-reg-area", tipo: "texto" },
            maquina: { id: "fnc-reg-maquina", tipo: "texto" },
            operador: { id: "fnc-reg-operador", tipo: "texto" },
            supervisor: { id: "fnc-reg-supervisor", tipo: "texto" },
            revisadoPor: { id: "fnc-reg-revisado-por", tipo: "texto" },
            fechaSalida: { id: "fnc-reg-fecha-salida", tipo: "fecha" },
            fechaFabricacion: { id: "fnc-reg-fecha-fabricacion", tipo: "fecha" },
            descripcionDefecto: { id: "fnc-reg-descripcion-defecto", tipo: "texto" },
            observacion: { id: "fnc-reg-observacion", tipo: "texto" },
            causaRaiz: { id: "fnc-reg-causa-raiz", tipo: "texto" },
            accionesCorrectivas: { id: "fnc-reg-acciones-correctivas", tipo: "texto" },
            verificacionSeguimiento: { id: "fnc-reg-verificacion-seguimiento", tipo: "texto" },
        };
    }

    _renderRegistro(d) {
        const campos = this._regCamposMap();
        Object.entries(campos).forEach(([campo, { id, tipo }]) => {
            const el = document.getElementById(id);
            if (tipo === "fecha") {
                el.value = d[campo] ? String(d[campo]).substring(0, 10) : "";
            } else {
                el.value = d[campo] ?? "";
            }
        });

        this._recalcularPctRecupRegistro();
        this._actualizarVisibilidadDisposicion("fnc-reg-tipo-pnc", "fnc-reg-disposicion-row");
        this._resincronizarAreaJerarquia("reg");
        this._modoEdicionRegistro(false);
        this._ultimoCampoEditadoRegistro = null;
        document.getElementById("fnc-reg-error").style.display = "none";
    }

    // Disposición (reposición/destrucción) solo aplica a Cuarentena y Rechazo Cliente — el resto
    // de los tipos de PNC no la necesitan (decisión explícita del usuario).
    _esDisposicionAplicable(tipoPnc) {
        return tipoPnc === "Cuarentena" || tipoPnc === "Rechazo Cliente";
    }

    _actualizarVisibilidadDisposicion(tipoPncSelectId, filaId) {
        const tipoPnc = document.getElementById(tipoPncSelectId).value;
        document.getElementById(filaId).style.display =
            this._esDisposicionAplicable(tipoPnc) ? "flex" : "none";
    }

    _modoEdicionRegistro(editable) {
        Object.values(this._regCamposMap()).forEach(({ id }) => {
            document.getElementById(id).disabled = !editable;
        });

        document.getElementById("fnc-reg-editar-btn").style.display = editable ? "none" : "inline-block";
        document.getElementById("fnc-reg-guardar-cambio-btn").style.display = editable ? "inline-block" : "none";
        document.getElementById("fnc-reg-guardar-todo-btn").style.display = editable ? "inline-block" : "none";
        document.getElementById("fnc-reg-cancelar-btn").style.display = editable ? "inline-block" : "none";
    }

    _habilitarEdicionRegistro() {
        this._modoEdicionRegistro(true);
        this._ultimoCampoEditadoRegistro = null;

        Object.entries(this._regCamposMap()).forEach(([campo, { id }]) => {
            const el = document.getElementById(id);
            const marcar = () => {
                this._ultimoCampoEditadoRegistro = campo;
                if (campo === "cantRechazada" || campo === "cantRecuperada") this._recalcularPctRecupRegistro();
                if (campo === "tipoPnc") this._actualizarVisibilidadDisposicion("fnc-reg-tipo-pnc", "fnc-reg-disposicion-row");
            };
            el.oninput = marcar;
            el.onchange = marcar;
        });
    }

    _cancelarEdicionRegistro() {
        if (this._detalleDataRow) this._renderRegistro(this._detalleDataRow);
    }

    // Mismo cálculo que el backend (CantRecuperada / CantRechazada), solo para feedback visual.
    _recalcularPctRecupRegistro() {
        const rechazada = parseFloat(document.getElementById("fnc-reg-cant-rechazada").value);
        const recuperada = parseFloat(document.getElementById("fnc-reg-cant-recuperada").value);
        const el = document.getElementById("fnc-reg-pct-recup");

        if (!rechazada || isNaN(recuperada)) {
            el.value = "";
            return;
        }
        el.value = `${(recuperada / rechazada * 100).toFixed(2)}%`;
    }

    _leerValorCampoRegistro(campo) {
        const { id, tipo } = this._regCamposMap()[campo];
        const raw = document.getElementById(id).value;
        if (tipo === "numero") return raw === "" ? null : parseFloat(raw);
        if (tipo === "fecha") return raw || null;
        return raw.trim();
    }

    // Aplica los cambios ya guardados en la API al objeto en memoria (el mismo que usan la tabla
    // principal y el propio modal), recalcula % Recup. localmente y refresca la tabla — evita un
    // refetch completo de Data solo para reflejar la edición.
    _aplicarCambiosRegistroEnMemoria(cambios) {
        Object.assign(this._detalleDataRow, cambios);
        this._detalleDataRow.pctRecuperacion =
            this._detalleDataRow.cantRecuperada != null && this._detalleDataRow.cantRechazada > 0
                ? this._detalleDataRow.cantRecuperada / this._detalleDataRow.cantRechazada
                : null;
        this._combinar();
        this._renderTabla();
    }

    async _guardarCambioRegistro() {
        const errorEl = document.getElementById("fnc-reg-error");
        errorEl.style.display = "none";

        if (!this._ultimoCampoEditadoRegistro) {
            errorEl.textContent = "No se detectó ningún cambio. Modifique un campo antes de guardar.";
            errorEl.style.display = "block";
            return;
        }
        if (!this._detalleDataId) return;

        const campo = this._ultimoCampoEditadoRegistro;
        const valor = this._leerValorCampoRegistro(campo);

        const btn = document.getElementById("fnc-reg-guardar-cambio-btn");
        btn.disabled = true;
        try {
            const res = await window.PhotinoBridge.send({
                action: "faret.nc.actualizarRegistro",
                id: Number(this._detalleDataId),
                [campo]: valor,
            });

            if (!res.ok) {
                errorEl.textContent = res.error || "Error al guardar el cambio";
                errorEl.style.display = "block";
                return;
            }

            this._aplicarCambiosRegistroEnMemoria({ [campo]: valor });
            this._renderRegistro(this._detalleDataRow);
            this._showMensaje("Campo actualizado", true);
        } catch {
            errorEl.textContent = "Error de comunicación con el backend";
            errorEl.style.display = "block";
        } finally {
            btn.disabled = false;
        }
    }

    async _guardarTodoRegistro() {
        const errorEl = document.getElementById("fnc-reg-error");
        errorEl.style.display = "none";
        if (!this._detalleDataId) return;

        const campos = this._regCamposMap();
        const payload = {};
        Object.keys(campos).forEach(campo => { payload[campo] = this._leerValorCampoRegistro(campo); });

        if (!payload.npNv || !payload.cliente || !payload.codigo || !payload.producto
            || !payload.descripcionDefecto || !payload.categoriaDefecto || !payload.nivel
            || payload.cantRequerida === null || payload.cantRechazada === null) {
            errorEl.textContent = "NP/NV, Cliente, Código, Producto, Categoría defecto, Nivel, "
                + "Descripción defecto, Cant. requerida y Cant. rechazada son obligatorios";
            errorEl.style.display = "block";
            return;
        }

        const btn = document.getElementById("fnc-reg-guardar-todo-btn");
        btn.disabled = true;
        try {
            const res = await window.PhotinoBridge.send({
                action: "faret.nc.actualizarRegistro",
                id: Number(this._detalleDataId),
                ...payload,
            });

            if (!res.ok) {
                errorEl.textContent = res.error || "Error al guardar los cambios";
                errorEl.style.display = "block";
                return;
            }

            this._aplicarCambiosRegistroEnMemoria(payload);
            this._renderRegistro(this._detalleDataRow);
            this._showMensaje("Registro actualizado completo", true);
        } catch {
            errorEl.textContent = "Error de comunicación con el backend";
            errorEl.style.display = "block";
        } finally {
            btn.disabled = false;
        }
    }

    // ---------- Formulario Nueva / Editar NC (manual, sin vínculo a Data) ----------

    _abrirFormNuevo() {
        this._editingId = null;
        document.getElementById("fnc-form-titulo").textContent = "Nueva No Conformidad";
        document.getElementById("fnc-form-tipo").value = "INTERNA";
        document.getElementById("fnc-form-origen").value = "AUDITORIA_INTERNA";
        document.getElementById("fnc-form-titulo-input").value = "";
        document.getElementById("fnc-form-severidad").value = "ALTA";
        document.getElementById("fnc-form-proceso").value = "";
        document.getElementById("fnc-form-norma").value = "";
        document.getElementById("fnc-form-fecha").value = "";
        document.getElementById("fnc-form-reportado").value = "";
        document.getElementById("fnc-form-responsable").value = "";
        document.getElementById("fnc-form-estado-campo").style.display = "none";
        document.getElementById("fnc-form-nota-estado").style.display = "none";
        document.getElementById("fnc-form-error").style.display = "none";
        document.getElementById("fnc-form-card").style.display = "block";
    }

    _abrirFormEditar(id) {
        const item = this._items.find(i => String(i.id) === String(id));
        if (!item) return;

        this._editingId = item.id;
        document.getElementById("fnc-form-titulo").textContent = `Editar No Conformidad ${item.codigo ?? ""}`;
        document.getElementById("fnc-form-tipo").value = item.tipo ?? "INTERNA";
        document.getElementById("fnc-form-origen").value = item.origen ?? "AUDITORIA_INTERNA";
        document.getElementById("fnc-form-titulo-input").value = item.titulo ?? "";
        document.getElementById("fnc-form-severidad").value = item.severidad ?? "ALTA";
        document.getElementById("fnc-form-proceso").value = item.proceso ?? "";
        document.getElementById("fnc-form-norma").value = item.norma ?? "";
        document.getElementById("fnc-form-fecha").value = item.fechaCreacion ? item.fechaCreacion.substring(0, 10) : "";
        document.getElementById("fnc-form-descripcion").value = item.descripcion ?? "";
        // La API no devuelve "reportadoPor" ni "responsable" al listar/consultar, solo al crear.
        document.getElementById("fnc-form-reportado").value = "";
        document.getElementById("fnc-form-responsable").value = "";
        document.getElementById("fnc-form-estado").value = item.estado ?? "-";
        document.getElementById("fnc-form-estado-campo").style.display = "flex";
        document.getElementById("fnc-form-nota-estado").style.display = "block";
        document.getElementById("fnc-form-error").style.display = "none";
        document.getElementById("fnc-form-card").style.display = "block";
    }

    _cerrarForm() {
        document.getElementById("fnc-form-card").style.display = "none";
        this._editingId = null;
    }

    _showMensaje(texto, ok) {
        const el = document.getElementById("fnc-mensaje");
        el.textContent = texto;
        el.style.display = "block";
        el.style.background = ok ? "#ECFDF5" : "#FEF2F2";
        el.style.color = ok ? "#065F46" : "#991B1B";
        el.style.borderLeftColor = ok ? "#10B981" : "#EF4444";
        setTimeout(() => { el.style.display = "none"; }, 4000);
    }

    async _guardar() {
        const errorEl = document.getElementById("fnc-form-error");
        const guardarBtn = document.getElementById("fnc-guardar-btn");
        errorEl.style.display = "none";

        const payload = {
            tipo: document.getElementById("fnc-form-tipo").value,
            origen: document.getElementById("fnc-form-origen").value.trim(),
            titulo: document.getElementById("fnc-form-titulo-input").value.trim(),
            descripcion: document.getElementById("fnc-form-descripcion").value.trim(),
            severidad: document.getElementById("fnc-form-severidad").value,
            proceso: document.getElementById("fnc-form-proceso").value.trim(),
            norma: document.getElementById("fnc-form-norma").value.trim(),
            reportadoPor: document.getElementById("fnc-form-reportado").value.trim(),
            responsable: document.getElementById("fnc-form-responsable").value.trim(),
            fechaDeteccion: document.getElementById("fnc-form-fecha").value,
        };

        if (!payload.origen || !payload.titulo || !payload.descripcion || !payload.proceso || !payload.fechaDeteccion) {
            errorEl.textContent = "Origen, título, descripción, proceso/área y fecha de detección son obligatorios";
            errorEl.style.display = "block";
            return;
        }

        guardarBtn.disabled = true;
        try {
            const action = this._editingId ? "faret.nc.update" : "faret.nc.create";
            const res = await window.PhotinoBridge.send({
                action,
                ...(this._editingId ? { id: this._editingId } : {}),
                ...payload,
            });

            if (!res.ok) {
                errorEl.textContent = res.error || "Error al guardar la no conformidad";
                errorEl.style.display = "block";
                return;
            }

            this._cerrarForm();
            this._showMensaje(this._editingId ? "No conformidad actualizada" : "No conformidad creada", true);
            this._loadLista();
        } catch {
            errorEl.textContent = "Error de comunicación con el backend";
            errorEl.style.display = "block";
        } finally {
            guardarBtn.disabled = false;
        }
    }

    _usuarioActual() {
        return sessionStorage.getItem("faretNombreUsuario") || "";
    }

    // ---------- Catálogos administrables (Cliente/Categoría defecto/Tipo de falla/Supervisor/
    // Revisado por + Área→Máquina/Operador jerárquico) ----------
    // Reemplaza el <datalist> nativo (solo sugería valores ya escritos, sin persistir nada) por
    // el combo genérico window.CatalogCombo: seleccionar uno existente, buscar, o crear uno
    // nuevo que queda guardado en el catálogo real (Paso 2) y disponible para todos los usuarios
    // desde el próximo focus (misma sesión: al toque; otra sesión: sin caché de por medio).

    // Cliente/Categoría defecto/Tipo de falla/Supervisor/Revisado por/Familia de producto/Nivel/
    // Impacto: catálogos planos propios de PNC Faret (cat_faret_*). Área/Máquina/Operador NO
    // están acá — son jerárquicos, ver _attachAreaJerarquia. Tipo PNC y Disposición NO se abren
    // (decisión explícita: Tipo PNC pilota la visibilidad de Disposición y los indicadores de
    // Cuarentenas/Rechazos/Reclamos comparando el string exacto).
    _catalogosPlanosConfig() {
        return [
            { campo: "cliente", cacheKey: "fnc-cat-cliente", listAction: "faret.pncCatalogos.clientes.list", crearAction: "faret.pncCatalogos.clientes.crear" },
            { campo: "categoria-defecto", cacheKey: "fnc-cat-categoria-defecto", listAction: "faret.pncCatalogos.categoriasDefecto.list", crearAction: "faret.pncCatalogos.categoriasDefecto.crear" },
            { campo: "tipo-falla", cacheKey: "fnc-cat-tipo-falla", listAction: "faret.pncCatalogos.tiposFalla.list", crearAction: "faret.pncCatalogos.tiposFalla.crear" },
            { campo: "supervisor", cacheKey: "fnc-cat-supervisor", listAction: "faret.pncCatalogos.supervisores.list", crearAction: "faret.pncCatalogos.supervisores.crear" },
            { campo: "revisado-por", cacheKey: "fnc-cat-revisado-por", listAction: "faret.pncCatalogos.revisores.list", crearAction: "faret.pncCatalogos.revisores.crear" },
            { campo: "familia-producto", cacheKey: "fnc-cat-familia-producto", listAction: "faret.pncCatalogos.familiasProducto.list", crearAction: "faret.pncCatalogos.familiasProducto.crear" },
            { campo: "nivel", cacheKey: "fnc-cat-nivel", listAction: "faret.pncCatalogos.niveles.list", crearAction: "faret.pncCatalogos.niveles.crear" },
            { campo: "impacto", cacheKey: "fnc-cat-impacto", listAction: "faret.pncCatalogos.impactos.list", crearAction: "faret.pncCatalogos.impactos.crear" },
        ];
    }

    async _catalogoObtener(action, extra) {
        const res = await window.PhotinoBridge.send({ action, ...(extra || {}) });
        return res.ok && Array.isArray(res.data) ? res.data : [];
    }

    async _catalogoCrear(action, nombre, extra) {
        const res = await window.PhotinoBridge.send({ action, nombre, ...(extra || {}) });
        if (!res.ok) {
            this._showMensaje(res.error || "No se pudo crear el valor de catálogo", false);
            return null;
        }
        return res.data;
    }

    // El <input> no trae un <div> hermano propio para el dropdown en el HTML (a diferencia del
    // combo de Responsable, que sí lo declara explícito) — se crea una sola vez, en el lugar
    // correcto para que .fnc-combo-wrap (position:relative en el <div class="fnc-form-campo">
    // padre) lo posicione bien.
    // No busca por posición en el DOM (input.nextElementSibling): CatalogCombo.attach() reparenta
    // el dropdown a document.body para no quedar cortado por el overflow:auto del modal, así que
    // ya no es un hermano del input después del primer enganche — se cachea la referencia en el
    // propio input.
    _dropdownFor(input) {
        if (input._catalogComboDropdownEl) return input._catalogComboDropdownEl;
        const dd = document.createElement("div");
        dd.className = "fnc-combo-dropdown";
        dd.style.display = "none";
        input.insertAdjacentElement("afterend", dd);
        input._catalogComboDropdownEl = dd;
        return dd;
    }

    // Engancha los combos de un formulario completo ("npnc" o "reg"). Se llama una sola vez por
    // prefijo (desde init()) — CatalogCombo.attach ya es idempotente, pero no hace falta
    // reenganchar en cada apertura del modal: los <input> son los mismos nodos del DOM durante
    // toda la vida del módulo, solo se muestran/ocultan.
    _attachCatalogosCombos(prefix) {
        this._catalogosPlanosConfig().forEach(cfg => {
            const input = document.getElementById(`fnc-${prefix}-${cfg.campo}`);
            if (!input) return;
            window.CatalogCombo.attach(input, this._dropdownFor(input), {
                cacheKey: cfg.cacheKey,
                obtenerOpciones: () => this._catalogoObtener(cfg.listAction),
                crear: nombre => this._catalogoCrear(cfg.crearAction, nombre),
            });
        });

        this._attachAreaJerarquia(prefix);
    }

    // Área es un combo plano contra el catálogo real ya existente cat_areas (api/catalogos,
    // compartido con otros módulos de planta — decisión explícita de reutilizarlo en vez de crear
    // un catálogo de área propio de PNC). Al elegir/crear un Área, Máquina y Operador se
    // re-scopean a esa área (cat_maquinas/cat_operadores, ambas con FK a cat_areas) — no permiten
    // "+ Crear" hasta que haya un Área resuelta, para no insertar máquinas/operadores huérfanos.
    _attachAreaJerarquia(prefix) {
        const areaInput = document.getElementById(`fnc-${prefix}-area`);
        if (!areaInput) return;

        window.CatalogCombo.attach(areaInput, this._dropdownFor(areaInput), {
            cacheKey: "fnc-cat-area",
            obtenerOpciones: () => this._catalogoObtener("faret.catalogos.areas"),
            crear: nombre => this._catalogoCrear("faret.catalogos.areas.crear", nombre),
            onSeleccionar: item => this._reescoparHijosDeArea(prefix, item ? item.id : null),
        });

        this._reescoparHijosDeArea(prefix, areaInput.dataset.catalogId ? Number(areaInput.dataset.catalogId) : null);
    }

    _reescoparHijosDeArea(prefix, areaId) {
        [
            { sufijo: "maquina", listAction: "faret.catalogos.maquinas", crearAction: "faret.catalogos.maquinas.crear" },
            { sufijo: "operador", listAction: "faret.catalogos.operadores", crearAction: "faret.catalogos.operadores.crear" },
        ].forEach(({ sufijo, listAction, crearAction }) => {
            const input = document.getElementById(`fnc-${prefix}-${sufijo}`);
            if (!input) return;

            window.CatalogCombo.attach(input, this._dropdownFor(input), {
                cacheKey: `fnc-cat-${sufijo}:${areaId || "sin-area"}`,
                obtenerOpciones: () => this._catalogoObtener(listAction, areaId ? { areaId } : {}),
                crear: areaId ? (nombre => this._catalogoCrear(crearAction, nombre, { areaId })) : null,
                bloqueadoMsg: () => (areaId ? null : "Seleccione un Área para poder crear un valor nuevo"),
            });
        });
    }

    // Al abrir un formulario con un Área ya guardada como texto libre (dato histórico o recién
    // cargado desde Data), intenta resolver su id contra el catálogo real por nombre exacto
    // (case-insensitive) para que Máquina/Operador nazcan ya scoped — si no hay coincidencia,
    // quedan sin scope (lista completa) hasta que el usuario toque el campo Área.
    async _resincronizarAreaJerarquia(prefix) {
        const areaInput = document.getElementById(`fnc-${prefix}-area`);
        if (!areaInput) return;

        delete areaInput.dataset.catalogId;
        const nombreActual = areaInput.value.trim().toLowerCase();

        if (!nombreActual) {
            this._reescoparHijosDeArea(prefix, null);
            return;
        }

        const areas = await this._catalogoObtener("faret.catalogos.areas");
        const match = areas.find(a => (a.nombre || "").trim().toLowerCase() === nombreActual);
        if (match) areaInput.dataset.catalogId = match.id;
        this._reescoparHijosDeArea(prefix, match ? match.id : null);
    }

    // ---------- Nueva No Conformidad (registro completo en Data + vínculo de gestión automático) ----------

    _abrirNuevoPnc() {
        document.getElementById("fnc-npnc-error").style.display = "none";
        document.getElementById("fnc-npnc-fecha-ingreso").value = window.DateUtils.hoyISO();

        [
            "fnc-npnc-np-nv", "fnc-npnc-cliente", "fnc-npnc-codigo", "fnc-npnc-producto",
            "fnc-npnc-categoria-defecto", "fnc-npnc-cant-requerida", "fnc-npnc-cant-rechazada",
            "fnc-npnc-cant-recuperada", "fnc-npnc-pnc-real", "fnc-npnc-cant-destruida",
            "fnc-npnc-cant-repuesta", "fnc-npnc-area", "fnc-npnc-maquina",
            "fnc-npnc-operador", "fnc-npnc-supervisor", "fnc-npnc-revisado-por",
            "fnc-npnc-fecha-salida", "fnc-npnc-fecha-fabricacion", "fnc-npnc-descripcion-defecto",
            "fnc-npnc-observacion", "fnc-npnc-causa-raiz", "fnc-npnc-acciones-correctivas",
            "fnc-npnc-verificacion-seguimiento",
        ].forEach(id => { document.getElementById(id).value = ""; });

        document.getElementById("fnc-npnc-tipo-pnc").value = "";
        document.getElementById("fnc-npnc-nivel").value = "Mayor";
        document.getElementById("fnc-npnc-tipo-falla").value = "";
        document.getElementById("fnc-npnc-impacto").value = "Calidad";
        document.getElementById("fnc-npnc-familia-producto").value = "";
        document.getElementById("fnc-npnc-disposicion").value = "No aplica";
        document.getElementById("fnc-npnc-pct-recup").value = "";
        this._actualizarVisibilidadDisposicion("fnc-npnc-tipo-pnc", "fnc-npnc-disposicion-row");

        document.getElementById("fnc-npnc-pdf-input").value = "";
        document.getElementById("fnc-npnc-fotos-input").value = "";
        document.getElementById("fnc-npnc-adjuntos-error").style.display = "none";

        this._resincronizarAreaJerarquia("npnc");
        document.getElementById("fnc-nuevo-pnc-modal").style.display = "flex";
    }

    _cerrarNuevoPnc() {
        document.getElementById("fnc-nuevo-pnc-modal").style.display = "none";
    }

    // Mismo cálculo que el backend (CantRecuperada / CantRechazada), solo para feedback visual
    // inmediato — el valor que se guarda de verdad lo calcula la API.
    _recalcularPctRecup() {
        const rechazada = parseFloat(document.getElementById("fnc-npnc-cant-rechazada").value);
        const recuperada = parseFloat(document.getElementById("fnc-npnc-cant-recuperada").value);
        const el = document.getElementById("fnc-npnc-pct-recup");

        if (!rechazada || isNaN(recuperada)) {
            el.value = "";
            return;
        }
        el.value = `${(recuperada / rechazada * 100).toFixed(2)}%`;
    }

    async _guardarNuevoPnc() {
        const errorEl = document.getElementById("fnc-npnc-error");
        errorEl.style.display = "none";

        const val = id => document.getElementById(id).value.trim();
        const num = id => {
            const v = document.getElementById(id).value;
            return v === "" ? null : parseFloat(v);
        };

        const npNv = val("fnc-npnc-np-nv");
        const cliente = val("fnc-npnc-cliente");
        const codigo = val("fnc-npnc-codigo");
        const producto = val("fnc-npnc-producto");
        const categoriaDefecto = val("fnc-npnc-categoria-defecto");
        const nivel = val("fnc-npnc-nivel");
        const descripcionDefecto = val("fnc-npnc-descripcion-defecto");
        const cantRequerida = num("fnc-npnc-cant-requerida");
        const cantRechazada = num("fnc-npnc-cant-rechazada");

        if (!npNv || !cliente || !codigo || !producto || !categoriaDefecto || !nivel || !descripcionDefecto
            || cantRequerida === null || cantRechazada === null) {
            errorEl.textContent = "NP/NV, Cliente, Código, Producto, Categoría defecto, Nivel, "
                + "Descripción defecto, Cant. requerida y Cant. rechazada son obligatorios";
            errorEl.style.display = "block";
            return;
        }

        const tipoPnc = val("fnc-npnc-tipo-pnc");
        const area = val("fnc-npnc-area");
        const hoy = window.DateUtils.hoyISO();
        const fechaIngreso = val("fnc-npnc-fecha-ingreso") || hoy;

        const payload = {
            // Campos del registro Data (importacion_pnc)
            tipoPnc,
            fechaIngreso,
            npNv,
            cliente,
            codigo,
            producto,
            familiaProducto: val("fnc-npnc-familia-producto") || null,
            cantRequerida,
            cantRechazada,
            cantRecuperada: num("fnc-npnc-cant-recuperada"),
            pncReal: num("fnc-npnc-pnc-real"),
            disposicion: this._esDisposicionAplicable(tipoPnc) ? val("fnc-npnc-disposicion") : null,
            cantDestruida: this._esDisposicionAplicable(tipoPnc) ? num("fnc-npnc-cant-destruida") : null,
            cantRepuesta: this._esDisposicionAplicable(tipoPnc) ? num("fnc-npnc-cant-repuesta") : null,
            fechaSalida: val("fnc-npnc-fecha-salida") || null,
            fechaFabricacion: val("fnc-npnc-fecha-fabricacion") || null,
            descripcionDefecto,
            categoriaDefecto,
            nivel,
            tipoFalla: val("fnc-npnc-tipo-falla"),
            area,
            maquina: val("fnc-npnc-maquina"),
            operador: val("fnc-npnc-operador"),
            supervisor: val("fnc-npnc-supervisor"),
            revisadoPor: val("fnc-npnc-revisado-por"),
            impacto: val("fnc-npnc-impacto"),
            observacion: val("fnc-npnc-observacion"),
            causaRaiz: val("fnc-npnc-causa-raiz"),
            accionesCorrectivas: val("fnc-npnc-acciones-correctivas"),
            verificacionSeguimiento: val("fnc-npnc-verificacion-seguimiento"),
            // Campos del vínculo de gestión (Mejora Continua) — se autocompletan a partir de los
            // datos de arriba, igual que ya hace "Gestionar" al crear el vínculo desde una fila de Data.
            tipo: "INTERNA",
            origen: "AUDITORIA_INTERNA",
            titulo: `PNC ${npNv} - ${producto || cliente}`.trim(),
            descripcion: [categoriaDefecto, descripcionDefecto].filter(Boolean).join(" - "),
            severidad: this._mapNivelASeveridad(nivel),
            proceso: tipoPnc || area || "PNC Nueva",
            fechaDeteccion: fechaIngreso,
        };

        const adjuntosErrorEl = document.getElementById("fnc-npnc-adjuntos-error");
        adjuntosErrorEl.style.display = "none";

        const pdfFile = document.getElementById("fnc-npnc-pdf-input").files?.[0] || null;
        const fotoFiles = Array.from(document.getElementById("fnc-npnc-fotos-input").files || []);

        const validacionAdjuntos = this._validarAdjuntosNuevaNc(pdfFile, fotoFiles);
        if (validacionAdjuntos) {
            adjuntosErrorEl.textContent = validacionAdjuntos;
            adjuntosErrorEl.style.display = "block";
            return;
        }

        const btn = document.getElementById("fnc-npnc-guardar-btn");
        btn.disabled = true;
        try {
            const res = await window.PhotinoBridge.send({ action: "faret.nc.crearRegistro", ...payload });

            if (!res.ok) {
                errorEl.textContent = res.error || "Error al crear la no conformidad";
                errorEl.style.display = "block";
                return;
            }

            const ncId = res.data?.nc?.id;
            const erroresAdjuntos = ncId ? await this._subirAdjuntosNuevaNc(ncId, pdfFile, fotoFiles) : [];

            this._cerrarNuevoPnc();
            this._showMensaje(
                erroresAdjuntos.length
                    ? `No conformidad creada, pero hubo un problema subiendo adjuntos: ${erroresAdjuntos.join("; ")}`
                    : "No conformidad creada y gestionable",
                erroresAdjuntos.length === 0
            );
            await this._loadLista();
        } catch {
            errorEl.textContent = "Error de comunicación con el backend";
            errorEl.style.display = "block";
        } finally {
            btn.disabled = false;
        }
    }

    // Mismos límites que el resto del sistema de adjuntos (10MB/PDF, 5MB/foto, máx. 10 fotos).
    _validarAdjuntosNuevaNc(pdfFile, fotoFiles) {
        if (pdfFile) {
            if (pdfFile.type !== "application/pdf") return "Solo se permite un archivo PDF";
            if (pdfFile.size > 10 * 1024 * 1024) return "El PDF excede el tamaño máximo de 10 MB";
        }
        if (fotoFiles.length > 10) return "Máximo 10 fotografías";
        const tiposValidos = ["image/jpeg", "image/png"];
        for (const file of fotoFiles) {
            if (!tiposValidos.includes(file.type)) return `"${file.name}" no es JPG/PNG`;
            if (file.size > 5 * 1024 * 1024) return `"${file.name}" excede el tamaño máximo de 5 MB`;
        }
        return null;
    }

    // Sube los adjuntos elegidos en el formulario de "Nueva NC" recién después de que la NC ya
    // existe (necesitan su id). Reutiliza la misma acción/backend que el resto del sistema de
    // adjuntos (ver sección "Análisis y Plan de Acción"). Devuelve la lista de errores (vacía si
    // todo salió bien) — nunca revierte la NC ya creada por un adjunto que falle.
    async _subirAdjuntosNuevaNc(ncId, pdfFile, fotoFiles) {
        const errores = [];
        const usuario = this._usuarioActual();

        if (pdfFile) {
            try {
                const contenidoBase64 = await this._leerArchivoBase64(pdfFile);
                const res = await window.PhotinoBridge.send({
                    action: "faret.nc.adjuntos.subir",
                    id: Number(ncId),
                    tipo: "CAUSA_RAIZ_PDF",
                    nombreArchivo: pdfFile.name,
                    tipoMime: pdfFile.type,
                    contenidoBase64,
                    subidoPor: usuario,
                });
                if (!res.ok) errores.push(res.error || "Error al subir el PDF");
            } catch (err) {
                errores.push(err.message || "Error al subir el PDF");
            }
        }

        for (const file of fotoFiles) {
            try {
                const contenidoBase64 = await this._leerArchivoBase64(file);
                const res = await window.PhotinoBridge.send({
                    action: "faret.nc.adjuntos.subir",
                    id: Number(ncId),
                    tipo: "EVIDENCIA_FOTO",
                    nombreArchivo: file.name,
                    tipoMime: file.type,
                    contenidoBase64,
                    subidoPor: usuario,
                });
                if (!res.ok) errores.push(res.error || `Error al subir "${file.name}"`);
            } catch (err) {
                errores.push(err.message || `Error al subir "${file.name}"`);
            }
        }

        return errores;
    }

    // ---------- Gestionar (crear vínculo Data→NC, o gestionar/cerrar/seguimiento de una NC existente) ----------

    _mapNivelASeveridad(nivel) {
        const n = (nivel || "").toUpperCase();
        if (n.includes("CRIT")) return "ALTA";
        if (n.includes("MAYOR")) return "MEDIA";
        if (n.includes("MENOR")) return "BAJA";
        return "MEDIA";
    }

    async _abrirGestion(key) {
        const fila = this._combinadosPorKey.get(key);
        if (!fila) return;

        this._gestionContext = { fila };
        document.getElementById("fnc-gestion-error").style.display = "none";
        document.getElementById("fnc-gestion-mensaje").style.display = "none";

        if (!fila.tieneNc) {
            document.getElementById("fnc-gestion-titulo").textContent = "Crear gestión de NC";
            document.getElementById("fnc-gestion-crear").style.display = "block";
            document.getElementById("fnc-gestion-existente").style.display = "none";

            document.getElementById("fnc-gcrear-tipo").value = "INTERNA";
            document.getElementById("fnc-gcrear-origen").value = "AUDITORIA_INTERNA";

            if (fila.fuente === "INSPECCION") {
                const i = fila.inspeccion;
                document.getElementById("fnc-gestion-subtitulo").textContent = `Vinculado a Inspección #${fila.inspeccionId}`;
                document.getElementById("fnc-gcrear-severidad").value = "MEDIA";
                document.getElementById("fnc-gcrear-fecha").value = i.fechaRegistro ? String(i.fechaRegistro).substring(0, 10) : "";
                document.getElementById("fnc-gcrear-proceso").value = i.areaControl || "Inspección Faret";
                document.getElementById("fnc-gcrear-titulo").value = `Inspección NV ${i.nvFaret || fila.inspeccionId}`.trim();
                document.getElementById("fnc-gcrear-descripcion").value =
                    [i.defectos, i.accionCorrectiva].filter(Boolean).join(" - ")
                    || `Registro de inspección (ID ${i.id}, máquina ${i.maquina || "-"})`;
            } else {
                const d = fila.data;
                document.getElementById("fnc-gestion-subtitulo").textContent = `Vinculado a Data #${fila.dataId}`;
                document.getElementById("fnc-gcrear-severidad").value = this._mapNivelASeveridad(d.nivel);
                document.getElementById("fnc-gcrear-fecha").value = d.fechaIngreso ? String(d.fechaIngreso).substring(0, 10) : "";
                document.getElementById("fnc-gcrear-proceso").value = d.tipoPnc || "PNC Data";
                document.getElementById("fnc-gcrear-titulo").value = `PNC Data #${d.id} - ${d.producto || d.cliente || ""}`.trim();
                document.getElementById("fnc-gcrear-descripcion").value =
                    [d.categoriaDefecto, d.observacion].filter(Boolean).join(" - ")
                    || `Registro importado de Data (ID ${d.id}, cliente ${d.cliente || "-"})`;
            }

            document.getElementById("fnc-gestion-modal").style.display = "flex";
            return;
        }

        document.getElementById("fnc-gestion-titulo").textContent = `Gestionar ${fila.nc.codigo || ""}`;
        document.getElementById("fnc-gestion-subtitulo").textContent =
            fila.dataId ? `Vinculado a Data #${fila.dataId}`
            : fila.inspeccionId ? `Vinculado a Inspección #${fila.inspeccionId}`
            : "No conformidad manual (sin vínculo)";
        document.getElementById("fnc-gestion-crear").style.display = "none";
        document.getElementById("fnc-gestion-existente").style.display = "block";

        document.getElementById("fnc-gestion-responsable").value = fila.nc.responsable || "";
        document.getElementById("fnc-gestion-estado").value = fila.nc.estadoGestion || "PENDIENTE";
        document.getElementById("fnc-gestion-fecha-compromiso").value =
            fila.nc.fechaCompromiso ? String(fila.nc.fechaCompromiso).substring(0, 10) : "";
        document.getElementById("fnc-cierre-comentario").value = "";
        document.getElementById("fnc-seguimiento-comentario").value = "";

        this._renderGestionDetalle(fila.nc);

        document.getElementById("fnc-gestion-modal").style.display = "flex";
        await this._cargarSeguimiento(fila.nc.id);
    }

    // ---------- Detalle de la NC dentro de "Gestionar" (tipo/origen/severidad/proceso/título/
    // descripción/norma) — mismo patrón de "Editar" que ya usa el registro de Data en "Ver detalle",
    // reutiliza faret.nc.update (ya wireado, no requiere cambios de API). ----------

    _gdetalleCamposMap() {
        return {
            tipo: { id: "fnc-gdetalle-tipo" },
            origen: { id: "fnc-gdetalle-origen" },
            severidad: { id: "fnc-gdetalle-severidad" },
            fechaDeteccion: { id: "fnc-gdetalle-fecha", tipo: "fecha" },
            proceso: { id: "fnc-gdetalle-proceso" },
            norma: { id: "fnc-gdetalle-norma" },
            titulo: { id: "fnc-gdetalle-titulo" },
            descripcion: { id: "fnc-gdetalle-descripcion" },
        };
    }

    _renderGestionDetalle(nc) {
        const campos = this._gdetalleCamposMap();
        Object.entries(campos).forEach(([campo, { id, tipo }]) => {
            const el = document.getElementById(id);
            if (tipo === "fecha") {
                el.value = nc[campo] ? String(nc[campo]).substring(0, 10) : "";
            } else {
                el.value = nc[campo] ?? "";
            }
        });

        this._modoEdicionGestionDetalle(false);
        document.getElementById("fnc-gdetalle-error").style.display = "none";
    }

    _modoEdicionGestionDetalle(editable) {
        Object.values(this._gdetalleCamposMap()).forEach(({ id }) => {
            document.getElementById(id).disabled = !editable;
        });

        document.getElementById("fnc-gdetalle-editar-btn").style.display = editable ? "none" : "inline-block";
        document.getElementById("fnc-gdetalle-guardar-btn").style.display = editable ? "inline-block" : "none";
        document.getElementById("fnc-gdetalle-cancelar-btn").style.display = editable ? "inline-block" : "none";
    }

    _habilitarEdicionGestionDetalle() {
        this._modoEdicionGestionDetalle(true);
    }

    _cancelarEdicionGestionDetalle() {
        const fila = this._gestionContext?.fila;
        if (fila?.nc) this._renderGestionDetalle(fila.nc);
    }

    async _guardarGestionDetalle() {
        const fila = this._gestionContext?.fila;
        if (!fila || !fila.nc) return;

        const errorEl = document.getElementById("fnc-gdetalle-error");
        errorEl.style.display = "none";

        const campos = this._gdetalleCamposMap();
        const payload = { id: fila.nc.id };
        Object.keys(campos).forEach(campo => {
            const { id, tipo } = campos[campo];
            const raw = document.getElementById(id).value;
            payload[campo] = tipo === "fecha" ? (raw || "") : raw.trim();
        });

        if (!payload.tipo || !payload.origen || !payload.titulo || !payload.descripcion
            || !payload.severidad || !payload.proceso || !payload.fechaDeteccion) {
            errorEl.textContent = "Tipo, origen, severidad, proceso/área, fecha de detección, "
                + "título y descripción son obligatorios";
            errorEl.style.display = "block";
            return;
        }

        // La API (Actualizar) pisa "responsable" sin COALESCE si no viaja en el payload — se manda
        // el valor actual (gestionado en la sección "Gestión") para no perderlo al guardar el detalle.
        payload.responsable = fila.nc.responsable || "";

        const btn = document.getElementById("fnc-gdetalle-guardar-btn");
        btn.disabled = true;
        try {
            const res = await window.PhotinoBridge.send({ action: "faret.nc.update", ...payload });

            if (!res.ok) {
                errorEl.textContent = res.error || "Error al guardar los cambios";
                errorEl.style.display = "block";
                return;
            }

            Object.assign(fila.nc, payload);
            this._combinar();
            this._renderTabla();
            this._modoEdicionGestionDetalle(false);
            this._showGestionMensaje("Detalle de la NC actualizado", true);
        } catch {
            errorEl.textContent = "Error de comunicación con el backend";
            errorEl.style.display = "block";
        } finally {
            btn.disabled = false;
        }
    }

    _cerrarGestion() {
        document.getElementById("fnc-gestion-modal").style.display = "none";
        this._gestionContext = null;
    }

    async _confirmarCrearGestion() {
        const errorEl = document.getElementById("fnc-gestion-error");
        errorEl.style.display = "none";

        const fila = this._gestionContext?.fila;
        if (!fila) return;

        const payload = {
            tipo: document.getElementById("fnc-gcrear-tipo").value,
            origen: document.getElementById("fnc-gcrear-origen").value,
            titulo: document.getElementById("fnc-gcrear-titulo").value.trim(),
            descripcion: document.getElementById("fnc-gcrear-descripcion").value.trim(),
            severidad: document.getElementById("fnc-gcrear-severidad").value,
            proceso: document.getElementById("fnc-gcrear-proceso").value.trim(),
            fechaDeteccion: document.getElementById("fnc-gcrear-fecha").value,
            sistemaOrigen: fila.fuente === "INSPECCION" ? "INSPECCION_FARET" : "DATA_FARET",
            origenId: String(fila.fuente === "INSPECCION" ? fila.inspeccionId : fila.dataId),
        };

        if (!payload.titulo || !payload.descripcion || !payload.proceso || !payload.fechaDeteccion) {
            errorEl.textContent = "Título, descripción, proceso/área y fecha de detección son obligatorios";
            errorEl.style.display = "block";
            return;
        }

        const btn = document.getElementById("fnc-gcrear-confirmar-btn");
        btn.disabled = true;
        try {
            const res = await window.PhotinoBridge.send({ action: "faret.nc.create", ...payload });

            if (!res.ok) {
                errorEl.textContent = res.error || "Error al crear la gestión";
                errorEl.style.display = "block";
                return;
            }

            this._cerrarGestion();
            this._showMensaje("Gestión creada correctamente", true);
            await this._loadLista();
        } catch {
            errorEl.textContent = "Error de comunicación con el backend";
            errorEl.style.display = "block";
        } finally {
            btn.disabled = false;
        }
    }

    async _guardarGestion() {
        const fila = this._gestionContext?.fila;
        if (!fila || !fila.nc) return;

        const errorEl = document.getElementById("fnc-gestion-error");
        errorEl.style.display = "none";

        const payload = {
            id: fila.nc.id,
            responsable: document.getElementById("fnc-gestion-responsable").value.trim(),
            estadoGestion: document.getElementById("fnc-gestion-estado").value,
            fechaCompromiso: document.getElementById("fnc-gestion-fecha-compromiso").value || null,
            actualizadoPor: this._usuarioActual(),
        };

        const btn = document.getElementById("fnc-gestion-guardar-btn");
        btn.disabled = true;
        try {
            const res = await window.PhotinoBridge.send({ action: "faret.nc.gestion.actualizar", ...payload });

            if (!res.ok) {
                errorEl.textContent = res.error || "Error al guardar la gestión";
                errorEl.style.display = "block";
                return;
            }

            this._showGestionMensaje("Gestión actualizada", true);
            await this._loadLista();
        } catch {
            errorEl.textContent = "Error de comunicación con el backend";
            errorEl.style.display = "block";
        } finally {
            btn.disabled = false;
        }
    }

    async _cargarSeguimiento(ncId) {
        const cont = document.getElementById("fnc-seguimiento-lista");
        cont.innerHTML = "Cargando...";

        try {
            const res = await window.PhotinoBridge.send({ action: "faret.nc.seguimiento.list", id: Number(ncId) });
            const items = res.ok && Array.isArray(res.data) ? res.data : [];

            if (!items.length) {
                cont.innerHTML = `<div class="faret-empty">Sin comentarios de seguimiento</div>`;
                return;
            }

            cont.innerHTML = items.map(c => `
                <div class="fnc-seguimiento-item">
                    <div>${c.comentario ?? "-"}</div>
                    <div class="fnc-seguimiento-meta">${c.autor ?? "Sin autor"} · ${c.creadoEn ? new Date(c.creadoEn).toLocaleString("es-CL") : "-"}</div>
                </div>
            `).join("");
        } catch {
            cont.innerHTML = `<div class="faret-error">Error al cargar el seguimiento</div>`;
        }
    }

    async _agregarSeguimiento() {
        const fila = this._gestionContext?.fila;
        if (!fila || !fila.nc) return;

        const comentario = document.getElementById("fnc-seguimiento-comentario").value.trim();
        if (!comentario) return;

        try {
            const res = await window.PhotinoBridge.send({
                action: "faret.nc.seguimiento.crear",
                id: fila.nc.id,
                comentario,
                autor: this._usuarioActual(),
            });

            if (!res.ok) {
                this._showGestionMensaje(res.error || "Error al agregar el comentario", false);
                return;
            }

            document.getElementById("fnc-seguimiento-comentario").value = "";
            await this._cargarSeguimiento(fila.nc.id);
            this._showGestionMensaje("Comentario agregado", true);
        } catch {
            this._showGestionMensaje("Error de comunicación con el backend", false);
        }
    }

    async _cerrarNc() {
        const fila = this._gestionContext?.fila;
        if (!fila || !fila.nc) return;

        if (!confirm("¿Cerrar esta No Conformidad? Quedará marcada como CERRADA.")) return;

        const comentarioCierre = document.getElementById("fnc-cierre-comentario").value.trim();

        try {
            const res = await window.PhotinoBridge.send({
                action: "faret.nc.cerrar",
                id: fila.nc.id,
                cerradoPor: this._usuarioActual(),
                comentarioCierre: comentarioCierre || null,
            });

            if (!res.ok) {
                this._showGestionMensaje(res.error || "Error al cerrar la no conformidad", false);
                return;
            }

            this._cerrarGestion();
            this._showMensaje("No conformidad cerrada", true);
            await this._loadLista();
        } catch {
            this._showGestionMensaje("Error de comunicación con el backend", false);
        }
    }

    async _eliminarFila(key) {
        const fila = this._combinadosPorKey.get(key);
        if (!fila) return;

        const partes = [];
        if (fila.dataId) partes.push(`el registro de Data #${fila.dataId}`);
        if (fila.nc) partes.push(`la gestión de NC ${fila.nc.codigo || ""}`.trim());

        if (!partes.length) return;

        const confirmado = confirm(
            `¿Eliminar esta fila? Se eliminará ${partes.join(" y ")}. Esta acción no se puede deshacer desde la pantalla.`
        );
        if (!confirmado) return;

        try {
            const res = await window.PhotinoBridge.send({
                action: "faret.nc.eliminarFila",
                dataId: fila.dataId || undefined,
                ncId: fila.nc ? fila.nc.id : undefined,
            });

            if (!res.ok) {
                alert(res.error || "Error al eliminar la fila");
                return;
            }

            this._showMensaje("Fila eliminada", true);
            await this._loadLista();
        } catch {
            alert("Error de comunicación con el backend");
        }
    }

    _showGestionMensaje(texto, ok) {
        const el = document.getElementById("fnc-gestion-mensaje");
        el.textContent = texto;
        el.style.display = "block";
        el.style.background = ok ? "#ECFDF5" : "#FEF2F2";
        el.style.color = ok ? "#065F46" : "#991B1B";
        el.style.borderLeftColor = ok ? "#10B981" : "#EF4444";
        setTimeout(() => { el.style.display = "none"; }, 4000);
    }

    // ---------- Analizar (5 Porqués / Ishikawa + Acciones correctivas) ----------

    async _abrirAnalisis(id) {
        const item = this._items.find(i => String(i.id) === String(id));
        if (!item) return;

        this._ncAnalisisId = item.id;
        this._analisisActual = null;
        this._acciones = [];
        this._adjuntos = [];
        this._analisisCerrada = (item.estadoGestion || "").toUpperCase() === "CERRADA";

        document.getElementById("fnc-analisis-titulo").textContent =
            `Análisis y Plan de Acción — ${item.codigo ?? ""}`;
        document.getElementById("fnc-analisis-nc-datos").innerHTML = `
            <div><strong>Código:</strong> ${item.codigo ?? "-"}</div>
            <div><strong>Estado:</strong> ${item.estado ?? "-"}</div>
            <div><strong>Título:</strong> ${item.titulo ?? "-"}</div>
            <div><strong>Severidad:</strong> ${item.severidad ?? "-"}</div>
            <div><strong>Proceso / Área:</strong> ${item.proceso ?? "-"}</div>
            <div><strong>Tipo / Origen:</strong> ${item.tipo ?? "-"} / ${item.origen ?? "-"}</div>
        `;

        document.getElementById("fnc-analisis-error").style.display = "none";
        document.getElementById("fnc-analisis-mensaje").style.display = "none";
        document.getElementById("fnc-analisis-contenido").style.display = "none";
        document.getElementById("fnc-analisis-loading").style.display = "block";
        document.getElementById("fnc-analisis-modal").style.display = "flex";

        await this._cargarAnalisis();
        await this._cargarAcciones();
        await this._cargarAdjuntos();

        document.getElementById("fnc-analisis-loading").style.display = "none";
        document.getElementById("fnc-analisis-contenido").style.display = "block";
    }

    _cerrarAnalisis() {
        document.getElementById("fnc-analisis-modal").style.display = "none";
        this._ncAnalisisId = null;
        this._analisisActual = null;
        this._acciones = [];
        this._adjuntos = [];
        this._analisisCerrada = false;
    }

    async _cargarAnalisis() {
        const errorEl = document.getElementById("fnc-analisis-error");
        errorEl.style.display = "none";

        try {
            const res = await window.PhotinoBridge.send({
                action: "faret.nc.analisis.get",
                id: Number(this._ncAnalisisId),
            });

            if (!res.ok) {
                errorEl.textContent = res.error || "Error al cargar el análisis";
                errorEl.style.display = "block";
                this._analisisActual = null;
            } else {
                // res.data === null → la NC aún no tiene análisis (caso normal, no es error)
                this._analisisActual = res.data || null;
            }
        } catch {
            errorEl.textContent = "Error de comunicación con el backend";
            errorEl.style.display = "block";
            this._analisisActual = null;
        }

        this._renderAnalisisForm();
    }

    _renderAnalisisForm() {
        const a = this._analisisActual;

        document.getElementById("fnc-analisis-metodologia").value = a?.metodologia || "CINCO_PORQUES";
        document.getElementById("fnc-analisis-problema").value = a?.problemaDetectado || "";
        document.getElementById("fnc-analisis-porque1").value = a?.porque1 || "";
        document.getElementById("fnc-analisis-porque2").value = a?.porque2 || "";
        document.getElementById("fnc-analisis-porque3").value = a?.porque3 || "";
        document.getElementById("fnc-analisis-porque4").value = a?.porque4 || "";
        document.getElementById("fnc-analisis-porque5").value = a?.porque5 || "";
        document.getElementById("fnc-analisis-causa-raiz").value = a?.causaRaiz || "";
        document.getElementById("fnc-analisis-conclusion").value = a?.conclusion || "";
    }

    async _guardarAnalisis() {
        const errorEl = document.getElementById("fnc-analisis-error");
        const guardarBtn = document.getElementById("fnc-analisis-guardar-btn");
        errorEl.style.display = "none";

        const payload = {
            metodologia: document.getElementById("fnc-analisis-metodologia").value,
            problemaDetectado: document.getElementById("fnc-analisis-problema").value.trim(),
            porque1: document.getElementById("fnc-analisis-porque1").value.trim(),
            porque2: document.getElementById("fnc-analisis-porque2").value.trim(),
            porque3: document.getElementById("fnc-analisis-porque3").value.trim(),
            porque4: document.getElementById("fnc-analisis-porque4").value.trim(),
            porque5: document.getElementById("fnc-analisis-porque5").value.trim(),
            causaRaiz: document.getElementById("fnc-analisis-causa-raiz").value.trim(),
            conclusion: document.getElementById("fnc-analisis-conclusion").value.trim(),
        };

        if (!payload.problemaDetectado) {
            errorEl.textContent = "El problema detectado es obligatorio";
            errorEl.style.display = "block";
            return;
        }

        if (!confirm("¿Guardar el análisis de causa raíz de esta no conformidad?")) return;

        const existeAnalisis = !!this._analisisActual;
        const usuario = this._usuarioActual();

        guardarBtn.disabled = true;
        try {
            const res = await window.PhotinoBridge.send({
                action: "faret.nc.analisis.guardar",
                id: Number(this._ncAnalisisId),
                existeAnalisis,
                ...(existeAnalisis ? { actualizadoPor: usuario } : { creadoPor: usuario }),
                ...payload,
            });

            if (!res.ok) {
                errorEl.textContent = res.error || "Error al guardar el análisis";
                errorEl.style.display = "block";
                return;
            }

            await this._cargarAnalisis();
            this._showAnalisisMensaje("Análisis guardado correctamente", true);
        } catch {
            errorEl.textContent = "Error de comunicación con el backend";
            errorEl.style.display = "block";
        } finally {
            guardarBtn.disabled = false;
        }
    }

    // ---------- Adjuntos: PDF de análisis de causa raíz + evidencia fotográfica ----------

    async _cargarAdjuntos() {
        const errorEl = document.getElementById("fnc-adjunto-pdf-error");
        errorEl.style.display = "none";

        try {
            const res = await window.PhotinoBridge.send({
                action: "faret.nc.adjuntos.list",
                id: Number(this._ncAnalisisId),
            });
            this._adjuntos = res.ok && Array.isArray(res.data) ? res.data : [];
        } catch {
            this._adjuntos = [];
        }

        this._renderAdjuntoPdf();
        this._renderAdjuntoFotos();
    }

    _renderAdjuntoPdf() {
        const bloque = document.getElementById("fnc-adjunto-pdf-bloque");
        const pdf = this._adjuntos.find(a => a.tipo === "CAUSA_RAIZ_PDF");
        const cerrada = this._analisisCerrada;

        if (!pdf) {
            bloque.innerHTML = cerrada
                ? `<span class="fnc-nota">Sin PDF adjunto.</span>`
                : `<button type="button" class="btn-secondary" id="fnc-adjunto-pdf-adjuntar-btn">Adjuntar PDF</button>`;
        } else {
            bloque.innerHTML = `
                <div class="fnc-adjunto-pdf-fila">
                    <span>${pdf.nombreArchivo}</span>
                    <button type="button" class="btn-secondary" id="fnc-adjunto-pdf-ver-btn">Ver</button>
                    ${cerrada ? "" : `<button type="button" class="btn-secondary" id="fnc-adjunto-pdf-reemplazar-btn">Reemplazar</button>`}
                </div>
            `;
        }

        document.getElementById("fnc-adjunto-pdf-adjuntar-btn")
            ?.addEventListener("click", () => document.getElementById("fnc-adjunto-pdf-input")?.click());
        document.getElementById("fnc-adjunto-pdf-reemplazar-btn")
            ?.addEventListener("click", () => document.getElementById("fnc-adjunto-pdf-input")?.click());
        document.getElementById("fnc-adjunto-pdf-ver-btn")
            ?.addEventListener("click", () => this._verAdjunto(pdf.id));
    }

    _renderAdjuntoFotos() {
        const grid = document.getElementById("fnc-adjunto-fotos-grid");
        const fotos = this._adjuntos.filter(a => a.tipo === "EVIDENCIA_FOTO");
        const cerrada = this._analisisCerrada;

        document.getElementById("fnc-adjunto-fotos-btn").style.display = cerrada ? "none" : "inline-block";

        if (fotos.length === 0) {
            grid.innerHTML = `<span class="fnc-nota">${cerrada ? "Sin fotografías adjuntas." : "Aún no hay fotografías adjuntas."}</span>`;
            return;
        }

        grid.innerHTML = fotos.map(f => `
            <div class="fnc-foto-item" data-adjunto-id="${f.id}" title="${f.nombreArchivo}">
                ${cerrada ? "" : `<button type="button" class="fnc-foto-eliminar" data-adjunto-id="${f.id}">×</button>`}
            </div>
        `).join("");

        fotos.forEach(f => {
            const el = grid.querySelector(`.fnc-foto-item[data-adjunto-id="${f.id}"]`);
            if (!el) return;

            el.addEventListener("click", (ev) => {
                if (ev.target.closest(".fnc-foto-eliminar")) return;
                this._verAdjunto(f.id);
            });

            el.querySelector(".fnc-foto-eliminar")
                ?.addEventListener("click", (ev) => {
                    ev.stopPropagation();
                    this._eliminarAdjunto(f.id);
                });

            // Miniatura cargada de forma diferida (no bloquea el render de la grilla).
            window.PhotinoBridge.send({
                action: "faret.nc.adjuntos.abrir",
                id: Number(this._ncAnalisisId),
                adjuntoId: f.id,
            }).then(res => {
                if (res?.ok && res.data) {
                    const img = document.createElement("img");
                    img.src = `data:${res.data.tipoMime};base64,${res.data.contenidoBase64}`;
                    img.alt = f.nombreArchivo;
                    el.prepend(img);
                }
            }).catch(() => {});
        });
    }

    _leerArchivoBase64(file) {
        return new Promise((resolve, reject) => {
            const reader = new FileReader();
            reader.onload = () => resolve(reader.result.split(",")[1] || "");
            reader.onerror = () => reject(new Error("No se pudo leer el archivo"));
            reader.readAsDataURL(file);
        });
    }

    async _subirPdfSeleccionado(e) {
        const input = e.target;
        const file = input.files?.[0];
        input.value = "";
        if (!file) return;

        const errorEl = document.getElementById("fnc-adjunto-pdf-error");
        errorEl.style.display = "none";

        if (file.type !== "application/pdf") {
            errorEl.textContent = "Solo se permite un archivo PDF";
            errorEl.style.display = "block";
            return;
        }
        if (file.size > 10 * 1024 * 1024) {
            errorEl.textContent = "El PDF excede el tamaño máximo de 10 MB";
            errorEl.style.display = "block";
            return;
        }

        try {
            const contenidoBase64 = await this._leerArchivoBase64(file);
            const res = await window.PhotinoBridge.send({
                action: "faret.nc.adjuntos.subir",
                id: Number(this._ncAnalisisId),
                tipo: "CAUSA_RAIZ_PDF",
                nombreArchivo: file.name,
                tipoMime: file.type,
                contenidoBase64,
                subidoPor: this._usuarioActual(),
            });

            if (!res.ok) {
                errorEl.textContent = res.error || "Error al subir el PDF";
                errorEl.style.display = "block";
                return;
            }

            await this._cargarAdjuntos();
        } catch (err) {
            errorEl.textContent = err.message || "Error al subir el PDF";
            errorEl.style.display = "block";
        }
    }

    async _subirFotosSeleccionadas(e) {
        const input = e.target;
        const files = Array.from(input.files || []);
        input.value = "";
        if (files.length === 0) return;

        const errorEl = document.getElementById("fnc-adjunto-fotos-error");
        errorEl.style.display = "none";

        const activas = this._adjuntos.filter(a => a.tipo === "EVIDENCIA_FOTO").length;
        if (activas + files.length > 10) {
            errorEl.textContent = `Máximo 10 fotografías por no conformidad (ya hay ${activas})`;
            errorEl.style.display = "block";
            return;
        }

        const tiposValidos = ["image/jpeg", "image/png"];
        for (const file of files) {
            if (!tiposValidos.includes(file.type)) {
                errorEl.textContent = `"${file.name}" no es JPG/PNG`;
                errorEl.style.display = "block";
                return;
            }
            if (file.size > 5 * 1024 * 1024) {
                errorEl.textContent = `"${file.name}" excede el tamaño máximo de 5 MB`;
                errorEl.style.display = "block";
                return;
            }
        }

        try {
            for (const file of files) {
                const contenidoBase64 = await this._leerArchivoBase64(file);
                const res = await window.PhotinoBridge.send({
                    action: "faret.nc.adjuntos.subir",
                    id: Number(this._ncAnalisisId),
                    tipo: "EVIDENCIA_FOTO",
                    nombreArchivo: file.name,
                    tipoMime: file.type,
                    contenidoBase64,
                    subidoPor: this._usuarioActual(),
                });
                if (!res.ok) {
                    errorEl.textContent = res.error || `Error al subir "${file.name}"`;
                    errorEl.style.display = "block";
                    break;
                }
            }
        } finally {
            await this._cargarAdjuntos();
        }
    }

    async _verAdjunto(adjuntoId) {
        try {
            const res = await window.PhotinoBridge.send({
                action: "faret.nc.adjuntos.abrir",
                id: Number(this._ncAnalisisId),
                adjuntoId,
            });
            if (!res.ok) throw new Error(res.error || "Error al abrir el adjunto");

            this._mostrarAdjuntoVisor(res.data.nombreArchivo, res.data.tipoMime, res.data.contenidoBase64);
        } catch (err) {
            alert(err.message);
        }
    }

    async _eliminarAdjunto(adjuntoId) {
        if (!confirm("¿Eliminar este adjunto?")) return;

        try {
            const res = await window.PhotinoBridge.send({
                action: "faret.nc.adjuntos.eliminar",
                id: Number(this._ncAnalisisId),
                adjuntoId,
            });
            if (!res.ok) throw new Error(res.error || "Error al eliminar el adjunto");

            await this._cargarAdjuntos();
        } catch (err) {
            alert(err.message);
        }
    }

    // Mismo patrón visual que mostrarFoto (Recepción-Calidad) / _mostrarAdjunto (Control
    // Documental): overlay oscuro + tarjeta blanca, anclado a document.body. PDF se previsualiza
    // en un <iframe> (WebView2 lo renderiza nativo), imagen en <img>.
    _mostrarAdjuntoVisor(nombreArchivo, tipoMime, contenidoBase64) {
        const existente = document.getElementById("fncModalAdjunto");
        if (existente) existente.remove();

        const dataUrl = `data:${tipoMime};base64,${contenidoBase64}`;
        const visor = tipoMime.startsWith("image/")
            ? `<img src="${dataUrl}" alt="${nombreArchivo}" style="display:block;max-width:100%;max-height:75vh;object-fit:contain;border-radius:8px;">`
            : `<iframe src="${dataUrl}" style="width:80vw;height:75vh;border:0;"></iframe>`;

        const modal = document.createElement("div");
        modal.id = "fncModalAdjunto";
        modal.style.cssText = "position:fixed;left:0;top:0;width:100%;height:100%;background:rgba(15,23,42,0.75);z-index:9999;display:flex;align-items:center;justify-content:center;padding:24px;";
        modal.innerHTML = `
            <div style="background:#fff;border-radius:12px;max-width:90%;max-height:90%;padding:16px;box-shadow:0 20px 60px rgba(0,0,0,0.35);position:relative;">
                <div style="display:flex;justify-content:space-between;align-items:center;gap:12px;margin-bottom:12px;">
                    <strong>${nombreArchivo}</strong>
                    <button id="fncBtnCerrarAdjunto" class="btn-secondary" type="button">Cerrar</button>
                </div>
                ${visor}
            </div>
        `;
        document.body.appendChild(modal);
        document.getElementById("fncBtnCerrarAdjunto").addEventListener("click", () => modal.remove());
    }

    async _cargarAcciones() {
        const loadingEl = document.getElementById("fnc-acciones-loading");
        loadingEl.style.display = "block";

        try {
            const res = await window.PhotinoBridge.send({
                action: "faret.nc.acciones.list",
                id: Number(this._ncAnalisisId),
            });

            this._acciones = res.ok && Array.isArray(res.data) ? res.data : [];
        } catch {
            this._acciones = [];
        } finally {
            loadingEl.style.display = "none";
        }

        this._renderAcciones();
    }

    _renderAcciones() {
        const tbody = document.getElementById("fnc-acciones-tbody");

        if (!this._acciones.length) {
            tbody.innerHTML = `<tr><td colspan="7" class="faret-empty">Sin acciones correctivas</td></tr>`;
            return;
        }

        const estados = ["PENDIENTE", "EN_PROCESO", "COMPLETADA", "CANCELADA"];

        tbody.innerHTML = this._acciones.map(a => `
            <tr>
                <td>${a.descripcion ?? "-"}</td>
                <td>${a.responsable ?? "-"}</td>
                <td>${a.fechaLimite ? a.fechaLimite.substring(0, 10) : "-"}</td>
                <td>${a.prioridad ?? "-"}</td>
                <td>
                    <select class="fnc-accion-estado-select" data-id="${a.id}">
                        ${estados.map(e => `<option value="${e}" ${e === a.estado ? "selected" : ""}>${e}</option>`).join("")}
                    </select>
                </td>
                <td>${a.integracionTareasEstado ?? "-"}</td>
                <td>
                    <button class="btn-secondary fnc-accion-guardar-estado-btn" data-id="${a.id}">Guardar</button>
                </td>
            </tr>
        `).join("");

        tbody.querySelectorAll(".fnc-accion-guardar-estado-btn").forEach(btn =>
            btn.addEventListener("click", () => this._actualizarEstadoAccion(btn.dataset.id)));
    }

    async _agregarAccion() {
        const errorEl = document.getElementById("fnc-accion-form-error");
        const agregarBtn = document.getElementById("fnc-accion-agregar-btn");
        errorEl.style.display = "none";

        const payload = {
            descripcion: document.getElementById("fnc-accion-descripcion").value.trim(),
            responsable: document.getElementById("fnc-accion-responsable").value.trim(),
            fechaLimite: document.getElementById("fnc-accion-fecha-limite").value,
            prioridad: document.getElementById("fnc-accion-prioridad").value || null,
        };

        if (!payload.descripcion || !payload.responsable || !payload.fechaLimite) {
            errorEl.textContent = "Descripción, responsable y fecha límite son obligatorios";
            errorEl.style.display = "block";
            return;
        }

        if (!confirm("¿Agregar esta acción correctiva a la no conformidad?")) return;

        agregarBtn.disabled = true;
        try {
            const res = await window.PhotinoBridge.send({
                action: "faret.nc.acciones.crear",
                id: Number(this._ncAnalisisId),
                analisisId: this._analisisActual?.id ?? null,
                creadoPor: this._usuarioActual(),
                ...payload,
            });

            if (!res.ok) {
                errorEl.textContent = res.error || "Error al agregar la acción";
                errorEl.style.display = "block";
                return;
            }

            document.getElementById("fnc-accion-descripcion").value = "";
            document.getElementById("fnc-accion-responsable").value = "";
            document.getElementById("fnc-accion-fecha-limite").value = "";
            document.getElementById("fnc-accion-prioridad").value = "";

            await this._cargarAcciones();
            this._showAnalisisMensaje("Acción correctiva agregada", true);
        } catch {
            errorEl.textContent = "Error de comunicación con el backend";
            errorEl.style.display = "block";
        } finally {
            agregarBtn.disabled = false;
        }
    }

    async _actualizarEstadoAccion(accionId) {
        const accion = this._acciones.find(a => String(a.id) === String(accionId));
        if (!accion) return;

        const select = document.querySelector(`.fnc-accion-estado-select[data-id="${accionId}"]`);
        const nuevoEstado = select ? select.value : accion.estado;

        if (!confirm(`¿Cambiar el estado de la acción a "${nuevoEstado}"?`)) return;

        try {
            const res = await window.PhotinoBridge.send({
                action: "faret.nc.acciones.actualizar",
                accionId: Number(accionId),
                descripcion: accion.descripcion,
                responsable: accion.responsable,
                fechaLimite: accion.fechaLimite ? accion.fechaLimite.substring(0, 10) : "",
                prioridad: accion.prioridad || null,
                estado: nuevoEstado,
                actualizadoPor: this._usuarioActual(),
            });

            if (!res.ok) {
                this._showAnalisisMensaje(res.error || "Error al actualizar la acción", false);
                return;
            }

            await this._cargarAcciones();
            this._showAnalisisMensaje("Acción correctiva actualizada", true);
        } catch {
            this._showAnalisisMensaje("Error de comunicación con el backend", false);
        }
    }

    _showAnalisisMensaje(texto, ok) {
        const el = document.getElementById("fnc-analisis-mensaje");
        el.textContent = texto;
        el.style.display = "block";
        el.style.background = ok ? "#ECFDF5" : "#FEF2F2";
        el.style.color = ok ? "#065F46" : "#991B1B";
        el.style.borderLeftColor = ok ? "#10B981" : "#EF4444";
        setTimeout(() => { el.style.display = "none"; }, 4000);
    }

    // ---------- Presentación (badges) ----------

    _badge(texto, color) {
        const valor = texto ?? "-";
        return `<span class="fnc-badge" style="background:${color}1F;color:${color};">${valor}</span>`;
    }

    _colorSeveridad(valor) {
        const s = (valor || "").toUpperCase();
        if (s === "ALTA" || s.includes("CRIT")) return "#DC2626";
        if (s === "MEDIA" || s.includes("MAYOR")) return "#D97706";
        if (s === "BAJA" || s.includes("MENOR")) return "#059669";
        return "#64748B";
    }

    _labelEstadoGestion(estado) {
        const map = {
            SIN_GESTION: "Sin gestión",
            PENDIENTE: "Pendiente",
            ASIGNADA: "Asignada",
            EN_GESTION: "En gestión",
            CERRADA: "Cerrada",
        };
        return map[estado] || estado || "-";
    }

    _colorEstadoGestion(estado) {
        switch (estado) {
            case "CERRADA": return "#059669";
            case "EN_GESTION": return "#2563EB";
            case "ASIGNADA": return "#D97706";
            case "SIN_GESTION": return "#94A3B8";
            default: return "#64748B"; // PENDIENTE
        }
    }

    _labelFuente(fuente) {
        switch (fuente) {
            case "DATA": return "Data";
            case "INSPECCION": return "Inspección";
            default: return "Manual";
        }
    }

    _colorFuente(fuente) {
        switch (fuente) {
            case "DATA": return "#2563EB";
            case "INSPECCION": return "#7C3AED";
            default: return "#64748B"; // MANUAL
        }
    }

    // Exporta siempre el conjunto ya filtrado completo (todas las páginas), no solo la página
    // visible: con filtros activos exporta lo filtrado; sin filtros, exporta todo lo combinado.
    _exportar() {
        const items = this._filtrarItems();
        const tabla = this._construirTablaTemp(items);
        window.ExcelExporter.exportTable({
            tableSelector: "#fnc-tabla-export-temp",
            fileName: `faret_no_conformidades_${Date.now()}.xlsx`,
            sheetName: "No Conformidades",
            title: "QCC Faret - No Conformidades"
        });
        tabla.remove();
    }

    _imprimir() {
        const items = this._filtrarItems();
        const tabla = this._construirTablaTemp(items);
        window.PrintExporter.printTable({
            tableSelector: "#fnc-tabla-export-temp",
            titulo: "No Conformidades",
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
        if (f.estadoGestion) partes.push(`Estado gestión: ${this._labelEstadoGestion(f.estadoGestion)}`);
        if (f.area) partes.push(`Área: ${f.area}`);
        if (f.fuente) partes.push(`Fuente: ${this._labelFuente(f.fuente)}`);
        if (f.fechaDesde) partes.push(`Fecha ingreso desde: ${f.fechaDesde}`);
        if (f.fechaHasta) partes.push(`Fecha ingreso hasta: ${f.fechaHasta}`);
        return partes.length ? partes.join(" · ") : "Sin filtros — histórico completo";
    }

    _construirTablaTemp(items) {
        const colsOpcionales = this._columnasOpcionalesVisibles();

        const tabla = document.createElement("table");
        tabla.id = "fnc-tabla-export-temp";
        tabla.style.position = "absolute";
        tabla.style.left = "-99999px";
        tabla.style.top = "0";

        tabla.innerHTML = `
            <thead>
                <tr>
                    <th>Código NC / ID Data</th>
                    <th>Fecha ingreso</th>
                    <th>Fecha salida</th>
                    <th>NP/NV</th>
                    <th>Cliente</th>
                    <th>Código producto</th>
                    <th>Producto</th>
                    <th>Tipo PNC</th>
                    <th>Categoría defecto</th>
                    <th>Nivel / Severidad</th>
                    <th>Estado gestión</th>
                    <th>Responsable</th>
                    ${colsOpcionales.map(col => `<th>${col.label}</th>`).join("")}
                    <th>Fuente</th>
                </tr>
            </thead>
            <tbody>
                ${items.map(fila => `
                    <tr>
                        <td>${fila.codigo}</td>
                        <td>${window.DateUtils.formatear(fila.fechaIngreso)}</td>
                        <td>${window.DateUtils.formatear(fila.fechaSalida)}</td>
                        <td>${fila.npNv}</td>
                        <td>${fila.cliente}</td>
                        <td>${fila.codigoProducto}</td>
                        <td>${fila.producto}</td>
                        <td>${fila.tipoPnc}</td>
                        <td>${fila.categoriaDefecto}</td>
                        <td>${fila.nivelSeveridad}</td>
                        <td>${this._labelEstadoGestion(fila.estadoGestion)}</td>
                        <td>${fila.responsable}</td>
                        ${colsOpcionales.map(col => `<td>${this._formatearColumnaOpcional(fila, col)}</td>`).join("")}
                        <td>${this._labelFuente(fila.fuente)}</td>
                    </tr>
                `).join("")}
            </tbody>
        `;

        document.body.appendChild(tabla);
        return tabla;
    }
};
