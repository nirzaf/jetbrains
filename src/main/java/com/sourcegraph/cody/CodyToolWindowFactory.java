package com.sourcegraph.cody;

import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.sourcegraph.cody.config.actions.OpenCodySettingsEditorAction;
import com.sourcegraph.config.ConfigUtil;
import com.sourcegraph.config.OpenPluginSettingsAction;
import org.jetbrains.annotations.NotNull;

public class CodyToolWindowFactory implements ToolWindowFactory, DumbAware {

  public static final String TOOL_WINDOW_ID = "Cody";

  @Override
  public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
    CodyToolWindowContent.Companion.executeOnInstanceIfNotDisposed(
        project,
        toolWindowContent -> {
          Content content =
              // ContentFactory.SERVICE.getInstance() has been deprecated in recent versions
              ApplicationManager.getApplication()
                  .getService(ContentFactory.class)
                  .createContent(toolWindowContent.getAllContentPanel(), "", false);
          content.setPreferredFocusableComponent(null);
          toolWindow.getContentManager().addContent(content);
          DefaultActionGroup customCodySettings = new DefaultActionGroup();
          customCodySettings.add(new OpenPluginSettingsAction("Cody Settings..."));
          customCodySettings.add(new OpenCodySettingsEditorAction());
          customCodySettings.addSeparator();
          toolWindow.setAdditionalGearActions(customCodySettings);
          return null;
        });
  }

  @Override
  public boolean shouldBeAvailable(@NotNull Project project) {
    return ConfigUtil.isCodyEnabled();
  }
}
