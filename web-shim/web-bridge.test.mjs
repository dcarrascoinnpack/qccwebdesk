// Tests de web-bridge.js (solo Node: node:test + vm, sin dependencias).
// Ejecutar desde la raíz del repo web:  node --test web-shim/
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";

const CODIGO = readFileSync(new URL("./web-bridge.js", import.meta.url), "utf8");
const XLSX_MINIMO = Buffer.from([0x50, 0x4b, 0x03, 0x04, 0x14, 0x00, 0x00, 0x00]).toString("base64");

/** Carga el shim en un "navegador" simulado y devuelve el contexto + registros de lo que hizo. */
function cargarShim() {
    const registro = { fetch: [], descargas: [], xhr: [] };

    class Storage {
        constructor() { this.datos = new Map(); }
        getItem(k) { return this.datos.has(k) ? this.datos.get(k) : null; }
        setItem(k, v) { this.datos.set(String(k), String(v)); }
        removeItem(k) { this.datos.delete(k); }
    }
    const localStorage = new Storage();
    const sessionStorage = new Storage();

    const document = {
        cookie: "XSRF-TOKEN=token-csrf",
        body: { appendChild() {} },
        addEventListener() {},
        createElement(tag) {
            const el = { tag, style: {}, remove() {} };
            el.click = () => registro.descargas.push({ nombre: el.download, href: el.href, rel: el.rel });
            return el;
        }
    };

    class XMLHttpRequest {
        open(metodo, url) { registro.xhr.push(`${metodo} ${url}`); }
        setRequestHeader() {}
        send() { this.status = 200; this.responseText = JSON.stringify({ ok: false, error: "No hay sesión activa" }); }
    }

    const blobs = [];
    const contexto = {
        window: null, document, localStorage, sessionStorage, XMLHttpRequest, Blob, Uint8Array, Object, JSON,
        Promise, String, Math, Date, setTimeout: () => 0, clearTimeout() {},
        AbortController,
        atob: (s) => { if (!/^[A-Za-z0-9+/]*={0,2}$/.test(s)) throw new Error("InvalidCharacterError"); return Buffer.from(s, "base64").toString("latin1"); },
        URL: { createObjectURL: (b) => { blobs.push(b); return "blob:qcc/" + blobs.length; }, revokeObjectURL() {} },
        fetch: (url, opciones) => {
            registro.fetch.push({ url, opciones });
            return Promise.resolve({ status: 200, ok: true, json: () => Promise.resolve({ ok: true, success: true, data: { via: "gateway" }, error: null }) });
        },
    };
    contexto.window = contexto;
    contexto.window.external = undefined;
    vm.createContext(contexto);
    vm.runInContext(CODIGO, contexto);
    return { ctx: contexto, registro, blobs, localStorage };
}

/** Objetos creados dentro del vm tienen otro prototipo: se comparan como JSON plano. */
const plano = (x) => JSON.parse(JSON.stringify(x));

async function guardar(shim, data) {
    return shim.ctx.PhotinoBridge.send({ action: "excel.guardar", data });
}

test("excel.guardar descarga en el navegador y nunca llama al gateway", async () => {
    const shim = cargarShim();
    const res = await guardar(shim, { fileName: "qcc_seguimiento_maquina_1.xlsx", base64: XLSX_MINIMO });

    assert.deepEqual(plano(res), { ok: true, success: true, data: { fileName: "qcc_seguimiento_maquina_1.xlsx" }, error: null });
    assert.equal(shim.registro.fetch.length, 0, "no debe haber llamadas de red");
    assert.equal(shim.registro.descargas.length, 1);
    assert.equal(shim.registro.descargas[0].nombre, "qcc_seguimiento_maquina_1.xlsx");
    assert.match(shim.registro.descargas[0].href, /^blob:/);
    assert.equal(shim.blobs[0].type, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    assert.equal(shim.blobs[0].size, 8);
    assert.deepEqual(plano(shim.ctx.QCC_WEB.accionesNavegador), ["excel.guardar"]);
});

test("nombres de archivo maliciosos se sanean", async () => {
    const casos = [
        ["..\\..\\Windows\\System32\\evil.exe", "evil.exe.xlsx"],
        ["../../etc/passwd", "passwd.xlsx"],
        ["a<b>:c|d?e*.xlsx", "a_b__c_d_e_.xlsx"],
        ["factura\u202exslx.exe", "facturaxslx.exe.xlsx"],
        ["linea\r\nnueva.xlsx", "linea__nueva.xlsx"],
        ["CON", "_CON.xlsx"],
        ["nul.xlsx", "_nul.xlsx"],
        ["Reporte Año Señal.XLSX", "Reporte Año Señal.XLSX"],
        ["  .oculto.xlsx  ", "oculto.xlsx"],
        ["x".repeat(400) + ".xlsx", "x".repeat(145) + ".xlsx"],
    ];
    for (const [entrada, esperado] of casos) {
        const shim = cargarShim();
        const res = await guardar(shim, { fileName: entrada, base64: XLSX_MINIMO });
        assert.equal(res.ok, true, entrada);
        assert.equal(shim.registro.descargas[0].nombre, esperado, JSON.stringify(entrada));
    }
    for (const vacio of ["", "   ", "../", ".xlsx", null, undefined]) {
        const shim = cargarShim();
        await guardar(shim, { fileName: vacio, base64: XLSX_MINIMO });
        assert.match(shim.registro.descargas[0].nombre, /^qcc_export_\d{8}_\d{6}\.xlsx$/, String(vacio));
    }
});

test("errores con los mismos mensajes que Photino y sin descarga", async () => {
    const casos = [
        [undefined, "Falta data para guardar Excel"],
        [{ fileName: "a.xlsx" }, "Excel vacío"],
        [{ fileName: "a.xlsx", base64: "   " }, "Excel vacío"],
        [{ fileName: "a.xlsx", base64: "@@no-es-base64@@" }, "Archivo Excel inválido."],
        [{ fileName: "a.xlsx", base64: Buffer.from("<html><script>alert(1)</script>").toString("base64") }, "Archivo Excel inválido."],
        [{ fileName: "a.xlsx", base64: Buffer.from("MZ\x90\x00ejecutable").toString("base64") }, "Archivo Excel inválido."],
    ];
    for (const [data, mensaje] of casos) {
        const shim = cargarShim();
        const res = await guardar(shim, data);
        assert.deepEqual(plano(res), { ok: false, success: false, data: null, error: mensaje });
        assert.equal(shim.registro.descargas.length, 0);
        assert.equal(shim.registro.fetch.length, 0);
    }
});

test("archivo sobre 50 MB se rechaza antes de decodificar", async () => {
    const shim = cargarShim();
    const enorme = "A".repeat(Math.ceil((50 * 1024 * 1024) / 3) * 4 + 4);
    const res = await guardar(shim, { fileName: "grande.xlsx", base64: enorme });
    assert.equal(res.error, "El archivo excede el tamaño máximo permitido.");
    assert.equal(shim.registro.descargas.length, 0);
});

test("las demás acciones siguen yendo al gateway con CSRF", async () => {
    const shim = cargarShim();
    const res = await shim.ctx.PhotinoBridge.send({ action: "maquinasSeguimiento.obtenerResumen", data: { maquinaId: 5 } });
    assert.equal(res.data.via, "gateway");
    assert.equal(shim.registro.fetch.length, 1);
    assert.equal(shim.registro.fetch[0].url, "api/v1/bridge");
    assert.equal(shim.registro.fetch[0].opciones.headers["X-XSRF-TOKEN"], "token-csrf");
});

test("un 403 del bridge muestra el aviso 'no disponible' sin bloquear", async () => {
    const shim = cargarShim();
    const elementos = [];
    shim.ctx.document.getElementById = () => null;
    shim.ctx.document.createElement = (tag) => { const el = { tag, style: {}, setAttribute() {}, remove() {}, click() {} }; elementos.push(el); return el; };
    shim.ctx.fetch = () => Promise.resolve({ status: 403, ok: false, json: () => Promise.resolve({ ok: false, success: false, data: null, error: "Acción no disponible en la versión web." }) });

    const res = await shim.ctx.PhotinoBridge.send({ action: "dashboard.validarTodo" });
    assert.equal(res.ok, false);
    assert.equal(res.error, "Acción no disponible en la versión web.");
    const aviso = elementos.find((e) => e.id === "qccWebAvisoNoDisponible");
    assert.ok(aviso, "debe crear el aviso");
    assert.equal(aviso.textContent, "Acción no disponible en la versión web.");
    assert.equal(aviso.style.opacity, "1");
});

test("localStorage sigue sin aceptar contraseñas, rol ni autoingreso", () => {
    const shim = cargarShim();
    for (const clave of ["lcc_password", "lcc_faret_password", "lcc_remember_login", "lcc_rolUsuario", "lcc_nombreUsuario"]) {
        shim.localStorage.setItem(clave, "x");
        assert.equal(shim.localStorage.getItem(clave), null, clave);
    }
    shim.localStorage.setItem("lcc_codigoUsuario", "operador1");
    assert.equal(shim.localStorage.getItem("lcc_codigoUsuario"), "operador1");
    assert.deepEqual(plano(shim.registro.xhr), ["GET api/v1/auth/session"]);
});

// ------------------------------------------------------------------ Fase 2g: PDF vía gateway → navegador
const PDF_MINIMO = Buffer.from("%PDF-1.4\n1 0 obj<</Type/Catalog>>endobj\ntrailer<</Root 1 0 R>>\n%%EOF\n", "latin1").toString("base64");
const ACCION_PDF = "certificadosLiberacion.calidadPdf.descargar";

/** Gateway simulado que responde ok con {fileName, base64} (ya validado en el servidor). */
function shimConPdf(data, cuerpo) {
    const shim = cargarShim();
    shim.ctx.fetch = (url, opciones) => {
        shim.registro.fetch.push({ url, opciones });
        return Promise.resolve({ status: 200, ok: true, json: () => Promise.resolve(cuerpo || { ok: true, success: true, data, error: null }) });
    };
    return shim;
}

test("calidadPdf.descargar pasa por el gateway (con CSRF) y descarga el PDF en el navegador", async () => {
    const shim = shimConPdf({ fileName: "CertificadoCalidad_123456.pdf", base64: PDF_MINIMO });
    const res = await shim.ctx.PhotinoBridge.send({ action: ACCION_PDF, data: { folio: 123456 } });

    assert.deepEqual(plano(res), { ok: true, success: true, data: { fileName: "CertificadoCalidad_123456.pdf" }, error: null });
    assert.equal(shim.registro.fetch.length, 1, "una sola llamada, al gateway");
    assert.equal(shim.registro.fetch[0].url, "api/v1/bridge");
    assert.equal(shim.registro.fetch[0].opciones.headers["X-XSRF-TOKEN"], "token-csrf");
    assert.equal(JSON.parse(shim.registro.fetch[0].opciones.body).data.folio, 123456);
    assert.equal(shim.registro.descargas.length, 1);
    assert.equal(shim.registro.descargas[0].nombre, "CertificadoCalidad_123456.pdf");
    assert.match(shim.registro.descargas[0].href, /^blob:/);
    assert.equal(shim.blobs[0].type, "application/pdf");
    assert.equal(shim.blobs[0].size, Buffer.from(PDF_MINIMO, "base64").length);
    // No es una acción "de navegador": el contract check la ve en la ActionPolicy del gateway.
    assert.deepEqual(plano(shim.ctx.QCC_WEB.accionesNavegador), ["excel.guardar"]);
    assert.deepEqual(plano(shim.ctx.QCC_WEB.accionesDescarga), [ACCION_PDF, "controlDocumental.adjunto.abrir"]);
});

test("un error del gateway en calidadPdf.descargar se devuelve tal cual, sin descarga", async () => {
    const shim = shimConPdf(null, { ok: false, success: false, data: null, error: "No se encontraron datos para el certificado N° 404" });
    const res = await shim.ctx.PhotinoBridge.send({ action: ACCION_PDF, data: { folio: 404 } });
    assert.equal(res.ok, false);
    assert.equal(res.error, "No se encontraron datos para el certificado N° 404");
    assert.equal(shim.registro.descargas.length, 0);
});

test("PDF vacío, corrupto, no-PDF o excesivo se rechaza limpiamente sin descarga", async () => {
    const casos = [
        [{ fileName: "x.pdf", base64: "" }, "El certificado no trae contenido"],
        [{ fileName: "x.pdf" }, "El certificado no trae contenido"],
        [null, "El certificado no trae contenido"],
        [{ fileName: "x.pdf", base64: "%%%no-base64%%%" }, "El certificado no es un PDF válido."],
        [{ fileName: "x.pdf", base64: Buffer.from("<html>no soy un pdf</html>").toString("base64") }, "El certificado no es un PDF válido."],
        [{ fileName: "x.pdf", base64: XLSX_MINIMO }, "El certificado no es un PDF válido."],
        [{ fileName: "x.pdf", base64: "A".repeat(Math.ceil(15 * 1024 * 1024 / 3) * 4 + 4) }, "El certificado excede el tamaño máximo permitido."],
    ];
    for (const [data, error] of casos) {
        const shim = shimConPdf(data);
        const res = await shim.ctx.PhotinoBridge.send({ action: ACCION_PDF, data: { folio: 1 } });
        assert.deepEqual(plano(res), { ok: false, success: false, data: null, error }, JSON.stringify(data)?.slice(0, 60));
        assert.equal(shim.registro.descargas.length, 0);
    }
});

test("nombres de PDF maliciosos se sanean y siempre terminan en .pdf", async () => {
    const casos = [
        ["..\\..\\Windows\\evil<>:\"|?*.exe", "evil_______.exe.pdf"],
        ["../../etc/passwd", "passwd.pdf"],
        ["CON.pdf", "_CON.pdf"],
        ["  .pdf ", /^certificado_\d{8}_\d{6}\.pdf$/],
        ["", /^certificado_\d{8}_\d{6}\.pdf$/],
        ["informe\u202e.fdp.pdf", "informe.fdp.pdf"],
        ["x".repeat(300) + ".pdf", (n) => n.length === 150 && n.endsWith(".pdf")],
    ];
    for (const [nombre, esperado] of casos) {
        const shim = shimConPdf({ fileName: nombre, base64: PDF_MINIMO });
        const res = await shim.ctx.PhotinoBridge.send({ action: ACCION_PDF, data: { folio: 1 } });
        assert.equal(res.ok, true);
        const obtenido = shim.registro.descargas[0].nombre;
        if (typeof esperado === "function") assert.ok(esperado(obtenido), obtenido);
        else if (esperado instanceof RegExp) assert.match(obtenido, esperado);
        else assert.equal(obtenido, esperado);
    }
});

test("el shim nunca guarda token ni rutas locales al descargar el PDF", async () => {
    const shim = shimConPdf({ fileName: "c.pdf", base64: PDF_MINIMO, path: "C:/Users/x/Downloads/c.pdf", token: "jwt-que-no-debe-salir" });
    const res = await shim.ctx.PhotinoBridge.send({ action: ACCION_PDF, data: { folio: 1 } });
    assert.deepEqual(Object.keys(plano(res.data)), ["fileName"]);
    assert.equal(shim.localStorage.datos.size, 0);
    assert.equal([...shim.ctx.sessionStorage.datos.values()].some((v) => /jwt-que-no-debe-salir|Downloads/.test(v)), false);
});

// ------------------------------------------------------------------ Fase 2h: adjuntos de Control Documental
const ACCION_ADJ = "controlDocumental.adjunto.abrir";
const DOCX_MINIMO = Buffer.from([0x50, 0x4b, 0x03, 0x04, 0x14, 0, 0, 0, 1, 2, 3]).toString("base64");

test("adjunto previsualizable (PDF/imagen) se devuelve tal cual para la modal de Photino, sin descarga", async () => {
    for (const [tipoMime, base64] of [["application/pdf", PDF_MINIMO], ["image/png", "iVBORw0KGgo="]]) {
        const shim = shimConPdf({ previsualizable: true, nombreArchivo: "PR-CAL-001_v1.1.pdf", tipoMime, contenidoBase64: base64 });
        const res = await shim.ctx.PhotinoBridge.send({ action: ACCION_ADJ, documentoVersionId: 1 });
        assert.deepEqual(plano(res), { ok: true, success: true, data: { previsualizable: true, nombreArchivo: "PR-CAL-001_v1.1.pdf", tipoMime, contenidoBase64: base64 }, error: null });
        assert.equal(shim.registro.fetch.length, 1);
        assert.equal(JSON.parse(shim.registro.fetch[0].opciones.body).documentoVersionId, 1, "payload plano, sin data");
        assert.equal(shim.registro.descargas.length, 0);
    }
    assert.deepEqual(plano(cargarShim().ctx.QCC_WEB.accionesDescarga), [ACCION_PDF, ACCION_ADJ]);
});

test("adjunto no previsualizable se descarga con Blob y responde {previsualizable:false, nombreArchivo} como Photino", async () => {
    const shim = shimConPdf({ previsualizable: false, nombreArchivo: "instructivo ñ.docx",
        tipoMime: "application/vnd.openxmlformats-officedocument.wordprocessingml.document", contenidoBase64: DOCX_MINIMO });
    const res = await shim.ctx.PhotinoBridge.send({ action: ACCION_ADJ, documentoVersionId: 3 });
    assert.deepEqual(plano(res), { ok: true, success: true, data: { previsualizable: false, nombreArchivo: "instructivo ñ.docx" }, error: null });
    assert.equal(shim.registro.descargas.length, 1);
    assert.equal(shim.registro.descargas[0].nombre, "instructivo ñ.docx");
    assert.equal(shim.blobs[0].type, "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    assert.equal(shim.blobs[0].size, 11);
});

test("MIME fuera de la lista segura baja como octet-stream; 'previsualizable' del servidor no basta si el MIME no lo es", async () => {
    let shim = shimConPdf({ previsualizable: false, nombreArchivo: "..\\..\\evil<>:\"|?*.exe", tipoMime: "text/html", contenidoBase64: Buffer.from("<b>x</b>").toString("base64") });
    let res = await shim.ctx.PhotinoBridge.send({ action: ACCION_ADJ, documentoVersionId: 900 });
    assert.equal(res.data.nombreArchivo, "evil_______.exe");
    assert.equal(shim.blobs[0].type, "application/octet-stream");
    // El servidor dice previsualizable pero el MIME es text/html → descarga, nunca modal.
    shim = shimConPdf({ previsualizable: true, nombreArchivo: "x.html", tipoMime: "text/html", contenidoBase64: Buffer.from("<script>").toString("base64") });
    res = await shim.ctx.PhotinoBridge.send({ action: ACCION_ADJ, documentoVersionId: 1 });
    assert.equal(res.data.previsualizable, false);
    assert.equal(shim.registro.descargas.length, 1);
    assert.equal(shim.blobs[0].type, "application/octet-stream");
});

test("adjunto vacío, corrupto o excesivo se rechaza limpiamente; el error del gateway pasa tal cual", async () => {
    const casos = [
        [{ previsualizable: false, nombreArchivo: "a.docx", tipoMime: "text/plain", contenidoBase64: "" }, "El adjunto no trae contenido"],
        [{ previsualizable: false, nombreArchivo: "a.docx", tipoMime: "text/plain", contenidoBase64: "%%%" }, "El adjunto no es válido."],
        [{ previsualizable: false, nombreArchivo: "a.docx", tipoMime: "text/plain", contenidoBase64: "A".repeat(Math.ceil(25 * 1024 * 1024 / 3) * 4 + 4) }, "El adjunto excede el tamaño máximo permitido."],
    ];
    for (const [data, error] of casos) {
        const shim = shimConPdf(data);
        const res = await shim.ctx.PhotinoBridge.send({ action: ACCION_ADJ, documentoVersionId: 1 });
        assert.deepEqual(plano(res), { ok: false, success: false, data: null, error });
        assert.equal(shim.registro.descargas.length, 0);
    }
    const shim = shimConPdf(null, { ok: false, success: false, data: null, error: "Esta versión no tiene ningún archivo adjunto" });
    const res = await shim.ctx.PhotinoBridge.send({ action: ACCION_ADJ, documentoVersionId: 404 });
    assert.equal(res.error, "Esta versión no tiene ningún archivo adjunto");
    assert.equal(shim.registro.descargas.length, 0);
});
