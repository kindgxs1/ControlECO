package com.controleco.app.ui.common

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.controleco.app.data.CategoryIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 分类图标（列表/选择器共用）：按 [icon] 规格渲染——
 * 默认（空）走「按分类名取内置图形」，也可为 emoji、指定内置图形、相册上传的图片。
 */
@Composable
fun CategoryLogo(categoryName: String, icon: String, size: Dp = 40.dp) {
    val emoji = CategoryIcon.emojiOf(icon)
    val glyphName = CategoryIcon.glyphOf(icon)
    val customColor = CategoryIcon.glyphColorOf(icon)
    val imageFile = CategoryIcon.imageOf(icon)
    when {
        emoji != null -> {
            val fontSize = with(LocalDensity.current) { (size * 0.78f).toSp() }
            Box(Modifier.size(size), contentAlignment = Alignment.Center) {
                Text(emoji, fontSize = fontSize, maxLines = 1, softWrap = false)
            }
        }
        imageFile != null -> ImageLogo(categoryName, imageFile, size)
        glyphName != null -> {
            val glyph = glyphByName(glyphName)
            if (glyph == null) {
                CategoryGlyph(categoryName, size)
            } else {
                // 自定义颜色优先；没写颜色才回落到"按分类名取色"
                val tint = customColor?.let { Color(it or 0xFF000000.toInt()) }
                    ?: glyphTint(glyph, categoryName)
                GlyphIcon(glyph = glyph, tint = tint, size = size)
            }
        }
        else -> {
            // 默认：按分类名取形。若只设了颜色（`GLYPH::RRGGBB`），形状照旧、颜色用自定义的
            val custom = customColor
            if (custom == null) {
                CategoryGlyph(categoryName, size)
            } else {
                val glyph = remember(categoryName) { glyphForName(categoryName) }
                GlyphIcon(glyph = glyph, tint = Color(custom or 0xFF000000.toInt()), size = size)
            }
        }
    }
}

/** 相册上传的图标：内部存储解码（有缓存），解码未完成前留白，不闪默认图形 */
@Composable
private fun ImageLogo(categoryName: String, fileName: String, size: Dp) {
    val context = LocalContext.current
    // 用 remember + LaunchedEffect 代替 produceState：语义一致（fileName 变化才重新解码），
    // 也避开 compose lint 对 produceState 赋值形式的误判（ProduceStateDoesNotAssignValue）。
    val bitmapState = remember(fileName) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(fileName) {
        bitmapState.value = withContext(Dispatchers.IO) { CategoryIcon.loadImage(context, fileName) }
    }
    val bmp = bitmapState.value
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = categoryName,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(size * 0.30f)),
        )
    } else {
        Box(Modifier.size(size))
    }
}
