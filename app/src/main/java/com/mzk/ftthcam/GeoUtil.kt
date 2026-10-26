package com.mzk.ftthcam

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import java.util.Locale
import java.util.concurrent.Executors

object GeoUtil {
    private val executor = Executors.newSingleThreadExecutor()

    /** تحويل الإحداثيات إلى عنوان نصي. الـ callback قد يُستدعى من أي Thread. */
    @Suppress("DEPRECATION")
    fun reverse(ctx: Context, lat: Double, lon: Double, cb: (String?) -> Unit) {
        if (!Geocoder.isPresent()) {
            cb(null)
            return
        }
        val geocoder = Geocoder(ctx.applicationContext, Locale("ar"))
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                geocoder.getFromLocation(lat, lon, 1, object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) {
                        cb(format(addresses.firstOrNull()))
                    }

                    override fun onError(errorMessage: String?) {
                        cb(null)
                    }
                })
            } catch (e: Exception) {
                cb(null)
            }
        } else {
            executor.execute {
                val result = try {
                    geocoder.getFromLocation(lat, lon, 1)?.firstOrNull()
                } catch (e: Exception) {
                    null
                }
                cb(format(result))
            }
        }
    }

    private fun format(a: Address?): String? {
        if (a == null) return null
        val line = if (a.maxAddressLineIndex >= 0) a.getAddressLine(0) else null
        if (!line.isNullOrBlank()) return line
        val joined = listOfNotNull(a.thoroughfare, a.subLocality, a.locality, a.adminArea, a.countryName)
            .filter { it.isNotBlank() }
            .joinToString("، ")
        return joined.ifBlank { null }
    }
}
