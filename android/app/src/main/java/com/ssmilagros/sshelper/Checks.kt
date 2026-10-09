package com.ssmilagros.sshelper

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class Finding(val level: String, val module: String, val title: String, val detail: String = "", val fp: Boolean = false)

class ScanCtx(val app: Context) {
    val findings = ArrayList<Finding>()
    private val seen = HashSet<String>()
    var country = "No disponible"
    var city = ""
    var nick = "?"
    var declaredNick = ""   // nick que escribió el jugador en la app
    val alts = LinkedHashSet<String>()   // nicks encontrados en logs/cuentas del launcher

    fun addNick(n: String) {
        if (n.isBlank()) return
        alts.add(n)
        if (nick == "?") nick = n
    }
    var vm = "No detectado"
    var vpn = "No detectada"
    var bootTime = ""
    var uptime = ""
    var filesScanned = 0
    var jarsInspected = 0
    var logSources = ArrayList<String>()
    var ipRep: JSONObject? = null
    val mcApps = ArrayList<JSONObject>()   // Minecraft/launchers instalados: versión + TIPO

    fun add(level: String, module: String, title: String, detail: String = "", fp: Boolean = false) {
        val key = "$level|$module|$title|${detail.take(200)}"
        if (!seen.add(key)) return
        if (findings.size >= 400) return
        findings.add(Finding(level, module, title.take(200), detail.take(1500), fp))
    }
}

class Hit(val name: String, val cat: String, val level: String, val fp: Boolean)

/** Busca firmas ignorando separadores ("meteor client" = "meteorclient"). Las cortas/ambiguas exigen límites de palabra. */
class Sigs(remote: JSONObject?) {
    private class Item(
        val name: String, val nn: String, val cat: String, val level: String,
        val fp: Boolean, val strict: Boolean, val re: Regex?
    )

    private val items = ArrayList<Item>()
    val hashes = HashSet<String>()

    init {
        val cats = linkedMapOf<String, MutableList<String>>(
            "strong" to SigData.strong.toMutableList(),
            "cheatapps" to SigData.cheatapps.toMutableList(),
            "weak" to SigData.weak.toMutableList(),
            "tools" to SigData.tools.toMutableList(),
            "macro" to SigData.macro.toMutableList(),
            "remote" to SigData.remote.toMutableList(),
            "ambiguous" to SigData.ambiguous.toMutableList()
        )
        if (remote != null) {
            val h = remote.optJSONArray("hashes")
            if (h != null) {
                for (i in 0 until h.length()) hashes.add(h.optString(i).trim().lowercase())
            }
            for ((k, list) in cats) {
                val a = remote.optJSONArray(k) ?: continue
                for (i in 0 until a.length()) {
                    val v = a.optString(i)
                    if (v.isNotBlank()) list.add(v)
                }
            }
        }
        for ((cat, names) in cats) {
            for (name in names.distinct()) {
                val nn = norm(name)
                if (nn.length < 3) continue
                val strict = cat == "ambiguous" || nn.length < 5
                val re: Regex? = if (strict) {
                    Regex("(?<![a-z0-9])" + nn.map { Regex.escape(it.toString()) }.joinToString("[\\s_\\-.]*") + "(?![a-z0-9])")
                } else null
                val level = if (cat == "strong" || cat == "cheatapps") "red" else "yellow"
                items.add(Item(name, nn, cat, level, cat == "ambiguous", strict, re))
            }
        }
    }

    fun find(text: String): List<Hit> {
        if (text.isEmpty()) return emptyList()
        val nt = norm(text)
        val low = text.lowercase()
        val out = ArrayList<Hit>()
        val seen = HashSet<String>()
        for (i in items) {
            if (!nt.contains(i.nn) || seen.contains(i.nn)) continue
            if (i.strict && i.re != null && !i.re.containsMatchIn(low)) continue
            seen.add(i.nn)
            out.add(Hit(i.name, i.cat, i.level, i.fp))
        }
        return out
    }

    companion object {
        private val NON_ALNUM = Regex("[^a-z0-9]")
        fun norm(s: String): String = NON_ALNUM.replace(s.lowercase(), "")
    }
}

class Step(val label: String, val fn: () -> Unit)

object Checks {
    internal val KNOWN_PKG = mapOf(
        "catch_.me_.if_.you_.can_" to Pair("GameGuardian", "red"),
        "com.cih.game_cih" to Pair("CIH Game Hacker", "red"),
        "com.dimonvideo.luckypatcher" to Pair("Lucky Patcher", "red"),
        "com.chelpus.lackypatch" to Pair("Lucky Patcher", "red"),
        "org.sbtools.gamehack" to Pair("SB Game Hacker", "red"),
        "com.xmodgame" to Pair("XModGames", "red"),
        "com.topjohnwu.magisk" to Pair("Magisk (root)", "yellow"),
        "me.weishu.kernelsu" to Pair("KernelSU (root)", "yellow"),
        "eu.chainfire.supersu" to Pair("SuperSU (root)", "yellow"),
        "org.lsposed.manager" to Pair("LSPosed (Xposed)", "yellow"),
        "de.robv.android.xposed.installer" to Pair("Xposed Installer", "yellow"),
        "com.lbe.parallel.intl" to Pair("Parallel Space (clonador)", "yellow"),
        "com.excelliance.dualaid" to Pair("Dual Space (clonador)", "yellow"),
        "com.excelliance.multiaccounts" to Pair("Multi Accounts (clonador)", "yellow"),
        "io.va.exposed" to Pair("VirtualXposed (entorno virtual)", "yellow"),
        "com.lody.virtual" to Pair("VirtualApp (entorno virtual)", "yellow"),
        "com.applisto.appcloner" to Pair("App Cloner (clonador)", "yellow"),
        "com.oasisfeng.island" to Pair("Island (perfil de trabajo)", "yellow"),
        "com.anydesk.anydeskandroid" to Pair("AnyDesk (control remoto)", "yellow"),
        "com.teamviewer.quicksupport.market" to Pair("TeamViewer QuickSupport (control remoto)", "yellow")
    )
    private val EMU_PREFIX = listOf("com.bluestacks", "com.bignox", "com.vphone", "com.microvirt", "com.ldmnq", "com.genymotion")
    private val VPN_PKG = listOf(
        "nordvpn", "protonvpn", "expressvpn", "mullvad", "surfshark", "windscribe", "tunnelbear", "cyberghost",
        "hotspotshield", "privateinternetaccess", "wireguard", "openvpn", "psiphon", "ipvanish", "purevpn",
        "hidemyass", "vyprvpn", "urbanvpn", "onedotonedotonedotone"
    )
    private val STORES = listOf("com.android.vending", "com.sec.android.app.samsungapps", "com.amazon.venezia", "com.huawei.appmarket")

    private fun fmt(ms: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(ms))

    private fun fmtDur(sec: Long): String {
        val d = sec / 86400
        val h = (sec % 86400) / 3600
        val m = (sec % 3600) / 60
        return if (d > 0) "${d}d ${h}h" else if (h > 0) "${h}h ${m}min" else "${m}min"
    }

    @Suppress("DEPRECATION")
    private fun installedApps(pm: PackageManager): List<ApplicationInfo> =
        if (Build.VERSION.SDK_INT >= 33) pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0L))
        else pm.getInstalledApplications(0)

    @Suppress("DEPRECATION")
    private fun pkgInfo(pm: PackageManager, pkg: String): PackageInfo? = try {
        if (Build.VERSION.SDK_INT >= 33) pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0L))
        else pm.getPackageInfo(pkg, 0)
    } catch (e: Exception) {
        null
    }

    @Suppress("DEPRECATION")
    private fun installerOf(pm: PackageManager, pkg: String): String? = try {
        if (Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(pkg).installingPackageName
        else pm.getInstallerPackageName(pkg)
    } catch (e: Exception) {
        null
    }

    private fun isUserApp(ai: ApplicationInfo): Boolean =
        (ai.flags and ApplicationInfo.FLAG_SYSTEM) == 0 || (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

    internal fun isMcLauncher(pkg: String): Boolean =
        pkg.startsWith("net.kdt.pojavlaunch") || pkg.startsWith("com.movtery.zalithlauncher") ||
            pkg == "git.artdeell.mojo" || pkg.startsWith("com.tungsten.fcl")

    fun pojavInstalled(ctx: Context): Boolean {
        return try {
            installedApps(ctx.packageManager).any { isMcLauncher(it.packageName) }
        } catch (e: Exception) {
            false
        }
    }

    fun run(sc: ScanCtx, sigs: Sigs, pojavTree: Uri?, progress: (Int, Int) -> Unit) {
        val steps = ArrayList<Step>()
        steps.add(Step("Dispositivo") { stepDevice(sc) })
        steps.add(Step("VPN") { stepVpn(sc) })
        steps.add(Step("Emulador / entorno virtual") { stepEnv(sc) })
        steps.add(Step("Root y depuración") { stepRoot(sc) })
        steps.add(Step("Accesibilidad") { stepAccessibility(sc, sigs) })
        steps.add(Step("Apps instaladas") { stepApps(sc, sigs) })
        val allFiles = Storage.hasAllFiles(sc.app)
        if (allFiles) {
            steps.add(Step("Launchers, descargas y archivos") { Storage.scan(sc.app, sigs, sc) })
        } else if (pojavTree != null) {
            steps.add(Step("Mods y logs de PojavLauncher") { Pojav.scan(sc.app, pojavTree, sigs, sc) })
        } else {
            sc.add("info", "Almacenamiento", "No se dio acceso a los archivos", "No se revisaron launchers, mods ni descargas. Pídele que lo acepte o que lo muestre en la llamada.")
        }
        if (Storage.hasUsageAccess(sc.app)) {
            steps.add(Step("Uso reciente de apps") { Storage.usage(sc.app, sigs, sc) })
        } else {
            sc.add("info", "Uso de apps", "No se dio acceso al uso de apps", "No se pudo ver cuándo se abrió Minecraft ni qué apps se usaron esta semana.")
        }
        val total = steps.size
        for ((i, s) in steps.withIndex()) {
            progress(i, total)
            try {
                s.fn()
            } catch (e: Throwable) {
                sc.add("info", "Escáner", "El paso '${s.label}' no se pudo completar", "${e.javaClass.simpleName}: ${e.message}")
            }
            progress(i + 1, total)
        }
    }

    private fun stepDevice(sc: ScanCtx) {
        val up = SystemClock.elapsedRealtime()
        sc.bootTime = fmt(System.currentTimeMillis() - up)
        sc.uptime = fmtDur(up / 1000)
        val rep = Github.ipReputation()
        sc.ipRep = rep
        if (rep != null) {
            sc.country = rep.optString("country", "No disponible")
            sc.city = rep.optString("city", "")
        }
        sc.add(
            "info", "Dispositivo", "${Build.MANUFACTURER} ${Build.MODEL}",
            "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
                "Parche de seguridad: ${Build.VERSION.SECURITY_PATCH}\n" +
                "Idioma: ${Locale.getDefault()}\nZona horaria: ${TimeZone.getDefault().id}\n" +
                "Encendido desde: ${sc.bootTime} (${sc.uptime})"
        )
    }

    private fun stepVpn(sc: ScanCtx) {
        val cm = sc.app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        var active = false
        @Suppress("DEPRECATION")
        val nets = cm.allNetworks
        for (n in nets) {
            val caps = cm.getNetworkCapabilities(n)
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) active = true
        }
        val pm = sc.app.packageManager
        val apps = ArrayList<String>()
        for (ai in installedApps(pm)) {
            if (!isUserApp(ai)) continue
            val p = ai.packageName.lowercase()
            if (VPN_PKG.any { p.contains(it) }) apps.add("${ai.loadLabel(pm)} (${ai.packageName})")
        }
        val rep = sc.ipRep
        var flagged = false
        if (active) {
            sc.add("yellow", "VPN", "VPN activa en el celular", "Android informa que la conexión pasa por una VPN.\n" + apps.joinToString("\n"))
            flagged = true
        } else if (apps.isNotEmpty()) {
            sc.add("info", "VPN", "Apps de VPN instaladas (sin VPN activa ahora)", apps.joinToString("\n"))
        }
        if (rep != null) {
            if (rep.optBoolean("proxy")) {
                sc.add("yellow", "VPN", "La IP pública figura como VPN/proxy", "Proveedor: ${rep.optString("isp")}\nUna base de reputación de IP la marca como proxy/VPN.")
                flagged = true
            } else if (rep.optBoolean("hosting")) {
                sc.add("yellow", "VPN", "La IP pública es de un centro de datos/hosting", "Proveedor: ${rep.optString("isp")}\nSuele ser VPN o servidor.", true)
            }
            val off = rep.optInt("offset", Int.MIN_VALUE)
            val local = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000
            if (off != Int.MIN_VALUE && Math.abs(off - local) >= 3600) {
                sc.add(
                    "info", "VPN", "La zona horaria del teléfono no coincide con la de la IP",
                    "Teléfono: UTC${"%+.1f".format(local / 3600.0)} · IP (${rep.optString("timezone")}): UTC${"%+.1f".format(off / 3600.0)}\nPuede ser VPN o un viaje reciente.", true
                )
            }
        }
        sc.vpn = if (flagged) "Sí / probable" else "No detectada"
    }

    private fun stepEnv(sc: ScanCtx) {
        val hard = ArrayList<String>()
        val soft = ArrayList<String>()
        val fp = Build.FINGERPRINT.lowercase()
        val model = Build.MODEL.lowercase()
        val man = Build.MANUFACTURER.lowercase()
        val hw = Build.HARDWARE.lowercase()
        val prod = Build.PRODUCT.lowercase()
        if (fp.startsWith("generic") || fp.contains("emulator") || fp.contains("sdk_gphone")) hard.add("Fingerprint: ${Build.FINGERPRINT}")
        if (fp.startsWith("unknown")) soft.add("Fingerprint desconocido: ${Build.FINGERPRINT}")
        if (model.contains("emulator") || model.contains("android sdk built for") || model.contains("google_sdk")) hard.add("Modelo: ${Build.MODEL}")
        if (man.contains("genymotion")) hard.add("Fabricante: ${Build.MANUFACTURER}")
        if (hw.contains("goldfish") || hw.contains("ranchu") || hw.contains("vbox") || hw.contains("nox")) hard.add("Hardware: ${Build.HARDWARE}")
        if (prod.contains("sdk") || prod.contains("emulator") || prod.contains("vbox86")) hard.add("Producto: ${Build.PRODUCT}")
        val files = listOf("/dev/qemu_pipe", "/dev/socket/qemud", "/system/bin/qemu-props", "/system/lib/libdroid4x.so", "/system/bin/nox-prop", "/system/bin/microvirtd")
        for (f in files) if (File(f).exists()) hard.add("Archivo del sistema: $f")
        val sm = sc.app.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensors = sm.getSensorList(Sensor.TYPE_ALL).size
        if (sensors < 6) soft.add("Solo $sensors sensores (un teléfono real suele tener 15 o más)")

        var summary = "No detectado"
        if (hard.isNotEmpty()) {
            sc.add("red", "Emulador", "Emulador detectado", hard.joinToString("\n") + "\nEl juego corre en un PC/emulador, no en un teléfono real.")
            summary = "Emulador detectado"
        } else if (soft.isNotEmpty()) {
            sc.add("yellow", "Emulador", "Señales débiles de emulador", soft.joinToString("\n"), true)
            summary = "Posible"
        }

        // Entorno clonado/virtual: dónde corre ESTA app
        val dataDir = sc.app.applicationInfo.dataDir ?: ""
        if (!(dataDir.startsWith("/data/user/") || dataDir.startsWith("/data/data/"))) {
            sc.add("yellow", "Emulador", "La app corre dentro de un entorno virtual/clonado", "Ruta de datos: $dataDir\nLos espacios paralelos (Parallel Space, VirtualXposed…) ocultan apps y archivos.")
            summary = "Entorno virtual/clonado"
        }
        val userId = Process.myUid() / 100000
        if (userId != 0) {
            sc.add("yellow", "Emulador", "La app corre en un perfil secundario (id $userId)", "Carpeta segura, Dual Apps o perfil de trabajo: allí se puede tener un Minecraft aparte.", true)
        }
        sc.vm = summary
    }

    private fun stepRoot(sc: ScanCtx) {
        val su = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su", "/system/app/Superuser.apk", "/data/local/bin/su", "/data/local/xbin/su")
        val found = su.filter { File(it).exists() }
        val tags = Build.TAGS ?: ""
        if (found.isNotEmpty()) {
            sc.add("yellow", "Root", "Archivos de root encontrados", found.joinToString("\n") + "\nCon root se pueden inyectar/editar apps en ejecución.")
        } else if (tags.contains("test-keys")) {
            sc.add("yellow", "Root", "ROM con firma de pruebas (test-keys)", "Suele ser una ROM personalizada o con root.", true)
        }
        val cr = sc.app.contentResolver
        if (Settings.Global.getInt(cr, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1) {
            sc.add("info", "Depuración", "Opciones de desarrollador activadas", "Es normal en usuarios avanzados.", true)
        }
        if (Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED, 0) == 1) {
            sc.add("yellow", "Depuración", "Depuración USB (ADB) activada", "ADB permite automatizar toques, instalar APKs modificados y ejecutar comandos desde un PC.")
        }
    }

    private fun stepAccessibility(sc: ScanCtx, sigs: Sigs) {
        val raw = Settings.Secure.getString(sc.app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        if (raw.isBlank()) return
        val ok = listOf("talkback", "selecttospeak", "accessibility.menu", "voiceaccess", "switchaccess", "samsung.android.accessibility", "bixby")
        for (s in raw.split(":").filter { it.isNotBlank() }) {
            val low = s.lowercase()
            val hits = sigs.find(s)
            if (hits.isNotEmpty()) {
                sc.add("yellow", "Accesibilidad", "Servicio de accesibilidad de tipo autoclicker/macro: $s", "Firma: ${hits[0].name}\nLos autoclickers usan accesibilidad para tocar la pantalla solos.")
            } else if (ok.none { low.contains(it) }) {
                sc.add("info", "Accesibilidad", "Servicio de accesibilidad activo: $s", "Revísalo: algunas apps usan accesibilidad para automatizar toques.", true)
            }
        }
    }

    private fun stepApps(sc: ScanCtx, sigs: Sigs) {
        val pm = sc.app.packageManager
        val weekAgo = System.currentTimeMillis() - 7L * 86400000L
        val recent = ArrayList<String>()
        var bedrock = false
        var emu: String? = null
        for (ai in installedApps(pm)) {
            val pkg = ai.packageName
            if (pkg == sc.app.packageName || !isUserApp(ai)) continue
            sc.filesScanned++
            val label = ai.loadLabel(pm).toString()
            val low = pkg.lowercase()

            val known = KNOWN_PKG[pkg]
            if (known != null) {
                sc.add(known.second, "Apps", "App instalada: ${known.first}", "$label\n$pkg")
            } else {
                for (h in sigs.find("$label $pkg")) {
                    sc.add(if (h.fp) "yellow" else h.level, "Apps", "App con nombre de cheat/automatización: $label", "$pkg\nFirma: ${h.name}", h.fp)
                }
            }
            if (EMU_PREFIX.any { low.startsWith(it) }) emu = emu ?: label

            val isMc = pkg == "com.mojang.minecraftpe" || pkg == "com.mojang.minecrafttrialpe" || isMcLauncher(pkg) ||
                Regex("minecraft|pojav|zalith").containsMatchIn(label.lowercase())
            if (isMc) {
                val pi = pkgInfo(pm, pkg)
                val inst = installerOf(pm, pkg)
                val official = pkg == "com.mojang.minecraftpe" || pkg == "com.mojang.minecrafttrialpe" || isMcLauncher(pkg)
                sc.add(
                    "info", "Minecraft", "Instalado: $label",
                    "$pkg · v${pi?.versionName ?: "?"}\nInstalada: ${pi?.firstInstallTime?.let { fmt(it) } ?: "?"} · Actualizada: ${pi?.lastUpdateTime?.let { fmt(it) } ?: "?"}\nOrigen: ${inst ?: "APK / ADB (sin tienda)"}"
                )
                val tipo = when {
                    pkg == "com.mojang.minecraftpe" -> "Bedrock oficial (Mojang)"
                    pkg == "com.mojang.minecrafttrialpe" -> "Bedrock Trial (prueba)"
                    pkg.startsWith("net.kdt.pojavlaunch") -> "Java en Android · PojavLauncher"
                    pkg.startsWith("com.movtery.zalithlauncher") -> "Java en Android · ZalithLauncher"
                    pkg == "git.artdeell.mojo" -> "Java en Android · MojoLauncher"
                    pkg.startsWith("com.tungsten.fcl") -> "Java en Android · Fold Craft Launcher"
                    else -> "Paquete NO oficial / clon o launcher desconocido"
                }
                sc.mcApps.add(JSONObject().put("label", label).put("pkg", pkg).put("version", pi?.versionName ?: "?").put("tipo", tipo))
                if (pkg == "com.mojang.minecraftpe") {
                    bedrock = true
                    if (inst == null || STORES.none { it == inst }) {
                        sc.add("yellow", "Minecraft", "Minecraft Bedrock instalado fuera de una tienda oficial", "Origen: ${inst ?: "APK / ADB"}\nPuede ser una versión modificada (o legítima de otra tienda).", true)
                    }
                } else if (!official) {
                    sc.add("yellow", "Minecraft", "App de Minecraft con paquete no oficial: $label", "$pkg\nPosible versión modificada o clon.")
                }
            }
            val pi = pkgInfo(pm, pkg)
            if (pi != null && pi.firstInstallTime > weekAgo) recent.add("$label ($pkg)")
        }
        if (emu != null) {
            sc.add("red", "Emulador", "Apps de emulador instaladas: $emu", "Aplicaciones propias de BlueStacks/LDPlayer/Nox/Genymotion.")
            sc.vm = "Emulador detectado (apps de $emu)"
        }
        if (recent.isNotEmpty()) {
            sc.add("info", "Apps", "Apps instaladas en los últimos 7 días (${recent.size})", recent.take(40).joinToString("\n"))
        }
        if (bedrock) {
            sc.add("info", "Minecraft", "Bedrock móvil: contenido interno no accesible",
                "Android bloquea a otras apps leer los datos de Minecraft. Pide en llamada ver sus packs/mods y grabación de pantalla.")
        }
    }
}

object Report {
    private fun order(f: Finding): Int = (if (f.fp) 3 else 0) + when (f.level) { "red" -> 0; "yellow" -> 1; else -> 2 }

    fun build(sc: ScanCtx, code: String, durationSec: Int): JSONObject {
        // Nick: el que escribió el jugador; si los logs de Pojav dicen otro, es una señal a revisar.
        val detected = sc.alts.toList()
        if (sc.declaredNick.isNotEmpty()) {
            if (detected.isNotEmpty() && detected.none { it.equals(sc.declaredNick, ignoreCase = true) }) {
                sc.add("yellow", "Cuenta", "El nick escrito no coincide con el de los logs/cuentas",
                    "Escribió: ${sc.declaredNick} · En el celular: ${detected.joinToString(", ")}\nPuede ser otra cuenta o un error al escribir: pregúntale.")
            } else if (detected.size > 1) {
                sc.add("info", "Cuenta", "Hay varios nicks en el celular", detected.joinToString(", "), true)
            }
            sc.nick = sc.declaredNick
        } else if (detected.isNotEmpty()) {
            sc.add("info", "Cuenta", "No escribió su nick; se tomó del launcher", detected.joinToString(", "))
        } else {
            sc.add("info", "Cuenta", "Nick no encontrado", "No escribió su nick y no hay logs/cuentas legibles (Bedrock o sin acceso a archivos). Pídelo en la llamada.")
        }
        val real = sc.findings.filter { !it.fp && it.level != "info" }
        val verdict = when {
            real.any { it.level == "red" } -> "red"
            real.any { it.level == "yellow" } -> "yellow"
            else -> "green"
        }
        val fl = JSONArray()
        for (f in sc.findings.sortedBy { order(it) }) {
            fl.put(JSONObject().put("level", f.level).put("module", f.module).put("title", f.title).put("detail", f.detail).put("probable_fp", f.fp))
        }
        val counts = JSONObject()
            .put("red", real.count { it.level == "red" })
            .put("yellow", real.count { it.level == "yellow" })
            .put("fp", sc.findings.count { it.fp })
            .put("info", sc.findings.count { it.level == "info" && !it.fp })
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val pc = JSONObject()
            .put("hostname", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("user", "—")
            .put("os", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            .put("country", sc.country).put("city", "")
            .put("custom_os", JSONArray()).put("install_date", "")
            .put("boot_time", sc.bootTime).put("uptime", sc.uptime)
            .put("boots", JSONArray())
            .put("vm", sc.vm).put("vpn", sc.vpn)
        val mcMain = sc.mcApps.firstOrNull { it.optString("pkg") == "com.mojang.minecraftpe" } ?: sc.mcApps.firstOrNull()
        val mcVer = JSONObject()
        if (mcMain != null) {
            val extra = if (sc.mcApps.size > 1) " (+${sc.mcApps.size - 1} más: " + sc.mcApps.filter { it !== mcMain }.joinToString(", ") { it.optString("label") } + ")" else ""
            mcVer.put("game", mcMain.optString("version")).put("tipo", mcMain.optString("tipo") + extra)
                .put("raw", mcMain.optString("pkg")).put("loader", "").put("source", "app instalada en el celular")
                .put("installed", JSONArray(sc.mcApps.map { "${it.optString("label")} ${it.optString("version")} · ${it.optString("tipo")} · ${it.optString("pkg")}" }))
        }
        val mc = JSONObject()
            .put("nick", sc.nick).put("alts", JSONArray(sc.alts.filter { !it.equals(sc.nick, ignoreCase = true) })).put("runtime", "").put("start", "")
            .put("sessions", JSONArray()).put("log_sources", JSONArray(sc.logSources))
        val system = JSONObject()
            .put("recycle_bin", "—").put("recent_files", JSONArray()).put("executed", JSONArray())
        val stats = JSONObject()
            .put("files_scanned", sc.filesScanned).put("jars_inspected", sc.jarsInspected)
            .put("java_modules", 0).put("usn_lines", 0).put("memory_mb", 0)
        val rojos = JSONArray(); val amar = JSONArray()
        real.filter { it.level == "red" }.take(6).forEach { rojos.put("${it.module}: ${it.title}") }
        real.filter { it.level == "yellow" }.take(8).forEach { amar.put("${it.module}: ${it.title}") }
        amar.put("Prueba de clics: no existe en celular (hazla tú en la llamada si juega con mouse/teclado)")
        val vd = JSONObject()
            .put("nivel", verdict)
            .put("titulo", when (verdict) { "red" -> "🔴 INDICIO FUERTE"; "yellow" -> "🟡 REVISAR"; else -> "🟢 SIN INDICIOS" })
            .put("explicacion", when (verdict) {
                "red" -> "Hay apps o archivos en el celular que el uso normal no explica. Pídele que te muestre la app en pantalla compartida y que explique cada señal antes de sancionar."
                "yellow" -> "Hay cosas dudosas pero no concluyentes. Pídele pantalla compartida y pregunta por cada punto amarillo. Un solo amarillo no sanciona."
                else -> "No se encontraron señales de trampa. Android no deja leer dentro de Minecraft: pide ver sus packs/mods y una grabación de pantalla."
            })
            .put("rojos", rojos).put("amarillos", amar).put("lectura_prueba", JSONArray())
        return JSONObject()
            .put("version", Github.VERSION)
            .put("os_family", "android")
            .put("veredicto", vd)
            .put("platform", "mobile")
            .put("code", code)
            .put("timestamp", ts)
            .put("verdict", verdict)
            .put("duration", durationSec)
            .put("admin", true)
            .put("pc", pc).put("minecraft", mc).put("mc_version", mcVer).put("system", system)
            .put("timeline", JSONArray()).put("usb_events", JSONArray())
            .put("stats", stats)
            .put("findings", fl)
            .put("findings_count", counts)
    }
}
