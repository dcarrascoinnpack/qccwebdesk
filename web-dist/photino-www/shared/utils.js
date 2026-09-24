window.TableUtils = (function () {
    // Marca/fija filas de una tabla y muestra una franja con las filas marcadas, persistente aunque
    // la fila deje de estar en el tbody actual (cambio de página/filtro/recarga).
    // Requiere que cada <tr> del <tbody> tenga data-id="<id del registro>".
    function init(tabla, franjaEl, opciones) {
        opciones = opciones || {};
        const obtenerId = opciones.obtenerId || (tr => tr.dataset.id);
        const seleccionados = new Map(); // id -> html de las celdas de datos (sin la celda de checkbox)

        if (franjaEl) {
            franjaEl.classList.add("tu-franja-fijas");
        }

        const theadRow = tabla.querySelector("thead tr");
        if (theadRow && !theadRow.querySelector(".tu-th-check")) {
            const th = document.createElement("th");
            th.className = "tu-th-check";
            theadRow.insertBefore(th, theadRow.firstChild);
        }

        function capturarFilaHtml(tr) {
            const clon = tr.cloneNode(true);
            const celdaCheck = clon.querySelector(".tu-td-check");
            if (celdaCheck) celdaCheck.remove();
            return clon.innerHTML;
        }

        function toggle(id, tr) {
            id = String(id);
            if (seleccionados.has(id)) {
                seleccionados.delete(id);
            } else {
                seleccionados.set(id, tr ? capturarFilaHtml(tr) : "");
            }
            refrescar();
        }

        function renderFranja() {
            if (!franjaEl) return;

            if (seleccionados.size === 0) {
                franjaEl.innerHTML = "";
                franjaEl.style.display = "none";
                return;
            }

            franjaEl.style.display = "";

            const filas = [];
            seleccionados.forEach((html, id) => {
                filas.push(`
                    <tr data-id="${id}" class="row-selected">
                        <td class="tu-td-check"><button type="button" class="tu-franja-unpin" title="Quitar de fijadas">✕</button></td>
                        ${html}
                    </tr>`);
            });

            franjaEl.innerHTML = `
                <div class="tu-franja-header">
                    <span>📌 Filas marcadas (${seleccionados.size})</span>
                    <button type="button" class="tu-franja-limpiar">Quitar todas</button>
                </div>
                <div class="table-container tu-franja-tabla-container">
                    <table class="table">
                        <tbody>${filas.join("")}</tbody>
                    </table>
                </div>`;

            franjaEl.querySelectorAll("tbody tr[data-id]").forEach(tr => {
                const id = tr.dataset.id;
                const btn = tr.querySelector(".tu-franja-unpin");
                if (btn) btn.addEventListener("click", () => toggle(id));
            });

            const btnLimpiar = franjaEl.querySelector(".tu-franja-limpiar");
            if (btnLimpiar) {
                btnLimpiar.addEventListener("click", () => {
                    seleccionados.clear();
                    refrescar();
                });
            }
        }

        function refrescar() {
            const filas = tabla.querySelectorAll("tbody tr[data-id]");

            filas.forEach(tr => {
                const id = String(obtenerId(tr));

                if (!tr.querySelector(".tu-td-check")) {
                    const td = document.createElement("td");
                    td.className = "tu-td-check";

                    const chk = document.createElement("input");
                    chk.type = "checkbox";
                    chk.className = "tu-checkbox";
                    chk.addEventListener("click", (ev) => {
                        ev.stopPropagation();
                        toggle(id, tr);
                    });

                    td.appendChild(chk);
                    tr.insertBefore(td, tr.firstChild);
                }

                const marcada = seleccionados.has(id);
                const chk = tr.querySelector(".tu-checkbox");
                if (chk) chk.checked = marcada;
                tr.classList.toggle("row-selected", marcada);

                if (marcada) {
                    seleccionados.set(id, capturarFilaHtml(tr));
                }
            });

            renderFranja();
        }

        refrescar();

        return {
            refrescar,
            obtenerSeleccionados: () => Array.from(seleccionados.keys()),
            limpiar: () => {
                seleccionados.clear();
                refrescar();
            },
        };
    }

    // Guarda el scroll de un contenedor, ejecuta fnRecargarAsync() y lo restaura al terminar.
    function preservarScroll(contenedor, fnRecargarAsync) {
        const el = typeof contenedor === "string" ? document.querySelector(contenedor) : contenedor;
        const scrollTop = el ? el.scrollTop : 0;

        const restaurar = () => {
            if (el) el.scrollTop = scrollTop;
        };

        const resultado = fnRecargarAsync();

        if (resultado && typeof resultado.then === "function") {
            return resultado.then((r) => {
                restaurar();
                return r;
            });
        }

        restaurar();
        return resultado;
    }

    // ---------- Bobinas: separar "A; B; C" en pares código/descripción/lote ----------
    // bobinaCodigo/bobinaDescripcion/bobinaLote vienen del mismo GROUP_CONCAT con el mismo
    // ORDER BY en el backend, así que la posición N de cada string corresponde a la misma bobina
    // en los 3 campos. Se empareja por índice (no se filtran vacíos por separado) para no
    // desalinear los arreglos si algún campo puntual viniera vacío para una bobina.
    function parsearListaBobinas(valor) {
        const texto = String(valor || "").trim();
        if (!texto) return [];
        return texto.split(";").map(v => v.trim());
    }

    function emparejarBobinas(codigo, descripcion, lote, observacion) {
        const codigos = parsearListaBobinas(codigo);
        const descripciones = parsearListaBobinas(descripcion);
        const lotes = parsearListaBobinas(lote);
        const observaciones = parsearListaBobinas(observacion);
        const total = Math.max(codigos.length, descripciones.length, lotes.length, observaciones.length);

        const pares = [];
        for (let i = 0; i < total; i++) {
            pares.push({
                codigo: codigos[i] || "",
                descripcion: descripciones[i] || "",
                lote: lotes[i] || "",
                observacion: observaciones[i] || "",
            });
        }
        return pares;
    }

    // Resumen listo para renderizar: primera bobina + cuántas quedan + el listado completo.
    function resumenBobinas(codigo, descripcion, lote, observacion) {
        const pares = emparejarBobinas(codigo, descripcion, lote, observacion);
        return {
            pares,
            cantidad: pares.length,
            primera: pares[0] || null,
            restantes: Math.max(0, pares.length - 1),
        };
    }

    // ---------- Popover compacto anclado a un elemento ----------
    // Vive en document.body (no dentro de la tabla) para no quedar cortado por el overflow de
    // .table-container. Solo una instancia abierta a la vez en toda la app (compartida entre
    // módulos): abrirPopover() siempre cierra cualquier popover previo antes de crear el nuevo,
    // así nunca quedan listeners globales acumulados.
    let popoverActivo = null;

    function cerrarPopover() {
        if (!popoverActivo) return;
        popoverActivo.limpiar();
        popoverActivo.el.remove();
        popoverActivo = null;
    }

    function posicionarPopover(el, trigger) {
        const margen = 8;
        const rectTrigger = trigger.getBoundingClientRect();
        const rectEl = el.getBoundingClientRect();

        let left = rectTrigger.left;
        let top = rectTrigger.bottom + margen;

        if (left + rectEl.width > window.innerWidth - margen) {
            left = window.innerWidth - rectEl.width - margen;
        }
        if (left < margen) left = margen;

        if (top + rectEl.height > window.innerHeight - margen) {
            const arriba = rectTrigger.top - rectEl.height - margen;
            top = arriba > margen ? arriba : margen;
        }

        el.style.top = `${top}px`;
        el.style.left = `${left}px`;
    }

    // `html` debe venir ya escapado por quien llama — TableUtils no conoce el origen de los datos.
    function abrirPopover(trigger, html) {
        cerrarPopover();

        const el = document.createElement("div");
        el.className = "tu-popover";
        el.setAttribute("role", "dialog");
        el.style.cssText = `
            position:fixed; z-index:9999; background:#ffffff; border:1px solid #E2E8F0;
            border-radius:10px; box-shadow:0 20px 60px rgba(0,0,0,0.25);
            max-width:420px; max-height:60vh; overflow:auto; font-size:13px; color:#0F172A;
        `;
        el.innerHTML = html;
        document.body.appendChild(el);
        posicionarPopover(el, trigger);

        const onMouseDown = (e) => {
            if (el.contains(e.target) || e.target === trigger || trigger.contains(e.target)) return;
            cerrarPopover();
        };
        const onKeyDown = (e) => {
            if (e.key === "Escape") cerrarPopover();
        };
        const onReposicionar = () => posicionarPopover(el, trigger);

        document.addEventListener("mousedown", onMouseDown, true);
        document.addEventListener("keydown", onKeyDown, true);
        window.addEventListener("resize", onReposicionar);
        window.addEventListener("scroll", onReposicionar, true);

        popoverActivo = {
            el,
            trigger,
            limpiar: () => {
                document.removeEventListener("mousedown", onMouseDown, true);
                document.removeEventListener("keydown", onKeyDown, true);
                window.removeEventListener("resize", onReposicionar);
                window.removeEventListener("scroll", onReposicionar, true);
            },
        };

        return el;
    }

    return { init, preservarScroll, resumenBobinas, abrirPopover, cerrarPopover };
})();

// ---------- Combo "seleccionar + buscar + crear" para campos tipo catálogo ----------
// Genérico: cualquier módulo puede engancharlo a un <input type="text"> + un <div> hermano.
// Los ítems que genera usan clases neutras .catalog-combo-* (sin prefijo de módulo) — cada
// módulo define esas 4 reglas en su propio CSS (item/nombre/crear/empty) reutilizando el look
// que ya tenía su combo de Responsable; el contenedor <div> del dropdown en sí sigue llevando
// la clase de posicionamiento que cada módulo ya tenía (ej. .fnc-combo-dropdown en Faret,
// .ncq-combo-dropdown en INNPACK) — eso lo decide el caller, no este archivo. No asume nada del
// backend — recibe funciones obtenerOpciones()/crear() y solo orquesta cache, filtrado y el
// afordance "+ Crear...". El caller decide de dónde vienen los datos (catálogo propio,
// catálogo jerárquico con áreaId, etc.).
window.CatalogCombo = (function () {
    const cache = new Map(); // cacheKey -> Array<{id, nombre, activo}>

    async function obtener(cacheKey, fetcher, forzar) {
        if (!forzar && cache.has(cacheKey)) return cache.get(cacheKey);
        let items = [];
        try {
            items = await fetcher();
        } catch {
            items = [];
        }
        cache.set(cacheKey, items);
        return items;
    }

    function invalidar(cacheKey) {
        cache.delete(cacheKey);
    }

    // opciones:
    //   cacheKey: string único (puede depender de un padre, ej. `maquina:${areaId}`)
    //   obtenerOpciones: async () => [{id, nombre, activo}]
    //   crear: async (nombreNormalizado) => {id, nombre, activo} | null  — si no viene (o es
    //     null), no se ofrece "+ Crear" (campo de solo lectura o sin contexto todavía, ej. sin
    //     Área elegida)
    //   onSeleccionar: (item|null) => void — llamado al elegir una opción existente o al crear una nueva
    //   bloqueadoMsg: () => string|null — si devuelve texto, se muestra en vez de la lista y no
    //     se permite crear (ej. "Seleccione un Área primero")
    //
    // Idempotente: puede llamarse varias veces sobre el mismo <input> (ej. cada vez que se abre
    // el modal, o cada vez que cambia el Área y hay que re-scopear Máquina/Operador) — los
    // listeners del DOM (focus/input/blur) se registran una sola vez por input; llamadas
    // posteriores solo reemplazan `opciones` y limpian la cache local de items para forzar un
    // refetch con el nuevo scope. Sin este resguardo, cada llamada apilaría listeners nuevos.
    function attach(input, dropdown, opciones) {
        if (!input || !dropdown) return null;

        if (input._catalogCombo) {
            input._catalogCombo.opciones = opciones;
            input._catalogCombo.items = [];
            return input._catalogCombo.handle;
        }

        // Se reparenta a document.body con position:fixed, con coordenadas calculadas desde el
        // <input> (mismo problema y misma solución ya usada en este archivo por
        // TableUtils.abrirPopover/posicionarPopover): si el dropdown se queda como hijo
        // posicionado con position:absolute dentro de un formulario, cualquier <input> cerca del
        // borde de un contenedor con overflow:auto (ej. .fnc-modal, max-height:85vh) hace que el
        // navegador recorte el dropdown — el usuario ve las opciones cortadas o directamente no
        // las ve. position:fixed + coordenadas en document.body no tiene ese problema.
        document.body.appendChild(dropdown);
        dropdown.style.position = "fixed";
        dropdown.style.zIndex = "10000";
        dropdown.style.left = "0";
        dropdown.style.right = "auto";

        const estado = { opciones, items: [] };
        input._catalogCombo = estado;

        // Por defecto se abre debajo del input; si no entra (input cerca del borde inferior de
        // la ventana) se abre hacia arriba — mismo criterio que posicionarPopover.
        function posicionar() {
            const margen = 4;
            const rect = input.getBoundingClientRect();
            const altura = Math.min(dropdown.scrollHeight || 200, 280);

            dropdown.style.left = `${Math.max(margen, rect.left)}px`;
            dropdown.style.width = `${rect.width}px`;

            const espacioAbajo = window.innerHeight - rect.bottom;
            if (espacioAbajo < altura + margen && rect.top > altura + margen) {
                dropdown.style.bottom = `${window.innerHeight - rect.top + margen}px`;
                dropdown.style.top = "auto";
            } else {
                dropdown.style.top = `${rect.bottom + margen}px`;
                dropdown.style.bottom = "auto";
            }
        }

        async function cargar(forzar) {
            estado.items = await obtener(estado.opciones.cacheKey, estado.opciones.obtenerOpciones, forzar);
        }

        function abrir() {
            posicionar();
            dropdown.style.display = "block";
            window.addEventListener("scroll", posicionar, true);
            window.addEventListener("resize", posicionar);
        }

        function cerrar() {
            dropdown.style.display = "none";
            window.removeEventListener("scroll", posicionar, true);
            window.removeEventListener("resize", posicionar);
        }

        function render() {
            if (input.disabled) return;

            const bloqueado = estado.opciones.bloqueadoMsg?.();
            if (bloqueado) {
                dropdown.innerHTML = `<div class="catalog-combo-empty"></div>`;
                dropdown.querySelector(".catalog-combo-empty").textContent = bloqueado;
                abrir();
                return;
            }

            const texto = input.value.trim();
            const filtro = texto.toLowerCase();
            const activos = estado.items.filter(i => i.activo !== false);
            const coincidencias = activos
                .filter(i => !filtro || i.nombre.toLowerCase().includes(filtro))
                .slice(0, 50);
            const hayExacto = activos.some(i => i.nombre.toLowerCase() === filtro);

            const puedeCrear = !!estado.opciones.crear && !!texto && !hayExacto;

            if (!coincidencias.length && !puedeCrear) {
                dropdown.innerHTML = `<div class="catalog-combo-empty">Sin coincidencias</div>`;
            } else {
                dropdown.innerHTML = "";
                coincidencias.forEach(item => {
                    const el = document.createElement("div");
                    el.className = "catalog-combo-item";
                    el.innerHTML = `<span class="catalog-combo-item-nombre"></span>`;
                    el.querySelector(".catalog-combo-item-nombre").textContent = item.nombre;
                    el.addEventListener("mousedown", e => {
                        e.preventDefault();
                        input.value = item.nombre;
                        input.dataset.catalogId = item.id;
                        cerrar();
                        estado.opciones.onSeleccionar?.(item);
                    });
                    dropdown.appendChild(el);
                });

                if (puedeCrear) {
                    const btn = document.createElement("div");
                    btn.className = "catalog-combo-item catalog-combo-item-crear";
                    btn.innerHTML = `<span></span>`;
                    btn.querySelector("span").textContent = `+ Crear "${texto}"`;
                    btn.addEventListener("mousedown", async e => {
                        e.preventDefault();
                        btn.querySelector("span").textContent = "Creando…";
                        try {
                            const nuevo = await estado.opciones.crear(texto);
                            if (nuevo) {
                                estado.items.push(nuevo);
                                input.value = nuevo.nombre;
                                input.dataset.catalogId = nuevo.id;
                                estado.opciones.onSeleccionar?.(nuevo);
                            }
                        } finally {
                            cerrar();
                        }
                    });
                    dropdown.appendChild(btn);
                }
            }

            abrir();
        }

        input.addEventListener("focus", async () => {
            if (input.disabled) return;
            if (!estado.items.length) await cargar(false);
            render();
        });
        input.addEventListener("input", () => {
            delete input.dataset.catalogId; // texto libre distinto a la última selección
            render();
        });
        input.addEventListener("blur", () => setTimeout(cerrar, 150));

        estado.handle = {
            recargar: async () => { await cargar(true); render(); },
        };
        return estado.handle;
    }

    return { attach, invalidar };
})();

// Fechas: el backend entrega fechas sin hora como texto plano "yyyy-MM-dd" (sin timezone). El
// constructor `new Date("yyyy-MM-dd")` las interpreta como UTC medianoche, y toLocaleDateString()
// las convierte a la hora local del navegador — en timezones negativos (Chile, UTC-3/-4) eso
// corre la fecha mostrada un día hacia atrás siempre, sin importar la hora del día. Estas
// funciones nunca pasan una fecha por el parser UTC de Date: formatear() la arma por texto, y
// hoyISO() lee los componentes locales en vez de toISOString() (que también es UTC).
window.DateUtils = (function () {
    function formatear(valor) {
        if (!valor) return "-";
        const texto = String(valor);
        const m = texto.match(/^(\d{4})-(\d{2})-(\d{2})/);
        if (!m) return texto;
        const [, anio, mes, dia] = m;
        return `${dia}-${mes}-${anio}`;
    }

    function hoyISO() {
        const d = new Date();
        const mes = String(d.getMonth() + 1).padStart(2, "0");
        const dia = String(d.getDate()).padStart(2, "0");
        return `${d.getFullYear()}-${mes}-${dia}`;
    }

    function mesActualISO() {
        const d = new Date();
        const mes = String(d.getMonth() + 1).padStart(2, "0");
        return `${d.getFullYear()}-${mes}`;
    }

    function primerDiaMesActualISO() {
        return `${mesActualISO()}-01`;
    }

    // Suma/resta días de calendario a una fecha "yyyy-MM-dd" sin pasar por conversión de
    // timezone (aritmética en UTC puro sobre los componentes, nunca en hora local).
    function sumarDias(fechaISO, dias) {
        const texto = String(fechaISO);
        const m = texto.match(/^(\d{4})-(\d{2})-(\d{2})/);
        if (!m) return texto;
        const [, anio, mes, dia] = m;
        const utc = new Date(Date.UTC(Number(anio), Number(mes) - 1, Number(dia)));
        utc.setUTCDate(utc.getUTCDate() + dias);
        const y = utc.getUTCFullYear();
        const mo = String(utc.getUTCMonth() + 1).padStart(2, "0");
        const d = String(utc.getUTCDate()).padStart(2, "0");
        return `${y}-${mo}-${d}`;
    }

    return { formatear, hoyISO, mesActualISO, primerDiaMesActualISO, sumarDias };
})();
