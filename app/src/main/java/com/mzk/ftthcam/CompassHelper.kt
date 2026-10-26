package com.mzk.ftthcam

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.SystemClock
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * اتجاه البوصلة (Azimuth) بالنسبة للشمال الحقيقي، لاتجاه الكاميرا المستخدمة
 * (الخلفية: عكس محور Z للجهاز، الأمامية: نفس محور Z).
 */
class CompassHelper(ctx: Context, private val onChange: () -> Unit) : SensorEventListener {

    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val rot = FloatArray(9)
    private var sinAvg = 0.0
    private var cosAvg = 0.0
    private var initialized = false
    private var lastNotify = 0L

    @Volatile
    var azimuth: Float? = null
        private set

    @Volatile
    var declination = 0f

    @Volatile
    var facingBack = true

    fun start() {
        sensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    fun stop() {
        sm.unregisterListener(this)
    }

    fun setLocation(l: Location) {
        try {
            declination = GeomagneticField(
                l.latitude.toFloat(), l.longitude.toFloat(), l.altitude.toFloat(), System.currentTimeMillis()
            ).declination
        } catch (_: Exception) {
        }
    }

    override fun onSensorChanged(e: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rot, e.values)
        // اتجاه الكاميرا في إحداثيات العالم (X شرق، Y شمال)
        var east = -rot[2].toDouble()
        var north = -rot[5].toDouble()
        if (!facingBack) {
            east = -east
            north = -north
        }
        if (hypot(east, north) < 0.35) {
            // الهاتف شبه أفقي: نستخدم اتجاه الحافة العلوية للهاتف
            east = rot[1].toDouble()
            north = rot[4].toDouble()
        }
        val az = atan2(east, north)
        val s = sin(az)
        val c = cos(az)
        if (!initialized) {
            sinAvg = s
            cosAvg = c
            initialized = true
        } else {
            val a = 0.2
            sinAvg += a * (s - sinAvg)
            cosAvg += a * (c - cosAvg)
        }
        var deg = Math.toDegrees(atan2(sinAvg, cosAvg)).toFloat() + declination
        deg = ((deg % 360f) + 360f) % 360f
        azimuth = deg
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotify > 200) {
            lastNotify = now
            onChange()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
    }

    companion object {
        private val NAMES = arrayOf(
            "شمال", "شمال شرق", "شرق", "جنوب شرق", "جنوب", "جنوب غرب", "غرب", "شمال غرب"
        )

        fun dirName(az: Float): String {
            val a = ((az % 360f) + 360f) % 360f
            return NAMES[(((a + 22.5f) % 360f) / 45f).toInt() % 8]
        }
    }
}
