@file:Suppress("FunctionName", "ClassName", "unused", "EnumEntryName", "UnusedImport")
package com.sourcegraph.cody.agent.protocol_generated;

data class ConfigParams(
  val experimentalNoodle: Boolean,
  val agentIDE: CodyIDE? = null, // Oneof: VSCode, JetBrains, Neovim, Emacs, Web, VisualStudio
  val agentExtensionVersion: String? = null,
  val serverEndpoint: String,
  val webviewType: WebviewType? = null, // Oneof: sidebar, editor
  val uiKindIsWeb: Boolean,
)

