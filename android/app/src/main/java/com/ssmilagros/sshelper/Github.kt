package com.ssmilagros.sshelper

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class Cfg(val owner: String, val repo: String, val branch: String, val token: String, val alts: List<String> = emptyList()) {
    fun withRepo(r: String): Cfg = Cfg(owner, r, branch, token, alts)
}

class Resp(val status: Int, val body: String, val err: String = "") {
    fun json(): JSONObject? = try { JSONObject(body) } catch (e: Exception) { null }
}

class CodeCheck(val ok: Boolean?, val msg: String, val sha: String?, val info: JSONObject, val cfg: Cfg? = null)

object Github {
    const val VERSION = "m1.4"
    private const val API = "https://api.github.com"

    fun b64(s: String): String = Base64.encodeToString(s.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    fun loadCfg(ctx: Context): Pair<Cfg?, String> {
        return try {
            val txt = ctx.assets.open("github.json").bufferedReader().use { it.readText() }.trimStart('\uFEFF')
            val j = JSONObject(txt)
            val owner = j.optString("owner").trim()
            val repo = j.optString("repo").trim()
            val token = j.optString("token").trim().replace(" ", "").replace("\n", "")
            var branch = j.optString("branch", "main").trim()
            if (branch.isEmpty()) branch = "main"
            if (owner.isEmpty() || repo.isEmpty() || token.isEmpty()) {
                Pair(null, "La configuración de la app está incompleta. Avisa al staff.")
            } else {
                val alts = ArrayList<String>()
                j.optJSONArray("repos")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val r = arr.optString(i).trim().substringAfterLast('/')
                        if (r.isNotEmpty()) alts.add(r)
                    }
                }
                Pair(Cfg(owner, repo, branch, token, alts), "")
            }
        } catch (e: Exception) {
            Pair(null, "No se encontró la configuración dentro de la app. Avisa al staff.")
        }
    }

    fun request(cfg: Cfg, method: String, path: String, body: JSONObject? = null, timeout: Int = 20000): Resp {
        var conn: HttpURLConnection? = null
        return try {
            val suffix = if (path.isEmpty()) "" else "/$path"
            val url = URL("$API/repos/${cfg.owner}/${cfg.repo}$suffix")
            conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = method
            conn.connectTimeout = timeout
            conn.readTimeout = timeout
            conn.setRequestProperty("Authorization", "Bearer ${cfg.token}")
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "SSHelper-Mobile/$VERSION")
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..399) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            Resp(code, text)
        } catch (e: Exception) {
            Resp(0, "", "${e.javaClass.simpleName}: ${(e.message ?: "").take(80)}")
        } finally {
            conn?.disconnect()
        }
    }

    /** Descarga simple (para la reputación de IP). */
    fun httpGet(url: String, timeout: Int = 7000): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = timeout
            conn.readTimeout = timeout
            conn.setRequestProperty("User-Agent", "SSHelper-Mobile/$VERSION")
            if (conn.responseCode in 200..299) conn.inputStream.bufferedReader().use { it.readText() } else null
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    /** Reputación de la IP pública (VPN/proxy/hosting). No se guarda la IP. */
    fun ipReputation(): JSONObject? {
        val txt = httpGet("http://ip-api.com/json/?fields=status,country,city,timezone,offset,isp,org,proxy,hosting") ?: return null
        return try {
            val j = JSONObject(txt)
            if (j.optString("status") == "success") j else null
        } catch (e: Exception) {
            null
        }
    }

    fun normalizeCode(raw: String): String {
        var s = raw.replace(Regex("[^A-Za-z0-9]"), "").uppercase()
        if (s.length == 10 && s.startsWith("SS")) s = s.substring(2)
        // el alfabeto de los códigos no tiene 0/O ni 1/I: si el jugador los confunde, se corrigen
        s = s.replace('0', 'O').replace('1', 'I')
        return if (s.length == 8) "SS-${s.substring(0, 4)}-${s.substring(4)}" else ""
    }

    fun platformOf(code: String, info: JSONObject): String {
        val p = info.optString("platform", "").lowercase()
        if (p == "java" || p == "bedrock" || p == "mobile") return p
        return when (code.getOrNull(3)) {
            'B' -> "bedrock"
            'M' -> "mobile"
            else -> "java"
        }
    }

    /** Repos donde se busca el código: el configurado, los extra de github.json ("repos") y los nombres usuales del proyecto. */
    private fun candidateRepos(cfg: Cfg): List<String> =
        (listOf(cfg.repo) + cfg.alts + listOf("ss-datos", "ss-milagros")).filter { it.isNotBlank() }.distinct()

    private fun lookup(cfg: Cfg, code: String): Resp {
        // 1.er intento con la rama configurada; si da 404 se prueba sin rama (la por defecto) y con "master"/"main"
        val branches = listOf(cfg.branch, "", if (cfg.branch == "main") "master" else "main").distinct()
        var r = Resp(0, "")
        for (b in branches) {
            val ref = if (b.isEmpty()) "" else "?ref=" + URLEncoder.encode(b, "UTF-8")
            r = request(cfg, "GET", "contents/codes/$code.json$ref")
            if (r.status != 404) break
        }
        return r
    }

    fun validateCode(cfg: Cfg, code: String): CodeCheck {
        val repos = candidateRepos(cfg)
        var found: Resp? = null
        var foundCfg: Cfg = cfg
        var firstErr: Resp? = null
        val tried = ArrayList<String>()
        for (repo in repos) {
            val c = cfg.withRepo(repo)
            val r = lookup(c, code)
            tried.add(repo)
            if (r.status == 200) { found = r; foundCfg = c; break }
            if (r.status == 401) { firstErr = r; break }               // token inválido: no tiene sentido seguir
            if (r.status != 404 && firstErr == null) firstErr = r      // 403 (sin permiso a ese repo), 5xx, sin red...
        }
        val r = found ?: firstErr ?: Resp(404, "")
        val gh = r.json()?.optString("message", "").orEmpty()
        return when {
            found != null -> {
                val j = found.json()
                var info = JSONObject()
                try {
                    val raw = j?.optString("content", "") ?: ""
                    info = JSONObject(String(Base64.decode(raw, Base64.DEFAULT), Charsets.UTF_8))
                } catch (e: Exception) {
                    // código sin contenido legible: se acepta igual
                }
                if (info.optString("status") == "done") {
                    CodeCheck(false, "Ese código ya fue usado. Pide uno nuevo al staff.", null, info, foundCfg)
                } else {
                    CodeCheck(true, "", j?.optString("sha"), info, foundCfg)
                }
            }
            r.status == 401 ->
                CodeCheck(null, "El servidor rechazó el token de la app (HTTP 401${if (gh.isNotEmpty()) ": $gh" else ""}). " +
                    "El token venció o es incorrecto. Avisa al staff para recompilar la app.", null, JSONObject())
            r.status == 403 ->
                CodeCheck(null, "El token no tiene permiso para leer ${cfg.owner}/${tried.joinToString(", ")} (HTTP 403${if (gh.isNotEmpty()) ": $gh" else ""}). " +
                    "Avisa al staff: el token debe tener acceso Contents (lectura y escritura) al repo del dashboard.", null, JSONObject())
            r.status == 404 -> CodeCheck(false,
                "Código no válido. Revisa que esté bien escrito.\n(Buscado: $code en ${tried.joinToString(", ") { "${cfg.owner}/$it" }}). " +
                    "Si el staff lo generó recién, confirma que el dashboard usa uno de esos repos y que el token de la app tiene acceso a él.", null, JSONObject())
            else -> CodeCheck(
                null,
                "No se pudo contactar con el servidor (${if (r.status == 0) "sin conexión" else r.status.toString()}" +
                    "${if (r.err.isNotEmpty()) " · ${r.err}" else ""}). Revisa tu internet.",
                null, JSONObject()
            )
        }
    }

    /** Firmas extra que el staff sube a config/signatures.json (sin recompilar la app). */
    fun fetchRemoteSigs(cfg: Cfg): JSONObject? {
        val q = URLEncoder.encode(cfg.branch, "UTF-8")
        val r = request(cfg, "GET", "contents/config/signatures.json?ref=$q", null, 10000)
        if (r.status != 200) return null
        return try {
            val raw = r.json()?.optString("content", "") ?: ""
            JSONObject(String(Base64.decode(raw, Base64.DEFAULT), Charsets.UTF_8))
        } catch (e: Exception) {
            null
        }
    }

    class UploadResult(val ok: Boolean, val path: String, val why: String)

    fun uploadReport(cfg: Cfg, report: JSONObject, code: String): UploadResult {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val path = "data/${code}_$stamp.json"
        val body = JSONObject()
            .put("message", "SS $VERSION: $code")
            .put("content", b64(report.toString()))
            .put("branch", cfg.branch)
        var last = ""
        for (attempt in 0 until 3) {
            val r = request(cfg, "PUT", "contents/$path", body, 30000)
            if (r.status == 200 || r.status == 201) return UploadResult(true, path, "")
            last = "HTTP ${r.status}"
            if (r.status == 401 || r.status == 403 || r.status == 404 || r.status == 422) break
            Thread.sleep(2000L * (attempt + 1))
        }
        return UploadResult(false, path, last)
    }

    fun markCodeDone(cfg: Cfg, code: String, sha: String?, info: JSONObject, report: JSONObject, path: String) {
        try {
            val upd = JSONObject(info.toString())
            upd.put("status", "done")
            upd.put("completed_at", report.optString("timestamp"))
            upd.put("verdict", report.optString("verdict"))
            upd.put("scan_file", path)
            upd.put("nick", report.optJSONObject("minecraft")?.optString("nick") ?: "?")
            val body = JSONObject()
                .put("message", "SS: $code usado")
                .put("content", b64(upd.toString()))
                .put("branch", cfg.branch)
            if (!sha.isNullOrEmpty()) body.put("sha", sha)
            request(cfg, "PUT", "contents/codes/$code.json", body, 20000)
        } catch (e: Exception) {
            // si no se puede marcar, el reporte ya está subido; el dashboard lo empareja por código
        }
    }
}
