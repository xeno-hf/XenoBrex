package xb.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 配色三套，和 ReX 同一組：
 * - 翠晶（預設，暗）：黑底＋翡翠綠，藍與金點綴
 * - 緋焰（暗）：緋紅＋金飾，翠綠點綴
 * - 曦光（淺）：象牙白＋金飾，翠綠點綴
 * 顏色值照 ReX 的 `Theme.kt`，兩個程式放在一起看是同一家。
 */
data class XbPalette(
    val id: String,
    val light: Boolean,
    val bg: Color,
    val bg2: Color,
    val card: Color,
    val cardHi: Color,
    val line: Color,
    val text: Color,
    val muted: Color,
    /** 代表色（填色用）。 */
    val brand: Color,
    val brand2: Color,
    /** 代表色當文字用。 */
    val brandInk: Color,
    val onBrand: Color,
    /** 另一位的代表色（點綴）。 */
    val partner: Color,
    /** 核心水晶綠：正常／已存檔。 */
    val accent: Color,
    val warn: Color,
    val danger: Color,
    /** 改過的格子。要和代表色分得開。 */
    val mark: Color,
    /** 表格冷熱色：由低到高。取自各人身上的顏色。 */
    val heat: List<Color>,
    /** 冷熱色蓋在卡片底色上的比例；越高越鮮豔。 */
    val heatStrength: Float,
)

val Emerald = XbPalette(
    id = "EMERALD", light = false,
    bg = Color(0xFF0A0C0D), bg2 = Color(0xFF0D1614),
    card = Color(0xFF131A19), cardHi = Color(0xFF1A2624),
    line = Color(0xFF283A37),
    text = Color(0xFFF3F7F6), muted = Color(0xFF8CA59F),
    brand = Color(0xFF22D69A), brand2 = Color(0xFF7FE8D0), brandInk = Color(0xFF48EBB5), onBrand = Color(0xFF00241A),
    partner = Color(0xFF4F6BEA),
    accent = Color(0xFF22D69A),
    warn = Color(0xFFE9B949), danger = Color(0xFFFF3D55),
    mark = Color(0xFFE9B949),
    // 胸前的藍 → 翡翠綠 → 靴子的金 → 紅
    heat = listOf(Color(0xFF3F57D6), Color(0xFF1FA6A0), Color(0xFF22D69A), Color(0xFFD9B13F), Color(0xFFF0553F)),
    heatStrength = 0.50f,
)

val Ruby = XbPalette(
    id = "RUBY", light = false,
    bg = Color(0xFF110A0B), bg2 = Color(0xFF1E0D10),
    card = Color(0xFF1D1214), cardHi = Color(0xFF2A161A),
    line = Color(0xFF45242A),
    text = Color(0xFFFBEFEE), muted = Color(0xFFB39498),
    brand = Color(0xFFE5323F), brand2 = Color(0xFFFF6A55), brandInk = Color(0xFFFF5A64), onBrand = Color(0xFF2A0006),
    partner = Color(0xFFE8B54A),
    accent = Color(0xFF2FD9A8),
    warn = Color(0xFFE8B54A), danger = Color(0xFFFF2E4D),
    mark = Color(0xFF2FD9A8),
    // 核心水晶的綠 → 金飾 → 緋紅
    heat = listOf(Color(0xFF1E8F86), Color(0xFF2FD9A8), Color(0xFFE8B54A), Color(0xFFF07A3A), Color(0xFFE5323F)),
    heatStrength = 0.52f,
)

val Topaz = XbPalette(
    id = "TOPAZ", light = true,
    bg = Color(0xFFFCFAF4), bg2 = Color(0xFFF4ECD8),
    card = Color(0xFFFFFFFF), cardHi = Color(0xFFFFF6DC),
    line = Color(0xFFE6DABB),
    text = Color(0xFF1E1B16), muted = Color(0xFF7D7258),
    brand = Color(0xFFD9A021), brand2 = Color(0xFFF5D27A), brandInk = Color(0xFF8A5E00), onBrand = Color(0xFF2B1F00),
    partner = Color(0xFF14B892),
    accent = Color(0xFF0E9F7E),
    warn = Color(0xFFCC7A00), danger = Color(0xFFD7263D),
    mark = Color(0xFF2F6FDB),
    // 水晶的綠 → 白 → 金 → 琥珀
    heat = listOf(Color(0xFF3CCFA8), Color(0xFFBFEBDD), Color(0xFFFFF3C4), Color(0xFFF5C84B), Color(0xFFEE8A2E)),
    heatStrength = 0.78f,
)

enum class ThemeChoice {
    EMERALD, RUBY, TOPAZ, SYSTEM;

    /** 顯示名稱（跟 ReX 同一組譯名）。 */
    val hint: String get() = tr("theme.$name")

    companion object {
        fun parse(s: String?): ThemeChoice = entries.firstOrNull { it.name == s } ?: EMERALD
    }
}

/** 目前配色。讀取會訂閱 Compose 狀態，換主題時全部畫面自動重繪。 */
object Xb {
    var palette by mutableStateOf(Emerald)

    val bg get() = palette.bg
    val panel get() = palette.card
    val panelHi get() = palette.cardHi
    val line get() = palette.line
    val text get() = palette.text
    val dim get() = palette.muted
    val brand get() = palette.brand
    val brandInk get() = palette.brandInk
    val onBrand get() = palette.onBrand
    val partner get() = palette.partner
    val accent get() = palette.accent
    val warn get() = palette.warn
    val bad get() = palette.danger
    val modified get() = palette.mark
    val header get() = palette.card
    val headerEditable get() = lerp(palette.card, palette.brand, if (palette.light) 0.22f else 0.16f)
    val selection get() = if (palette.light) palette.brandInk else palette.brand2

    /** 表格一格的底色。[h]：0～1 的冷熱程度，NaN 表示不上色。 */
    fun heat(h: Float): Color {
        val p = palette
        if (h.isNaN()) return p.cardHi
        val stops = p.heat
        val x = h.coerceIn(0f, 1f) * (stops.size - 1)
        val i = x.toInt().coerceAtMost(stops.size - 2)
        return lerp(p.card, lerp(stops[i], stops[i + 1], x - i), p.heatStrength)
    }

    /** 數字用的字型：Oxanium（OFL），數字等寬、方正。和 ReX 的儀表同一套。 */
    val digits: FontFamily = FontFamily(
        Font(resource = "font/oxanium_medium.ttf", weight = FontWeight.Medium),
        Font(resource = "font/oxanium_semibold.ttf", weight = FontWeight.SemiBold),
        Font(resource = "font/oxanium_bold.ttf", weight = FontWeight.Bold),
        Font(resource = "font/oxanium_extrabold.ttf", weight = FontWeight.ExtraBold),
    )

    fun digits(size: TextUnit, weight: FontWeight = FontWeight.Medium, italic: Boolean = false, color: Color = Color.Unspecified) =
        TextStyle(fontFamily = digits, fontSize = size, fontWeight = weight, fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal, color = color)
}

@Composable
fun XbTheme(choice: ThemeChoice = ThemeChoice.EMERALD, content: @Composable () -> Unit) {
    val p = when (choice) {
        ThemeChoice.EMERALD -> Emerald
        ThemeChoice.RUBY -> Ruby
        ThemeChoice.TOPAZ -> Topaz
        ThemeChoice.SYSTEM -> if (isSystemInDarkTheme()) Emerald else Topaz
    }
    Xb.palette = p
    val scheme = if (p.light) lightColorScheme(
        primary = p.brandInk, onPrimary = Color.White, secondary = p.accent,
        background = p.bg, onBackground = p.text, surface = p.card, onSurface = p.text,
        surfaceVariant = p.cardHi, onSurfaceVariant = p.muted, outline = p.line, error = p.danger,
        surfaceContainerHigh = p.card, surfaceContainerHighest = p.cardHi,
    ) else darkColorScheme(
        primary = p.brand, onPrimary = p.onBrand, secondary = p.accent,
        background = p.bg, onBackground = p.text, surface = p.card, onSurface = p.text,
        surfaceVariant = p.cardHi, onSurfaceVariant = p.muted, outline = p.line, error = p.danger,
        surfaceContainerHigh = p.cardHi, surfaceContainerHighest = p.cardHi,
    )
    MaterialTheme(colorScheme = scheme, content = content)
}

/** 背景：暗色＝漸層＋碳纖斜紋＋代表色柔光；淺色＝象牙白＋頂部金色柔光。和 ReX 相同。 */
fun Modifier.xbBackground(): Modifier = drawBehind {
    val p = Xb.palette
    drawRect(Brush.verticalGradient(listOf(p.bg, p.bg2)))
    if (p.light) {
        drawRect(Brush.radialGradient(listOf(p.brand2.copy(alpha = 0.22f), Color.Transparent), center = Offset(size.width * 0.5f, 0f), radius = size.maxDimension * 0.7f))
    } else {
        drawRect(Brush.radialGradient(listOf(p.brand.copy(alpha = 0.10f), Color.Transparent), center = Offset(size.width * 0.5f, size.height * 0.35f), radius = size.maxDimension * 0.6f))
        val step = 6.dp.toPx()
        var x = -size.height
        while (x < size.width) {
            drawLine(Color.White.copy(alpha = 0.018f), Offset(x, size.height), Offset(x + size.height, 0f), strokeWidth = step / 2)
            x += step
        }
    }
}

/** 核心水晶：菱形＋十字紋。和 ReX 的狀態燈同一個形狀。 */
fun DrawScope.crystal(color: Color, center: Offset, w: Float, h: Float, glow: Boolean = false, cross: Boolean = true) {
    val gem = Path().apply {
        moveTo(center.x, center.y - h); lineTo(center.x + w * 0.62f, center.y); lineTo(center.x, center.y + h); lineTo(center.x - w * 0.62f, center.y); close()
    }
    if (glow) drawCircle(color.copy(alpha = 0.25f), radius = maxOf(w, h) * 1.25f, center = center)
    drawPath(gem, color)
    if (cross) {
        val s = maxOf(1f, w * 0.14f)
        drawLine(Color.White.copy(alpha = 0.7f), Offset(center.x, center.y - h * 0.55f), Offset(center.x, center.y + h * 0.55f), strokeWidth = s)
        drawLine(Color.White.copy(alpha = 0.7f), Offset(center.x - w * 0.32f, center.y), Offset(center.x + w * 0.32f, center.y), strokeWidth = s)
    }
}

/** 程式圖示：深色圓角方塊裡一顆發光的核心水晶。固定用翠晶的顏色。 */
object AppIcon : Painter() {
    override val intrinsicSize: Size = Size(256f, 256f)

    override fun DrawScope.onDraw() {
        val p = Emerald
        val r = size.minDimension * 0.22f
        drawRoundRect(Brush.verticalGradient(listOf(p.bg, p.bg2)), cornerRadius = CornerRadius(r, r))
        drawRoundRect(Brush.radialGradient(listOf(p.brand.copy(alpha = 0.30f), Color.Transparent), center = center, radius = size.minDimension * 0.62f), cornerRadius = CornerRadius(r, r))
        drawRoundRect(p.line, cornerRadius = CornerRadius(r, r), style = Stroke(size.minDimension * 0.02f))
        // 兩道發光的紋路
        val w = size.minDimension
        drawLine(Brush.horizontalGradient(listOf(Color.Transparent, p.brand2, p.partner, Color.Transparent)), Offset(w * 0.12f, w * 0.80f), Offset(w * 0.88f, w * 0.80f), strokeWidth = w * 0.022f)
        crystal(p.brand, center.copy(y = center.y - w * 0.04f), w * 0.26f, w * 0.34f, glow = true)
    }
}

@Composable
fun CrystalDot(color: Color, glow: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier) { crystal(color, center, size.width / 2, size.height / 2, glow) }
}

/**
 * 字標：Xeno＋B＋ReX。前半用一般字色，結尾的「re」「x」照 ReX 標誌的配色（代表色＋另一位的顏色）。
 */
@Composable
fun Wordmark(size: Int) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text("XenoB", style = Xb.digits(size.sp, FontWeight.ExtraBold, italic = true), color = Xb.text)
        Text("re", style = Xb.digits(size.sp, FontWeight.ExtraBold, italic = true), color = Xb.brandInk)
        Text("x", style = Xb.digits(size.sp, FontWeight.ExtraBold, italic = true), color = Xb.partner)
    }
}
