# -*- coding: utf-8 -*-
"""
Contract check Photino <-> QCC Web (herramienta de build; NO forma parte del runtime del gateway).

Compara, para un COMMIT de Photino (nunca su working tree):
  - acciones que usa el frontend compartido (src/UI/www, PhotinoBridge.send);
  - acciones que realmente atienden MessageRouter + handlers C# (src/Backend);
  - acciones habilitadas en la web (export de la ActionPolicy Java, web-actions.json).

Estados por acción:
  COMPATIBLE  habilitada en web, la usa Photino y su lógica C# no cambió desde que se validó.
  PENDIENTE   la usa Photino pero la web aún no la habilita (responde "no disponible").
  REVISAR     habilitada en web pero su lógica C# cambió (huella distinta), no tiene huella
              validada, o no se pudo calcular la huella. Bloquea el release web.
  SOLO_WEB    habilitada en web pero Photino ya no la atiende o no la usa. Bloquea el release web.
Informativos (no cuentan para la compatibilidad):
  NO_USADA              la atiende un handler C# pero ningún frontend la llama (código muerto).
  PHOTINO_SIN_HANDLER   el frontend la llama pero ningún handler la atiende (bug de Photino).

Habilitada en web significa: ActionPolicy (vía bridge), AuthController (vía /api/v1/auth/*) o
ACCIONES_NAVEGADOR de web-bridge.js (resuelta en el navegador, p. ej. excel.guardar). En los tres
casos la huella es la de la lógica C# de Photino equivalente, así que un cambio allí → REVISAR.

Uso:
  python photino_contract.py --photino-repo RUTA --commit SHA --web-actions web-actions.json
         --web-shim web-shim/web-bridge.js --baseline contract/baseline.json --out DIR
         [--aprobar ACCION ...]

Código de salida: 0 = release web permitido; 2 = bloqueado (REVISAR / SOLO_WEB / error);
1 = error de uso o de lectura. Un resultado bloqueado JAMÁS afecta a Photino.
"""
import argparse
import datetime
import hashlib
import json
import os
import re
import subprocess
import sys
from collections import defaultdict

WWW = "src/UI/www/"
BACKEND = "src/Backend/"
ROUTER = "src/Backend/Services/MessageRouter.cs"
CSPROJ = "QualityControlCenter.csproj"

BLOQUEANTES = ("REVISAR", "SOLO_WEB")


# =============================================================== fuentes de archivos

class GitSource:
    """Lee archivos de un commit (bytes exactos del blob, sin conversión de finales de línea)."""

    def __init__(self, repo, commit):
        self.repo = repo
        self.commit = self._git("rev-parse", "--verify", commit + "^{commit}").strip()
        self.commit_date = self._git("show", "-s", "--format=%cI", self.commit).strip()
        listado = self._git("ls-tree", "-r", "--name-only", self.commit, "--", "src/UI/www", "src/Backend", CSPROJ)
        self._paths = [p for p in listado.splitlines() if p]
        self._cache = {}

    def _git(self, *args):
        r = subprocess.run(["git", "-C", self.repo, *args], capture_output=True)
        if r.returncode != 0:
            raise RuntimeError("git %s falló: %s" % (" ".join(args), r.stderr.decode("utf-8", "replace")))
        return r.stdout.decode("utf-8", "replace")

    def paths(self):
        return list(self._paths)

    def read(self, path):
        if path not in self._cache:
            r = subprocess.run(["git", "-C", self.repo, "cat-file", "blob", "%s:%s" % (self.commit, path)],
                               capture_output=True)
            if r.returncode != 0:
                raise RuntimeError("No se pudo leer %s en %s" % (path, self.commit))
            self._cache[path] = r.stdout.decode("utf-8", "replace").lstrip("\ufeff")
        return self._cache[path]


class DictSource:
    """Fuente en memoria (tests)."""

    def __init__(self, files, commit="test", commit_date=""):
        self.files = dict(files)
        self.commit = commit
        self.commit_date = commit_date

    def paths(self):
        return list(self.files)

    def read(self, path):
        return self.files[path]


# =============================================================== utilidades C#

def quitar_comentarios_cs(texto):
    """Elimina comentarios // y /* */ respetando literales de string y char."""
    out, i, n = [], 0, len(texto)
    while i < n:
        c = texto[i]
        if texto.startswith("//", i):
            j = texto.find("\n", i)
            i = n if j < 0 else j
        elif texto.startswith("/*", i):
            j = texto.find("*/", i + 2)
            i = n if j < 0 else j + 2
        elif c == '"' or (c in "@$" and i + 1 < n and texto[i + 1] in '"@$'):
            j = _fin_string(texto, i)
            out.append(texto[i:j])
            i = j
        elif c == "'":
            j = i + 1
            while j < n and texto[j] != "'":
                j += 2 if texto[j] == "\\" else 1
            out.append(texto[i:j + 1])
            i = j + 1
        else:
            out.append(c)
            i += 1
    return "".join(out)


def _fin_string(texto, i):
    """Índice después del cierre de un literal string C# que empieza en i ("..", @"..", $"..", $@"..")."""
    n = len(texto)
    verbatim = False
    while i < n and texto[i] in "@$":
        verbatim = verbatim or texto[i] == "@"
        i += 1
    i += 1  # comilla de apertura
    while i < n:
        if verbatim:
            if texto[i] == '"':
                if i + 1 < n and texto[i + 1] == '"':
                    i += 2
                    continue
                return i + 1
        else:
            if texto[i] == "\\":
                i += 2
                continue
            if texto[i] == '"' or texto[i] == "\n":
                return i + 1
        i += 1
    return n


def normalizar(texto):
    return re.sub(r"\s+", " ", quitar_comentarios_cs(texto)).strip()


def extraer_metodo(src, nombre):
    """Texto de la definición del método `nombre` (firma + cuerpo), o None. Soporta cuerpos {...} y =>."""
    patron = re.compile(r"(?m)^[ \t]*(?:(?:public|private|protected|internal|static|async|override|virtual|sealed|new)\s+)+"
                        r"[\w<>\[\],\s\(\)\?\.]*?\b" + re.escape(nombre) + r"\s*\(")
    m = patron.search(src)
    if not m:
        return None
    i = m.end()
    profundidad = 1
    while i < len(src) and profundidad:  # cierra la lista de parámetros
        if src[i] == "(":
            profundidad += 1
        elif src[i] == ")":
            profundidad -= 1
        i += 1
    j = i
    while j < len(src) and src[j] not in "{=;":
        j += 1
    if j >= len(src) or src[j] == ";":
        return None
    if src[j] == "=":  # expression-bodied
        k = _avanzar_hasta(src, j, ";")
        return src[m.start():k + 1]
    k = _cerrar_llave(src, j)
    return src[m.start():k + 1]


def _cerrar_llave(src, j):
    profundidad, i = 0, j
    while i < len(src):
        c = src[i]
        if c == '"' or (c in "@$" and i + 1 < len(src) and src[i + 1] in '"@$'):
            i = _fin_string(src, i)
            continue
        if src.startswith("//", i):
            i = src.find("\n", i) if src.find("\n", i) >= 0 else len(src)
            continue
        if c == "{":
            profundidad += 1
        elif c == "}":
            profundidad -= 1
            if profundidad == 0:
                return i
        i += 1
    return len(src) - 1


def _avanzar_hasta(src, j, fin):
    i = j
    while i < len(src):
        c = src[i]
        if c == '"' or (c in "@$" and i + 1 < len(src) and src[i + 1] in '"@$'):
            i = _fin_string(src, i)
            continue
        if c == fin:
            return i
        i += 1
    return len(src) - 1


# =============================================================== frontend JS (huella del llamado)

def quitar_comentarios_js(texto):
    """Elimina comentarios // y /* */ de JS respetando strings '..', ".." y `..`."""
    out, i, n = [], 0, len(texto)
    while i < n:
        c = texto[i]
        if texto.startswith("//", i):
            j = texto.find("\n", i)
            i = n if j < 0 else j
        elif texto.startswith("/*", i):
            j = texto.find("*/", i + 2)
            i = n if j < 0 else j + 2
        elif c in "\"'`":
            j = _fin_string_js(texto, i)
            out.append(texto[i:j])
            i = j
        else:
            out.append(c)
            i += 1
    return "".join(out)


def _fin_string_js(texto, i):
    q, j, n = texto[i], i + 1, len(texto)
    while j < n:
        if texto[j] == "\\":
            j += 2
            continue
        if texto[j] == q or (q != "`" and texto[j] == "\n"):
            return j + 1
        j += 1
    return n


def _cierre_js(texto, abre):
    """Índice del cierre que balancea el ( [ { en `abre`, saltando strings JS; -1 si no cierra."""
    pares = {"(": ")", "[": "]", "{": "}"}
    pila, i, n = [], abre, len(texto)
    while i < n:
        c = texto[i]
        if c in "\"'`":
            i = _fin_string_js(texto, i)
            continue
        if c in pares:
            pila.append(pares[c])
        elif c in ")]}":
            if not pila or pila.pop() != c:
                return -1
            if not pila:
                return i
        i += 1
    return -1


def _sentencia_js(texto, inicio):
    """Desde `inicio` hasta el ; o fin de línea de nivel 0 (balanceando paréntesis/llaves)."""
    i, n = inicio, len(texto)
    while i < n:
        c = texto[i]
        if c in "\"'`":
            i = _fin_string_js(texto, i)
            continue
        if c in "([{":
            k = _cierre_js(texto, i)
            if k < 0:
                return texto[inicio:]
            i = k + 1
            continue
        if c in ";\n":
            return texto[inicio:i]
        i += 1
    return texto[inicio:]


def _metodo_js(texto, nombre):
    """Definición `nombre(...) {...}` (método de clase o function) del mismo archivo, o None."""
    m = re.search(r"(?m)^[ \t]*(?:async\s+)?(?:function\s+)?" + re.escape(nombre) + r"\s*\([^)]*\)\s*\{", texto)
    if not m:
        return None
    k = _cierre_js(texto, m.end() - 1)
    return texto[m.start():k + 1] if k >= 0 else None


def fragmentos_llamado_js(texto, accion, callbacks=None):
    """
    Lo mínimo que define el PAYLOAD que el frontend manda para `accion`:
      - el argumento completo de cada PhotinoBridge.send(...) que contiene el literal de la acción
        (si el literal no está dentro de un send, p. ej. un mapa de configuración, su sentencia);
      - un salto: declaraciones locales (const/let/var) de identificadores usados en ese argumento
        (`...filtros`, `data`) dentro del método que lo contiene, y los métodos `this.x(...)`
        llamados desde el argumento o desde esas declaraciones;
      - ACCIÓN DINÁMICA (literal como valor de una clave de un mapa, p. ej. `crearAction: "x.crear"`):
        los usos de esa clave en el mismo archivo (`cfg.crearAction`), el método que arma el payload
        (`this._catalogoCrear(...)`) y los métodos `this.x()` usados dentro de su PhotinoBridge.send.
        Si el uso es un callback (`crear: nombre => ...`), su nombre se agrega a `callbacks` para que
        huella_frontend incluya dónde el componente compartido lo invoca (`opciones.crear(texto)`).
        Solo entra la entrada del mapa de ESTA acción, no las vecinas.
      - ACCIÓN EN VARIABLE LOCAL (`const action = cond ? "x.update" : "x.create"` y luego
        `PhotinoBridge.send({ action, ...payload })` en el mismo método): el método desde su cabecera hasta
        el cierre de ESE send (armado completo del payload, incluidas mutaciones previas) y los métodos
        `this.x()` llamados en ese tramo.
    No cubre mutaciones posteriores (`data.x = ...`): documentado como límite.
    """
    limpio = quitar_comentarios_js(texto)
    frag = []
    for m in re.finditer(r"([\"'`])" + re.escape(accion) + r"\1", limpio):
        pos = m.start()
        send = limpio.rfind("PhotinoBridge.send(", 0, pos)
        arg = None
        if send >= 0:
            abre = send + len("PhotinoBridge.send")
            cierra = _cierre_js(limpio, abre)
            if cierra > pos:
                arg = limpio[abre:cierra + 1]
        dinamica = []
        if arg is None:
            ini = limpio.rfind("\n", 0, pos) + 1
            arg = _sentencia_js(limpio, ini)
            ancla = ini
            dinamica = _usos_de_clave_js(limpio, ini, ini + len(arg), pos, callbacks)
            if not dinamica:
                dinamica = _variable_local_hasta_send_js(limpio, ini, arg)
        else:
            ancla = send
        partes = ["llamado:" + arg] + _contexto_js(limpio, ancla, arg) + dinamica
        frag.append("\n".join(re.sub(r"\s+", " ", p).strip() for p in partes))
    return frag


def _contexto_js(limpio, ancla, arg, metodos=True):
    """Declaraciones locales (mismo método) de los identificadores de `arg` y métodos this.x() usados."""
    previo = limpio[:ancla]
    ventana = "\n".join(previo.split("\n")[-80:])
    # Solo dentro del método/función que contiene el llamado (no cruzar a métodos vecinos).
    cabeceras = [c for c in re.finditer(r"(?m)^[ \t]*(?:async\s+)?(?:function\s+)?([A-Za-z_$][\w$]*)\s*\([^\n]*\)\s*\{\s*$", ventana)
                 if c.group(1) not in ("if", "for", "while", "switch", "catch", "with", "else")]
    if cabeceras:
        ventana = ventana[cabeceras[-1].start():]
    base = len(previo) - len(ventana)
    extras = []
    # Identificadores sueltos o en spread (`...x`), no accesos a miembro (`obj.x`), ni la variable
    # que declara la propia sentencia (`const res = await this._send(...)`).
    propia = re.match(r"\s*(?:const|let|var)\s+([A-Za-z_$][\w$]*)", arg)
    idents = {mi.group(1) for mi in re.finditer(r"(?<![\w$])([A-Za-z_$][\w$]*)", arg)
              if not (arg[:mi.start()].endswith(".") and not arg[:mi.start()].endswith("..."))}
    if propia:
        idents.discard(propia.group(1))
    for ident in sorted(idents):
        decl = None
        for d in re.finditer(r"\b(?:const|let|var)\s+" + re.escape(ident) + r"\s*=", ventana):
            decl = d
        if decl:
            extras.append(_sentencia_js(limpio, base + decl.start()))
    partes = ["decl:" + e for e in extras]
    if metodos:
        for met in sorted(set(re.findall(r"this\.(\w+)\s*\(", arg + "\n" + "\n".join(extras)))):
            cuerpo = _metodo_js(limpio, met)
            if cuerpo:
                partes.append("metodo:" + cuerpo)
    return partes


def _variable_local_hasta_send_js(limpio, ini, sentencia):
    """Literal asignado a una variable local: tramo del método hasta el send que la usa + métodos this.x()."""
    var = re.match(r"\s*(?:const|let|var)\s+([A-Za-z_$][\w$]*)\s*=", sentencia)
    if not var:
        return []
    cabeceras = [c for c in re.finditer(r"(?m)^[ \t]*(?:async\s+)?(?:function\s+)?([A-Za-z_$][\w$]*)\s*\([^\n]*\)\s*\{\s*$", limpio[:ini])
                 if c.group(1) not in ("if", "for", "while", "switch", "catch", "with", "else")]
    if not cabeceras:
        return []
    cabecera = cabeceras[-1]
    met_fin = _cierre_js(limpio, limpio.index("{", cabecera.start()))
    if met_fin < ini:
        return []
    uso = re.compile(r"(?<![\w$.])" + re.escape(var.group(1)) + r"(?![\w$])")
    partes = []
    for s in re.finditer(r"PhotinoBridge\.send\s*\(", limpio[ini:met_fin]):
        abre = ini + s.end() - 1
        cierra = _cierre_js(limpio, abre)
        if cierra < 0 or not uso.search(limpio[abre:cierra + 1]):
            continue
        tramo = limpio[cabecera.start():cierra + 1]
        partes.append("metodo_hasta_send:" + tramo)
        for met in sorted(set(re.findall(r"this\.(\w+)\s*\(", tramo))):
            cuerpo = _metodo_js(limpio, met)
            if cuerpo and met != cabecera.group(1):
                partes.append("metodo:" + cuerpo)
    return partes


def _usos_de_clave_js(limpio, ini_entrada, fin_entrada, pos, callbacks):
    """Acción dinámica: usos de la clave del mapa que contiene el literal y el método que arma el payload."""
    clave = re.search(r"([A-Za-z_$][\w$]*)\s*:\s*$", limpio[ini_entrada:pos])
    if not clave:
        return []
    partes = []
    for u in re.finditer(r"\.\s*" + re.escape(clave.group(1)) + r"(?![\w$])", limpio):
        if ini_entrada <= u.start() < fin_entrada:
            continue
        uso = _sentencia_js(limpio, limpio.rfind("\n", 0, u.start()) + 1)
        partes.append("uso:" + uso)
        for met in sorted(set(re.findall(r"this\.(\w+)\s*\(", uso))):
            cuerpo = _metodo_js(limpio, met)
            if not cuerpo:
                continue
            partes.append("metodo:" + cuerpo)
            # Métodos usados DENTRO del send de ese método (p. ej. creadoPor: this._usuarioActual()).
            for s in re.finditer(r"PhotinoBridge\.send\s*\(", cuerpo):
                cierra = _cierre_js(cuerpo, s.end() - 1)
                args = cuerpo[s.end() - 1:cierra + 1] if cierra > 0 else ""
                for m2 in sorted(set(re.findall(r"this\.(\w+)\s*\(", args))):
                    cuerpo2 = _metodo_js(limpio, m2)
                    if cuerpo2:
                        partes.append("metodo:" + cuerpo2)
        cb = re.match(r"\s*([A-Za-z_$][\w$]*)\s*:\s*(?:async\s*)?(?:\([^)]*\)|[A-Za-z_$][\w$]*)\s*=>", uso)
        if cb and callbacks is not None:
            callbacks.add(cb.group(1))
    return partes


def fragmentos_callback_js(texto, nombre):
    """
    Dónde un componente compartido invoca el callback `nombre` recibido en `opciones`
    (CatalogCombo: `const nuevo = await estado.opciones.crear(texto)`) y la declaración local de sus
    argumentos (`const texto = input.value.trim()`): la transformación del dato antes del envío.
    """
    limpio = quitar_comentarios_js(texto)
    frag = []
    for m in re.finditer(r"\bopciones\s*\.\s*" + re.escape(nombre) + r"\s*\(", limpio):
        ini = limpio.rfind("\n", 0, m.start()) + 1
        sentencia = _sentencia_js(limpio, ini)
        partes = ["callback:" + sentencia] + _contexto_js(limpio, ini, sentencia, metodos=False)
        frag.append("\n".join(re.sub(r"\s+", " ", p).strip() for p in partes))
    return frag


LLAMADA = re.compile(r"(?<![\w.])([A-Z]\w*)\s*\(")
LLAMADA_CAMPO = re.compile(r"\b(_\w+)\s*\.\s*([A-Z]\w*)\s*\(")
LITERAL_ACCION = re.compile(r'"([a-zA-Z]+(?:\.[a-zA-Z0-9_]+)+)"')


# =============================================================== inventario

class Inventario:
    def __init__(self, fuente):
        self.fuente = fuente
        self.rutas = self._leer_router()
        self.prefijos = sorted({k.split(".")[0] for _, k, _ in self.rutas}, key=len, reverse=True)
        self.clases = self._indexar_clases()
        self.backend = self._leer_handlers()         # accion -> [(clase, path, offset)]
        self.frontend, self.dinamicas = self._leer_frontend()

    # ---------------------------------------------------------------- router
    def _leer_router(self):
        src = quitar_comentarios_cs(self.fuente.read(ROUTER))
        rutas = []
        for m in re.finditer(r'action\.StartsWith\("([^"]+)"\)|action == "([^"]+)"', src):
            cola = src[m.end(): m.end() + 400]
            h = re.search(r"new (\w+Handler)\(|_authHandler|rawResult\s*=\s*([A-Z]\w*)\s*\(", cola)
            if h and h.group(1):
                handler = h.group(1)
            elif h and "_authHandler" in h.group(0):
                handler = "AuthHandler"
            elif h and h.group(2):
                # Acción atendida por un método del propio router (p. ej. excel.guardar → GuardarExcel).
                handler = "MessageRouter." + h.group(2)
            else:
                handler = "MessageRouter"
            rutas.append(("prefijo" if m.group(1) else "exacta", m.group(1) or m.group(2), handler))
        return rutas

    def enrutar(self, accion):
        for tipo, clave, handler in self.rutas:
            if (tipo == "prefijo" and accion.startswith(clave)) or (tipo == "exacta" and accion == clave):
                return handler
        return None

    # ---------------------------------------------------------------- clases C#
    def _indexar_clases(self):
        clases = {}
        for p in self.fuente.paths():
            if p.startswith(BACKEND) and p.endswith(".cs"):
                for m in re.finditer(r"\bclass\s+(\w+)", self.fuente.read(p)):
                    clases.setdefault(m.group(1), p)
        return clases

    def _leer_handlers(self):
        backend = defaultdict(list)
        for p in self.fuente.paths():
            if not (p.startswith(BACKEND + "Modules/") and p.endswith("Handler.cs")):
                continue
            clase = p.rsplit("/", 1)[1][:-3]
            src = self.fuente.read(p)
            offset = 0
            for linea in src.splitlines(True):
                s = linea.strip()
                if s.startswith("case ") or "action ==" in s or re.match(r'^"[^"]+"(\s*(or|\|\|)\s*"[^"]+")*\s*=>', s):
                    for m in LITERAL_ACCION.finditer(linea):
                        backend[m.group(1)].append((clase, p, offset + m.start()))
                offset += len(linea)
        for tipo, clave, handler in self.rutas:
            if tipo == "exacta":
                backend[clave].append((handler, ROUTER, None))
        return backend

    # ---------------------------------------------------------------- frontend
    def _leer_frontend(self):
        front = defaultdict(set)
        dinamicas = []
        alt = "|".join(re.escape(p) for p in self.prefijos)
        literal = re.compile(r"([\"'`])((?:%s)[A-Za-z]*\.[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)*)\1" % alt)
        plantilla = re.compile(r"`((?:%s)[A-Za-z]*\.[^`]*\$\{[^`]*)`" % alt)
        extensiones = (".js", ".html", ".css", ".png", ".jpg", ".svg", ".json", ".xlsx", ".pdf", ".ico")
        for p in self.fuente.paths():
            if not p.startswith(WWW) or "/libs/" in p or not p.endswith((".js", ".html")):
                continue
            lineas = self.fuente.read(p).splitlines()
            texto = "\n".join(lineas)
            rel = p[len(WWW):]
            for i, linea in enumerate(lineas, 1):
                for _, accion in literal.findall(linea):
                    if not accion.lower().endswith(extensiones):
                        front[accion].add(rel)
                for t in plantilla.findall(linea):
                    resueltas = self._resolver_plantilla(t, lineas, i, texto)
                    for a in resueltas:
                        front[a].add(rel)
                    dinamicas.append({"ubicacion": "%s:%d" % (rel, i), "expresion": "`%s`" % t,
                                      "resueltas": sorted(resueltas)})
        return front, dinamicas

    @staticmethod
    def _resolver_plantilla(t, lineas, i, texto):
        """`muestraLab.${x}.guardar` dentro de f(x) → literales pasados a .f("...")."""
        var = re.search(r"\$\{(\w+)\}", t).group(1)
        for j in range(i - 1, max(0, i - 80), -1):
            fm = re.match(r"\s*(?:async\s+)?(\w+)\s*\(([^)]*)\)\s*\{", lineas[j - 1])
            if fm and var in [x.strip() for x in fm.group(2).split(",")]:
                idx = [x.strip() for x in fm.group(2).split(",")].index(var)
                res = set()
                for cm in re.finditer(r"\." + fm.group(1) + r"\(([^)]*)\)", texto):
                    args = [x.strip() for x in cm.group(1).split(",")]
                    if len(args) > idx and re.match(r"^[\"'](\w+)[\"']$", args[idx]):
                        res.add(re.sub(r"\$\{\w+\}", args[idx][1:-1], t))
                return res
        return set()

    # ---------------------------------------------------------------- huella
    def huella(self, accion):
        """
        SHA-256 de la lógica C# alcanzable desde el case de la acción:
          - handler al que enruta MessageRouter;
          - el propio case (hasta la siguiente rama);
          - métodos del handler que llama, transitivamente;
          - métodos de servicios invocados vía campos del handler (_api.Metodo), un salto.
        Comentarios y espacios se normalizan (un cambio cosmético no altera la huella).
        Devuelve (huella | None, error | None, componentes).
        """
        declaraciones = self.backend.get(accion)
        enrutada = self.enrutar(accion)
        if not declaraciones or not enrutada:
            return None, "sin handler en Photino", []
        if enrutada.startswith("MessageRouter."):
            return self._huella_metodo_router(accion, enrutada.split(".", 1)[1])
        propias = [d for d in declaraciones if d[0] == enrutada and d[2] is not None]
        if not propias:
            return None, "el router envía '%s' a %s, que no la declara" % (accion, enrutada), []
        clase, path, offset = propias[0]
        src = self.fuente.read(path)
        segmento = self._segmento_case(src, offset)
        partes = ["router:%s" % enrutada, "case:" + normalizar(segmento)]
        componentes = ["MessageRouter→%s" % enrutada, "%s case \"%s\"" % (path.rsplit("/", 1)[1], accion)]
        visitados = set()
        pendientes = [(path, n) for n in LLAMADA.findall(quitar_comentarios_cs(segmento))]
        pendientes += self._llamadas_a_campos(src, segmento)
        while pendientes:
            archivo, metodo = pendientes.pop()
            if (archivo, metodo) in visitados:
                continue
            visitados.add((archivo, metodo))
            cuerpo = extraer_metodo(self.fuente.read(archivo), metodo)
            if cuerpo is None:
                continue
            partes.append("%s#%s:%s" % (archivo.rsplit("/", 1)[1], metodo, normalizar(cuerpo)))
            componentes.append("%s#%s" % (archivo.rsplit("/", 1)[1], metodo))
            if archivo == path:  # solo se sigue transitivamente dentro del handler + 1 salto a servicios
                cuerpo_limpio = quitar_comentarios_cs(cuerpo)
                pendientes += [(path, n) for n in LLAMADA.findall(cuerpo_limpio)]
                pendientes += self._llamadas_a_campos(src, cuerpo)
        digest = hashlib.sha256("\n".join(sorted(partes)).encode("utf-8")).hexdigest()
        return digest, None, componentes[:2] + sorted(componentes[2:])

    def huella_frontend(self, accion):
        """
        SHA-256 del LLAMADO del frontend a la acción (payload): fragmentos_llamado_js en cada
        archivo .js/.html que la usa. Un cambio visual/CSS no la altera; un cambio en las claves o
        en cómo se arma el payload sí → REVISAR. Devuelve (huella | None, error | None).
        """
        archivos = sorted(self.frontend.get(accion, []))
        if not archivos:
            return None, "el frontend no usa la acción"
        partes = []
        callbacks = set()
        for rel in archivos:
            for fr in fragmentos_llamado_js(self.fuente.read(WWW + rel), accion, callbacks):
                partes.append(rel + "\n" + fr)
        if not partes:
            return None, "no se encontró el llamado literal en " + ", ".join(archivos)
        # Acción dinámica pasada como callback a un componente compartido: sitio donde se invoca.
        for nombre in sorted(callbacks):
            for p in self.fuente.paths():
                if p.startswith(WWW) and p.endswith(".js") and "/libs/" not in p:
                    for fr in fragmentos_callback_js(self.fuente.read(p), nombre):
                        partes.append(p[len(WWW):] + "\n" + fr)
        return hashlib.sha256("\n--\n".join(sorted(partes)).encode("utf-8")).hexdigest(), None

    def _huella_metodo_router(self, accion, metodo):
        """Acción atendida directamente por un método de MessageRouter (ruta exacta)."""
        cuerpo = extraer_metodo(self.fuente.read(ROUTER), metodo)
        if cuerpo is None:
            return None, "MessageRouter.%s no encontrado" % metodo, []
        partes = ["router:MessageRouter.%s" % metodo, "ruta:%s" % accion, "MessageRouter.cs#%s:%s" % (metodo, normalizar(cuerpo))]
        digest = hashlib.sha256("\n".join(partes).encode("utf-8")).hexdigest()
        return digest, None, ['MessageRouter.cs action == "%s"' % accion, "MessageRouter.cs#%s" % metodo]

    def _llamadas_a_campos(self, src_handler, codigo):
        """_api.Metodo( → (archivo de la clase del campo, Metodo)."""
        res = []
        for campo, metodo in LLAMADA_CAMPO.findall(quitar_comentarios_cs(codigo)):
            m = re.search(r"\b(\w+)\s+" + re.escape(campo) + r"\s*[;=]", src_handler)
            if m and m.group(1) in self.clases:
                res.append((self.clases[m.group(1)], metodo))
        return res

    @staticmethod
    def _segmento_case(src, offset):
        """
        Código de la rama de la acción:
          - `if (action == "x") { ... }` → el bloque completo (llaves balanceadas, sin tope de largo);
            `if (action == "x") return ...;` → hasta el fin de la sentencia.
          - `case "x":` / `"x" => ...` → desde el literal hasta el siguiente case / rama / default.
        """
        inicio = src.rfind("\n", 0, offset) + 1
        fin_linea = src.find("\n", offset)
        linea = src[inicio:fin_linea if fin_linea >= 0 else len(src)]
        if re.search(r"\bif\s*\(", linea) and re.search(r"action\s*==", linea):
            i = offset
            while i < len(src):
                c = src[i]
                if c == '"' or (c in "@$" and i + 1 < len(src) and src[i + 1] in '"@$'):
                    i = _fin_string(src, i)
                    continue
                if c == "{":
                    return src[inicio:_cerrar_llave(src, i) + 1]
                if c == ";":
                    return src[inicio:i + 1]
                i += 1
            return src[inicio:]
        resto = src[fin_linea if fin_linea >= 0 else offset:]
        m = re.search(r'\n\s*(case\s+"|default\s*:|_\s*=>|"[a-zA-Z]+\.[^"]*"\s*(=>|or\b)|.*action\s*==\s*")', resto)
        fin = (fin_linea + m.start()) if m else len(src)
        return src[inicio:fin]


# =============================================================== comparación

def version_photino(fuente):
    try:
        m = re.search(r"<Version>\s*([^<]+?)\s*</Version>", fuente.read(CSPROJ))
        return m.group(1) if m else None
    except (KeyError, RuntimeError):
        return None


def comparar(inv, web_actions, baseline):
    web = {a["accion"]: a for a in web_actions.get("acciones", [])}
    base = baseline.get("acciones", {})
    acciones = sorted(set(inv.frontend) | set(inv.backend) | set(web))
    filas = []
    for a in acciones:
        en_front = a in inv.frontend
        en_back = a in inv.backend
        en_web = a in web
        fila = {"accion": a, "frontend": sorted(inv.frontend.get(a, [])),
                "handler": inv.enrutar(a) if en_back else None, "web": web.get(a), "motivo": None,
                "huella": None, "huellaValidada": base.get(a, {}).get("huella")}
        if en_web:
            if not (en_front and en_back):
                fila["estado"] = "SOLO_WEB"
                fila["motivo"] = "Photino ya no la " + ("atiende" if not en_back else "usa en el frontend")
            else:
                h, error, componentes = inv.huella(a)
                hf, error_f = inv.huella_frontend(a)
                fila["huella"] = h
                fila["huellaFrontend"] = hf
                fila["componentesHuella"] = componentes
                validada = "(%s)" % (base.get(a, {}).get("photinoVersion") or base.get(a, {}).get("photinoCommit", "?")[:7])
                if h is None:
                    fila["estado"], fila["motivo"] = "REVISAR", "no se pudo calcular la huella: " + error
                elif hf is None:
                    fila["estado"], fila["motivo"] = "REVISAR", "no se pudo calcular la huella del frontend: " + error_f
                elif a not in base:
                    fila["estado"], fila["motivo"] = "REVISAR", "sin huella validada (baseline)"
                elif base[a].get("huella") != h:
                    fila["estado"], fila["motivo"] = "REVISAR", "la lógica C# cambió desde la validación " + validada
                elif not base[a].get("huellaFrontend"):
                    fila["estado"], fila["motivo"] = "REVISAR", "sin huella del frontend validada (baseline)"
                elif base[a]["huellaFrontend"] != hf:
                    fila["estado"], fila["motivo"] = "REVISAR", "el llamado/payload del frontend cambió desde la validación " + validada
                else:
                    fila["estado"] = "COMPATIBLE"
        elif en_front and en_back:
            fila["estado"] = "PENDIENTE"
        elif en_front:
            fila["estado"] = "PHOTINO_SIN_HANDLER"
        else:
            fila["estado"] = "NO_USADA"
        filas.append(fila)
    return filas


def construir_reporte(fuente, inv, filas, web_actions, baseline):
    cuenta = defaultdict(int)
    for f in filas:
        cuenta[f["estado"]] += 1
    total_front = sum(1 for f in filas if f["frontend"])
    compatibles = cuenta["COMPATIBLE"]
    bloqueos = [f for f in filas if f["estado"] in BLOQUEANTES]
    version = version_photino(fuente)
    return {
        "generado": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "photino": {"version": version, "commit": fuente.commit, "commitFecha": fuente.commit_date},
        "web": {"accionesHabilitadas": sorted(a["accion"] for a in web_actions.get("acciones", []))},
        "baseline": {"photinoCommit": baseline.get("photinoCommit"), "photinoVersion": baseline.get("photinoVersion")},
        "resumen": {
            "texto": "Photino %s · Web compatible %d/%d" % (version or "?", compatibles, total_front),
            "accionesFrontend": total_front,
            "compatibles": compatibles,
            "pendientes": cuenta["PENDIENTE"],
            "revisar": cuenta["REVISAR"],
            "soloWeb": cuenta["SOLO_WEB"],
            "photinoSinHandler": cuenta["PHOTINO_SIN_HANDLER"],
            "noUsadas": cuenta["NO_USADA"],
            "dinamicasSinResolver": sum(1 for d in inv.dinamicas if not d["resueltas"]),
        },
        "bloqueante": bool(bloqueos),
        "motivosBloqueo": ["%s: %s (%s)" % (f["estado"], f["accion"], f["motivo"]) for f in bloqueos],
        "acciones": filas,
        "dinamicas": inv.dinamicas,
    }


def escribir_markdown(reporte, ruta):
    r = reporte["resumen"]
    orden = {"REVISAR": 0, "SOLO_WEB": 1, "COMPATIBLE": 2, "PENDIENTE": 3, "PHOTINO_SIN_HANDLER": 4, "NO_USADA": 5}
    lineas = [
        "# Contract check Photino ↔ QCC Web",
        "",
        "**%s**" % r["texto"],
        "",
        "- Photino: `%s` @ `%s` (%s)" % (reporte["photino"]["version"], reporte["photino"]["commit"][:12],
                                         reporte["photino"]["commitFecha"]),
        "- Generado: %s" % reporte["generado"],
        "- Release web: **%s**" % ("BLOQUEADO" if reporte["bloqueante"] else "PERMITIDO"),
        "- Pendientes: %d · Revisar: %d · Solo web: %d · Photino sin handler: %d · No usadas: %d · "
        "Dinámicas sin resolver: %d" % (r["pendientes"], r["revisar"], r["soloWeb"], r["photinoSinHandler"],
                                        r["noUsadas"], r["dinamicasSinResolver"]),
        "",
    ]
    if reporte["motivosBloqueo"]:
        lineas += ["## Bloqueos", ""] + ["- %s" % m for m in reporte["motivosBloqueo"]] + [""]
    lineas += ["## Acciones", "", "| ACTION | PHOTINO | WEB | ESTADO | detalle |", "|---|---|---|---|---|"]
    for f in sorted(reporte["acciones"], key=lambda x: (orden.get(x["estado"], 9), x["accion"])):
        photino = "OK" if (f["frontend"] and f["handler"]) else ("solo handler" if f["handler"] else
                                                                  ("solo frontend" if f["frontend"] else "NO"))
        via = (f["web"] or {}).get("via") or ""
        detalle = f["motivo"] or ("vía " + via if via and via != "bridge" else "")
        lineas.append("| `%s` | %s | %s | %s | %s |" % (f["accion"], photino, "OK" if f["web"] else "NO",
                                                       f["estado"], detalle.replace("|", "/")))
    with open(ruta, "w", encoding="utf-8", newline="\n") as fh:
        fh.write("\n".join(lineas) + "\n")


def aprobar(baseline, filas, acciones, fuente):
    """Registra como validada la huella ACTUAL de las acciones indicadas (tras revisión humana)."""
    por_accion = {f["accion"]: f for f in filas}
    nuevas = dict(baseline.get("acciones", {}))
    for a in acciones:
        f = por_accion.get(a)
        if not f or not f["web"] or not f["huella"] or not f.get("huellaFrontend"):
            raise ValueError("No se puede aprobar '%s': no está habilitada en web o no tiene huella calculable" % a)
        nuevas[a] = {"huella": f["huella"], "huellaFrontend": f["huellaFrontend"], "handler": f["handler"],
                     "photinoCommit": fuente.commit,
                     "photinoVersion": version_photino(fuente),
                     "aprobado": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")}
    return {"descripcion": "Huellas validadas por acción habilitada en web: lógica C# de Photino (huella) y "
                           "llamado/payload del frontend (huellaFrontend). Solo se actualiza con --aprobar tras "
                           "revisar el cambio.",
            "photinoCommit": fuente.commit, "photinoVersion": version_photino(fuente), "acciones": nuevas}


def acciones_navegador(ruta_shim):
    """
    Claves de ACCIONES_NAVEGADOR en web-bridge.js: acciones de Photino que la web resuelve 100%
    en el navegador (sin gateway). El shim es la única fuente de verdad de esta lista.
    """
    with open(ruta_shim, encoding="utf-8") as fh:
        codigo = fh.read()
    m = re.search(r"var\s+ACCIONES_NAVEGADOR\s*=\s*\{(.*?)\n\s*\};", codigo, re.S)
    if not m:
        raise ValueError("No se encontró ACCIONES_NAVEGADOR en %s" % ruta_shim)
    cuerpo = re.sub(r"//[^\n]*", "", m.group(1))
    return re.findall(r'"([a-zA-Z]+(?:\.[a-zA-Z0-9_]+)+)"\s*:', cuerpo)


def agregar_acciones_navegador(web_actions, ruta_shim):
    existentes = {a["accion"] for a in web_actions.get("acciones", [])}
    nuevas = list(web_actions.get("acciones", []))
    for accion in acciones_navegador(ruta_shim):
        if accion in existentes:
            raise ValueError("'%s' está declarada a la vez en ActionPolicy/AuthController y en el navegador" % accion)
        nuevas.append({"accion": accion, "via": "navegador (web-bridge.js)"})
    return dict(web_actions, acciones=nuevas)


def leer_json(ruta, por_defecto=None):
    if not ruta or not os.path.exists(ruta):
        if por_defecto is not None:
            return por_defecto
        raise FileNotFoundError(ruta)
    with open(ruta, encoding="utf-8-sig") as fh:
        return json.load(fh)


def main(argv=None):
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
        sys.stderr.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--photino-repo", required=True)
    ap.add_argument("--commit", required=True)
    ap.add_argument("--web-actions", required=True, help="export de la ActionPolicy (web-actions.json)")
    ap.add_argument("--baseline", required=True, help="contract/baseline.json (versionado en el repo web)")
    ap.add_argument("--out", required=True, help="carpeta de salida de contract-report.{json,md}")
    ap.add_argument("--web-shim", help="web-shim/web-bridge.js: acciones resueltas en el navegador")
    ap.add_argument("--aprobar", nargs="*", default=[], help="acciones cuya huella actual se valida")
    args = ap.parse_args(argv)

    try:
        fuente = GitSource(args.photino_repo, args.commit)
        web_actions = leer_json(args.web_actions)
        if args.web_shim:
            web_actions = agregar_acciones_navegador(web_actions, args.web_shim)
        baseline = leer_json(args.baseline, {"acciones": {}})
        inv = Inventario(fuente)
        filas = comparar(inv, web_actions, baseline)
        if args.aprobar:
            baseline = aprobar(baseline, filas, args.aprobar, fuente)
            with open(args.baseline, "w", encoding="utf-8", newline="\n") as fh:
                json.dump(baseline, fh, indent=2, ensure_ascii=False)
                fh.write("\n")
            filas = comparar(inv, web_actions, baseline)
        reporte = construir_reporte(fuente, inv, filas, web_actions, baseline)
    except (RuntimeError, FileNotFoundError, ValueError, KeyError) as e:
        print("ERROR contract check: %s" % e, file=sys.stderr)
        return 1

    os.makedirs(args.out, exist_ok=True)
    with open(os.path.join(args.out, "contract-report.json"), "w", encoding="utf-8", newline="\n") as fh:
        json.dump(reporte, fh, indent=2, ensure_ascii=False)
        fh.write("\n")
    escribir_markdown(reporte, os.path.join(args.out, "contract-report.md"))

    print(reporte["resumen"]["texto"])
    print("Release web: %s" % ("BLOQUEADO" if reporte["bloqueante"] else "PERMITIDO"))
    for m in reporte["motivosBloqueo"]:
        print("  - " + m)
    return 2 if reporte["bloqueante"] else 0


if __name__ == "__main__":
    sys.exit(main())
