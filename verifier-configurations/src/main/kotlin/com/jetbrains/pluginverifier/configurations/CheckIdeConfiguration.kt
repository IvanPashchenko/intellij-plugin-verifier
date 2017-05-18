package com.jetbrains.pluginverifier.configurations

import com.intellij.structure.ide.IdeVersion
import com.jetbrains.pluginverifier.api.PluginDescriptor
import com.jetbrains.pluginverifier.api.Verifier
import com.jetbrains.pluginverifier.api.VerifierParams
import com.jetbrains.pluginverifier.format.UpdateInfo
import com.jetbrains.pluginverifier.misc.closeLogged
import com.jetbrains.pluginverifier.plugin.CreatePluginResult
import com.jetbrains.pluginverifier.plugin.PluginCreator
import com.jetbrains.pluginverifier.repository.RepositoryManager
import com.jetbrains.pluginverifier.utils.VerificationResultToApiResultConverter


class CheckIdeConfiguration(val params: CheckIdeParams) : Configuration {

  private var allPluginsToCheck: List<CreatePluginResult> = emptyList()

  override fun execute(): CheckIdeResults {
    allPluginsToCheck = params.pluginsToCheck.map { PluginCreator.createPlugin(it) }
    try {
      return doExecute()
    } finally {
      allPluginsToCheck.forEach { it.closeLogged() }
    }
  }

  private fun doExecute(): CheckIdeResults {
    val pluginsToCheck = getNotExcludedPlugins().map { it to params.ideDescriptor }
    val verifierParams = VerifierParams(params.jdkDescriptor, pluginsToCheck, params.externalClassesPrefixes, params.problemsFilter, params.externalClassPath, params.dependencyResolver)
    val results = Verifier(verifierParams).verify(params.progress)
    return CheckIdeResults(params.ideDescriptor.ideVersion, VerificationResultToApiResultConverter().convert(results), params.excludedPlugins, getMissingUpdatesProblems())
  }

  private fun getNotExcludedPlugins(): List<PluginDescriptor.ByInstance> = allPluginsToCheck
      .filterIsInstance<CreatePluginResult.OK>()
      .filterNot { params.excludedPlugins.containsEntry(it.success.plugin.pluginId, it.success.plugin.pluginVersion) }
      .map { PluginDescriptor.ByInstance(it) }

  private fun getMissingUpdatesProblems(): List<MissingCompatibleUpdate> {
    val ideVersion = params.ideDescriptor.ideVersion
    val existingUpdatesForIde = RepositoryManager
        .getLastCompatibleUpdates(ideVersion)
        .filterNot { params.excludedPlugins.containsEntry(it.pluginId, it.version) }
        .map { it.pluginId }
        .filterNotNull()
        .toSet()

    return params.pluginIdsToCheckExistingBuilds.distinct()
        .filterNot { existingUpdatesForIde.contains(it) }
        .map {
          val buildForCommunity = getUpdateCompatibleWithCommunityEdition(it, ideVersion)
          if (buildForCommunity != null) {
            val details = "\nNote: there is an update (#" + buildForCommunity.updateId + ") compatible with IDEA Community Edition, " +
                "but the Plugin repository does not offer to install it if you run the IDEA Ultimate."
            MissingCompatibleUpdate(it, ideVersion, details)
          } else {
            MissingCompatibleUpdate(it, ideVersion, "")
          }
        }
  }

  private fun getUpdateCompatibleWithCommunityEdition(pluginId: String, version: IdeVersion): UpdateInfo? {
    val ideVersion = version.asString()
    if (ideVersion.startsWith("IU-")) {
      val communityVersion = "IC-" + ideVersion.substringAfter(ideVersion, "IU-")
      try {
        return RepositoryManager.getLastCompatibleUpdateOfPlugin(IdeVersion.createIdeVersion(communityVersion), pluginId)
      } catch (e: Exception) {
        return null
      }
    }
    return null
  }


}

