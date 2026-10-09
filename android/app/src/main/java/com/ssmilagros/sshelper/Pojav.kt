package com.ssmilagros.sshelper

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/** Revisa la carpeta de PojavLauncher que el jugador elige con el selector de archivos de Android. */
object Pojav {
    private class Doc(val id: String, val name: String, val mime: String, val size: Long, val path: String)

    val SKIP = setOf(
        "assets", "libraries", "runtimes", "runtime", "saves", "crash-reports", "cache", "versions", "lib",
        "screenshots", "resourcepacks", "shaderpacks", "datapacks", "server-resource-packs"
    )
    // clases de módulos típicas de clientes de cheat (nombre simple en minúsculas)
    private val CHEAT_CLASSES = setOf(
        "killaura", "aimassist", "autoclicker", "triggerbot", "scaffold", "nofall", "criticals", "chestesp",
        "tracers", "crystalaura", "autototem", "bowaimbot", "fullbright", "wtap", "hitboxes", "clickaura",
        "antiknockback", "backtrack", "jumpreset", "safewalk", "noslow", "autopot", "legitscaffold",
        "bridgeassist", "nuker", "autoarmor", "cheststealer", "forcefield"
    )
    private val PKG_MARKERS = listOf(
        "meteordevelopment/meteorclient", "net/ccbluex/liquidbounce", "net/wurstclient",
        "me/zeroeightsix/kami", "org/rusherhack"
    )
    private val META_FILES = setOf("fabric.mod.json", "mcmod.info", "quilt.mod.json", "mods.toml")

    private fun children(cr: ContentResolver, tree: Uri, parentId: String, parentPath: String): List<Doc> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val out = ArrayList<Doc>()
        var c: Cursor? = null
        try {
            c = cr.query(
                uri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE
                ),
                null, null, null
            )
            while (c != null && c.moveToNext()) {
                val name = c.getString(1)
                if (name == null) continue
                out.add(Doc(c.getString(0), name, c.getString(2) ?: "", if (c.isNull(3)) 0L else c.getLong(3), "$parentPath/$name"))
            }
        } catch (e: Exception) {
            // carpeta ilegible: se ignora
        } finally {
            c?.close()
        }
        return out
    }

    private fun readLimited(cr: ContentResolver, uri: Uri, max: Int): ByteArray {
        val bos = java.io.ByteArrayOutputStream()
        cr.openInputStream(uri)?.use { ins ->
            val buf = ByteArray(65536)
            var total = 0
            while (total < max) {
                val n = ins.read(buf, 0, minOf(buf.size, max - total))
                if (n < 0) break
                bos.write(buf, 0, n)
                total += n
            }
        }
        return bos.toByteArray()
    }

    fun scan(ctx: Context, tree: Uri, sigs: Sigs, sc: ScanCtx, budgetMs: Long = 70000) {
        val cr = ctx.contentResolver
        val deadline = System.currentTimeMillis() + budgetMs
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val queue = ArrayDeque<Triple<String, String, Int>>()
        queue.add(Triple(rootId, "", 0))
        val jars = ArrayList<Doc>()
        val logs = ArrayList<Doc>()
        var seen = 0
        sc.logSources.add("PojavLauncher (carpeta elegida por el jugador)")

        while (queue.isNotEmpty() && System.currentTimeMillis() < deadline && seen < 6000) {
            val (id, path, depth) = queue.removeFirst()
            for (d in children(cr, tree, id, path)) {
                seen++
                val low = d.name.lowercase()
                if (d.mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    if (depth < 5 && !SKIP.contains(low)) queue.add(Triple(d.id, d.path, depth + 1))
                    continue
                }
                sc.filesScanned++
                for (h in sigs.find(d.name)) {
                    sc.add(if (h.fp) "yellow" else h.level, "Pojav", "Archivo con nombre de cheat: ${d.name}", "${d.path}\nFirma: ${h.name}", h.fp)
                }
                if (low.endsWith(".jar")) jars.add(d)
                if (low == "latest.log" || low == "latestlog.txt" || low == "latestlog.old") logs.add(d)
            }
        }
        if (seen == 0) {
            sc.add("info", "Pojav", "La carpeta elegida está vacía o no se pudo leer",
                "Pide al jugador elegir la carpeta 'games/PojavLauncher' (en el almacenamiento interno).")
            return
        }

        // .jar: primero los de mods/
        val ordered = jars.sortedBy { if (it.path.lowercase().contains("/mods/")) 0 else 1 }.take(60)
        for (d in ordered) {
            if (System.currentTimeMillis() > deadline) {
                sc.add("info", "Pojav", "Revisión de .jar parcial", "Se agotó el tiempo máximo.")
                break
            }
            if (d.size > 90_000_000L) {
                sc.add("info", "Pojav", "Jar omitido por tamaño: ${d.name}", d.path)
                continue
            }
            inspectJar(cr, tree, d, sigs, sc)
        }

        // logs: nick y firmas
        for (d in logs.take(3)) {
            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, d.id)
            val text = try { String(readLimited(cr, uri, 3_000_000), Charsets.UTF_8) } catch (e: Exception) { "" }
            processLog("Pojav", d.path, text, sigs, sc)
        }
    }

    /** Nick(s) y firmas de cheat dentro de un log del juego (sirve para la carpeta elegida y para el escaneo directo). */
    fun processLog(module: String, path: String, text: String, sigs: Sigs, sc: ScanCtx) {
        if (text.isEmpty()) return
        sc.logSources.add(path)
        for (m in Regex("Setting user: ([A-Za-z0-9_]{3,16})").findAll(text).take(5)) sc.addNick(m.groupValues[1])
        val lowText = text.lowercase()
        for (h in sigs.find(text)) {
            val idx = lowText.indexOf(h.name.lowercase())
            val line = if (idx >= 0) {
                val s = lowText.lastIndexOf('\n', idx) + 1
                val e = lowText.indexOf('\n', idx).let { if (it < 0) minOf(text.length, idx + 160) else it }
                lowText.substring(s, minOf(e, s + 240))
            } else "(coincidencia por nombre sin separadores)"
            sc.add(if (h.fp) "yellow" else h.level, module, "Nombre de cheat en el log del juego: ${h.name}", "$path\n$line", h.fp)
        }
    }

    private fun inspectJar(cr: ContentResolver, tree: Uri, d: Doc, sigs: Sigs, sc: ScanCtx) {
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, d.id)
        inspectJarStream("Pojav", d.name, d.path, { cr.openInputStream(uri) }, sigs, sc)
    }

    /** Revisa un .jar (hash, clases y paquetes de cheat, ficha del mod) leyéndolo desde cualquier fuente. */
    fun inspectJarStream(module: String, name: String, path: String, open: () -> java.io.InputStream?, sigs: Sigs, sc: ScanCtx) {
        val md = MessageDigest.getInstance("SHA-256")
        val classes = HashSet<String>()
        val markers = HashSet<String>()
        val metaText = StringBuilder()
        var complete = false
        try {
            open()?.use { raw ->
                val dis = DigestInputStream(raw, md)
                val zin = ZipInputStream(dis)
                var e: ZipEntry? = zin.nextEntry
                var n = 0
                while (e != null && n < 60000) {
                    n++
                    val en = e.name.lowercase()
                    if (en.endsWith(".class")) {
                        val base = en.substringAfterLast('/').removeSuffix(".class")
                        if (CHEAT_CLASSES.contains(base)) classes.add(base)
                        for (p in PKG_MARKERS) if (en.contains(p)) markers.add(p)
                    } else if (META_FILES.contains(en.substringAfterLast('/')) && metaText.length < 20000) {
                        val bytes = zin.readBytes()
                        metaText.append(String(bytes, 0, minOf(bytes.size, 8000), Charsets.UTF_8)).append('\n')
                    }
                    e = zin.nextEntry
                }
                val buf = ByteArray(65536)
                while (dis.read(buf) != -1) { /* completa el hash */ }
                complete = true
            }
        } catch (ex: Exception) {
            // jar corrupto: se informa abajo
        }
        sc.jarsInspected++
        val hash = md.digest().joinToString("") { "%02x".format(it) }
        if (complete && sigs.hashes.contains(hash)) {
            sc.add("red", module, "Jar con hash de cheat conocido: $name", "$path\nSHA-256: $hash")
        }
        if (markers.isNotEmpty()) {
            sc.add("red", module, "Jar con paquetes de un cliente de cheat: $name", "$path\n${markers.joinToString(", ")}")
        }
        if (classes.size >= 3) {
            sc.add("red", module, "Jar con ${classes.size} clases de módulos de cheat: $name", "$path\n${classes.sorted().joinToString(", ")}")
        } else if (classes.isNotEmpty()) {
            sc.add("yellow", module, "Jar con clase(s) con nombre de módulo de cheat: $name", "$path\n${classes.sorted().joinToString(", ")}", true)
        }
        if (metaText.isNotEmpty()) {
            for (h in sigs.find(metaText.toString())) {
                sc.add(if (h.fp) "yellow" else h.level, module, "Mod con nombre de cheat en su ficha: $name", "$path\nFirma: ${h.name}", h.fp)
            }
        }
        if (!complete) {
            sc.add("info", module, "No se pudo leer el jar completo: $name", path)
        }
    }
}
