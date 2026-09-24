// Réplica del módulo INNPACK (producto-terminado.controller.js) — misma tabla compartida
// registros_producto_terminado, mismas acciones productoTerminado.* del backend (sin pasar por
// FaretHandler/FaretApiClient, igual patrón que faret-control-documental.controller.js), única
// diferencia real es empresa: "FARET" en cada payload e ids con prefijo fpt/Faret para no chocar
// con el CSS/DOM del módulo INNPACK.
if (!window.FaretProductoTerminadoController) {
  class FaretProductoTerminadoController {
    constructor() {
      this.filtros = null
      this.loading = false
      this._clickHandler = null
      this._keyHandler = null
      this.charts = []

      this.items = []
      this.page = 1
      this.limit = 50
      this.total = 0
      this.pages = 1
    }

    async init() {
      console.log("INIT FARET PRODUCTO TERMINADO")

      this.bindEvents()
      this.initDatePickers()

      await this.cargarFiltros()
      await this.cargarDatos()
    }

    initDatePickers() {
      if (!window.flatpickr) return

      flatpickr("#fptFechaDesde", {
        dateFormat: "Y-m-d",
        altInput: true,
        altFormat: "d-m-Y",
        allowInput: true
      })

      flatpickr("#fptFechaHasta", {
        dateFormat: "Y-m-d",
        altInput: true,
        altFormat: "d-m-Y",
        allowInput: true
      })
    }

    bindEvents() {
      if (this._clickHandler) return

      this._clickHandler = (e) => {
        if (e.target.id === "btnActualizarFaretProductoTerminado" || e.target.id === "btnFiltrarFaretProductoTerminado") {
          this.cargarDatos()
          return
        }

        if (e.target.id === "btnExportarFaretProductoTerminado") {
          this.exportarProductoTerminado()
          return
        }

        if (e.target.id === "btnLimpiarFaretProductoTerminado") {
          const desde = document.getElementById("fptFechaDesde")
          const hasta = document.getElementById("fptFechaHasta")

          if (desde?._flatpickr) desde._flatpickr.clear()
          if (hasta?._flatpickr) hasta._flatpickr.clear()

          document.getElementById("fptNp").value = ""
          document.getElementById("fptCodigoProducto").value = ""
          document.getElementById("fptProceso").value = ""
          document.getElementById("fptMaquina").value = ""
          document.getElementById("fptTurno").value = ""
          document.getElementById("fptInspector").value = ""
          document.getElementById("fptResultado").value = ""
          document.getElementById("fptOrigen").value = ""

          this.cargarDatos()
          return
        }

        if (e.target.dataset.fptPage) {
          this.page = Number(e.target.dataset.fptPage)
          this.cargarTabla()
          return
        }

        if (e.target.classList.contains("btn-ver-detalle-fpt")) {
          const id = Number(e.target.dataset.id)
          this.abrirDetalle(id)
          return
        }

        if (e.target.classList.contains("btn-eliminar-fpt")) {
          const id = Number(e.target.dataset.id)
          this.eliminarInspeccion(id)
          return
        }

        if (e.target.id === "btnCerrarDetalleFaretProductoTerminado" || e.target.id === "modalDetalleFaretProductoTerminado") {
          this.cerrarDetalle()
          return
        }

        if (e.target.classList.contains("btn-ver-foto-fpt")) {
          this.mostrarImagen(e.target.dataset.url)
          return
        }

        if (e.target.id === "btnCerrarImagenFaretProductoTerminado" || e.target.id === "modalImagenFaretProductoTerminado") {
          this.cerrarImagen()
          return
        }

        if (e.target.id === "btnEditarFechaFpt") {
          this._abrirEditarFechaFpt()
          return
        }
        if (e.target.id === "btnCancelarFechaFpt") {
          this._cerrarEditarFechaFpt()
          return
        }
        if (e.target.id === "btnGuardarFechaFpt") {
          this._guardarFechaFpt()
          return
        }
      }

      document.addEventListener("click", this._clickHandler)

      if (!this._keyHandler) {
        this._keyHandler = (e) => {
          if (e.key !== "Escape") return

          if (document.getElementById("modalImagenFaretProductoTerminado")) {
            this.cerrarImagen()
          } else if (document.getElementById("modalDetalleFaretProductoTerminado")) {
            this.cerrarDetalle()
          }
        }

        document.addEventListener("keydown", this._keyHandler)
      }
    }

    async cargarFiltros() {
      try {
        const res = await window.PhotinoBridge.send({
          action: "productoTerminado.filtros",
          data: { empresa: "FARET" }
        })

        if (!res || res.ok === false) {
          throw new Error(res?.error || "Error cargando filtros")
        }

        this.filtros = res.data || {}

        const maquinas = this.filtros.maquinas || []
        const inspectores = this.filtros.inspectores || []
        const origenes = this.filtros.origenes || []

        const cboMaquina = document.getElementById("fptMaquina")
        const cboInspector = document.getElementById("fptInspector")
        const cboOrigen = document.getElementById("fptOrigen")

        if (cboMaquina) {
          cboMaquina.innerHTML =
            `<option value="">Todas</option>` +
            maquinas.map(m => `<option value="${m}">${m}</option>`).join("")
        }

        if (cboInspector) {
          cboInspector.innerHTML =
            `<option value="">Todos</option>` +
            inspectores.map(x => `<option value="${x.id}">${x.nombre}</option>`).join("")
        }

        if (cboOrigen) {
          cboOrigen.innerHTML =
            `<option value="">Todos</option>` +
            origenes.map(x => `<option value="${x.id}">${x.nombre}</option>`).join("")
        }
      } catch (err) {
        console.error("FARET PRODUCTO TERMINADO — ERROR FILTROS:", err)
        this.mostrarError(`No se pudieron cargar los catálogos de filtros: ${err.message}`)
      }
    }

    getFiltrosActuales() {
      return {
        empresa: "FARET",
        fechaDesde: this.getVal("fptFechaDesde"),
        fechaHasta: this.getVal("fptFechaHasta"),
        np: this.getVal("fptNp"),
        codigoProducto: this.getVal("fptCodigoProducto"),
        proceso: this.getVal("fptProceso"),
        maquina: this.getVal("fptMaquina"),
        turno: this.getVal("fptTurno"),
        inspectorId: this.getVal("fptInspector"),
        resultado: this.getVal("fptResultado"),
        origenId: this.getVal("fptOrigen")
      }
    }

    async cargarDatos() {
      if (this.loading) return

      this.loading = true
      this.ocultarError()
      this.page = 1

      try {
        const res = await window.PhotinoBridge.send({
          action: "productoTerminado.resumen",
          data: this.getFiltrosActuales()
        })

        console.log("📊 FARET PRODUCTO TERMINADO — resumen:", res)

        if (!res || res.ok === false) {
          throw new Error(res?.error || "Error cargando el resumen")
        }

        this.data = res.data || {}
        this.renderKpis()
        this.renderCharts()
      } catch (err) {
        console.error("FARET PRODUCTO TERMINADO — ERROR RESUMEN:", err)
        this.mostrarError(`Error cargando KPIs: ${err.message}`)
      } finally {
        this.loading = false
      }

      await this.cargarTabla()
    }

    async cargarTabla() {
      const tbody = document.getElementById("tbodyFaretProductoTerminado")
      if (tbody) tbody.innerHTML = `<tr><td colspan="12">Cargando...</td></tr>`

      try {
        const res = await window.PhotinoBridge.send({
          action: "productoTerminado.list",
          data: { ...this.getFiltrosActuales(), page: this.page, limit: this.limit }
        })

        if (!res || res.ok === false) {
          throw new Error(res?.error || "Error cargando la tabla")
        }

        this.items = res.data?.items || []
        this.total = res.data?.total || 0
        this.page = res.data?.page || this.page
        this.limit = res.data?.limit || this.limit
        this.pages = this.limit > 0 ? Math.max(1, Math.ceil(this.total / this.limit)) : 1

        this.renderTabla()
        this.renderPaginacion()
      } catch (err) {
        console.error("FARET PRODUCTO TERMINADO — ERROR TABLA:", err)
        if (tbody) tbody.innerHTML = `<tr><td colspan="12">Error: ${this.escape(err.message)}</td></tr>`
      }
    }

    renderTabla() {
      const tbody = document.getElementById("tbodyFaretProductoTerminado")
      if (!tbody) return

      if (this.items.length === 0) {
        tbody.innerHTML = `<tr><td colspan="12">Sin registros</td></tr>`
        return
      }

      tbody.innerHTML = this.items.map(r => `
        <tr data-id="${r.id}">
          <td>${r.id}</td>
          <td>${this.escape(r.fechaRegistro)} ${this.escape(r.horaRegistro)}</td>
          <td>${this.escape(r.inspector)}</td>
          <td>${this.escape(r.np)}</td>
          <td>${this.escape(r.cliente)}</td>
          <td>${this.escape(r.codigoProducto)}</td>
          <td>${this.escape(r.proceso)}</td>
          <td>${this.numero(r.cantidadLote)}</td>
          <td>${this.escape(r.maquina)}</td>
          <td>${this.escape(r.turno)}</td>
          <td>${this.renderResultado(r.resultado)}</td>
          <td>
            <button class="btn-secondary btn-ver-detalle-fpt" data-id="${r.id}">Ver Detalle</button>
            <button class="btn-danger btn-eliminar-fpt" data-id="${r.id}">Eliminar</button>
          </td>
        </tr>
      `).join("")
    }

    async eliminarInspeccion(id) {
      if (!id) return

      if (!confirm("¿Eliminar esta inspección? Esta acción no se puede deshacer desde la pantalla.")) {
        return
      }

      try {
        const res = await window.PhotinoBridge.send({
          action: "productoTerminado.eliminar",
          data: { empresa: "FARET", id }
        })

        if (!res || res.ok === false) {
          throw new Error(res?.error || "Error al eliminar")
        }

        await this.cargarDatos()
      } catch (err) {
        console.error("FARET PRODUCTO TERMINADO — ERROR ELIMINAR:", err)
        alert(`No se pudo eliminar: ${err.message}`)
      }
    }

    renderPaginacion() {
      const container = document.getElementById("paginacionFaretProductoTerminado")
      if (!container) return

      let html = ""
      const rango = 2
      const inicio = Math.max(1, this.page - rango)
      const fin = Math.min(this.pages, this.page + rango)

      if (this.page > 1) {
        html += `<button data-fpt-page="${this.page - 1}">←</button>`
      }

      if (inicio > 1) {
        html += `<button data-fpt-page="1">1</button>`
        if (inicio > 2) html += `<button disabled>...</button>`
      }

      for (let i = inicio; i <= fin; i++) {
        html += `
          <button
            data-fpt-page="${i}"
            class="${i === this.page ? "active" : ""}">
            ${i}
          </button>
        `
      }

      if (fin < this.pages) {
        if (fin < this.pages - 1) html += `<button disabled>...</button>`
        html += `<button data-fpt-page="${this.pages}">${this.pages}</button>`
      }

      if (this.page < this.pages) {
        html += `<button data-fpt-page="${this.page + 1}">→</button>`
      }

      container.innerHTML = html
    }

    // Exportación Excel: siempre trae el detalle COMPLETO filtrado (no la página visible) desde
    // productoTerminado.exportarDetalle — una fila por inspección/hallazgo/defecto, conservando
    // ID inspección + correlativo de hallazgo + defecto + origen (trazabilidad pedida).
    async exportarProductoTerminado() {
      try {
        const res = await window.PhotinoBridge.send({
          action: "productoTerminado.exportarDetalle",
          data: this.getFiltrosActuales()
        })

        if (!res || res.ok === false) {
          throw new Error(res?.error || "Error exportando")
        }

        const rows = res.data || []

        if (rows.length === 0) {
          alert("No hay datos para exportar con los filtros actuales.")
          return
        }

        const tabla = this.construirTablaExportTemp(rows)

        window.ExcelExporter.exportTable({
          tableSelector: "#tablaFaretProductoTerminadoExportTemp",
          fileName: `qcc_faret_producto_terminado_${Date.now()}.xlsx`,
          sheetName: "Producto Terminado",
          title: "QCC - Producto Terminado (Faret)"
        })

        tabla.remove()
      } catch (err) {
        console.error("FARET PRODUCTO TERMINADO — ERROR EXPORT:", err)
        alert(`No se pudo exportar: ${err.message}`)
      }
    }

    construirTablaExportTemp(rows) {
      const tabla = document.createElement("table")
      tabla.id = "tablaFaretProductoTerminadoExportTemp"
      tabla.style.position = "absolute"
      tabla.style.left = "-99999px"
      tabla.style.top = "0"

      tabla.innerHTML = `
        <thead>
          <tr>
            <th>ID Inspección</th>
            <th>Fecha</th>
            <th>Inspector</th>
            <th>NP</th>
            <th>Cliente</th>
            <th>Ítem</th>
            <th>Producto</th>
            <th>Proceso</th>
            <th>Cant. Lote</th>
            <th>Máquina</th>
            <th>Nivel</th>
            <th>AQL</th>
            <th>Letra Código</th>
            <th>Tamaño Muestra</th>
            <th>Ac</th>
            <th>Re</th>
            <th>Unidades NC</th>
            <th>Defectos Totales</th>
            <th>Resultado</th>
            <th>Hallazgo #</th>
            <th>Tipo de Defecto</th>
            <th>Origen del Problema</th>
          </tr>
        </thead>
        <tbody>
          ${rows.map(r => `
            <tr>
              <td>${this.escape(r.inspeccionId)}</td>
              <td>${this.escape(r.fecha)}</td>
              <td>${this.escape(r.inspector)}</td>
              <td>${this.escape(r.np)}</td>
              <td>${this.escape(r.cliente)}</td>
              <td>${this.escape(r.codigoProducto)}</td>
              <td>${this.escape(r.descripcionProducto)}</td>
              <td>${this.escape(r.proceso)}</td>
              <td>${this.escape(r.cantidadLote)}</td>
              <td>${this.escape(r.maquina)}</td>
              <td>${this.escape(r.nivelInspeccion)}</td>
              <td>${this.escape(r.aql)}</td>
              <td>${this.escape(r.letraCodigo)}</td>
              <td>${this.escape(r.tamanoMuestra)}</td>
              <td>${r.ac ?? "-"}</td>
              <td>${r.re ?? "-"}</td>
              <td>${this.escape(r.unidadesNoConformes)}</td>
              <td>${this.escape(r.defectosTotales)}</td>
              <td>${this.escape(r.resultado)}</td>
              <td>${r.hallazgoCorrelativo ?? "-"}</td>
              <td>${this.escape(r.defecto || "-")}</td>
              <td>${this.escape(r.origen || "-")}</td>
            </tr>
          `).join("")}
        </tbody>
      `

      document.body.appendChild(tabla)
      return tabla
    }

    renderResultado(value) {
      const v = String(value || "").toUpperCase()

      if (v === "CONFORME") return `<span style="color:#16a34a; font-weight:700;">Conforme</span>`
      if (v === "NO CONFORME") return `<span style="color:#dc2626; font-weight:700;">No Conforme</span>`

      return this.escape(value || "-")
    }

    async abrirDetalle(id) {
      if (!id) return

      try {
        const res = await window.PhotinoBridge.send({
          action: "productoTerminado.detalle",
          data: { empresa: "FARET", id }
        })

        if (!res || res.ok === false) {
          throw new Error(res?.error || "Error cargando el detalle")
        }

        this.renderDetalleModal(res.data)
      } catch (err) {
        console.error("FARET PRODUCTO TERMINADO — ERROR DETALLE:", err)
        alert(`No se pudo cargar el detalle: ${err.message}`)
      }
    }

    renderDetalleModal(d) {
      this.cerrarDetalle()
      this._detalleActual = d

      const modal = document.createElement("div")
      modal.id = "modalDetalleFaretProductoTerminado"
      modal.style.position = "fixed"
      modal.style.left = "0"
      modal.style.top = "0"
      modal.style.width = "100%"
      modal.style.height = "100%"
      modal.style.background = "rgba(15, 23, 42, 0.75)"
      modal.style.zIndex = "9999"
      modal.style.display = "flex"
      modal.style.alignItems = "center"
      modal.style.justifyContent = "center"
      modal.style.padding = "24px"

      const pallets = (d.pallets || []).join(", ") || "-"

      const hallazgosHtml = (d.hallazgos || []).length === 0
        ? `<div style="color:#64748b;">Sin hallazgos registrados.</div>`
        : d.hallazgos.map(h => `
            <div style="border:1px solid #e2e8f0; border-radius:8px; padding:12px; margin-bottom:10px;">
              <div style="font-weight:700; margin-bottom:6px;">Hallazgo ${this.numero(h.correlativo)}</div>
              <div><strong>Defectos:</strong> ${this.escape((h.defectos || []).map(x => x.nombre).join(", ") || "-")}</div>
              <div><strong>Origen:</strong> ${this.escape(h.origen || "-")}</div>
              <div><strong>Observación:</strong> ${this.escape(h.observacion || "-")}</div>
              ${h.fotoRuta
            ? `<button class="btn-secondary btn-ver-foto-fpt" data-url="${this.escape(h.fotoRuta)}" style="margin-top:8px;">Ver Foto</button>`
            : ""}
            </div>
          `).join("")

      modal.innerHTML = `
        <div style="
          background:#ffffff;
          border-radius:12px;
          max-width:720px;
          width:100%;
          max-height:90%;
          overflow-y:auto;
          padding:20px;
          box-shadow:0 20px 60px rgba(0,0,0,0.35);
          position:relative;
        ">
          <div style="display:flex; justify-content:space-between; align-items:center; gap:12px; margin-bottom:16px;">
            <strong>Detalle de inspección #${d.id}</strong>
            <button id="btnCerrarDetalleFaretProductoTerminado" class="btn-secondary" type="button">Cerrar</button>
          </div>

          <div style="font-weight:700; margin-bottom:8px;">Información del lote</div>
          <div style="display:grid; grid-template-columns: repeat(2, 1fr); gap:8px 20px; margin-bottom:16px; font-size:13px;">
            <div>
              <strong>Fecha/Hora:</strong> ${this.escape(d.fechaRegistro)} ${this.escape(d.horaRegistro)}
              <button type="button" id="btnEditarFechaFpt" class="btn-secondary" style="margin-left:8px; padding:2px 8px;">Editar fecha</button>
              ${d.fechaRegistroModificadaPor ? `<div style="color:#64748b; font-size:11px;">Modificada por ${this.escape(d.fechaRegistroModificadaPor)} el ${this.escape(d.fechaRegistroFechaModificacion)}</div>` : ""}
            </div>
            <div><strong>Inspector:</strong> ${this.escape(d.inspector)}</div>
            <div><strong>NP:</strong> ${this.escape(d.np)}</div>
            <div><strong>Cliente:</strong> ${this.escape(d.cliente)}</div>
            <div><strong>Ítem:</strong> ${this.escape(d.codigoProducto)}</div>
            <div><strong>Producto:</strong> ${this.escape(d.descripcionProducto)}</div>
            <div><strong>Proceso:</strong> ${this.escape(d.proceso)}</div>
            <div><strong>Pallets inspeccionados:</strong> ${this.escape(pallets)}</div>
            <div><strong>Cant. lote:</strong> ${this.numero(d.cantidadLote)}</div>
            <div><strong>Cant. pallets:</strong> ${this.numero(d.cantidadPallets)}</div>
            <div><strong>Cant. cajas/bins:</strong> ${this.numero(d.cantidadCajasBins)}</div>
            <div><strong>Máquina:</strong> ${this.escape(d.maquina)}</div>
            <div><strong>Turno:</strong> ${this.escape(d.turno)}</div>
          </div>

          <div style="font-weight:700; margin-bottom:8px;">Plan de muestreo (NCh44:2007)</div>
          <div style="display:grid; grid-template-columns: repeat(3, 1fr); gap:8px 20px; margin-bottom:16px; font-size:13px;">
            <div><strong>Nivel:</strong> ${this.escape(d.nivelInspeccion)}</div>
            <div><strong>AQL:</strong> ${this.numero(d.aql)}</div>
            <div><strong>Letra código:</strong> ${this.escape(d.letraCodigo)}</div>
            <div><strong>Tamaño muestra:</strong> ${this.numero(d.tamanoMuestra)}</div>
            <div><strong>Ac:</strong> ${d.ac ?? "-"}</div>
            <div><strong>Re:</strong> ${d.re ?? "-"}</div>
            ${d.inspeccion100
          ? `<div style="grid-column: span 3; color:#b45309;">Inspección 100% — no aplica plan de muestreo.</div>`
          : ""}
          </div>

          <div style="font-weight:700; margin-bottom:8px;">Resultado</div>
          <div style="display:grid; grid-template-columns: repeat(3, 1fr); gap:8px 20px; margin-bottom:16px; font-size:13px;">
            <div><strong>Unidades NC:</strong> ${this.numero(d.unidadesNoConformes)}</div>
            <div><strong>Defectos totales:</strong> ${this.numero(d.defectosTotales)}</div>
            <div><strong>Resultado:</strong> ${this.renderResultado(d.resultado)}</div>
          </div>

          <div style="font-weight:700; margin-bottom:8px;">Hallazgos</div>
          ${hallazgosHtml}

          <div id="bloqueEditarFechaFpt" style="display:none; margin-top:16px; padding:12px; border:1px solid #e2e8f0; border-radius:8px;">
            <label style="display:block; margin-bottom:6px;">Nueva fecha</label>
            <input type="date" id="inputEditarFechaFpt" style="margin-right:8px;">
            <input type="time" id="inputEditarHoraFpt" style="margin-right:12px;">
            <button type="button" id="btnCancelarFechaFpt" class="btn-secondary">Cancelar</button>
            <button type="button" id="btnGuardarFechaFpt" class="btn-primary">Guardar fecha</button>
          </div>
        </div>
      `

      document.body.appendChild(modal)
    }

    cerrarDetalle() {
      const modal = document.getElementById("modalDetalleFaretProductoTerminado")
      if (modal) modal.remove()
    }

    // Punto 7 del REG-LAB-04: solo corrige fecha_registro/hora_registro con auditoría — el resto
    // de la inspección (mediciones, plan de muestreo, hallazgos) sigue siendo de solo lectura.
    _abrirEditarFechaFpt() {
      const d = this._detalleActual
      if (!d) return
      const [dia, mes, anio] = (d.fechaRegistro || "").split("-")
      document.getElementById("inputEditarFechaFpt").value = anio ? `${anio}-${mes}-${dia}` : ""
      document.getElementById("inputEditarHoraFpt").value = d.horaRegistro || ""
      document.getElementById("bloqueEditarFechaFpt").style.display = "block"
    }

    _cerrarEditarFechaFpt() {
      const bloque = document.getElementById("bloqueEditarFechaFpt")
      if (bloque) bloque.style.display = "none"
    }

    async _guardarFechaFpt() {
      const fechaRegistro = document.getElementById("inputEditarFechaFpt").value
      const horaRegistro = document.getElementById("inputEditarHoraFpt").value
      if (!fechaRegistro) return alert("Ingresa la nueva fecha")

      const usuarioNombre = sessionStorage.getItem("faretNombreUsuario") || sessionStorage.getItem("nombreUsuario") || null

      try {
        const res = await window.PhotinoBridge.send({
          action: "productoTerminado.actualizarFecha",
          data: { id: this._detalleActual.id, empresa: "FARET", fechaRegistro, horaRegistro, usuarioNombre }
        })
        if (!res || res.ok === false) throw new Error(res?.error || "Error actualizando la fecha")

        await this.abrirDetalle(this._detalleActual.id)
        await this.cargarDatos()
      } catch (err) {
        alert(err.message)
      }
    }

    mostrarImagen(url) {
      const imagenUrl = this.normalizarImagenUrl(url)

      if (!imagenUrl) {
        alert("No hay imagen disponible para este hallazgo.")
        return
      }

      this.cerrarImagen()

      const modal = document.createElement("div")
      modal.id = "modalImagenFaretProductoTerminado"
      modal.style.position = "fixed"
      modal.style.left = "0"
      modal.style.top = "0"
      modal.style.width = "100%"
      modal.style.height = "100%"
      modal.style.background = "rgba(15, 23, 42, 0.75)"
      modal.style.zIndex = "10000"
      modal.style.display = "flex"
      modal.style.alignItems = "center"
      modal.style.justifyContent = "center"
      modal.style.padding = "24px"

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
          <div style="display:flex; justify-content:space-between; align-items:center; gap:12px; margin-bottom:12px;">
            <strong>Foto del hallazgo</strong>
            <button id="btnCerrarImagenFaretProductoTerminado" class="btn-secondary" type="button">Cerrar</button>
          </div>

          <img
            src="${this.escape(imagenUrl)}"
            alt="Foto del hallazgo"
            style="display:block; max-width:100%; max-height:75vh; object-fit:contain; border-radius:8px;"
          />
        </div>
      `

      document.body.appendChild(modal)
    }

    cerrarImagen() {
      const modal = document.getElementById("modalImagenFaretProductoTerminado")
      if (modal) modal.remove()
    }

    normalizarImagenUrl(url) {
      const value = String(url || "").trim()

      if (!value) return ""

      if (value.startsWith("http://") || value.startsWith("https://")) {
        return value
      }

      if (value.startsWith("/")) {
        return `https://api.faret.cl/calidad${value}`
      }

      return `https://api.faret.cl/calidad/${value}`
    }

    escape(value) {
      return String(value ?? "")
        .replaceAll("&", "&amp;")
        .replaceAll("<", "&lt;")
        .replaceAll(">", "&gt;")
        .replaceAll('"', "&quot;")
        .replaceAll("'", "&#039;")
    }

    renderKpis() {
      this.setText("fptTotalInspecciones", this.numero(this.data.totalInspecciones))
      this.setText("fptPorcentajeConformes", `${this.numero(this.data.porcentajeConformes)}%`)
      this.setText("fptPorcentajeNoConformes", `${this.numero(this.data.porcentajeNoConformes)}%`)
      this.setText("fptUnidadesNc", this.numero(this.data.unidadesNoConformes))
      this.setText("fptDefectosTotales", this.numero(this.data.defectosRegistrados))
    }

    // ---------- Gráficos (mismo patrón técnico que producto-terminado.controller.js /
    // no-conformidades.controller.js / faret-nc.controller.js). ----------

    renderCharts() {
      this.destroyCharts()

      const pareto = this._aplicarTopNOtros(
        this._calcularAcumulado(this.data.paretoDefectos || [], "cantidad"),
        "cantidad"
      )
      this._chartPareto(
        "chartFaretProductoTerminadoPareto", pareto, "defecto", "cantidad", "porcentajeAcumulado"
      )

      this._chartBarHorizontal(
        "chartFaretProductoTerminadoOrigen", this.data.ncPorOrigen || [], "origen", "cantidad", "Unidades NC"
      )

      this._chartBarAgrupada("chartFaretProductoTerminadoTendencia", this.data.tendencia || [], "fecha", [
        { key: "inspecciones", label: "Inspecciones", color: "#3b82f6" },
        { key: "noConformes", label: "No Conformes", color: "#ef4444" },
      ])

      this._chartComparacionProcesos(
        "chartFaretProductoTerminadoComparacion", this.data.comparacionProcesos || []
      )
    }

    _calcularAcumulado(rows, valueKey) {
      const total = rows.reduce((s, r) => s + Number(r[valueKey] || 0), 0)
      let acumulado = 0

      return rows.map(r => {
        acumulado += Number(r[valueKey] || 0)
        return {
          ...r,
          porcentajeAcumulado: total > 0 ? Math.round((acumulado / total) * 10000) / 100 : 0
        }
      })
    }

    _aplicarTopNOtros(rows, valueKey, topN = 10) {
      if (rows.length <= topN) return rows

      const top = rows.slice(0, topN)
      const resto = rows.slice(topN)
      const valorOtros = resto.reduce((s, r) => s + Number(r[valueKey] || 0), 0)
      const acumuladoReal = rows[rows.length - 1].porcentajeAcumulado

      return [...top, {
        defecto: "Otros",
        [valueKey]: valorOtros,
        porcentajeAcumulado: acumuladoReal,
        esOtros: true
      }]
    }

    _chartBarHorizontal(canvasId, rows, labelKey, valueKey, label) {
      const ctx = document.getElementById(canvasId)
      if (!ctx) return

      const chart = new Chart(ctx, {
        type: "bar",
        data: {
          labels: rows.map(r => r[labelKey] || "-"),
          datasets: [{
            label,
            data: rows.map(r => Number(r[valueKey] || 0)),
            backgroundColor: ["#ef4444", "#f97316", "#eab308", "#22c55e", "#16a34a", "#3b82f6", "#6366f1"],
            borderRadius: 8
          }]
        },
        options: {
          indexAxis: "y",
          responsive: true,
          maintainAspectRatio: false,
          plugins: { legend: { display: false } },
          scales: { x: { beginAtZero: true }, y: { ticks: { font: { size: 11 } } } }
        }
      })

      this.charts.push(chart)
    }

    _chartBarAgrupada(canvasId, rows, labelKey, series) {
      const ctx = document.getElementById(canvasId)
      if (!ctx) return

      const chart = new Chart(ctx, {
        type: "bar",
        data: {
          labels: rows.map(r => r[labelKey] || "-"),
          datasets: series.map(s => ({
            label: s.label,
            data: rows.map(r => Number(r[s.key] || 0)),
            backgroundColor: s.color,
            borderRadius: 6
          }))
        },
        options: {
          responsive: true,
          maintainAspectRatio: false,
          plugins: { legend: { position: "bottom", labels: { font: { size: 11 } } } },
          scales: {
            y: { beginAtZero: true, ticks: { callback: v => Number(v).toLocaleString("es-CL") } },
            x: { ticks: { autoSkip: true, maxRotation: 45, minRotation: 0, font: { size: 11 } } }
          }
        }
      })

      this.charts.push(chart)
    }

    _chartPareto(canvasId, rows, labelKey, valueKey, pctKey) {
      const ctx = document.getElementById(canvasId)
      if (!ctx) return

      const truncarEtiqueta = (texto, max = 14) =>
        texto.length > max ? `${texto.slice(0, max - 1)}…` : texto

      const chart = new Chart(ctx, {
        type: "bar",
        data: {
          labels: rows.map(r => r[labelKey] || "-"),
          datasets: [
            {
              label: "Cantidad",
              data: rows.map(r => Number(r[valueKey] || 0)),
              backgroundColor: rows.map(r => r.esOtros ? "#94a3b8" : "#3b82f6"),
              borderRadius: 6,
              yAxisID: "y"
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
              yAxisID: "y1"
            }
          ]
        },
        options: {
          responsive: true,
          maintainAspectRatio: false,
          plugins: {
            legend: { position: "bottom", labels: { font: { size: 11 } } },
            tooltip: {
              callbacks: {
                title: items => (items[0] ? rows[items[0].dataIndex]?.[labelKey] || "" : "")
              }
            }
          },
          scales: {
            y: { beginAtZero: true, position: "left", title: { display: true, text: "Cantidad" } },
            y1: {
              beginAtZero: true,
              max: 100,
              position: "right",
              grid: { drawOnChartArea: false },
              title: { display: true, text: "% Acumulado" }
            },
            x: {
              ticks: {
                maxRotation: 40,
                minRotation: 40,
                font: { size: 10 },
                callback: function (value) {
                  return truncarEtiqueta(String(this.getLabelForValue(value)))
                }
              }
            }
          }
        }
      })

      this.charts.push(chart)
    }

    _chartComparacionProcesos(canvasId, rows) {
      const ctx = document.getElementById(canvasId)
      if (!ctx) return

      const chart = new Chart(ctx, {
        type: "bar",
        data: {
          labels: rows.map(r => r.proceso || "-"),
          datasets: [
            {
              label: "Inspecciones",
              data: rows.map(r => Number(r.inspecciones || 0)),
              backgroundColor: "#3b82f6",
              borderRadius: 6,
              yAxisID: "y"
            },
            {
              label: "Unidades NC",
              data: rows.map(r => Number(r.unidadesNc || 0)),
              backgroundColor: "#ef4444",
              borderRadius: 6,
              yAxisID: "y"
            },
            {
              type: "line",
              label: "% NC",
              data: rows.map(r => Number(r.porcentajeNc || 0)),
              borderColor: "#16a34a",
              backgroundColor: "#16a34a",
              pointBackgroundColor: "#16a34a",
              pointRadius: 4,
              tension: 0.25,
              yAxisID: "y1"
            }
          ]
        },
        options: {
          responsive: true,
          maintainAspectRatio: false,
          plugins: { legend: { position: "bottom", labels: { font: { size: 11 } } } },
          scales: {
            y: { beginAtZero: true, position: "left", title: { display: true, text: "Cantidad" } },
            y1: {
              beginAtZero: true,
              max: 100,
              position: "right",
              grid: { drawOnChartArea: false },
              title: { display: true, text: "% NC" }
            }
          }
        }
      })

      this.charts.push(chart)
    }

    destroyCharts() {
      this.charts.forEach(chart => {
        try {
          chart.destroy()
        } catch (_) { }
      })

      this.charts = []
    }

    mostrarError(message) {
      const estado = document.getElementById("fptEstadoConexion")
      if (!estado) return

      estado.textContent = message
      estado.style.display = "block"
      estado.style.color = "#DC2626"
    }

    ocultarError() {
      const estado = document.getElementById("fptEstadoConexion")
      if (estado) estado.style.display = "none"
    }

    setText(id, value) {
      const el = document.getElementById(id)
      if (el) el.textContent = value
    }

    numero(value) {
      return Number(value || 0).toLocaleString("es-CL", {
        minimumFractionDigits: 0,
        maximumFractionDigits: 2
      })
    }

    getVal(id) {
      return document.getElementById(id)?.value || ""
    }

    destroy() {
      console.log("DESTROY FARET PRODUCTO TERMINADO")

      if (this._clickHandler) {
        document.removeEventListener("click", this._clickHandler)
      }

      if (this._keyHandler) {
        document.removeEventListener("keydown", this._keyHandler)
      }

      this._clickHandler = null
      this._keyHandler = null
      this.loading = false
      this.destroyCharts()
      this.cerrarDetalle()
      this.cerrarImagen()
    }
  }

  window.FaretProductoTerminadoController = FaretProductoTerminadoController
}
