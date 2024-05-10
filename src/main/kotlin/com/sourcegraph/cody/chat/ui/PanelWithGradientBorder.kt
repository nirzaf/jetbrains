package com.sourcegraph.cody.chat.ui

import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.VerticalFlowLayout
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.sourcegraph.cody.agent.protocol.Speaker
import com.sourcegraph.cody.ui.Colors
import java.awt.GradientPaint
import java.awt.Graphics
import java.awt.Graphics2D
import javax.swing.BorderFactory
import javax.swing.JPanel
import javax.swing.border.Border

open class PanelWithGradientBorder(private val gradientWidth: Int, speaker: Speaker) : JPanel() {

  private val isHuman: Boolean = speaker == Speaker.HUMAN

  init {
    computeLayout()

    ApplicationManager.getApplication()
        .messageBus
        .connect()
        .subscribe(LafManagerListener.TOPIC, LafManagerListener { computeLayout() })
  }

private fun computeLayout() {
    val panelBackground = UIUtil.getPanelBackground()
    this.border = JBUI.Borders.empty()

    this.layout = VerticalFlowLayout(VerticalFlowLayout.TOP, 0, 0, true, false)
    this.background = if (isHuman) ColorUtil.darker(panelBackground, 2) else panelBackground
  }

  override fun paintComponent(g: Graphics) {
    super.paintComponent(g)
    paintLeftBorderGradient(g)
  }

  private fun paintLeftBorderGradient(g: Graphics) {
    if (!isHuman) return
    val halfOfHeight = height / 2
    val firstPartGradient =
        GradientPaint(0f, 0f, Colors.PURPLE, 0f, halfOfHeight.toFloat(), Colors.ORANGE)
    val secondPartGradient =
        GradientPaint(0f, halfOfHeight.toFloat(), Colors.ORANGE, 0f, height.toFloat(), Colors.CYAN)
    val g2d = g as Graphics2D
    g2d.paint = firstPartGradient
    g2d.fillRect(0, 0, gradientWidth, halfOfHeight)
    g2d.paint = secondPartGradient
    g2d.fillRect(0, halfOfHeight, gradientWidth, height)
  }
}
