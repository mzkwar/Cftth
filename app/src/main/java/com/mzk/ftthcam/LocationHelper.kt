package com.mzk.ftthcam

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat

/**
 * يتتبع أفضل موقع متاح (GPS + الشبكة) ويحدّث العنوان في الخلفية
 * حتى تكون البيانات جاهزة لحظة الالتقاط بدون انتظار.
 */
class LocationHelper(
    private val ctx: Context,
    private val onChange: () -> Unit
) : LocationListener {

    private val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    var current: Location? = null
        private set

    @Volatile
    var address: String? = null
        private set

    private var addrLoc: Location? = null
    private var addrBusy = false
    private var addrAttemptAt = 0L

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start() {
        if (!hasPermission()) return
        try {
            lm.removeUpdates(this)
        } catch (_: Exception) {
        }
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                if (lm.isProviderEnabled(p)) {
                    lm.requestLocationUpdates(p, 1000L, 0f, this, Looper.getMainLooper())
                    lm.getLastKnownLocation(p)?.let { consider(it) }
                }
            } catch (_: Exception) {
            }
        }
    }

    fun stop() {
        try {
            lm.removeUpdates(this)
        } catch (_: Exception) {
        }
    }

    override fun onLocationChanged(location: Location) {
        consider(location)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
    }

    override fun onProviderEnabled(provider: String) {
    }

    override fun onProviderDisabled(provider: String) {
    }

    private fun consider(l: Location) {
        val c = current
        if (c == null || isBetter(l, c)) {
            current = l
            maybeGeocode(l)
            onChange()
        }
    }

    private fun isBetter(n: Location, o: Location): Boolean {
        val dt = n.time - o.time
        val sigNewer = dt > 15_000
        val sigOlder = dt < -15_000
        val newer = dt > 0
        val accDelta = n.accuracy - o.accuracy
        val moreAccurate = accDelta < 0
        val lessAccurate = accDelta > 0
        val sigLess = accDelta > 100
        if (sigNewer) return true
        if (sigOlder) return false
        if (moreAccurate) return true
        if (newer && !lessAccurate) return true
        if (newer && !sigLess && n.provider == o.provider) return true
        return false
    }

    private fun maybeGeocode(l: Location) {
        val now = SystemClock.elapsedRealtime()
        val prev = addrLoc
        if (prev != null && prev.distanceTo(l) > 150f) address = null
        val moved = prev == null || prev.distanceTo(l) > 30f
        val retry = address == null && now - addrAttemptAt > 15_000
        if (addrBusy || !(moved || retry)) return
        addrBusy = true
        addrAttemptAt = now
        GeoUtil.reverse(ctx, l.latitude, l.longitude) { a ->
            main.post {
                addrBusy = false
                if (a != null) {
                    address = a
                    addrLoc = l
                }
                onChange()
            }
        }
    }
}
