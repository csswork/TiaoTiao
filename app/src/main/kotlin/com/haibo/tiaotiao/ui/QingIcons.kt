package com.haibo.tiaotiao.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.haibo.tiaotiao.R

internal enum class QingGlyph(@get:DrawableRes val resource: Int) {
    HOME(R.drawable.ic_qing_home), APPS(R.drawable.ic_qing_apps), RULES(R.drawable.ic_qing_rules),
    HISTORY(R.drawable.ic_qing_history), SETTINGS(R.drawable.ic_qing_settings),
    CHEVRON_LEFT(R.drawable.ic_qing_chevron_left), CHEVRON_RIGHT(R.drawable.ic_qing_chevron_right),
    CHEVRON_DOWN(R.drawable.ic_qing_chevron_down), CHECK_CIRCLE(R.drawable.ic_qing_check_circle),
    PENDING_CIRCLE(R.drawable.ic_qing_pending_circle), SEARCH(R.drawable.ic_qing_search), PLAY(R.drawable.ic_qing_play)
}

/** Pinned QingIcon Line vectors share one viewport and weight. Decorative glyphs are not announced twice. */
@Composable internal fun QingIcon(glyph: QingGlyph, modifier: Modifier = Modifier, description: String? = null,
    size: Dp = 24.dp, tint: Color = LocalContentColor.current) {
    Icon(painter = painterResource(glyph.resource), contentDescription = description,
        modifier = modifier.size(size), tint = tint)
}
