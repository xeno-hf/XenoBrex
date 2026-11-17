package xb.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Hint(text: String, content: @Composable () -> Unit) {
    TooltipArea(tooltip = {
        val shape = RoundedCornerShape(8.dp)
        Box(Modifier.background(Xb.panelHi, shape).border(1.dp, Xb.line, shape).padding(horizontal = 9.dp, vertical = 5.dp)) {
            Text(text, fontSize = 12.sp, color = Xb.text)
        }
    }, content = content)
}

/** 按鈕：圓角方塊、細框；主要動作用代表色填滿。和 ReX 的按鈕同一個樣子。 */
@Composable
fun ToolButton(label: String, hint: String, enabled: Boolean = true, primary: Boolean = false, tag: String = label, digits: Boolean = false, onClick: () -> Unit) {
    Hint(hint) {
        val shape = RoundedCornerShape(10.dp)
        val source = remember { MutableInteractionSource() }
        val hovered by source.collectIsHoveredAsState()
        val fill = when {
            primary && enabled -> if (hovered) Xb.palette.brand2 else Xb.brand
            hovered && enabled -> Xb.panelHi
            else -> Xb.panel
        }
        Box(
            Modifier
                .clip(shape)
                .background(fill)
                .border(1.dp, if (primary && enabled) Color.Transparent else if (hovered && enabled) Xb.brand.copy(alpha = 0.55f) else Xb.line, shape)
                .hoverable(source, enabled)
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 7.dp)
                .testTag(tag),
            contentAlignment = Alignment.Center,
        ) {
            val color = if (!enabled) Xb.dim.copy(alpha = 0.45f) else if (primary) Xb.onBrand else Xb.text
            // 按鈕上的字不折行：空間不夠時由外面的列換行，不要把字擠成直的。
            if (digits) Text(label, style = Xb.digits(13.sp, FontWeight.SemiBold), color = color, maxLines = 1, softWrap = false)
            else Text(label, fontSize = 13.sp, fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Normal, color = color, maxLines = 1, softWrap = false)
        }
    }
}

/** 小標籤：淡淡的底色加字。 */
@Composable
fun Pill(text: String, color: Color = Xb.dim, digits: Boolean = false) {
    Box(Modifier.background(color.copy(alpha = if (Xb.palette.light) 0.16f else 0.14f), RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 2.dp)) {
        if (digits) Text(text, style = Xb.digits(11.5.sp), color = color) else Text(text, fontSize = 11.5.sp, color = color)
    }
}

/** 裝飾線：代表色從中間亮到兩邊淡掉。取自三人身上發光的紋路。 */
@Composable
fun EtherLine(modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxWidth().height(2.dp).drawBehind {
            val p = Xb.palette
            drawRect(Brush.horizontalGradient(listOf(Color.Transparent, p.brand.copy(alpha = 0.85f), p.brand2, p.partner.copy(alpha = 0.7f), Color.Transparent)))
        },
    )
}

/**
 * 字標旁的那顆水晶（小彩蛋）：按一下換下一套配色。正式的換主題在設定頁；這裡不給提示、不顯示可以按的樣子。
 * 顏色是目前這套的代表色；「自動」畫成左半綠、右半金。
 */
@Composable
fun ThemeCrystal(current: ThemeChoice, width: Int = 11, height: Int = 15, onNext: () -> Unit) {
    run {
        Box(
            Modifier
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onNext)
                .padding(horizontal = 7.dp, vertical = 5.dp)
                .testTag("themeNext"),
            contentAlignment = Alignment.Center,
        ) {
            val size = Modifier.size(width.dp, height.dp)
            if (current == ThemeChoice.SYSTEM) {
                Canvas(size) {
                    crystal(Emerald.brand, center, this.size.width / 2, this.size.height / 2, glow = true, cross = false)
                    val right = androidx.compose.ui.graphics.Path().apply {
                        moveTo(center.x, 0f); lineTo(center.x + this@Canvas.size.width / 2 * 0.62f, center.y); lineTo(center.x, this@Canvas.size.height); close()
                    }
                    drawPath(right, Topaz.brand)
                    drawLine(Color.White.copy(alpha = 0.7f), androidx.compose.ui.geometry.Offset(center.x, this.size.height * 0.2f), androidx.compose.ui.geometry.Offset(center.x, this.size.height * 0.8f), strokeWidth = 1f)
                }
            } else {
                CrystalDot(Xb.brand, glow = true, size)
            }
        }
    }
}

/** 字標本身是一顆按鈕：按下去看「關於」。 */
@Composable
fun LogoButton(size: Int, onClick: () -> Unit) {
    Hint(tr("about.hint")) {
        Box(Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 2.dp).testTag("logo")) {
            Wordmark(size)
        }
    }
}

/** 面板：半透明的卡片底、圓角、細框，讓背景的紋路透出來一點。 */
fun Modifier.panel(radius: Int = 14): Modifier {
    val shape = RoundedCornerShape(radius.dp)
    return this.clip(shape).background(Xb.panel.copy(alpha = if (Xb.palette.light) 0.86f else 0.78f)).border(1.dp, Xb.line, shape)
}
