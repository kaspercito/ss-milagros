package com.ssmilagros.sshelper

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {
    private val BG = Color.parseColor("#14141B")
    private val FG = Color.parseColor("#ECECF2")
    private val MUT = Color.parseColor("#7D7D8C")
    private val ACC = Color.parseColor("#C83028")
    private val ERR = Color.parseColor("#FF7B7B")
    private val OK = Color.parseColor("#5FD38D")

    private val ui = Handler(Looper.getMainLooper())

    private lateinit var fCode: LinearLayout
    private lateinit var fRun: LinearLayout
    private lateinit var fDone: LinearLayout
    private lateinit var entry: EditText
    private lateinit var btn: Button
    private lateinit var lblErr: TextView
    private lateinit var lblPct: TextView
    private lateinit var bar: ProgressBar
    private lateinit var lblStatus: TextView
    private lateinit var btnRetry: Button

    @Volatile private var busy = false
    @Volatile private var running = false
    @Volatile private var floorV = 0.0
    @Volatile private var ceilV = 0.0
    private var shown = 0.0

    @Volatile private var pickedTree: Uri? = null
    private var treeLatch: CountDownLatch? = null

    private var cfg: Cfg? = null
    private var codeStr = ""
    private var codeSha: String? = null
    private var codeInfo = JSONObject()
    @Volatile private var report: JSONObject? = null

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radiusDp: Int, strokeColor: Int? = null): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(radiusDp).toFloat()
        if (strokeColor != null) g.setStroke(dp(1), strokeColor)
        return g
    }

    private fun heartDrawable(): BitmapDrawable {
        val rows = arrayOf(".###.###.", "#RRR#RRR#", "#RRRRRRR#", "#RRRRRRR#", ".#RRRRR#.", "..#RRR#..", "...#R#...", "....#....")
        val bmp = Bitmap.createBitmap(9, 8, Bitmap.Config.ARGB_8888)
        for (y in rows.indices) {
            for (x in rows[y].indices) {
                val c = when {
                    (x == 2 && y == 1) || (x == 1 && y == 2) -> Color.parseColor("#F08C82")
                    rows[y][x] == '#' -> Color.parseColor("#160808")
                    rows[y][x] == 'R' -> Color.parseColor("#C83028")
                    else -> Color.TRANSPARENT
                }
                bmp.setPixel(x, y, c)
            }
        }
        val d = BitmapDrawable(resources, bmp)
        d.isFilterBitmap = false      // píxeles nítidos, sin suavizado
        return d
    }

    private fun label(text: String, sizeSp: Float, color: Int, bold: Boolean = false): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = sizeSp
        t.setTextColor(color)
        if (bold) t.setTypeface(t.typeface, Typeface.BOLD)
        return t
    }

    private fun primaryButton(text: String): Button {
        val b = Button(this)
        b.text = text
        b.setTextColor(Color.WHITE)
        b.isAllCaps = false
        b.textSize = 16f
        b.setTypeface(b.typeface, Typeface.BOLD)
        b.background = rounded(ACC, 8)
        return b
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(BG)
        root.gravity = Gravity.CENTER_HORIZONTAL
        root.setPadding(dp(28), dp(48), dp(28), dp(20))

        val heart = ImageView(this)
        heart.setImageDrawable(heartDrawable())
        heart.scaleType = ImageView.ScaleType.FIT_XY
        root.addView(heart, LinearLayout.LayoutParams(dp(108), dp(96)))

        val title = label("SS Helper", 22f, FG, true)
        title.gravity = Gravity.CENTER
        val tlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        tlp.topMargin = dp(10)
        root.addView(title, tlp)

        val body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL
        val blp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        blp.topMargin = dp(20)
        root.addView(body, blp)

        // ── pantalla 1: código ──
        fCode = LinearLayout(this)
        fCode.orientation = LinearLayout.VERTICAL
        fCode.addView(label("Código", 12f, MUT))
        entry = EditText(this)
        entry.setTextColor(FG)
        entry.typeface = Typeface.MONOSPACE
        entry.textSize = 22f
        entry.gravity = Gravity.CENTER
        entry.isSingleLine = true
        entry.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        entry.imeOptions = EditorInfo.IME_ACTION_GO
        entry.hint = "SS-XXXX-XXXX"
        entry.setHintTextColor(Color.parseColor("#444455"))
        entry.background = rounded(Color.parseColor("#1D1D28"), 8, Color.parseColor("#2B2B3A"))
        entry.setPadding(dp(12), dp(14), dp(12), dp(14))
        val elp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        elp.topMargin = dp(6)
        fCode.addView(entry, elp)
        btn = primaryButton("Iniciar")
        val btlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52))
        btlp.topMargin = dp(14)
        fCode.addView(btn, btlp)
        lblErr = label("", 13f, ERR)
        lblErr.gravity = Gravity.CENTER
        val errlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        errlp.topMargin = dp(12)
        fCode.addView(lblErr, errlp)

        // ── pantalla 2: progreso ──
        fRun = LinearLayout(this)
        fRun.orientation = LinearLayout.VERTICAL
        fRun.gravity = Gravity.CENTER_HORIZONTAL
        lblPct = label("0%", 48f, FG, true)
        lblPct.gravity = Gravity.CENTER
        fRun.addView(lblPct)
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        bar.max = 1000
        bar.progressTintList = ColorStateList.valueOf(ACC)
        bar.progressBackgroundTintList = ColorStateList.valueOf(Color.parseColor("#23232E"))
        val barlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10))
        barlp.topMargin = dp(14)
        fRun.addView(bar, barlp)
        lblStatus = label("Escaneando…", 14f, MUT)
        lblStatus.gravity = Gravity.CENTER
        val stlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        stlp.topMargin = dp(14)
        fRun.addView(lblStatus, stlp)
        btnRetry = primaryButton("Reintentar")
        btnRetry.visibility = View.GONE
        val rtlp = LinearLayout.LayoutParams(dp(200), dp(48))
        rtlp.topMargin = dp(16)
        fRun.addView(btnRetry, rtlp)

        // ── pantalla 3: listo ──
        fDone = LinearLayout(this)
        fDone.orientation = LinearLayout.VERTICAL
        fDone.gravity = Gravity.CENTER_HORIZONTAL
        val ok = label("Escaneo completado", 22f, OK, true)
        ok.gravity = Gravity.CENTER
        fDone.addView(ok)
        val ok2 = label("Ya puedes cerrar la app.", 14f, MUT)
        ok2.gravity = Gravity.CENTER
        val ok2lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        ok2lp.topMargin = dp(8)
        fDone.addView(ok2, ok2lp)
        val un = Button(this)
        un.text = "Desinstalar la app"
        un.isAllCaps = false
        un.setTextColor(FG)
        un.background = rounded(Color.parseColor("#23232E"), 8)
        un.setOnClickListener {
            startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")))
        }
        val unlp = LinearLayout.LayoutParams(dp(220), dp(48))
        unlp.topMargin = dp(22)
        fDone.addView(un, unlp)

        for (f in listOf(fCode, fRun, fDone)) {
            f.visibility = View.GONE
            body.addView(f, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        val note = label("Solo lectura · se revisan apps y ajustes del teléfono · el resultado se envía al staff", 11f, Color.parseColor("#555563"))
        note.gravity = Gravity.CENTER
        root.addView(note, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        setContentView(root)
        showFrame(fCode)

        btn.setOnClickListener { onStartClick() }
        entry.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) { onStartClick(); true } else false
        }
        btnRetry.setOnClickListener {
            btnRetry.visibility = View.GONE
            Thread { send() }.start()
        }
        ui.post(tick)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (running) {
                var sh = shown
                val fl = floorV
                val ce = ceilV
                if (sh < fl) sh = minOf(fl, sh + maxOf(0.8, (fl - sh) * 0.3))
                else if (sh < ce) sh = minOf(ce, sh + 0.04)
                shown = sh
                bar.progress = (sh * 10).toInt()
                lblPct.text = "${sh.toInt()}%"
            }
            ui.postDelayed(this, 80)
        }
    }

    private fun showFrame(f: View) {
        fCode.visibility = View.GONE
        fRun.visibility = View.GONE
        fDone.visibility = View.GONE
        f.visibility = View.VISIBLE
    }

    private fun onStartClick() {
        if (busy) return
        val code = Github.normalizeCode(entry.text.toString())
        if (code.isEmpty()) {
            lblErr.text = "Formato inválido. Ejemplo: SS-MABC-1234"
            return
        }
        busy = true
        lblErr.text = ""
        btn.isEnabled = false
        Thread { worker(code) }.start()
    }

    private fun fail(msg: String) {
        busy = false
        running = false
        runOnUiThread {
            lblErr.text = msg
            btn.isEnabled = true
            showFrame(fCode)
        }
    }

    /** Pide la carpeta de PojavLauncher con el selector de archivos de Android (esperando la respuesta). */
    @Suppress("DEPRECATION")
    private fun askPojavFolder(): Uri? {
        val latch = CountDownLatch(1)
        treeLatch = latch
        pickedTree = null
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle("PojavLauncher detectado")
                .setMessage("Para revisar los mods y logs, elige la carpeta «games/PojavLauncher» del almacenamiento interno y pulsa «Usar esta carpeta».")
                .setCancelable(false)
                .setPositiveButton("Elegir carpeta") { _, _ ->
                    startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), 1001)
                }
                .setNegativeButton("Omitir") { _, _ -> latch.countDown() }
                .show()
        }
        latch.await(300, TimeUnit.SECONDS)
        return pickedTree
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001) {
            if (resultCode == RESULT_OK) pickedTree = data?.data
            treeLatch?.countDown()
        }
    }

    private fun worker(code: String) {
        try {
            val loaded = Github.loadCfg(this)
            val c = loaded.first
            if (c == null) { fail(loaded.second); return }
            cfg = c
            val ping = Github.request(c, "GET", "", null, 15000)
            if (ping.status == 401) {
                fail("El token de la app no es válido o venció (HTTP 401). Avisa al staff para recompilar la app.")
                return
            }
            val chk = Github.validateCode(c, code)
            if (chk.ok != true) { fail(chk.msg); return }
            if (Github.platformOf(code, chk.info) != "mobile") {
                fail("Ese código es para PC (Java/Bedrock). Usa SSHelper.exe en el ordenador.")
                return
            }
            codeStr = code
            codeSha = chk.sha
            codeInfo = chk.info

            var tree: Uri? = null
            if (Checks.pojavInstalled(this)) tree = askPojavFolder()

            floorV = 0.0; ceilV = 0.0; shown = 0.0
            running = true
            runOnUiThread { showFrame(fRun); lblStatus.text = "Escaneando…" }

            val t0 = System.currentTimeMillis()
            val sc = ScanCtx(this)
            val sigs = Sigs(Github.fetchRemoteSigs(c))
            Checks.run(sc, sigs, tree) { done, total ->
                floorV = done.toDouble() / total * 94
                ceilV = maxOf(floorV, (done + 1).toDouble() / total * 94 - 0.5)
            }
            report = Report.build(sc, code, ((System.currentTimeMillis() - t0) / 1000).toInt())
            send()
        } catch (e: Throwable) {
            fail("Error inesperado: ${e.javaClass.simpleName}: ${(e.message ?: "").take(100)}")
        }
    }

    private fun send() {
        val c = cfg
        val r = report
        if (c == null || r == null) { fail("Error interno: no hay reporte que enviar."); return }
        floorV = 95.0; ceilV = 99.0
        runOnUiThread { lblStatus.text = "Enviando…"; btnRetry.visibility = View.GONE }
        val res = Github.uploadReport(c, r, codeStr)
        if (res.ok) {
            Github.markCodeDone(c, codeStr, codeSha, codeInfo, r, res.path)
            floorV = 100.0; ceilV = 100.0
            Thread.sleep(1200)
            runOnUiThread {
                running = false
                bar.progress = 1000
                lblPct.text = "100%"
                showFrame(fDone)
            }
        } else {
            runOnUiThread {
                lblStatus.text = "No se pudo enviar el resultado (${res.why}). Revisa tu internet."
                btnRetry.visibility = View.VISIBLE
            }
        }
    }
}
