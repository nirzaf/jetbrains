package com.sourcegraph.common

import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.notification.impl.NotificationFullContent
import com.intellij.openapi.actionSystem.AnActionEvent
import com.sourcegraph.Icons
import com.sourcegraph.common.BrowserOpener.openInBrowser

class TrialEndedNotification :
    Notification(
        "Sourcegraph errors",
        CodyBundle.getString("EndOfTrialNotification.ended.title"),
        CodyBundle.getString("EndOfTrialNotification.ended.content"),
        NotificationType.WARNING),
    NotificationFullContent {

  init {
    icon = Icons.CodyLogo

    addAction(
        object :
            NotificationAction(CodyBundle.getString("EndOfTrialNotification.link-action-name")) {
          override fun actionPerformed(anActionEvent: AnActionEvent, notification: Notification) {
            openInBrowser(
                anActionEvent.project, CodyBundle.getString("EndOfTrialNotification.ended.link"))
            notification.expire()
          }
        })
    addAction(
        object :
            NotificationAction(CodyBundle.getString("EndOfTrialNotification.do-not-show-again")) {
          override fun actionPerformed(anActionEvent: AnActionEvent, notification: Notification) {
            PropertiesComponent.getInstance().setValue(ignore, true)
            notification.expire()
          }
        })
  }

  companion object {
    val ignore: String = CodyBundle.getString("EndOfTrialNotification.ignore.ended")
  }
}
