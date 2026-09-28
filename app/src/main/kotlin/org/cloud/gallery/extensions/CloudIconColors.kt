package org.fossify.gallery.extensions

import android.content.Context
import androidx.core.graphics.ColorUtils
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.getProperTextColor

fun Context.getCloudUnuploadedIconColor(): Int {
    val backgroundColor = getProperBackgroundColor()
    val textColor = getProperTextColor()
    val textRatio = if (ColorUtils.calculateLuminance(backgroundColor) > 0.5) 0.35f else 0.65f
    return ColorUtils.blendARGB(backgroundColor, textColor, textRatio)
}
