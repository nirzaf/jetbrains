package com.sourcegraph.cody.ui


import com.intellij.openapi.observable.properties.AtomicBooleanProperty
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.util.NlsContexts
import com.intellij.ui.TitledSeparator
import com.intellij.util.ui.IndentedIcon
import com.intellij.util.ui.UIUtil
import java.awt.Cursor
import java.awt.Insets
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import kotlin.math.max

class CollapsibleTitledSeparator(@NlsContexts.Separator title: String) : TitledSeparator(title) {
    val expandedProperty = AtomicBooleanProperty(true)
    var expanded by expandedProperty

    init {
        updateIcon()
        expandedProperty.afterChange { updateIcon() }
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() {
            override fun mouseReleased(e: MouseEvent) {
                expanded = !expanded
            }
        })
    }

    fun onAction(listener: (Boolean) -> Unit) {
        expandedProperty.afterChange(listener)
    }

    fun updateIcon() {
        val treeExpandedIcon = UIUtil.getTreeExpandedIcon()
        val treeCollapsedIcon = UIUtil.getTreeCollapsedIcon()
        val width = max(treeExpandedIcon.iconWidth, treeCollapsedIcon.iconWidth)
        var icon = if (expanded) treeExpandedIcon else treeCollapsedIcon
        val extraSpace = width - icon.iconWidth
        if (extraSpace > 0) {
            val left = extraSpace / 2
            icon = IndentedIcon(icon, Insets(0, left, 0, extraSpace - left))
        }
        label.icon = icon
        label.disabledIcon = IconLoader.getTransparentIcon(icon, 0.5f)
    }
}