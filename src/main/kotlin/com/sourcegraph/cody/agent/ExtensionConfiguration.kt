package com.sourcegraph.cody.agent

data class ExtensionConfiguration(
    var serverEndpoint: String,
    var proxy: String? = null,
    var accessToken: String,
    val anonymousUserID: String?,
    var customHeaders: Map<String, String> = emptyMap(),
    var autocompleteAdvancedProvider: String? = null,
    var autocompleteAdvancedServerEndpoint: String? = null,
    var autocompleteAdvancedAccessToken: String? = null,
    var debug: Boolean? = false,
    var verboseDebug: Boolean? = false,
    var codebase: String? = null
)
