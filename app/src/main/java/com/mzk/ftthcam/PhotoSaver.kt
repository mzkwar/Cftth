package com.mzk.ftthcam

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** يحفظ الصورة (مع العلامة المائية) في المعرض ويكتب بيانات EXIF (الموقع/التاريخ/الاتجاه/FAT/ZONE). */
object PhotoSaver {

    private const val FOLDER = "FTTH-Camera"

    @Suppress("DEPRECATION")
    fun save(ctx: Context, bmp: Bitmap, d: WmData): Uri {
        val name = "FTTH_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(d.timeMs)) + ".jpg"
        val tmp = File(ctx.cacheDir, name)
        FileOutputStream(tmp).use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        try {
            writeExif(tmp, d)
        } catch (_: Exception) {
            // الصورة تُحفظ حتى لو فشلت كتابة EXIF
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.DATE_TAKEN, d.timeMs)
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/$FOLDER")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), FOLDER)
                dir.mkdirs()
                put(MediaStore.MediaColumns.DATA, File(dir, name).absolutePath)
            }
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("تعذر إنشاء الملف في المعرض")
        try {
            val out = resolver.openOutputStream(uri) ?: throw IOException("تعذر فتح الملف للكتابة")
            out.use { o -> FileInputStream(tmp).use { it.copyTo(o) } }
            if (Build.VERSION.SDK_INT >= 29) {
                val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
            }
        } catch (e: Exception) {
            try {
                resolver.delete(uri, null, null)
            } catch (_: Exception) {
            }
            throw e
        } finally {
            tmp.delete()
        }
        return uri
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun writeExif(file: File, d: WmData) {
        val exif = ExifInterface(file.absolutePath)
        val lat = d.lat
        val lon = d.lon
        if (lat != null && lon != null) exif.setLatLong(lat, lon)
        val stamp = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date(d.timeMs))
        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, stamp)
        exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, stamp)
        exif.setAttribute(ExifInterface.TAG_DATETIME, stamp)
        val az = d.azimuth
        if (az != null) {
            exif.setAttribute(ExifInterface.TAG_GPS_IMG_DIRECTION, "${(az * 100).roundToInt()}/100")
            exif.setAttribute(ExifInterface.TAG_GPS_IMG_DIRECTION_REF, "T")
        }
        exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, "FTTH|FAT=${enc(d.fat)}|ZONE=${enc(d.zone)}")
        exif.setAttribute(ExifInterface.TAG_SOFTWARE, "FTTH Field Camera")
        exif.saveAttributes()
    }
}
