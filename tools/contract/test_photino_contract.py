# -*- coding: utf-8 -*-
"""
Tests del contract check (solo librería estándar).
Ejecutar desde la raíz del repo web:  python -m unittest discover -s tools/contract -v
"""
import json
import os
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import photino_contract as pc  # noqa: E402

ROUTER = '''
namespace X {
  public class MessageRouter {
    public async Task<string> Handle(string payload) {
      // action.StartsWith("comentado") no debe contar
      if (action.StartsWith("inicio")) { var handler = new HomeHandler(_c); }
      else if (action == "excel.guardar") { rawResult = GuardarExcel(data); }
      else if (action.StartsWith("faretLab")) { var handler = new FaretLaboratorioHandler(_c); }
      else if (action.StartsWith("faret")) { var handler = new FaretHandler(_c); }
    }
  }
}
'''

HOME = '''
public class HomeHandler {
    private readonly InnpackHomeApiService _api;
    public async Task<string> Handle(string action, Dictionary<string, object>? data) {
        switch (action) {
            case "inicio.getDashboard":
                return await ObtenerDashboard();
            case "inicio.frecuencias.actualizar":
                return await ActualizarFrecuencia(data);
            default:
                return Error("x");
        }
    }
    private async Task<string> ObtenerDashboard() => await Forward(_api.DashboardAsync());
    private async Task<string> ActualizarFrecuencia(Dictionary<string, object>? p) {
        return await Forward(_api.ActualizarFrecuenciaAsync(1, 2));
    }
    private static async Task<string> Forward(Task<(bool ok, string body)> call) {
        var (ok, body) = await call;
        if (!ok) return Error("fallo {con llaves}");
        return Ok(body);
    }
    private static string Ok(object? d) { return "ok"; }
    private static string Error(string m) { return m; }
}
'''

SERVICIO = '''
public class InnpackHomeApiService {
    private readonly InnpackApiClient _client;
    public Task<(bool ok, string body)> DashboardAsync() => _client.GetAsync("api/home/dashboard");
    public Task<(bool ok, string body)> ActualizarFrecuenciaAsync(int id, int m) => _client.PutJsonAsync($"api/home/frecuencias/{id}", new { m });
}
'''

FARET = '''
public class FaretHandler {
    public async Task<string> Handle(string action) {
        return action switch {
            "faret.login" => await Login(),
            _ => "no"
        };
    }
    private async Task<string> Login() { return "x"; }
}
'''

LAB = '''
public class FaretLaboratorioHandler {
    public async Task<string> Handle(string action) {
        if (action == "faretLab.rct.guardar") return await Guardar();
        if (action == "faretLab.fct.guardar") return await Guardar();
        return "";
    }
    private async Task<string> Guardar() { return ""; }
}
'''

INICIO_JS = '''
window.InicioController = class {
    async cargar() { return window.PhotinoBridge.send({ action: "inicio.getDashboard" }); }
    async editar() { return window.PhotinoBridge.send({ action: 'inicio.frecuencias.actualizar', data: {} }); }
    // no es una acción: "inicio.view.html"
};
'''

LAB_JS = '''
class X {
    async _guardarEnsayo(accionSufijo, data) {
        return window.PhotinoBridge.send({ action: `faretLab.${accionSufijo}.guardar`, data });
    }
    a() { this._guardarEnsayo("rct", {}); }
    b() { this._guardarEnsayo("fct", {}); }
    c() { return window.PhotinoBridge.send({ action: "faret.login" }); }
    d() { return window.PhotinoBridge.send({ action: "faret.fantasma" }); }
}
'''


def repo(**cambios):
    archivos = {
        pc.ROUTER: ROUTER,
        pc.CSPROJ: "<Project><PropertyGroup><Version>9.9.9</Version></PropertyGroup></Project>",
        "src/Backend/Modules/Home/HomeHandler.cs": HOME,
        "src/Backend/Services/InnpackApi/InnpackHomeApiService.cs": SERVICIO,
        "src/Backend/Modules/Faret/FaretHandler.cs": FARET,
        "src/Backend/Modules/FaretLaboratorio/FaretLaboratorioHandler.cs": LAB,
        "src/UI/www/modules/inicio/inicio.controller.js": INICIO_JS,
        "src/UI/www/modules/faret-laboratorio/faret-laboratorio.controller.js": LAB_JS,
        "src/UI/www/libs/chart.js": 'x = "inicio.noCuenta"',
    }
    archivos.update(cambios)
    return pc.DictSource(archivos, commit="abc1234def")


WEB_DASHBOARD = {"acciones": [{"accion": "inicio.getDashboard", "empresas": ["INNPACK"], "roles": ["admin"]}]}


def estados(fuente, web=WEB_DASHBOARD, baseline=None):
    inv = pc.Inventario(fuente)
    filas = pc.comparar(inv, web, baseline or {"acciones": {}})
    return {f["accion"]: f for f in filas}, inv


def baseline_de(fuente, acciones=("inicio.getDashboard",)):
    inv = pc.Inventario(fuente)
    return pc.aprobar({"acciones": {}}, pc.comparar(inv, WEB_DASHBOARD, {"acciones": {}}), acciones, fuente)


class InventarioTest(unittest.TestCase):

    def test_frontend_literales_y_plantillas_resueltas_sin_libs(self):
        inv = pc.Inventario(repo())
        self.assertEqual(sorted(inv.frontend), [
            "faret.fantasma", "faret.login", "faretLab.fct.guardar", "faretLab.rct.guardar",
            "inicio.frecuencias.actualizar", "inicio.getDashboard"])
        self.assertEqual(inv.dinamicas[0]["resueltas"], ["faretLab.fct.guardar", "faretLab.rct.guardar"])

    def test_backend_case_expresion_switch_e_igualdad(self):
        inv = pc.Inventario(repo())
        for a in ("inicio.getDashboard", "inicio.frecuencias.actualizar", "faret.login",
                  "faretLab.rct.guardar", "excel.guardar"):
            self.assertIn(a, inv.backend, a)

    def test_router_respeta_orden_y_ignora_comentarios(self):
        inv = pc.Inventario(repo())
        self.assertEqual(inv.enrutar("faretLab.rct.guardar"), "FaretLaboratorioHandler")
        self.assertEqual(inv.enrutar("faret.login"), "FaretHandler")
        self.assertIsNone(inv.enrutar("comentado.x"))


class EstadosTest(unittest.TestCase):

    def test_estados_basicos(self):
        f = repo()
        filas, _ = estados(f, baseline=baseline_de(f))
        self.assertEqual(filas["inicio.getDashboard"]["estado"], "COMPATIBLE")
        self.assertEqual(filas["inicio.frecuencias.actualizar"]["estado"], "PENDIENTE")
        self.assertEqual(filas["faret.fantasma"]["estado"], "PHOTINO_SIN_HANDLER")
        self.assertEqual(filas["excel.guardar"]["estado"], "NO_USADA")

    def test_sin_baseline_es_revisar(self):
        filas, _ = estados(repo())
        self.assertEqual(filas["inicio.getDashboard"]["estado"], "REVISAR")

    def test_accion_web_que_photino_ya_no_atiende_es_solo_web(self):
        web = {"acciones": WEB_DASHBOARD["acciones"] + [{"accion": "usuarios.borrarTodo"}]}
        filas, _ = estados(repo(), web=web)
        self.assertEqual(filas["usuarios.borrarTodo"]["estado"], "SOLO_WEB")

    def test_accion_web_que_el_frontend_ya_no_usa_es_solo_web(self):
        f = repo(**{"src/UI/www/modules/inicio/inicio.controller.js": "// sin llamadas"})
        filas, _ = estados(f, baseline=baseline_de(repo()))
        self.assertEqual(filas["inicio.getDashboard"]["estado"], "SOLO_WEB")


class HuellaTest(unittest.TestCase):

    def setUp(self):
        self.base = baseline_de(repo())

    def estado_con(self, **cambios):
        filas, _ = estados(repo(**cambios), baseline=self.base)
        return filas["inicio.getDashboard"]

    def test_cambio_en_metodo_llamado_pasa_a_revisar(self):
        home = HOME.replace('return Ok(body);', 'return Ok(body + "x");')
        f = self.estado_con(**{"src/Backend/Modules/Home/HomeHandler.cs": home})
        self.assertEqual(f["estado"], "REVISAR")
        self.assertIn("cambió", f["motivo"])

    def test_cambio_en_el_servicio_llamado_pasa_a_revisar(self):
        servicio = SERVICIO.replace("api/home/dashboard", "api/home/dashboard-v2")
        self.assertEqual(self.estado_con(**{"src/Backend/Services/InnpackApi/InnpackHomeApiService.cs": servicio})["estado"],
                         "REVISAR")

    def test_cambio_en_el_case_pasa_a_revisar(self):
        home = HOME.replace("return await ObtenerDashboard();", "return await ObtenerDashboard(); // x\n                return Error(\"y\");")
        self.assertEqual(self.estado_con(**{"src/Backend/Modules/Home/HomeHandler.cs": home})["estado"], "REVISAR")

    def test_cambio_de_handler_en_el_router_pasa_a_revisar(self):
        router = ROUTER.replace('action.StartsWith("inicio")) { var handler = new HomeHandler(_c); }',
                                'action.StartsWith("inicio")) { var handler = new FaretHandler(_c); }')
        f = self.estado_con(**{pc.ROUTER: router})
        self.assertEqual(f["estado"], "REVISAR")

    def test_cambios_cosmeticos_no_alteran_la_huella(self):
        home = HOME.replace("private static string Ok(object? d) { return \"ok\"; }",
                            "// comentario nuevo\n    private static string Ok(object? d)\n    {\n        /* bloque */ return \"ok\";\n    }")
        self.assertEqual(self.estado_con(**{"src/Backend/Modules/Home/HomeHandler.cs": home})["estado"], "COMPATIBLE")

    def test_cambio_en_otra_accion_del_mismo_handler_no_afecta(self):
        home = HOME.replace("return await Forward(_api.ActualizarFrecuenciaAsync(1, 2));",
                            "return await Forward(_api.ActualizarFrecuenciaAsync(9, 9));")
        servicio = SERVICIO.replace("api/home/frecuencias/", "api/home/frecuencias-v2/")
        f = self.estado_con(**{"src/Backend/Modules/Home/HomeHandler.cs": home,
                               "src/Backend/Services/InnpackApi/InnpackHomeApiService.cs": servicio})
        self.assertEqual(f["estado"], "COMPATIBLE")

    def test_componentes_de_la_huella(self):
        filas, _ = estados(repo(), baseline=self.base)
        self.assertEqual(filas["inicio.getDashboard"]["componentesHuella"], [
            "MessageRouter→HomeHandler", 'HomeHandler.cs case "inicio.getDashboard"',
            "HomeHandler.cs#Error", "HomeHandler.cs#Forward", "HomeHandler.cs#ObtenerDashboard", "HomeHandler.cs#Ok",
            "InnpackHomeApiService.cs#DashboardAsync"])

    def test_huella_no_calculable_es_revisar(self):
        web = {"acciones": [{"accion": "excel.guardar"}]}  # atendida por el router, no por un handler
        f = repo(**{"src/UI/www/x.js": 'send({action: "excel.guardar"})'})
        filas, _ = estados(f, web=web, baseline={"acciones": {"excel.guardar": {"huella": "x"}}})
        self.assertEqual(filas["excel.guardar"]["estado"], "REVISAR")
        self.assertIn("no se pudo calcular", filas["excel.guardar"]["motivo"])


AUTH_ROUTER = ROUTER.replace('if (action.StartsWith("inicio"))',
                             'if (action.StartsWith("auth")) { rawResult = await _authHandler.Handle(action, d); }\n'
                             '      else if (action.StartsWith("inicio"))')

AUTH_HANDLER = '''
public class AuthHandler {
    private readonly AuthService _authService;
    public async Task<string> Handle(string action, JsonElement data) {
        return action switch {
            "auth.login" => await Login(data),
            "auth.logout" => Logout(),
            "auth.me" => Me(),
            _ => "x",
        };
    }
    private async Task<string> Login(JsonElement data) { var r = await _authService.LoginAsync(data); return r; }
    private string Logout() { _authService.Logout(); return ""; }
    private string Me() { return _authService.GetCurrentUser(); }
}
'''

AUTH_SERVICE = '''
public class AuthService {
    private readonly InnpackApiClient _api;
    public async Task<string> LoginAsync(JsonElement r) {
        var (ok, body) = await _api.PostJsonAsync("api/auth/login", r);
        return ok ? body : "Error al comunicarse con la API";
    }
    public void Logout() { }
    public string GetCurrentUser() { return "u"; }
}
'''

AUTH_JS = 'send({ action: "auth.login", data: {} }); send({ action: "auth.me", data: {} });'


MAQUINAS_HANDLER = '''
public class MaquinasHandler {
    private readonly MaquinasApi _api;
    public async Task<string> Handle(string action, Dictionary<string, object> data) {
        if (action == "maquinas.resumen")
        {
            int? id = null;
%s
            var (ok, body) = await _api.ResumenAsync(id);
            return JsonSerializer.Serialize(new { ok = true, data = body });
        }
        return "no";
    }
}
''' % "\n".join("            // parseo largo %d { \"}\" }" % i + "\n            id = id ?? %d;" % i for i in range(60))


class BloqueIfLargoTest(unittest.TestCase):
    """Rama `if (action == ...) { ... }` más larga que cualquier tope: se toma el bloque completo."""

    WEB = {"acciones": [{"accion": "maquinas.resumen"}]}

    def fuente(self, handler):
        router = ROUTER.replace('if (action.StartsWith("inicio"))',
                                'if (action.StartsWith("maquinas")) { var handler = new MaquinasHandler(_c); }\n'
                                '      else if (action.StartsWith("inicio"))')
        return repo(**{pc.ROUTER: router, "src/Backend/Modules/M/MaquinasHandler.cs": handler,
                       "src/UI/www/m.js": 'send({ action: "maquinas.resumen" })'})

    def test_cambio_al_final_de_un_bloque_largo_pasa_a_revisar(self):
        self.assertGreater(len(MAQUINAS_HANDLER), 3000)
        base_f = self.fuente(MAQUINAS_HANDLER)
        inv = pc.Inventario(base_f)
        baseline = pc.aprobar({}, pc.comparar(inv, self.WEB, {}), ["maquinas.resumen"], base_f)

        cambiado = MAQUINAS_HANDLER.replace("new { ok = true, data = body }", "new { ok = true, data = body, extra = 1 }")
        filas = {f["accion"]: f for f in pc.comparar(pc.Inventario(self.fuente(cambiado)), self.WEB, baseline)}
        self.assertEqual(filas["maquinas.resumen"]["estado"], "REVISAR")

        sin_cambio = {f["accion"]: f for f in pc.comparar(pc.Inventario(self.fuente(MAQUINAS_HANDLER)), self.WEB, baseline)}
        self.assertEqual(sin_cambio["maquinas.resumen"]["estado"], "COMPATIBLE")

    def test_if_de_una_linea_sin_llaves(self):
        seg = pc.Inventario._segmento_case(LAB, LAB.index('"faretLab.rct.guardar"'))
        self.assertTrue(seg.strip().endswith("return await Guardar();"))
        self.assertNotIn("faretLab.fct.guardar", seg)


class AuthContratoTest(unittest.TestCase):
    """auth.login / auth.me: atendidas en web por AuthController, controladas igual por huella."""

    WEB = {"acciones": [{"accion": "auth.login", "via": "AuthController POST /api/v1/auth/login"},
                        {"accion": "auth.me", "via": "AuthController GET /api/v1/auth/session"}]}

    @staticmethod
    def fuente(**cambios):
        base = {pc.ROUTER: AUTH_ROUTER,
                "src/Backend/Modules/Auth/AuthHandler.cs": AUTH_HANDLER,
                "src/Backend/Modules/Auth/AuthService.cs": AUTH_SERVICE,
                "src/UI/www/modules/auth/auth.controller.js": AUTH_JS}
        base.update(cambios)
        return repo(**base)

    def filas(self, fuente, baseline):
        inv = pc.Inventario(fuente)
        return {f["accion"]: f for f in pc.comparar(inv, self.WEB, baseline)}

    def baseline(self):
        f = self.fuente()
        inv = pc.Inventario(f)
        return pc.aprobar({}, pc.comparar(inv, self.WEB, {}), ["auth.login", "auth.me"], f)

    def test_compatibles_con_baseline(self):
        filas = self.filas(self.fuente(), self.baseline())
        self.assertEqual(filas["auth.login"]["estado"], "COMPATIBLE")
        self.assertEqual(filas["auth.me"]["estado"], "COMPATIBLE")
        self.assertIn("AuthService.cs#LoginAsync", filas["auth.login"]["componentesHuella"])
        self.assertEqual(filas["auth.logout"]["estado"], "NO_USADA")

    def test_cambio_en_la_logica_de_login_pasa_a_revisar(self):
        servicio = AUTH_SERVICE.replace('"api/auth/login"', '"api/v2/auth/login"')
        filas = self.filas(self.fuente(**{"src/Backend/Modules/Auth/AuthService.cs": servicio}), self.baseline())
        self.assertEqual(filas["auth.login"]["estado"], "REVISAR")
        self.assertEqual(filas["auth.me"]["estado"], "COMPATIBLE")

    def test_cambio_en_sesion_de_photino_pasa_auth_me_a_revisar(self):
        servicio = AUTH_SERVICE.replace('return "u";', 'return "otro";')
        filas = self.filas(self.fuente(**{"src/Backend/Modules/Auth/AuthService.cs": servicio}), self.baseline())
        self.assertEqual(filas["auth.me"]["estado"], "REVISAR")
        self.assertEqual(filas["auth.login"]["estado"], "COMPATIBLE")


class ReporteTest(unittest.TestCase):

    def test_resumen_texto_y_bloqueo(self):
        f = repo()
        inv = pc.Inventario(f)
        base = baseline_de(f)
        rep = pc.construir_reporte(f, inv, pc.comparar(inv, WEB_DASHBOARD, base), WEB_DASHBOARD, base)
        self.assertEqual(rep["resumen"]["texto"], "Photino 9.9.9 · Web compatible 1/6")
        self.assertFalse(rep["bloqueante"])

        rep2 = pc.construir_reporte(f, inv, pc.comparar(inv, WEB_DASHBOARD, {"acciones": {}}), WEB_DASHBOARD, {})
        self.assertTrue(rep2["bloqueante"])
        self.assertIn("REVISAR: inicio.getDashboard", rep2["motivosBloqueo"][0])

    def test_markdown_generado(self):
        f = repo()
        inv = pc.Inventario(f)
        rep = pc.construir_reporte(f, inv, pc.comparar(inv, WEB_DASHBOARD, {}), WEB_DASHBOARD, {})
        with tempfile.TemporaryDirectory() as d:
            ruta = os.path.join(d, "r.md")
            pc.escribir_markdown(rep, ruta)
            with open(ruta, encoding="utf-8") as fh:
                texto = fh.read()
        self.assertIn("| ACTION | PHOTINO | WEB | ESTADO | detalle |", texto)
        self.assertIn("| `inicio.getDashboard` | OK | OK | REVISAR |", texto)
        self.assertIn("Release web: **BLOQUEADO**", texto)

    def test_aprobar_rechaza_accion_no_habilitada(self):
        f = repo()
        inv = pc.Inventario(f)
        with self.assertRaises(ValueError):
            pc.aprobar({}, pc.comparar(inv, WEB_DASHBOARD, {}), ["usuarios.list"], f)


class LimpiezaCsTest(unittest.TestCase):

    def test_quitar_comentarios_respeta_strings(self):
        src = 'var a = "http://x"; // fin\nvar b = @"c:\\y""z"; /* bloque */ var c = \'/\';'
        self.assertEqual(pc.quitar_comentarios_cs(src), 'var a = "http://x"; \nvar b = @"c:\\y""z";  var c = \'/\';')

    def test_extraer_metodo_con_llaves_en_strings(self):
        cuerpo = pc.extraer_metodo(HOME, "Forward")
        self.assertTrue(cuerpo.strip().endswith("}"))
        self.assertIn("return Ok(body);", cuerpo)
        self.assertNotIn("private static string Ok", cuerpo)


PHOTINO_REAL = os.path.join(os.path.dirname(__file__), "..", "..", "..", "qualitycontrol_desktop_faret")


@unittest.skipUnless(os.path.isdir(os.path.join(PHOTINO_REAL, ".git")), "repo Photino no disponible")
class PhotinoRealTest(unittest.TestCase):
    """Contra el commit real de Photino 1.8.12 (solo lectura vía git)."""

    def test_commit_6c42e05_con_baseline_versionada(self):
        fuente = pc.GitSource(PHOTINO_REAL, "6c42e05")
        inv = pc.Inventario(fuente)
        raiz = os.path.join(os.path.dirname(__file__), "..", "..")
        baseline = pc.leer_json(os.path.join(raiz, "contract", "baseline.json"))
        web = {"acciones": [{"accion": a} for a in baseline["acciones"]]}
        rep = pc.construir_reporte(fuente, inv, pc.comparar(inv, web, baseline), web, baseline)
        self.assertEqual(rep["resumen"]["accionesFrontend"], 236)
        self.assertEqual(rep["resumen"]["noUsadas"], 29)
        self.assertEqual(rep["resumen"]["photinoSinHandler"], 0)
        self.assertEqual(rep["resumen"]["dinamicasSinResolver"], 0)
        self.assertEqual(rep["resumen"]["compatibles"], len(baseline["acciones"]))
        self.assertFalse(rep["bloqueante"])



class GitSourceTest(unittest.TestCase):

    def test_lee_el_commit_y_no_el_working_tree(self):
        with tempfile.TemporaryDirectory() as d:
            def git(*a):
                subprocess.run(["git", "-C", d, *a], check=True, capture_output=True)
            git("init", "-q")
            git("config", "user.email", "test@example.invalid")
            git("config", "user.name", "test")
            git("config", "core.autocrlf", "false")
            os.makedirs(os.path.join(d, "src", "Backend"))
            ruta = os.path.join(d, "src", "Backend", "A.cs")
            with open(ruta, "w", encoding="utf-8", newline="\n") as fh:
                fh.write("// commiteado\r\nclass A {}\n")
            git("add", ".")
            git("commit", "-q", "-m", "c1")
            with open(ruta, "w", encoding="utf-8") as fh:
                fh.write("// SIN COMMITEAR\n")

            fuente = pc.GitSource(d, "HEAD")
            self.assertEqual(fuente.paths(), ["src/Backend/A.cs"])
            self.assertEqual(fuente.read("src/Backend/A.cs"), "// commiteado\r\nclass A {}\n")


if __name__ == "__main__":
    unittest.main()
