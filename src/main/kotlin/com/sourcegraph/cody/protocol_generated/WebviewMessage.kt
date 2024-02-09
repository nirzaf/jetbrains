@file:Suppress("FunctionName", "ClassName")
package com.sourcegraph.cody.protocol_generated
data class WebviewMessage(
  var command: String? = null,
  var eventName: String? = null,
  var properties: TelemetryEventProperties? = null,
  var text: String? = null,
  var submitType: String? = null,
  var addEnhancedContext: Boolean? = null,
  var contextFiles: List<ContextFile>? = null,
  var action: String? = null,
  var chatID: String? = null,
  var value: String? = null,
  var page: String? = null,
  var model: String? = null,
  var uri: Uri? = null,
  var range: ActiveTextEditorSelectionRange? = null,
  var filePath: String? = null,
  var index: Int? = null,
  var explicitRepos: List<Repo>? = null,
  var repoId: String? = null,
  var metadata: CodeBlockMeta? = null,
  var eventType: String? = null,
  var authKind: String? = null,
  var endpoint: String? = null,
  var authMethod: String? = null,
  var onboardingKind: String? = null,
  var query: String? = null,
  var snippet: String? = null,
)

