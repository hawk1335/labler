package io.github.toolicious.labler.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Typeface
import io.github.toolicious.labler.model.LabelFont
import io.github.toolicious.labler.render.FontRegistry

/**
 * FontFamily for showing a label font in the UI, so a chip or a list row is set in the very font
 * it stands for. Reads FontRegistry.revision, so it recomposes once fonts finish loading or when
 * the user adds or removes one. An unresolvable [customFamily] yields the fallback typeface, the
 * same one the label itself would render with.
 */
@Composable
fun labelFontFamily(font: LabelFont = LabelFont.SANS, customFamily: String? = null): FontFamily {
    val revision = FontRegistry.revision
    return remember(font, customFamily, revision) {
        FontFamily(Typeface(FontRegistry.base(font, customFamily)))
    }
}
