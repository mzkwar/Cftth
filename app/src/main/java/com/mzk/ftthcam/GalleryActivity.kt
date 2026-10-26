package com.mzk.ftthcam

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.concurrent.Executors

class GalleryActivity : AppCompatActivity() {

    private lateinit var img: ImageView
    private lateinit var emptyText: TextView
    private lateinit var infoCard: LinearLayout
    private lateinit var tvNote: TextView
    private lateinit var btnNavigate: Button
    private lateinit var btnCopy: Button

    private val io = Executors.newSingleThreadExecutor()
    private var lat: Double? = null
    private var lon: Double? = null
    private var tvAddress: TextView? = null
    private var loadToken = 0
    private var askedPerms = false

    private val picker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) load(uri) }

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { launchPicker() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gallery)

        img = findViewById(R.id.img)
        emptyText = findViewById(R.id.emptyText)
        infoCard = findViewById(R.id.infoCard)
        tvNote = findViewById(R.id.tvNote)
        btnNavigate = findViewById(R.id.btnNavigate)
        btnCopy = findViewById(R.id.btnCopy)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnPick).setOnClickListener { startPick() }
        btnNavigate.setOnClickListener {
            val la = lat
            val lo = lon
            if (la != null && lo != null) Nav.navigateTo(this, la, lo)
        }
        btnCopy.setOnClickListener {
            val la = lat
            val lo = lon
            if (la != null && lo != null) {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("coords", "$la,$lo"))
                toast("تم نسخ الإحداثيات")
            }
        }
        setBtn(btnNavigate, false)
        setBtn(btnCopy, false)

        if (savedInstanceState == null) startPick()
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
    }

    private fun setBtn(b: Button, enabled: Boolean) {
        b.isEnabled = enabled
        b.alpha = if (enabled) 1f else 0.4f
    }

    private fun startPick() {
        if (Build.VERSION.SDK_INT >= 29 && !askedPerms) {
            // صلاحية "موقع الصور" ضرورية لقراءة إحداثيات صور المعرض على أندرويد 10+
            val need = ArrayList<String>()
            if (Build.VERSION.SDK_INT >= 33) {
                need.add(Manifest.permission.READ_MEDIA_IMAGES)
            } else {
                need.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            need.add(Manifest.permission.ACCESS_MEDIA_LOCATION)
            val missing = need.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (missing.isNotEmpty()) {
                askedPerms = true
                permLauncher.launch(missing.toTypedArray())
                return
            }
        }
        launchPicker()
    }

    private fun launchPicker() {
        picker.launch(arrayOf("image/*"))
    }

    private fun load(uri: Uri) {
        val token = ++loadToken
        emptyText.visibility = View.VISIBLE
        emptyText.text = "جارٍ قراءة الصورة…"
        infoCard.visibility = View.GONE
        tvNote.visibility = View.GONE
        io.execute {
            var info: PhotoInfo? = null
            var bmp: Bitmap? = null
            var err: String? = null
            try {
                val read = ExifReader.read(this, uri)
                info = read
                bmp = ExifReader.decodeScaled(this, uri, 1600, read.rotation)
            } catch (t: Throwable) {
                err = t.message ?: "تعذر قراءة الصورة"
            }
            val fInfo = info
            val fBmp = bmp
            val fErr = err
            runOnUiThread {
                if (token == loadToken && !isFinishing) show(fInfo, fBmp, fErr, token)
            }
        }
    }

    private fun show(info: PhotoInfo?, bmp: Bitmap?, err: String?, token: Int) {
        if (bmp != null) {
            img.setImageBitmap(bmp)
            emptyText.visibility = View.GONE
        } else {
            img.setImageDrawable(null)
            emptyText.visibility = View.VISIBLE
            emptyText.text = err ?: "تعذر عرض الصورة"
        }
        infoCard.removeAllViews()
        tvAddress = null
        lat = info?.lat
        lon = info?.lon

        if (info == null) {
            infoCard.visibility = View.GONE
            setBtn(btnNavigate, false)
            setBtn(btnCopy, false)
            tvNote.text = err ?: "تعذر قراءة بيانات الصورة"
            tvNote.visibility = View.VISIBLE
            return
        }

        infoCard.visibility = View.VISIBLE
        val la = info.lat
        val lo = info.lon
        addRow("خط العرض (Lat)", if (la != null) String.format(Locale.US, "%.6f", la) else "غير متوفر", true)
        addRow("خط الطول (Lon)", if (lo != null) String.format(Locale.US, "%.6f", lo) else "غير متوفر", true)
        addRow("التاريخ", info.date ?: "غير متوفر", true)
        addRow("الوقت", info.time ?: "غير متوفر", true)
        info.azimuth?.let {
            addRow("الاتجاه", "${it.toInt() % 360}° " + CompassHelper.dirName(it.toFloat()))
        }
        info.fat?.let { addRow("FAT", it, true) }
        info.zone?.let { addRow("ZONE", it, true) }

        val hasLoc = la != null && lo != null
        setBtn(btnNavigate, hasLoc)
        setBtn(btnCopy, hasLoc)

        if (hasLoc) {
            tvNote.visibility = View.GONE
            val addrView = addRow("العنوان", "جارٍ التحديد…")
            tvAddress = addrView
            GeoUtil.reverse(this, la!!, lo!!) { a ->
                runOnUiThread {
                    if (token == loadToken && !isFinishing) {
                        tvAddress?.text = a ?: "غير متاح (يتطلب اتصالاً بالإنترنت)"
                    }
                }
            }
        } else {
            tvNote.text = "هذه الصورة لا تحتوي على إحداثيات GPS. قد تكون خدمة الموقع مغلقة وقت التصوير، " +
                "أو أن الموقع محجوب عن التطبيق (امنح صلاحية «الوصول إلى موقع الصور» من إعدادات التطبيق)."
            tvNote.visibility = View.VISIBLE
        }
    }

    private fun addRow(label: String, value: String, isLtr: Boolean = false): TextView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(7), 0, dp(7))
        }
        val l = TextView(this).apply {
            text = label
            textSize = 14f
            setTextColor(ContextCompat.getColor(this@GalleryActivity, R.color.text_dim))
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            layoutParams = LinearLayout.LayoutParams(dp(130), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val v = TextView(this).apply {
            text = value
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ContextCompat.getColor(this@GalleryActivity, R.color.white))
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            if (isLtr) textDirection = View.TEXT_DIRECTION_LTR
            setTextIsSelectable(true)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(l)
        row.addView(v)
        infoCard.addView(row)
        return v
    }
}
