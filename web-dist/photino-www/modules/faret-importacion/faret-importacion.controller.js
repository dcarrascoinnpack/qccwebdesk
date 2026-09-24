window.FaretImportacionController = class FaretImportacionController {

    init() {
        console.log("FaretImportacionController iniciado");

        this._selectedFile = null; // { name, base64 }
        this._loteId = null;

        this._fileInput = document.getElementById("fi-file-input");

        document.getElementById("fi-select-btn")
            ?.addEventListener("click", () => this._fileInput.click());

        this._fileInput?.addEventListener("change", (e) => this._onFileSelected(e));

        document.getElementById("fi-validar-btn")
            ?.addEventListener("click", () => this._validar());

        document.getElementById("fi-confirmar-btn")
            ?.addEventListener("click", () => this._confirmar());

        document.getElementById("fi-historial-refresh-btn")
            ?.addEventListener("click", () => this._loadHistorial());

        document.getElementById("fi-historial-exportar-btn")
            ?.addEventListener("click", () => this._exportarHistorial());

        this._filasFijas = window.TableUtils.init(
            document.getElementById("fi-historial-tabla"),
            document.getElementById("fi-historial-filas-fijadas")
        );

        this._loadHistorial();
    }

    destroy() {
        console.log("FaretImportacionController destruido");
    }

    _onFileSelected(e) {
        const file = e.target.files && e.target.files[0];
        const nameEl = document.getElementById("fi-file-name");
        const validarBtn = document.getElementById("fi-validar-btn");

        this._selectedFile = null;
        this._resetResumen();

        if (!file) {
            nameEl.textContent = "Ningún archivo seleccionado";
            validarBtn.disabled = true;
            return;
        }

        if (!file.name.toLowerCase().endsWith(".xlsx")) {
            nameEl.textContent = "Formato inválido: solo se admite .xlsx";
            validarBtn.disabled = true;
            return;
        }

        const reader = new FileReader();
        reader.onload = () => {
            const base64 = String(reader.result).split(",")[1] || "";
            this._selectedFile = { name: file.name, base64 };
            nameEl.textContent = file.name;
            validarBtn.disabled = false;
        };
        reader.onerror = () => {
            nameEl.textContent = "Error leyendo el archivo";
            validarBtn.disabled = true;
        };
        reader.readAsDataURL(file);
    }

    async _validar() {
        if (!this._selectedFile) return;

        const loadingEl = document.getElementById("fi-validar-loading");
        const errorEl = document.getElementById("fi-validar-error");
        const validarBtn = document.getElementById("fi-validar-btn");

        this._resetResumen();
        loadingEl.style.display = "block";
        errorEl.style.display = "none";
        validarBtn.disabled = true;

        try {
            const res = await window.PhotinoBridge.send({
                action: "faret.importacion.validar",
                fileName: this._selectedFile.name,
                base64: this._selectedFile.base64,
            });

            if (!res.ok) {
                errorEl.textContent = res.error || "Error al validar el archivo";
                errorEl.style.display = "block";
                return;
            }

            this._loteId = res.data.loteId;
            this._renderResumen(res.data);
        } catch {
            errorEl.textContent = "Error de comunicación con el backend";
            errorEl.style.display = "block";
        } finally {
            loadingEl.style.display = "none";
            validarBtn.disabled = false;
        }
    }

    _renderResumen(data) {
        const card = document.getElementById("fi-resumen-card");
        const confirmarBtn = document.getElementById("fi-confirmar-btn");

        card.style.display = "block";
        document.getElementById("fi-resumen-archivo").textContent = data.nombreArchivo || "-";
        document.getElementById("fi-total-filas").textContent = data.totalFilas ?? 0;
        document.getElementById("fi-filas-validas").textContent = data.filasValidas ?? 0;
        document.getElementById("fi-filas-error").textContent = data.filasConError ?? 0;

        confirmarBtn.disabled = !(data.filasValidas > 0);

        const tbody = document.getElementById("fi-errores-tbody");
        const errores = Array.isArray(data.errores) ? data.errores : [];

        if (!errores.length) {
            tbody.innerHTML = `<tr><td colspan="2" class="faret-empty">Sin errores</td></tr>`;
            return;
        }

        tbody.innerHTML = errores.map(e => `
            <tr>
                <td>${e.fila ?? "-"}</td>
                <td>${(e.errores || []).join("<br>")}</td>
            </tr>
        `).join("");
    }

    _resetResumen() {
        this._loteId = null;
        document.getElementById("fi-resumen-card").style.display = "none";
        document.getElementById("fi-confirmar-ok").style.display = "none";
        document.getElementById("fi-confirmar-error").style.display = "none";
        document.getElementById("fi-confirmar-btn").disabled = true;
    }

    async _confirmar() {
        if (!this._loteId) return;

        const loadingEl = document.getElementById("fi-confirmar-loading");
        const errorEl = document.getElementById("fi-confirmar-error");
        const okEl = document.getElementById("fi-confirmar-ok");
        const confirmarBtn = document.getElementById("fi-confirmar-btn");

        loadingEl.style.display = "block";
        errorEl.style.display = "none";
        okEl.style.display = "none";
        confirmarBtn.disabled = true;

        try {
            const res = await window.PhotinoBridge.send({
                action: "faret.importacion.confirmar",
                loteId: this._loteId,
            });

            if (!res.ok) {
                errorEl.textContent = res.error || "Error al confirmar la importación";
                errorEl.style.display = "block";
                confirmarBtn.disabled = false;
                return;
            }

            okEl.textContent = `Importación confirmada (ID ${res.data.importacionId}). ` +
                `${res.data.filasInsertadas} filas insertadas.`;
            okEl.style.display = "block";

            this._loteId = null;
            this._selectedFile = null;
            this._fileInput.value = "";
            document.getElementById("fi-file-name").textContent = "Ningún archivo seleccionado";
            document.getElementById("fi-validar-btn").disabled = true;

            this._loadHistorial();
        } catch {
            errorEl.textContent = "Error de comunicación con el backend";
            errorEl.style.display = "block";
            confirmarBtn.disabled = false;
        } finally {
            loadingEl.style.display = "none";
        }
    }

    async _loadHistorial() {
        const contenedor = document.getElementById("fi-historial-tabla")?.closest(".table-container");
        await window.TableUtils.preservarScroll(contenedor, () => this._loadHistorialInterna());
    }

    async _loadHistorialInterna() {
        const loadingEl = document.getElementById("fi-historial-loading");
        const errorEl = document.getElementById("fi-historial-error");
        const tbody = document.getElementById("fi-historial-tbody");

        loadingEl.style.display = "block";
        errorEl.style.display = "none";

        try {
            const res = await window.PhotinoBridge.send({ action: "faret.importacion.list" });

            if (!res.ok) {
                errorEl.textContent = res.error || "Error al cargar el historial";
                errorEl.style.display = "block";
                tbody.innerHTML = `<tr><td colspan="9" class="faret-empty">Sin datos</td></tr>`;
                return;
            }

            const lista = Array.isArray(res.data) ? res.data : [];
            this._renderHistorial(lista);
        } catch {
            errorEl.textContent = "Error de comunicación con el backend";
            errorEl.style.display = "block";
            tbody.innerHTML = `<tr><td colspan="9" class="faret-empty">Sin datos</td></tr>`;
        } finally {
            loadingEl.style.display = "none";
        }
    }

    _renderHistorial(lista) {
        const tbody = document.getElementById("fi-historial-tbody");

        if (!lista.length) {
            tbody.innerHTML = `<tr><td colspan="9" class="faret-empty">Sin importaciones registradas</td></tr>`;
            this._filasFijas?.refrescar();
            return;
        }

        tbody.innerHTML = lista.map(i => `
            <tr data-id="${i.id}">
                <td>${i.id ?? "-"}</td>
                <td>${i.nombreArchivo ?? "-"}</td>
                <td>${i.usuarioNombre ?? "-"}</td>
                <td>${i.totalFilas ?? 0}</td>
                <td>${i.filasValidas ?? 0}</td>
                <td>${i.filasError ?? 0}</td>
                <td>${i.estado ?? "-"}</td>
                <td>${i.fecha ? new Date(i.fecha).toLocaleString("es-CL") : "-"}</td>
            </tr>
        `).join("");

        this._filasFijas?.refrescar();
    }

    _exportarHistorial() {
        window.ExcelExporter.exportTable({
            tableSelector: "#fi-historial-tabla",
            fileName: `faret_historial_importaciones_${Date.now()}.xlsx`,
            sheetName: "Historial",
            title: "QCC Faret - Historial de Importaciones"
        });
    }
};
