if (!window.MuestraLaboratorioController) {
  class MuestraLaboratorioController {
    constructor() {
      this._clickHandler = null
      this._changeHandler = null
      this._muestraActualId = null
      this._corrigiendoEnsayoId = null
      this.deepLinkEstado = null
      // Catálogos reales (máquinas/tipos de onda) y snapshot FPS de la muestra en edición —
      // ver _consultarNp/_consultarRegistroProduccion.
      this._catalogos = { maquinas: [], tiposOnda: [], metodos: [] }
      this._fpsProcesos = []
      this._fpsRegistros = []
      this._fpsSnapshot = null
    }

    async init() {
      console.log("INIT MUESTRA LABORATORIO")
      this.consumirDeepLink()
      this.bindEvents()
      await this.cargarCatalogos()
      await this.cargarLista()
      await this.cargarIndicadores()
    }

    // Máquinas (mismo catálogo que Talleres Externos/Producto Terminado) y tipos de onda (mismo
    // catálogo que ya usa la app móvil Flutter) — se cargan una vez al iniciar el módulo.
    async cargarCatalogos() {
      try {
        const res = await window.PhotinoBridge.send({ action: "muestraLab.catalogos", data: {} })
        if (!res || res.ok === false) return
        this._catalogos = res.data || { maquinas: [], tiposOnda: [] }

        const selMaquina = document.getElementById("mlbNmMaquina")
        const selOnda = document.getElementById("mlbNmTipoOnda")
        const selFiltroMaquina = document.getElementById("mlbFiltroMaquina")
        if (selMaquina) {
          selMaquina.innerHTML = `<option value="">-- Seleccionar --</option>` +
            this._catalogos.maquinas.map(m => `<option value="${m.id}">${this._esc(m.nombre)}</option>`).join("")
        }
        if (selOnda) {
          selOnda.innerHTML = `<option value="">-- Seleccionar --</option>` +
            this._catalogos.tiposOnda.map(t => `<option value="${t.id}">${this._esc(t.nombre)}</option>`).join("")
        }
        if (selFiltroMaquina) {
          selFiltroMaquina.innerHTML = `<option value="">Todas</option>` +
            this._catalogos.maquinas.map(m => `<option value="${m.id}">${this._esc(m.nombre)}</option>`).join("")
        }
      } catch {
        // Sin catálogos no se bloquea el módulo — los selects quedan solo con "-- Seleccionar --".
      }
    }

    _esc(v) {
      if (v === null || v === undefined) return ""
      return String(v).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
    }

    // Solo etiqueta visible — el valor real guardado en BD (m.estado) no cambia, para no migrar
    // histórico ni romper el filtro. Alinea con la terminología del REG-LAB-04 (punto 34).
    _labelEstado(estado) {
      const map = { "En analisis": "En ensayo", "Finalizada": "Finalizado" }
      return map[estado] || estado || "-"
    }

    // Si "Gestionar" de una alerta en Inicio dejó un filtro de estado pendiente para este
    // módulo, lo toma y lo borra de inmediato (mismo mecanismo que registros-control).
    consumirDeepLink() {
      const raw = sessionStorage.getItem("qccDeepLinkId")
      if (!raw) return

      try {
        const info = JSON.parse(raw)
        if (info?.modulo === "muestra-laboratorio" && info.estadoFiltro) {
          this.deepLinkEstado = info.estadoFiltro
        }
      } catch { /* ignorar */ }

      sessionStorage.removeItem("qccDeepLinkId")
    }

    quitarDeepLink() {
      this.deepLinkEstado = null
      this.cargarLista()
    }

    renderDeepLinkBanner(total) {
      const banner = document.getElementById("mlbDeepLinkBanner")
      if (!banner) return

      if (!this.deepLinkEstado) {
        banner.style.display = "none"
        banner.innerHTML = ""
        return
      }

      banner.style.display = "block"
      banner.innerHTML = `
        <div class="card" style="
          border-left:4px solid #3b82f6;
          background:#eff6ff;
          margin-bottom:16px;
          display:flex;
          justify-content:space-between;
          align-items:center;
          gap:12px;
        ">
          <span>Mostrando ${total} muestra(s) pendiente(s) desde una alerta de Inicio.</span>
          <button class="btn-secondary" id="mlbBtnVerTodosDeepLink">Ver todas</button>
        </div>
      `
    }

    bindEvents() {
      if (this._clickHandler) return

      this._clickHandler = (e) => {
        const id = e.target.id

        if (id === "mlbBtnMetodos") return this.abrirMetodos()
        if (id === "mlbMetodoCerrar") return this.cerrarModal("mlbModalMetodos")
        if (id === "mlbMetodoLimpiar") return this.limpiarFormMetodo()
        if (id === "mlbMetodoGuardar") return this.guardarMetodo()
        if (e.target.classList?.contains("mlb-metodo-editar-btn")) {
          return this.editarMetodo(JSON.parse(e.target.dataset.metodo))
        }
        if (e.target.classList?.contains("mlb-metodo-toggle-btn")) {
          return this.toggleMetodo(parseInt(e.target.dataset.id, 10), e.target.dataset.activo === "true")
        }

        if (id === "mlbBtnEspecificaciones") return this.abrirEspecificaciones()
        if (id === "mlbEspecCerrar") return this.cerrarModal("mlbModalEspecificaciones")
        if (id === "mlbEspecLimpiar") return this.limpiarFormEspecificacion()
        if (id === "mlbEspecGuardar") return this.guardarEspecificacion()
        if (e.target.classList?.contains("mlb-espec-editar-btn")) {
          return this.editarEspecificacion(JSON.parse(e.target.dataset.espec))
        }
        if (e.target.classList?.contains("mlb-espec-toggle-btn")) {
          return this.toggleEspecificacion(parseInt(e.target.dataset.id, 10), e.target.dataset.activo === "true")
        }

        if (id === "mlbBtnNuevaMuestra") return this.abrirNuevaMuestra()
        if (id === "mlbNmCancelar") return this.cerrarModal("mlbModalNuevaMuestra")
        if (id === "mlbNmGuardar") return this.guardarNuevaMuestra()
        if (id === "mlbNmBtnConsultarNp") return this._consultarNp()
        if (id === "mlbNmBtnConsultarProduccion") return this._consultarRegistroProduccion()
        if (e.target.classList?.contains("mlb-btn-resolver-bobina")) {
          return this._resolverBobina(e.target.dataset.input, e.target.dataset.info)
        }

        if (id === "mlbBtnFiltrar") return this.cargarLista()
        if (id === "mlbBtnLimpiarFiltros") return this.limpiarFiltros()
        if (id === "mlbBtnVerTodosDeepLink") return this.quitarDeepLink()

        if (e.target.classList?.contains("mlb-ver-btn")) {
          return this.abrirDetalle(parseInt(e.target.dataset.id, 10))
        }
        if (e.target.classList?.contains("mlb-eliminar-btn")) {
          return this.eliminarMuestra(parseInt(e.target.dataset.id, 10))
        }
        if (id === "mlbDetCerrar") return this.cerrarModal("mlbModalDetalle")
        if (id === "mlbBtnInforme") return this.generarInforme()
        if (id === "mlbDetBtnEditarFecha") return this._abrirEditarFecha()
        if (id === "mlbDetBtnAnularRegistro") return this.anularMuestra()
        if (id === "mlbDetBtnVerRegistroProduccion") return this.verRegistroProduccion()
        if (id === "mlbDetBtnMaterialesFps") return this._verMaterialesFps()
        if (id === "mlbRegProdCerrar") return this.cerrarModal("mlbModalRegistroProduccion")
        if (e.target.classList?.contains("mlb-regprod-ver-btn")) {
          this.cerrarModal("mlbModalRegistroProduccion")
          return this.abrirDetalle(parseInt(e.target.dataset.id, 10))
        }
        if (id === "mlbNmBtnBuscarMonotapa") return this._buscarMonotapaRelacionada()
        if (id === "mlbEditarFechaCancelar") return this._cerrarEditarFecha()
        if (id === "mlbEditarFechaGuardar") return this._guardarFechaEnsayo()
        if (id === "mlbBtnCrearNc") return this.crearNoConformidad()

        if (id === "mlbBtnNuevoHumedad") return this.abrirModal("mlbModalHumedad")
        if (id === "mlbHumCancelar") return this.cerrarModal("mlbModalHumedad")
        if (id === "mlbHumGuardar") return this.guardarHumedad()

        if (id === "mlbBtnNuevoGramaje") return this.abrirModal("mlbModalGramaje")
        if (id === "mlbGraCancelar") return this.cerrarModal("mlbModalGramaje")
        if (id === "mlbGraGuardar") return this.guardarGramaje()

        if (id === "mlbBtnNuevoCobb") return this.abrirModal("mlbModalCobb")
        if (id === "mlbCobbCancelar") return this.cerrarModal("mlbModalCobb")
        if (id === "mlbCobbGuardar") return this.guardarCobb()

        if (id === "mlbBtnNuevoEspesor") return this.abrirModal("mlbModalEspesor")
        if (id === "mlbEspCancelar") return this.cerrarModal("mlbModalEspesor")
        if (id === "mlbEspGuardar") return this.guardarEspesor()

        if (id === "mlbBtnNuevoRct") return this.abrirModal("mlbModalRct")
        if (id === "mlbRctCancelar") return this.cerrarModal("mlbModalRct")
        if (id === "mlbRctGuardar") return this.guardarRct()

        if (id === "mlbBtnNuevoFct") return this.abrirModal("mlbModalFct")
        if (id === "mlbFctCancelar") return this.cerrarModal("mlbModalFct")
        if (id === "mlbFctGuardar") return this.guardarFct()

        if (id === "mlbBtnNuevoEct") return this.abrirModal("mlbModalEct")
        if (id === "mlbEctCancelar") return this.cerrarModal("mlbModalEct")
        if (id === "mlbEctGuardar") return this.guardarEct()

        if (id === "mlbBtnNuevoBctMedido") return this.abrirModalBctMedido()
        if (id === "mlbBctMedCancelar") return this.cerrarModal("mlbModalBctMedido")
        if (id === "mlbBctMedGuardar") return this.guardarBctMedido()

        if (id === "mlbBtnNuevoBctTeorico") return this.abrirModalBctTeorico()
        if (id === "mlbBctTeoCancelar") return this.cerrarModal("mlbModalBctTeorico")
        if (id === "mlbBctTeoGuardar") return this.guardarBctTeorico()

        if (id === "mlbBtnNuevoViscosidad") return this.abrirModal("mlbModalViscosidad")
        if (id === "mlbViscCancelar") return this.cerrarModal("mlbModalViscosidad")
        if (id === "mlbViscGuardar") return this.guardarViscosidad()

        if (id === "mlbBtnNuevoPh") return this.abrirModal("mlbModalPh")
        if (id === "mlbPhCancelar") return this.cerrarModal("mlbModalPh")
        if (id === "mlbPhGuardar") return this.guardarPh()

        if (id === "mlbBtnNuevoSolidos") return this.abrirModal("mlbModalSolidos")
        if (id === "mlbSolCancelar") return this.cerrarModal("mlbModalSolidos")
        if (id === "mlbSolGuardar") return this.guardarSolidos()

        if (id === "mlbBtnNuevoLugol") return this.abrirModal("mlbModalLugol")
        if (id === "mlbLugolCancelar") return this.cerrarModal("mlbModalLugol")
        if (id === "mlbLugolGuardar") return this.guardarLugol()

        if (e.target.classList?.contains("mlb-anular-btn")) {
          return this.anularEnsayo(parseInt(e.target.dataset.id, 10))
        }
        if (e.target.classList?.contains("mlb-historial-btn")) {
          return this._verHistorialEnsayo(parseInt(e.target.dataset.id, 10))
        }
        if (e.target.classList?.contains("mlb-corregir-btn")) {
          return this.corregirEnsayo(parseInt(e.target.dataset.id, 10))
        }

        if (e.target.classList?.contains("mlb-bob-agregar")) {
          return this._agregarFilaBobina(e.target.dataset.container, e.target.dataset.labels)
        }
        if (e.target.classList?.contains("mlb-bob-quitar")) {
          return e.target.closest(".mlb-bobina-row")?.remove()
        }

        if (id === "mlbBtnAdjuntar") return this._pedirArchivoAdjunto()
        if (id === "mlbAdjuntoCerrar") return this.cerrarModal("mlbModalAdjunto")
        if (e.target.classList?.contains("mlb-ver-adjunto-btn")) {
          return this._verAdjunto(parseInt(e.target.dataset.adjuntoId, 10))
        }
        if (e.target.classList?.contains("mlb-eliminar-adjunto-btn")) {
          return this._eliminarAdjunto(parseInt(e.target.dataset.adjuntoId, 10))
        }
      }
      document.addEventListener("click", this._clickHandler)

      this._changeHandler = (e) => {
        if (e.target.id === "mlbHumMetodoEquipo") {
          this.actualizarCamposHumedad()
          this._refrescarMetodoAuto("HUMEDAD", "mlbHumMetodo", e.target.value)
        }
        if (e.target.id === "mlbBctMedCajas") this.renderBctMedCajas()
        if (e.target.id === "mlbNmProceso") this._onSeleccionarProcesoFps()
        if (e.target.id === "mlbNmRegistroProduccion") this._onSeleccionarRegistroProduccion()
        if (e.target.id === "mlbNmOrigen") {
          document.getElementById("mlbNmPruebaSolicitadaBloque").style.display = e.target.value === "Pruebas" ? "block" : "none"
          document.getElementById("mlbNmMonotapaBloque").style.display = e.target.value === "Monotapa" ? "block" : "none"
          document.getElementById("mlbNmEmplacadoBloque").style.display = e.target.value === "Emplacado" ? "block" : "none"
        }
        if (e.target.id === "mlbAdjuntoInput") {
          const file = e.target.files[0]
          if (file) this._subirAdjunto(file)
        }
      }
      document.addEventListener("change", this._changeHandler)
    }

    abrirModal(id) {
      document.getElementById(id).style.display = "flex"

      // Punto 32 del REG-LAB-04: el método ya no lo escribe el analista — se muestra (bloqueado)
      // el que aplicará el backend al guardar, resuelto desde el maestro de métodos.
      const metodoPorModal = {
        mlbModalHumedad: () => this._refrescarMetodoAuto("HUMEDAD", "mlbHumMetodo", document.getElementById("mlbHumMetodoEquipo")?.value),
        mlbModalGramaje: () => this._refrescarMetodoAuto("GRAMAJE", "mlbGraMetodo"),
        mlbModalCobb: () => this._refrescarMetodoAuto("COBB", "mlbCobbMetodo"),
        mlbModalEspesor: () => this._refrescarMetodoAuto("ESPESOR", "mlbEspMetodo"),
        mlbModalRct: () => this._refrescarMetodoAuto("RCT", "mlbRctMetodo"),
        mlbModalFct: () => this._refrescarMetodoAuto("FCT", "mlbFctMetodo"),
        mlbModalEct: () => this._refrescarMetodoAuto("ECT", "mlbEctMetodo"),
        mlbModalBctMedido: () => this._refrescarMetodoAuto("BCT_MEDIDO", "mlbBctMedMetodo"),
      }
      if (metodoPorModal[id]) metodoPorModal[id]()

      // Punto 11 REG-LAB-04: al abrir Humedad/Gramaje/Espesor "en blanco" (no en modo corrección,
      // que ya precarga sus propias bobinas vía _precargarXxx) se limpia el muestreo de bobinas.
      const bobinasContainerPorModal = {
        mlbModalHumedad: "mlbHumBobinasFilas",
        mlbModalGramaje: "mlbGraBobinasFilas",
        mlbModalEspesor: "mlbEspBobinasFilas",
      }
      if (bobinasContainerPorModal[id] && !this._corrigiendoEnsayoId) {
        document.getElementById(bobinasContainerPorModal[id]).innerHTML = ""
      }
    }

    // ---------- Muestreo de 3+ bobinas (Punto 11 del REG-LAB-04) ----------

    _renderFilaBobina(labels) {
      const [l1, l2, l3] = labels
      const div = document.createElement("div")
      div.className = "mlb-form-row mlb-bobina-row"
      div.innerHTML = `
        <div class="mlb-form-campo">
          <label>N° bobina</label><input type="text" class="mlb-bob-numero">
          <div class="mlb-bob-historial" style="font-size:11px;color:#b45309;"></div>
        </div>
        <div class="mlb-form-campo"><label>Lote</label><input type="text" class="mlb-bob-lote"></div>
        <div class="mlb-form-campo"><label>Posición</label>
          <select class="mlb-bob-posicion">
            <option value="Onda">Onda</option>
            <option value="Liner">Liner</option>
            <option value="Cartulina">Cartulina</option>
          </select>
        </div>
        <div class="mlb-form-campo"><label>${l1}</label><input type="number" step="0.0001" class="mlb-bob-v1"></div>
        <div class="mlb-form-campo"><label>${l2}</label><input type="number" step="0.0001" class="mlb-bob-v2"></div>
        <div class="mlb-form-campo"><label>${l3}</label><input type="number" step="0.0001" class="mlb-bob-v3"></div>
        <div class="mlb-form-campo" style="align-self:flex-end;"><button type="button" class="btn-secondary mlb-bob-quitar">Quitar</button></div>
      `
      div.querySelector(".mlb-bob-numero").addEventListener("blur", e => this._chequearHistorialBobina(e.target))
      return div
    }

    // Punto 12 del REG-LAB-04: avisa (sin bloquear el registro) si la bobina ya fue muestreada
    // antes en otra producción. Best-effort — un error de red acá no debe impedir guardar el ensayo.
    async _chequearHistorialBobina(inputEl) {
      const notaEl = inputEl.parentElement.querySelector(".mlb-bob-historial")
      if (!notaEl) return
      notaEl.textContent = ""

      const numero = inputEl.value.trim()
      if (!numero || !this._muestraActualId) return

      try {
        const res = await window.PhotinoBridge.send({
          action: "muestraLab.bobinaHistorial",
          data: { numeroBobina: numero, excluirMuestraId: this._muestraActualId }
        })
        if (!res || res.ok === false) return

        const historial = res.data || []
        if (historial.length === 0) return

        const primero = historial[0]
        const extra = historial.length > 1 ? ` (+${historial.length - 1} más)` : ""
        notaEl.textContent = `⚠ Ya analizada antes: NP ${primero.np}, ${primero.fechaEnsayo || primero.fechaIngreso} (${primero.tipoEnsayo})${extra}`
      } catch {
        // Best-effort, ver comentario del método.
      }
    }

    _agregarFilaBobina(containerId, labelsCsv) {
      const contenedor = document.getElementById(containerId)
      if (!contenedor) return
      contenedor.appendChild(this._renderFilaBobina(labelsCsv.split(",")))
    }

    // Recolecta las filas con al menos un campo con datos — filas vacías (el analista agregó una
    // de más y no la usó) se ignoran en vez de mandarlas como bobina en blanco.
    _leerBobinas(containerId) {
      const contenedor = document.getElementById(containerId)
      if (!contenedor) return []

      const num = (input) => (input.value === "" ? null : parseFloat(input.value))
      const filas = []
      contenedor.querySelectorAll(".mlb-bobina-row").forEach(row => {
        const numeroBobina = row.querySelector(".mlb-bob-numero").value.trim()
        const lote = row.querySelector(".mlb-bob-lote").value.trim()
        const posicion = row.querySelector(".mlb-bob-posicion").value
        const valor1 = num(row.querySelector(".mlb-bob-v1"))
        const valor2 = num(row.querySelector(".mlb-bob-v2"))
        const valor3 = num(row.querySelector(".mlb-bob-v3"))
        if (!numeroBobina && !lote && valor1 === null && valor2 === null && valor3 === null) return
        filas.push({ numeroBobina: numeroBobina || null, lote: lote || null, posicion, valor1, valor2, valor3 })
      })
      return filas
    }

    _refrescarMetodoAuto(tipoEnsayo, inputId, variante) {
      const input = document.getElementById(inputId)
      if (!input) return

      const candidatos = (this._catalogos.metodos || []).filter(m => m.tipoEnsayo === tipoEnsayo)
      const match = variante ? candidatos.find(m => m.variante === variante) : candidatos.find(m => !m.variante)

      if (!match) {
        input.value = "(sin método configurado en el maestro)"
        return
      }
      const codigoVersion = [match.codigo, match.version ? `v${match.version}` : null].filter(Boolean).join(" ")
      input.value = codigoVersion ? `${match.nombre} (${codigoVersion})` : match.nombre
    }
    cerrarModal(id) {
      document.getElementById(id).style.display = "none"
      this._limpiarCorreccion()
    }

    // =====================================================================
    // LISTA
    // =====================================================================
    limpiarFiltros() {
      document.getElementById("mlbFiltroEstado").value = ""
      document.getElementById("mlbFiltroNp").value = ""
      document.getElementById("mlbFiltroFechaDesde").value = ""
      document.getElementById("mlbFiltroFechaHasta").value = ""
      document.getElementById("mlbFiltroCliente").value = ""
      document.getElementById("mlbFiltroCodigo").value = ""
      document.getElementById("mlbFiltroDescripcion").value = ""
      document.getElementById("mlbFiltroOrigen").value = ""
      document.getElementById("mlbFiltroMaquina").value = ""
      document.getElementById("mlbFiltroAnalista").value = ""
      document.getElementById("mlbFiltroBobina").value = ""
      this.deepLinkEstado = null
      this.cargarLista()
    }

    async cargarLista() {
      const body = document.getElementById("mlbMuestrasBody")
      body.innerHTML = '<tr><td colspan="11" style="text-align:center;">Cargando...</td></tr>'

      try {
        const estado = this.deepLinkEstado || document.getElementById("mlbFiltroEstado")?.value || ""
        const np = document.getElementById("mlbFiltroNp")?.value?.trim() || ""
        const fechaDesde = document.getElementById("mlbFiltroFechaDesde")?.value || ""
        const fechaHasta = document.getElementById("mlbFiltroFechaHasta")?.value || ""
        const cliente = document.getElementById("mlbFiltroCliente")?.value?.trim() || ""
        const codigoProducto = document.getElementById("mlbFiltroCodigo")?.value?.trim() || ""
        const descripcion = document.getElementById("mlbFiltroDescripcion")?.value?.trim() || ""
        const origen = document.getElementById("mlbFiltroOrigen")?.value || ""
        const maquinaIdFiltro = document.getElementById("mlbFiltroMaquina")?.value || ""
        const analistaNombre = document.getElementById("mlbFiltroAnalista")?.value?.trim() || ""
        const bobina = document.getElementById("mlbFiltroBobina")?.value?.trim() || ""

        const res = await window.PhotinoBridge.send({
          action: "muestraLab.list",
          data: {
            estado, np, fechaDesde, fechaHasta, cliente, codigoProducto, descripcion, origen,
            maquinaId: maquinaIdFiltro ? parseInt(maquinaIdFiltro, 10) : null,
            analistaNombre, bobina
          }
        })

        if (!res || res.ok === false) throw new Error(res?.error || "Error cargando muestras")

        const items = res.data || []
        this.renderDeepLinkBanner(items.length)

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
            <td>${this._labelEstado(m.estado)}</td>
            <td>${m.evaluacion || "-"}</td>
            <td>
              <button class="btn-secondary mlb-ver-btn" data-id="${m.id}">Ver</button>
              <button class="btn-danger mlb-eliminar-btn" data-id="${m.id}">Eliminar</button>
            </td>
          </tr>
        `).join("")
      } catch (err) {
        console.error(err)
        body.innerHTML = `<tr><td colspan="11" style="text-align:center;">${err.message}</td></tr>`
      }
    }

    // Borrado lógico (columna `eliminado`, distinto de "Anular" — que solo cambia el estado del
    // registro conservando el historial). Eliminar oculta la fila de todos los listados/filtros.
    async eliminarMuestra(id) {
      if (!confirm(`¿Eliminar la muestra #${id}? Esta acción la ocultará de todos los listados y no se puede deshacer desde la interfaz.`)) return

      try {
        const res = await window.PhotinoBridge.send({ action: "muestraLab.eliminar", data: { id } })
        if (!res || res.ok === false) throw new Error(res?.error || "Error eliminando la muestra")
        await this.cargarLista()
      } catch (err) {
        alert(err.message)
      }
    }

    // =====================================================================
    // ESPECIFICACIONES
    // =====================================================================
    // ---------- Métodos de ensayo (Punto 32 del REG-LAB-04) ----------

    async abrirMetodos() {
      this.limpiarFormMetodo()
      await this.cargarMetodos()
      this.abrirModal("mlbModalMetodos")
    }

    async cargarMetodos() {
      const body = document.getElementById("mlbMetodoBody")
      body.innerHTML = '<tr><td colspan="8" style="text-align:center;">Cargando...</td></tr>'

      try {
        const res = await window.PhotinoBridge.send({ action: "muestraLab.metodo.list", data: {} })
        if (!res || res.ok === false) throw new Error(res?.error || "Error cargando métodos")

        const items = res.data || []
        if (items.length === 0) {
          body.innerHTML = '<tr><td colspan="8" style="text-align:center;">Sin métodos cargados</td></tr>'
          return
        }

        body.innerHTML = items.map(m => `
          <tr>
            <td>${m.tipoEnsayo}</td>
            <td>${m.variante || "-"}</td>
            <td>${this._esc(m.nombre)}</td>
            <td>${this._esc(m.codigo || "-")}</td>
            <td>${this._esc(m.version || "-")}</td>
            <td>${this._esc(m.unidad || "-")}</td>
            <td>${m.activo ? "Sí" : "No"}</td>
            <td>
              <button class="btn-secondary mlb-metodo-editar-btn" data-metodo='${JSON.stringify(m).replace(/'/g, "&apos;")}'>Editar</button>
              <button class="btn-secondary mlb-metodo-toggle-btn" data-id="${m.id}" data-activo="${m.activo}">${m.activo ? "Desactivar" : "Activar"}</button>
            </td>
          </tr>
        `).join("")
      } catch (err) {
        body.innerHTML = `<tr><td colspan="8" style="text-align:center;">${err.message}</td></tr>`
      }

      // Refresca los catálogos en memoria para que los modales de ensayo reflejen el cambio
      // sin esperar a recargar el módulo completo.
      await this.cargarCatalogos()
    }

    limpiarFormMetodo() {
      document.getElementById("mlbMetodoId").value = ""
      document.getElementById("mlbMetodoTipoEnsayo").value = "HUMEDAD"
      document.getElementById("mlbMetodoVariante").value = ""
      document.getElementById("mlbMetodoNombre").value = ""
      document.getElementById("mlbMetodoCodigo").value = ""
      document.getElementById("mlbMetodoVersion").value = ""
      document.getElementById("mlbMetodoUnidad").value = ""
      document.getElementById("mlbMetodoFormTitulo").textContent = "Nuevo método"
    }

    editarMetodo(m) {
      document.getElementById("mlbMetodoId").value = m.id
      document.getElementById("mlbMetodoTipoEnsayo").value = m.tipoEnsayo
      document.getElementById("mlbMetodoVariante").value = m.variante || ""
      document.getElementById("mlbMetodoNombre").value = m.nombre || ""
      document.getElementById("mlbMetodoCodigo").value = m.codigo || ""
      document.getElementById("mlbMetodoVersion").value = m.version || ""
      document.getElementById("mlbMetodoUnidad").value = m.unidad || ""
      document.getElementById("mlbMetodoFormTitulo").textContent = `Editando método #${m.id}`
    }

    async guardarMetodo() {
      const idVal = document.getElementById("mlbMetodoId").value

      const data = {
        id: idVal ? parseInt(idVal, 10) : null,
        tipoEnsayo: document.getElementById("mlbMetodoTipoEnsayo").value,
        variante: document.getElementById("mlbMetodoVariante").value,
        nombre: document.getElementById("mlbMetodoNombre").value.trim(),
        codigo: document.getElementById("mlbMetodoCodigo").value.trim(),
        version: document.getElementById("mlbMetodoVersion").value.trim(),
        unidad: document.getElementById("mlbMetodoUnidad").value.trim()
      }

      try {
        const res = await window.PhotinoBridge.send({ action: "muestraLab.metodo.guardar", data })
        if (!res || res.ok === false) throw new Error(res?.error || "Error guardando el método")

        this.limpiarFormMetodo()
        await this.cargarMetodos()
      } catch (err) {
        alert(err.message)
      }
    }

    async toggleMetodo(id, activoActual) {
      try {
        const res = await window.PhotinoBridge.send({
          action: "muestraLab.metodo.activar",
          data: { id, activo: !activoActual }
        })
        if (!res || res.ok === false) throw new Error(res?.error || "Error actualizando el método")

        await this.cargarMetodos()
      } catch (err) {
        alert(err.message)
      }
    }

    async abrirEspecificaciones() {
      this.limpiarFormEspecificacion()
      await this.cargarEspecificaciones()
      this.abrirModal("mlbModalEspecificaciones")
    }

    async cargarEspecificaciones() {
      const body = document.getElementById("mlbEspecBody")
      body.innerHTML = '<tr><td colspan="8" style="text-align:center;">Cargando...</td></tr>'

      try {
        const res = await window.PhotinoBridge.send({ action: "muestraLab.especificacion.list", data: {} })
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
              <button class="btn-secondary mlb-espec-editar-btn" data-espec='${JSON.stringify(s).replace(/'/g, "&apos;")}'>Editar</button>
              <button class="btn-secondary mlb-espec-toggle-btn" data-id="${s.id}" data-activo="${s.activo}">${s.activo ? "Desactivar" : "Activar"}</button>
            </td>
          </tr>
        `).join("")
      } catch (err) {
        body.innerHTML = `<tr><td colspan="8" style="text-align:center;">${err.message}</td></tr>`
      }
    }

    limpiarFormEspecificacion() {
      document.getElementById("mlbEspecId").value = ""
      document.getElementById("mlbEspecTipoMuestra").value = "Papel"
      document.getElementById("mlbEspecTipoEnsayo").value = "HUMEDAD"
      document.getElementById("mlbEspecCodigo").value = ""
      document.getElementById("mlbEspecMin").value = ""
      document.getElementById("mlbEspecMax").value = ""
      document.getElementById("mlbEspecUnidad").value = ""
      document.getElementById("mlbEspecFormTitulo").textContent = "Nueva especificación"
    }

    editarEspecificacion(s) {
      document.getElementById("mlbEspecId").value = s.id
      document.getElementById("mlbEspecTipoMuestra").value = s.tipoMuestra
      document.getElementById("mlbEspecTipoEnsayo").value = s.tipoEnsayo
      document.getElementById("mlbEspecCodigo").value = s.codigoProducto || ""
      document.getElementById("mlbEspecMin").value = s.limiteMin ?? ""
      document.getElementById("mlbEspecMax").value = s.limiteMax ?? ""
      document.getElementById("mlbEspecUnidad").value = s.unidad || ""
      document.getElementById("mlbEspecFormTitulo").textContent = `Editando especificación #${s.id}`
    }

    async guardarEspecificacion() {
      const idVal = document.getElementById("mlbEspecId").value
      const num = (id) => {
        const v = document.getElementById(id).value
        return v === "" ? null : parseFloat(v)
      }

      const data = {
        id: idVal ? parseInt(idVal, 10) : null,
        tipoMuestra: document.getElementById("mlbEspecTipoMuestra").value,
        tipoEnsayo: document.getElementById("mlbEspecTipoEnsayo").value,
        codigoProducto: document.getElementById("mlbEspecCodigo").value.trim(),
        limiteMin: num("mlbEspecMin"),
        limiteMax: num("mlbEspecMax"),
        unidad: document.getElementById("mlbEspecUnidad").value.trim()
      }

      try {
        const res = await window.PhotinoBridge.send({ action: "muestraLab.especificacion.guardar", data })
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
          action: "muestraLab.especificacion.activar",
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
      document.getElementById("mlbNmNp").value = ""
      document.getElementById("mlbNmCliente").value = ""
      document.getElementById("mlbNmCodigo").value = ""
      document.getElementById("mlbNmDescripcion").value = ""
      document.getElementById("mlbNmMaquina").value = ""
      document.getElementById("mlbNmTipoOnda").value = ""
      document.getElementById("mlbNmTurno").value = ""
      document.getElementById("mlbNmLote").value = ""
      document.getElementById("mlbNmProveedor").value = ""
      document.getElementById("mlbNmObservacion").value = ""
      document.getElementById("mlbNmSolicitante").value = ""
      document.getElementById("mlbNmEtapaOrigen").value = ""
      document.getElementById("mlbNmMotivoSolicitud").value = ""
      document.getElementById("mlbNmEnsayosRequeridos").value = ""
      document.getElementById("mlbNmOrigen").value = "ControlRecepcion"
      document.getElementById("mlbNmPruebaSolicitadaBloque").style.display = "none"
      document.getElementById("mlbNmMonotapaBloque").style.display = "none"
      document.getElementById("mlbNmEmplacadoBloque").style.display = "none"
      document.getElementById("mlbNmPesoOndaExtendida").value = ""
      document.getElementById("mlbNmPesoRecorte10x10").value = ""
      document.getElementById("mlbNmLongitudOndaExtendida").value = ""
      document.getElementById("mlbNmAlturaOnda").value = ""
      document.getElementById("mlbNmMonotapaRelacionadaId").value = ""
      document.getElementById("mlbNmMonotapaRelacionadaInfo").textContent = ""
      document.getElementById("mlbNmPliegoRelacionado").value = ""

      document.getElementById("mlbNmCliente").disabled = false
      document.getElementById("mlbNmCodigo").disabled = false
      document.getElementById("mlbNmDescripcion").disabled = false
      document.getElementById("mlbNmProcesoWrap").style.display = "none"
      document.getElementById("mlbNmRegistroWrap").style.display = "none"
      document.getElementById("mlbNmFpsInfo").style.display = "none"
      document.getElementById("mlbNmBtnConsultarProduccion").disabled = true

      this._fpsProcesos = []
      this._fpsRegistros = []
      this._fpsSnapshot = null

      this.abrirModal("mlbModalNuevaMuestra")
    }

    // Consulta la NP en Planificación FARET (vista-planificacion) para autocompletar
    // Cliente/Código/Descripción y listar los procesos disponibles (punto de partida para
    // "Consultar producción"). No persiste nada todavía.
    // Punto 20 REG-LAB-04: valida el id de la muestra Monotapa relacionada y muestra un resumen
    // (no bloquea el campo — igual criterio que "resolver bobina", solo informativo/de apoyo).
    async _buscarMonotapaRelacionada() {
      const info = document.getElementById("mlbNmMonotapaRelacionadaInfo")
      const idVal = document.getElementById("mlbNmMonotapaRelacionadaId").value
      if (!idVal) { info.textContent = ""; return }

      info.textContent = "Buscando..."
      try {
        const res = await window.PhotinoBridge.send({ action: "muestraLab.detalle", data: { id: parseInt(idVal, 10) } })
        if (!res || res.ok === false || !res.data) throw new Error("No se encontró esa muestra")
        if (res.data.origen !== "Monotapa") throw new Error(`La muestra #${res.data.id} no es de origen Monotapa (es ${res.data.origen})`)

        info.textContent = `✓ Muestra #${res.data.id} — NP ${res.data.np || "-"} — ${res.data.fechaIngreso}`
      } catch (err) {
        info.textContent = `⚠ ${err.message}`
      }
    }

    // Puntos 1/8/9/10/38 REG-LAB-04: abre la vista agrupada de todos los controles que comparten
    // la misma NP + N.º de registro de producción que la muestra actualmente abierta.
    async verRegistroProduccion() {
      const m = this._muestraActualDetalle
      if (!m || !m.np) return

      const lote = m.lote || (m.registroProduccionRecordKey ? String(m.registroProduccionRecordKey) : "")
      document.getElementById("mlbRegProdSubtitulo").textContent = `NP ${m.np}${lote ? ` — N.º de registro de producción ${lote}` : ""}`
      const body = document.getElementById("mlbRegProdBody")
      body.innerHTML = '<tr><td colspan="9" style="text-align:center;">Cargando...</td></tr>'
      this.abrirModal("mlbModalRegistroProduccion")

      try {
        const res = await window.PhotinoBridge.send({ action: "muestraLab.registroProduccion.list", data: { np: m.np, lote } })
        if (!res || res.ok === false) throw new Error(res?.error || "Error cargando los controles de esta producción")

        const items = res.data || []
        if (items.length === 0) {
          body.innerHTML = '<tr><td colspan="9" style="text-align:center;">Sin otros controles registrados para esta producción</td></tr>'
          return
        }

        body.innerHTML = items.map(c => `
          <tr${c.id === m.id ? ' style="font-weight:700; background:#f1f5f9;"' : ""}>
            <td>${this._esc(c.origen)}</td>
            <td>${this._esc(c.tipoMuestra)}</td>
            <td>${this._esc(c.fechaEnsayo || c.fechaIngreso)}</td>
            <td>${this._esc(c.maquina || "-")}</td>
            <td>${this._esc(c.turno || "-")}</td>
            <td>${this._labelEstado(c.estado)}</td>
            <td>${this._esc(c.evaluacion || "-")}</td>
            <td>${c.totalEnsayos}</td>
            <td><button class="btn-secondary mlb-regprod-ver-btn" data-id="${c.id}">${c.id === m.id ? "(actual)" : "Ver"}</button></td>
          </tr>
        `).join("")
      } catch (err) {
        body.innerHTML = `<tr><td colspan="9" style="text-align:center;">${err.message}</td></tr>`
      }
    }

    async _consultarNp() {
      const np = document.getElementById("mlbNmNp").value.trim()
      if (!np) return alert("Ingresa la NP antes de consultar")

      try {
        const res = await window.PhotinoBridge.send({ action: "muestraLab.consultarNp", data: { np } })
        if (!res || res.ok === false) throw new Error(res?.error || "No se pudo consultar la NP")

        this._fpsProcesos = res.data || []
        if (this._fpsProcesos.length === 0) throw new Error(`No se encontró la NP ${np}`)

        const primero = this._fpsProcesos[0]
        document.getElementById("mlbNmCliente").value = primero.cliente || ""
        document.getElementById("mlbNmCodigo").value = primero.codigoProducto || ""
        document.getElementById("mlbNmDescripcion").value = primero.descripcion || ""
        document.getElementById("mlbNmCliente").disabled = true
        document.getElementById("mlbNmCodigo").disabled = true
        document.getElementById("mlbNmDescripcion").disabled = true

        const selProceso = document.getElementById("mlbNmProceso")
        selProceso.innerHTML = this._fpsProcesos
          .map((p, i) => `<option value="${i}">${this._esc(p.proceso)} (proceso ${p.idProceso})</option>`)
          .join("")
        document.getElementById("mlbNmProcesoWrap").style.display = this._fpsProcesos.length > 1 ? "" : "none"
        document.getElementById("mlbNmBtnConsultarProduccion").disabled = false
      } catch (err) {
        alert(err.message)
      }
    }

    _onSeleccionarProcesoFps() {
      document.getElementById("mlbNmRegistroWrap").style.display = "none"
      document.getElementById("mlbNmFpsInfo").style.display = "none"
    }

    // Consulta las sesiones reales (WorkOrderRecords) del proceso elegido — el analista elige
    // cuál corresponde al control que está registrando.
    async _consultarRegistroProduccion() {
      const selProceso = document.getElementById("mlbNmProceso")
      const proceso = this._fpsProcesos[selProceso.value ? parseInt(selProceso.value, 10) : 0]
      if (!proceso) return alert("Consulta primero la NP")

      try {
        const res = await window.PhotinoBridge.send({
          action: "muestraLab.consultarRegistroProduccion",
          data: { idProceso: proceso.idProceso }
        })
        if (!res || res.ok === false) throw new Error(res?.error || "No se pudo consultar FPS")

        this._fpsRegistros = res.data || []
        if (this._fpsRegistros.length === 0) throw new Error("Este proceso no tiene sesiones de producción registradas en FPS")

        const selRegistro = document.getElementById("mlbNmRegistroProduccion")
        selRegistro.innerHTML = this._fpsRegistros
          .map((r, i) => `<option value="${i}">${r.productionStartDate || "-"} — ${this._esc(r.maquinaDescripcion || r.maquina)} — ${this._esc(r.operador)}</option>`)
          .join("")
        document.getElementById("mlbNmRegistroWrap").style.display = ""
        this._fpsSnapshot = { proceso }
        this._onSeleccionarRegistroProduccion()
      } catch (err) {
        alert(err.message)
      }
    }

    _onSeleccionarRegistroProduccion() {
      const selRegistro = document.getElementById("mlbNmRegistroProduccion")
      const registro = this._fpsRegistros[selRegistro.value ? parseInt(selRegistro.value, 10) : 0]
      if (!registro || !this._fpsSnapshot) return

      this._fpsSnapshot = {
        ...this._fpsSnapshot,
        registro,
        fechaConsultaFps: new Date().toISOString(),
      }

      document.getElementById("mlbNmOperadorTexto").textContent = registro.operador || "-"
      document.getElementById("mlbNmFechaProduccionTexto").textContent = registro.productionStartDate || "-"
      document.getElementById("mlbNmFpsInfo").style.display = ""

      if (registro.productionStartDate) {
        document.getElementById("mlbNmTurno").value = this._calcularTurno(new Date(registro.productionStartDate))
      }

      // Sugiere la máquina por nombre si coincide con el catálogo real — el analista puede
      // corregirla igual, FPS y el catálogo QCC no son la misma fuente.
      const nombreFps = (registro.maquinaDescripcion || registro.maquina || "").trim().toLowerCase()
      if (nombreFps) {
        const match = this._catalogos.maquinas.find(m => m.nombre.trim().toLowerCase() === nombreFps)
        if (match) document.getElementById("mlbNmMaquina").value = match.id
      }
    }

    // Misma regla de turno que ya usa la app móvil Flutter (core/utils/turno_calculator.dart):
    // A 07:00-14:29, B 14:30-23:29, C 23:30-06:59.
    _calcularTurno(fecha) {
      const minutos = fecha.getHours() * 60 + fecha.getMinutes()
      if (minutos >= 7 * 60 && minutos < 14 * 60 + 30) return "A"
      if (minutos >= 14 * 60 + 30 && minutos < 23 * 60 + 30) return "B"
      return "C"
    }

    // Resuelve un lote/bobina contra SAP (apisapfaret) — mismo endpoint que ya usa la app móvil
    // Flutter para el escaneo de bobinas. Solo informa; el valor tipeado por el analista es el
    // que se guarda, se use o no la resolución.
    async _resolverBobina(inputId, infoId) {
      const input = document.getElementById(inputId)
      const info = document.getElementById(infoId)
      if (!input || !info) return

      const lote = input.value.trim()
      if (!lote) return alert("Ingresa el número de bobina/lote antes de resolver")

      info.textContent = "Consultando SAP..."
      info.classList.remove("mlb-bobina-info-error")

      try {
        const res = await window.PhotinoBridge.send({ action: "muestraLab.resolverBobina", data: { lote } })
        if (!res || res.ok === false) throw new Error(res?.error || "No se encontró el lote en SAP")

        const d = res.data
        info.textContent = `${d.itemName || d.itemCode || "Sin descripción"}${d.ubicacion ? " · " + d.ubicacion : ""}`
      } catch (err) {
        info.textContent = err.message
        info.classList.add("mlb-bobina-info-error")
      }
    }

    async guardarNuevaMuestra() {
      const num = (id) => {
        const v = document.getElementById(id).value
        return v === "" ? null : parseFloat(v)
      }
      const maquinaSel = document.getElementById("mlbNmMaquina")
      const tipoOndaSel = document.getElementById("mlbNmTipoOnda")
      const maquinaNombre = maquinaSel.selectedOptions[0]?.textContent || ""
      const registro = this._fpsSnapshot?.registro

      const data = {
        origen: document.getElementById("mlbNmOrigen").value,
        tipoMuestra: document.getElementById("mlbNmTipoMuestra").value,
        np: document.getElementById("mlbNmNp").value.trim(),
        cliente: document.getElementById("mlbNmCliente").value.trim(),
        codigoProducto: document.getElementById("mlbNmCodigo").value.trim(),
        descripcion: document.getElementById("mlbNmDescripcion").value.trim(),
        maquina: maquinaSel.value ? maquinaNombre : "",
        maquinaId: maquinaSel.value ? parseInt(maquinaSel.value, 10) : null,
        tipoOndaId: tipoOndaSel.value ? parseInt(tipoOndaSel.value, 10) : null,
        turno: document.getElementById("mlbNmTurno").value.trim(),
        registroProduccionRecordKey: registro ? registro.recordKey : null,
        idProcesoFps: this._fpsSnapshot ? this._fpsSnapshot.proceso.idProceso : null,
        procesoTextoFps: this._fpsSnapshot ? this._fpsSnapshot.proceso.proceso : null,
        operadorTexto: registro ? registro.operador : null,
        fechaProduccionFps: registro ? registro.productionStartDate : null,
        fechaConsultaFps: this._fpsSnapshot ? this._fpsSnapshot.fechaConsultaFps : null,
        lote: document.getElementById("mlbNmLote").value.trim(),
        proveedor: document.getElementById("mlbNmProveedor").value.trim(),
        observacion: document.getElementById("mlbNmObservacion").value.trim(),
        solicitante: document.getElementById("mlbNmSolicitante").value.trim() || null,
        motivoSolicitud: document.getElementById("mlbNmMotivoSolicitud").value.trim() || null,
        etapaOrigen: document.getElementById("mlbNmEtapaOrigen").value || null,
        ensayosRequeridos: document.getElementById("mlbNmEnsayosRequeridos").value.trim() || null,
        pesoOndaExtendida: num("mlbNmPesoOndaExtendida"),
        pesoRecorte10x10: num("mlbNmPesoRecorte10x10"),
        longitudOndaExtendida: num("mlbNmLongitudOndaExtendida"),
        alturaOnda: num("mlbNmAlturaOnda"),
        monotapaRelacionadaId: document.getElementById("mlbNmMonotapaRelacionadaId").value
          ? parseInt(document.getElementById("mlbNmMonotapaRelacionadaId").value, 10) : null,
        pliegoRelacionado: document.getElementById("mlbNmPliegoRelacionado").value.trim() || null
      }

      try {
        const res = await window.PhotinoBridge.send({ action: "muestraLab.crear", data })
        if (!res || res.ok === false) throw new Error(res?.error || "Error creando la muestra")

        this.cerrarModal("mlbModalNuevaMuestra")
        await this.cargarLista()
        await this.cargarIndicadores()
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
        const res = await window.PhotinoBridge.send({ action: "muestraLab.detalle", data: { id } })
        if (!res || res.ok === false) throw new Error(res?.error || "Error cargando el detalle")

        this._muestraActualId = id
        this._muestraActualDetalle = res.data
        const m = res.data

        const maquinaNombre = this._catalogos.maquinas.find(x => x.id === m.maquinaId)?.nombre
        const tipoOndaNombre = this._catalogos.tiposOnda.find(x => x.id === m.tipoOndaId)?.nombre

        document.getElementById("mlbDetId").textContent = m.id
        document.getElementById("mlbDetResumen").innerHTML = `
          <div><b>${m.origen || "-"}</b>Origen</div>
          <div><b>${m.tipoMuestra || "-"}</b>Tipo de muestra</div>
          <div><b>${m.np || "-"}</b>NP</div>
          <div><b>${m.cliente || "-"}</b>Cliente</div>
          <div><b>${m.codigoProducto || "-"}</b>Código</div>
          <div><b>${this._labelEstado(m.estado)}</b>Estado</div>
          <div><b>${m.evaluacion || "-"}</b>Evaluación</div>
          <div><b>${maquinaNombre || m.maquina || "-"}</b>Máquina</div>
          <div><b>${tipoOndaNombre || "-"}</b>Tipo de onda</div>
          ${m.registroProduccionRecordKey ? `
            <div><b>${m.registroProduccionRecordKey}</b>Registro de producción (FPS)</div>
            <div><b>${m.operadorTexto || "-"}</b>Operador (FPS)</div>
            <div><b>${m.fechaProduccionFps || "-"}</b>Fecha producción (FPS)</div>
          ` : ""}
        `

        const auditoriaFecha = document.getElementById("mlbDetAuditoriaFecha")
        if (m.fechaEnsayoModificadaPor) {
          auditoriaFecha.style.display = "block"
          auditoriaFecha.innerHTML = `<small>Fecha efectiva modificada por <b>${this._esc(m.fechaEnsayoModificadaPor)}</b> el ${this._esc(m.fechaEnsayoFechaModificacion)}</small>`
        } else {
          auditoriaFecha.style.display = "none"
          auditoriaFecha.innerHTML = ""
        }
        this._cerrarEditarFecha()

        const anulado = m.estado === "Anulado"
        const bannerAnulado = document.getElementById("mlbDetAnuladoBanner")
        if (anulado) {
          bannerAnulado.style.display = "block"
          bannerAnulado.innerHTML = `<b>Registro anulado</b> por ${this._esc(m.anuladoPor || "-")} el ${this._esc(m.fechaAnulacion || "-")}. Motivo: ${this._esc(m.motivoAnulacionRegistro || "-")}`
        } else {
          bannerAnulado.style.display = "none"
          bannerAnulado.innerHTML = ""
        }
        document.getElementById("mlbDetAccionesEnsayo").style.display = anulado ? "none" : "flex"
        document.getElementById("mlbDetBtnEditarFecha").style.display = anulado ? "none" : "inline-block"
        document.getElementById("mlbDetBtnAnularRegistro").style.display = anulado ? "none" : "inline-block"

        document.getElementById("mlbDetBtnVerRegistroProduccion").style.display = m.np ? "inline-block" : "none"
        document.getElementById("mlbDetBtnMaterialesFps").style.display = m.idProcesoFps ? "inline-block" : "none"
        document.getElementById("mlbDetMaterialesFps").style.display = "none"
        document.getElementById("mlbDetMaterialesFps").innerHTML = ""
        this._renderFiltroEnsayosPorEtapa(m.origen)
        this._renderInfoMonotapaEmplacado(m)

        document.getElementById("mlbDetNc").innerHTML = this.renderBloqueNc(m)
        this.renderAdjuntos(m.adjuntos || [])

        this._ensayosActuales = m.ensayos || []
        this.renderEnsayos(this._ensayosActuales)
        this.abrirModal("mlbModalDetalle")
      } catch (err) {
        alert(err.message)
      }
    }

    // Vínculo a No Conformidades: solo aparece cuando la muestra evaluó "No cumple". Si ya tiene
    // una NC vinculada, muestra el código; si no, ofrece crearla (queda gestionada desde el
    // módulo No Conformidades, acá solo se crea y se vincula).
    // Punto 8 REG-LAB-04: la etapa (Origen) determina qué ensayos tienen sentido — Corrugado
    // trabaja sobre papeles (Humedad/Gramaje/Espesor/Cobb/RCT), Emplacado/Troquelado/Pegado/
    // Producto terminado trabajan sobre el complejo (Humedad/Gramaje/Espesor/ECT/FCT/BCT).
    // Pruebas/Otro/MuestraExterna no restringen nada (una prueba puntual puede pedir cualquiera).
    _ensayosPorEtapa(origen) {
      const sustrato = ["mlbBtnNuevoHumedad", "mlbBtnNuevoGramaje", "mlbBtnNuevoEspesor", "mlbBtnNuevoCobb", "mlbBtnNuevoRct"]
      const complejo = ["mlbBtnNuevoHumedad", "mlbBtnNuevoGramaje", "mlbBtnNuevoEspesor", "mlbBtnNuevoEct", "mlbBtnNuevoFct", "mlbBtnNuevoBctMedido", "mlbBtnNuevoBctTeorico"]
      const extra = ["mlbBtnNuevoViscosidad", "mlbBtnNuevoPh", "mlbBtnNuevoSolidos", "mlbBtnNuevoLugol"]
      const mapa = {
        ControlRecepcion: sustrato,
        Corrugado: sustrato,
        Monotapa: ["mlbBtnNuevoHumedad"],
        Emplacado: complejo,
        Troquelado: complejo,
        Pegado: complejo,
        ProductoTerminado: complejo,
      }
      return mapa[origen] ? [...mapa[origen], ...extra] : null // null = sin restricción (Pruebas/Otro/MuestraExterna)
    }

    _renderFiltroEnsayosPorEtapa(origen) {
      const permitidos = this._ensayosPorEtapa(origen)
      document.querySelectorAll("#mlbDetAccionesEnsayo button").forEach(btn => {
        btn.style.display = (!permitidos || permitidos.includes(btn.id)) ? "inline-block" : "none"
      })
    }

    // Puntos 19/20 REG-LAB-04: muestra los campos propios de Monotapa/Emplacado cuando corresponde.
    _renderInfoMonotapaEmplacado(m) {
      const monotapaDiv = document.getElementById("mlbDetMonotapaInfo")
      if (m.origen === "Monotapa" && (m.pesoOndaExtendida != null || m.pesoRecorte10x10 != null || m.longitudOndaExtendida != null || m.alturaOnda != null)) {
        monotapaDiv.style.display = "block"
        monotapaDiv.innerHTML = `
          <div class="module-title" style="font-size:13px;">Control de Monotapa</div>
          <div><b>${m.pesoOndaExtendida ?? "-"}</b> Peso onda extendida (g) &nbsp; · &nbsp;
               <b>${m.pesoRecorte10x10 ?? "-"}</b> Peso recorte 10×10 (g) &nbsp; · &nbsp;
               <b>${m.gramajeRecorte ?? "-"}</b> Gramaje recorte (g/m²) &nbsp; · &nbsp;
               <b>${m.longitudOndaExtendida ?? "-"}</b> Longitud onda extendida (mm) &nbsp; · &nbsp;
               <b>${m.alturaOnda ?? "-"}</b> Altura de onda (mm)</div>
        `
      } else {
        monotapaDiv.style.display = "none"
        monotapaDiv.innerHTML = ""
      }

      const emplacadoDiv = document.getElementById("mlbDetEmplacadoInfo")
      if (m.origen === "Emplacado" && (m.monotapaRelacionadaResumen || m.pliegoRelacionado)) {
        emplacadoDiv.style.display = "block"
        emplacadoDiv.innerHTML = `
          <div class="module-title" style="font-size:13px;">Control de Emplacado</div>
          <div>${m.monotapaRelacionadaResumen ? `<b>Monotapa relacionada:</b> ${this._esc(m.monotapaRelacionadaResumen)}` : ""}
               ${m.pliegoRelacionado ? ` &nbsp; · &nbsp; <b>Pliego:</b> ${this._esc(m.pliegoRelacionado)}` : ""}</div>
        `
      } else {
        emplacadoDiv.style.display = "none"
        emplacadoDiv.innerHTML = ""
      }
    }

    // Punto 5 del REG-LAB-04: materiales/insumos de FPS (ZZZMateriasPrimasOT, Tipo INSUMO) usados
    // en el proceso de esta producción. Se consulta bajo demanda (no en cada abrirDetalle) porque
    // es una llamada a un sistema externo (FPS) y no todas las muestras tienen idProcesoFps.
    async _verMaterialesFps() {
      const m = this._muestraActualDetalle
      if (!m || !m.idProcesoFps) return

      const div = document.getElementById("mlbDetMaterialesFps")
      div.style.display = "block"
      div.innerHTML = '<div class="module-title" style="font-size:13px;">Materiales utilizados (FPS)</div><div>Consultando...</div>'

      try {
        const res = await window.PhotinoBridge.send({ action: "muestraLab.materialesFps", data: { idProceso: m.idProcesoFps } })
        if (!res || res.ok === false) throw new Error(res?.error || "Error consultando materiales en FPS")

        const materiales = res.data || []
        if (materiales.length === 0) {
          div.innerHTML = '<div class="module-title" style="font-size:13px;">Materiales utilizados (FPS)</div><div class="subtitle">Sin materiales/insumos registrados para este proceso en FPS.</div>'
          return
        }

        div.innerHTML = `
          <div class="module-title" style="font-size:13px;">Materiales utilizados (FPS)</div>
          <ul style="margin:4px 0 0; padding-left:18px;">
            ${materiales.map(mat => `<li><b>${this._esc(mat.itemCode)}</b> — ${this._esc(mat.itemName)}</li>`).join("")}
          </ul>
        `
      } catch (err) {
        div.innerHTML = `<div class="module-title" style="font-size:13px;">Materiales utilizados (FPS)</div><div class="subtitle">${this._esc(err.message)}</div>`
      }
    }

    renderBloqueNc(m) {
      if (m.evaluacion !== "No cumple") return ""

      if (m.ncId) {
        return `<div class="subtitle" style="margin-top:8px;">No Conformidad vinculada: <b>${m.ncCodigo || `#${m.ncId}`}</b> (gestiónala desde el módulo No Conformidades).</div>`
      }

      return `
        <div class="subtitle" style="margin-top:8px; display:flex; align-items:center; gap:10px;">
          <span>Esta muestra no cumple especificación y no tiene una No Conformidad vinculada.</span>
          <button class="btn-secondary" id="mlbBtnCrearNc">Crear No Conformidad</button>
        </div>
      `
    }

    async crearNoConformidad() {
      if (!this._muestraActualId) return
      if (!confirm("¿Crear una No Conformidad vinculada a esta muestra?")) return

      try {
        const res = await window.PhotinoBridge.send({
          action: "muestraLab.nc.crear",
          data: { muestraId: this._muestraActualId }
        })
        if (!res || res.ok === false) throw new Error(res?.error || "Error creando la No Conformidad")

        alert(`No Conformidad creada (${res.data.codigo}). Gestiónala desde el módulo No Conformidades.`)
        await this.abrirDetalle(this._muestraActualId)
      } catch (err) {
        alert(err.message)
      }
    }

    // Punto 2/34 del REG-LAB-04: anular el registro completo (distinto de anular un ensayo
    // puntual, que ya existía). No hay borrado físico — el registro queda visible con su
    // motivo/usuario/fecha de anulación, sin poder agregarle más ensayos.
    async anularMuestra() {
      if (!this._muestraActualId) return
      const motivo = prompt("Motivo de anulación del registro completo:")
      if (!motivo) return

      try {
        const res = await window.PhotinoBridge.send({
          action: "muestraLab.anular",
          data: { id: this._muestraActualId, motivo }
        })
        if (!res || res.ok === false) throw new Error(res?.error || "Error anulando el registro")

        await this.abrirDetalle(this._muestraActualId)
        await this.cargarLista()
      } catch (err) {
        alert(err.message)
      }
    }

    // ---------- Adjuntos (Punto 3 del REG-LAB-04) ----------

    renderAdjuntos(adjuntos) {
      const cont = document.getElementById("mlbDetAdjuntos")
      if (!adjuntos.length) {
        cont.innerHTML = `<div class="subtitle">Sin adjuntos.</div>`
        return
      }
      cont.innerHTML = `
        <ul style="margin:0; padding-left:18px;">
          ${adjuntos.map(a => `
            <li style="margin-bottom:4px;">
              ${this._esc(a.nombreArchivo)} <small style="color:#64748b;">(${this._esc(a.subidoPor || "-")}, ${this._esc(a.fechaSubida)})</small>
              <button class="btn-secondary mlb-ver-adjunto-btn" data-adjunto-id="${a.id}" style="padding:2px 8px;">Ver</button>
              <button class="btn-secondary mlb-eliminar-adjunto-btn" data-adjunto-id="${a.id}" style="padding:2px 8px;">Eliminar</button>
            </li>
          `).join("")}
        </ul>
      `
    }

    _pedirArchivoAdjunto() {
      const input = document.getElementById("mlbAdjuntoInput")
      input.value = ""
      input.click()
    }

    _leerArchivoComoBase64(file) {
      return new Promise((resolve, reject) => {
        const reader = new FileReader()
        reader.onload = () => resolve((reader.result || "").toString().split(",")[1] || "")
        reader.onerror = reject
        reader.readAsDataURL(file)
      })
    }

    _validarArchivoAdjunto(file) {
      const extensionesValidas = [".pdf", ".doc", ".docx", ".jpg", ".jpeg", ".png", ".webp"]
      const extension = file.name.substring(file.name.lastIndexOf(".")).toLowerCase()
      if (!extensionesValidas.includes(extension)) {
        return `Tipo de archivo no permitido. Formatos válidos: ${extensionesValidas.join(", ")}`
      }
      const MAX_BYTES = 10 * 1024 * 1024
      if (file.size > MAX_BYTES) {
        return "El archivo supera el tamaño máximo permitido (10 MB)"
      }
      return null
    }

    async _subirAdjunto(file) {
      if (!this._muestraActualId) return

      const error = this._validarArchivoAdjunto(file)
      if (error) {
        alert(error)
        return
      }

      try {
        const contenidoBase64 = await this._leerArchivoComoBase64(file)
        const res = await window.PhotinoBridge.send({
          action: "muestraLab.adjunto.subir",
          data: { muestraId: this._muestraActualId, nombreArchivo: file.name, contenidoBase64 }
        })
        if (!res || res.ok === false) throw new Error(res?.error || "Error al subir el adjunto")

        await this.abrirDetalle(this._muestraActualId)
      } catch (err) {
        alert(err.message)
      }
    }

    async _verAdjunto(adjuntoId) {
      try {
        const res = await window.PhotinoBridge.send({
          action: "muestraLab.adjunto.abrir",
          data: { adjuntoId }
        })
        if (!res || res.ok === false) throw new Error(res?.error || "Error al abrir el adjunto")

        if (!res.data.previsualizable) return

        document.getElementById("mlbAdjuntoTitulo").textContent = res.data.nombreArchivo
        const contenedor = document.getElementById("mlbAdjuntoContenido")
        const dataUri = `data:${res.data.tipoMime};base64,${res.data.contenidoBase64}`
        contenedor.innerHTML = res.data.tipoMime.startsWith("image/")
          ? `<img src="${dataUri}" style="max-width:100%; max-height:60vh;">`
          : `<iframe src="${dataUri}" style="width:100%; height:60vh; border:0;"></iframe>`
        this.abrirModal("mlbModalAdjunto")
      } catch (err) {
        alert(err.message)
      }
    }

    async _eliminarAdjunto(adjuntoId) {
      if (!confirm("¿Eliminar este adjunto?")) return

      try {
        const res = await window.PhotinoBridge.send({
          action: "muestraLab.adjunto.eliminar",
          data: { adjuntoId }
        })
        if (!res || res.ok === false) throw new Error(res?.error || "Error al eliminar el adjunto")

        await this.abrirDetalle(this._muestraActualId)
      } catch (err) {
        alert(err.message)
      }
    }

    renderEnsayos(ensayos) {
      const body = document.getElementById("mlbEnsayosBody")
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
          ? ` <button type="button" class="mlb-historial-btn" data-id="${e.id}" title="Ver historial de valores (Punto 36)" style="border:none;background:none;cursor:pointer;font-size:14px;">&#9998;</button>`
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
              ${puedeAnular ? `<button class="btn-secondary mlb-anular-btn" data-id="${e.id}">Anular</button>` : ""}
              ${puedeCorregir ? `<button class="btn-secondary mlb-corregir-btn" data-id="${e.id}">Corregir</button>` : ""}
            </td>
          </tr>
        `
      }).join("")
    }

    // Punto 36 del REG-LAB-04: "valor anterior → valor nuevo" cuando un ensayo fue corregido.
    // No requiere backend nuevo — cada corrección ya crea un ensayo NUEVO vinculado al original
    // (ensayoReemplazaId/motivoReemplazo, columnas ya existentes) y el original queda Anulado con
    // su valor intacto en la misma lista de ensayos de la muestra. Solo hace falta recorrer esa
    // cadena, que puede tener más de un eslabón si el ensayo fue corregido más de una vez.
    _verHistorialEnsayo(ensayoId) {
      const lista = this._ensayosActuales || []
      const actual = lista.find(x => x.id === ensayoId)
      if (!actual) return

      const cadena = []
      let cursor = actual
      while (cursor) {
        cadena.unshift(cursor)
        cursor = cursor.ensayoReemplazaId ? lista.find(x => x.id === cursor.ensayoReemplazaId) : null
      }

      const fmt = e => e.resultadoValor != null ? `${e.resultadoValor} ${e.resultadoUnidad || ""}`.trim() : "-"
      const lineas = cadena.map((e, i) => {
        if (i === 0) return `Valor original (#${e.id}, ${e.fecha || "-"}): ${fmt(e)}`
        return `→ Corrección ${i} (#${e.id}, ${e.fecha || "-"}), motivo "${e.motivoReemplazo || "-"}": ${fmt(e)}`
      })

      alert(`Historial de valores — ${actual.tipoEnsayo}\n\n${lineas.join("\n")}`)
    }

    // =====================================================================
    // INFORME (impresión/guardar como PDF, mismo mecanismo que No Conformidades)
    // =====================================================================
    // Punto 7 del REG-LAB-04: corrige la fecha efectiva de un registro ya creado, sin tocar
    // fecha_ingreso (creación, siempre automática) — deja auditoría de quién/cuándo del lado API.
    _abrirEditarFecha() {
      const m = this._muestraActualDetalle
      if (!m) return
      const input = document.getElementById("mlbEditarFechaInput")
      input.value = m.fechaEnsayo ? m.fechaEnsayo.replace(" ", "T").substring(0, 16) : ""
      document.getElementById("mlbEditarFechaBloque").style.display = ""
    }

    _cerrarEditarFecha() {
      document.getElementById("mlbEditarFechaBloque").style.display = "none"
    }

    async _guardarFechaEnsayo() {
      const fechaEnsayo = document.getElementById("mlbEditarFechaInput").value
      if (!fechaEnsayo) return alert("Ingresa la nueva fecha/hora")

      try {
        const res = await window.PhotinoBridge.send({
          action: "muestraLab.actualizarFechaEnsayo",
          data: { id: this._muestraActualId, fechaEnsayo }
        })
        if (!res || res.ok === false) throw new Error(res?.error || "Error actualizando la fecha")

        await this.abrirDetalle(this._muestraActualId)
        await this.cargarLista()
      } catch (err) {
        alert(err.message)
      }
    }

    generarInforme() {
      const m = this._muestraActualDetalle
      if (!m) return

      const maquinaNombre = this._catalogos.maquinas.find(x => x.id === m.maquinaId)?.nombre
      const tipoOndaNombre = this._catalogos.tiposOnda.find(x => x.id === m.tipoOndaId)?.nombre

      const resumen = [
        { label: "Origen", valor: m.origen || "-" },
        { label: "Tipo de muestra", valor: m.tipoMuestra || "-" },
        { label: "NP", valor: m.np || "-" },
        { label: "Cliente", valor: m.cliente || "-" },
        { label: "Código", valor: m.codigoProducto || "-" },
        { label: "Descripción", valor: m.descripcion || "-" },
        { label: "Máquina", valor: maquinaNombre || m.maquina || "-" },
        { label: "Tipo de onda", valor: tipoOndaNombre || "-" },
        { label: "Turno", valor: m.turno || "-" },
        { label: "N.º de registro de producción", valor: m.registroProduccionRecordKey || m.lote || "-" },
        { label: "Proveedor", valor: m.proveedor || "-" },
        { label: "Fecha ingreso", valor: m.fechaIngreso || "-" },
        { label: "Fecha ensayo", valor: m.fechaEnsayo || "-" },
        { label: "Analista", valor: m.analistaNombre || "-" },
        { label: "Estado", valor: this._labelEstado(m.estado) },
        { label: "Evaluación", valor: m.evaluacion || "-" }
      ]

      if (m.registroProduccionRecordKey) {
        resumen.push(
          { label: "Proceso (FPS)", valor: m.procesoTextoFps || "-" },
          { label: "Operador (FPS)", valor: m.operadorTexto || "-" },
          { label: "Fecha producción (FPS)", valor: m.fechaProduccionFps || "-" },
        )
      }
      if (m.fechaEnsayoModificadaPor) {
        resumen.push({ label: "Fecha efectiva modificada por", valor: `${m.fechaEnsayoModificadaPor} (${m.fechaEnsayoFechaModificacion})` })
      }
      if (m.origen === "Monotapa" && (m.pesoOndaExtendida != null || m.pesoRecorte10x10 != null)) {
        resumen.push(
          { label: "Peso onda extendida (g)", valor: m.pesoOndaExtendida ?? "-" },
          { label: "Peso recorte 10×10 (g)", valor: m.pesoRecorte10x10 ?? "-" },
          { label: "Gramaje recorte (g/m²)", valor: m.gramajeRecorte ?? "-" },
          { label: "Longitud onda extendida (mm)", valor: m.longitudOndaExtendida ?? "-" },
          { label: "Altura de onda (mm)", valor: m.alturaOnda ?? "-" },
        )
      }
      if (m.origen === "Emplacado" && (m.monotapaRelacionadaResumen || m.pliegoRelacionado)) {
        if (m.monotapaRelacionadaResumen) resumen.push({ label: "Monotapa relacionada", valor: m.monotapaRelacionadaResumen })
        if (m.pliegoRelacionado) resumen.push({ label: "Pliego relacionado", valor: m.pliegoRelacionado })
      }

      const ensayos = this._ensayosActuales || []
      const tablas = []
      // Solo ensayos vigentes (Finalizado) — un ensayo Anulado (ej. una corrección histórica)
      // no debe aparecer en el informe impreso junto al que lo reemplazó.
      const porTipo = tipo => ensayos.filter(e => e.tipoEnsayo === tipo && e.estado === "Finalizado")

      // Hoja 1 del REG-LAB-04 (papel/sustrato): Control Recepción, Corrugado, Monotapa.
      // Hoja 2 (complejo): Emplacado, Troquelado, Pegado, Producto terminado.
      const esHoja1 = ["ControlRecepcion", "Corrugado", "Monotapa"].includes(m.origen)
      const esMonotapa = m.origen === "Monotapa"
      const cubiertos = new Set()
      const push = (tipo, fn) => { porTipo(tipo).forEach(e => tablas.push(fn(e))); cubiertos.add(tipo) }

      if (esHoja1) {
        if (esMonotapa) push("HUMEDAD", e => this._tablaHumedadMonotapa(e, m))
        else push("HUMEDAD", e => this._tablaSustrato(e, "1. HUMEDAD DE SUSTRATOS (%)", ["Punto izquierdo (%)", "Centro (%)", "Punto derecho (%)"]))
        push("GRAMAJE", e => this._tablaSustrato(e, "2. GRAMAJE DE SUSTRATOS (g/m²)", ["Peso (g) Punto izq.", "Peso (g) Centro", "Peso (g) Punto der."]))
        push("ESPESOR", e => this._tablaSustrato(e, "3. ESPESOR DE SUSTRATOS (mm)", ["Punto izquierdo", "Centro", "Punto derecho"]))
        push("COBB", e => this._tablaCobbDoc(e))
        push("RCT", e => this._tablaRctDoc(e))
      } else if (m.origen) {
        push("HUMEDAD", e => this._tablaHumedadComplejo(e))
        push("GRAMAJE", e => this._tablaGramajeComplejo(e))
        push("ESPESOR", e => this._tablaEspesorComplejo(e))
        push("ECT", e => this._tablaEct(e))
        push("FCT", e => this._tablaFctDoc(e))
        push("BCT_MEDIDO", e => this._tablaBctMedido(e))
      }

      // Cualquier ensayo cuyo tipo no haya sido cubierto por la hoja correspondiente (ej. un tipo
      // excepcional registrado en un origen atípico, o BCT_TEORICO/VISCOSIDAD/PH/SOLIDOS/LUGOL,
      // que no tienen sección propia en el formulario físico) igual se lista, en formato genérico,
      // para no perder datos.
      ;["HUMEDAD", "GRAMAJE", "ESPESOR", "COBB", "RCT", "FCT", "ECT", "BCT_MEDIDO", "BCT_TEORICO", "VISCOSIDAD", "PH", "SOLIDOS", "LUGOL"]
        .forEach(tipo => { if (!cubiertos.has(tipo)) porTipo(tipo).forEach(e => tablas.push(this._tablaGenerica(e))) })

      window.PrintExporter.printReport({
        titulo: `Informe de Laboratorio - Muestra #${m.id}`,
        empresa: "INNPACK",
        subtitulo: m.observacion ? `Observación general: ${m.observacion}` : "",
        totalRegistros: ensayos.length,
        resumen,
        tablas
      })
    }

    // ---------- Punto 37 REG-LAB-04: secciones del informe con el layout del formulario físico ----------
    // Cada sección es una tabla "transpuesta" (columnas = bobinas/probetas, filas = atributos),
    // igual al REG-LAB-04 real (columnas Onda/Liner/Cartulina lado a lado). El título de cada
    // sección lleva la metadata del ensayo (método/analista/fecha/resultado/cumplimiento/estado).

    _metaEnsayo(e) {
      const resultado = e.resultadoValor != null ? `${e.resultadoValor} ${e.resultadoUnidad || ""}`.trim() : "-"
      const partes = [
        `#${e.id}`, `Método: ${e.metodo || "-"}`, `Analista: ${e.analistaNombre || "-"}`, `Fecha: ${e.fecha || "-"}`,
        `Resultado: ${resultado}`, `Cumplimiento: ${e.cumplimiento || "-"}`, `Estado: ${this._labelEstado(e.estado)}`
      ]
      if (e.observacion) partes.push(`Obs: ${e.observacion}`)
      return partes.join(" · ")
    }

    // Agrupa las bobinas muestreadas (Punto 11) en las 3 columnas fijas del REG-LAB-04
    // (ONDA/LINER/CARTULINA), igual que el formulario físico, en vez de una columna dinámica por
    // bobina. Si hay más de una bobina en la misma posición, sus valores van juntos en la misma
    // celda separados por "/".
    _agruparBobinasPorPosicion(bobinas) {
      const grupos = { Onda: [], Liner: [], Cartulina: [] }
      bobinas.forEach(b => {
        const pos = ["Onda", "Liner", "Cartulina"].includes(b.posicion) ? b.posicion : "Onda"
        grupos[pos].push(b)
      })
      return grupos
    }

    _celdaBobinas(lista, campo) {
      if (!lista || !lista.length) return "-"
      return lista.map(b => (b[campo] ?? "-")).join(" / ")
    }

    // Humedad/Gramaje/Espesor de SUSTRATOS (Hoja 1): columnas fijas ONDA/LINER/CARTULINA, igual
    // que el REG-LAB-04 físico. Si el ensayo no tiene bobinas muestreadas (Punto 11, flujo simple
    // de 1 bobina), cae a la tabla genérica.
    _tablaSustrato(e, titulo, labels) {
      const d = e.detalle || {}
      const bobinas = d.bobinas || []
      if (bobinas.length === 0) return this._tablaGenerica(e, titulo)

      const grupos = this._agruparBobinasPorPosicion(bobinas)
      const posiciones = ["Onda", "Liner", "Cartulina"]
      // Punto 8/9 REG-LAB-04: "Origen de la muestra" (Bobinas / Separación de papeles o descape)
      // solo existe en el detalle de Humedad — no aplica a Gramaje/Espesor.
      const origenMuestraLabel = { Bobinas: "Bobinas", SeparacionPapeles: "Separación de papeles o descape" }
      return {
        titulo: `${titulo} — ${this._metaEnsayo(e)}`,
        columnas: ["", ...posiciones.map(p => p.toUpperCase())],
        filas: [
          ...(d.origenMuestra ? [["Origen de la muestra", origenMuestraLabel[d.origenMuestra] || d.origenMuestra, "", ""]] : []),
          ["Lote de bobina de origen", ...posiciones.map(p => this._celdaBobinas(grupos[p], "lote"))],
          [labels[0], ...posiciones.map(p => this._celdaBobinas(grupos[p], "valor1"))],
          [labels[1], ...posiciones.map(p => this._celdaBobinas(grupos[p], "valor2"))],
          [labels[2], ...posiciones.map(p => this._celdaBobinas(grupos[p], "valor3"))],
          ["Promedio", ...posiciones.map(p => this._celdaBobinas(grupos[p], "promedio"))],
        ]
      }
    }

    // 6. HUMEDAD MONOTAPA (%) — sección propia del REG-LAB-04 para el ensayo de Humedad hecho
    // directo sobre una muestra de origen Monotapa (distinto de "Humedad de sustratos", sección 1).
    _tablaHumedadMonotapa(e, m) {
      const d = e.detalle || {}
      return {
        titulo: `6. HUMEDAD MONOTAPA (%) — ${this._metaEnsayo(e)}`,
        columnas: ["Atributo", "Valor"],
        filas: [
          ["Hora", e.fecha || "-"],
          ["Punto izquierdo (%)", d.higrometroIzquierdo ?? "-"],
          ["Centro (%)", d.higrometroCentro ?? "-"],
          ["Punto derecho (%)", d.higrometroDerecho ?? "-"],
          ["Peso onda (g)", m.pesoOndaExtendida ?? "-"],
          ["Peso onda 10 x 10 (g)", m.pesoRecorte10x10 ?? "-"],
          ["Medida onda extendida (cm)", m.longitudOndaExtendida ?? "-"],
        ]
      }
    }

    // 4. COBB (g/m²): el formulario físico solo contempla ONDA/LINER (2 probetas); si el ensayo
    // registró una 3ª probeta se agrega igual como columna adicional, sin perder el dato.
    _tablaCobbDoc(e) {
      const d = e.detalle || {}
      const cols = [{ label: "ONDA", p: d.p1 }, { label: "LINER", p: d.p2 }]
      if (d.p3) cols.push({ label: "Adicional", p: d.p3 })
      return {
        titulo: `4. COBB (g/m²) – Área efectiva: 100 cm² — ${this._metaEnsayo(e)}`,
        columnas: ["", ...cols.map(c => c.label)],
        filas: [
          ["Lote de bobina de origen", ...cols.map(c => c.p?.bobina || "-")],
          ["Cara", ...cols.map(c => c.p?.cara || "-")],
          ["Peso inicial (g)", ...cols.map(c => c.p?.pesoInicial ?? "-")],
          ["Peso final (g)", ...cols.map(c => c.p?.pesoFinal ?? "-")],
          ["Seg.", ...cols.map(c => c.p?.tiempo || "-")],
          ["Resultado (g/m²)", ...cols.map(c => c.p?.resultado ?? "-")],
        ]
      }
    }

    // 5. RCT (lbf): igual que Cobb, columnas fijas ONDA/LINER.
    _tablaRctDoc(e) {
      const d = e.detalle || {}
      const cols = [{ label: "ONDA", p: d.p1 }, { label: "LINER", p: d.p2 }]
      if (d.p3) cols.push({ label: "Adicional", p: d.p3 })
      return {
        titulo: `5. RCT (lbf) — ${this._metaEnsayo(e)} · Promedio fuerza: ${d.promedioForce ?? "-"} · Promedio resistencia: ${d.promedioStrength ?? "-"}`,
        columnas: ["", ...cols.map(c => c.label)],
        filas: [
          ["Lote de bobina de origen", ...cols.map(c => c.p?.bobina || "-")],
          ["Fuerza (lbf)", ...cols.map(c => c.p?.force ?? "-")],
          [`Resistencia (${d.strengthUnidad || "lbf/m"})`, ...cols.map(c => c.p?.strength ?? "-")],
        ]
      }
    }

    // 5. FCT (lbf) — Hoja 2: N° de muestra + M1/M2/M3, sin fila de bobina (no aplica al complejo).
    _tablaFctDoc(e) {
      const d = e.detalle || {}
      const probetas = [d.p1, d.p2, d.p3].filter(Boolean)
      return {
        titulo: `5. FCT (lbf) — ${this._metaEnsayo(e)} · Promedio fuerza: ${d.promedioForce ?? "-"} · Promedio resistencia: ${d.promedioStrength ?? "-"}`,
        columnas: ["", ...probetas.map((_, i) => `M${i + 1}`)],
        filas: [
          ["Fuerza (lbf)", ...probetas.map(p => p.force ?? "-")],
          [`Resistencia (${d.strengthUnidad || "psi"})`, ...probetas.map(p => p.strength ?? "-")],
        ]
      }
    }

    _tablaEct(e) {
      const d = e.detalle || {}
      const probetas = [d.p1, d.p2, d.p3, d.p4, d.p5].filter(Boolean)
      return {
        titulo: `4. ECT (lbf) — ${this._metaEnsayo(e)} · Promedio fuerza: ${d.promedioForce ?? "-"} · Promedio resistencia: ${d.promedioStrengthLbfM ?? "-"} lbf/m (${d.promedioStrengthLbIn ?? "-"} lb/in)`,
        columnas: ["", ...probetas.map((_, i) => `M${i + 1}`)],
        filas: [
          ["Fuerza (lbf)", ...probetas.map(p => p.force ?? "-")],
          ["Resistencia (lbf/m)", ...probetas.map(p => p.strength ?? "-")],
        ]
      }
    }

    _tablaBctMedido(e) {
      const d = e.detalle || {}
      const cajas = [d.c1, d.c2, d.c3].filter(Boolean)
      return {
        titulo: `6. BCT (lbf) — ${this._metaEnsayo(e)} · Promedio: ${d.promedioLbf ?? "-"} lbf${d.motivoMenos3 ? ` · Motivo <3 cajas: ${d.motivoMenos3}` : ""}`,
        columnas: ["", ...cajas.map((_, i) => `M${i + 1}`)],
        filas: [
          ["Largo (mm)", ...cajas.map(c => c.largo ?? "-")],
          ["Ancho (mm)", ...cajas.map(c => c.ancho ?? "-")],
          ["Alto (mm)", ...cajas.map(c => c.alto ?? "-")],
          ["Tipo de onda", ...cajas.map(c => c.tipoOnda || "-")],
          ["Gramaje complejo (g/m²)", ...cajas.map(c => c.gramajeComplejo ?? "-")],
          ["Espesor complejo (mm)", ...cajas.map(c => c.espesorComplejo ?? "-")],
          ["Resultado (lbf)", ...cajas.map(c => c.resultadoLbf ?? "-")],
        ]
      }
    }

    // 1/2/3. HUMEDAD/GRAMAJE/ESPESOR DEL COMPLEJO — Hoja 2: sin columnas Onda/Liner/Cartulina (el
    // complejo ya no distingue sustratos), un único set de valores por "N° de muestra".
    _tablaHumedadComplejo(e) {
      const d = e.detalle || {}
      return {
        titulo: `1. HUMEDAD DEL COMPLEJO (%) — ${this._metaEnsayo(e)}`,
        columnas: ["Atributo", "Valor"],
        filas: [
          ["Método", d.metodoEquipo || "-"],
          ["Punto izquierdo (%)", d.higrometroIzquierdo ?? "-"],
          ["Centro (%)", d.higrometroCentro ?? "-"],
          ["Punto derecho (%)", d.higrometroDerecho ?? "-"],
          ["Peso inicial (g)", d.horno1PesoInicial ?? "-"],
          ["Peso final (g)", d.horno1PesoFinal ?? "-"],
          ["Resultado (%)", d.hornoPromedio ?? d.higrometroPromedio ?? "-"],
        ]
      }
    }

    _tablaGramajeComplejo(e) {
      const d = e.detalle || {}
      return {
        titulo: `2. GRAMAJE DEL COMPLEJO (g/m²) — ${this._metaEnsayo(e)}`,
        columnas: ["Atributo", "Valor"],
        filas: [
          ["Tamaño de probeta", d.tamanoProbeta || "-"],
          ["Peso probeta (g) — Punto izquierdo", d.muestra1 ?? "-"],
          ["Peso probeta (g) — Centro", d.muestra2 ?? "-"],
          ["Peso probeta (g) — Punto derecho", d.muestra3 ?? "-"],
          ["Resultado promedio (g/m²)", d.promedio ?? "-"],
        ]
      }
    }

    _tablaEspesorComplejo(e) {
      const d = e.detalle || {}
      return {
        titulo: `3. ESPESOR DEL COMPLEJO (mm) — ${this._metaEnsayo(e)}`,
        columnas: ["Atributo", "Valor"],
        filas: [
          ["Punto izquierdo (mm)", d.medicion1 ?? "-"],
          ["Centro (mm)", d.medicion2 ?? "-"],
          ["Punto derecho (mm)", d.medicion3 ?? "-"],
          ["Resultado promedio (mm)", d.promedio ?? "-"],
        ]
      }
    }

    // Fallback universal: tabla de 2 columnas (Atributo/Valor) a partir del detalle plano del
    // ensayo — usado por BCT Teórico/Viscosidad/pH/Sólidos/Lugol (sin layout de columnas por
    // bobina/probeta en el REG-LAB-04) y por Humedad/Gramaje/Espesor cuando no hay bobinas
    // muestreadas (flujo simple de 1 bobina, el que ya existía).
    _tablaGenerica(e, tituloOverride) {
      const d = e.detalle || {}
      const etiquetas = {
        metodoEquipo: "Método de equipo", higrometroIzquierdo: "Higrómetro - Punto izquierdo (%)",
        higrometroCentro: "Higrómetro - Centro (%)", higrometroDerecho: "Higrómetro - Punto derecho (%)",
        higrometroPromedio: "Higrómetro - Promedio (%)", termobalanzaValor: "Termobalanza (%)",
        horno1PesoInicial: "Horno 1 - Peso inicial (g)", horno1PesoFinal: "Horno 1 - Peso final (g)",
        horno2PesoInicial: "Horno 2 - Peso inicial (g)", horno2PesoFinal: "Horno 2 - Peso final (g)",
        horno3PesoInicial: "Horno 3 - Peso inicial (g)", horno3PesoFinal: "Horno 3 - Peso final (g)",
        hornoPromedio: "Horno - Promedio (%)", diferenciaMetodos: "Diferencia entre métodos (%)",
        bobinaOnda: "Bobina Onda", bobinaLiner: "Bobina Liner", bobinaCartulina: "Bobina Cartulina",
        origenMuestra: "Origen de la muestra",
        tipoMaterial: "Tipo de material", modalidad: "Modalidad", tamanoProbeta: "Tamaño de probeta",
        muestra1: "Muestra 1", muestra2: "Muestra 2", muestra3: "Muestra 3", promedio: "Promedio",
        tipoMedicion: "Tipo de medición", medicion1: "Medición 1", medicion2: "Medición 2", medicion3: "Medición 3",
        tipoAdhesivo: "Tipo de adhesivo", temperatura: "Temperatura", equipo: "Equipo", husillo: "Husillo",
        velocidadRpm: "Velocidad (RPM)", resultadoCp: "Resultado (cP)",
        valorTexto: "Valor / rango", colorObservado: "Color observado",
        puntoMuestra: "Punto de muestra", coloracion: "Coloración", resultado: "Resultado", interpretacion: "Interpretación",
        cumplimiento: "Cumplimiento",
        ectLbfM: "ECT usado (lbf/m)", ectLbIn: "ECT usado (lb/in)", espesorMm: "Espesor usado (mm)",
        espesorIn: "Espesor usado (in)", largoMm: "Largo interno (mm)", anchoMm: "Ancho interno (mm)",
        perimetroIn: "Perímetro (in)", bctTeoricoLbf: "BCT teórico (lbf)", bctTeoricoKgf: "BCT teórico (kgf)",
        aviso: "Aviso",
      }
      const excluir = new Set(["bobinas", "ectEnsayoId", "espesorEnsayoId", "largoIn", "anchoIn", "d1", "d2", "d3"])

      const filas = Object.keys(d)
        .filter(k => !excluir.has(k) && d[k] !== null && d[k] !== undefined && d[k] !== "" && typeof d[k] !== "object")
        .map(k => [etiquetas[k] || k, d[k]])

      // Sólidos totales: 3 determinaciones anidadas (d1/d2/d3), no capturadas por el filtro plano de arriba.
      ;["d1", "d2", "d3"].forEach((key, i) => {
        const det = d[key]
        if (det) filas.push([`Determinación ${i + 1} (m1/m2/m3)`, `${det.m1 ?? "-"} / ${det.m2 ?? "-"} / ${det.m3 ?? "-"}`])
      })

      return {
        titulo: `${tituloOverride || e.tipoEnsayo} — ${this._metaEnsayo(e)}`,
        columnas: ["Atributo", "Valor"],
        filas
      }
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
        HUMEDAD: { modalId: "mlbModalHumedad", precargar: e => this._precargarHumedad(e) },
        GRAMAJE: { modalId: "mlbModalGramaje", precargar: e => this._precargarGramaje(e) },
        COBB: { modalId: "mlbModalCobb", precargar: e => this._precargarCobb(e) },
        ESPESOR: { modalId: "mlbModalEspesor", precargar: e => this._precargarEspesor(e) },
        RCT: { modalId: "mlbModalRct", precargar: e => this._precargarResistencia(e, "Rct", true) },
        FCT: { modalId: "mlbModalFct", precargar: e => this._precargarResistencia(e, "Fct", false) },
        ECT: { modalId: "mlbModalEct", precargar: e => this._precargarEct(e) },
        BCT_MEDIDO: { modalId: "mlbModalBctMedido", precargar: e => this._precargarBctMedido(e) },
        BCT_TEORICO: { modalId: "mlbModalBctTeorico", precargar: e => this._precargarBctTeorico(e) },
        VISCOSIDAD: { modalId: "mlbModalViscosidad", precargar: e => this._precargarViscosidad(e) },
        PH: { modalId: "mlbModalPh", precargar: e => this._precargarPh(e) },
        SOLIDOS: { modalId: "mlbModalSolidos", precargar: e => this._precargarSolidos(e) },
        LUGOL: { modalId: "mlbModalLugol", precargar: e => this._precargarLugol(e) },
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
      let bloque = document.getElementById("mlbMotivoCorreccionBloque")
      if (!bloque) {
        bloque = document.createElement("div")
        bloque.id = "mlbMotivoCorreccionBloque"
        bloque.className = "mlb-form-campo mlb-form-campo-full"
        bloque.innerHTML = '<label>Motivo de la corrección *</label><textarea id="mlbMotivoCorreccionInput" rows="2"></textarea>'
      }
      document.getElementById("mlbMotivoCorreccionInput") // asegura que exista antes de limpiar
      bloque.querySelector("textarea").value = ""

      const modal = document.getElementById(modalId)
      const acciones = modal.querySelector(".mlb-form-acciones")
      acciones.parentNode.insertBefore(bloque, acciones)
    }

    _limpiarCorreccion() {
      this._corrigiendoEnsayoId = null
      const bloque = document.getElementById("mlbMotivoCorreccionBloque")
      if (bloque) bloque.remove()
    }

    // Si se está corrigiendo un ensayo, agrega ensayoOriginalId/motivoReemplazo al payload y
    // valida que el motivo esté completo. Devuelve false (y muestra la alerta) si falta el
    // motivo, para que el guardarXxx() que la llama corte el flujo con un simple `if (!... ) return`.
    _aplicarDatosCorreccion(data) {
      if (!this._corrigiendoEnsayoId) return true

      const motivo = document.getElementById("mlbMotivoCorreccionInput")?.value?.trim()
      if (!motivo) {
        alert("Debes indicar el motivo de la corrección")
        return false
      }

      data.ensayoOriginalId = this._corrigiendoEnsayoId
      data.motivoReemplazo = motivo
      return true
    }

    // Punto 11 REG-LAB-04: reconstruye las filas de bobinas ya guardadas al corregir un ensayo.
    _precargarBobinas(containerId, labelsCsv, bobinas) {
      const contenedor = document.getElementById(containerId)
      contenedor.innerHTML = ""
      ;(bobinas || []).forEach(b => {
        const fila = this._renderFilaBobina(labelsCsv.split(","))
        fila.querySelector(".mlb-bob-numero").value = b.numeroBobina || ""
        fila.querySelector(".mlb-bob-lote").value = b.lote || ""
        fila.querySelector(".mlb-bob-posicion").value = b.posicion || "Onda"
        fila.querySelector(".mlb-bob-v1").value = b.valor1 ?? ""
        fila.querySelector(".mlb-bob-v2").value = b.valor2 ?? ""
        fila.querySelector(".mlb-bob-v3").value = b.valor3 ?? ""
        contenedor.appendChild(fila)
      })
    }

    _precargarHumedad(e) {
      const d = e.detalle || {}
      document.getElementById("mlbHumMetodoEquipo").value = d.metodoEquipo || "Higrometro"
      document.getElementById("mlbHumIzq").value = d.higrometroIzquierdo ?? ""
      document.getElementById("mlbHumCentro").value = d.higrometroCentro ?? ""
      document.getElementById("mlbHumDer").value = d.higrometroDerecho ?? ""
      document.getElementById("mlbHumTermo").value = d.termobalanzaValor ?? ""
      document.getElementById("mlbHum1i").value = d.horno1PesoInicial ?? ""
      document.getElementById("mlbHum1f").value = d.horno1PesoFinal ?? ""
      document.getElementById("mlbHum2i").value = d.horno2PesoInicial ?? ""
      document.getElementById("mlbHum2f").value = d.horno2PesoFinal ?? ""
      document.getElementById("mlbHum3i").value = d.horno3PesoInicial ?? ""
      document.getElementById("mlbHum3f").value = d.horno3PesoFinal ?? ""
      document.getElementById("mlbHumBobinaOnda").value = d.bobinaOnda || ""
      document.getElementById("mlbHumBobinaLiner").value = d.bobinaLiner || ""
      document.getElementById("mlbHumBobinaCartulina").value = d.bobinaCartulina || ""
      document.getElementById("mlbHumOrigenMuestra").value = d.origenMuestra || ""
      document.getElementById("mlbHumMetodo").value = e.metodo || ""
      document.getElementById("mlbHumObservacion").value = e.observacion || ""
      this._precargarBobinas("mlbHumBobinasFilas", "Punto izquierdo,Centro,Punto derecho", d.bobinas)
      this.actualizarCamposHumedad()
    }

    _precargarGramaje(e) {
      const d = e.detalle || {}
      document.getElementById("mlbGraTipoMaterial").value = d.tipoMaterial || "Papel"
      document.getElementById("mlbGraModalidad").value = d.modalidad || "ProbetaPeso"
      document.getElementById("mlbGraTamanoProbeta").value = d.tamanoProbeta || "10x10"
      document.getElementById("mlbGra1").value = d.muestra1 ?? ""
      document.getElementById("mlbGra2").value = d.muestra2 ?? ""
      document.getElementById("mlbGra3").value = d.muestra3 ?? ""
      document.getElementById("mlbGraBobinaOnda").value = d.bobinaOnda || ""
      document.getElementById("mlbGraBobinaLiner").value = d.bobinaLiner || ""
      document.getElementById("mlbGraBobinaCartulina").value = d.bobinaCartulina || ""
      document.getElementById("mlbGraMetodo").value = e.metodo || ""
      document.getElementById("mlbGraObservacion").value = e.observacion || ""
      this._precargarBobinas("mlbGraBobinasFilas", "Muestra 1,Muestra 2,Muestra 3", d.bobinas)
    }

    _precargarCobb(e) {
      const d = e.detalle || {}
      ;[1, 2, 3].forEach(n => {
        const p = d[`p${n}`] || {}
        document.getElementById(`mlbCobb${n}Bobina`).value = p.bobina || ""
        document.getElementById(`mlbCobb${n}Cara`).value = p.cara || "Externa"
        document.getElementById(`mlbCobb${n}Inicial`).value = p.pesoInicial ?? ""
        document.getElementById(`mlbCobb${n}Final`).value = p.pesoFinal ?? ""
        document.getElementById(`mlbCobb${n}Tiempo`).value = p.tiempo || ""
      })
      document.getElementById("mlbCobbMetodo").value = e.metodo || ""
      document.getElementById("mlbCobbObservacion").value = e.observacion || ""
    }

    _precargarEspesor(e) {
      const d = e.detalle || {}
      document.getElementById("mlbEspTipoMedicion").value = d.tipoMedicion || "Ubicacion"
      document.getElementById("mlbEsp1").value = d.medicion1 ?? ""
      document.getElementById("mlbEsp2").value = d.medicion2 ?? ""
      document.getElementById("mlbEsp3").value = d.medicion3 ?? ""
      document.getElementById("mlbEspBobinaOnda").value = d.bobinaOnda || ""
      document.getElementById("mlbEspBobinaLiner").value = d.bobinaLiner || ""
      document.getElementById("mlbEspBobinaCartulina").value = d.bobinaCartulina || ""
      document.getElementById("mlbEspMetodo").value = e.metodo || ""
      document.getElementById("mlbEspObservacion").value = e.observacion || ""
      this._precargarBobinas("mlbEspBobinasFilas", "Punto izquierdo,Centro,Punto derecho", d.bobinas)
    }

    _precargarResistencia(e, prefijoIds, esRct) {
      const d = e.detalle || {}
      if (esRct) document.getElementById("mlbRctComponente").value = d.componente || "Liner"
      ;[1, 2, 3].forEach(n => {
        const p = d[`p${n}`] || {}
        if (esRct) document.getElementById(`mlb${prefijoIds}${n}Bobina`).value = p.bobina || ""
        document.getElementById(`mlb${prefijoIds}${n}Force`).value = p.force ?? ""
        document.getElementById(`mlb${prefijoIds}${n}Strength`).value = p.strength ?? ""
      })
      document.getElementById(`mlb${prefijoIds}StrengthUnidad`).value = d.strengthUnidad || ""
      document.getElementById(`mlb${prefijoIds}Metodo`).value = e.metodo || ""
      document.getElementById(`mlb${prefijoIds}Observacion`).value = e.observacion || ""
    }

    _precargarEct(e) {
      const d = e.detalle || {}
      ;[1, 2, 3, 4, 5].forEach(n => {
        const p = d[`p${n}`] || {}
        document.getElementById(`mlbEct${n}`).value = p.force ?? ""
      })
      document.getElementById("mlbEctMetodo").value = e.metodo || ""
      document.getElementById("mlbEctObservacion").value = e.observacion || ""
    }

    _precargarBctMedido(e) {
      const d = e.detalle || {}
      document.getElementById("mlbBctMedCajas").value = String(d.cajasEnsayadas || 3)
      document.getElementById("mlbBctMedMotivo").value = d.motivoMenos3 || ""
      this.renderBctMedCajas()
      ;[1, 2, 3].forEach(n => {
        const c = d[`c${n}`]
        const largoEl = document.getElementById(`mlbBctMedC${n}Largo`)
        if (!c || !largoEl) return
        largoEl.value = c.largo ?? ""
        document.getElementById(`mlbBctMedC${n}Ancho`).value = c.ancho ?? ""
        document.getElementById(`mlbBctMedC${n}Alto`).value = c.alto ?? ""
        document.getElementById(`mlbBctMedC${n}TipoOnda`).value = c.tipoOnda || ""
        document.getElementById(`mlbBctMedC${n}Gramaje`).value = c.gramajeComplejo ?? ""
        document.getElementById(`mlbBctMedC${n}Espesor`).value = c.espesorComplejo ?? ""
        document.getElementById(`mlbBctMedC${n}Resultado`).value = c.resultadoLbf ?? ""
      })
      document.getElementById("mlbBctMedMetodo").value = e.metodo || ""
      document.getElementById("mlbBctMedObservacion").value = e.observacion || ""
    }

    _precargarBctTeorico(e) {
      const d = e.detalle || {}
      const ects = (this._ensayosActuales || []).filter(x => x.tipoEnsayo === "ECT" && x.estado === "Finalizado")
      const espesores = (this._ensayosActuales || []).filter(x => x.tipoEnsayo === "ESPESOR" && x.estado === "Finalizado")

      const selEct = document.getElementById("mlbBctTeoEct")
      const selEsp = document.getElementById("mlbBctTeoEspesor")
      selEct.innerHTML = ects.map(x => `<option value="${x.id}">Ensayo #${x.id} - ${x.resultadoValor} ${x.resultadoUnidad} (${x.fecha})</option>`).join("")
      selEsp.innerHTML = espesores.map(x => `<option value="${x.id}">Ensayo #${x.id} - ${x.resultadoValor} ${x.resultadoUnidad} (${x.fecha})</option>`).join("")

      if (d.ectEnsayoId) selEct.value = String(d.ectEnsayoId)
      if (d.espesorEnsayoId) selEsp.value = String(d.espesorEnsayoId)
      document.getElementById("mlbBctTeoLargo").value = d.largoMm ?? ""
      document.getElementById("mlbBctTeoAncho").value = d.anchoMm ?? ""
      document.getElementById("mlbBctTeoObservacion").value = e.observacion || ""
    }

    _precargarViscosidad(e) {
      const d = e.detalle || {}
      document.getElementById("mlbViscTipoAdhesivo").value = d.tipoAdhesivo || ""
      document.getElementById("mlbViscTemperatura").value = d.temperatura ?? ""
      document.getElementById("mlbViscEquipo").value = d.equipo || ""
      document.getElementById("mlbViscHusillo").value = d.husillo || ""
      document.getElementById("mlbViscRpm").value = d.velocidadRpm ?? ""
      document.getElementById("mlbViscResultado").value = d.resultadoCp ?? ""
      document.getElementById("mlbViscObservacion").value = e.observacion || ""
    }

    _precargarPh(e) {
      const d = e.detalle || {}
      document.getElementById("mlbPhValor").value = d.valorTexto || ""
      document.getElementById("mlbPhColor").value = d.colorObservado || ""
      document.getElementById("mlbPhObservacion").value = e.observacion || ""
    }

    _precargarSolidos(e) {
      const d = e.detalle || {}
      ;[1, 2, 3].forEach(n => {
        const det = d[`d${n}`] || {}
        document.getElementById(`mlbSol${n}M1`).value = det.m1 ?? ""
        document.getElementById(`mlbSol${n}M2`).value = det.m2 ?? ""
        document.getElementById(`mlbSol${n}M3`).value = det.m3 ?? ""
      })
      document.getElementById("mlbSolObservacion").value = e.observacion || ""
    }

    _precargarLugol(e) {
      const d = e.detalle || {}
      document.getElementById("mlbLugolPunto").value = d.puntoMuestra || ""
      document.getElementById("mlbLugolColoracion").value = d.coloracion || ""
      document.getElementById("mlbLugolResultado").value = d.resultado || "Negativo"
      document.getElementById("mlbLugolInterpretacion").value = d.interpretacion || ""
      document.getElementById("mlbLugolCumplimiento").value = e.cumplimiento || "Sin especificacion"
      document.getElementById("mlbLugolObservacion").value = e.observacion || ""
    }

    // =====================================================================
    // ENSAYO HUMEDAD
    // =====================================================================
    actualizarCamposHumedad() {
      const metodo = document.getElementById("mlbHumMetodoEquipo").value
      document.getElementById("mlbHumHigrometroCampos").style.display = metodo === "Higrometro" ? "flex" : "none"
      document.getElementById("mlbHumTermobalanzaCampos").style.display = metodo === "Termobalanza" ? "flex" : "none"
      document.getElementById("mlbHumHornoCampos").style.display = metodo === "Horno" ? "block" : "none"
    }

    async guardarHumedad() {
      if (!this._muestraActualId) return

      const num = (id) => {
        const v = document.getElementById(id).value
        return v === "" ? null : parseFloat(v)
      }

      const data = {
        muestraId: this._muestraActualId,
        metodoEquipo: document.getElementById("mlbHumMetodoEquipo").value,
        metodo: document.getElementById("mlbHumMetodo").value.trim(),
        observacion: document.getElementById("mlbHumObservacion").value.trim(),
        higrometroIzquierdo: num("mlbHumIzq"),
        higrometroCentro: num("mlbHumCentro"),
        higrometroDerecho: num("mlbHumDer"),
        termobalanzaValor: num("mlbHumTermo"),
        horno1PesoInicial: num("mlbHum1i"),
        horno1PesoFinal: num("mlbHum1f"),
        horno2PesoInicial: num("mlbHum2i"),
        horno2PesoFinal: num("mlbHum2f"),
        horno3PesoInicial: num("mlbHum3i"),
        horno3PesoFinal: num("mlbHum3f"),
        bobinaOnda: document.getElementById("mlbHumBobinaOnda").value.trim() || null,
        bobinaLiner: document.getElementById("mlbHumBobinaLiner").value.trim() || null,
        bobinaCartulina: document.getElementById("mlbHumBobinaCartulina").value.trim() || null,
        bobinas: this._leerBobinas("mlbHumBobinasFilas"),
        origenMuestra: document.getElementById("mlbHumOrigenMuestra").value || null,
      }
      if (!this._aplicarDatosCorreccion(data)) return

      try {
        const res = await window.PhotinoBridge.send({ action: "muestraLab.humedad.guardar", data })
        if (!res || res.ok === false) throw new Error(res?.error || "Error guardando el ensayo de humedad")

        this.cerrarModal("mlbModalHumedad")
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
        tipoMaterial: document.getElementById("mlbGraTipoMaterial").value,
        modalidad: document.getElementById("mlbGraModalidad").value,
        tamanoProbeta: document.getElementById("mlbGraTamanoProbeta").value,
        metodo: document.getElementById("mlbGraMetodo").value.trim(),
        observacion: document.getElementById("mlbGraObservacion").value.trim(),
        muestra1: num("mlbGra1"),
        muestra2: num("mlbGra2"),
        muestra3: num("mlbGra3"),
        bobinaOnda: document.getElementById("mlbGraBobinaOnda").value.trim() || null,
        bobinaLiner: document.getElementById("mlbGraBobinaLiner").value.trim() || null,
        bobinaCartulina: document.getElementById("mlbGraBobinaCartulina").value.trim() || null,
        bobinas: this._leerBobinas("mlbGraBobinasFilas")
      }
      if (!this._aplicarDatosCorreccion(data)) return

      try {
        const res = await window.PhotinoBridge.send({ action: "muestraLab.gramaje.guardar", data })
        if (!res || res.ok === false) throw new Error(res?.error || "Error guardando el ensayo de gramaje")

        this.cerrarModal("mlbModalGramaje")
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
        bobina: txt(`mlbCobb${n}Bobina`),
        cara: document.getElementById(`mlbCobb${n}Cara`).value,
        pesoInicial: num(`mlbCobb${n}Inicial`),
        pesoFinal: num(`mlbCobb${n}Final`),
        tiempo: txt(`mlbCobb${n}Tiempo`)
      })

      const data = {
        muestraId: this._muestraActualId,
        metodo: document.getElementById("mlbCobbMetodo").value.trim(),
        observacion: document.getElementById("mlbCobbObservacion").value.trim(),
        p1: probeta(1),
        p2: probeta(2),
        p3: probeta(3)
      }
      if (!this._aplicarDatosCorreccion(data)) return

      try {
        const res = await window.PhotinoBridge.send({ action: "muestraLab.cobb.guardar", data })
        if (!res || res.ok === false) throw new Error(res?.error || "Error guardando el ensayo Cobb")

        this.cerrarModal("mlbModalCobb")
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
        tipoMedicion: document.getElementById("mlbEspTipoMedicion").value,
        metodo: document.getElementById("mlbEspMetodo").value.trim(),
        observacion: document.getElementById("mlbEspObservacion").value.trim(),
        medicion1: num("mlbEsp1"),
        medicion2: num("mlbEsp2"),
        medicion3: num("mlbEsp3"),
        bobinaOnda: document.getElementById("mlbEspBobinaOnda").value.trim() || null,
        bobinaLiner: document.getElementById("mlbEspBobinaLiner").value.trim() || null,
        bobinaCartulina: document.getElementById("mlbEspBobinaCartulina").value.trim() || null,
        bobinas: this._leerBobinas("mlbEspBobinasFilas")
      }
      if (!this._aplicarDatosCorreccion(data)) return

      try {
        const res = await window.PhotinoBridge.send({ action: "muestraLab.espesor.guardar", data })
        if (!res || res.ok === false) throw new Error(res?.error || "Error guardando el ensayo de espesor")

        this.cerrarModal("mlbModalEspesor")
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
        bobina: txt(`mlb${prefijoIds}${n}Bobina`),
        force: num(`mlb${prefijoIds}${n}Force`),
        strength: num(`mlb${prefijoIds}${n}Strength`)
      })

      const data = {
        muestraId: this._muestraActualId,
        metodo: document.getElementById(`mlb${prefijoIds}Metodo`).value.trim(),
        observacion: document.getElementById(`mlb${prefijoIds}Observacion`).value.trim(),
        strengthUnidad: txt(`mlb${prefijoIds}StrengthUnidad`),
        p1: probeta(1),
        p2: probeta(2),
        p3: probeta(3)
      }
      if (prefijoIds === "Rct") {
        data.componente = document.getElementById("mlbRctComponente").value
      }
      if (!this._aplicarDatosCorreccion(data)) return

      try {
        const res = await window.PhotinoBridge.send({ action: `muestraLab.${accionSufijo}.guardar`, data })
        if (!res || res.ok === false) throw new Error(res?.error || `Error guardando el ensayo ${accionSufijo.toUpperCase()}`)

        this.cerrarModal(`mlbModal${prefijoIds}`)
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
        metodo: document.getElementById("mlbEctMetodo").value.trim(),
        observacion: document.getElementById("mlbEctObservacion").value.trim(),
        p1Force: num("mlbEct1"),
        p2Force: num("mlbEct2"),
        p3Force: num("mlbEct3"),
        p4Force: num("mlbEct4"),
        p5Force: num("mlbEct5")
      }
      if (!this._aplicarDatosCorreccion(data)) return

      try {
        const res = await window.PhotinoBridge.send({ action: "muestraLab.ect.guardar", data })
        if (!res || res.ok === false) throw new Error(res?.error || "Error guardando el ensayo ECT")

        this.cerrarModal("mlbModalEct")
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
      document.getElementById("mlbBctMedCajas").value = "3"
      document.getElementById("mlbBctMedMotivo").value = ""
      document.getElementById("mlbBctMedMetodo").value = ""
      document.getElementById("mlbBctMedObservacion").value = ""
      this.renderBctMedCajas()
      this.abrirModal("mlbModalBctMedido")
    }

    renderBctMedCajas() {
      const n = parseInt(document.getElementById("mlbBctMedCajas").value, 10)
      document.getElementById("mlbBctMedMotivoBloque").style.display = n < 3 ? "block" : "none"

      const bloque = document.getElementById("mlbBctMedCajasBloque")
      let html = ""
      for (let i = 1; i <= n; i++) {
        html += `
          <div class="module-title" style="font-size:13px; margin-top:8px;">Caja ${i}</div>
          <div class="mlb-form-row">
            <div class="mlb-form-campo"><label>Largo</label><input type="number" step="0.01" id="mlbBctMedC${i}Largo"></div>
            <div class="mlb-form-campo"><label>Ancho</label><input type="number" step="0.01" id="mlbBctMedC${i}Ancho"></div>
            <div class="mlb-form-campo"><label>Alto</label><input type="number" step="0.01" id="mlbBctMedC${i}Alto"></div>
            <div class="mlb-form-campo"><label>Tipo de onda</label><input type="text" id="mlbBctMedC${i}TipoOnda"></div>
          </div>
          <div class="mlb-form-row">
            <div class="mlb-form-campo"><label>Gramaje complejo</label><input type="number" step="0.01" id="mlbBctMedC${i}Gramaje"></div>
            <div class="mlb-form-campo"><label>Espesor complejo</label><input type="number" step="0.0001" id="mlbBctMedC${i}Espesor"></div>
            <div class="mlb-form-campo"><label>Resultado (lbf)</label><input type="number" step="0.0001" id="mlbBctMedC${i}Resultado"></div>
          </div>
        `
      }
      bloque.innerHTML = html
    }

    async guardarBctMedido() {
      if (!this._muestraActualId) return

      const cajasEnsayadas = parseInt(document.getElementById("mlbBctMedCajas").value, 10)
      const num = (id) => {
        const el = document.getElementById(id)
        if (!el || el.value === "") return null
        return parseFloat(el.value)
      }
      const txt = (id) => document.getElementById(id)?.value?.trim() || null

      const caja = (i) => ({
        largo: num(`mlbBctMedC${i}Largo`),
        ancho: num(`mlbBctMedC${i}Ancho`),
        alto: num(`mlbBctMedC${i}Alto`),
        tipoOnda: txt(`mlbBctMedC${i}TipoOnda`),
        gramajeComplejo: num(`mlbBctMedC${i}Gramaje`),
        espesorComplejo: num(`mlbBctMedC${i}Espesor`),
        resultadoLbf: num(`mlbBctMedC${i}Resultado`)
      })

      const data = {
        muestraId: this._muestraActualId,
        cajasEnsayadas,
        motivoMenos3: document.getElementById("mlbBctMedMotivo").value.trim(),
        metodo: document.getElementById("mlbBctMedMetodo").value.trim(),
        observacion: document.getElementById("mlbBctMedObservacion").value.trim(),
        c1: caja(1),
        c2: cajasEnsayadas >= 2 ? caja(2) : null,
        c3: cajasEnsayadas >= 3 ? caja(3) : null
      }
      if (!this._aplicarDatosCorreccion(data)) return

      try {
        const res = await window.PhotinoBridge.send({ action: "muestraLab.bctMedido.guardar", data })
        if (!res || res.ok === false) throw new Error(res?.error || "Error guardando BCT Medido")

        this.cerrarModal("mlbModalBctMedido")
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

      const selEct = document.getElementById("mlbBctTeoEct")
      const selEsp = document.getElementById("mlbBctTeoEspesor")

      if (ects.length === 0 || espesores.length === 0) {
        alert("Necesitas al menos un ECT y un Espesor finalizados en esta muestra antes de calcular el BCT teórico.")
        return
      }

      selEct.innerHTML = ects.map(e => `<option value="${e.id}">Ensayo #${e.id} - ${e.resultadoValor} ${e.resultadoUnidad} (${e.fecha})</option>`).join("")
      selEsp.innerHTML = espesores.map(e => `<option value="${e.id}">Ensayo #${e.id} - ${e.resultadoValor} ${e.resultadoUnidad} (${e.fecha})</option>`).join("")

      document.getElementById("mlbBctTeoLargo").value = ""
      document.getElementById("mlbBctTeoAncho").value = ""
      document.getElementById("mlbBctTeoObservacion").value = ""

      this.abrirModal("mlbModalBctTeorico")
    }

    async guardarBctTeorico() {
      if (!this._muestraActualId) return

      const data = {
        muestraId: this._muestraActualId,
        ectEnsayoId: parseInt(document.getElementById("mlbBctTeoEct").value, 10),
        espesorEnsayoId: parseInt(document.getElementById("mlbBctTeoEspesor").value, 10),
        largoMm: parseFloat(document.getElementById("mlbBctTeoLargo").value),
        anchoMm: parseFloat(document.getElementById("mlbBctTeoAncho").value),
        observacion: document.getElementById("mlbBctTeoObservacion").value.trim()
      }

      if (!data.largoMm || !data.anchoMm) {
        alert("Largo y ancho interno son obligatorios")
        return
      }
      if (!this._aplicarDatosCorreccion(data)) return

      try {
        const res = await window.PhotinoBridge.send({ action: "muestraLab.bctTeorico.guardar", data })
        if (!res || res.ok === false) throw new Error(res?.error || "Error calculando BCT teórico")

        this.cerrarModal("mlbModalBctTeorico")
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
        observacion: document.getElementById("mlbViscObservacion").value.trim(),
        tipoAdhesivo: document.getElementById("mlbViscTipoAdhesivo").value.trim(),
        temperatura: num("mlbViscTemperatura"),
        equipo: document.getElementById("mlbViscEquipo").value.trim(),
        husillo: document.getElementById("mlbViscHusillo").value.trim(),
        velocidadRpm: num("mlbViscRpm"),
        resultadoCp: num("mlbViscResultado")
      }
      if (!this._aplicarDatosCorreccion(data)) return

      try {
        const res = await window.PhotinoBridge.send({ action: "muestraLab.viscosidad.guardar", data })
        if (!res || res.ok === false) throw new Error(res?.error || "Error guardando Viscosidad")

        this.cerrarModal("mlbModalViscosidad")
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

      const valorTexto = document.getElementById("mlbPhValor").value.trim()
      if (!valorTexto) {
        alert("Ingresa el valor o rango leído")
        return
      }

      const data = {
        muestraId: this._muestraActualId,
        observacion: document.getElementById("mlbPhObservacion").value.trim(),
        valorTexto,
        colorObservado: document.getElementById("mlbPhColor").value.trim()
      }
      if (!this._aplicarDatosCorreccion(data)) return

      try {
        const res = await window.PhotinoBridge.send({ action: "muestraLab.ph.guardar", data })
        if (!res || res.ok === false) throw new Error(res?.error || "Error guardando pH")

        this.cerrarModal("mlbModalPh")
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
      const det = (n) => ({ m1: num(`mlbSol${n}M1`), m2: num(`mlbSol${n}M2`), m3: num(`mlbSol${n}M3`) })

      const data = {
        muestraId: this._muestraActualId,
        observacion: document.getElementById("mlbSolObservacion").value.trim(),
        d1: det(1),
        d2: det(2),
        d3: det(3)
      }
      if (!this._aplicarDatosCorreccion(data)) return

      try {
        const res = await window.PhotinoBridge.send({ action: "muestraLab.solidos.guardar", data })
        if (!res || res.ok === false) throw new Error(res?.error || "Error guardando Sólidos totales")

        this.cerrarModal("mlbModalSolidos")
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
        observacion: document.getElementById("mlbLugolObservacion").value.trim(),
        puntoMuestra: document.getElementById("mlbLugolPunto").value.trim(),
        coloracion: document.getElementById("mlbLugolColoracion").value.trim(),
        resultado: document.getElementById("mlbLugolResultado").value,
        interpretacion: document.getElementById("mlbLugolInterpretacion").value.trim(),
        cumplimiento: document.getElementById("mlbLugolCumplimiento").value
      }
      if (!this._aplicarDatosCorreccion(data)) return

      try {
        const res = await window.PhotinoBridge.send({ action: "muestraLab.lugol.guardar", data })
        if (!res || res.ok === false) throw new Error(res?.error || "Error guardando Lugol")

        this.cerrarModal("mlbModalLugol")
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
          action: "muestraLab.ensayo.anular",
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
      console.log("DESTROY MUESTRA LABORATORIO")
      if (this._clickHandler) {
        document.removeEventListener("click", this._clickHandler)
        this._clickHandler = null
      }
      if (this._changeHandler) {
        document.removeEventListener("change", this._changeHandler)
        this._changeHandler = null
      }
      (this._statsCharts || []).forEach(c => c.destroy())
      this._statsCharts = []
    }

    // =====================================================================
    // INDICADORES (KPIs + gráficos) — reemplaza el resumen del módulo "Laboratorio" (app móvil)
    // eliminado. Fuente propia de este módulo (muestra_laboratorio/muestra_laboratorio_ensayos),
    // histórico completo sin filtros propios por ahora.
    // =====================================================================
    async cargarIndicadores() {
      try {
        const res = await window.PhotinoBridge.send({ action: "muestraLab.indicadores" })
        if (!res || res.ok === false) throw new Error(res?.error || "Error cargando indicadores")

        const ind = res.data || {}
        document.getElementById("mlbKpiTotalMuestras").textContent = ind.totalMuestras ?? 0
        document.getElementById("mlbKpiPendientes").textContent = ind.muestrasPendientes ?? 0
        document.getElementById("mlbKpiEnsayosFinalizados").textContent = ind.ensayosFinalizados ?? 0
        document.getElementById("mlbKpiPctCumplimiento").textContent =
          ind.pctCumplimiento === null || ind.pctCumplimiento === undefined ? "-" : `${ind.pctCumplimiento}%`

        this._renderChartBarras("mlbChartPorTipoEnsayo", ind.porTipoEnsayo || [], "Ensayos", { scroll: true })
        this._renderChartBarras("mlbChartPorOrigen", ind.porOrigen || [], "Muestras", { scroll: true })
        this._renderChartDoughnut("mlbChartCumplimiento", ind.porCumplimiento || [])
      } catch (err) {
        console.error("Error cargando indicadores de Laboratorio - Muestras:", err)
      }
    }

    // Barra horizontal, mismo patrón ya usado en No Conformidades: todas las categorías reales
    // (sin agrupar en "Otros"), alto dinámico + scroll interno para no romper el layout cuando
    // hay muchas (ej. hasta 13 tipos de ensayo), paleta cíclica para que cualquier cantidad de
    // barras tenga siempre un color real.
    _renderChartBarras(canvasId, rows, label, opts = {}) {
      const ctx = document.getElementById(canvasId)
      if (!ctx) return

      this._statsCharts = this._statsCharts || []
      const existente = this._statsCharts.find(c => c.canvas.id === canvasId)
      if (existente) { existente.destroy(); this._statsCharts = this._statsCharts.filter(c => c !== existente) }

      if (opts.scroll) {
        const alto = Math.max(180, rows.length * 26)
        ctx.style.setProperty("height", `${alto}px`, "important")
      }

      const paleta = ["#ef4444", "#f97316", "#eab308", "#22c55e", "#16a34a", "#3b82f6", "#6366f1", "#a855f7", "#ec4899", "#14b8a6"]

      const chart = new Chart(ctx, {
        type: "bar",
        data: {
          labels: rows.map(r => r.categoria || "-"),
          datasets: [{
            label,
            data: rows.map(r => Number(r.total || 0)),
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
      })
      this._statsCharts.push(chart)
    }

    _renderChartDoughnut(canvasId, rows) {
      const ctx = document.getElementById(canvasId)
      if (!ctx) return

      this._statsCharts = this._statsCharts || []
      const existente = this._statsCharts.find(c => c.canvas.id === canvasId)
      if (existente) { existente.destroy(); this._statsCharts = this._statsCharts.filter(c => c !== existente) }

      const colores = { "Cumple": "#22c55e", "No cumple": "#ef4444", "Sin especificacion": "#94a3b8", "Sin especificación": "#94a3b8" }
      const paleta = ["#3b82f6", "#6366f1", "#a855f7", "#ec4899", "#14b8a6"]

      const chart = new Chart(ctx, {
        type: "doughnut",
        data: {
          labels: rows.map(r => r.categoria || "-"),
          datasets: [{
            data: rows.map(r => Number(r.total || 0)),
            backgroundColor: rows.map((r, i) => colores[r.categoria] || paleta[i % paleta.length]),
            borderWidth: 0,
          }],
        },
        options: {
          responsive: true,
          maintainAspectRatio: false,
          plugins: { legend: { position: "bottom", labels: { font: { size: 11 } } } },
          cutout: "62%",
        },
      })
      this._statsCharts.push(chart)
    }
  }

  window.MuestraLaboratorioController = MuestraLaboratorioController
}
