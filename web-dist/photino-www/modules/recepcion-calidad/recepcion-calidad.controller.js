if (!window.RecepcionCalidadController) {
  class RecepcionCalidadController {
    constructor() {
      this._clickHandler = null
      this._changeHandler = null
      this._loteActualId = null
      this._loteActualTipo = null
      this._lineaSeleccionadaSap = null
      this._ultimaSeleccionAleatoria = false
    }

    async init() {
      console.log("INIT RECEPCION CALIDAD")
      this.bindEvents()
      await this.cargarLista()
    }

    bindEvents() {
      if (this._clickHandler) return

      this._clickHandler = (e) => {
        const id = e.target.id

        if (id === "rcqBtnNuevoLote") return this.abrirNuevoLote()
        if (id === "rcqNlCancelar") return this.cerrarModal("rcqModalNuevoLote")
        if (id === "rcqNlCrear") return this.crearLote()
        if (id === "rcqBtnConsultarSap") return this.consultarSap()
        if (id === "rcqBtnFiltrar") return this.cargarLista()

        if (e.target.classList?.contains("rcq-usar-linea-btn")) {
          const idx = parseInt(e.target.dataset.idx, 10)
          return this.usarLineaSap(idx)
        }
        if (e.target.classList?.contains("rcq-ver-btn")) {
          return this.abrirDetalle(parseInt(e.target.dataset.id, 10))
        }

        if (id === "rcqDetCerrar") return this.cerrarModal("rcqModalDetalle")
        if (id === "rcqBtnGenerarPlan") return this.generarPlan()
        if (id === "rcqBtnSeleccionAleatoria") return this.seleccionAleatoria()
        if (id === "rcqBtnGuardarMuestreadas") return this.guardarMuestreadas()
        if (id === "rcqBtnCrearMuestra") return this.crearMuestra()
        if (id === "rcqBtnActualizarEstado") return this.actualizarEstado()

        if (e.target.classList?.contains("rcq-bobina-check")) {
          this._ultimaSeleccionAleatoria = false
        }
        if (id === "rcqBtnVerFoto") return this.verFoto()
        if (id === "rcqBtnCrearNc") return this.crearNoConformidad()
      }
      document.addEventListener("click", this._clickHandler)

      this._changeHandler = (e) => {
        if (e.target.id === "rcqNlTipo") this.toggleTipoLote()
      }
      document.addEventListener("change", this._changeHandler)
    }

    abrirModal(id) { document.getElementById(id).style.display = "flex" }
    cerrarModal(id) { document.getElementById(id).style.display = "none" }

    fechaInputToSap(v) { return v ? v.replaceAll("-", "") : "" }

    // =====================================================================
    // LISTA
    // =====================================================================
    async cargarLista() {
      const body = document.getElementById("rcqLotesBody")
      body.innerHTML = '<tr><td colspan="10" style="text-align:center;">Cargando...</td></tr>'
      try {
        const estado = document.getElementById("rcqFiltroEstado")?.value || ""
        const res = await window.PhotinoBridge.send({ action: "recepcion.list", data: { estado } })
        if (!res || res.ok === false) throw new Error(res?.error || "Error cargando lotes")

        const items = res.data || []
        if (items.length === 0) {
          body.innerHTML = '<tr><td colspan="10" style="text-align:center;">Sin lotes registrados</td></tr>'
          return
        }

        body.innerHTML = items.map(l => `
          <tr>
            <td>${l.id}</td>
            <td>${l.fechaCreacion || "-"}</td>
            <td>${l.tipoMateriaPrima || "-"}</td>
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
        body.innerHTML = `<tr><td colspan="10" style="text-align:center;">${err.message}</td></tr>`
      }
    }

    // =====================================================================
    // NUEVO LOTE
    // =====================================================================
    abrirNuevoLote() {
      document.getElementById("rcqNlTipo").value = "Bobina"
      document.getElementById("rcqSapDesde").value = ""
      document.getElementById("rcqSapHasta").value = ""
      document.getElementById("rcqSapBody").innerHTML = '<tr><td colspan="9" style="text-align:center;">Consulta un rango de fechas</td></tr>'
      document.getElementById("rcqBobinasBloque").style.display = "none"
      document.getElementById("rcqBobinasLista").innerHTML = ""
      document.getElementById("rcqManProveedor").value = ""
      document.getElementById("rcqManGuia").value = ""
      document.getElementById("rcqManCodigo").value = ""
      document.getElementById("rcqManLote").value = ""
      document.getElementById("rcqManDescripcion").value = ""

      document.getElementById("rcqPvaNombre").value = ""
      document.getElementById("rcqPvaCantidadBins").value = ""
      document.getElementById("rcqPvaFecha").value = ""
      document.getElementById("rcqPvaCertificado").value = "Pendiente"
      document.getElementById("rcqPvaCondicion").value = "Conforme"
      document.getElementById("rcqPvaObservacion").value = ""
      document.getElementById("rcqPvaFoto").value = ""

      document.getElementById("rcqPfNp").value = ""
      document.getElementById("rcqPfCliente").value = ""
      document.getElementById("rcqPfProducto").value = ""
      document.getElementById("rcqPfCantidadTotal").value = ""
      document.getElementById("rcqPfCantidadVerde").value = ""
      document.getElementById("rcqPfCantidadAzul").value = ""
      document.getElementById("rcqPfCantidadRoja").value = ""
      document.getElementById("rcqPfEstadoCarpeta").value = "Recibida"
      document.getElementById("rcqPfCondicionVisual").value = ""
      document.getElementById("rcqPfTipoHallazgo").value = ""
      document.getElementById("rcqPfCantidadAfectada").value = ""
      document.getElementById("rcqPfObservacion").value = ""
      document.getElementById("rcqPfFoto").value = ""

      this._lineaSeleccionadaSap = null
      this.toggleTipoLote()
      this.abrirModal("rcqModalNuevoLote")
    }

    toggleTipoLote() {
      const tipo = document.getElementById("rcqNlTipo").value
      document.getElementById("rcqNlBloqueSap").style.display = tipo === "Bobina" ? "block" : "none"
      document.getElementById("rcqNlBloqueManual").style.display = tipo === "Bobina" ? "none" : "block"
      document.getElementById("rcqBloquePva").style.display = tipo === "PVA" ? "block" : "none"
      document.getElementById("rcqBloquePliego").style.display = tipo === "PliegoFaret" ? "block" : "none"
    }

    // Lee un <input type="file"> y devuelve su contenido en base64 (sin el prefijo data:...;base64,),
    // o null si no se seleccionó archivo.
    _leerFotoBase64(inputId) {
      const input = document.getElementById(inputId)
      const file = input?.files?.[0]
      if (!file) return Promise.resolve(null)

      return new Promise((resolve, reject) => {
        const reader = new FileReader()
        reader.onload = () => resolve(reader.result.split(",")[1] || null)
        reader.onerror = () => reject(new Error("No se pudo leer la fotografía"))
        reader.readAsDataURL(file)
      })
    }

    async consultarSap() {
      const desde = this.fechaInputToSap(document.getElementById("rcqSapDesde").value)
      const hasta = this.fechaInputToSap(document.getElementById("rcqSapHasta").value)
      const body = document.getElementById("rcqSapBody")

      if (!desde || !hasta) {
        alert("Indica desde y hasta")
        return
      }

      body.innerHTML = '<tr><td colspan="9" style="text-align:center;">Consultando SAP...</td></tr>'
      try {
        const res = await window.PhotinoBridge.send({ action: "recepcion.sap.consultar", data: { desde, hasta } })
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

      const bloque = document.getElementById("rcqBobinasBloque")
      const lista = document.getElementById("rcqBobinasLista")
      lista.innerHTML = "Buscando bobinas..."
      bloque.style.display = "block"

      try {
        const res = await window.PhotinoBridge.send({
          action: "recepcion.sap.lotes",
          data: { itemCode: linea.itemCode, fecha: linea.fechaRecepcion }
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
      const tipo = document.getElementById("rcqNlTipo").value
      let data

      if (tipo === "Bobina") {
        if (!this._lineaSeleccionadaSap) {
          alert("Consulta SAP y usa una línea primero")
          return
        }
        const bobinas = Array.from(document.querySelectorAll("#rcqBobinasLista .rcq-bobina-check:checked")).map(el => el.value)
        if (bobinas.length === 0) {
          alert("Selecciona al menos una bobina")
          return
        }
        data = {
          tipoMateriaPrima: "Bobina",
          proveedor: this._lineaSeleccionadaSap.proveedor,
          guia: this._lineaSeleccionadaSap.guia,
          itemCode: this._lineaSeleccionadaSap.itemCode,
          descripcion: this._lineaSeleccionadaSap.descripcion,
          anchoDeclarado: this._lineaSeleccionadaSap.anchoDeclarado,
          gramajeDeclarado: this._lineaSeleccionadaSap.gramajeDeclarado,
          bobinas
        }
      } else {
        data = {
          tipoMateriaPrima: tipo,
          proveedor: document.getElementById("rcqManProveedor").value.trim(),
          guia: document.getElementById("rcqManGuia").value.trim(),
          itemCode: document.getElementById("rcqManCodigo").value.trim(),
          loteProveedor: document.getElementById("rcqManLote").value.trim(),
          descripcion: document.getElementById("rcqManDescripcion").value.trim(),
          bobinas: []
        }

        try {
          if (tipo === "PVA") {
            data.pvaNombreAdhesivo = document.getElementById("rcqPvaNombre").value.trim()
            data.pvaCantidadBins = document.getElementById("rcqPvaCantidadBins").value || null
            data.pvaFechaFabricacionVencimiento = document.getElementById("rcqPvaFecha").value || null
            data.pvaCertificadoCalidad = document.getElementById("rcqPvaCertificado").value
            data.pvaCondicionGeneral = document.getElementById("rcqPvaCondicion").value
            data.pvaObservacion = document.getElementById("rcqPvaObservacion").value.trim()
            data.pvaFotoBase64 = await this._leerFotoBase64("rcqPvaFoto")
          } else if (tipo === "PliegoFaret") {
            const total = parseFloat(document.getElementById("rcqPfCantidadTotal").value) || 0
            const verde = parseFloat(document.getElementById("rcqPfCantidadVerde").value) || 0
            const azul = parseFloat(document.getElementById("rcqPfCantidadAzul").value) || 0
            const roja = parseFloat(document.getElementById("rcqPfCantidadRoja").value) || 0

            if (total && (verde + azul + roja) !== total) {
              alert("Cantidad verde + azul + roja debe ser igual a la cantidad total")
              return
            }

            data.pfNp = document.getElementById("rcqPfNp").value.trim()
            data.pfCliente = document.getElementById("rcqPfCliente").value.trim()
            data.pfProducto = document.getElementById("rcqPfProducto").value.trim()
            data.pfCantidadTotal = total || null
            data.pfCantidadVerde = verde || null
            data.pfCantidadAzul = azul || null
            data.pfCantidadRoja = roja || null
            data.pfEstadoCarpeta = document.getElementById("rcqPfEstadoCarpeta").value
            data.pfCondicionVisual = document.getElementById("rcqPfCondicionVisual").value.trim()
            data.pfTipoHallazgo = document.getElementById("rcqPfTipoHallazgo").value
            data.pfCantidadAfectada = document.getElementById("rcqPfCantidadAfectada").value || null
            data.pfObservacion = document.getElementById("rcqPfObservacion").value.trim()
            data.pfFotoBase64 = await this._leerFotoBase64("rcqPfFoto")
          }
        } catch (err) {
          alert(err.message)
          return
        }
      }

      try {
        const res = await window.PhotinoBridge.send({ action: "recepcion.crear", data })
        if (!res || res.ok === false) throw new Error(res?.error || "Error creando el lote")

        this.cerrarModal("rcqModalNuevoLote")
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
        const res = await window.PhotinoBridge.send({ action: "recepcion.detalle", data: { id } })
        if (!res || res.ok === false) throw new Error(res?.error || "Error cargando el detalle")

        this._loteActualId = id
        const l = res.data

        document.getElementById("rcqDetId").textContent = l.id
        document.getElementById("rcqDetResumen").innerHTML = `
          <div><b>${l.tipoMateriaPrima || "-"}</b>Tipo</div>
          <div><b>${l.proveedor || "-"}</b>Proveedor</div>
          <div><b>${l.itemCode || "-"}</b>Código</div>
          <div><b>${l.descripcion || "-"}</b>Descripción</div>
          <div><b>${l.totalBobinas}</b>Bobinas en el lote</div>
          <div><b>${l.estado || "-"}</b>Estado</div>
        `

        document.getElementById("rcqPlanExistente").textContent = l.plan
          ? `Plan vigente: Nivel ${l.plan.nivelInspeccion}, AQL ${l.plan.aql}, letra ${l.plan.letraCodigo}, muestra=${l.plan.tamanoMuestra}, Ac=${l.plan.numeroAceptacion ?? "-"} Re=${l.plan.numeroRechazo ?? "-"}`
          : "Sin plan de muestreo generado todavía."

        const lista = document.getElementById("rcqBobinasLoteLista")
        const muestreadasSet = new Set((l.muestreadas || []).map(m => m.numeroBobina))
        lista.innerHTML = (l.bobinas || []).map(b => `
          <label>
            <input type="checkbox" class="rcq-bobina-check" value="${b}" ${muestreadasSet.has(b) ? "checked" : ""}>
            ${b}
          </label>
        `).join("") || "Este lote no tiene bobinas de SAP asociadas (materia prima manual)."

        this._loteActualTipo = l.tipoMateriaPrima
        document.getElementById("rcqDetEspecifico").innerHTML = this.renderDetalleEspecifico(l)
        document.getElementById("rcqDetNc").innerHTML = this.renderBloqueNc(l)

        document.getElementById("rcqMuestraInfo").textContent = l.muestraLaboratorioId
          ? `Ya existe una muestra de Laboratorio vinculada (ID ${l.muestraLaboratorioId}). Ábrela desde el módulo Laboratorio - Muestras.`
          : "Todavía no se ha creado una muestra de Laboratorio para este lote."

        this._planActual = l.plan
        this.abrirModal("rcqModalDetalle")
      } catch (err) {
        alert(err.message)
      }
    }

    renderDetalleEspecifico(l) {
      if (l.tipoMateriaPrima === "PVA" && l.pva) {
        const p = l.pva
        return `
          <div class="module-title" style="font-size:14px; margin-top:14px;">Adhesivo PVA</div>
          <div class="rcq-detalle-resumen">
            <div><b>${p.nombreAdhesivo || "-"}</b>Nombre del adhesivo</div>
            <div><b>${p.cantidadBins ?? "-"}</b>Cantidad de bins</div>
            <div><b>${p.fechaFabricacionVencimiento || "-"}</b>Fecha fabricación/vencimiento</div>
            <div><b>${p.certificadoCalidad || "-"}</b>Certificado de calidad</div>
            <div><b>${p.condicionGeneral || "-"}</b>Condición general</div>
          </div>
          ${p.observacion ? `<div class="subtitle">Observación: ${p.observacion}</div>` : ""}
          ${p.tieneFoto ? `<button class="btn-secondary" id="rcqBtnVerFoto">Ver fotografía</button>` : ""}
        `
      }

      if (l.tipoMateriaPrima === "PliegoFaret" && l.pliegoFaret) {
        const p = l.pliegoFaret
        return `
          <div class="module-title" style="font-size:14px; margin-top:14px;">Pliegos impresos Faret</div>
          <div class="rcq-detalle-resumen">
            <div><b>${p.np || "-"}</b>NP</div>
            <div><b>${p.cliente || "-"}</b>Cliente</div>
            <div><b>${p.producto || "-"}</b>Producto</div>
            <div><b>${p.cantidadTotal ?? "-"}</b>Cantidad total</div>
            <div><b>${p.cantidadVerde ?? "-"}</b>Cantidad verde</div>
            <div><b>${p.cantidadAzul ?? "-"}</b>Cantidad azul</div>
            <div><b>${p.cantidadRoja ?? "-"}</b>Cantidad roja</div>
            <div><b>${p.estadoCarpeta || "-"}</b>Estado de la carpeta</div>
            <div><b>${p.condicionVisual || "-"}</b>Condición visual</div>
            <div><b>${p.tipoHallazgo || "(sin hallazgo)"}</b>Tipo de hallazgo</div>
            <div><b>${p.cantidadAfectada ?? "-"}</b>Cantidad afectada</div>
          </div>
          ${p.observacion ? `<div class="subtitle">Observación: ${p.observacion}</div>` : ""}
          ${p.tieneFoto ? `<button class="btn-secondary" id="rcqBtnVerFoto">Ver fotografía</button>` : ""}
        `
      }

      return ""
    }

    async verFoto() {
      if (!this._loteActualId || !this._loteActualTipo) return
      try {
        const res = await window.PhotinoBridge.send({
          action: "recepcion.foto.abrir",
          data: { loteId: this._loteActualId, tipoMateriaPrima: this._loteActualTipo }
        })
        if (!res || res.ok === false) throw new Error(res?.error || "Error abriendo la fotografía")

        this.mostrarFoto(`data:${res.data.mime};base64,${res.data.base64}`)
      } catch (err) {
        alert(err.message)
      }
    }

    // Mismo patrón visual que mostrarImagenRegistroControl (Registros de Control): overlay
    // oscuro + tarjeta blanca + botón Cerrar, anclado a document.body.
    mostrarFoto(dataUrl) {
      const existente = document.getElementById("rcqModalFoto")
      if (existente) existente.remove()

      const modal = document.createElement("div")
      modal.id = "rcqModalFoto"
      modal.style.cssText = "position:fixed;left:0;top:0;width:100%;height:100%;background:rgba(15,23,42,0.75);z-index:9999;display:flex;align-items:center;justify-content:center;padding:24px;"

      modal.innerHTML = `
        <div style="background:#fff;border-radius:12px;max-width:90%;max-height:90%;padding:16px;box-shadow:0 20px 60px rgba(0,0,0,0.35);position:relative;">
          <div style="display:flex;justify-content:space-between;align-items:center;gap:12px;margin-bottom:12px;">
            <strong>Fotografía</strong>
            <button id="rcqBtnCerrarFoto" class="btn-secondary" type="button">Cerrar</button>
          </div>
          <img src="${dataUrl}" alt="Fotografía" style="display:block;max-width:100%;max-height:75vh;object-fit:contain;border-radius:8px;">
        </div>
      `
      document.body.appendChild(modal)
      document.getElementById("rcqBtnCerrarFoto").addEventListener("click", () => modal.remove())
    }

    // Vínculo a No Conformidades: solo aparece cuando el lote quedó "NoConforme".
    renderBloqueNc(l) {
      if (l.estado !== "NoConforme") return ""

      if (l.ncId) {
        return `<div class="subtitle" style="margin-top:8px;">No Conformidad vinculada: <b>${l.ncCodigo || `#${l.ncId}`}</b> (gestiónala desde el módulo No Conformidades).</div>`
      }

      return `
        <div class="subtitle" style="margin-top:8px; display:flex; align-items:center; gap:10px;">
          <span>Este lote quedó No conforme y no tiene una No Conformidad vinculada.</span>
          <button class="btn-secondary" id="rcqBtnCrearNc">Crear No Conformidad</button>
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
      const nivelInspeccion = document.getElementById("rcqPlanNivel").value
      const aql = parseFloat(document.getElementById("rcqPlanAql").value)

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
      const checks = Array.from(document.querySelectorAll("#rcqBobinasLoteLista .rcq-bobina-check"))
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
      const checks = Array.from(document.querySelectorAll("#rcqBobinasLoteLista .rcq-bobina-check:checked"))
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
        const res = await window.PhotinoBridge.send({ action: "recepcion.muestra.crear", data: { loteId: this._loteActualId } })
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
      const estado = document.getElementById("rcqEstadoManual").value

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
      console.log("DESTROY RECEPCION CALIDAD")
      if (this._clickHandler) { document.removeEventListener("click", this._clickHandler); this._clickHandler = null }
      if (this._changeHandler) { document.removeEventListener("change", this._changeHandler); this._changeHandler = null }
    }
  }

  window.RecepcionCalidadController = RecepcionCalidadController
}
