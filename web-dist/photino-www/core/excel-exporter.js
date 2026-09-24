window.ExcelExporter = {
    exportTable(options) {
        const {
            tableSelector,
            fileName,
            sheetName = "Datos",
            title = "Exportación QCC"
        } = options

        const table = document.querySelector(tableSelector)

        if (!table) {
            alert("No se encontró la tabla para exportar")
            return
        }

        const rows = Array.from(table.querySelectorAll("tr"))
            .filter(row => row.offsetParent !== null)

        if (!rows.length) {
            alert("No hay datos visibles para exportar")
            return
        }

        const data = rows.map(row =>
            Array.from(row.children)
                .filter(cell => !cell.classList.contains("tu-th-check") && !cell.classList.contains("tu-td-check"))
                .map(cell =>
                    String(cell.innerText || "")
                        .replace(/\s+/g, " ")
                        .trim()
                )
        )

        const now = new Date()
        const fecha = now.toLocaleString("es-CL")

        const aoa = [
            [title],
            [`Generado: ${fecha}`],
            [],
            ...data
        ]

        const wb = XLSX.utils.book_new()
        const ws = XLSX.utils.aoa_to_sheet(aoa)

        const maxCols = Math.max(...aoa.map(r => r.length))
        ws["!cols"] = Array.from({ length: maxCols }).map((_, colIndex) => {
            const maxLen = Math.max(
                ...aoa.map(row => String(row[colIndex] || "").length)
            )

            return { wch: Math.min(Math.max(maxLen + 2, 12), 38) }
        })

        ws["!freeze"] = { xSplit: 0, ySplit: 4 }

        const range = XLSX.utils.decode_range(ws["!ref"])

        for (let R = range.s.r; R <= range.e.r; R++) {
            for (let C = range.s.c; C <= range.e.c; C++) {
                const ref = XLSX.utils.encode_cell({ r: R, c: C })
                if (!ws[ref]) continue

                ws[ref].s = {
                    font: {
                        name: "Arial",
                        sz: R === 0 ? 16 : 11,
                        bold: R === 0 || R === 3,
                        color: { rgb: R === 3 ? "FFFFFF" : "111827" }
                    },
                    fill: {
                        fgColor: {
                            rgb: R === 0
                                ? "E8F0FE"
                                : R === 3
                                    ? "111827"
                                    : R % 2 === 0 && R > 3
                                        ? "F8FAFC"
                                        : "FFFFFF"
                        }
                    },
                    alignment: {
                        vertical: "center",
                        horizontal: R === 0 ? "center" : "left",
                        wrapText: true
                    },
                    border: {
                        top: { style: "thin", color: { rgb: "CBD5E1" } },
                        bottom: { style: "thin", color: { rgb: "CBD5E1" } },
                        left: { style: "thin", color: { rgb: "CBD5E1" } },
                        right: { style: "thin", color: { rgb: "CBD5E1" } }
                    }
                }
            }
        }

        ws["!merges"] = [
            {
                s: { r: 0, c: 0 },
                e: { r: 0, c: Math.max(maxCols - 1, 0) }
            }
        ]

        XLSX.utils.book_append_sheet(wb, ws, sheetName)

        const safeName = fileName || `qcc_export_${now.getTime()}.xlsx`

        const base64 = XLSX.write(wb, {
            bookType: "xlsx",
            type: "base64"
        })

        window.PhotinoBridge.send({
            action: "excel.guardar",
            data: {
                fileName: safeName,
                base64: base64
            }
        })
            .then((res) => {
                if (res?.ok) {
                    this.showToast("âœ… Archivo exportado correctamente")
                } else {
                    this.showToast("âŒ Error exportando archivo")
                }
            })
            .catch(() => {
                this.showToast("âŒ Error exportando archivo")
            })
    }

    ,

    showToast(message) {
        let toast = document.getElementById("excelExportToast")

        if (!toast) {
            toast = document.createElement("div")
            toast.id = "excelExportToast"

            toast.style.position = "fixed"
            toast.style.top = "50%"
            toast.style.left = "50%"
            toast.style.transform = "translate(-50%, -50%)"
            toast.style.background = "#111827"
            toast.style.color = "#ffffff"
            toast.style.padding = "18px 28px"
            toast.style.borderRadius = "14px"
            toast.style.fontSize = "15px"
            toast.style.fontWeight = "600"
            toast.style.boxShadow = "0 15px 40px rgba(0,0,0,0.25)"
            toast.style.zIndex = "999999"
            toast.style.opacity = "0"
            toast.style.transition = "all .25s ease"

            document.body.appendChild(toast)
        }

        toast.textContent = message
        toast.style.opacity = "1"

        clearTimeout(this._toastTimer)

        this._toastTimer = setTimeout(() => {
            toast.style.opacity = "0"
        }, 2400)
    }
}
