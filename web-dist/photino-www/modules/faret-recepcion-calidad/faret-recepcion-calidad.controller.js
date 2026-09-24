if (!window.FaretRecepcionCalidadController) {
  class FaretRecepcionCalidadController {
    constructor() {
      this._clickHandler = null
      this._loteActualId = null
      this._lineaSeleccionadaSap = null
      this._ultimaSeleccionAleatoria = false
    }

    async init() {
      console.log("INIT FARET RECEPCION CALIDAD")
      this.bindEvents()
      await this.cargarLista()
    }

    bindEvents() {
      if (this._clickHandler) return

      this._clickHandler = (e) => {
        const id = e.target.id

        if (id === "frcqBtnNuevoLote") return this.abrirNuevoLote()
        if (id === "frcqNlCancelar") return this.cerrarModal("frcqModalNuevoLote")
        if (id === "frcqNlCrear") return this.crearLote()
        if (id === "frcqBtnConsultarSap") return this.consultarSap()
        if (id === "frcqBtnFiltrar") return this.cargarLista()

        if (e.target.classList?.contains("rcq-usar-linea-btn")) {
          const idx = parseInt(e.target.dataset.idx, 10)
          return this.usarLineaSap(idx)
        }
        if (e.target.classList?.contains("rcq-ver-btn")) {
          return this.abrirDetalle(parseInt(e.target.dataset.id, 10))
        }

        if (id === "frcqDetCerrar") return this.cerrarModal("frcqModalDetalle")
        if (id === "frcqBtnGenerarPlan") return this.generarPlan()
        if (id === "frcqBtnSeleccionAleatoria") return this.seleccionAleatoria()
        if (id === "frcqBtnGuardarMuestreadas") return this.guardarMuestreadas()
        if (id === "frcqBtnCrearMuestra") return this.crearMuestra()
        if (id === "frcqBtnActualizarEstado") return this.actualizarEstado()
        if (id === "frcqBtnCrearNc") return this.crearNoConformidad()

        if (e.target.classList?.contains("rcq-bobina-check")) {
          this._ultimaSeleccionAleatoria = false
        }
      }
      document.addEventListener("click", this._clickHandler)
    }

    abrirModal(id) { document.getElementById(id).style.display = "flex" }
    cerrarModal(id) { document.getElementById(id).style.display = "none" }

    fechaInputToSap(v) { return v ? v.replaceAll("-", "") : "" }

    // =====================================================================
    // LISTA
    // =====================================================================
    async cargarLista() {
      const body = document.getElementById("frcqLotesBody")
      body.innerHTML = '<tr><td colspan="9" style="text-align:center;">Cargando...</td></tr>'
      try {
        const estado = document.getElementById("frcqFiltroEstado")?.value || ""
        const res = await window.PhotinoBridge.send({
          action: "recepcion.list",
          data: { estado, empresa: "FARET" }
        })
        if (!res || res.ok === false) throw new Error(res?.error || "Error cargando lotes")

        const items = res.data || []
        if (items.length === 0) {
          body.innerHTML = '<tr><td colspan="9" style="text-align:center;">Sin lotes registrados</td></tr>'
          return
        }

        body.innerHTML = items.map(l => `
          <tr>
            <td>${l.id}</td>
            <td>${l.fechaCreacion || "-"}</td>
            <td>${l.proveedor || "-"}</td>
            <td>${l.itemCode || "-"}</td>
            <td>${l.descripcion || "-"}</td>
            <td>${l.totalBobinas}</td>
            <td>${l.totalMuestreadas}</td>
            <td>${l.estado || "-"}</td>
            <td><button class="btn-secondary rcq-ver-btn" data-id="${l.id}">Ver</button></td>
          </tr>
        `).join("")
      } catch (err) {
        body.innerHTML = `<tr><td colspan="9" style="text-align:center;">${err.message}</td></tr>`
      }
    }

    // =====================================================================
    // NUEVO LOTE (solo Bobina vía SAP FARET_PRODUCCION)
    // =====================================================================
    abrirNuevoLote() {
      document.getElementById("frcqSapDesde").value = ""
      document.getElementById("frcqSapHasta").value = ""
      document.getElementById("frcqSapBody").innerHTML = '<tr><td colspan="9" style="text-align:center;">Consulta un rango de fechas</td></tr>'
      document.getElementById("frcqBobinasBloque").style.display = "none"
      document.getElementById("frcqBobinasLista").innerHTML = ""
      this._lineaSeleccionadaSap = null
      this.abrirModal("frcqModalNuevoLote")
    }

    async consultarSap() {
      const desde = this.fechaInputToSap(document.getElementById("frcqSapDesde").value)
      const hasta = this.fechaInputToSap(document.getElementById("frcqSapHasta").value)
      const body = document.getElementById("frcqSapBody")

      if (!desde || !hasta) {
        alert("Indica desde y hasta")
        return
      }

      body.innerHTML = '<tr><td colspan="9" style="text-align:center;">Consultando SAP...</td></tr>'
      try {
        const res = await window.PhotinoBridge.send({
          action: "recepcion.sap.consultar",
          data: { desde, hasta, empresa: "FARET" }
        })
        if (!res || res.ok === false) throw new Error(res?.error || "Error consultando SAP")

        this._resultadosSap = res.data || []
        if (this._resultadosSap.length === 0) {
          body.innerHTML = '<tr><td colspan="9" style="text-align:center;">Sin recepciones en ese rango</td></tr>'
          return
        }

        body.innerHTML = this._resultadosSap.map((r, idx) => `
          <tr>
            <td><button class="btn-secondary rcq-usar-linea-btn" data-idx="${idx}">Usar</button></td>
            <td>${r.fechaRecepcion || "-"}</td>
            <td>${r.proveedor || "-"}</td>
            <td>${r.guia || "-"}</td>
            <td>${r.itemCode || "-"}</td>
            <td>${r.descripcion || "-"}</td>
            <td>${r.cantidadRecibida ?? "-"}</td>
            <td>${r.anchoDeclarado ?? "-"}</td>
            <td>${r.gramajeDeclarado ?? "-"}</td>
          </tr>
        `).join("")
      } catch (err) {
        body.innerHTML = `<tr><td colspan="9" style="text-align:center;">${err.message}</td></tr>`
      }
    }

    async usarLineaSap(idx) {
      const linea = this._resultadosSap[idx]
      this._lineaSeleccionadaSap = linea

      const bloque = document.getElementById("frcqBobinasBloque")
      const lista = document.getElementById("frcqBobinasLista")
      lista.innerHTML = "Buscando bobinas..."
      bloque.style.display = "block"

      try {
        const res = await window.PhotinoBridge.send({
          action: "recepcion.sap.lotes",
          data: { itemCode: linea.itemCode, fecha: linea.fechaRecepcion, empresa: "FARET" }
        })
        if (!res || res.ok === false) throw new Error(res?.error || "Error buscando bobinas")

        const bobinas = res.data || []
        if (bobinas.length === 0) {
          lista.innerHTML = "No se encontraron bobinas para ese código y fecha (puedes escribir los números manualmente más adelante si hace falta)."
          return
        }

        lista.innerHTML = bobinas.map(b => `
          <label>
            <input type="checkbox" class="rcq-bobina-check" value="${b.numeroBobina}" checked>
            ${b.numeroBobina}
          </label>
        `).join("")
      } catch (err) {
        lista.innerHTML = err.message
      }
    }

    async crearLote() {
      if (!this._lineaSeleccionadaSap) {
        alert("Consulta SAP y usa una línea primero")
        return
      }
      const bobinas = Array.from(document.querySelectorAll("#frcqBobinasLista .rcq-bobina-check:checked")).map(el => el.value)
      if (bobinas.length === 0) {
        alert("Selecciona al menos una bobina")
        return
      }

      const data = {
        tipoMateriaPrima: "Bobina",
        empresa: "FARET",
        proveedor: this._lineaSeleccionadaSap.proveedor,
        guia: this._lineaSeleccionadaSap.guia,
        itemCode: this._lineaSeleccionadaSap.itemCode,
        descripcion: this._lineaSeleccionadaSap.descripcion,
        anchoDeclarado: this._lineaSeleccionadaSap.anchoDeclarado,
        gramajeDeclarado: this._lineaSeleccionadaSap.gramajeDeclarado,
        bobinas
      }

      try {
        const res = await window.PhotinoBridge.send({ action: "recepcion.crear", data })
        if (!res || res.ok === false) throw new Error(res?.error || "Error creando el lote")

        this.cerrarModal("frcqModalNuevoLote")
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
        const res = await window.PhotinoBridge.send({ action: "recepcion.detalle", data: { id, empresa: "FARET" } })
        if (!res || res.ok === false) throw new Error(res?.error || "Error cargando el detalle")

        this._loteActualId = id
        const l = res.data

        document.getElementById("frcqDetId").textContent = l.id
        document.getElementById("frcqDetResumen").innerHTML = `
          <div><b>${l.proveedor || "-"}</b>Proveedor</div>
          <div><b>${l.itemCode || "-"}</b>Código</div>
          <div><b>${l.descripcion || "-"}</b>Descripción</div>
          <div><b>${l.totalBobinas}</b>Bobinas en el lote</div>
          <div><b>${l.estado || "-"}</b>Estado</div>
        `

        document.getElementById("frcqDetNc").innerHTML = this.renderBloqueNc(l)

        document.getElementById("frcqPlanExistente").textContent = l.plan
          ? `Plan vigente: Nivel ${l.plan.nivelInspeccion}, AQL ${l.plan.aql}, letra ${l.plan.letraCodigo}, muestra=${l.plan.tamanoMuestra}, Ac=${l.plan.numeroAceptacion ?? "-"} Re=${l.plan.numeroRechazo ?? "-"}`
          : "Sin plan de muestreo generado todavía."

        const lista = document.getElementById("frcqBobinasLoteLista")
        const muestreadasSet = new Set((l.muestreadas || []).map(m => m.numeroBobina))
        lista.innerHTML = (l.bobinas || []).map(b => `
          <label>
            <input type="checkbox" class="rcq-bobina-check" value="${b}" ${muestreadasSet.has(b) ? "checked" : ""}>
            ${b}
          </label>
        `).join("") || "Este lote no tiene bobinas de SAP asociadas."

        document.getElementById("frcqMuestraInfo").textContent = l.muestraLaboratorioId
          ? `Ya existe una muestra de Laboratorio vinculada (ID ${l.muestraLaboratorioId}). Ábrela desde el módulo Laboratorio - Muestras.`
          : "Todavía no se ha creado una muestra de Laboratorio para este lote."

        this._planActual = l.plan
        this.abrirModal("frcqModalDetalle")
      } catch (err) {
        alert(err.message)
      }
    }

    renderBloqueNc(l) {
      if (l.estado !== "NoConforme") return ""

      if (l.ncId) {
        return `<div class="subtitle" style="margin-top:8px;">No Conformidad vinculada: <b>${l.ncCodigo || `#${l.ncId}`}</b> (gestiónala desde el módulo No Conformidades).</div>`
      }

      return `
        <div class="subtitle" style="margin-top:8px; display:flex; align-items:center; gap:10px;">
          <span>Este lote quedó No conforme y no tiene una No Conformidad vinculada.</span>
          <button class="btn-secondary" id="frcqBtnCrearNc">Crear No Conformidad</button>
        </div>
      `
    }

    async crearNoConformidad() {
      if (!this._loteActualId) return
      if (!confirm("¿Crear una No Conformidad vinculada a este lote?")) return

      try {
        const res = await window.PhotinoBridge.send({
          action: "recepcion.nc.crear",
          data: { loteId: this._loteActualId }
        })
        if (!res || res.ok === false) throw new Error(res?.error || "Error creando la No Conformidad")

        alert(`No Conformidad creada (${res.data.codigo}). Gestiónala desde el módulo No Conformidades.`)
        await this.abrirDetalle(this._loteActualId)
      } catch (err) {
        alert(err.message)
      }
    }

    async generarPlan() {
      if (!this._loteActualId) return
      const nivelInspeccion = document.getElementById("frcqPlanNivel").value
      const aql = parseFloat(document.getElementById("frcqPlanAql").value)

      try {
        const res = await window.PhotinoBridge.send({
          action: "recepcion.plan.generar",
          data: { loteId: this._loteActualId, nivelInspeccion, aql }
        })
        if (!res || res.ok === false) throw new Error(res?.error || "Error generando el plan")

        await this.abrirDetalle(this._loteActualId)
      } catch (err) {
        alert(err.message)
      }
    }

    seleccionAleatoria() {
      if (!this._planActual) {
        alert("Genera el plan de muestreo primero")
        return
      }
      const checks = Array.from(document.querySelectorAll("#frcqBobinasLoteLista .rcq-bobina-check"))
      checks.forEach(c => { c.checked = false })

      const n = Math.min(this._planActual.tamanoMuestra, checks.length)
      const indices = checks.map((_, i) => i)
      for (let i = indices.length - 1; i > 0; i--) {
        const j = Math.floor(Math.random() * (i + 1))
        ;[indices[i], indices[j]] = [indices[j], indices[i]]
      }
      indices.slice(0, n).forEach(i => { checks[i].checked = true })

      this._ultimaSeleccionAleatoria = true
    }

    async guardarMuestreadas() {
      if (!this._loteActualId) return
      const checks = Array.from(document.querySelectorAll("#frcqBobinasLoteLista .rcq-bobina-check:checked"))
      const seleccionadas = checks.map(c => c.value)

      if (seleccionadas.length === 0) {
        alert("Selecciona al menos una bobina muestreada")
        return
      }

      let criterioManual = null
      const tipo = this._ultimaSeleccionAleatoria ? "Aleatoria" : "Manual"
      if (tipo === "Manual" && this._planActual && seleccionadas.length !== this._planActual.tamanoMuestra) {
        criterioManual = prompt(
          `Seleccionaste ${seleccionadas.length} bobina(s), el plan pide ${this._planActual.tamanoMuestra}. Indica el motivo:`
        )
        if (!criterioManual) return
      }

      const data = {
        loteId: this._loteActualId,
        bobinas: seleccionadas.map(numeroBobina => ({ numeroBobina, seleccionTipo: tipo, criterioManual }))
      }

      try {
        const res = await window.PhotinoBridge.send({ action: "recepcion.bobinas.muestrear", data })
        if (!res || res.ok === false) throw new Error(res?.error || "Error guardando la selección")

        await this.abrirDetalle(this._loteActualId)
        await this.cargarLista()
      } catch (err) {
        alert(err.message)
      }
    }

    async crearMuestra() {
      if (!this._loteActualId) return
      try {
        const res = await window.PhotinoBridge.send({
          action: "recepcion.muestra.crear",
          data: { loteId: this._loteActualId, empresa: "FARET" }
        })
        if (!res || res.ok === false) throw new Error(res?.error || "Error creando la muestra de Laboratorio")

        alert(`Muestra de Laboratorio creada (ID ${res.data.muestraLaboratorioId}). Ábrela desde el módulo Laboratorio - Muestras.`)
        await this.abrirDetalle(this._loteActualId)
        await this.cargarLista()
      } catch (err) {
        alert(err.message)
      }
    }

    async actualizarEstado() {
      if (!this._loteActualId) return
      const estado = document.getElementById("frcqEstadoManual").value

      try {
        const res = await window.PhotinoBridge.send({
          action: "recepcion.estado.actualizar",
          data: { loteId: this._loteActualId, estado }
        })
        if (!res || res.ok === false) throw new Error(res?.error || "Error actualizando el estado")

        await this.abrirDetalle(this._loteActualId)
        await this.cargarLista()
      } catch (err) {
        alert(err.message)
      }
    }

    destroy() {
      console.log("DESTROY FARET RECEPCION CALIDAD")
      if (this._clickHandler) { document.removeEventListener("click", this._clickHandler); this._clickHandler = null }
    }
  }

  window.FaretRecepcionCalidadController = FaretRecepcionCalidadController
}
