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
