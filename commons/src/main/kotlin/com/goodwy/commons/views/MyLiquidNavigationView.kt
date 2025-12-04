package com.goodwy.commons.views

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource

class MyLiquidNavigationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private var isInitializing: Boolean = false
    private val composeView = ComposeView(context)

    private var tabs: MutableList<MyTabItem> = mutableListOf()

    /** Compose-driven index — THIS controls UI */
    private var selectedIndexState = mutableStateOf(0)

    private var listener: ((Int) -> Unit)? = null
    private var tabSelectedAction: ((MyTab) -> Unit)? = null
    private var tabUnselectedAction: ((MyTab) -> Unit)? = null

    private var selectedIndex: Int
        get() = selectedIndexState.value
        set(value) { selectedIndexState.value = value }
    init {
        addView(
            composeView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
        redraw()
    }

    /* ------------------------------------------------------ */
    /* Compose UI                                              */
    /* ------------------------------------------------------ */
    private fun redraw() {
        composeView.setContent {
            MyLiquidBottomTabs(
                tabs = tabs.map { painterResource(it.iconRes) to it.label },
                selectedTabIndex = this.selectedIndexState,
                onTabSelected = { index ->
                    internalSelectFromUser(index)
                }
            )
        }
    }

    /* When user taps a tab */
    private fun internalSelectFromUser(index: Int) {
        if (index !in tabs.indices) return

        val old = selectedIndex

        if (!isInitializing && old != index) {
            getTabAt(old)?.let { tabUnselectedAction?.invoke(it) }
        }

        if (!isInitializing) {
            getTabAt(index)?.let { tabSelectedAction?.invoke(it) }
            listener?.invoke(index)
        }

        selectedIndex = index
    }


    /* ------------------------------------------------------ */
    /* Tab API                                                 */
    /* ------------------------------------------------------ */

    fun newTab(): MyTab = MyTab()

    fun addTab(tab: MyTab) {
        isInitializing = true
        val icon = tab.iconRes ?: error("Tab icon missing")
        tabs.add(MyTabItem(icon, tab.label ?: ""))
        redraw()
        isInitializing = false
    }
    fun removeAllTabs() {
        tabs.clear()
        selectedIndex = 0
        redraw()
    }

    fun getTabAt(index: Int): MyTab? {
        return if (index in tabs.indices)
            MyTab().apply {
                position = index
                iconRes = tabs[index].iconRes
                label = tabs[index].label
            }
        else null
    }

    val tabCount: Int get() = tabs.size

    /**
     * Programmatically select tab
     */
    fun selectTab(index: Int, notifyListener: Boolean = true) {
        if (index !in tabs.indices) return

        val old = selectedIndex

        if (!isInitializing && old != index) {
            getTabAt(old)?.let { tabUnselectedAction?.invoke(it) }
        }

        if (!isInitializing && notifyListener) {
            getTabAt(index)?.let { tabSelectedAction?.invoke(it) }
            listener?.invoke(index)
        }

        selectedIndex = index // <- also updates Compose
    }


    /**
     * Tab selection listener (TabLayout compatible)
     */
    fun onTabSelectionChanged(
        tabUnselectedAction: (MyTab) -> Unit,
        tabSelectedAction: (MyTab) -> Unit
    ) {
        this.tabUnselectedAction = tabUnselectedAction
        this.tabSelectedAction = tabSelectedAction

        listener = { index ->
            // This listener now triggers only for programmatic changes
            val old = selectedIndex
            if (old != index) {
                getTabAt(old)?.let { tabUnselectedAction(it) }
            }
            getTabAt(index)?.let { tabSelectedAction(it) }
        }
    }

    /* ------------------------------------------------------ */
    /* Data classes                                            */
    /* ------------------------------------------------------ */

    inner class MyTab {
        var iconRes: Int? = null
        var label: String? = null
        var position: Int = -1

        fun setIcon(res: Int): MyTab {
            iconRes = res
            return this
        }

        fun setText(txt: String): MyTab {
            label = txt
            return this
        }

        fun select() {
            selectTab(position)
        }
    }
}

data class MyTabItem(
    val iconRes: Int,
    val label: String
)
