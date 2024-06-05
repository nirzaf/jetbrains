package com.sourcegraph.cody.config

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.sourcegraph.config.ConfigUtil

data class ServerAuth(
    val instanceUrl: String,
    val accessToken: String,
    val customRequestHeaders: String
)

object ServerAuthLoader {
  val logger = Logger.getInstance(ServerAuth::class.java)

  @JvmStatic
  fun loadServerAuth(project: Project): ServerAuth {
    val codyAuthenticationManager = CodyAuthenticationManager.getInstance(project)
    val defaultAccount = codyAuthenticationManager.getActiveAccount()
    if (defaultAccount != null) {
      val accessToken = codyAuthenticationManager.getTokenForAccount(defaultAccount) ?: ""
      return ServerAuth(
          defaultAccount.server.url, accessToken, defaultAccount.server.customRequestHeaders)
    }
    return ServerAuth(ConfigUtil.DOTCOM_URL, "", "")
  }
}
