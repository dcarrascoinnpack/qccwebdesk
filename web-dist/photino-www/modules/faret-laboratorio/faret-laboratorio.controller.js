if (!window.FaretLaboratorioController) {
  class FaretLaboratorioController {
    constructor() {
      this._clickHandler = null
      this._changeHandler = null
      this._muestraActualId = null
      this._corrigiendoEnsayoId = null
    }

    async init() {
      console.log("INIT FARET LABORATORIO")
      this.bindEvents()
      await this.cargarLista()
    }

    bindEvents() {
      if (this._clickHandler) return

      this._clickHandler = (e) => {
        const id = e.target.id

        if (id === "flbBtnEspecificaciones") return this.abrirEspecificaciones()
        if (id === "flbEspecCerrar") return this.cerrarModal("flbModalEspecificaciones")
        if (id === "flbEspecLimpiar") return this.limpiarFormEspecificacion()
        if (id === "flbEspecGuardar") return this.guardarEspecificacion()
        if (e.target.classList?.contains("flb-espec-editar-btn")) {
          return this.editarEspecificacion(JSON.parse(e.target.dataset.espec))
        }
        if (e.target.classList?.contains("flb-espec-toggle-btn")) {
          return this.toggleEspecificacion(parseInt(e.target.dataset.id, 10), e.target.dataset.activo === "true")
        }

        if (id === "flbBtnNuevaMuestra") return this.abrirNuevaMuestra()
        if (id === "flbNmCancelar") return this.cerrarModal("flbModalNuevaMuestra")
        if (id === "flbNmGuardar") return this.guardarNuevaMuestra()

        if (id === "flbBtnFiltrar") return this.cargarLista()

        if (e.target.classList?.contains("flb-ver-btn")) {
          return this.abrirDetalle(parseInt(e.target.dataset.id, 10))
        }
        if (id === "flbDetCerrar") return this.cerrarModal("flbModalDetalle")
        if (id === "flbBtnInforme") return this.generarInforme()
        if (id === "flbBtnCrearNc") return this.crearNoConformidad()

        if (id === "flbBtnNuevoHumedad") return this.abrirModal("flbModalHumedad")
        if (id === "flbHumCancelar") return this.cerrarModal("flbModalHumedad")
        if (id === "flbHumGuardar") return this.guardarHumedad()

        if (id === "flbBtnNuevoGramaje") return this.abrirModal("flbModalGramaje")
        if (id === "flbGraCancelar") return this.cerrarModal("flbModalGramaje")
        if (id === "flbGraGuardar") return this.guardarGramaje()

        if (id === "flbBtnNuevoCobb") return this.abrirModal("flbModalCobb")
        if (id === "flbCobbCancelar") return this.cerrarModal("flbModalCobb")
        if (id === "flbCobbGuardar") return this.guardarCobb()

        if (id === "flbBtnNuevoEspesor") return this.abrirModal("flbModalEspesor")
        if (id === "flbEspCancelar") return this.cerrarModal("flbModalEspesor")
        if (id === "flbEspGuardar") return this.guardarEspesor()

        if (id === "flbBtnNuevoRct") return this.abrirModal("flbModalRct")
        if (id === "flbRctCancelar") return this.cerrarModal("flbModalRct")
        if (id === "flbRctGuardar") return this.guardarRct()

        if (id === "flbBtnNuevoFct") return this.abrirModal("flbModalFct")
        if (id === "flbFctCancelar") return this.cerrarModal("flbModalFct")
        if (id === "flbFctGuardar") return this.guardarFct()

        if (id === "flbBtnNuevoEct") return this.abrirModal("flbModalEct")
        if (id === "flbEctCancelar") return this.cerrarModal("flbModalEct")
        if (id === "flbEctGuardar") return this.guardarEct()

        if (id === "flbBtnNuevoBctMedido") return this.abrirModalBctMedido()
        if (id === "flbBctMedCancelar") return this.cerrarModal("flbModalBctMedido")
        if (id === "flbBctMedGuardar") return this.guardarBctMedido()

        if (id === "flbBtnNuevoBctTeorico") return this.abrirModalBctTeorico()
        if (id === "flbBctTeoCancelar") return this.cerrarModal("flbModalBctTeorico")
        if (id === "flbBctTeoGuardar") return this.guardarBctTeorico()

        if (id === "flbBtnNuevoViscosidad") return this.abrirModal("flbModalViscosidad")
        if (id === "flbViscCancelar") return this.cerrarModal("flbModalViscosidad")
        if (id === "flbViscGuardar") return this.guardarViscosidad()

        if (id === "flbBtnNuevoPh") return this.abrirModal("flbModalPh")
        if (id === "flbPhCancelar") return this.cerrarModal("flbModalPh")
        if (id === "flbPhGuardar") return this.guardarPh()

        if (id === "flbBtnNuevoSolidos") return this.abrirModal("flbModalSolidos")
        if (id === "flbSolCancelar") return this.cerrarModal("flbModalSolidos")
        if (id === "flbSolGuardar") return this.guardarSolidos()

        if (id === "flbBtnNuevoLugol") return this.abrirModal("flbModalLugol")
        if (id === "flbLugolCancelar") return this.cerrarModal("flbModalLugol")
        if (id === "flbLugolGuardar") return this.guardarLugol()

        if (e.target.classList?.contains("flb-anular-btn")) {
          return this.anularEnsayo(parseInt(e.target.dataset.id, 10))
        }
        if (e.target.classList?.contains("flb-corregir-btn")) {
          return this.corregirEnsayo(parseInt(e.target.dataset.id, 10))
        }
      }
      document.addEventListener("click", this._clickHandler)

      this._changeHandler = (e) => {
        if (e.target.id === "flbHumMetodoEquipo") this.actualizarCamposHumedad()
        if (e.target.id === "flbBctMedCajas") this.renderBctMedCajas()
      }
      document.addEventListener("change", this._changeHandler)
    }

    abrirModal(id) {
      document.getElementById(id).style.display = "flex"
    }
    cerrarModal(id) {
      document.getElementById(id).style.display = "none"
      this._limpiarCorreccion()
    }

    // =====================================================================
    // LISTA
    // =====================================================================
    async cargarLista() {
      const body = document.getElementById("flbMuestrasBody")
      body.innerHTML = '<tr><td colspan="11" style="text-align:center;">Cargando...</td></tr>'

      try {
        const estado = document.getElementById("flbFiltroEstado")?.value || ""
        const np = document.getElementById("flbFiltroNp")?.value?.trim() || ""

        const res = await window.PhotinoBridge.send({
          action: "faretLab.list",
          data: { estado, np }
        })

        if (!res || res.ok === false) throw new Error(res?.error || "Error cargando muestras")

        const items = res.data || []
        if (items.length === 0) {
          body.innerHTML = '<tr><td colspan="11" style="text-align:center;">Sin muestras registradas</td></tr>'
          return
        }

        body.innerHTML = items.map(m => `
          <tr>
            <td>${m.id}</td>
            <td>${m.fechaIngreso || "-"}</td>
            <td>${m.origen || "-"}</td>
            <td>${m.tipoMuestra || "-"}</td>
            <td>${m.np || "-"}</td>
            <td>${m.cliente || "-"}</td>
            <td>${m.codigoProducto || "-"}</td>
            <td>${m.totalEnsayos}</td>
            <td>${m.estado || "-"}</td>
            <td>${m.evaluacion || "-"}</td>
            <td><button class="btn-secondary flb-ver-btn" data-id="${m.id}">Ver</button></td>
          </tr>
        `).join("")
      } catch (err) {
        console.error(err)
        body.innerHTML = `<tr><td colspan="11" style="text-align:center;">${err.message}</td></tr>`
      }
    }

    // =====================================================================
    // ESPECIFICACIONES
    // =====================================================================
    async abrirEspecificaciones() {
      this.limpiarFormEspecificacion()
      await this.cargarEspecificaciones()
      this.abrirModal("flbModalEspecificaciones")
    }

    async cargarEspecificaciones() {
      const body = document.getElementById("flbEspecBody")
      body.innerHTML = '<tr><td colspan="8" style="text-align:center;">Cargando...</td></tr>'

      try {
        const res = await window.PhotinoBridge.send({ action: "faretLab.especificacion.list", data: {} })
        if (!res || res.ok === false) throw new Error(res?.error || "Error cargando especificaciones")

        const items = res.data || []
        if (items.length === 0) {
          body.innerHTML = '<tr><td colspan="8" style="text-align:center;">Sin especificaciones cargadas</td></tr>'
          return
        }

        body.innerHTML = items.map(s => `
          <tr>
            <td>${s.tipoMuestra}</td>
            <td>${s.tipoEnsayo}</td>
            <td>${s.codigoProducto || "(todos)"}</td>
            <td>${s.limiteMin ?? "-"}</td>
            <td>${s.limiteMax ?? "-"}</td>
            <td>${s.unidad || "-"}</td>
            <td>${s.activo ? "Sí" : "No"}</td>
            <td>
              <button class="btn-secondary flb-espec-editar-btn" data-espec='${JSON.stringify(s).replace(/'/g, "&apos;")}'>Editar</button>
              <button class="btn-secondary flb-espec-toggle-btn" data-id="${s.id}" data-activo="${s.activo}">${s.activo ? "Desactivar" : "Activar"}</button>
            </td>
          </tr>
        `).join("")
      } catch (err) {
        body.innerHTML = `<tr><td colspan="8" style="text-align:center;">${err.message}</td></tr>`
      }
    }

    limpiarFormEspecificacion() {
      document.getElementById("flbEspecId").value = ""
      document.getElementById("flbEspecTipoMuestra").value = "Papel"
      document.getElementById("flbEspecTipoEnsayo").value = "HUMEDAD"
      document.getElementById("flbEspecCodigo").value = ""
      document.getElementById("flbEspecMin").value = ""
      document.getElementById("flbEspecMax").value = ""
      document.getElementById("flbEspecUnidad").value = ""
      document.getElementById("flbEspecFormTitulo").textContent = "Nueva especificación"
    }

    editarEspecificacion(s) {
      document.getElementById("flbEspecId").value = s.id
      document.getElementById("flbEspecTipoMuestra").value = s.tipoMuestra
      document.getElementById("flbEspecTipoEnsayo").value = s.tipoEnsayo
      document.getElementById("flbEspecCodigo").value = s.codigoProducto || ""
      document.getElementById("flbEspecMin").value = s.limiteMin ?? ""
      document.getElementById("flbEspecMax").value = s.limiteMax ?? ""
      document.getElementById("flbEspecUnidad").value = s.unidad || ""
      document.getElementById("flbEspecFormTitulo").textContent = `Editando especificación #${s.id}`
    }

    async guardarEspecificacion() {
      const idVal = document.getElementById("flbEspecId").value
      const num = (id) => {
        const v = document.getElementById(id).value
        return v === "" ? null : parseFloat(v)
      }

      const data = {
        id: idVal ? parseInt(idVal, 10) : null,
        tipoMuestra: document.getElementById("flbEspecTipoMuestra").value,
        tipoEnsayo: document.getElementById("flbEspecTipoEnsayo").value,
        codigoProducto: document.getElementById("flbEspecCodigo").value.trim(),
        limiteMin: num("flbEspecMin"),
        limiteMax: num("flbEspecMax"),
        unidad: document.getElementById("flbEspecUnidad").value.trim()
      }

      try {
        const res = await window.PhotinoBridge.send({ action: "faretLab.especificacion.guardar", data })
        if (!res || res.ok === false) throw new Error(res?.error || "Error guardando la especificación")

        this.limpiarFormEspecificacion()
        await this.cargarEspecificaciones()
      } catch (err) {
        alert(err.message)
      }
    }

    async toggleEspecificacion(id, activoActual) {
      try {
        const res = await window.PhotinoBridge.send({
          action: "faretLab.especificacion.activar",
          data: { id, activo: !activoActual }
        })
        if (!res || res.ok === false) throw new Error(res?.error || "Error actualizando la especificación")

        await this.cargarEspecificaciones()
      } catch (err) {
        alert(err.message)
      }
    }

    // =====================================================================
    // NUEVA MUESTRA
    // =====================================================================
    abrirNuevaMuestra() {
      document.getElementById("flbNmNp").value = ""
      document.getElementById("flbNmCliente").value = ""
      document.getElementById("flbNmCodigo").value = ""
      document.getElementById("flbNmDescripcion").value = ""
      document.getElementById("flbNmMaquina").value = ""
      document.getElementById("flbNmTurno").value = ""
      document.getElementById("flbNmLote").value = ""
      document.getElementById("flbNmProveedor").value = ""
      document.getElementById("flbNmObservacion").value = ""
      this.abrirModal("flbModalNuevaMuestra")
    }

    async guardarNuevaMuestra() {
      const data = {
        origen: document.getElementById("flbNmOrigen").value,
        tipoMuestra: document.getElementById("flbNmTipoMuestra").value,
        np: document.getElementById("flbNmNp").value.trim(),
        cliente: document.getElementById("flbNmCliente").value.trim(),
        codigoProducto: document.getElementById("flbNmCodigo").value.trim(),
        descripcion: document.getElementById("flbNmDescripcion").value.trim(),
        maquina: document.getElementById("flbNmMaquina").value.trim(),
        turno: document.getElementById("flbNmTurno").value.trim(),
        lote: document.getElementById("flbNmLote").value.trim(),
        proveedor: document.getElementById("flbNmProveedor").value.trim(),
        observacion: document.getElementById("flbNmObservacion").value.trim()
      }

      try {
        const res = await window.PhotinoBridge.send({ action: "faretLab.crear", data })
        if (!res || res.ok === false) throw new Error(res?.error || "Error creando la muestra")

        this.cerrarModal("flbModalNuevaMuestra")
        await this.cargarLista()
        await this.abrirDetalle(res.data.id)
      } catch (err) {
        alert(err.message)
      }
    }

    // =====================================================================
    // DETALLE
    // =====================================================================
    async abrirDetalle(id) {
      try {
        const res = await window.PhotinoBridge.send({ action: "faretLab.detalle", data: { id } })
        if (!res || res.ok === false) throw new Error(res?.error || "Error cargando el detalle")

        this._muestraActualId = id
        this._muestraActualDetalle = res.data
        const m = res.data

        document.getElementById("flbDetId").textContent = m.id
        document.getElementById("flbDetResumen").innerHTML = `
          <div><b>${m.origen || "-"}</b>Origen</div>
          <div><b>${m.tipoMuestra || "-"}</b>Tipo de muestra</div>
          <div><b>${m.np || "-"}</b>NP</div>
          <div><b>${m.cliente || "-"}</b>Cliente</div>
          <div><b>${m.codigoProducto || "-"}</b>Código</div>
          <div><b>${m.estado || "-"}</b>Estado</div>
          <div><b>${m.evaluacion || "-"}</b>Evaluación</div>
        `

        document.getElementById("flbDetNc").innerHTML = this.renderBloqueNc(m)

        this._ensayosActuales = m.ensayos || []
        this.renderEnsayos(this._ensayosActuales)
        this.abrirModal("flbModalDetalle")
      } catch (err) {
        alert(err.message)
      }
    }

    // Vínculo a No Conformidades: solo aparece cuando la muestra evaluó "No cumple". Si ya tiene
    // una NC vinculada, muestra el código; si no, ofrece crearla (queda gestionada desde el
    // módulo No Conformidades, acá solo se crea y se vincula).
    renderBloqueNc(m) {
      if (m.evaluacion !== "No cumple") return ""

      if (m.ncId) {
        return `<div class="subtitle" style="margin-top:8px;">No Conformidad vinculada: <b>${m.ncCodigo || `#${m.ncId}`}</b> (gestiónala desde el módulo No Conformidades).</div>`
      }

      return `
        <div class="subtitle" style="margin-top:8px; display:flex; align-items:center; gap:10px;">
          <span>Esta muestra no cumple especificación y no tiene una No Conformidad vinculada.</span>
          <button class="btn-secondary" id="flbBtnCrearNc">Crear No Conformidad</button>
        </div>
      `
    }

    async crearNoConformidad() {
      if (!this._muestraActualId) return
      if (!confirm("¿Crear una No Conformidad vinculada a esta muestra?")) return

      try {
        const res = await window.PhotinoBridge.send({
          action: "faretLab.nc.crear",
          data: { muestraId: this._muestraActualId }
        })
        if (!res || res.ok === false) throw new Error(res?.error || "Error creando la No Conformidad")

        alert(`No Conformidad creada (${res.data.codigo}). Gestiónala desde el módulo No Conformidades.`)
        await this.abrirDetalle(this._muestraActualId)
      } catch (err) {
        alert(err.message)
      }
    }

    renderEnsayos(ensayos) {
      const body = document.getElementById("flbEnsayosBody")
      if (!ensayos || ensayos.length === 0) {
        body.innerHTML = '<tr><td colspan="9" style="text-align:center;">Sin ensayos todavía</td></tr>'
        return
      }

      body.innerHTML = ensayos.map(e => {
        const resultado = e.resultadoValor != null ? `${e.resultadoValor} ${e.resultadoUnidad || ""}` : "-"
        const spec = (e.especificacionMin != null || e.especificacionMax != null)
          ? `${e.especificacionMin ?? ""} - ${e.especificacionMax ?? ""} ${e.especificacionUnidad || ""}`
          : "-"
        const puedeAnular = e.estado !== "Anulado"
        const puedeCorregir = e.estado === "Finalizado" && !!this._tipoEnsayoConfig(e.tipoEnsayo)
        const motivoAnulTitle = e.estado === "Anulado" && e.motivoAnulacion ? ` title="${e.motivoAnulacion}"` : ""
        const corrigeBadge = e.ensayoReemplazaId
          ? ` <span title="Corrige al ensayo #${e.ensayoReemplazaId}: ${e.motivoReemplazo || ""}">&#9998;</span>`
          : ""

        return `
          <tr>
            <td>${e.tipoEnsayo}</td>
            <td>${e.metodo || "-"}</td>
            <td>${e.analistaNombre || "-"}</td>
            <td>${e.fecha || "-"}</td>
            <td>${resultado}</td>
            <td>${spec}</td>
            <td>${e.cumplimiento}</td>
            <td${motivoAnulTitle}>${e.estado}${e.observacion ? ` <span title="${e.observacion}">&#9432;</span>` : ""}${corrigeBadge}</td>
            <td>
              ${puedeAnular ? `<button class="btn-secondary flb-anular-btn" data-id="${e.id}">Anular</button>` : ""}
              ${puedeCorregir ? `<button class="btn-secondary flb-corregir-btn" data-id="${e.id}">Corregir</button>` : ""}
            </td>
          </tr>
        `
      }).join("")
    }

    // =====================================================================
    // INFORME (impresión/guardar como PDF, mismo mecanismo que No Conformidades)
    // =====================================================================
    generarInforme() {
      const m = this._muestraActualDetalle
      if (!m) return

      const resumen = [
        { label: "Origen", valor: m.origen || "-" },
        { label: "Tipo de muestra", valor: m.tipoMuestra || "-" },
        { label: "NP", valor: m.np || "-" },
        { label: "Cliente", valor: m.cliente || "-" },
        { label: "Código", valor: m.codigoProducto || "-" },
        { label: "Descripción", valor: m.descripcion || "-" },
        { label: "Máquina", valor: m.maquina || "-" },
        { label: "Turno", valor: m.turno || "-" },
        { label: "Lote", valor: m.lote || "-" },
        { label: "Proveedor", valor: m.proveedor || "-" },
        { label: "Fecha ingreso", valor: m.fechaIngreso || "-" },
        { label: "Fecha ensayo", valor: m.fechaEnsayo || "-" },
        { label: "Analista", valor: m.analistaNombre || "-" },
        { label: "Estado", valor: m.estado || "-" },
        { label: "Evaluación", valor: m.evaluacion || "-" }
      ]

      const ensayos = this._ensayosActuales || []
      const filas = ensayos.map(e => {
        const resultado = e.resultadoValor != null ? `${e.resultadoValor} ${e.resultadoUnidad || ""}` : "-"
        const spec = (e.especificacionMin != null || e.especificacionMax != null)
          ? `${e.especificacionMin ?? ""} - ${e.especificacionMax ?? ""} ${e.especificacionUnidad || ""}`
          : "-"
        return [
          e.tipoEnsayo, e.metodo || "-", e.analistaNombre || "-", e.fecha || "-",
          resultado, spec, e.cumplimiento, e.estado, e.observacion || "-"
        ]
      })

      window.PrintExporter.printReport({
        titulo: `Informe de Laboratorio - Muestra #${m.id}`,
        empresa: "FARET",
        subtitulo: m.observacion ? `Observación general: ${m.observacion}` : "",
        totalRegistros: ensayos.length,
        resumen,
        tablas: [{
          titulo: "Ensayos",
          columnas: ["Tipo", "Método", "Analista", "Fecha", "Resultado", "Especificación", "Cumplimiento", "Estado", "Observación"],
          filas
        }]
      })
    }

    // =====================================================================
    // EDICIÓN CON AUDITORÍA DE UN ENSAYO FINALIZADO ("Corregir")
    // No edita in-place: reabre el mismo modal de "+ Tipo" precargado con los valores actuales,
    // pide un motivo obligatorio, y al guardar crea un ensayo NUEVO vinculado al original (que
    // queda anulado conservando su fila intacta) — mismo guardarXxx() de siempre, sin duplicar
    // su lógica. Ver MuestraLaboratorioHandler.FinalizarGuardado / Repository.ReemplazarEnsayo.
    // =====================================================================
    _tipoEnsayoConfig(tipo) {
      const map = {
        HUMEDAD: { modalId: "flbModalHumedad", precargar: e => this._precargarHumedad(e) },
        GRAMAJE: { modalId: "flbModalGramaje", precargar: e => this._precargarGramaje(e) },
        COBB: { modalId: "flbModalCobb", precargar: e => this._precargarCobb(e) },
        ESPESOR: { modalId: "flbModalEspesor", precargar: e => this._precargarEspesor(e) },
        RCT: { modalId: "flbModalRct", precargar: e => this._precargarResistencia(e, "Rct", true) },
        FCT: { modalId: "flbModalFct", precargar: e => this._precargarResistencia(e, "Fct", false) },
        ECT: { modalId: "flbModalEct", precargar: e => this._precargarEct(e) },
        BCT_MEDIDO: { modalId: "flbModalBctMedido", precargar: e => this._precargarBctMedido(e) },
        BCT_TEORICO: { modalId: "flbModalBctTeorico", precargar: e => this._precargarBctTeorico(e) },
        VISCOSIDAD: { modalId: "flbModalViscosidad", precargar: e => this._precargarViscosidad(e) },
        PH: { modalId: "flbModalPh", precargar: e => this._precargarPh(e) },
        SOLIDOS: { modalId: "flbModalSolidos", precargar: e => this._precargarSolidos(e) },
        LUGOL: { modalId: "flbModalLugol", precargar: e => this._precargarLugol(e) },
      }
      return map[tipo]
    }

    corregirEnsayo(ensayoId) {
      const ensayo = (this._ensayosActuales || []).find(e => e.id === ensayoId)
      if (!ensayo) return

      const cfg = this._tipoEnsayoConfig(ensayo.tipoEnsayo)
      if (!cfg) {
        alert("Este tipo de ensayo aún no soporta corrección desde esta pantalla.")
        return
      }

      this._corrigiendoEnsayoId = ensayoId
      cfg.precargar(ensayo)
      this._mostrarMotivoCorreccion(cfg.modalId)
      this.abrirModal(cfg.modalId)
    }

    _mostrarMotivoCorreccion(modalId) {
      let bloque = document.getElementById("flbMotivoCorreccionBloque")
      if (!bloque) {
        bloque = document.createElement("div")
        bloque.id = "flbMotivoCorreccionBloque"
        bloque.className = "flb-form-campo flb-form-campo-full"
        bloque.innerHTML = '<label>Motivo de la corrección *</label><textarea id="flbMotivoCorreccionInput" rows="2"></textarea>'
      }
      document.getElementById("flbMotivoCorreccionInput") // asegura que exista antes de limpiar
      bloque.querySelector("textarea").value = ""

      const modal = document.getElementById(modalId)
      const acciones = modal.querySelector(".flb-form-acciones")
      acciones.parentNode.insertBefore(bloque, acciones)
    }

    _limpiarCorreccion() {
      this._corrigiendoEnsayoId = null
      const bloque = document.getElementById("flbMotivoCorreccionBloque")
      if (bloque) bloque.remove()
    }

    // Si se está corrigiendo un ensayo, agrega ensayoOriginalId/motivoReemplazo al payload y
    // valida que el motivo esté completo. Devuelve false (y muestra la alerta) si falta el
    // motivo, para que el guardarXxx() que la llama corte el flujo con un simple `if (!... ) return`.
    _aplicarDatosCorreccion(data) {
      if (!this._corrigiendoEnsayoId) return true

      const motivo = document.getElementById("flbMotivoCorreccionInput")?.value?.trim()
      if (!motivo) {
        alert("Debes indicar el motivo de la corrección")
        return false
      }

      data.ensayoOriginalId = this._corrigiendoEnsayoId
      data.motivoReemplazo = motivo
      return true
    }

    _precargarHumedad(e) {
      const d = e.detalle || {}
      document.getElementById("flbHumMetodoEquipo").value = d.metodoEquipo || "Higrometro"
      document.getElementById("flbHumIzq").value = d.higrometroIzquierdo ?? ""
      document.getElementById("flbHumCentro").value = d.higrometroCentro ?? ""
      document.getElementById("flbHumDer").value = d.higrometroDerecho ?? ""
      document.getElementById("flbHumTermo").value = d.termobalanzaValor ?? ""
      document.getElementById("flbHum1i").value = d.horno1PesoInicial ?? ""
      document.getElementById("flbHum1f").value = d.horno1PesoFinal ?? ""
      document.getElementById("flbHum2i").value = d.horno2PesoInicial ?? ""
      document.getElementById("flbHum2f").value = d.horno2PesoFinal ?? ""
      document.getElementById("flbHum3i").value = d.horno3PesoInicial ?? ""
      document.getElementById("flbHum3f").value = d.horno3PesoFinal ?? ""
      document.getElementById("flbHumMetodo").value = e.metodo || ""
      document.getElementById("flbHumObservacion").value = e.observacion || ""
      this.actualizarCamposHumedad()
    }

    _precargarGramaje(e) {
      const d = e.detalle || {}
      document.getElementById("flbGraTipoMaterial").value = d.tipoMaterial || "Papel"
      document.getElementById("flbGraModalidad").value = d.modalidad || "ProbetaPeso"
      document.getElementById("flbGra1").value = d.muestra1 ?? ""
      document.getElementById("flbGra2").value = d.muestra2 ?? ""
      document.getElementById("flbGra3").value = d.muestra3 ?? ""
      document.getElementById("flbGraMetodo").value = e.metodo || ""
      document.getElementById("flbGraObservacion").value = e.observacion || ""
    }

    _precargarCobb(e) {
      const d = e.detalle || {}
      ;[1, 2, 3].forEach(n => {
        const p = d[`p${n}`] || {}
        document.getElementById(`flbCobb${n}Bobina`).value = p.bobina || ""
        document.getElementById(`flbCobb${n}Cara`).value = p.cara || "Externa"
        document.getElementById(`flbCobb${n}Inicial`).value = p.pesoInicial ?? ""
        document.getElementById(`flbCobb${n}Final`).value = p.pesoFinal ?? ""
        document.getElementById(`flbCobb${n}Tiempo`).value = p.tiempo || ""
      })
      document.getElementById("flbCobbMetodo").value = e.metodo || ""
      document.getElementById("flbCobbObservacion").value = e.observacion || ""
    }

    _precargarEspesor(e) {
      const d = e.detalle || {}
      document.getElementById("flbEspTipoMedicion").value = d.tipoMedicion || "Ubicacion"
      document.getElementById("flbEsp1").value = d.medicion1 ?? ""
      document.getElementById("flbEsp2").value = d.medicion2 ?? ""
      document.getElementById("flbEsp3").value = d.medicion3 ?? ""
      document.getElementById("flbEspMetodo").value = e.metodo || ""
      document.getElementById("flbEspObservacion").value = e.observacion || ""
    }

    _precargarResistencia(e, prefijoIds, esRct) {
      const d = e.detalle || {}
      if (esRct) document.getElementById("flbRctComponente").value = d.componente || "Liner"
      ;[1, 2, 3].forEach(n => {
        const p = d[`p${n}`] || {}
        if (esRct) document.getElementById(`flb${prefijoIds}${n}Bobina`).value = p.bobina || ""
        document.getElementById(`flb${prefijoIds}${n}Force`).value = p.force ?? ""
        document.getElementById(`flb${prefijoIds}${n}Strength`).value = p.strength ?? ""
      })
      document.getElementById(`flb${prefijoIds}StrengthUnidad`).value = d.strengthUnidad || ""
      document.getElementById(`flb${prefijoIds}Metodo`).value = e.metodo || ""
      document.getElementById(`flb${prefijoIds}Observacion`).value = e.observacion || ""
    }

    _precargarEct(e) {
      const d = e.detalle || {}
      ;[1, 2, 3, 4, 5].forEach(n => {
        const p = d[`p${n}`] || {}
        document.getElementById(`flbEct${n}`).value = p.force ?? ""
      })
      document.getElementById("flbEctMetodo").value = e.metodo || ""
      document.getElementById("flbEctObservacion").value = e.observacion || ""
    }

    _precargarBctMedido(e) {
      const d = e.detalle || {}
      document.getElementById("flbBctMedCajas").value = String(d.cajasEnsayadas || 3)
      document.getElementById("flbBctMedMotivo").value = d.motivoMenos3 || ""
      this.renderBctMedCajas()
      ;[1, 2, 3].forEach(n => {
        const c = d[`c${n}`]
        const largoEl = document.getElementById(`flbBctMedC${n}Largo`)
        if (!c || !largoEl) return
        largoEl.value = c.largo ?? ""
        document.getElementById(`flbBctMedC${n}Ancho`).value = c.ancho ?? ""
        document.getElementById(`flbBctMedC${n}Alto`).value = c.alto ?? ""
        document.getElementById(`flbBctMedC${n}TipoOnda`).value = c.tipoOnda || ""
        document.getElementById(`flbBctMedC${n}Gramaje`).value = c.gramajeComplejo ?? ""
        document.getElementById(`flbBctMedC${n}Espesor`).value = c.espesorComplejo ?? ""
        document.getElementById(`flbBctMedC${n}Resultado`).value = c.resultadoLbf ?? ""
      })
      document.getElementById("flbBctMedMetodo").value = e.metodo || ""
      document.getElementById("flbBctMedObservacion").value = e.observacion || ""
    }

    _precargarBctTeorico(e) {
      const d = e.detalle || {}
      const ects = (this._ensayosActuales || []).filter(x => x.tipoEnsayo === "ECT" && x.estado === "Finalizado")
      const espesores = (this._ensayosActuales || []).filter(x => x.tipoEnsayo === "ESPESOR" && x.estado === "Finalizado")

      const selEct = document.getElementById("flbBctTeoEct")
      const selEsp = document.getElementById("flbBctTeoEspesor")
      selEct.innerHTML = ects.map(x => `<option value="${x.id}">Ensayo #${x.id} - ${x.resultadoValor} ${x.resultadoUnidad} (${x.fecha})</option>`).join("")
      selEsp.innerHTML = espesores.map(x => `<option value="${x.id}">Ensayo #${x.id} - ${x.resultadoValor} ${x.resultadoUnidad} (${x.fecha})</option>`).join("")

      if (d.ectEnsayoId) selEct.value = String(d.ectEnsayoId)
      if (d.espesorEnsayoId) selEsp.value = String(d.espesorEnsayoId)
      document.getElementById("flbBctTeoLargo").value = d.largoMm ?? ""
      document.getElementById("flbBctTeoAncho").value = d.anchoMm ?? ""
      document.getElementById("flbBctTeoObservacion").value = e.observacion || ""
    }

    _precargarViscosidad(e) {
      const d = e.detalle || {}
      document.getElementById("flbViscTipoAdhesivo").value = d.tipoAdhesivo || ""
      document.getElementById("flbViscTemperatura").value = d.temperatura ?? ""
      document.getElementById("flbViscEquipo").value = d.equipo || ""
      document.getElementById("flbViscHusillo").value = d.husillo || ""
      document.getElementById("flbViscRpm").value = d.velocidadRpm ?? ""
      document.getElementById("flbViscResultado").value = d.resultadoCp ?? ""
      document.getElementById("flbViscObservacion").value = e.observacion || ""
    }

    _precargarPh(e) {
      const d = e.detalle || {}
      document.getElementById("flbPhValor").value = d.valorTexto || ""
      document.getElementById("flbPhColor").value = d.colorObservado || ""
      document.getElementById("flbPhObservacion").value = e.observacion || ""
    }

    _precargarSolidos(e) {
      const d = e.detalle || {}
      ;[1, 2, 3].forEach(n => {
        const det = d[`d${n}`] || {}
        document.getElementById(`flbSol${n}M1`).value = det.m1 ?? ""
        document.getElementById(`flbSol${n}M2`).value = det.m2 ?? ""
        document.getElementById(`flbSol${n}M3`).value = det.m3 ?? ""
      })
      document.getElementById("flbSolObservacion").value = e.observacion || ""
    }

    _precargarLugol(e) {
      const d = e.detalle || {}
      document.getElementById("flbLugolPunto").value = d.puntoMuestra || ""
      document.getElementById("flbLugolColoracion").value = d.coloracion || ""
      document.getElementById("flbLugolResultado").value = d.resultado || "Negativo"
      document.getElementById("flbLugolInterpretacion").value = d.interpretacion || ""
      document.getElementById("flbLugolCumplimiento").value = e.cumplimiento || "Sin especificacion"
      document.getElementById("flbLugolObservacion").value = e.observacion || ""
    }

    // =====================================================================
    // ENSAYO HUMEDAD
    // =====================================================================
    actualizarCamposHumedad() {
      const metodo = document.getElementById("flbHumMetodoEquipo").value
      document.getElementById("flbHumHigrometroCampos").style.display = metodo === "Higrometro" ? "flex" : "none"
      document.getElementById("flbHumTermobalanzaCampos").style.display = metodo === "Termobalanza" ? "flex" : "none"
      document.getElementById("flbHumHornoCampos").style.display = metodo === "Horno" ? "block" : "none"
    }

    async guardarHumedad() {
      if (!this._muestraActualId) return

      const num = (id) => {
        const v = document.getElementById(id).value
        return v === "" ? null : parseFloat(v)
      }

      const data = {
        muestraId: this._muestraActualId,
        metodoEquipo: document.getElementById("flbHumMetodoEquipo").value,
        metodo: document.getElementById("flbHumMetodo").value.trim(),
        observacion: document.getElementById("flbHumObservacion").value.trim(),
        higrometroIzquierdo: num("flbHumIzq"),
        higrometroCentro: num("flbHumCentro"),
        higrometroDerecho: num("flbHumDer"),
        termobalanzaValor: num("flbHumTermo"),
        horno1PesoInicial: num("flbHum1i"),
        horno1PesoFinal: num("flbHum1f"),
        horno2PesoInicial: num("flbHum2i"),
        horno2PesoFinal: num("flbHum2f"),
        horno3PesoInicial: num("flbHum3i"),
        horno3PesoFinal: num("flbHum3f")
      }
      if (!this._aplicarDatosCorreccion(data)) return

      try {
        const res = await window.PhotinoBridge.send({ action: "faretLab.humedad.guardar", data })
        if (!res || res.ok === false) throw new Error(res?.error || "Error guardando el ensayo de humedad")

        this.cerrarModal("flbModalHumedad")
        await this.abrirDetalle(this._muestraActualId)
        await this.cargarLista()
      } catch (err) {
        alert(err.message)
      }
    }

    // =====================================================================
    // ENSAYO GRAMAJE
    // =====================================================================
    async guardarGramaje() {
      if (!this._muestraActualId) return

      const num = (id) => {
        const v = document.getElementById(id).value
        return v === "" ? null : parseFloat(v)
      }

      const data = {
        muestraId: this._muestraActualId,
        tipoMaterial: document.getElementById("flbGraTipoMaterial").value,
        modalidad: document.getElementById("flbGraModalidad").value,
        metodo: document.getElementById("flbGraMetodo").value.trim(),
        observacion: document.getElementById("flbGraObservacion").value.trim(),
        muestra1: num("flbGra1"),
        muestra2: num("flbGra2"),
        muestra3: num("flbGra3")
      }
      if (!this._aplicarDatosCorreccion(data)) return

      try {
        const res = await window.PhotinoBridge.send({ action: "faretLab.gramaje.guardar", data })
        if (!res || res.ok === false) throw new Error(res?.error || "Error guardando el ensayo de gramaje")

        this.cerrarModal("flbModalGramaje")
        await this.abrirDetalle(this._muestraActualId)
        await this.cargarLista()
      } catch (err) {
        alert(err.message)
      }
    }

    // =====================================================================
    // ENSAYO COBB
    // =====================================================================
    async guardarCobb() {
      if (!this._muestraActualId) return

      const num = (id) => {
        const v = document.getElementById(id).value
        return v === "" ? null : parseFloat(v)
      }
      const txt = (id) => document.getElementById(id).value.trim() || null

      const probeta = (n) => ({
        bobina: txt(`flbCobb${n}Bobina`),
        cara: document.getElementById(`flbCobb${n}Cara`).value,
        pesoInicial: num(`flbCobb${n}Inicial`),
        pesoFinal: num(`flbCobb${n}Final`),
        tiempo: txt(`flbCobb${n}Tiempo`)
      })

      const data = {
        muestraId: this._muestraActualId,
        metodo: document.getElementById("flbCobbMetodo").value.trim(),
        observacion: document.getElementById("flbCobbObservacion").value.trim(),
        p1: probeta(1),
        p2: probeta(2),
        p3: probeta(3)
      }
      if (!this._aplicarDatosCorreccion(data)) return

      try {
        const res = await window.PhotinoBridge.send({ action: "faretLab.cobb.guardar", data })
        if (!res || res.ok === false) throw new Error(res?.error || "Error guardando el ensayo Cobb")

        this.cerrarModal("flbModalCobb")
        await this.abrirDetalle(this._muestraActualId)
        await this.cargarLista()
      } catch (err) {
        alert(err.message)
      }
    }

    // =====================================================================
    // ENSAYO ESPESOR
    // =====================================================================
    async guardarEspesor() {
      if (!this._muestraActualId) return

      const num = (id) => {
        const v = document.getElementById(id).value
        return v === "" ? null : parseFloat(v)
      }

      const data = {
        muestraId: this._muestraActualId,
        tipoMedicion: document.getElementById("flbEspTipoMedicion").value,
        metodo: document.getElementById("flbEspMetodo").value.trim(),
        observacion: document.getElementById("flbEspObservacion").value.trim(),
        medicion1: num("flbEsp1"),
        medicion2: num("flbEsp2"),
        medicion3: num("flbEsp3")
      }
      if (!this._aplicarDatosCorreccion(data)) return

      try {
        const res = await window.PhotinoBridge.send({ action: "faretLab.espesor.guardar", data })
        if (!res || res.ok === false) throw new Error(res?.error || "Error guardando el ensayo de espesor")

        this.cerrarModal("flbModalEspesor")
        await this.abrirDetalle(this._muestraActualId)
        await this.cargarLista()
      } catch (err) {
        alert(err.message)
      }
    }

    // =====================================================================
    // ENSAYO RCT / FCT
    // =====================================================================
    async guardarRct() {
      await this._guardarResistencia("rct", "Rct")
    }

    async guardarFct() {
      await this._guardarResistencia("fct", "Fct")
    }

    async _guardarResistencia(accionSufijo, prefijoIds) {
      if (!this._muestraActualId) return

      const num = (id) => {
        const v = document.getElementById(id).value
        return v === "" ? null : parseFloat(v)
      }
      const txt = (id) => document.getElementById(id)?.value?.trim() || null

      const probeta = (n) => ({
        bobina: txt(`flb${prefijoIds}${n}Bobina`),
        force: num(`flb${prefijoIds}${n}Force`),
        strength: num(`flb${prefijoIds}${n}Strength`)
      })

      const data = {
        muestraId: this._muestraActualId,
        metodo: document.getElementById(`flb${prefijoIds}Metodo`).value.trim(),
        observacion: document.getElementById(`flb${prefijoIds}Observacion`).value.trim(),
        strengthUnidad: txt(`flb${prefijoIds}StrengthUnidad`),
        p1: probeta(1),
        p2: probeta(2),
        p3: probeta(3)
      }
      if (prefijoIds === "Rct") {
        data.componente = document.getElementById("flbRctComponente").value
      }
      if (!this._aplicarDatosCorreccion(data)) return

      try {
        const res = await window.PhotinoBridge.send({ action: `faretLab.${accionSufijo}.guardar`, data })
        if (!res || res.ok === false) throw new Error(res?.error || `Error guardando el ensayo ${accionSufijo.toUpperCase()}`)

        this.cerrarModal(`flbModal${prefijoIds}`)
        await this.abrirDetalle(this._muestraActualId)
        await this.cargarLista()
      } catch (err) {
        alert(err.message)
      }
    }

    // =====================================================================
    // ENSAYO ECT
    // =====================================================================
    async guardarEct() {
      if (!this._muestraActualId) return

      const num = (id) => {
        const v = document.getElementById(id).value
        return v === "" ? null : parseFloat(v)
      }

      const data = {
        muestraId: this._muestraActualId,
        metodo: document.getElementById("flbEctMetodo").value.trim(),
        observacion: document.getElementById("flbEctObservacion").value.trim(),
        p1Force: num("flbEct1"),
        p2Force: num("flbEct2"),
        p3Force: num("flbEct3"),
        p4Force: num("flbEct4"),
        p5Force: num("flbEct5")
      }
      if (!this._aplicarDatosCorreccion(data)) return

      try {
        const res = await window.PhotinoBridge.send({ action: "faretLab.ect.guardar", data })
        if (!res || res.ok === false) throw new Error(res?.error || "Error guardando el ensayo ECT")

        this.cerrarModal("flbModalEct")
        await this.abrirDetalle(this._muestraActualId)
        await this.cargarLista()
      } catch (err) {
        alert(err.message)
      }
    }

    // =====================================================================
    // BCT MEDIDO
    // =====================================================================
    abrirModalBctMedido() {
      document.getElementById("flbBctMedCajas").value = "3"
      document.getElementById("flbBctMedMotivo").value = ""
      document.getElementById("flbBctMedMetodo").value = ""
      document.getElementById("flbBctMedObservacion").value = ""
      this.renderBctMedCajas()
      this.abrirModal("flbModalBctMedido")
    }

    renderBctMedCajas() {
      const n = parseInt(document.getElementById("flbBctMedCajas").value, 10)
      document.getElementById("flbBctMedMotivoBloque").style.display = n < 3 ? "block" : "none"

      const bloque = document.getElementById("flbBctMedCajasBloque")
      let html = ""
      for (let i = 1; i <= n; i++) {
        html += `
          <div class="module-title" style="font-size:13px; margin-top:8px;">Caja ${i}</div>
          <div class="flb-form-row">
            <div class="flb-form-campo"><label>Largo</label><input type="number" step="0.01" id="flbBctMedC${i}Largo"></div>
            <div class="flb-form-campo"><label>Ancho</label><input type="number" step="0.01" id="flbBctMedC${i}Ancho"></div>
            <div class="flb-form-campo"><label>Alto</label><input type="number" step="0.01" id="flbBctMedC${i}Alto"></div>
            <div class="flb-form-campo"><label>Tipo de onda</label><input type="text" id="flbBctMedC${i}TipoOnda"></div>
          </div>
          <div class="flb-form-row">
            <div class="flb-form-campo"><label>Gramaje complejo</label><input type="number" step="0.01" id="flbBctMedC${i}Gramaje"></div>
            <div class="flb-form-campo"><label>Espesor complejo</label><input type="number" step="0.0001" id="flbBctMedC${i}Espesor"></div>
            <div class="flb-form-campo"><label>Resultado (lbf)</label><input type="number" step="0.0001" id="flbBctMedC${i}Resultado"></div>
          </div>
        `
      }
      bloque.innerHTML = html
    }

    async guardarBctMedido() {
      if (!this._muestraActualId) return

      const cajasEnsayadas = parseInt(document.getElementById("flbBctMedCajas").value, 10)
      const num = (id) => {
        const el = document.getElementById(id)
        if (!el || el.value === "") return null
        return parseFloat(el.value)
      }
      const txt = (id) => document.getElementById(id)?.value?.trim() || null

      const caja = (i) => ({
        largo: num(`flbBctMedC${i}Largo`),
        ancho: num(`flbBctMedC${i}Ancho`),
        alto: num(`flbBctMedC${i}Alto`),
        tipoOnda: txt(`flbBctMedC${i}TipoOnda`),
        gramajeComplejo: num(`flbBctMedC${i}Gramaje`),
        espesorComplejo: num(`flbBctMedC${i}Espesor`),
        resultadoLbf: num(`flbBctMedC${i}Resultado`)
      })

      const data = {
        muestraId: this._muestraActualId,
        cajasEnsayadas,
        motivoMenos3: document.getElementById("flbBctMedMotivo").value.trim(),
        metodo: document.getElementById("flbBctMedMetodo").value.trim(),
        observacion: document.getElementById("flbBctMedObservacion").value.trim(),
        c1: caja(1),
        c2: cajasEnsayadas >= 2 ? caja(2) : null,
        c3: cajasEnsayadas >= 3 ? caja(3) : null
      }
      if (!this._aplicarDatosCorreccion(data)) return

      try {
        const res = await window.PhotinoBridge.send({ action: "faretLab.bctMedido.guardar", data })
        if (!res || res.ok === false) throw new Error(res?.error || "Error guardando BCT Medido")

        this.cerrarModal("flbModalBctMedido")
        await this.abrirDetalle(this._muestraActualId)
        await this.cargarLista()
      } catch (err) {
        alert(err.message)
      }
    }

    // =====================================================================
    // BCT TEORICO (McKee)
    // =====================================================================
    abrirModalBctTeorico() {
      const ects = (this._ensayosActuales || []).filter(e => e.tipoEnsayo === "ECT" && e.estado === "Finalizado")
      const espesores = (this._ensayosActuales || []).filter(e => e.tipoEnsayo === "ESPESOR" && e.estado === "Finalizado")

      const selEct = document.getElementById("flbBctTeoEct")
      const selEsp = document.getElementById("flbBctTeoEspesor")

      if (ects.length === 0 || espesores.length === 0) {
        alert("Necesitas al menos un ECT y un Espesor finalizados en esta muestra antes de calcular el BCT teórico.")
        return
      }

      selEct.innerHTML = ects.map(e => `<option value="${e.id}">Ensayo #${e.id} - ${e.resultadoValor} ${e.resultadoUnidad} (${e.fecha})</option>`).join("")
      selEsp.innerHTML = espesores.map(e => `<option value="${e.id}">Ensayo #${e.id} - ${e.resultadoValor} ${e.resultadoUnidad} (${e.fecha})</option>`).join("")

      document.getElementById("flbBctTeoLargo").value = ""
      document.getElementById("flbBctTeoAncho").value = ""
      document.getElementById("flbBctTeoObservacion").value = ""

      this.abrirModal("flbModalBctTeorico")
    }

    async guardarBctTeorico() {
      if (!this._muestraActualId) return

      const data = {
        muestraId: this._muestraActualId,
        ectEnsayoId: parseInt(document.getElementById("flbBctTeoEct").value, 10),
        espesorEnsayoId: parseInt(document.getElementById("flbBctTeoEspesor").value, 10),
        largoMm: parseFloat(document.getElementById("flbBctTeoLargo").value),
        anchoMm: parseFloat(document.getElementById("flbBctTeoAncho").value),
        observacion: document.getElementById("flbBctTeoObservacion").value.trim()
      }

      if (!data.largoMm || !data.anchoMm) {
        alert("Largo y ancho interno son obligatorios")
        return
      }
      if (!this._aplicarDatosCorreccion(data)) return

      try {
        const res = await window.PhotinoBridge.send({ action: "faretLab.bctTeorico.guardar", data })
        if (!res || res.ok === false) throw new Error(res?.error || "Error calculando BCT teórico")

        this.cerrarModal("flbModalBctTeorico")
        await this.abrirDetalle(this._muestraActualId)
        await this.cargarLista()
      } catch (err) {
        alert(err.message)
      }
    }

    // =====================================================================
    // VISCOSIDAD
    // =====================================================================
    async guardarViscosidad() {
      if (!this._muestraActualId) return

      const num = (id) => {
        const v = document.getElementById(id).value
        return v === "" ? null : parseFloat(v)
      }

      const data = {
        muestraId: this._muestraActualId,
        observacion: document.getElementById("flbViscObservacion").value.trim(),
        tipoAdhesivo: document.getElementById("flbViscTipoAdhesivo").value.trim(),
        temperatura: num("flbViscTemperatura"),
        equipo: document.getElementById("flbViscEquipo").value.trim(),
        husillo: document.getElementById("flbViscHusillo").value.trim(),
        velocidadRpm: num("flbViscRpm"),
        resultadoCp: num("flbViscResultado")
      }
      if (!this._aplicarDatosCorreccion(data)) return

      try {
        const res = await window.PhotinoBridge.send({ action: "faretLab.viscosidad.guardar", data })
        if (!res || res.ok === false) throw new Error(res?.error || "Error guardando Viscosidad")

        this.cerrarModal("flbModalViscosidad")
        await this.abrirDetalle(this._muestraActualId)
        await this.cargarLista()
      } catch (err) {
        alert(err.message)
      }
    }

    // =====================================================================
    // pH
    // =====================================================================
    async guardarPh() {
      if (!this._muestraActualId) return

      const valorTexto = document.getElementById("flbPhValor").value.trim()
      if (!valorTexto) {
        alert("Ingresa el valor o rango leído")
        return
      }

      const data = {
        muestraId: this._muestraActualId,
        observacion: document.getElementById("flbPhObservacion").value.trim(),
        valorTexto,
        colorObservado: document.getElementById("flbPhColor").value.trim()
      }
      if (!this._aplicarDatosCorreccion(data)) return

      try {
        const res = await window.PhotinoBridge.send({ action: "faretLab.ph.guardar", data })
        if (!res || res.ok === false) throw new Error(res?.error || "Error guardando pH")

        this.cerrarModal("flbModalPh")
        await this.abrirDetalle(this._muestraActualId)
        await this.cargarLista()
      } catch (err) {
        alert(err.message)
      }
    }

    // =====================================================================
    // SOLIDOS TOTALES
    // =====================================================================
    async guardarSolidos() {
      if (!this._muestraActualId) return

      const num = (id) => {
        const v = document.getElementById(id).value
        return v === "" ? null : parseFloat(v)
      }
      const det = (n) => ({ m1: num(`flbSol${n}M1`), m2: num(`flbSol${n}M2`), m3: num(`flbSol${n}M3`) })

      const data = {
        muestraId: this._muestraActualId,
        observacion: document.getElementById("flbSolObservacion").value.trim(),
        d1: det(1),
        d2: det(2),
        d3: det(3)
      }
      if (!this._aplicarDatosCorreccion(data)) return

      try {
        const res = await window.PhotinoBridge.send({ action: "faretLab.solidos.guardar", data })
        if (!res || res.ok === false) throw new Error(res?.error || "Error guardando Sólidos totales")

        this.cerrarModal("flbModalSolidos")
        await this.abrirDetalle(this._muestraActualId)
        await this.cargarLista()
      } catch (err) {
        alert(err.message)
      }
    }

    // =====================================================================
    // LUGOL
    // =====================================================================
    async guardarLugol() {
      if (!this._muestraActualId) return

      const data = {
        muestraId: this._muestraActualId,
        observacion: document.getElementById("flbLugolObservacion").value.trim(),
        puntoMuestra: document.getElementById("flbLugolPunto").value.trim(),
        coloracion: document.getElementById("flbLugolColoracion").value.trim(),
        resultado: document.getElementById("flbLugolResultado").value,
        interpretacion: document.getElementById("flbLugolInterpretacion").value.trim(),
        cumplimiento: document.getElementById("flbLugolCumplimiento").value
      }
      if (!this._aplicarDatosCorreccion(data)) return

      try {
        const res = await window.PhotinoBridge.send({ action: "faretLab.lugol.guardar", data })
        if (!res || res.ok === false) throw new Error(res?.error || "Error guardando Lugol")

        this.cerrarModal("flbModalLugol")
        await this.abrirDetalle(this._muestraActualId)
        await this.cargarLista()
      } catch (err) {
        alert(err.message)
      }
    }

    // =====================================================================
    // ANULAR ENSAYO
    // =====================================================================
    async anularEnsayo(ensayoId) {
      const motivo = prompt("Motivo de anulación:")
      if (!motivo) return

      try {
        const res = await window.PhotinoBridge.send({
          action: "faretLab.ensayo.anular",
          data: { ensayoId, motivo }
        })
        if (!res || res.ok === false) throw new Error(res?.error || "Error anulando el ensayo")

        await this.abrirDetalle(this._muestraActualId)
        await this.cargarLista()
      } catch (err) {
        alert(err.message)
      }
    }

    destroy() {
      console.log("DESTROY FARET LABORATORIO")
      if (this._clickHandler) {
        document.removeEventListener("click", this._clickHandler)
        this._clickHandler = null
      }
      if (this._changeHandler) {
        document.removeEventListener("change", this._changeHandler)
        this._changeHandler = null
      }
    }
  }

  window.FaretLaboratorioController = FaretLaboratorioController
}
