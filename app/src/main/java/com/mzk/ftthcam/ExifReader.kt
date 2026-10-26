package com.mzk.ftthcam

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.IOException
import java.io.InputStream
import java.net.URLDecoder

data class PhotoInfo(
    val lat: Double?,
    val lon: Double?,
    val date: String?,
    val time: String?,
    val azimuth: Double?,
    val fat: String?,
    val zone: String?,
    val rotation: Int
)

object ExifReader {

    /**
     * يفتح الصورة مفضّلاً النسخة الأصلية (غير المحجوبة الموقع) على أندرويد 10+.
     * وإن لم تتوفر الصلاحية يعود للفتح العادي.
     */
    fun openStream(ctx: Context, uri: Uri): InputStream? {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                val media = if (DocumentsContract.isDocumentUri(ctx, uri)) MediaStore.getMediaUri(ctx, uri) else uri
                if (media != null) {
                    return ctx.contentResolver.openInputStream(MediaStore.setRequireOriginal(media))
                }
            } catch (_: Exception) {
                // نعود للفتح العادي
            }
        }
        return ctx.contentResolver.openInputStream(uri)
    }

    fun read(ctx: Context, uri: Uri): PhotoInfo {
        val stream = openStream(ctx, uri) ?: throw IOException("تعذر فتح الصورة")
        return stream.use { ins ->
            val exif = ExifInterface(ins)
            val ll = exif.latLong
            var date: String? = null
            var time: String? = null

            val raw = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?: exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED)
                ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
            if (raw != null) {
                val parts = raw.trim().split(" ")
                if (parts.size >= 2) {
                    date = parts[0].replace(':', '/')
                    time = parts[1]
                }
            }
            if (date == null) {
                exif.getAttribute(ExifInterface.TAG_GPS_DATESTAMP)?.let { date = it.replace(':', '/') + " (UTC)" }
                gpsTime(exif.getAttribute(ExifInterface.TAG_GPS_TIMESTAMP))?.let { time = "$it (UTC)" }
            }

            var az: Double? = exif.getAttributeDouble(ExifInterface.TAG_GPS_IMG_DIRECTION, -1.0)
            if (az != null && az < 0) az = null

            var fat: String? = null
            var zone: String? = null
            val desc = exif.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION)
            if (desc != null && desc.startsWith("FTTH|")) {
                for (part in desc.split("|")) {
                    try {
                        if (part.startsWith("FAT=")) fat = URLDecoder.decode(part.substring(4), "UTF-8").ifBlank { null }
                        if (part.startsWith("ZONE=")) zone = URLDecoder.decode(part.substring(5), "UTF-8").ifBlank { null }
                    } catch (_: Exception) {
                    }
                }
            }

            PhotoInfo(
                lat = ll?.get(0),
                lon = ll?.get(1),
                date = date,
                time = time,
                azimuth = az,
                fat = fat,
                zone = zone,
                rotation = exif.rotationDegrees
            )
        }
    }

    /** "14/1,3/1,9/1" -> "14:03:09" */
    private fun gpsTime(s: String?): String? {
        if (s == null) return null
        return try {
            val p = s.split(",").map {
                val q = it.trim().split("/")
                q[0].toDouble() / (if (q.size > 1) q[1].toDouble() else 1.0)
            }
            if (p.size < 3) null else String.format("%02d:%02d:%02d", p[0].toInt(), p[1].toInt(), p[2].toInt())
        } catch (_: Exception) {
            null
        }
    }

    /** يقرأ نسخة مصغّرة من الصورة للعرض مع تطبيق الدوران. */
    fun decodeScaled(ctx: Context, uri: Uri, maxDim: Int, rotation: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: return null
        if (rotation == 0) return bmp
        val m = Matrix().apply { postRotate(rotation.toFloat()) }
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        if (rotated !== bmp) bmp.recycle()
        return rotated
    }
}
