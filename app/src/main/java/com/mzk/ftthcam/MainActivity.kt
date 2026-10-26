package com.mzk.ftthcam

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.media.MediaActionSound
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.widget.doAfterTextChanged
import androidx.drawerlayout.widget.DrawerLayout
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private enum class Flash { OFF, TORCH, CAPTURE }

    private lateinit var drawer: DrawerLayout
    private lateinit var previewView: PreviewView
    private lateinit var btnFlash: ImageButton
    private lateinit var btnGlare: ImageButton
    private lateinit var btnTemplate: ImageButton
    private lateinit var btnFlip: ImageButton
    private lateinit var btnMenu: ImageButton
    private lateinit var btnGallery: ImageButton
    private lateinit var shutter: View
    private lateinit var infoText: TextView
    private lateinit var etFat: EditText
    private lateinit var etZone: EditText
    private lateinit var permissionMsg: TextView
    private lateinit var focusRing: ImageView
    private lateinit var flashOverlay: View
    private lateinit var zoomChips: List<TextView>

    private lateinit var prefs: SharedPreferences
    private lateinit var locationHelper: LocationHelper
    private lateinit var compass: CompassHelper

    private val zoomValues = listOf(1f, 2f, 3f)
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val sound = MediaActionSound()

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var flash = Flash.OFF
    private var zoomPreset = 1f
    private var antiGlare = false
    private var template = 0
    private var capturing = false

    private val orientationListener by lazy {
        object : android.view.OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val rotation = when {
                    orientation >= 315 || orientation < 45 -> Surface.ROTATION_0
                    orientation in 225..314 -> Surface.ROTATION_90
                    orientation in 135..224 -> Surface.ROTATION_180
                    else -> Surface.ROTATION_270
                }
                imageCapture?.targetRotation = rotation
            }
        }
    }

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onPermissionsResult() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        drawer = findViewById(R.id.drawer)
        previewView = findViewById(R.id.previewView)
        btnFlash = findViewById(R.id.btnFlash)
        btnGlare = findViewById(R.id.btnGlare)
        btnTemplate = findViewById(R.id.btnTemplate)
        btnFlip = findViewById(R.id.btnFlip)
        btnMenu = findViewById(R.id.btnMenu)
        btnGallery = findViewById(R.id.btnGallery)
        shutter = findViewById(R.id.shutter)
        infoText = findViewById(R.id.infoText)
        etFat = findViewById(R.id.etFat)
        etZone = findViewById(R.id.etZone)
        permissionMsg = findViewById(R.id.permissionMsg)
        focusRing = findViewById(R.id.focusRing)
        flashOverlay = findViewById(R.id.flashOverlay)
        zoomChips = listOf(
            findViewById(R.id.zoom1), findViewById(R.id.zoom2), findViewById(R.id.zoom3)
        )

        prefs = getSharedPreferences("ftth", MODE_PRIVATE)
        etFat.setText(prefs.getString("fat", ""))
        etZone.setText(prefs.getString("zone", ""))
        etFat.doAfterTextChanged { prefs.edit().putString("fat", it?.toString() ?: "").apply() }
        etZone.doAfterTextChanged { prefs.edit().putString("zone", it?.toString() ?: "").apply() }
        template = prefs.getInt("template", 0).coerceIn(0, WatermarkRenderer.TEMPLATE_NAMES.size - 1)

        sound.load(MediaActionSound.SHUTTER_CLICK)

        compass = CompassHelper(this) { updateInfo() }
        locationHelper = LocationHelper(this) {
            locationHelper.current?.let { compass.setLocation(it) }
            updateInfo()
        }

        setupButtons()
        setupTouch()
        updateZoomChips()
        updateFlashIcon()
        updateGlareIcon()
        updateInfo()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (drawer.isDrawerOpen(GravityCompat.END)) {
                    drawer.closeDrawer(GravityCompat.END)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        requestPerms()
    }

    override fun onResume() {
        super.onResume()
        compass.start()
        if (orientationListener.canDetectOrientation()) orientationListener.enable()
        if (locationHelper.hasPermission()) locationHelper.start()
        if (hasCameraPermission() && cameraProvider == null) startCamera()
        if (hasCameraPermission()) permissionMsg.visibility = View.GONE
        updateFlashIcon()
        updateInfo()
    }

    override fun onPause() {
        super.onPause()
        locationHelper.stop()
        compass.stop()
        orientationListener.disable()
        flash = Flash.OFF
        updateFlashIcon()
    }

    override fun onDestroy() {
        super.onDestroy()
        ioExecutor.shutdown()
        sound.release()
    }

    // ---------------- الصلاحيات ----------------

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun requestPerms() {
        val list = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT <= 28) list.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        val missing = list.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) onPermissionsResult() else permLauncher.launch(missing.toTypedArray())
    }

    private fun onPermissionsResult() {
        if (hasCameraPermission()) {
            permissionMsg.visibility = View.GONE
            if (cameraProvider == null) startCamera()
        } else {
            permissionMsg.visibility = View.VISIBLE
        }
        if (locationHelper.hasPermission()) locationHelper.start()
        updateInfo()
    }

    // ---------------- الواجهة ----------------

    private fun setupButtons() {
        permissionMsg.setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            )
        }

        btnMenu.setOnClickListener { drawer.openDrawer(GravityCompat.END) }

        btnFlash.setOnClickListener {
            val cam = camera
            if (cam == null || !cam.cameraInfo.hasFlashUnit()) {
                toast("المصباح غير متوفر في هذه الكاميرا")
                return@setOnClickListener
            }
            flash = when (flash) {
                Flash.OFF -> Flash.TORCH
                Flash.TORCH -> Flash.CAPTURE
                Flash.CAPTURE -> Flash.OFF
            }
            applyFlash()
            toast(
                when (flash) {
                    Flash.OFF -> "المصباح: مغلق"
                    Flash.TORCH -> "المصباح: يعمل بشكل مستمر"
                    Flash.CAPTURE -> "المصباح: عند التصوير فقط"
                }
            )
        }

        btnGlare.setOnClickListener {
            antiGlare = !antiGlare
            applyExposure()
            updateGlareIcon()
            toast(if (antiGlare) "منع التوهج: مفعّل (تقليل التعريض)" else "منع التوهج: متوقف")
        }

        btnTemplate.setOnClickListener {
            val dialog = AlertDialog.Builder(this)
                .setTitle("قالب الطباعة على الصورة")
                .setSingleChoiceItems(WatermarkRenderer.TEMPLATE_NAMES.toTypedArray(), template) { d, which ->
                    template = which
                    prefs.edit().putInt("template", which).apply()
                    toast("القالب: " + WatermarkRenderer.TEMPLATE_NAMES[which])
                    d.dismiss()
                }
                .setNegativeButton("إلغاء", null)
                .create()
            dialog.show()
            dialog.window?.decorView?.layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        btnFlip.setOnClickListener {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                CameraSelector.LENS_FACING_FRONT
            } else {
                CameraSelector.LENS_FACING_BACK
            }
            compass.facingBack = lensFacing == CameraSelector.LENS_FACING_BACK
            flash = Flash.OFF
            bindCamera()
        }

        btnGallery.setOnClickListener { startActivity(Intent(this, GalleryActivity::class.java)) }

        shutter.setOnClickListener { takePhoto() }

        zoomChips.forEachIndexed { i, chip ->
            chip.setOnClickListener {
                zoomPreset = zoomValues[i]
                applyZoom()
                updateZoomChips()
            }
        }

        findViewById<View>(R.id.menuGallery).setOnClickListener {
            drawer.closeDrawer(GravityCompat.END)
            startActivity(Intent(this, GalleryActivity::class.java))
        }
        findViewById<View>(R.id.menuAboutUs).setOnClickListener {
            drawer.closeDrawer(GravityCompat.END)
            Nav.openFacebook(this, "https://www.facebook.com/share/1JEV4idPKD/")
        }
        findViewById<View>(R.id.menuAboutApp).setOnClickListener {
            drawer.closeDrawer(GravityCompat.END)
            showAbout()
        }
        findViewById<View>(R.id.menuExit).setOnClickListener {
            drawer.closeDrawer(GravityCompat.END)
            finishAffinity()
        }
    }

    private fun showAbout() {
        val tv = TextView(this).apply {
            text = getString(R.string.about_text)
            textSize = 16f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text))
            setPadding(dp(24), dp(12), dp(24), dp(8))
            setLineSpacing(0f, 1.35f)
            textDirection = View.TEXT_DIRECTION_RTL
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.about_title)
            .setView(tv)
            .setPositiveButton(R.string.ok, null)
            .create()
        dialog.show()
        dialog.window?.decorView?.layoutDirection = View.LAYOUT_DIRECTION_RTL
    }

    private fun setupTouch() {
        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val cam = camera ?: return true
                val zs = cam.cameraInfo.zoomState.value ?: return true
                val r = (zs.zoomRatio * detector.scaleFactor).coerceIn(zs.minZoomRatio, zs.maxZoomRatio)
                cam.cameraControl.setZoomRatio(r)
                zoomPreset = r
                updateZoomChips()
                return true
            }
        })
        val tap = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                focusAt(e.x, e.y)
                return true
            }
        })
        previewView.setOnTouchListener { v, ev ->
            scale.onTouchEvent(ev)
            if (!scale.isInProgress) tap.onTouchEvent(ev)
            if (ev.action == MotionEvent.ACTION_UP) v.performClick()
            true
        }
    }

    private fun focusAt(x: Float, y: Float) {
        val cam = camera ?: return
        val point = previewView.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(
            point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
        ).setAutoCancelDuration(4, TimeUnit.SECONDS).build()
        cam.cameraControl.startFocusAndMetering(action)

        focusRing.animate().cancel()
        focusRing.x = x - focusRing.width / 2f
        focusRing.y = y - focusRing.height / 2f
        focusRing.visibility = View.VISIBLE
        focusRing.alpha = 1f
        focusRing.scaleX = 1.4f
        focusRing.scaleY = 1.4f
        focusRing.animate().scaleX(1f).scaleY(1f).setDuration(200).withEndAction {
            focusRing.animate().alpha(0f).setStartDelay(600).setDuration(300).start()
        }.start()
    }

    private fun updateZoomChips() {
        zoomChips.forEachIndexed { i, chip -> chip.isSelected = abs(zoomPreset - zoomValues[i]) < 0.05f }
    }

    private fun updateFlashIcon() {
        when (flash) {
            Flash.OFF -> {
                btnFlash.setImageResource(R.drawable.ic_flash_off)
                btnFlash.setColorFilter(Color.WHITE)
            }
            Flash.TORCH -> {
                btnFlash.setImageResource(R.drawable.ic_flash_on)
                btnFlash.setColorFilter(ContextCompat.getColor(this, R.color.accent))
            }
            Flash.CAPTURE -> {
                btnFlash.setImageResource(R.drawable.ic_flash_auto)
                btnFlash.setColorFilter(ContextCompat.getColor(this, R.color.amber))
            }
        }
    }

    private fun updateGlareIcon() {
        btnGlare.setColorFilter(if (antiGlare) ContextCompat.getColor(this, R.color.accent) else Color.WHITE)
    }

    private fun updateInfo() {
        val loc = locationHelper.current
        val l1 = when {
            loc != null -> {
                val acc = if (loc.hasAccuracy()) "  (±${loc.accuracy.roundToInt()}م)" else ""
                "الإحداثيات: " + ltr(String.format(Locale.US, "%.6f, %.6f", loc.latitude, loc.longitude)) + acc
            }
            !locationHelper.hasPermission() -> "الإحداثيات: صلاحية الموقع غير ممنوحة"
            else -> "الإحداثيات: جارٍ البحث عن إشارة GPS…"
        }
        val az = compass.azimuth
        val azText = if (az != null) {
            ltr("${az.roundToInt() % 360}°") + " " + CompassHelper.dirName(az)
        } else {
            "غير متاح"
        }
        val addr = locationHelper.address ?: (if (loc != null) "جارٍ التحديد…" else "—")
        infoText.text = l1 + "\n" + "الاتجاه: " + azText + "\n" + "العنوان: " + addr
    }

    // ---------------- الكاميرا ----------------

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                cameraProvider = future.get()
                bindCamera()
            } catch (e: Exception) {
                toast("تعذر تشغيل الكاميرا")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera() {
        val provider = cameraProvider ?: return
        var selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        if (!provider.hasCamera(selector)) {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                CameraSelector.LENS_FACING_FRONT
            } else {
                CameraSelector.LENS_FACING_BACK
            }
            selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
            if (!provider.hasCamera(selector)) {
                toast("لا توجد كاميرا في هذا الجهاز")
                return
            }
            compass.facingBack = lensFacing == CameraSelector.LENS_FACING_BACK
        }

        val preview = Preview.Builder().setTargetAspectRatio(AspectRatio.RATIO_16_9).build()
        preview.setSurfaceProvider(previewView.surfaceProvider)
        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER

        val capture = ImageCapture.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_16_9)
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
        imageCapture = capture

        try {
            provider.unbindAll()
            camera = provider.bindToLifecycle(this, selector, preview, capture)
            applyCameraState()
            handler.postDelayed({ applyZoom() }, 500)
        } catch (e: Exception) {
            toast("تعذر ربط الكاميرا")
        }
    }

    private fun applyCameraState() {
        applyFlash()
        applyZoom()
        applyExposure()
    }

    private fun applyFlash() {
        val cam = camera ?: return
        if (!cam.cameraInfo.hasFlashUnit()) flash = Flash.OFF
        cam.cameraControl.enableTorch(flash == Flash.TORCH)
        imageCapture?.flashMode =
            if (flash == Flash.CAPTURE) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
        updateFlashIcon()
    }

    private fun applyZoom() {
        val cam = camera ?: return
        val zs = cam.cameraInfo.zoomState.value
        val ratio = if (zs != null) zoomPreset.coerceIn(zs.minZoomRatio, zs.maxZoomRatio) else zoomPreset
        cam.cameraControl.setZoomRatio(ratio)
    }

    private fun applyExposure() {
        val cam = camera ?: return
        val st = cam.cameraInfo.exposureState
        if (!st.isExposureCompensationSupported) return
        val step = st.exposureCompensationStep.toFloat()
        val range = st.exposureCompensationRange
        val index = if (antiGlare && step > 0f) {
            (-1.5f / step).roundToInt().coerceIn(range.lower, range.upper)
        } else {
            0
        }
        cam.cameraControl.setExposureCompensationIndex(index)
    }

    // ---------------- الالتقاط ----------------

    private fun takePhoto() {
        val ic = imageCapture
        if (ic == null) {
            toast("الكاميرا غير جاهزة بعد")
            return
        }
        if (capturing) return
        capturing = true
        shutterFeedback()

        val loc = locationHelper.current
        val data = WmData(
            lat = loc?.latitude,
            lon = loc?.longitude,
            accuracy = if (loc != null && loc.hasAccuracy()) loc.accuracy else null,
            address = locationHelper.address,
            azimuth = compass.azimuth,
            fat = etFat.text.toString().trim(),
            zone = etZone.text.toString().trim(),
            timeMs = System.currentTimeMillis()
        )
        val tpl = template

        ic.takePicture(ioExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                var error: String? = null
                try {
                    val rot = image.imageInfo.rotationDegrees
                    val src = image.toBitmap()
                    val swap = rot == 90 || rot == 270
                    val ow = if (swap) src.height else src.width
                    val oh = if (swap) src.width else src.height
                    val out = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(out)
                    val m = Matrix()
                    m.postRotate(rot.toFloat(), src.width / 2f, src.height / 2f)
                    m.postTranslate((ow - src.width) / 2f, (oh - src.height) / 2f)
                    canvas.drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG))
                    src.recycle()
                    WatermarkRenderer.draw(canvas, ow, oh, data, tpl)
                    PhotoSaver.save(this@MainActivity, out, data)
                    out.recycle()
                } catch (t: Throwable) {
                    error = t.message ?: "خطأ غير معروف"
                } finally {
                    image.close()
                }
                runOnUiThread {
                    capturing = false
                    if (error != null) {
                        toast("فشل حفظ الصورة: $error")
                    } else if (data.lat == null) {
                        toast("تم الحفظ بدون إحداثيات (لا توجد إشارة GPS)")
                    } else {
                        toast("تم حفظ الصورة في المعرض")
                    }
                }
            }

            override fun onError(exception: ImageCaptureException) {
                runOnUiThread {
                    capturing = false
                    toast("فشل الالتقاط: ${exception.message}")
                }
            }
        })
    }

    private fun shutterFeedback() {
        sound.play(MediaActionSound.SHUTTER_CLICK)
        shutter.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        flashOverlay.animate().cancel()
        flashOverlay.visibility = View.VISIBLE
        flashOverlay.alpha = 0.55f
        flashOverlay.animate().alpha(0f).setDuration(160).withEndAction {
            flashOverlay.visibility = View.INVISIBLE
        }.start()
    }
}
