package com.sourcegraph.cody.util

import com.intellij.ide.lightEdit.LightEdit
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.LangDataKeys
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbService
import com.intellij.testFramework.EditorTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.runInEdtAndWait
import com.intellij.util.messages.Topic
import com.sourcegraph.cody.edit.CodyInlineEditActionNotifier
import com.sourcegraph.cody.edit.FixupService
import com.sourcegraph.config.ConfigUtil
import java.io.File
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

open class CodyIntegrationTextFixture : BasePlatformTestCase() {
  private val logger = Logger.getInstance(CodyIntegrationTextFixture::class.java)

  override fun setUp() {
    super.setUp()
    configureFixture()
    checkInitialConditions()
  }

  override fun tearDown() {
    try {
      FixupService.getInstance(myFixture.project).getActiveSession()?.apply {
        try {
          dispose()
        } catch (x: Exception) {
          logger.warn("Error shutting down session", x)
        }
      }
    } finally {
      super.tearDown()
    }
  }

  private fun configureFixture() {
    // If you don't specify this system property with this setting when running the tests,
    // the tests will fail, because IntelliJ will run them from the EDT, which can't block.
    // Setting this property invokes the tests from an executor pool thread, which lets us
    // block/wait on potentially long-running operations during the integration test.
    val policy = System.getProperty("idea.test.execution.policy")
    assertTrue(policy == "com.sourcegraph.cody.test.NonEdtIdeaTestExecutionPolicy")

    // This is wherever src/integrationTest/resources is on the box running the tests.
    val testResourcesDir = File(System.getProperty("test.resources.dir"))
    assertTrue(testResourcesDir.exists())

    // During test runs this is set by IntelliJ to a private temp folder.
    // We pass it to the Agent during initialization.
    val workspaceRootUri = ConfigUtil.getWorkspaceRootPath(project)

    // We copy the test resources there manually, bypassing Gradle, which is picky.
    val testDataPath = Paths.get(workspaceRootUri.toString(), "src/").toFile()
    testResourcesDir.copyRecursively(testDataPath, overwrite = true)

    // This useful setting lets us tell the fixture to look where we copied them.
    myFixture.testDataPath = testDataPath.path

    // The file we pass to configureByFile must be relative to testDataPath.
    val projectFile = "testProjects/documentCode/src/main/java/Foo.java"
    val sourcePath = Paths.get(testDataPath.path, projectFile).toString()
    assertTrue(File(sourcePath).exists())
    myFixture.configureByFile(projectFile)

    initCaretPosition()
  }

  private fun checkInitialConditions() {
    val project = myFixture.project

    // Check if the project is in dumb mode
    val isDumbMode = DumbService.getInstance(project).isDumb
    assertFalse("Project should not be in dumb mode", isDumbMode)

    // Check if the project is in LightEdit mode
    val isLightEditMode = LightEdit.owns(project)
    assertFalse("Project should not be in LightEdit mode", isLightEditMode)

    // Check the initial state of the action's presentation
    val action = ActionManager.getInstance().getAction("cody.documentCodeAction")
    val event =
        AnActionEvent.createFromAnAction(action, null, "", createEditorContext(myFixture.editor))
    action.update(event)
    val presentation = event.presentation
    assertTrue("Action should be enabled", presentation.isEnabled)
    assertTrue("Action should be visible", presentation.isVisible)
  }

  private fun createEditorContext(editor: Editor): DataContext {
    return (editor as? EditorEx)?.dataContext ?: DataContext.EMPTY_CONTEXT
  }

  // This provides a crude mechanism for specifying the caret position in the test file.
  private fun initCaretPosition() {
    runInEdtAndWait {
      val virtualFile = myFixture.file.virtualFile
      val document = FileDocumentManager.getInstance().getDocument(virtualFile)!!
      val caretToken = "[[caret]]"
      val caretIndex = document.text.indexOf(caretToken)

      if (caretIndex != -1) { // Remove caret token from doc
        WriteCommandAction.runWriteCommandAction(project) {
          document.deleteString(caretIndex, caretIndex + caretToken.length)
        }
        // Place the caret at the position where the token was found.
        myFixture.editor.caretModel.moveToOffset(caretIndex)
        // myFixture.editor.selectionModel.setSelection(caretIndex, caretIndex)
      } else {
        initSelectionRange()
      }
    }
  }

  // Provides  a mechanism to specify the selection range via [[start]] and [[end]].
  // The tokens are removed and the range is selected, notifying the Agent.
  private fun initSelectionRange() {
    runInEdtAndWait {
      val virtualFile = myFixture.file.virtualFile
      val document = FileDocumentManager.getInstance().getDocument(virtualFile)!!
      val startToken = "[[start]]"
      val endToken = "[[end]]"
      val start = document.text.indexOf(startToken)
      val end = document.text.indexOf(endToken)
      // Remove the tokens from the document.
      if (start != -1 && end != -1) {
        ApplicationManager.getApplication().runWriteAction {
          document.deleteString(start, start + startToken.length)
          document.deleteString(end, end + endToken.length)
        }
        myFixture.editor.selectionModel.setSelection(start, end)
      } else {
        logger.warn("No caret or selection range specified in test file.")
      }
    }
  }

  protected fun awaitAcceptLensGroup(): CodyInlineEditActionNotifier.Context {
    val future = subscribeToTopic(CodyInlineEditActionNotifier.TOPIC_DISPLAY_ACCEPT_GROUP)
    executeDocumentCodeAction() // awaits sending command to Agent
    val context = future.get() // awaits the Accept group appearing
    assertNotNull("Timed out waiting for Accept group lens", context)
    val editor = myFixture.editor
    assertTrue("Lens group inlay should be displayed", editor.inlayModel.hasBlockElements())
    return context!!
  }

  protected fun executeDocumentCodeAction() {
    assertFalse(myFixture.editor.inlayModel.hasBlockElements())
    // Execute the action and await the working group lens.
    runInEdtAndWait { EditorTestUtil.executeAction(myFixture.editor, "cody.documentCodeAction") }
  }

  // Returns a future that completes when the topic is published.
  protected fun subscribeToTopic(
      topic: Topic<CodyInlineEditActionNotifier>,
  ): CompletableFuture<CodyInlineEditActionNotifier.Context?> {
    val future =
        CompletableFuture<CodyInlineEditActionNotifier.Context?>()
            .completeOnTimeout(null, ASYNC_WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    project.messageBus
        .connect()
        .subscribe(
            topic,
            object : CodyInlineEditActionNotifier {
              override fun afterAction(context: CodyInlineEditActionNotifier.Context) {
                logger.warn(
                    "afterAction for topic '${topic.displayName}' called with context: $context")
                future.complete(context)
              }
            })
    logger.warn("Subscribed to topic: $topic")
    return future
  }

  // Run the IDE action specified by actionId.
  protected fun triggerAction(actionId: String) {
    val action = ActionManager.getInstance().getAction(actionId)
    val context =
        SimpleDataContext.builder()
            .add(LangDataKeys.PROJECT, project)
            .add(LangDataKeys.MODULE, module)
            .add(PlatformDataKeys.EDITOR, FileEditorManager.getInstance(project).selectedTextEditor)
            .build()

    action.actionPerformed(
        AnActionEvent(
            null, context, "", action.templatePresentation.clone(), ActionManager.getInstance(), 0))
  }

  protected fun hasJavadocComment(text: String): Boolean {
    // TODO: Check for the exact contents once they are frozen.
    val javadocPattern = Pattern.compile("/\\*\\*.*?\\*/", Pattern.DOTALL)
    return javadocPattern.matcher(text).find()
  }

  companion object {
    // TODO: find the lowest value this can be for production, and use it
    // If it's too low the test may be flaky.
    // const val ASYNC_WAIT_TIMEOUT_SECONDS = 15000L

    const val ASYNC_WAIT_TIMEOUT_SECONDS = 15L
  }
}
