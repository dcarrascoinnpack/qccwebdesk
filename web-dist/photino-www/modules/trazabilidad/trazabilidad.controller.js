if (!window.TrazabilidadController) {
  class TrazabilidadController {
    constructor() {
      this._clickHandler = null
      this._keyHandler = null
    }

    async init() {
      console.log("INIT TRAZABILIDAD")
      this.bindEvents()
    }

    bindEvents() {
      if (this._clickHandler) return

      this._clickHandler = (e) => {
        if (e.target.id === "btnTrzConsultar") {
          this.consultar()
        }
        if (e.target.id === "btnTrzImprimir") {
          this.imprimirReporte()
        }
      }
      document.addEventListener("click", this._clickHandler)

      this._keyHandler = (e) => {
        if (e.key === "Enter" && document.activeElement?.id === "trzNp") {
          this.consultar()
        }
      }
      document.addEventListener("keydown", this._keyHandler)
    }

    async consultar() {
      const np = document.getElementById("trzNp")?.value?.trim()
      if (!np) {
        alert("Ingresa un NP")
        return
      }

      const estadoEl = document.getElementById("trzEstadoConexion")
      const resultadoEl = document.getElementById("trzResultado")
      estadoEl.style.display = "none"
      resultadoEl.style.display = "none"

      try {
        const res = await window.PhotinoBridge.send({
          action: "trazabilidad.consultarNp",
          data: { np }
        })

        if (!res || res.ok === false) {
          throw new Error(res?.error || "Error consultando la NP")
        }

        const { procesos, paletizado, avisoPlanificacion } = res.data

        if (avisoPlanificacion) {
          estadoEl.textContent = avisoPlanificacion
          estadoEl.style.display = "block"
        }

        this._ultimaConsulta = { np, procesos, paletizado }

        this.renderResumen(procesos)
        this.renderProcesos(procesos)
        this.renderPaletizado(paletizado)

        resultadoEl.style.display = "block"
      } catch (err) {
        console.error("Error consultando trazabilidad:", err)
        estadoEl.textContent = err.message || "Error consultando la NP"
        estadoEl.style.display = "block"
      }
    }

    renderResumen(procesos) {
      const cliente = procesos[0]?.cli || "-"
      const producto = procesos[0]?.itemName || "-"
      const cantidad = procesos[0]?.cant ?? "-"
      const planificados = procesos.filter(p => p.est === "Planificada").length
      const pendientes = procesos.filter(p => p.est === "No Planificado").length

      document.getElementById("trzCliente").textContent = cliente
      document.getElementById("trzProducto").textContent = producto
      document.getElementById("trzCantidad").textContent = cantidad
      document.getElementById("trzPlanificados").textContent = planificados
      document.getElementById("trzPendientes").textContent = pendientes
    }

    renderProcesos(procesos) {
      const body = document.getElementById("trzProcesosBody")

      if (!procesos || procesos.length === 0) {
        body.innerHTML = '<tr><td colspan="8" style="text-align:center;">Sin procesos encontrados en Planificación FARET para este NP</td></tr>'
        return
      }

      body.innerHTML = procesos.map(p => `
        <tr>
          <td>${p.proc || "-"}</td>
          <td>${p.sec || "-"}</td>
          <td>${p.rec || "-"}</td>
          <td>${p.cant ?? "-"}</td>
          <td>${p.cantProd ?? "-"}</td>
          <td>${p.est || "-"}</td>
          <td>${p.ent || "-"}</td>
          <td>${this.renderMateriales(p.materiales)}</td>
        </tr>
      `).join("")
    }

    renderMateriales(materiales) {
      if (!materiales || materiales.length === 0) return "-"
      return materiales.map(m => `${m.itemName || m.itemCode}`).join("<br>")
    }

    renderPaletizado(paletizado) {
      const body = document.getElementById("trzPaletizadoBody")

      if (!paletizado || paletizado.length === 0) {
        body.innerHTML = '<tr><td colspan="7" style="text-align:center;">Sin registros de paletizado para este NP</td></tr>'
        return
      }

      body.innerHTML = paletizado.map(p => `
        <tr>
          <td>${p.idPalet || "-"}</td>
          <td>${p.fecha ? new Date(p.fecha).toLocaleString("es-CL") : "-"}</td>
          <td>${p.planta || "-"}</td>
          <td>${p.taller || "-"}</td>
          <td>${p.tipo || "-"}</td>
          <td>${p.cantidad ?? "-"}</td>
          <td>${p.descripcion || "-"}</td>
        </tr>
      `).join("")
    }

    // Reutiliza PrintExporter.printReport (mismo mecanismo ya usado en Laboratorio/No
    // Conformidades: iframe oculto + diálogo de impresión del navegador, sin librería de PDF).
    // Trae exactamente los mismos datos ya cargados en pantalla, sin volver a consultar nada.
    imprimirReporte() {
      const consulta = this._ultimaConsulta
      if (!consulta) {
        alert("Consulta un NP primero")
        return
      }

      const { np, procesos, paletizado } = consulta

      const planificados = procesos.filter(p => p.est === "Planificada").length
      const pendientes = procesos.filter(p => p.est === "No Planificado").length

      window.PrintExporter.printReport({
        empresa: "INNPACK",
        titulo: `Trazabilidad NP ${np}`,
        totalRegistros: procesos.length,
        resumen: [
          { label: "Cliente", valor: procesos[0]?.cli || "-" },
          { label: "Producto", valor: procesos[0]?.itemName || "-" },
          { label: "Cantidad NP", valor: procesos[0]?.cant ?? "-" },
          { label: "Procesos planificados", valor: planificados },
          { label: "Procesos pendientes", valor: pendientes },
        ],
        tablas: [
          {
            titulo: "Trazabilidad de Planificación (procesos)",
            columnas: ["Proceso", "Sección", "Máquina", "Cantidad", "Cant. Producida", "Estado", "Fecha Entrega", "Materiales"],
            filas: procesos.length
              ? procesos.map(p => [
                  p.proc || "-",
                  p.sec || "-",
                  p.rec || "-",
                  p.cant ?? "-",
                  p.cantProd ?? "-",
                  p.est || "-",
                  p.ent || "-",
                  this.renderMateriales(p.materiales),
                ])
              : [["Sin procesos encontrados en Planificación FARET para este NP", "", "", "", "", "", "", ""]],
          },
          {
            titulo: "Terminaciones y Paletizado",
            columnas: ["Código Paletizado", "Fecha", "Planta", "Taller", "Tipo", "Cantidad", "Descripción"],
            filas: paletizado.length
              ? paletizado.map(p => [
                  p.idPalet || "-",
                  p.fecha ? new Date(p.fecha).toLocaleString("es-CL") : "-",
                  p.planta || "-",
                  p.taller || "-",
                  p.tipo || "-",
                  p.cantidad ?? "-",
                  p.descripcion || "-",
                ])
              : [["Sin registros de paletizado para este NP", "", "", "", "", "", ""]],
          },
        ],
      })
    }

    destroy() {
      console.log("DESTROY TRAZABILIDAD")

      if (this._clickHandler) {
        document.removeEventListener("click", this._clickHandler)
        this._clickHandler = null
      }
      if (this._keyHandler) {
        document.removeEventListener("keydown", this._keyHandler)
        this._keyHandler = null
      }
    }
  }

  window.TrazabilidadController = TrazabilidadController
}
