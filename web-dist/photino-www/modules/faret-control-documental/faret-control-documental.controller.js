window.FaretControlDocumentalController = class FaretControlDocumentalController {

    init() {
        console.log("FaretControlDocumentalController iniciado");

        this._page = 1;
        this._pageSize = 100;
        this._pages = 1;
        this._total = 0;
        this._items = [];
        this._editingId = null;
        this._detalleActual = null;

        document.getElementById("fcd-nuevo-btn")?.addEventListener("click", () => this._abrirNuevo());
        document.getElementById("fcd-exportar-btn")?.addEventListener("click", () => this._exportar());
        document.getElementById("fcd-filtrar-btn")?.addEventListener("click", () => { this._page = 1; this._loadLista(); });
        document.getElementById("fcd-limpiar-btn")?.addEventListener("click", () => this._limpiarFiltros());

        document.getElementById("fcd-form-cerrar-btn")?.addEventListener("click", () => this._cerrarForm());
        document.getElementById("fcd-form-cancelar-btn")?.addEventListener("click", () => this._cancelarEdicion());
        document.getElementById("fcd-form-editar-btn")?.addEventListener("click", () => this._habilitarEdicion());
        document.getElementById("fcd-f-guardar-btn")?.addEventListener("click", () => this._guardarForm());
        document.getElementById("fcd-f-fecha-actualizacion")?.addEventListener("change", () => this._autocalcularProximaRevision("fcd-f-fecha-actualizacion", "fcd-f-proxima-revision"));
        document.getElementById("fcd-nv-fecha-actualizacion")?.addEventListener("change", () => this._autocalcularProximaRevision("fcd-nv-fecha-actualizacion", "fcd-nv-proxima-revision"));
        document.getElementById("fcd-nv-agregar-btn")?.addEventListener("click", () => this._agregarVersion());

        document.getElementById("fcd-adjunto-cerrar-btn")?.addEventListener("click", () => {
            document.getElementById("fcd-adjunto-modal").style.display = "none";
        });
        document.getElementById("fcd-adjunto-input")?.addEventListener("change", (e) => {
            const file = e.target.files[0];
            if (file) this._subirAdjunto(file);
        });

        document.getElementById("fcd-paginacion")?.addEventListener("click", (e) => {
            const btn = e.target.closest("[data-fcd-page]");
            if (!btn || btn.disabled) return;
            this._irPagina(Number(btn.dataset.fcdPage));
        });

        this._loadLista();
    }

    destroy() {
        console.log("FaretControlDocumentalController destruido");
    }

    // Módulo con datos 100% compartidos con INNPACK: llama directo a las mismas acciones
    // "controlDocumental.*" (sin prefijo "faret.") porque la tabla vive en la misma BD "calidad"
    // que ya administra este mismo desktop — no hay ninguna API Faret dueña de este dato.
    _usuarioActual() {
        return sessionStorage.getItem("faretNombreUsuario") || "";
    }

    // ---------- Filtros ----------

    _getFiltros() {
        return {
            texto: document.getElementById("fcd-filtro-texto")?.value || "",
            tipoDocumento: document.getElementById("fcd-filtro-tipo")?.value || "",
            area: document.getElementById("fcd-filtro-area")?.value || "",
            estado: document.getElementById("fcd-filtro-estado")?.value || "",
            alcanceEmpresa: document.getElementById("fcd-filtro-alcance")?.value || "",
        };
    }

    _limpiarFiltros() {
        document.getElementById("fcd-filtro-texto").value = "";
        ["fcd-filtro-tipo", "fcd-filtro-area", "fcd-filtro-estado", "fcd-filtro-alcance"]
            .forEach(id => { const el = document.getElementById(id); if (el) el.value = ""; });
        this._page = 1;
        this._loadLista();
    }

    // Los <select> de Tipo/Área se pueblan con los valores reales ya cargados en el listado
    // (no hay catálogo propio ni acción de filtrosOpciones en el backend de este módulo).
    _poblarFiltrosSelect() {
        const poblar = (selectId, campo) => {
            const select = document.getElementById(selectId);
            if (!select) return;
            const valorActual = select.value;
            const valores = [...new Set(this._items.map(d => d[campo]).filter(Boolean))].sort();
            select.innerHTML = `<option value="">${selectId === "fcd-filtro-area" ? "Todas" : "Todos"}</option>`
                + valores.map(v => `<option value="${v}">${v}</option>`).join("");
            if (valorActual) select.value = valorActual;
        };
        poblar("fcd-filtro-tipo", "tipoDocumento");
        poblar("fcd-filtro-area", "area");

        const dlTipo = document.getElementById("fcd-dl-tipo");
        const dlArea = document.getElementById("fcd-dl-area");
        if (dlTipo) dlTipo.innerHTML = [...new Set(this._items.map(d => d.tipoDocumento).filter(Boolean))]
            .map(v => `<option value="${v}"></option>`).join("");
        if (dlArea) dlArea.innerHTML = [...new Set(this._items.map(d => d.area).filter(Boolean))]
            .map(v => `<option value="${v}"></option>`).join("");
    }

    // ---------- Listado ----------

    async _loadLista() {
        const tbody = document.getElementById("fcd-tbody");
        tbody.innerHTML = `<tr><td colspan="11">Cargando...</td></tr>`;

        const filtros = this._getFiltros();

        try {
            const res = await window.PhotinoBridge.send({ action: "controlDocumental.list", page: this._page, pageSize: this._pageSize, ...filtros });

            if (!res.ok) {
                tbody.innerHTML = `<tr><td colspan="11">${res.error || "Error al cargar"}</td></tr>`;
                return;
            }

            this._items = res.data.items || [];
            this._total = res.data.total || 0;
            this._pages = res.data.pages || 1;
            this._page = res.data.page || 1;

            this._poblarFiltrosSelect();
            this._renderTabla();
            this._renderPaginacion();
        } catch {
            tbody.innerHTML = `<tr><td colspan="11">Error de comunicación con el backend</td></tr>`;
        }
    }

    _renderTabla() {
        const tbody = document.getElementById("fcd-tbody");

        if (!this._items.length) {
            tbody.innerHTML = `<tr><td colspan="11">Sin documentos</td></tr>`;
            return;
        }

        tbody.innerHTML = this._items.map(d => `
            <tr>
                <td>${d.codigoBase ?? "-"}</td>
                <td>${d.nombre ?? "-"}</td>
                <td>${d.tipoDocumento ?? "-"}</td>
                <td>${d.area ?? "-"}</td>
                <td>${d.alcanceEmpresa ?? "-"}</td>
                <td>${this._badge(this._labelEstado(d.estado), this._colorEstado(d.estado))}</td>
                <td>${d.versionVigente ?? "-"}</td>
                <td>${this._fecha(d.versionProximaRevision)}</td>
                <td>${d.responsable ?? "-"}</td>
                <td>
                    ${d.tieneAdjuntoVigente
                        ? `<button class="btn-ghost fcd-ver-adjunto-btn" data-version-id="${d.versionVigenteId}">📎 Ver</button>`
                        : ""}
                    ${d.versionVigenteId
                        ? `<button class="btn-ghost fcd-subir-adjunto-btn" data-version-id="${d.versionVigenteId}">${d.tieneAdjuntoVigente ? "Reemplazar" : "Adjuntar"}</button>`
                        : ""}
                </td>
                <td>
                    <button class="btn-ghost fcd-ver-btn" data-id="${d.id}">Ver</button>
                    <button class="btn-danger fcd-eliminar-btn" data-id="${d.id}">Eliminar</button>
                </td>
            </tr>
        `).join("");

        tbody.querySelectorAll(".fcd-ver-btn").forEach(btn => btn.addEventListener("click", () => this._verDetalle(Number(btn.dataset.id))));
        tbody.querySelectorAll(".fcd-eliminar-btn").forEach(btn => btn.addEventListener("click", () => this._eliminarDocumento(Number(btn.dataset.id))));
        tbody.querySelectorAll(".fcd-ver-adjunto-btn").forEach(btn =>
            btn.addEventListener("click", () => this._verAdjunto(Number(btn.dataset.versionId))));
        tbody.querySelectorAll(".fcd-subir-adjunto-btn").forEach(btn =>
            btn.addEventListener("click", () => this._pedirArchivoAdjunto(Number(btn.dataset.versionId))));
    }

    async _eliminarDocumento(id) {
        if (!confirm("¿Eliminar este documento? Ya no aparecerá en el listado.")) return;
        try {
            const res = await window.PhotinoBridge.send({
                action: "controlDocumental.eliminar",
                id,
                actualizadoPor: this._usuarioActual(),
            });
            if (!res.ok) {
                this._showMensaje(res.error || "Error al eliminar el documento", false);
                return;
            }
            this._showMensaje("Documento eliminado", true);
            await this._loadLista();
        } catch {
            this._showMensaje("Error de comunicación con el backend", false);
        }
    }

    _renderPaginacion() {
        const container = document.getElementById("fcd-paginacion");
        if (!container) return;

        let html = "";
        const rango = 2;
        const inicio = Math.max(1, this._page - rango);
        const fin = Math.min(this._pages, this._page + rango);

        if (this._page > 1) html += `<button data-fcd-page="${this._page - 1}">←</button>`;
        if (inicio > 1) {
            html += `<button data-fcd-page="1">1</button>`;
            if (inicio > 2) html += `<button disabled>...</button>`;
        }
        for (let i = inicio; i <= fin; i++) {
            html += `<button data-fcd-page="${i}" class="${i === this._page ? "active" : ""}">${i}</button>`;
        }
        if (fin < this._pages) {
            if (fin < this._pages - 1) html += `<button disabled>...</button>`;
            html += `<button data-fcd-page="${this._pages}">${this._pages}</button>`;
        }
        if (this._page < this._pages) html += `<button data-fcd-page="${this._page + 1}">→</button>`;

        container.innerHTML = html;
    }

    _irPagina(pagina) {
        if (pagina < 1 || pagina > this._pages) return;
        this._page = pagina;
        this._loadLista();
    }

    // ---------- Formulario (Nuevo / Ver / Editar) ----------

    _camposMap() {
        return {
            codigoBase: { id: "fcd-f-codigo-base", tipo: "texto" },
            nombre: { id: "fcd-f-nombre", tipo: "texto" },
            tipoDocumento: { id: "fcd-f-tipo-documento", tipo: "texto" },
            area: { id: "fcd-f-area", tipo: "texto" },
            alcanceEmpresa: { id: "fcd-f-alcance", tipo: "texto" },
            estado: { id: "fcd-f-estado", tipo: "texto" },
            responsable: { id: "fcd-f-responsable", tipo: "texto" },
            ubicacion: { id: "fcd-f-ubicacion", tipo: "texto" },
            observaciones: { id: "fcd-f-observaciones", tipo: "texto" },
        };
    }

    _leerCampo(campo) {
        const raw = document.getElementById(this._camposMap()[campo].id).value;
        return raw.trim();
    }

    _setModoEdicion(editable) {
        Object.values(this._camposMap()).forEach(({ id }) => { document.getElementById(id).disabled = !editable; });
        document.getElementById("fcd-form-editar-btn").style.display = (!editable && this._editingId) ? "inline-block" : "none";
        document.getElementById("fcd-f-guardar-btn").style.display = editable ? "inline-block" : "none";
        document.getElementById("fcd-f-cancelar-btn").style.display = (editable && this._editingId) ? "inline-block" : "none";
    }

    _autocalcularProximaRevision(fechaId, proximaId) {
        const fecha = document.getElementById(fechaId).value;
        const proximaEl = document.getElementById(proximaId);
        if (!fecha || proximaEl.value) return;
        proximaEl.value = window.DateUtils.sumarDias(fecha, 365);
    }

    _abrirNuevo() {
        this._editingId = null;
        this._detalleActual = null;
        document.getElementById("fcd-form-titulo").textContent = "Nuevo Documento";
        document.getElementById("fcd-form-subtitulo").textContent = "Todo documento nace con su versión inicial";
        document.getElementById("fcd-form-error").style.display = "none";

        Object.entries(this._camposMap()).forEach(([, { id }]) => { document.getElementById(id).value = ""; });
        document.getElementById("fcd-f-alcance").value = "FARET";
        document.getElementById("fcd-f-estado").value = "VIGENTE";
        document.getElementById("fcd-f-version").value = "";
        document.getElementById("fcd-f-fecha-actualizacion").value = window.DateUtils.hoyISO();
        document.getElementById("fcd-f-proxima-revision").value = "";
        document.getElementById("fcd-f-adjunto").value = "";

        document.getElementById("fcd-version-inicial-seccion").style.display = "block";
        document.getElementById("fcd-historial-seccion").style.display = "none";

        this._setModoEdicion(true);
        document.getElementById("fcd-form-modal").style.display = "flex";
    }

    async _verDetalle(id) {
        document.getElementById("fcd-form-modal").style.display = "flex";
        document.getElementById("fcd-form-titulo").textContent = "Cargando...";
        document.getElementById("fcd-form-error").style.display = "none";
        document.getElementById("fcd-version-inicial-seccion").style.display = "none";
        document.getElementById("fcd-historial-seccion").style.display = "block";

        try {
            const res = await window.PhotinoBridge.send({ action: "controlDocumental.get", id });
            if (!res.ok) {
                document.getElementById("fcd-form-error").textContent = res.error || "Error al cargar el detalle";
                document.getElementById("fcd-form-error").style.display = "block";
                return;
            }

            this._editingId = id;
            this._detalleActual = res.data;
            document.getElementById("fcd-form-titulo").textContent = `Documento ${res.data.codigoBase ?? ""}`;
            document.getElementById("fcd-form-subtitulo").textContent = `Estado: ${this._labelEstado(res.data.estado)}`;
            this._renderForm(res.data);
            this._renderVersiones(res.data.versiones || []);
            this._setModoEdicion(false);

            document.getElementById("fcd-nv-version").value = "";
            document.getElementById("fcd-nv-fecha-actualizacion").value = "";
            document.getElementById("fcd-nv-proxima-revision").value = "";
            document.getElementById("fcd-nv-adjunto").value = "";
        } catch {
            document.getElementById("fcd-form-error").textContent = "Error de comunicación con el backend";
            document.getElementById("fcd-form-error").style.display = "block";
        }
    }

    _renderForm(doc) {
        Object.entries(this._camposMap()).forEach(([campo, { id }]) => {
            document.getElementById(id).value = doc[campo] ?? "";
        });
    }

    _renderVersiones(versiones) {
        const tbody = document.getElementById("fcd-versiones-tbody");
        if (!versiones.length) {
            tbody.innerHTML = `<tr><td colspan="4">Sin versiones</td></tr>`;
            return;
        }
        tbody.innerHTML = versiones.map(v => `
            <tr>
                <td>${v.version ?? "-"}</td>
                <td>${this._fecha(v.fechaActualizacion)}</td>
                <td>${this._fecha(v.proximaRevision)}</td>
                <td>${v.esVersionVigente ? this._badge("Vigente", "#059669") : "-"}</td>
            </tr>
        `).join("");
    }

    // ---------- Adjuntos ----------

    _pedirArchivoAdjunto(versionId) {
        this._versionIdParaAdjunto = versionId;
        const input = document.getElementById("fcd-adjunto-input");
        input.value = "";
        input.click();
    }

    _leerArchivoComoBase64(file) {
        return new Promise((resolve, reject) => {
            const reader = new FileReader();
            reader.onload = () => resolve((reader.result || "").toString().split(",")[1] || "");
            reader.onerror = reject;
            reader.readAsDataURL(file);
        });
    }

    // Devuelve un mensaje de error si el archivo no es válido, o null si está OK. Se usa tanto al
    // adjuntar suelto desde la tabla principal como al adjuntar en el mismo paso de crear un
    // documento/versión.
    _validarArchivoAdjunto(file) {
        const extensionesValidas = [".pdf", ".doc", ".docx", ".jpg", ".jpeg", ".png", ".webp"];
        const extension = file.name.substring(file.name.lastIndexOf(".")).toLowerCase();
        if (!extensionesValidas.includes(extension)) {
            return `Tipo de archivo no permitido. Formatos válidos: ${extensionesValidas.join(", ")}`;
        }
        const MAX_BYTES = 10 * 1024 * 1024;
        if (file.size > MAX_BYTES) {
            return "El archivo supera el tamaño máximo permitido (10 MB)";
        }
        return null;
    }

    async _subirAdjunto(file) {
        const versionId = this._versionIdParaAdjunto;
        if (!versionId) return;

        const error = this._validarArchivoAdjunto(file);
        if (error) {
            this._showMensaje(error, false);
            return;
        }

        try {
            const contenidoBase64 = await this._leerArchivoComoBase64(file);
            const res = await window.PhotinoBridge.send({
                action: "controlDocumental.adjunto.subir",
                documentoVersionId: versionId,
                nombreArchivo: file.name,
                contenidoBase64,
                subidoPor: this._usuarioActual(),
            });
            if (!res.ok) {
                this._showMensaje(res.error || "Error al subir el adjunto", false);
                return;
            }
            this._showMensaje("Adjunto subido", true);
            await this._loadLista();
        } catch {
            this._showMensaje("Error de comunicación con el backend", false);
        }
    }

    async _verAdjunto(versionId) {
        try {
            const res = await window.PhotinoBridge.send({
                action: "controlDocumental.adjunto.abrir",
                documentoVersionId: versionId,
            });
            if (!res.ok) {
                this._showMensaje(res.error || "Error al abrir el adjunto", false);
                return;
            }
            if (!res.data.previsualizable) {
                this._showMensaje(`Abriendo "${res.data.nombreArchivo}"...`, true);
                return;
            }
            this._mostrarAdjunto(res.data);
        } catch {
            this._showMensaje("Error de comunicación con el backend", false);
        }
    }

    _mostrarAdjunto({ nombreArchivo, tipoMime, contenidoBase64 }) {
        document.getElementById("fcd-adjunto-titulo").textContent = nombreArchivo;
        const contenedor = document.getElementById("fcd-adjunto-contenido");
        const dataUri = `data:${tipoMime};base64,${contenidoBase64}`;

        contenedor.innerHTML = tipoMime.startsWith("image/")
            ? `<img src="${dataUri}" style="max-width:100%; max-height:65vh;">`
            : `<iframe src="${dataUri}" style="width:100%; height:65vh; border:0;"></iframe>`;

        document.getElementById("fcd-adjunto-modal").style.display = "flex";
    }

    _habilitarEdicion() {
        this._setModoEdicion(true);
    }

    _cancelarEdicion() {
        if (this._detalleActual) this._renderForm(this._detalleActual);
        this._setModoEdicion(false);
    }

    _cerrarForm() {
        document.getElementById("fcd-form-modal").style.display = "none";
    }

    async _guardarForm() {
        const errorEl = document.getElementById("fcd-form-error");
        errorEl.style.display = "none";

        const campos = {};
        Object.keys(this._camposMap()).forEach(campo => { campos[campo] = this._leerCampo(campo); });

        if (!campos.codigoBase || !campos.tipoDocumento || !campos.nombre) {
            errorEl.textContent = "Código base, Tipo de documento y Nombre son obligatorios";
            errorEl.style.display = "block";
            return;
        }

        const usuario = this._usuarioActual();
        const btn = document.getElementById("fcd-f-guardar-btn");
        btn.disabled = true;

        try {
            let res;
            if (this._editingId) {
                res = await window.PhotinoBridge.send({ action: "controlDocumental.update", id: this._editingId, actualizadoPor: usuario, ...campos });
            } else {
                const version = document.getElementById("fcd-f-version").value.trim();
                const fechaActualizacion = document.getElementById("fcd-f-fecha-actualizacion").value;
                const proximaRevision = document.getElementById("fcd-f-proxima-revision").value || null;

                if (!version || !fechaActualizacion) {
                    errorEl.textContent = "La versión y la fecha de actualización iniciales son obligatorias";
                    errorEl.style.display = "block";
                    btn.disabled = false;
                    return;
                }

                const archivoAdjunto = document.getElementById("fcd-f-adjunto").files[0];
                let adjuntoNombreArchivo = null;
                let adjuntoContenidoBase64 = null;
                if (archivoAdjunto) {
                    const errorAdjunto = this._validarArchivoAdjunto(archivoAdjunto);
                    if (errorAdjunto) {
                        errorEl.textContent = errorAdjunto;
                        errorEl.style.display = "block";
                        btn.disabled = false;
                        return;
                    }
                    adjuntoNombreArchivo = archivoAdjunto.name;
                    adjuntoContenidoBase64 = await this._leerArchivoComoBase64(archivoAdjunto);
                }

                res = await window.PhotinoBridge.send({
                    action: "controlDocumental.create",
                    creadoPor: usuario,
                    ...campos,
                    version,
                    fechaActualizacion,
                    proximaRevision,
                    adjuntoNombreArchivo,
                    adjuntoContenidoBase64,
                });
            }

            if (!res.ok) {
                errorEl.textContent = res.error || "Error al guardar el documento";
                errorEl.style.display = "block";
                return;
            }

            this._cerrarForm();
            this._showMensaje(this._editingId ? "Documento actualizado" : "Documento creado", true);
            await this._loadLista();
        } catch {
            errorEl.textContent = "Error de comunicación con el backend";
            errorEl.style.display = "block";
        } finally {
            btn.disabled = false;
        }
    }

    async _agregarVersion() {
        if (!this._editingId) return;
        const errorEl = document.getElementById("fcd-form-error");
        errorEl.style.display = "none";

        const version = document.getElementById("fcd-nv-version").value.trim();
        const fechaActualizacion = document.getElementById("fcd-nv-fecha-actualizacion").value;
        const proximaRevision = document.getElementById("fcd-nv-proxima-revision").value || null;

        if (!version || !fechaActualizacion) {
            errorEl.textContent = "La nueva versión y su fecha de actualización son obligatorias";
            errorEl.style.display = "block";
            return;
        }
        if (!confirm(`¿Agregar la versión "${version}" como la nueva versión vigente?`)) return;

        const archivoAdjunto = document.getElementById("fcd-nv-adjunto").files[0];
        let adjuntoNombreArchivo = null;
        let adjuntoContenidoBase64 = null;
        if (archivoAdjunto) {
            const errorAdjunto = this._validarArchivoAdjunto(archivoAdjunto);
            if (errorAdjunto) {
                errorEl.textContent = errorAdjunto;
                errorEl.style.display = "block";
                return;
            }
            adjuntoNombreArchivo = archivoAdjunto.name;
            adjuntoContenidoBase64 = await this._leerArchivoComoBase64(archivoAdjunto);
        }

        const btn = document.getElementById("fcd-nv-agregar-btn");
        btn.disabled = true;
        try {
            const res = await window.PhotinoBridge.send({
                action: "controlDocumental.version.crear",
                documentoId: this._editingId,
                version,
                fechaActualizacion,
                proximaRevision,
                creadoPor: this._usuarioActual(),
                adjuntoNombreArchivo,
                adjuntoContenidoBase64,
            });
            if (!res.ok) {
                errorEl.textContent = res.error || "Error al agregar la versión";
                errorEl.style.display = "block";
                return;
            }
            await this._verDetalle(this._editingId);
            this._showMensaje("Versión agregada", true);
            await this._loadLista();
        } catch {
            errorEl.textContent = "Error de comunicación con el backend";
            errorEl.style.display = "block";
        } finally {
            btn.disabled = false;
        }
    }

    _showMensaje(texto, ok) {
        const el = document.getElementById("fcd-mensaje");
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
        return `<span class="fcd-badge" style="background:${color}1F;color:${color};">${texto ?? "-"}</span>`;
    }

    _labelEstado(estado) {
        const map = { VIGENTE: "Vigente", EN_REVISION: "En revisión", OBSOLETO: "Obsoleto" };
        return map[estado] || estado || "-";
    }

    _colorEstado(estado) {
        switch (estado) {
            case "VIGENTE": return "#059669";
            case "EN_REVISION": return "#D97706";
            case "OBSOLETO": return "#64748B";
            default: return "#64748B";
        }
    }

    // ---------- Exportar ----------

    async _exportar() {
        const filtros = this._getFiltros();
        try {
            const res = await window.PhotinoBridge.send({ action: "controlDocumental.list", page: 1, pageSize: 999999, ...filtros });
            const items = res.ok && Array.isArray(res.data.items) ? res.data.items : [];
            this._exportarDesdeItems(items);
        } catch {
            this._showMensaje("Error al exportar", false);
        }
    }

    _exportarDesdeItems(items) {
        const tabla = document.createElement("table");
        tabla.id = "fcd-tabla-export-temp";
        tabla.style.position = "absolute";
        tabla.style.left = "-99999px";
        tabla.style.top = "0";

        tabla.innerHTML = `
            <thead>
                <tr>
                    <th>Código base</th><th>Nombre</th><th>Tipo</th><th>Área</th><th>Alcance</th>
                    <th>Estado</th><th>Versión vigente</th><th>Próxima revisión</th><th>Responsable</th>
                </tr>
            </thead>
            <tbody>
                ${items.map(d => `
                    <tr>
                        <td>${d.codigoBase ?? "-"}</td>
                        <td>${d.nombre ?? "-"}</td>
                        <td>${d.tipoDocumento ?? "-"}</td>
                        <td>${d.area ?? "-"}</td>
                        <td>${d.alcanceEmpresa ?? "-"}</td>
                        <td>${this._labelEstado(d.estado)}</td>
                        <td>${d.versionVigente ?? "-"}</td>
                        <td>${this._fecha(d.versionProximaRevision)}</td>
                        <td>${d.responsable ?? "-"}</td>
                    </tr>
                `).join("")}
            </tbody>
        `;

        document.body.appendChild(tabla);
        window.ExcelExporter.exportTable({
            tableSelector: "#fcd-tabla-export-temp",
            fileName: `control_documental_faret_${Date.now()}.xlsx`,
            sheetName: "Control Documental",
            title: "QCC - Control Documental (Faret)",
        });
        tabla.remove();
    }
};
