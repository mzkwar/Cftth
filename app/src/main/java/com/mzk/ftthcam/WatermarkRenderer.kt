package com.mzk.ftthcam

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.TextUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

data class WmData(
    val lat: Double?,
    val lon: Double?,
    val accuracy: Float?,
    val address: String?,
    val azimuth: Float?,
    val fat: String,
    val zone: String,
    val timeMs: Long
)

/** يرسم طبقة البيانات (العلامة المائية) مباشرة على الـ Bitmap بعدة قوالب. */
object WatermarkRenderer {

    val TEMPLATE_NAMES = listOf(
        "شريط سفلي كامل",
        "مضغوط (زاوية)",
        "شريط علوي + سفلي",
        "مختصر",
        "كبير للقراءة"
    )

    private const val YELLOW = 0xFFFFD60A.toInt()

    fun draw(canvas: Canvas, w: Int, h: Int, d: WmData, template: Int) {
        val u = w / 100f
        when (template) {
            1 -> compact(canvas, w, h, u, d)
            2 -> topBottom(canvas, w, h, u, d)
            3 -> minimal(canvas, w, h, u, d)
            4 -> large(canvas, w, h, u, d)
            else -> fullBar(canvas, w, h, u, d)
        }
    }

    // ---------- النصوص ----------

    private fun fatZone(d: WmData): String {
        val fat = d.fat.ifBlank { "-" }
        val zone = d.zone.ifBlank { "-" }
        return ltr("FAT: $fat  |  ZONE: $zone")
    }

    private fun coords(d: WmData): String {
        val lat = d.lat
        val lon = d.lon
        return if (lat != null && lon != null) {
            ltr(String.format(Locale.US, "%.6f, %.6f", lat, lon))
        } else {
            "غير متاح (لا توجد إشارة GPS)"
        }
    }

    private fun azimuthText(d: WmData): String {
        val az = d.azimuth ?: return "غير متاح"
        return ltr("${az.roundToInt() % 360}°") + " " + CompassHelper.dirName(az)
    }

    private fun dateText(d: WmData): String =
        ltr(SimpleDateFormat("yyyy/MM/dd  HH:mm:ss", Locale.US).format(Date(d.timeMs)))

    private fun addressText(d: WmData): String = d.address ?: "غير متاح"

    // ---------- أدوات الرسم ----------

    private fun paint(u: Float, sizeUnits: Float, color: Int = Color.WHITE, bold: Boolean = true) =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = sizeUnits * u
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            setShadowLayer(0.35f * u, 0f, 0f, Color.BLACK)
        }

    private fun lay(
        text: String,
        p: TextPaint,
        width: Int,
        align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL,
        maxLines: Int = 1
    ): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, p, width.coerceAtLeast(1))
            .setAlignment(align)
            .setTextDirection(TextDirectionHeuristics.RTL)
            .setIncludePad(false)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()

    private fun stackHeight(ls: List<StaticLayout>, gap: Float): Float {
        var total = 0f
        for (l in ls) total += l.height
        return total + gap * (ls.size - 1)
    }

    private fun drawStack(c: Canvas, ls: List<StaticLayout>, x: Float, y: Float, gap: Float) {
        var yy = y
        for (l in ls) {
            c.save()
            c.translate(x, yy)
            l.draw(c)
            c.restore()
            yy += l.height + gap
        }
    }

    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        this.color = color
    }

    private fun drawCompass(c: Canvas, cx: Float, cy: Float, r: Float, az: Float, u: Float) {
        c.drawCircle(cx, cy, r, fill(0x99000000.toInt()))
        c.drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 0.5f * u
            color = Color.WHITE
        })
        c.save()
        c.rotate(-az, cx, cy)
        val north = Path().apply {
            moveTo(cx, cy - r * 0.85f)
            lineTo(cx - r * 0.24f, cy)
            lineTo(cx + r * 0.24f, cy)
            close()
        }
        val south = Path().apply {
            moveTo(cx, cy + r * 0.85f)
            lineTo(cx - r * 0.24f, cy)
            lineTo(cx + r * 0.24f, cy)
            close()
        }
        c.drawPath(north, fill(0xFFFF3B30.toInt()))
        c.drawPath(south, fill(Color.WHITE))
        c.restore()
    }

    // ---------- القوالب ----------

    /** 0: شريط سفلي بعرض الصورة + بوصلة */
    private fun fullBar(c: Canvas, w: Int, h: Int, u: Float, d: WmData) {
        val pad = 2.6f * u
        val az = d.azimuth
        val r = 5.6f * u
        val compassSpace = if (az != null) (2 * r + pad) else 0f
        val textW = (w - 2 * pad - compassSpace).toInt()
        val p = paint(u, 3.3f)
        val ls = listOf(
            lay("العنوان: " + addressText(d), p, textW, maxLines = 2),
            lay(fatZone(d), paint(u, 3.6f, YELLOW), textW),
            lay("الإحداثيات: " + coords(d), p, textW),
            lay("الاتجاه: " + azimuthText(d), p, textW),
            lay(dateText(d), paint(u, 3.0f, 0xFFDDDDDD.toInt(), bold = false), textW)
        )
        val gap = 0.9f * u
        val contentH = maxOf(stackHeight(ls, gap), if (az != null) 2 * r else 0f)
        val top = h - contentH - 2 * pad
        c.drawRect(0f, top, w.toFloat(), h.toFloat(), fill(0xB3000000.toInt()))
        drawStack(c, ls, pad + compassSpace, top + pad + (contentH - stackHeight(ls, gap)) / 2f, gap)
        if (az != null) {
            drawCompass(c, pad + r, top + pad + contentH / 2f, r, az, u)
        }
    }

    /** 1: كتلة صغيرة في الزاوية السفلية */
    private fun compact(c: Canvas, w: Int, h: Int, u: Float, d: WmData) {
        val pad = 2.2f * u
        val blockW = (w * 0.64f).toInt()
        val inner = (blockW - 2 * pad).toInt()
        val az = d.azimuth
        val azPart = if (az != null) "  •  " + azimuthText(d) else ""
        val ls = listOf(
            lay(fatZone(d), paint(u, 3.5f, YELLOW), inner),
            lay(coords(d), paint(u, 3.0f), inner),
            lay(dateText(d) + azPart, paint(u, 2.7f, 0xFFDDDDDD.toInt(), bold = false), inner)
        )
        val gap = 0.7f * u
        val bh = stackHeight(ls, gap) + 2 * pad
        val margin = 2f * u
        val left = w - blockW - margin
        val top = h - bh - margin
        c.drawRoundRect(RectF(left, top, left + blockW, top + bh), 1.6f * u, 1.6f * u, fill(0xA6000000.toInt()))
        drawStack(c, ls, left + pad, top + pad, gap)
    }

    /** 2: شريط علوي (FAT/ZONE + التاريخ) وسفلي (العنوان والإحداثيات والاتجاه) */
    private fun topBottom(c: Canvas, w: Int, h: Int, u: Float, d: WmData) {
        val pad = 2.4f * u
        val inner = (w - 2 * pad).toInt()
        val topLs = listOf(
            lay(fatZone(d), paint(u, 4.4f, YELLOW), inner, Layout.Alignment.ALIGN_CENTER),
            lay(dateText(d), paint(u, 3.0f, 0xFFDDDDDD.toInt(), bold = false), inner, Layout.Alignment.ALIGN_CENTER)
        )
        val gapTop = 0.6f * u
        val topH = stackHeight(topLs, gapTop) + 2 * pad
        c.drawRect(0f, 0f, w.toFloat(), topH, fill(0xB3000000.toInt()))
        drawStack(c, topLs, pad, pad, gapTop)

        val p = paint(u, 3.2f)
        val botLs = listOf(
            lay("العنوان: " + addressText(d), p, inner, maxLines = 2),
            lay("الإحداثيات: " + coords(d), p, inner),
            lay("الاتجاه: " + azimuthText(d), p, inner)
        )
        val gap = 0.8f * u
        val botH = stackHeight(botLs, gap) + 2 * pad
        c.drawRect(0f, h - botH, w.toFloat(), h.toFloat(), fill(0xB3000000.toInt()))
        drawStack(c, botLs, pad, h - botH + pad, gap)
    }

    /** 3: سطران فقط */
    private fun minimal(c: Canvas, w: Int, h: Int, u: Float, d: WmData) {
        val pad = 2f * u
        val inner = (w - 2 * pad).toInt()
        val az = d.azimuth
        val azPart = if (az != null) "   " + azimuthText(d) else ""
        val ls = listOf(
            lay(fatZone(d), paint(u, 3.2f, YELLOW), inner, Layout.Alignment.ALIGN_CENTER),
            lay(coords(d) + azPart, paint(u, 3.0f), inner, Layout.Alignment.ALIGN_CENTER)
        )
        val gap = 0.6f * u
        val bh = stackHeight(ls, gap) + 2 * pad
        c.drawRect(0f, h - bh, w.toFloat(), h.toFloat(), fill(0x99000000.toInt()))
        drawStack(c, ls, pad, h - bh + pad, gap)
    }

    /** 4: خط كبير عالي التباين للقراءة تحت الشمس */
    private fun large(c: Canvas, w: Int, h: Int, u: Float, d: WmData) {
        val pad = 3f * u
        val inner = (w - 2 * pad).toInt()
        val ls = listOf(
            lay(fatZone(d), paint(u, 5.0f, YELLOW), inner),
            lay(coords(d), paint(u, 4.6f, YELLOW), inner),
            lay("الاتجاه: " + azimuthText(d), paint(u, 4.0f, YELLOW), inner),
            lay(addressText(d), paint(u, 3.2f), inner, maxLines = 2),
            lay(dateText(d), paint(u, 3.0f, 0xFFDDDDDD.toInt(), bold = false), inner)
        )
        val gap = 1.0f * u
        val bh = stackHeight(ls, gap) + 2 * pad
        c.drawRect(0f, h - bh, w.toFloat(), h.toFloat(), fill(0xE6000000.toInt()))
        drawStack(c, ls, pad, h - bh + pad, gap)
    }
}
