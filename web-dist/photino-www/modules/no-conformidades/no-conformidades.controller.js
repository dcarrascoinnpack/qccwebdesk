window.NoConformidadesController = class NoConformidadesController {

    init() {
        console.log("NoConformidadesController iniciado");

        this._page = 1;
        this._pageSize = 20;
        this._pages = 1;
        this._total = 0;
        this._filtrosOpciones = { clientes: [], tiposPnc: [], responsables: [], categoriasDefecto: [], areas: [], maquinas: [], operadores: [], supervisores: [], revisadoPor: [] };
        this._editingId = null;
        this._detalleActual = null;
        this._gestionId = null;
        this._analisisNcId = null;
        this._analisisActual = null;
        this._acciones = [];
        this._statsCharts = [];
        this._itemsCompletos = [];

        // Columnas opcionales del listado (campos que ya vienen en cada fila de "noConformidades.list"
        // pero no se muestran por defecto en la tabla base).
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

        document.getElementById("ncq-nuevo-btn")?.addEventListener("click", () => this._abrirNuevo());
        document.getElementById("ncq-exportar-btn")?.addEventListener("click", () => this._exportar());
        document.getElementById("ncq-imprimir-btn")?.addEventListener("click", () => this._imprimir());
        document.getElementById("ncq-imprimir-reporte-btn")?.addEventListener("click", () => this._imprimirReporteEstadistico());

        document.getElementById("ncq-columnas-btn")?.addEventListener("click", (e) => {
            e.stopPropagation();
            const dd = document.getElementById("ncq-columnas-dropdown");
            if (dd) dd.style.display = dd.style.display === "none" ? "block" : "none";
        });

        document.addEventListener("click", (e) => {
            const wrap = document.getElementById("ncq-columnas-btn")?.closest(".ncq-combo-wrap");
            if (wrap && !wrap.contains(e.target)) {
                const dd = document.getElementById("ncq-columnas-dropdown");
                if (dd) dd.style.display = "none";
            }
        });

        this._renderColumnasDropdown();
        document.getElementById("ncq-filtrar-btn")?.addEventListener("click", () => { this._page = 1; this._loadLista(); });
        document.getElementById("ncq-limpiar-btn")?.addEventListener("click", () => this._limpiarFiltros());

        document.getElementById("ncq-form-cerrar-btn")?.addEventListener("click", () => this._cerrarForm());
        document.getElementById("ncq-form-cancelar-btn")?.addEventListener("click", () => this._cancelarEdicion());
        document.getElementById("ncq-form-editar-btn")?.addEventListener("click", () => this._habilitarEdicion());
        document.getElementById("ncq-f-guardar-btn")?.addEventListener("click", () => this._guardarForm());

        ["ncq-f-cant-rechazada", "ncq-f-cant-recuperada"].forEach(id =>
            document.getElementById(id)?.addEventListener("input", () => this._recalcularPctRecup()));

        document.getElementById("ncq-f-tipo-pnc")?.addEventListener("change", () =>
            this._actualizarVisibilidadDisposicion());

        document.getElementById("ncq-gestion-cerrar-btn")?.addEventListener("click", () => this._cerrarGestion());
        document.getElementById("ncq-gestion-guardar-btn")?.addEventListener("click", () => this._guardarGestion());
        document.getElementById("ncq-seguimiento-agregar-btn")?.addEventListener("click", () => this._agregarSeguimiento());
        document.getElementById("ncq-cerrar-nc-btn")?.addEventListener("click", () => this._cerrarNc());

        document.getElementById("ncq-analisis-cerrar-btn")?.addEventListener("click", () => this._cerrarAnalisis());
        document.getElementById("ncq-analisis-guardar-btn")?.addEventListener("click", () => this._guardarAnalisis());

        document.getElementById("ncq-adjunto-pdf-input")?.addEventListener("change", (e) => this._subirPdfSeleccionado(e));
        document.getElementById("ncq-adjunto-fotos-btn")?.addEventListener("click", () => document.getElementById("ncq-adjunto-fotos-input")?.click());
        document.getElementById("ncq-adjunto-fotos-input")?.addEventListener("change", (e) => this._subirFotosSeleccionadas(e));
        document.getElementById("ncq-accion-agregar-btn")?.addEventListener("click", () => this._agregarAccion());

        document.getElementById("ncq-paginacion")?.addEventListener("click", (e) => {
            const btn = e.target.closest("[data-ncq-page]");
            if (!btn || btn.disabled) return;
            this._irPagina(Number(btn.dataset.ncqPage));
        });

        this._attachCatalogosCombos();

        this._cargarFiltrosOpciones();
        this._loadLista();
    }

    // ---------- Catálogos administrables (Cliente/Categoría defecto/Tipo de falla/Supervisor/
    // Revisado por/Área/Familia de producto/Nivel/Impacto) ----------
    // Reemplaza el <datalist> nativo (solo sugería, sin persistir) por window.CatalogCombo
    // (shared/utils.js, ya usado igual en faret-nc): seleccionar existente, buscar, o crear uno
    // nuevo que queda guardado en cat_nc_* y disponible para todos los usuarios desde el próximo
    // focus. Máquina y Operador NO están acá — decisión explícita: siguen sugiriendo desde las
    // tablas reales `maquinas` (con QR) y `usuarios` (login real), sin "crear nuevo", para no
    // divergir de esos registros reales. Un solo formulario (ncq-f-*, sin split nuevo/registro
    // como Faret), así que se engancha una sola vez desde init().
    _catalogosPlanosConfig() {
        return [
            { campo: "cliente", cacheKey: "ncq-cat-cliente", listAction: "noConformidades.catalogos.clientes.list", crearAction: "noConformidades.catalogos.clientes.crear" },
            { campo: "categoria-defecto", cacheKey: "ncq-cat-categoria-defecto", listAction: "noConformidades.catalogos.categoriasDefecto.list", crearAction: "noConformidades.catalogos.categoriasDefecto.crear" },
            { campo: "tipo-falla", cacheKey: "ncq-cat-tipo-falla", listAction: "noConformidades.catalogos.tiposFalla.list", crearAction: "noConformidades.catalogos.tiposFalla.crear" },
            { campo: "supervisor", cacheKey: "ncq-cat-supervisor", listAction: "noConformidades.catalogos.supervisores.list", crearAction: "noConformidades.catalogos.supervisores.crear" },
            { campo: "revisado-por", cacheKey: "ncq-cat-revisado-por", listAction: "noConformidades.catalogos.revisores.list", crearAction: "noConformidades.catalogos.revisores.crear" },
            { campo: "area", cacheKey: "ncq-cat-area", listAction: "noConformidades.catalogos.areas.list", crearAction: "noConformidades.catalogos.areas.crear" },
            { campo: "familia-producto", cacheKey: "ncq-cat-familia-producto", listAction: "noConformidades.catalogos.familiasProducto.list", crearAction: "noConformidades.catalogos.familiasProducto.crear" },
            { campo: "nivel", cacheKey: "ncq-cat-nivel", listAction: "noConformidades.catalogos.niveles.list", crearAction: "noConformidades.catalogos.niveles.crear" },
            { campo: "impacto", cacheKey: "ncq-cat-impacto", listAction: "noConformidades.catalogos.impactos.list", crearAction: "noConformidades.catalogos.impactos.crear" },
        ];
    }

    async _catalogoObtener(action) {
        const res = await window.PhotinoBridge.send({ action });
        return res.ok && Array.isArray(res.data) ? res.data : [];
    }

    async _catalogoCrear(action, nombre) {
        const res = await window.PhotinoBridge.send({ action, nombre, creadoPor: this._usuarioActual() });
        if (!res.ok) {
            this._showMensaje(res.error || "No se pudo crear el valor de catálogo", false);
            return null;
        }
        return res.data;
    }

    // No busca por posición en el DOM (input.nextElementSibling): CatalogCombo.attach() reparenta
    // el dropdown a document.body para no quedar cortado por el overflow:auto del modal, así que
    // ya no es un hermano del input después del primer enganche — se cachea la referencia en el
    // propio input.
    _dropdownFor(input) {
        if (input._catalogComboDropdownEl) return input._catalogComboDropdownEl;
        const dd = document.createElement("div");
        dd.className = "ncq-combo-dropdown";
        dd.style.display = "none";
        input.insertAdjacentElement("afterend", dd);
        input._catalogComboDropdownEl = dd;
        return dd;
    }

    _attachCatalogosCombos() {
        this._catalogosPlanosConfig().forEach(cfg => {
            const input = document.getElementById(`ncq-f-${cfg.campo}`);
            if (!input) return;
            window.CatalogCombo.attach(input, this._dropdownFor(input), {
                cacheKey: cfg.cacheKey,
                obtenerOpciones: () => this._catalogoObtener(cfg.listAction),
                crear: nombre => this._catalogoCrear(cfg.crearAction, nombre),
            });
        });
    }

    destroy() {
        console.log("NoConformidadesController destruido");
        this._destroyStatsCharts();
    }

    _usuarioActual() {
        return sessionStorage.getItem("nombreUsuario") || sessionStorage.getItem("codigoUsuario") || "";
    }

    // ---------- Filtros ----------

    async _cargarFiltrosOpciones() {
        try {
            const res = await window.PhotinoBridge.send({ action: "noConformidades.filtrosOpciones" });
            if (!res.ok) return;
            this._filtrosOpciones = res.data;

            // Tipo PNC además siembra el mismo catálogo fijo del formulario "Nueva NC" (Cuarentena/
            // Rechazo/Reclamo/Interna) para que esas opciones existan en el filtro aunque todavía
            // no haya ningún registro real con ese valor.
            const TIPO_PNC_BASE = ["Cuarentena", "Rechazo", "Reclamo", "Interna"];
            const mapa = {
                "ncq-filtro-cliente": "clientes",
                "ncq-filtro-tipo-pnc": "tiposPnc",
                "ncq-filtro-area": "areas",
            };
            Object.entries(mapa).forEach(([selectId, campo]) => {
                const select = document.getElementById(selectId);
                if (!select) return;
                const valorActual = select.value;
                const base = campo === "tiposPnc" ? TIPO_PNC_BASE : [];
                const valores = new Set([...base, ...(this._filtrosOpciones[campo] || [])]);
                select.innerHTML = `<option value="">Todos</option>` +
                    [...valores].sort().map(v => `<option value="${v}">${v}</option>`).join("");
                if (valorActual) select.value = valorActual;
            });

        } catch { }

        // Máquina y Operador no salen de esta tabla (recién creada, vacía al principio) sino de
        // catálogos ya existentes en INNPACK: el listado real de máquinas activas (mismo que usa
        // el módulo "Máquinas y Procesos") y los usuarios que aparecen en Inspecciones (mismo
        // catálogo que ya usa el filtro "Inspector" de Dashboard/Registros Producción).
        this._cargarDatalistMaquinas();
        this._cargarDatalistOperadores();
    }

    async _cargarDatalistMaquinas() {
        try {
            const res = await window.PhotinoBridge.send({ action: "maquinasSeguimiento.obtenerResumen", data: {} });
            const dl = document.getElementById("ncq-dl-maquina");
            if (!dl || !res.ok) return;
            const nombres = (res.data.maquinas || []).map(m => m.nombre).filter(Boolean);
            dl.innerHTML = nombres.map(v => `<option value="${v}"></option>`).join("");
        } catch { }
    }

    async _cargarDatalistOperadores() {
        try {
            const res = await window.PhotinoBridge.send({ action: "dashboard.obtenerFiltros" });
            const dl = document.getElementById("ncq-dl-operador");
            if (!dl || !res.ok) return;
            const nombres = (res.data.usuarios || []).map(u => u.nombre).filter(Boolean);
            dl.innerHTML = nombres.map(v => `<option value="${v}"></option>`).join("");
        } catch { }
    }

    _getFiltros() {
        return {
            cliente: document.getElementById("ncq-filtro-cliente")?.value || "",
            tipoPnc: document.getElementById("ncq-filtro-tipo-pnc")?.value || "",
            nivel: document.getElementById("ncq-filtro-nivel")?.value || "",
            estadoGestion: document.getElementById("ncq-filtro-estado-gestion")?.value || "",
            area: document.getElementById("ncq-filtro-area")?.value || "",
            fechaDesde: document.getElementById("ncq-filtro-fecha-desde")?.value || "",
            fechaHasta: document.getElementById("ncq-filtro-fecha-hasta")?.value || "",
        };
    }

    _limpiarFiltros() {
        ["ncq-filtro-cliente", "ncq-filtro-tipo-pnc", "ncq-filtro-nivel", "ncq-filtro-estado-gestion", "ncq-filtro-area"]
            .forEach(id => { const el = document.getElementById(id); if (el) el.value = ""; });
        ["ncq-filtro-fecha-desde", "ncq-filtro-fecha-hasta"].forEach(id => { document.getElementById(id).value = ""; });
        this._page = 1;
        this._loadLista();
    }

    // ---------- Listado ----------

    async _loadLista() {
        const tbody = document.getElementById("ncq-tbody");
        tbody.innerHTML = `<tr><td colspan="${this._totalColumnasTabla()}">Cargando...</td></tr>`;

        const filtros = this._getFiltros();

        try {
            const [listRes, resumenRes, itemsCompletos] = await Promise.all([
                window.PhotinoBridge.send({ action: "noConformidades.list", page: this._page, pageSize: this._pageSize, ...filtros }),
                window.PhotinoBridge.send({ action: "noConformidades.resumen", ...filtros }),
                this._obtenerItemsFiltrados(),
            ]);

            if (!listRes.ok) {
                tbody.innerHTML = `<tr><td colspan="${this._totalColumnasTabla()}">${listRes.error || "Error al cargar"}</td></tr>`;
                return;
            }

            this._items = listRes.data.items || [];
            this._total = listRes.data.total || 0;
            this._pages = listRes.data.pages || 1;
            this._page = listRes.data.page || 1;
            this._itemsCompletos = Array.isArray(itemsCompletos) ? itemsCompletos : [];

            if (resumenRes.ok) this._renderResumen(resumenRes.data);
            this._renderIndicadores(this._calcularIndicadores(this._itemsCompletos));
            this._renderTabla();
            this._renderPaginacion();
        } catch {
            tbody.innerHTML = `<tr><td colspan="${this._totalColumnasTabla()}">Error de comunicación con el backend</td></tr>`;
        }
    }

    _renderResumen(r) {
        document.getElementById("ncq-total").textContent = r.total ?? 0;
        document.getElementById("ncq-abiertas").textContent = r.abiertas ?? 0;
        document.getElementById("ncq-cerradas").textContent = r.cerradas ?? 0;
        document.getElementById("ncq-criticas").textContent = r.criticas ?? 0;
    }

    // ---------- Indicadores estadísticos ----------
    // Se calculan sobre `this._itemsCompletos` (todo el universo que cumple los filtros activos,
    // vía _obtenerItemsFiltrados() — mismo mecanismo ya usado por Exportar/Imprimir, sin fetch
    // nuevo ni endpoint nuevo). A diferencia de Faret, acá no hay que fusionar Data+NC: cada fila
    // de `no_conformidades` ya trae directo tipoPnc/familiaProducto/area/categoriaDefecto/
    // cantRecuperada/cantDestruida/fechaIngreso, así que no se filtra por "fuente".

    _calcularIndicadores(items) {
        const porTipo = tipo => items.filter(nc => (nc.tipoPnc || "").trim() === tipo);

        // `cantidadTotalUnidades` usa cantRechazada (la cantidad que efectivamente entró en el
        // estado del tipo PNC, ej. cuarentena) — distinto de recuperados/destruidos, que ahora
        // solo se calculan mes a mes para el gráfico de evolución (_agruparPorMes), no como total.
        const resumenTipo = tipo => {
            const rows = porTipo(tipo);
            return {
                total: rows.length,
                cantidadTotalUnidades: rows.reduce((s, nc) => s + (Number(nc.cantRechazada) || 0), 0),
                evolucionMensual: this._agruparPorMes(rows),
            };
        };

        const agruparCategoria = campo => {
            const mapa = new Map();
            items.forEach(nc => {
                const valor = (nc[campo] || "").toString().trim();
                if (!valor) return;
                mapa.set(valor, (mapa.get(valor) || 0) + 1);
            });
            return [...mapa.entries()]
                .map(([categoria, total]) => ({ categoria, total }))
                .sort((a, b) => b.total - a.total);
        };

        // Conteo de valores DISTINTOS (no de NC) — Set sobre el mismo `items` ya filtrado.
        const contarDistintos = campo => new Set(
            items.map(nc => (nc[campo] || "").toString().trim()).filter(Boolean)
        ).size;

        return {
            cuarentenas: resumenTipo("Cuarentena"),
            rechazosCliente: resumenTipo("Rechazo Cliente"),
            // "Rechazo" (genérico) y "Rechazo Cliente" son valores de catálogo distintos —
            // "Rechazados totales" combina ambos, a diferencia de "Rechazos cliente — total" de
            // arriba, que solo cuenta el tipo "Rechazo Cliente".
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
            pareto: this._calcularPareto(items),
        };
    }

    _agruparPorMes(rows) {
        const mapa = new Map();
        rows.forEach(nc => {
            if (!nc.fechaIngreso) return;
            const mes = String(nc.fechaIngreso).substring(0, 7); // yyyy-MM
            if (!mapa.has(mes)) mapa.set(mes, { recuperados: 0, destruidos: 0 });
            const acc = mapa.get(mes);
            acc.recuperados += Number(nc.cantRecuperada) || 0;
            acc.destruidos += Number(nc.cantDestruida) || 0;
        });
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

    // Mismo cálculo que el Pareto de Faret: orden descendente por frecuencia, % acumulado con 2
    // decimales.
    _calcularPareto(rows) {
        const mapa = new Map();
        rows.forEach(nc => {
            const valor = (nc.categoriaDefecto || "").toString().trim();
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

        this._setText("ncq-stat-cuarentenas-total", ind.cuarentenas.total);
        this._setText("ncq-stat-cuarentenas-cant-unidades", ind.cuarentenas.cantidadTotalUnidades);

        this._setText("ncq-stat-rechazos-total", ind.rechazosCliente.total);
        this._setText("ncq-stat-rechazos-totales-general", ind.rechazosTotales);

        this._setText("ncq-stat-reclamos-total", ind.totalReclamos);

        this._setText("ncq-stat-maquinas-total", ind.maquinasInvolucradas);
        this._setText("ncq-stat-operadores-total", ind.operadoresInvolucrados);
        this._setText("ncq-stat-clientes-total", ind.clientesInvolucrados);

        this._renderEvolucionMensual(
            "ncq-chart-cuarentenas", "ncq-nota-cuarentenas",
            ind.cuarentenas.evolucionMensual, "Cuarentenas — evolución mensual"
        );
        this._renderEvolucionMensual(
            "ncq-chart-rechazos", "ncq-nota-rechazos",
            ind.rechazosCliente.evolucionMensual, "Rechazos de cliente — evolución mensual"
        );

        this._chartBarHorizontalStats(
            "ncq-chart-familia", ind.porFamilia, "categoria", "total", "PNC",
            "PNC por familia de producto"
        );
        this._chartBarHorizontalStats(
            "ncq-chart-area", ind.porArea, "categoria", "total", "Incidentes",
            "Incidentes por área"
        );
        this._chartBarHorizontalStats(
            "ncq-chart-maquina", ind.porMaquina, "categoria", "total", "NC",
            "Máquinas involucradas", { scroll: true }
        );
        this._chartBarHorizontalStats(
            "ncq-chart-operador", ind.porOperador, "categoria", "total", "NC",
            "Operadores involucrados", { scroll: true }
        );
        this._chartBarHorizontalStats(
            "ncq-chart-cliente", ind.porCliente, "categoria", "total", "NC",
            "Clientes involucrados", { scroll: true }
        );
        this._chartDoughnutStats(
            "ncq-chart-tipo-pnc", ind.porTipoPnc, "categoria", "total",
            "Distribución por tipo de PNC"
        );
        this._chartParetoStats(
            "ncq-chart-pareto", this._aplicarTopNOtros(ind.pareto), "defecto", "frecuencia", "porcentajeAcumulado",
            "Pareto de defectos"
        );
    }

    // Limita el Pareto a las N categorías con mayor frecuencia (por defecto 10) y agrupa el resto
    // en una barra "Otros" — el % acumulado de esa barra usa el acumulado real ya calculado sobre
    // TODAS las categorías filtradas, nunca se recalcula solo sobre el Top N.
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

    // ---------- Charts (mismo patrón visual/técnico que faret-nc.controller.js) ----------

    // opts.scroll=true: el canvas muestra TODAS las categorías (sin Top-N ni "Otros" — no se
    // oculta ningún dato real), con un alto calculado según la cantidad de filas para que cada
    // barra quede legible; el contenedor `.ncq-chart-scroll` (ver CSS) limita el alto visible y
    // agrega scroll interno para que el bloque no crezca sin límite. Se fija con !important
    // inline porque la regla general `.ncq-stats-card canvas { height: 200px !important }` sería
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

    // Mismo patrón que _chartDoughnutStats en faret-nc.controller.js.
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

    // Pareto real: barras = frecuencia por defecto, línea = % acumulado sobre eje secundario.
    // `rows` ya viene recortado a Top N + "Otros" (_aplicarTopNOtros). Los nombres largos se
    // truncan solo en el eje (tooltip conserva el nombre completo desde `rows`).
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

        // `full: true` marca este gráfico para ocupar el ancho completo en el reporte impreso.
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

    // Toma el <canvas> YA renderizado en pantalla de cada gráfico (chart.canvas — mismas
    // instancias que ve el usuario con los filtros activos, sin crear ni recalcular ningún
    // Chart). Valida ancho/alto del canvas y el DataURL resultante antes de incluirlo; si algo
    // falla se omite ESE gráfico puntual sin bloquear el resto del reporte.
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

    // ---------- Columnas opcionales del listado ----------

    _cargarColumnasVisibles() {
        try {
            const raw = localStorage.getItem("noConformidadesColumnasVisibles");
            return new Set(raw ? JSON.parse(raw) : []);
        } catch {
            return new Set();
        }
    }

    _guardarColumnasVisibles() {
        try {
            localStorage.setItem("noConformidadesColumnasVisibles", JSON.stringify([...this._columnasVisibles]));
        } catch {
            // localStorage no disponible; el toggle sigue funcionando en memoria para esta sesión
        }
    }

    _renderColumnasDropdown() {
        const dd = document.getElementById("ncq-columnas-dropdown");
        if (!dd) return;

        dd.innerHTML = this._columnasDisponibles.map(col => `
            <label class="ncq-columnas-item">
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

    _formatearColumnaOpcional(nc, col) {
        const valor = nc[col.key];
        if (valor === null || valor === undefined || valor === "") return "-";
        if (col.tipo === "fecha") return this._fecha(valor);
        if (col.tipo === "porcentaje") return `${(valor * 100).toFixed(2)}%`;
        return valor;
    }

    // Inserta/quita los <th> de las columnas opcionales activas justo antes de "Acciones".
    _actualizarThead() {
        const theadRow = document.querySelector("#ncq-tabla thead tr");
        const accionesTh = document.getElementById("ncq-th-acciones");
        if (!theadRow || !accionesTh) return;

        theadRow.querySelectorAll('[data-opcional="true"]').forEach(th => th.remove());

        this._columnasOpcionalesVisibles().forEach(col => {
            const th = document.createElement("th");
            th.textContent = col.label;
            th.dataset.opcional = "true";
            theadRow.insertBefore(th, accionesTh);
        });
    }

    // Total de columnas del thead (14 base + opcionales activas), usado para el colspan de las
    // filas de estado (Cargando/Sin registros/Error).
    _totalColumnasTabla() {
        return 14 + this._columnasOpcionalesVisibles().length;
    }

    _renderTabla() {
        this._actualizarThead();
        const colsOpcionales = this._columnasOpcionalesVisibles();

        const tbody = document.getElementById("ncq-tbody");

        if (!this._items.length) {
            tbody.innerHTML = `<tr><td colspan="${this._totalColumnasTabla()}">Sin registros</td></tr>`;
            return;
        }

        tbody.innerHTML = this._items.map(nc => `
            <tr>
                <td>${nc.codigo ?? "-"}</td>
                <td>${this._fecha(nc.fechaIngreso)}</td>
                <td>${this._fecha(nc.fechaSalida)}</td>
                <td>${nc.npNv ?? "-"}</td>
                <td>${nc.cliente ?? "-"}</td>
                <td>${nc.codigoProducto ?? "-"}</td>
                <td>${nc.producto ?? "-"}</td>
                <td>${nc.tipoPnc ?? "-"}</td>
                <td>${nc.categoriaDefecto ?? "-"}</td>
                <td>${this._badge(nc.nivel, this._colorSeveridad(nc.severidad || nc.nivel))}</td>
                <td>${this._badge(this._labelEstadoGestion(nc.estadoGestion), this._colorEstadoGestion(nc.estadoGestion))}</td>
                <td>${nc.responsable ?? "-"}</td>
                <td>${this._fecha(nc.fechaCompromiso)}</td>
                ${colsOpcionales.map(col => `<td>${this._formatearColumnaOpcional(nc, col)}</td>`).join("")}
                <td>
                    <button class="btn-ghost ncq-ver-btn" data-id="${nc.id}">Ver</button>
                    <button class="btn-primary ncq-analizar-btn" data-id="${nc.id}">Analizar</button>
                    <button class="btn-secondary ncq-gestionar-btn" data-id="${nc.id}">Gestionar</button>
                    <button class="btn-danger ncq-eliminar-btn" data-id="${nc.id}">Eliminar</button>
                </td>
            </tr>
        `).join("");

        tbody.querySelectorAll(".ncq-ver-btn").forEach(btn => btn.addEventListener("click", () => this._verDetalle(Number(btn.dataset.id))));
        tbody.querySelectorAll(".ncq-analizar-btn").forEach(btn => btn.addEventListener("click", () => this._abrirAnalisis(Number(btn.dataset.id))));
        tbody.querySelectorAll(".ncq-gestionar-btn").forEach(btn => btn.addEventListener("click", () => this._abrirGestion(Number(btn.dataset.id))));
        tbody.querySelectorAll(".ncq-eliminar-btn").forEach(btn => btn.addEventListener("click", () => this._eliminarNc(Number(btn.dataset.id))));
    }

    async _eliminarNc(id) {
        if (!confirm("¿Eliminar esta No Conformidad? Ya no aparecerá en el listado.")) return;
        try {
            const res = await window.PhotinoBridge.send({
                action: "noConformidades.eliminar",
                id,
                actualizadoPor: this._usuarioActual(),
            });
            if (!res.ok) {
                this._showMensaje(res.error || "Error al eliminar la No Conformidad", false);
                return;
            }
            this._showMensaje("No conformidad eliminada", true);
            await this._loadLista();
        } catch {
            this._showMensaje("Error de comunicación con el backend", false);
        }
    }

    _renderPaginacion() {
        const container = document.getElementById("ncq-paginacion");
        if (!container) return;

        let html = "";
        const rango = 2;
        const inicio = Math.max(1, this._page - rango);
        const fin = Math.min(this._pages, this._page + rango);

        if (this._page > 1) html += `<button data-ncq-page="${this._page - 1}">←</button>`;
        if (inicio > 1) {
            html += `<button data-ncq-page="1">1</button>`;
            if (inicio > 2) html += `<button disabled>...</button>`;
        }
        for (let i = inicio; i <= fin; i++) {
            html += `<button data-ncq-page="${i}" class="${i === this._page ? "active" : ""}">${i}</button>`;
        }
        if (fin < this._pages) {
            if (fin < this._pages - 1) html += `<button disabled>...</button>`;
            html += `<button data-ncq-page="${this._pages}">${this._pages}</button>`;
        }
        if (this._page < this._pages) html += `<button data-ncq-page="${this._page + 1}">→</button>`;

        container.innerHTML = html;
    }

    _irPagina(pagina) {
        if (pagina < 1 || pagina > this._pages) return;
        this._page = pagina;
        this._loadLista();
    }

    // ---------- Formulario (Nueva NC / Ver / Editar) ----------

    _camposMap() {
        return {
            fechaIngreso: { id: "ncq-f-fecha-ingreso", tipo: "fecha" },
            npNv: { id: "ncq-f-np-nv", tipo: "texto" },
            cliente: { id: "ncq-f-cliente", tipo: "texto" },
            codigoProducto: { id: "ncq-f-codigo", tipo: "texto" },
            producto: { id: "ncq-f-producto", tipo: "texto" },
            familiaProducto: { id: "ncq-f-familia-producto", tipo: "texto" },
            tipoPnc: { id: "ncq-f-tipo-pnc", tipo: "texto" },
            nivel: { id: "ncq-f-nivel", tipo: "texto" },
            categoriaDefecto: { id: "ncq-f-categoria-defecto", tipo: "texto" },
            tipoFalla: { id: "ncq-f-tipo-falla", tipo: "texto" },
            impacto: { id: "ncq-f-impacto", tipo: "texto" },
            cantRequerida: { id: "ncq-f-cant-requerida", tipo: "numero" },
            cantRechazada: { id: "ncq-f-cant-rechazada", tipo: "numero" },
            cantRecuperada: { id: "ncq-f-cant-recuperada", tipo: "numero" },
            pncReal: { id: "ncq-f-pnc-real", tipo: "numero" },
            disposicion: { id: "ncq-f-disposicion", tipo: "texto" },
            cantDestruida: { id: "ncq-f-cant-destruida", tipo: "numero" },
            cantRepuesta: { id: "ncq-f-cant-repuesta", tipo: "numero" },
            area: { id: "ncq-f-area", tipo: "texto" },
            maquina: { id: "ncq-f-maquina", tipo: "texto" },
            operador: { id: "ncq-f-operador", tipo: "texto" },
            supervisor: { id: "ncq-f-supervisor", tipo: "texto" },
            revisadoPor: { id: "ncq-f-revisado-por", tipo: "texto" },
            fechaSalida: { id: "ncq-f-fecha-salida", tipo: "fecha" },
            fechaFabricacion: { id: "ncq-f-fecha-fabricacion", tipo: "fecha" },
            descripcionDefecto: { id: "ncq-f-descripcion-defecto", tipo: "texto" },
            observacion: { id: "ncq-f-observacion", tipo: "texto" },
            causaRaiz: { id: "ncq-f-causa-raiz", tipo: "texto" },
            accionesCorrectivas: { id: "ncq-f-acciones-correctivas", tipo: "texto" },
            verificacionSeguimiento: { id: "ncq-f-verificacion-seguimiento", tipo: "texto" },
        };
    }

    _leerCampo(campo, tipo) {
        const raw = document.getElementById(this._camposMap()[campo].id).value;
        if (tipo === "numero") return raw === "" ? null : parseFloat(raw);
        if (tipo === "fecha") return raw || null;
        return raw.trim();
    }

    // Disposición (reposición/destrucción) solo aplica a Cuarentena y Rechazo Cliente — el resto
    // de los tipos de PNC no la necesitan (decisión explícita del usuario).
    _esDisposicionAplicable(tipoPnc) {
        return tipoPnc === "Cuarentena" || tipoPnc === "Rechazo Cliente";
    }

    _actualizarVisibilidadDisposicion() {
        const tipoPnc = document.getElementById("ncq-f-tipo-pnc").value;
        document.getElementById("ncq-f-disposicion-row").style.display =
            this._esDisposicionAplicable(tipoPnc) ? "flex" : "none";
    }

    _setModoEdicion(editable) {
        Object.values(this._camposMap()).forEach(({ id }) => { document.getElementById(id).disabled = !editable; });
        document.getElementById("ncq-form-editar-btn").style.display = (!editable && this._editingId) ? "inline-block" : "none";
        document.getElementById("ncq-f-guardar-btn").style.display = editable ? "inline-block" : "none";
        document.getElementById("ncq-f-cancelar-btn").style.display = (editable && this._editingId) ? "inline-block" : "none";
    }

    _abrirNuevo() {
        this._editingId = null;
        this._detalleActual = null;
        document.getElementById("ncq-form-titulo").textContent = "Nueva No Conformidad";
        document.getElementById("ncq-form-subtitulo").textContent = "Se guarda como una No Conformidad completa, disponible para gestionar de inmediato";
        document.getElementById("ncq-form-error").style.display = "none";

        Object.entries(this._camposMap()).forEach(([campo, { id }]) => { document.getElementById(id).value = ""; });
        document.getElementById("ncq-f-fecha-ingreso").value = window.DateUtils.hoyISO();
        document.getElementById("ncq-f-nivel").value = "Mayor";
        document.getElementById("ncq-f-impacto").value = "Calidad";
        document.getElementById("ncq-f-disposicion").value = "No aplica";
        document.getElementById("ncq-f-pct-recup").value = "";
        this._actualizarVisibilidadDisposicion();

        document.getElementById("ncq-f-pdf-input").value = "";
        document.getElementById("ncq-f-fotos-input").value = "";
        document.getElementById("ncq-f-adjuntos-error").style.display = "none";
        document.getElementById("ncq-f-adjuntos-bloque").style.display = "block";

        this._setModoEdicion(true);
        document.getElementById("ncq-form-modal").style.display = "flex";
    }

    async _verDetalle(id) {
        document.getElementById("ncq-form-modal").style.display = "flex";
        document.getElementById("ncq-form-titulo").textContent = "Cargando...";
        document.getElementById("ncq-form-error").style.display = "none";
        document.getElementById("ncq-f-adjuntos-bloque").style.display = "none";

        try {
            const res = await window.PhotinoBridge.send({ action: "noConformidades.get", id });
            if (!res.ok) {
                document.getElementById("ncq-form-error").textContent = res.error || "Error al cargar el detalle";
                document.getElementById("ncq-form-error").style.display = "block";
                return;
            }

            this._editingId = id;
            this._detalleActual = res.data;
            document.getElementById("ncq-form-titulo").textContent = `No Conformidad ${res.data.codigo ?? ""}`;
            document.getElementById("ncq-form-subtitulo").textContent = `Estado gestión: ${this._labelEstadoGestion(res.data.estadoGestion)}`;
            this._renderForm(res.data);
            this._setModoEdicion(false);
        } catch {
            document.getElementById("ncq-form-error").textContent = "Error de comunicación con el backend";
            document.getElementById("ncq-form-error").style.display = "block";
        }
    }

    _renderForm(nc) {
        Object.entries(this._camposMap()).forEach(([campo, { id, tipo }]) => {
            const el = document.getElementById(id);
            if (tipo === "fecha") el.value = nc[campo] ? String(nc[campo]).substring(0, 10) : "";
            else el.value = nc[campo] ?? "";
        });
        this._recalcularPctRecup();
        this._actualizarVisibilidadDisposicion();
    }

    _habilitarEdicion() {
        this._setModoEdicion(true);
    }

    _cancelarEdicion() {
        if (this._detalleActual) this._renderForm(this._detalleActual);
        this._setModoEdicion(false);
    }

    _cerrarForm() {
        document.getElementById("ncq-form-modal").style.display = "none";
    }

    _mapNivelASeveridad(nivel) {
        const n = (nivel || "").toUpperCase();
        if (n.includes("CRIT")) return "ALTA";
        if (n.includes("MAYOR")) return "MEDIA";
        if (n.includes("MENOR")) return "BAJA";
        return "MEDIA";
    }

    _recalcularPctRecup() {
        const rechazada = parseFloat(document.getElementById("ncq-f-cant-rechazada").value);
        const recuperada = parseFloat(document.getElementById("ncq-f-cant-recuperada").value);
        const el = document.getElementById("ncq-f-pct-recup");
        if (!rechazada || isNaN(recuperada)) { el.value = ""; return; }
        el.value = `${(recuperada / rechazada * 100).toFixed(2)}%`;
    }

    async _guardarForm() {
        const errorEl = document.getElementById("ncq-form-error");
        errorEl.style.display = "none";

        const campos = {};
        Object.entries(this._camposMap()).forEach(([campo, { tipo }]) => { campos[campo] = this._leerCampo(campo, tipo); });

        if (!campos.npNv || !campos.cliente || !campos.codigoProducto || !campos.producto || !campos.categoriaDefecto
            || !campos.nivel || !campos.descripcionDefecto || campos.cantRequerida === null || campos.cantRechazada === null) {
            errorEl.textContent = "NP/NV, Cliente, Código, Producto, Categoría defecto, Nivel, Descripción defecto, "
                + "Cant. requerida y Cant. rechazada son obligatorios";
            errorEl.style.display = "block";
            return;
        }

        const hoy = window.DateUtils.hoyISO();
        const fechaIngreso = campos.fechaIngreso || hoy;

        const cabecera = {
            tipo: "INTERNA",
            origen: "AUDITORIA_INTERNA",
            titulo: `PNC ${campos.npNv} - ${campos.producto || campos.cliente}`.trim(),
            descripcion: [campos.categoriaDefecto, campos.descripcionDefecto].filter(Boolean).join(" - "),
            severidad: this._mapNivelASeveridad(campos.nivel),
            proceso: campos.tipoPnc || campos.area || "PNC Nueva",
            fechaDeteccion: fechaIngreso,
        };

        const usuario = this._usuarioActual();
        const payload = { ...campos, fechaIngreso, ...cabecera };

        let pdfFile = null;
        let fotoFiles = [];
        if (!this._editingId) {
            const adjuntosErrorEl = document.getElementById("ncq-f-adjuntos-error");
            adjuntosErrorEl.style.display = "none";

            pdfFile = document.getElementById("ncq-f-pdf-input").files?.[0] || null;
            fotoFiles = Array.from(document.getElementById("ncq-f-fotos-input").files || []);

            const validacion = this._validarAdjuntosNuevaNc(pdfFile, fotoFiles);
            if (validacion) {
                adjuntosErrorEl.textContent = validacion;
                adjuntosErrorEl.style.display = "block";
                return;
            }
        }

        const btn = document.getElementById("ncq-f-guardar-btn");
        btn.disabled = true;
        try {
            const action = this._editingId ? "noConformidades.update" : "noConformidades.create";
            const res = await window.PhotinoBridge.send({
                action,
                ...(this._editingId ? { id: this._editingId, actualizadoPor: usuario } : { creadoPor: usuario }),
                ...payload,
            });

            if (!res.ok) {
                errorEl.textContent = res.error || "Error al guardar la no conformidad";
                errorEl.style.display = "block";
                return;
            }

            const ncId = !this._editingId ? res.data?.id : null;
            const erroresAdjuntos = ncId ? await this._subirAdjuntosNuevaNc(ncId, pdfFile, fotoFiles) : [];

            this._cerrarForm();
            this._showMensaje(
                erroresAdjuntos.length
                    ? `No conformidad creada, pero hubo un problema subiendo adjuntos: ${erroresAdjuntos.join("; ")}`
                    : (this._editingId ? "No conformidad actualizada" : "No conformidad creada"),
                erroresAdjuntos.length === 0
            );
            this._cargarFiltrosOpciones();
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

    // Sube los adjuntos elegidos en el formulario de "Nueva No Conformidad" recién después de que
    // la NC ya existe (necesitan su id). Reutiliza la misma acción/backend que el resto del
    // sistema de adjuntos (ver modal "Análisis y Plan de Acción"). Devuelve la lista de errores
    // (vacía si todo salió bien) — nunca revierte la NC ya creada por un adjunto que falle.
    async _subirAdjuntosNuevaNc(ncId, pdfFile, fotoFiles) {
        const errores = [];
        const usuario = this._usuarioActual();

        if (pdfFile) {
            try {
                const contenidoBase64 = await this._leerArchivoBase64(pdfFile);
                const res = await window.PhotinoBridge.send({
                    action: "noConformidades.adjuntos.subir",
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
                    action: "noConformidades.adjuntos.subir",
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

    _showMensaje(texto, ok) {
        const el = document.getElementById("ncq-mensaje");
        el.textContent = texto;
        el.style.display = "block";
        el.style.background = ok ? "#ECFDF5" : "#FEF2F2";
        el.style.color = ok ? "#065F46" : "#991B1B";
        el.style.borderLeftColor = ok ? "#10B981" : "#EF4444";
        setTimeout(() => { el.style.display = "none"; }, 4000);
    }

    // ---------- Gestionar ----------

    async _abrirGestion(id) {
        this._gestionId = id;
        document.getElementById("ncq-gestion-error").style.display = "none";
        document.getElementById("ncq-gestion-mensaje").style.display = "none";
        document.getElementById("ncq-gestion-titulo").textContent = "Cargando...";
        document.getElementById("ncq-gestion-modal").style.display = "flex";

        try {
            const res = await window.PhotinoBridge.send({ action: "noConformidades.get", id });
            if (!res.ok) {
                document.getElementById("ncq-gestion-error").textContent = res.error || "Error al cargar la no conformidad";
                document.getElementById("ncq-gestion-error").style.display = "block";
                return;
            }

            const nc = res.data;
            document.getElementById("ncq-gestion-titulo").textContent = `Gestionar ${nc.codigo ?? ""}`;
            document.getElementById("ncq-gestion-responsable").value = nc.responsable || "";
            document.getElementById("ncq-gestion-estado").value = nc.estadoGestion || "PENDIENTE";
            document.getElementById("ncq-gestion-fecha-compromiso").value = nc.fechaCompromiso ? String(nc.fechaCompromiso).substring(0, 10) : "";
            document.getElementById("ncq-cierre-comentario").value = "";
            document.getElementById("ncq-seguimiento-comentario").value = "";

            await this._cargarSeguimiento(id);
        } catch {
            document.getElementById("ncq-gestion-error").textContent = "Error de comunicación con el backend";
            document.getElementById("ncq-gestion-error").style.display = "block";
        }
    }

    _cerrarGestion() {
        document.getElementById("ncq-gestion-modal").style.display = "none";
        this._gestionId = null;
    }

    async _guardarGestion() {
        if (!this._gestionId) return;
        const errorEl = document.getElementById("ncq-gestion-error");
        errorEl.style.display = "none";

        const payload = {
            id: this._gestionId,
            responsable: document.getElementById("ncq-gestion-responsable").value.trim(),
            estadoGestion: document.getElementById("ncq-gestion-estado").value,
            fechaCompromiso: document.getElementById("ncq-gestion-fecha-compromiso").value || null,
            actualizadoPor: this._usuarioActual(),
        };

        const btn = document.getElementById("ncq-gestion-guardar-btn");
        btn.disabled = true;
        try {
            const res = await window.PhotinoBridge.send({ action: "noConformidades.gestion.actualizar", ...payload });
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
        const cont = document.getElementById("ncq-seguimiento-lista");
        cont.innerHTML = "Cargando...";
        try {
            const res = await window.PhotinoBridge.send({ action: "noConformidades.seguimiento.list", id: ncId });
            const items = res.ok && Array.isArray(res.data) ? res.data : [];
            if (!items.length) { cont.innerHTML = `<div>Sin comentarios de seguimiento</div>`; return; }
            cont.innerHTML = items.map(c => `
                <div class="ncq-seguimiento-item">
                    <div>${c.comentario ?? "-"}</div>
                    <div class="ncq-seguimiento-meta">${c.autor ?? "Sin autor"} · ${c.creadoEn ? new Date(c.creadoEn).toLocaleString("es-CL") : "-"}</div>
                </div>
            `).join("");
        } catch {
            cont.innerHTML = `<div>Error al cargar el seguimiento</div>`;
        }
    }

    async _agregarSeguimiento() {
        if (!this._gestionId) return;
        const comentario = document.getElementById("ncq-seguimiento-comentario").value.trim();
        if (!comentario) return;

        try {
            const res = await window.PhotinoBridge.send({
                action: "noConformidades.seguimiento.crear",
                id: this._gestionId,
                comentario,
                autor: this._usuarioActual(),
            });
            if (!res.ok) { this._showGestionMensaje(res.error || "Error al agregar el comentario", false); return; }
            document.getElementById("ncq-seguimiento-comentario").value = "";
            await this._cargarSeguimiento(this._gestionId);
            this._showGestionMensaje("Comentario agregado", true);
        } catch {
            this._showGestionMensaje("Error de comunicación con el backend", false);
        }
    }

    async _cerrarNc() {
        if (!this._gestionId) return;
        if (!confirm("¿Cerrar esta No Conformidad? Quedará marcada como CERRADA.")) return;

        const comentarioCierre = document.getElementById("ncq-cierre-comentario").value.trim();
        try {
            const res = await window.PhotinoBridge.send({
                action: "noConformidades.cerrar",
                id: this._gestionId,
                cerradoPor: this._usuarioActual(),
                comentarioCierre: comentarioCierre || null,
            });
            if (!res.ok) { this._showGestionMensaje(res.error || "Error al cerrar la no conformidad", false); return; }
            this._cerrarGestion();
            this._showMensaje("No conformidad cerrada", true);
            await this._loadLista();
        } catch {
            this._showGestionMensaje("Error de comunicación con el backend", false);
        }
    }

    _showGestionMensaje(texto, ok) {
        const el = document.getElementById("ncq-gestion-mensaje");
        el.textContent = texto;
        el.style.display = "block";
        el.style.background = ok ? "#ECFDF5" : "#FEF2F2";
        el.style.color = ok ? "#065F46" : "#991B1B";
        el.style.borderLeftColor = ok ? "#10B981" : "#EF4444";
        setTimeout(() => { el.style.display = "none"; }, 4000);
    }

    // ---------- Analizar ----------

    async _abrirAnalisis(id) {
        this._analisisNcId = id;
        this._analisisActual = null;
        this._acciones = [];
        this._adjuntos = [];
        this._analisisCerrada = false;

        document.getElementById("ncq-analisis-titulo").textContent = "Cargando...";
        document.getElementById("ncq-analisis-error").style.display = "none";
        document.getElementById("ncq-analisis-mensaje").style.display = "none";
        document.getElementById("ncq-analisis-modal").style.display = "flex";

        try {
            const ncRes = await window.PhotinoBridge.send({ action: "noConformidades.get", id });
            if (ncRes.ok) {
                document.getElementById("ncq-analisis-titulo").textContent = `Análisis y Plan de Acción — ${ncRes.data.codigo ?? ""}`;
                this._analisisCerrada = (ncRes.data.estadoGestion || "").toUpperCase() === "CERRADA";
            }
        } catch { }

        await this._cargarAnalisis();
        await this._cargarAcciones();
        await this._cargarAdjuntos();
    }

    _cerrarAnalisis() {
        document.getElementById("ncq-analisis-modal").style.display = "none";
        this._analisisNcId = null;
        this._adjuntos = [];
        this._analisisCerrada = false;
    }

    async _cargarAnalisis() {
        const errorEl = document.getElementById("ncq-analisis-error");
        errorEl.style.display = "none";
        try {
            const res = await window.PhotinoBridge.send({ action: "noConformidades.analisis.get", id: this._analisisNcId });
            this._analisisActual = res.ok ? res.data : null;
            if (!res.ok) { errorEl.textContent = res.error || "Error al cargar el análisis"; errorEl.style.display = "block"; }
        } catch {
            errorEl.textContent = "Error de comunicación con el backend";
            errorEl.style.display = "block";
            this._analisisActual = null;
        }
        this._renderAnalisisForm();
    }

    _renderAnalisisForm() {
        const a = this._analisisActual;
        document.getElementById("ncq-analisis-metodologia").value = a?.metodologia || "CINCO_PORQUES";
        document.getElementById("ncq-analisis-problema").value = a?.problemaDetectado || "";
        document.getElementById("ncq-analisis-porque1").value = a?.porque1 || "";
        document.getElementById("ncq-analisis-porque2").value = a?.porque2 || "";
        document.getElementById("ncq-analisis-porque3").value = a?.porque3 || "";
        document.getElementById("ncq-analisis-porque4").value = a?.porque4 || "";
        document.getElementById("ncq-analisis-porque5").value = a?.porque5 || "";
        document.getElementById("ncq-analisis-causa-raiz").value = a?.causaRaiz || "";
        document.getElementById("ncq-analisis-conclusion").value = a?.conclusion || "";
    }

    async _guardarAnalisis() {
        const errorEl = document.getElementById("ncq-analisis-error");
        errorEl.style.display = "none";

        const payload = {
            metodologia: document.getElementById("ncq-analisis-metodologia").value,
            problemaDetectado: document.getElementById("ncq-analisis-problema").value.trim(),
            porque1: document.getElementById("ncq-analisis-porque1").value.trim(),
            porque2: document.getElementById("ncq-analisis-porque2").value.trim(),
            porque3: document.getElementById("ncq-analisis-porque3").value.trim(),
            porque4: document.getElementById("ncq-analisis-porque4").value.trim(),
            porque5: document.getElementById("ncq-analisis-porque5").value.trim(),
            causaRaiz: document.getElementById("ncq-analisis-causa-raiz").value.trim(),
            conclusion: document.getElementById("ncq-analisis-conclusion").value.trim(),
        };

        if (!payload.problemaDetectado) {
            errorEl.textContent = "El problema detectado es obligatorio";
            errorEl.style.display = "block";
            return;
        }
        if (!confirm("¿Guardar el análisis de causa raíz de esta no conformidad?")) return;

        const btn = document.getElementById("ncq-analisis-guardar-btn");
        btn.disabled = true;
        try {
            const res = await window.PhotinoBridge.send({
                action: "noConformidades.analisis.guardar",
                id: this._analisisNcId,
                usuario: this._usuarioActual(),
                ...payload,
            });
            if (!res.ok) { errorEl.textContent = res.error || "Error al guardar el análisis"; errorEl.style.display = "block"; return; }
            await this._cargarAnalisis();
            this._showAnalisisMensaje("Análisis guardado correctamente", true);
        } catch {
            errorEl.textContent = "Error de comunicación con el backend";
            errorEl.style.display = "block";
        } finally {
            btn.disabled = false;
        }
    }

    // ---------- Adjuntos: PDF de análisis de causa raíz + evidencia fotográfica ----------

    async _cargarAdjuntos() {
        const errorEl = document.getElementById("ncq-adjunto-pdf-error");
        errorEl.style.display = "none";

        try {
            const res = await window.PhotinoBridge.send({
                action: "noConformidades.adjuntos.list",
                id: Number(this._analisisNcId),
            });
            this._adjuntos = res.ok && Array.isArray(res.data) ? res.data : [];
        } catch {
            this._adjuntos = [];
        }

        this._renderAdjuntoPdf();
        this._renderAdjuntoFotos();
    }

    _renderAdjuntoPdf() {
        const bloque = document.getElementById("ncq-adjunto-pdf-bloque");
        const pdf = this._adjuntos.find(a => a.tipo === "CAUSA_RAIZ_PDF");
        const cerrada = this._analisisCerrada;

        if (!pdf) {
            bloque.innerHTML = cerrada
                ? `<span class="ncq-stats-nota">Sin PDF adjunto.</span>`
                : `<button type="button" class="btn-secondary" id="ncq-adjunto-pdf-adjuntar-btn">Adjuntar PDF</button>`;
        } else {
            bloque.innerHTML = `
                <div class="ncq-adjunto-pdf-fila">
                    <span>${pdf.nombreArchivo}</span>
                    <button type="button" class="btn-secondary" id="ncq-adjunto-pdf-ver-btn">Ver</button>
                    ${cerrada ? "" : `<button type="button" class="btn-secondary" id="ncq-adjunto-pdf-reemplazar-btn">Reemplazar</button>`}
                </div>
            `;
        }

        document.getElementById("ncq-adjunto-pdf-adjuntar-btn")
            ?.addEventListener("click", () => document.getElementById("ncq-adjunto-pdf-input")?.click());
        document.getElementById("ncq-adjunto-pdf-reemplazar-btn")
            ?.addEventListener("click", () => document.getElementById("ncq-adjunto-pdf-input")?.click());
        document.getElementById("ncq-adjunto-pdf-ver-btn")
            ?.addEventListener("click", () => this._verAdjunto(pdf.id));
    }

    _renderAdjuntoFotos() {
        const grid = document.getElementById("ncq-adjunto-fotos-grid");
        const fotos = this._adjuntos.filter(a => a.tipo === "EVIDENCIA_FOTO");
        const cerrada = this._analisisCerrada;

        document.getElementById("ncq-adjunto-fotos-btn").style.display = cerrada ? "none" : "inline-block";

        if (fotos.length === 0) {
            grid.innerHTML = `<span class="ncq-stats-nota">${cerrada ? "Sin fotografías adjuntas." : "Aún no hay fotografías adjuntas."}</span>`;
            return;
        }

        grid.innerHTML = fotos.map(f => `
            <div class="ncq-foto-item" data-adjunto-id="${f.id}" title="${f.nombreArchivo}">
                ${cerrada ? "" : `<button type="button" class="ncq-foto-eliminar" data-adjunto-id="${f.id}">×</button>`}
            </div>
        `).join("");

        fotos.forEach(f => {
            const el = grid.querySelector(`.ncq-foto-item[data-adjunto-id="${f.id}"]`);
            if (!el) return;

            el.addEventListener("click", (ev) => {
                if (ev.target.closest(".ncq-foto-eliminar")) return;
                this._verAdjunto(f.id);
            });

            el.querySelector(".ncq-foto-eliminar")
                ?.addEventListener("click", (ev) => {
                    ev.stopPropagation();
                    this._eliminarAdjunto(f.id);
                });

            window.PhotinoBridge.send({
                action: "noConformidades.adjuntos.abrir",
                id: Number(this._analisisNcId),
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

        const errorEl = document.getElementById("ncq-adjunto-pdf-error");
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
                action: "noConformidades.adjuntos.subir",
                id: Number(this._analisisNcId),
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

        const errorEl = document.getElementById("ncq-adjunto-fotos-error");
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
                    action: "noConformidades.adjuntos.subir",
                    id: Number(this._analisisNcId),
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
                action: "noConformidades.adjuntos.abrir",
                id: Number(this._analisisNcId),
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
                action: "noConformidades.adjuntos.eliminar",
                id: Number(this._analisisNcId),
                adjuntoId,
            });
            if (!res.ok) throw new Error(res.error || "Error al eliminar el adjunto");

            await this._cargarAdjuntos();
        } catch (err) {
            alert(err.message);
        }
    }

    // Mismo patrón visual que el visor de imágenes de Registros de Control / faret-nc: overlay
    // oscuro + tarjeta blanca, anclado a document.body. PDF se previsualiza en un <iframe>
    // (WebView2 lo renderiza nativo), imagen en <img>.
    _mostrarAdjuntoVisor(nombreArchivo, tipoMime, contenidoBase64) {
        const existente = document.getElementById("ncqModalAdjunto");
        if (existente) existente.remove();

        const dataUrl = `data:${tipoMime};base64,${contenidoBase64}`;
        const visor = tipoMime.startsWith("image/")
            ? `<img src="${dataUrl}" alt="${nombreArchivo}" style="display:block;max-width:100%;max-height:75vh;object-fit:contain;border-radius:8px;">`
            : `<iframe src="${dataUrl}" style="width:80vw;height:75vh;border:0;"></iframe>`;

        const modal = document.createElement("div");
        modal.id = "ncqModalAdjunto";
        modal.style.cssText = "position:fixed;left:0;top:0;width:100%;height:100%;background:rgba(15,23,42,0.75);z-index:9999;display:flex;align-items:center;justify-content:center;padding:24px;";
        modal.innerHTML = `
            <div style="background:#fff;border-radius:12px;max-width:90%;max-height:90%;padding:16px;box-shadow:0 20px 60px rgba(0,0,0,0.35);position:relative;">
                <div style="display:flex;justify-content:space-between;align-items:center;gap:12px;margin-bottom:12px;">
                    <strong>${nombreArchivo}</strong>
                    <button id="ncqBtnCerrarAdjunto" class="btn-secondary" type="button">Cerrar</button>
                </div>
                ${visor}
            </div>
        `;
        document.body.appendChild(modal);
        document.getElementById("ncqBtnCerrarAdjunto").addEventListener("click", () => modal.remove());
    }

    async _cargarAcciones() {
        try {
            const res = await window.PhotinoBridge.send({ action: "noConformidades.acciones.list", id: this._analisisNcId });
            this._acciones = res.ok && Array.isArray(res.data) ? res.data : [];
        } catch {
            this._acciones = [];
        }
        this._renderAcciones();
    }

    _renderAcciones() {
        const tbody = document.getElementById("ncq-acciones-tbody");
        if (!this._acciones.length) {
            tbody.innerHTML = `<tr><td colspan="6">Sin acciones correctivas</td></tr>`;
            return;
        }

        const estados = ["PENDIENTE", "EN_PROCESO", "COMPLETADA", "CANCELADA"];
        tbody.innerHTML = this._acciones.map(a => `
            <tr>
                <td>${a.descripcion ?? "-"}</td>
                <td>${a.responsable ?? "-"}</td>
                <td>${this._fecha(a.fechaLimite)}</td>
                <td>${a.prioridad ?? "-"}</td>
                <td>
                    <select class="ncq-accion-estado-select" data-id="${a.id}">
                        ${estados.map(e => `<option value="${e}" ${e === a.estado ? "selected" : ""}>${e}</option>`).join("")}
                    </select>
                </td>
                <td><button class="btn-secondary ncq-accion-guardar-btn" data-id="${a.id}">Guardar</button></td>
            </tr>
        `).join("");

        tbody.querySelectorAll(".ncq-accion-guardar-btn").forEach(btn =>
            btn.addEventListener("click", () => this._actualizarEstadoAccion(btn.dataset.id)));
    }

    async _agregarAccion() {
        const errorEl = document.getElementById("ncq-accion-error");
        errorEl.style.display = "none";

        const payload = {
            descripcion: document.getElementById("ncq-accion-descripcion").value.trim(),
            responsable: document.getElementById("ncq-accion-responsable").value.trim(),
            fechaLimite: document.getElementById("ncq-accion-fecha-limite").value,
            prioridad: document.getElementById("ncq-accion-prioridad").value || null,
        };

        if (!payload.descripcion || !payload.responsable || !payload.fechaLimite) {
            errorEl.textContent = "Descripción, responsable y fecha límite son obligatorios";
            errorEl.style.display = "block";
            return;
        }
        if (!confirm("¿Agregar esta acción correctiva a la no conformidad?")) return;

        const btn = document.getElementById("ncq-accion-agregar-btn");
        btn.disabled = true;
        try {
            const res = await window.PhotinoBridge.send({
                action: "noConformidades.acciones.crear",
                id: this._analisisNcId,
                analisisId: this._analisisActual?.id ?? null,
                creadoPor: this._usuarioActual(),
                ...payload,
            });
            if (!res.ok) { errorEl.textContent = res.error || "Error al agregar la acción"; errorEl.style.display = "block"; return; }

            document.getElementById("ncq-accion-descripcion").value = "";
            document.getElementById("ncq-accion-responsable").value = "";
            document.getElementById("ncq-accion-fecha-limite").value = "";
            document.getElementById("ncq-accion-prioridad").value = "";

            await this._cargarAcciones();
            this._showAnalisisMensaje("Acción correctiva agregada", true);
        } catch {
            errorEl.textContent = "Error de comunicación con el backend";
            errorEl.style.display = "block";
        } finally {
            btn.disabled = false;
        }
    }

    async _actualizarEstadoAccion(accionId) {
        const accion = this._acciones.find(a => String(a.id) === String(accionId));
        if (!accion) return;

        const select = document.querySelector(`.ncq-accion-estado-select[data-id="${accionId}"]`);
        const nuevoEstado = select ? select.value : accion.estado;
        if (!confirm(`¿Cambiar el estado de la acción a "${nuevoEstado}"?`)) return;

        try {
            const res = await window.PhotinoBridge.send({
                action: "noConformidades.acciones.actualizar",
                accionId: Number(accionId),
                descripcion: accion.descripcion,
                responsable: accion.responsable,
                fechaLimite: accion.fechaLimite ? String(accion.fechaLimite).substring(0, 10) : "",
                prioridad: accion.prioridad || null,
                estado: nuevoEstado,
                actualizadoPor: this._usuarioActual(),
            });
            if (!res.ok) { this._showAnalisisMensaje(res.error || "Error al actualizar la acción", false); return; }
            await this._cargarAcciones();
            this._showAnalisisMensaje("Acción correctiva actualizada", true);
        } catch {
            this._showAnalisisMensaje("Error de comunicación con el backend", false);
        }
    }

    _showAnalisisMensaje(texto, ok) {
        const el = document.getElementById("ncq-analisis-mensaje");
        el.textContent = texto;
        el.style.display = "block";
        el.style.background = ok ? "#ECFDF5" : "#FEF2F2";
        el.style.color = ok ? "#065F46" : "#991B1B";
        el.style.borderLeftColor = ok ? "#10B981" : "#EF4444";
        setTimeout(() => { el.style.display = "none"; }, 4000);
    }

    // ---------- Presentación ----------

    _fecha(valor) {
        return window.DateUtils.formatear(valor);
    }

    _badge(texto, color) {
        return `<span class="ncq-badge" style="background:${color}1F;color:${color};">${texto ?? "-"}</span>`;
    }

    _colorSeveridad(valor) {
        const s = (valor || "").toUpperCase();
        if (s === "ALTA" || s.includes("CRIT")) return "#DC2626";
        if (s === "MEDIA" || s.includes("MAYOR")) return "#D97706";
        if (s === "BAJA" || s.includes("MENOR")) return "#059669";
        return "#64748B";
    }

    _labelEstadoGestion(estado) {
        const map = { PENDIENTE: "Pendiente", ASIGNADA: "Asignada", EN_GESTION: "En gestión", CERRADA: "Cerrada" };
        return map[estado] || estado || "-";
    }

    _colorEstadoGestion(estado) {
        switch (estado) {
            case "CERRADA": return "#059669";
            case "EN_GESTION": return "#2563EB";
            case "ASIGNADA": return "#D97706";
            default: return "#64748B";
        }
    }

    // ---------- Exportar / Imprimir ----------

    async _obtenerItemsFiltrados() {
        const filtros = this._getFiltros();
        const res = await window.PhotinoBridge.send({ action: "noConformidades.list", page: 1, pageSize: 999999, ...filtros });
        return res.ok && Array.isArray(res.data.items) ? res.data.items : [];
    }

    _resumenFiltrosTexto() {
        const f = this._getFiltros();
        const partes = [];
        if (f.cliente) partes.push(`Cliente: ${f.cliente}`);
        if (f.tipoPnc) partes.push(`Tipo PNC: ${f.tipoPnc}`);
        if (f.nivel) partes.push(`Nivel: ${f.nivel}`);
        if (f.estadoGestion) partes.push(`Estado gestión: ${this._labelEstadoGestion(f.estadoGestion)}`);
        if (f.area) partes.push(`Área: ${f.area}`);
        if (f.fechaDesde) partes.push(`Fecha ingreso desde: ${f.fechaDesde}`);
        if (f.fechaHasta) partes.push(`Fecha ingreso hasta: ${f.fechaHasta}`);
        return partes.length ? partes.join(" · ") : "Sin filtros — histórico completo";
    }

    async _exportar() {
        try {
            const items = await this._obtenerItemsFiltrados();
            const tabla = this._construirTablaTemp(items);
            window.ExcelExporter.exportTable({
                tableSelector: "#ncq-tabla-export-temp",
                fileName: `no_conformidades_${Date.now()}.xlsx`,
                sheetName: "No Conformidades",
                title: "QCC - No Conformidades",
            });
            tabla.remove();
        } catch {
            this._showMensaje("Error al exportar", false);
        }
    }

    async _imprimir() {
        try {
            const items = await this._obtenerItemsFiltrados();
            const tabla = this._construirTablaTemp(items);
            window.PrintExporter.printTable({
                tableSelector: "#ncq-tabla-export-temp",
                titulo: "No Conformidades",
                empresa: "INNPACK",
                subtitulo: this._resumenFiltrosTexto(),
                totalRegistros: items.length,
            });
            tabla.remove();
        } catch {
            this._showMensaje("Error al imprimir", false);
        }
    }

    // Reutiliza this._itemsCompletos (ya cargado por _loadLista con los filtros activos, mismo
    // universo que alimenta los 6 indicadores) — sin refetch. Los gráficos se capturan tal cual
    // están renderizados en pantalla (_capturarGraficosParaImpresion), sin recrear ningún Chart.
    _imprimirReporteEstadistico() {
        const items = this._itemsCompletos || [];
        const ind = this._calcularIndicadores(items);
        const paretoImpreso = this._aplicarTopNOtros(ind.pareto);

        const graficos = this._capturarGraficosParaImpresion();

        window.PrintExporter.printReport({
            empresa: "INNPACK",
            titulo: "Reporte Estadístico de No Conformidades",
            subtitulo: this._resumenFiltrosTexto(),
            totalRegistros: items.length,
            resumen: [
                { label: "Cuarentenas — total", valor: ind.cuarentenas.total },
                { label: "Cantidad total unidades cuarentenas", valor: ind.cuarentenas.cantidadTotalUnidades },
                { label: "Rechazos cliente — total", valor: ind.rechazosCliente.total },
                { label: "Rechazados totales", valor: ind.rechazosTotales },
                { label: "Total reclamos", valor: ind.totalReclamos },
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

    _construirTablaTemp(items) {
        const colsOpcionales = this._columnasOpcionalesVisibles();

        const tabla = document.createElement("table");
        tabla.id = "ncq-tabla-export-temp";
        tabla.style.position = "absolute";
        tabla.style.left = "-99999px";
        tabla.style.top = "0";

        tabla.innerHTML = `
            <thead>
                <tr>
                    <th>Código</th><th>Fecha ingreso</th><th>Fecha salida</th><th>NP/NV</th><th>Cliente</th>
                    <th>Código producto</th><th>Producto</th><th>Tipo PNC</th><th>Categoría defecto</th><th>Nivel</th>
                    <th>Estado gestión</th><th>Responsable</th><th>Fecha compromiso</th>
                    ${colsOpcionales.map(col => `<th>${col.label}</th>`).join("")}
                </tr>
            </thead>
            <tbody>
                ${items.map(nc => `
                    <tr>
                        <td>${nc.codigo ?? "-"}</td>
                        <td>${this._fecha(nc.fechaIngreso)}</td>
                        <td>${this._fecha(nc.fechaSalida)}</td>
                        <td>${nc.npNv ?? "-"}</td>
                        <td>${nc.cliente ?? "-"}</td>
                        <td>${nc.codigoProducto ?? "-"}</td>
                        <td>${nc.producto ?? "-"}</td>
                        <td>${nc.tipoPnc ?? "-"}</td>
                        <td>${nc.categoriaDefecto ?? "-"}</td>
                        <td>${nc.nivel ?? "-"}</td>
                        <td>${this._labelEstadoGestion(nc.estadoGestion)}</td>
                        <td>${nc.responsable ?? "-"}</td>
                        <td>${this._fecha(nc.fechaCompromiso)}</td>
                        ${colsOpcionales.map(col => `<td>${this._formatearColumnaOpcional(nc, col)}</td>`).join("")}
                    </tr>
                `).join("")}
            </tbody>
        `;

        document.body.appendChild(tabla);
        return tabla;
    }
};
