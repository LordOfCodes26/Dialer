package com.goodwy.commons.views

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.paint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goodwy.commons.compose.liquid.LiquidBottomTab
import com.goodwy.commons.compose.liquid.LiquidBottomTabs
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop

@Composable
fun MyLiquidBottomTabs(
    tabs: List<Pair<Painter, String>>,
    selectedTabIndex: MutableState<Int>,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val isLightTheme = !isSystemInDarkTheme()
    val contentColor = if (isLightTheme) Color.Black else Color.White
    val iconColorFilter = ColorFilter.tint(contentColor)
    val backgroundColor = if (isLightTheme) Color.White else Color(0xFF121212)
    val backdrop = rememberCanvasBackdrop {
        drawRect(backgroundColor)
    }


    LiquidBottomTabs(
        selectedTabIndex = { selectedTabIndex.value },
        onTabSelected = {
            selectedTabIndex.value = it
            onTabSelected(it)
        },
        backdrop = backdrop,
        tabsCount = tabs.size,
        modifier = modifier.padding(horizontal = 20.dp, vertical = 20.dp)
    ) {
        tabs.forEachIndexed { index, (icon, label) ->
            LiquidBottomTab({ selectedTabIndex.value = index }) {
                Box(
                    Modifier
                        .size(28.dp)
                        .paint(icon, colorFilter = iconColorFilter)
                )
                BasicText(text = label, style = TextStyle(contentColor, 12.sp))
            }
        }
    }
}
