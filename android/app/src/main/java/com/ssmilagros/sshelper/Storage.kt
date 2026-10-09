package com.ssmilagros.sshelper

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.Process
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Escaneo directo del almacenamiento (sin selector de carpetas) y del uso reciente de apps.
 * Solo funciona si el jugador aceptó y dio "acceso a todos los archivos" / "acceso a uso de apps".
 */
object Storage {
    private const val MODULE = "Almacenamiento"
    private const val MAX_JAR = 90_000_000L

    // Carpetas típicas de launchers de Minecraft Java en Android (relativas al almacenamiento interno)
    private val LAUNCHER_REL = listOf(
        "games/PojavLauncher", "games/ZalithLauncher", "games/MojoLauncher", "games/FCL", "games/FoldCraftLauncher",
        "PojavLauncher", "ZalithLauncher", "MojoLauncher", "FCL"
    )
    private val LAUNCHER_PKGS = listOf(
        "net.kdt.pojavlaunch", "net.kdt.pojavlaunch.debug", "com.movtery.zalithlauncher", "git.artdeell.mojo",
        "com.tungsten.fcl", "com.tungsten.fclite"
    )
    // Carpetas donde un jugador suele dejar cheats descargados
    private val GENERAL_REL = listOf(
        "Download", "Downloads", "Documents", "Telegram", "WhatsApp/Media/WhatsApp Documents",
        "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Documents", "Bluetooth", "Music", "Movies"
    )
    private val GENERAL_SKIP = setOf("cache", ".thumbnails", ".trash", ".cache", "thumbnails")
    private val NICK_RE = Regex("^[A-Za-z0-9_]{3,16}$")
    private val fmtDate = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    fun hasAllFiles(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else ctx.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    fun hasUsageAccess(ctx: Context): Boolean {
        return try {
            val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= 29)
                ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
            else ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
            mode == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            false
        }
    }

    private class Walker(val deadline: Long, val limit: Int) {
        var seen = 0
        fun walk(root: File, maxDepth: Int, skip: Set<String>, onFile: (File) -> Unit) {
            val q = ArrayDeque<Pair<File, Int>>()
            q.add(Pair(root, 0))
            while (q.isNotEmpty() && System.currentTimeMillis() < deadline && seen < limit) {
                val (dir, depth) = q.removeFirst()
                val list = try { dir.listFiles() } catch (e: Exception) { null } ?: continue
                for (f in list) {
                    seen++
                    if (f.isDirectory) {
                        if (depth < maxDepth && !skip.contains(f.name.lowercase())) q.add(Pair(f, depth + 1))
                    } else {
                        try { onFile(f) } catch (e: Exception) { /* archivo ilegible */ }
                    }
                }
            }
        }
    }

    fun scan(ctx: Context, sigs: Sigs, sc: ScanCtx, budgetMs: Long = 100000) {
        val root = Environment.getExternalStorageDirectory()
        val deadline = System.currentTimeMillis() + budgetMs
        fun rel(f: File) = f.path.removePrefix(root.path)

        // ── 1) launchers de Minecraft Java encontrados solos ──
        val launcherDirs = LinkedHashSet<File>()
        for (r in LAUNCHER_REL) File(root, r).let { if (it.isDirectory) launcherDirs.add(it) }
        File(root, "games").listFiles()?.filter { it.isDirectory }?.forEach { launcherDirs.add(it) }
        for (p in LAUNCHER_PKGS) {
            for (base in listOf("Android/data/$p/files", "Android/media/$p")) {
                val d = File(root, base)
                if (d.isDirectory && (d.listFiles()?.isNotEmpty() == true)) launcherDirs.add(d)
            }
        }
        if (launcherDirs.isEmpty()) {
            sc.add("info", MODULE, "No se encontró carpeta de launcher de Minecraft Java",
                "Se buscó en games/, PojavLauncher, ZalithLauncher, MojoLauncher, FCL y Android/data. " +
                    "Si el jugador usa Pojav, puede que sus datos estén en una ruta que Android 11+ no deja leer.")
        }
        for (dir in launcherDirs) {
            if (System.currentTimeMillis() > deadline) break
            scanLauncher(dir, sigs, sc, deadline, ::rel)
        }

        // ── 2) descargas y carpetas habituales ──
        val apks = ArrayList<String>()
        val w = Walker(deadline, 15000)
        val general = ArrayList<File>()
        for (r in GENERAL_REL) File(root, r).let { if (it.isDirectory) general.add(it) }
        // archivos sueltos en la raíz del almacenamiento
        root.listFiles()?.filter { it.isFile }?.forEach { analyzeFile(ctx, it, sigs, sc, apks, ::rel) }
        for (g in general) {
            w.walk(g, 4, GENERAL_SKIP) { f -> analyzeFile(ctx, f, sigs, sc, apks, ::rel) }
        }
        if (apks.isNotEmpty()) {
            sc.add("info", MODULE, "APK guardados en el teléfono (${apks.size})", apks.take(30).joinToString("\n"))
        }
        if (System.currentTimeMillis() > deadline) {
            sc.add("info", MODULE, "Revisión de almacenamiento parcial", "Se agotó el tiempo máximo.")
        }
    }

    private fun scanLauncher(dir: File, sigs: Sigs, sc: ScanCtx, deadline: Long, rel: (File) -> String) {
        sc.logSources.add("Launcher: ${rel(dir)}")
        val jars = ArrayList<File>()
        val logs = ArrayList<File>()
        val w = Walker(deadline, 8000)
        var any = false
        w.walk(dir, 6, Pojav.SKIP) { f ->
            any = true
            sc.filesScanned++
            val low = f.name.lowercase()
            for (h in sigs.find(f.name)) {
                sc.add(if (h.fp) "yellow" else h.level, MODULE, "Archivo con nombre de cheat: ${f.name}", "${rel(f)}\nFirma: ${h.name}", h.fp)
            }
            if (low.endsWith(".jar")) jars.add(f)
            if (low == "latest.log" || low == "latestlog.txt" || low == "latestlog.old" || low == "debug.log") logs.add(f)
            if (f.parentFile?.name?.lowercase() == "accounts") nickFromAccountFile(f)?.let { sc.addNick(it) }
        }
        if (!any) return

        val ordered = jars.sortedBy { if (it.path.lowercase().contains("/mods/")) 0 else 1 }.take(80)
        for (f in ordered) {
            if (System.currentTimeMillis() > deadline) {
                sc.add("info", MODULE, "Revisión de .jar parcial", "Se agotó el tiempo máximo.")
                break
            }
            if (f.length() > MAX_JAR) {
                sc.add("info", MODULE, "Jar omitido por tamaño: ${f.name}", rel(f))
                continue
            }
            Pojav.inspectJarStream(MODULE, f.name, rel(f), { FileInputStream(f) }, sigs, sc)
        }
        for (f in logs.sortedByDescending { it.lastModified() }.take(4)) {
            Pojav.processLog(MODULE, rel(f), readTail(f, 3_000_000), sigs, sc)
        }
    }

    private fun analyzeFile(ctx: Context, f: File, sigs: Sigs, sc: ScanCtx, apks: MutableList<String>, rel: (File) -> String) {
        sc.filesScanned++
        val low = f.name.lowercase()
        for (h in sigs.find(f.name)) {
            sc.add(if (h.fp) "yellow" else h.level, MODULE, "Archivo con nombre de cheat: ${f.name}", "${rel(f)}\nFirma: ${h.name}\nModificado: ${fmtDate.format(Date(f.lastModified()))}", h.fp)
        }
        when (low.substringAfterLast('.', "")) {
            "apk" -> analyzeApk(ctx, f, sigs, sc, apks, rel)
            "jar" -> if (f.length() <= MAX_JAR) Pojav.inspectJarStream(MODULE, f.name, rel(f), { FileInputStream(f) }, sigs, sc)
        }
    }

    private fun analyzeApk(ctx: Context, f: File, sigs: Sigs, sc: ScanCtx, apks: MutableList<String>, rel: (File) -> String) {
        val pm = ctx.packageManager
        var label = ""
        var pkg = ""
        try {
            val pi = pm.getPackageArchiveInfo(f.path, 0)
            val ai = pi?.applicationInfo
            if (pi != null && ai != null) {
                ai.sourceDir = f.path
                ai.publicSourceDir = f.path
                pkg = pi.packageName
                label = ai.loadLabel(pm).toString()
            }
        } catch (e: Throwable) {
            // apk ilegible
        }
        apks.add("${f.name} → ${label.ifEmpty { "?" }} ($pkg)")
        val known = Checks.KNOWN_PKG[pkg]
        if (known != null) {
            sc.add(known.second, MODULE, "APK de ${known.first} guardado en el teléfono", "${rel(f)}\n$label ($pkg)")
        } else if (label.isNotEmpty() || pkg.isNotEmpty()) {
            for (h in sigs.find("$label $pkg")) {
                sc.add(if (h.fp) "yellow" else h.level, MODULE, "APK con nombre de cheat/automatización: $label", "${rel(f)}\n$pkg\nFirma: ${h.name}", h.fp)
            }
        }
        if (f.length() <= 150_000_000L && sigs.hashes.isNotEmpty()) {
            val hash = sha256(f)
            if (hash != null && sigs.hashes.contains(hash)) {
                sc.add("red", MODULE, "APK con hash de cheat conocido: ${f.name}", "${rel(f)}\nSHA-256: $hash")
            }
        }
    }

    /** Lee solo el nombre de usuario de un archivo de cuenta del launcher (nunca tokens ni contraseñas). */
    private fun nickFromAccountFile(f: File): String? {
        if (f.length() > 200_000L) return null
        val base = f.name.substringBeforeLast('.')
        return try {
            val text = String(f.readBytes(), Charsets.UTF_8)
            val u = JSONObject(text).optString("username", "")
            if (NICK_RE.matches(u)) u else if (NICK_RE.matches(base)) base else null
        } catch (e: Exception) {
            if (NICK_RE.matches(base)) base else null
        }
    }

    private fun readTail(f: File, max: Int): String {
        return try {
            RandomAccessFile(f, "r").use { raf ->
                val len = raf.length()
                val n = minOf(len, max.toLong()).toInt()
                raf.seek(len - n)
                val buf = ByteArray(n)
                raf.readFully(buf)
                String(buf, Charsets.UTF_8)
            }
        } catch (e: Exception) {
            ""
        }
    }

    private fun sha256(f: File): String? {
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            FileInputStream(f).use { ins ->
                val buf = ByteArray(65536)
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            null
        }
    }

    /** Uso de apps en los últimos 7 días: cuándo se abrió Minecraft y si se usó algo sospechoso (incluso ya desinstalado). */
    @Suppress("DEPRECATION")
    fun usage(ctx: Context, sigs: Sigs, sc: ScanCtx) {
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val end = System.currentTimeMillis()
        val begin = end - 7L * 86400000L
        val map = usm.queryAndAggregateUsageStats(begin, end)
        val pm = ctx.packageManager
        val rows = map.values
            .filter { it.packageName != ctx.packageName && it.lastTimeUsed > begin }
            .sortedByDescending { it.lastTimeUsed }
        if (rows.isEmpty()) {
            sc.add("info", "Uso de apps", "Sin datos de uso en los últimos 7 días", "El teléfono no devolvió historial (¿se borró o se reinició recientemente?).", true)
            return
        }
        val mc = ArrayList<String>()
        val gone = ArrayList<String>()
        for (u in rows) {
            val pkg = u.packageName
            var label = pkg
            var installed = true
            try {
                label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            } catch (e: Exception) {
                installed = false
            }
            val mins = u.totalTimeInForeground / 60000
            val line = "$label ($pkg) · último uso ${fmtDate.format(Date(u.lastTimeUsed))} · ${mins} min en pantalla"
            if (pkg == "com.mojang.minecraftpe" || Checks.isMcLauncher(pkg) || Regex("minecraft|pojav|zalith").containsMatchIn(pkg + label.lowercase())) {
                mc.add(line)
            }
            val known = Checks.KNOWN_PKG[pkg]
            if (known != null) {
                sc.add(known.second, "Uso de apps", "App sospechosa usada esta semana: ${known.first}", line + if (installed) "" else "\n(ya NO está instalada)")
            } else {
                for (h in sigs.find("$label $pkg")) {
                    sc.add(if (h.fp) "yellow" else h.level, "Uso de apps", "App con nombre de cheat/automatización usada esta semana: $label", line + if (installed) "" else "\n(ya NO está instalada)", h.fp)
                }
            }
            if (!installed) gone.add(line)
        }
        if (mc.isNotEmpty()) sc.add("info", "Uso de apps", "Minecraft / launchers usados en los últimos 7 días", mc.joinToString("\n"))
        else sc.add("info", "Uso de apps", "Minecraft no se abrió en los últimos 7 días", "No hay uso registrado de Minecraft ni de launchers en la semana.")
        if (gone.isNotEmpty()) {
            sc.add("yellow", "Uso de apps", "Apps usadas esta semana que ya se desinstalaron (${gone.size})",
                "Pudo desinstalar algo antes del escaneo. Pregúntale por cada una.\n" + gone.take(20).joinToString("\n"), true)
        }
    }
}
