package com.mzk.ftthcam

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import kotlin.math.roundToInt

/** يجعل النص (أرقام/لاتيني) يظهر من اليسار لليمين داخل سطر عربي. */
fun ltr(s: String): String = "‪$s‬"

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

fun Context.toast(msg: String) {
    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

object Nav {
    /** يفتح Google Maps مباشرة بوضع الملاحة إلى الإحداثيات؛ وإن لم يوجد التطبيق يفتح الرابط في المتصفح. */
    fun navigateTo(ctx: Context, lat: Double, lon: Double) {
        val nav = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$lat,$lon&mode=d"))
            .setPackage("com.google.android.apps.maps")
        try {
            ctx.startActivity(nav)
            return
        } catch (_: ActivityNotFoundException) {
        } catch (_: SecurityException) {
        }
        val web = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$lat,$lon&travelmode=driving")
        )
        try {
            ctx.startActivity(web)
        } catch (_: Exception) {
            ctx.toast("لا يوجد تطبيق خرائط أو متصفح لفتح الموقع")
        }
    }

    /** يفتح رابط فيسبوك عبر التطبيق الرسمي، وإن لم يوجد فعبر المتصفح. */
    fun openFacebook(ctx: Context, url: String) {
        val app = Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage("com.facebook.katana")
        try {
            ctx.startActivity(app)
            return
        } catch (_: ActivityNotFoundException) {
        } catch (_: SecurityException) {
        }
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            ctx.toast("تعذر فتح الرابط")
        }
    }
}
