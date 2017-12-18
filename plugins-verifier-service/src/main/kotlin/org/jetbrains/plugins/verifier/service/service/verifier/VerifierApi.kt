package org.jetbrains.plugins.verifier.service.service.verifier

import com.jetbrains.plugin.structure.base.plugin.PluginProblem
import com.jetbrains.plugin.structure.intellij.plugin.PluginDependency
import com.jetbrains.plugin.verification.DependenciesGraphs
import com.jetbrains.plugin.verification.UpdateRangeCompatibilityResults
import com.jetbrains.plugin.verification.VerificationResults
import com.jetbrains.pluginverifier.dependencies.DependenciesGraph
import com.jetbrains.pluginverifier.dependencies.DependencyEdge
import com.jetbrains.pluginverifier.dependencies.DependencyNode
import com.jetbrains.pluginverifier.dependencies.MissingDependency
import com.jetbrains.pluginverifier.results.Result
import com.jetbrains.pluginverifier.results.Verdict
import com.jetbrains.pluginverifier.results.problems.Problem
import com.jetbrains.pluginverifier.results.warnings.Warning

fun prepareVerificationResponse(compatibilityResult: CheckRangeCompatibilityResult): UpdateRangeCompatibilityResults.UpdateRangeCompatibilityResult {
  val apiResultType = convertToApiResultType(compatibilityResult)
  val apiResults = compatibilityResult.verificationResults.orEmpty().map { convertVerifierResult(it) }
  val invalidPluginProblems = compatibilityResult.invalidPluginProblems.orEmpty().map { convertInvalidProblem(it) }
  val nonDownloadableReason = compatibilityResult.nonDownloadableReason
  return UpdateRangeCompatibilityResults.UpdateRangeCompatibilityResult.newBuilder()
      .setUpdateId(compatibilityResult.updateInfo.updateId)
      .setResultType(apiResultType)
      .addAllIdeVerificationResults(apiResults)
      .addAllInvalidPluginProblems(invalidPluginProblems)
      .apply { if (nonDownloadableReason != null) setNonDownloadableReason(nonDownloadableReason) }
      .build()
}

private fun convertInvalidProblem(pluginProblem: PluginProblem): VerificationResults.InvalidPluginProblem =
    VerificationResults.InvalidPluginProblem.newBuilder()
        .setMessage(pluginProblem.message)
        .setLevel(when (pluginProblem.level) {
          PluginProblem.Level.ERROR -> VerificationResults.InvalidPluginProblem.Level.ERROR
          PluginProblem.Level.WARNING -> VerificationResults.InvalidPluginProblem.Level.WARNING
        })
        .build()

private fun convertDependencyGraph(dependenciesGraph: DependenciesGraph): DependenciesGraphs.DependenciesGraph = DependenciesGraphs.DependenciesGraph.newBuilder()
    .setStart(convertNode(dependenciesGraph.start))
    .addAllVertices(dependenciesGraph.vertices.map { convertNode(it) })
    .addAllEdges(dependenciesGraph.edges.map { convertEdge(it) })
    .build()

private fun convertEdge(dependencyEdge: DependencyEdge): DependenciesGraphs.DependenciesGraph.Edge =
    DependenciesGraphs.DependenciesGraph.Edge.newBuilder()
        .setFrom(convertNode(dependencyEdge.from))
        .setTo(convertNode(dependencyEdge.to))
        .setDependency(convertPluginDependency(dependencyEdge.dependency))
        .build()

private fun convertNode(internalNode: DependencyNode): DependenciesGraphs.DependenciesGraph.Node = DependenciesGraphs.DependenciesGraph.Node.newBuilder()
    .setPluginId(internalNode.id)
    .setVersion(internalNode.version)
    .addAllMissingDependencies(internalNode.missingDependencies.map { convertMissingDependency(it) })
    .build()

private fun convertMissingDependency(missingDependency: MissingDependency) = DependenciesGraphs.DependenciesGraph.MissingDependency.newBuilder()
    .setDependency(convertPluginDependency(missingDependency.dependency))
    .setMissingReason(missingDependency.missingReason)
    .build()

private fun convertPluginDependency(dependency: PluginDependency) =
    DependenciesGraphs.DependenciesGraph.Dependency.newBuilder()
        .setDependencyId(dependency.id)
        .setIsModule(dependency.isModule)
        .setIsOptional(dependency.isOptional)
        .build()

private fun convertVerifierResult(result: Result): VerificationResults.VerificationResult {
  val verdict = result.verdict
  val problems = verdict.getProblems()
  val warnings = verdict.getWarnings()
  val dependenciesGraph = verdict.getDependenciesGraph()

  return VerificationResults.VerificationResult.newBuilder()
      .setIdeVersion(result.ideVersion.asString())
      .addAllProblems(convertProblems(problems))
      .addAllWarnings(convertWarnings(warnings))
      .apply { if (dependenciesGraph != null) setDependenciesGraph(convertDependencyGraph(dependenciesGraph)) }
      .build()
}

private fun Verdict.getWarnings(): Set<Warning> = with(this) {
  when (this) {
    is Verdict.OK -> emptySet()
    is Verdict.Warnings -> warnings
    is Verdict.MissingDependencies -> warnings
    is Verdict.Problems -> warnings
    is Verdict.Bad -> emptySet()
    is Verdict.NotFound -> emptySet()
    is Verdict.FailedToDownload -> emptySet()
  }
}

private fun Verdict.getProblems(): Set<Problem> = with(this) {
  when (this) {
    is Verdict.OK -> emptySet()
    is Verdict.Warnings -> emptySet()
    is Verdict.MissingDependencies -> this.problems
    is Verdict.Problems -> this.problems
    is Verdict.Bad -> emptySet()
    is Verdict.NotFound -> emptySet()
    is Verdict.FailedToDownload -> emptySet()
  }
}

private fun Verdict.getDependenciesGraph(): DependenciesGraph? = with(this) {
  when (this) {
    is Verdict.OK -> dependenciesGraph
    is Verdict.Warnings -> dependenciesGraph
    is Verdict.MissingDependencies -> dependenciesGraph
    is Verdict.Problems -> dependenciesGraph
    is Verdict.Bad -> null
    is Verdict.NotFound -> null
    is Verdict.FailedToDownload -> null
  }
}

private fun convertProblems(problems: Set<Problem>): List<VerificationResults.Problem> = problems.map {
  VerificationResults.Problem.newBuilder()
      .setMessage(it.fullDescription)
      .build()
}

private fun convertWarnings(warnings: Set<Warning>): List<VerificationResults.Warning> = warnings.map {
  VerificationResults.Warning.newBuilder()
      .setMessage(it.message)
      .build()
}

private fun convertToApiResultType(compatibilityResult: CheckRangeCompatibilityResult): UpdateRangeCompatibilityResults.UpdateRangeCompatibilityResult.ResultType = when (compatibilityResult.resultType) {
  CheckRangeCompatibilityResult.ResultType.NON_DOWNLOADABLE -> UpdateRangeCompatibilityResults.UpdateRangeCompatibilityResult.ResultType.NON_DOWNLOADABLE
  CheckRangeCompatibilityResult.ResultType.NO_COMPATIBLE_IDES -> UpdateRangeCompatibilityResults.UpdateRangeCompatibilityResult.ResultType.NO_COMPATIBLE_IDES
  CheckRangeCompatibilityResult.ResultType.INVALID_PLUGIN -> UpdateRangeCompatibilityResults.UpdateRangeCompatibilityResult.ResultType.INVALID_PLUGIN
  CheckRangeCompatibilityResult.ResultType.VERIFICATION_DONE -> UpdateRangeCompatibilityResults.UpdateRangeCompatibilityResult.ResultType.VERIFICATION_DONE
}
