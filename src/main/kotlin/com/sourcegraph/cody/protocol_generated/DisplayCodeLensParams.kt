@file:Suppress("FunctionName", "ClassName", "unused", "EnumEntryName")
package com.sourcegraph.cody.protocol_generated

data class DisplayCodeLensParams(
  val uri: String? = null,
  val codeLenses: List<ProtocolCodeLens>? = null,
)

