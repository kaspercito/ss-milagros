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

class Cfg(val owner: String, val repo: String, val branch: String, val token: String)

class Resp(val status: Int, val body: String, val err: String = "") {
    fun json(): JSONObject? = try { JSONObject(body) } catch (e: Exception) { null }
}

class CodeCheck(val ok: Boolean?, val msg: String, val sha: String?, val info: JSONObject)

object Github {
    const val VERSION = "m1.1"
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
                Pair(Cfg(owner, repo, branch, token), "")
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

    fun validateCode(cfg: Cfg, code: String): CodeCheck {
        // 1.er intento con la rama configurada; si da 404 se prueba sin rama (la por defecto) y con "master"
        val branches = listOf(cfg.branch, "", if (cfg.branch == "main") "master" else "main").distinct()
        var r = Resp(0, "")
        for (b in branches) {
            val ref = if (b.isEmpty()) "" else "?ref=" + URLEncoder.encode(b, "UTF-8")
            r = request(cfg, "GET", "contents/codes/$code.json$ref")
            if (r.status != 404) break
        }
        val gh = r.json()?.optString("message", "").orEmpty()
        return when {
            r.status == 200 -> {
                val j = r.json()
                var info = JSONObject()
                try {
                    val raw = j?.optString("content", "") ?: ""
                    info = JSONObject(String(Base64.decode(raw, Base64.DEFAULT), Charsets.UTF_8))
                } catch (e: Exception) {
                    // código sin contenido legible: se acepta igual
                }
                if (info.optString("status") == "done") {
                    CodeCheck(false, "Ese código ya fue usado. Pide uno nuevo al staff.", null, info)
                } else {
                    CodeCheck(true, "", j?.optString("sha"), info)
                }
            }
            r.status == 404 -> CodeCheck(false,
                "Código no válido. Revisa que esté bien escrito.\n(Buscado: $code en ${cfg.owner}/${cfg.repo}). " +
                    "Si el staff lo generó recién, confirma que el dashboard y la app usan el MISMO repo.", null, JSONObject())
            r.status == 401 || r.status == 403 ->
                CodeCheck(null, "El servidor rechazó la autorización (HTTP ${r.status}${if (gh.isNotEmpty()) ": $gh" else ""}). " +
                    "El token de la app venció o no tiene permiso. Avisa al staff para recompilar la app.", null, JSONObject())
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
