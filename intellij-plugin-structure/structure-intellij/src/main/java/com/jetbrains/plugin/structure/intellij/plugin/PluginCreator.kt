package com.jetbrains.plugin.structure.intellij.plugin

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.jetbrains.plugin.structure.base.plugin.*
import com.jetbrains.plugin.structure.base.problems.ContainsNewlines
import com.jetbrains.plugin.structure.base.problems.NotNumber
import com.jetbrains.plugin.structure.base.problems.PropertyNotSpecified
import com.jetbrains.plugin.structure.base.problems.UnableToReadDescriptor
import com.jetbrains.plugin.structure.intellij.beans.*
import com.jetbrains.plugin.structure.intellij.extractor.PluginBeanExtractor
import com.jetbrains.plugin.structure.intellij.problems.*
import com.jetbrains.plugin.structure.intellij.resources.ResourceResolver
import com.jetbrains.plugin.structure.intellij.version.IdeVersion
import com.jetbrains.plugin.structure.intellij.xinclude.XIncluder
import com.jetbrains.plugin.structure.intellij.xinclude.XIncluderException
import org.jdom2.Document
import org.jdom2.Element
import org.jsoup.Jsoup
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URL
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

internal class PluginCreator {

  companion object {
    private val LOG = LoggerFactory.getLogger(PluginCreator::class.java)

    private const val MAX_PRODUCT_CODE_LENGTH = 15
    private const val MAX_VERSION_LENGTH = 64
    private const val MAX_PROPERTY_LENGTH = 255
    private const val MAX_LONG_PROPERTY_LENGTH = 65535

    private const val INTELLIJ_THEME_EXTENSION = "com.intellij.themeProvider"

    private val latinSymbolsRegex = Regex("[A-Za-z]|\\s")

    private val jsonMapper = jacksonObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    val releaseDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
  }

  val pluginFile: File
  val optionalDependenciesConfigFiles: MutableMap<PluginDependency, String> = hashMapOf()
  val descriptorPath: String
  private val validateDescriptor: Boolean
  private val plugin: IdePluginImpl?
  private val problems = arrayListOf<PluginProblem>()

  constructor(
      descriptorPath: String,
      validateDescriptor: Boolean,
      document: Document,
      documentUrl: URL,
      pathResolver: ResourceResolver,
      pluginFile: File,
      icons: List<PluginIcon>
  ) {
    this.descriptorPath = descriptorPath
    this.pluginFile = pluginFile
    this.validateDescriptor = validateDescriptor
    plugin = resolveDocumentAndValidateBean(document, documentUrl, descriptorPath, pathResolver, icons)
  }

  constructor(descriptorPath: String, singleProblem: PluginProblem, pluginFile: File) {
    require(singleProblem.level == PluginProblem.Level.ERROR) { "Only severe problems allowed here" }
    this.descriptorPath = descriptorPath
    this.pluginFile = pluginFile
    this.validateDescriptor = true
    registerProblem(singleProblem)
    plugin = null
  }

  val isSuccess: Boolean
    get() = !hasErrors()

  val pluginCreationResult: PluginCreationResult<IdePlugin>
    get() = if (hasErrors()) {
      PluginCreationFail(problems)
    } else {
      PluginCreationSuccess<IdePlugin>(plugin!!, problems)
    }

  fun addOptionalDescriptor(
      pluginDependency: PluginDependency,
      configurationFile: String,
      optionalDependencyCreator: PluginCreator
  ) {
    val pluginCreationResult = optionalDependencyCreator.pluginCreationResult
    if (pluginCreationResult is PluginCreationSuccess<IdePlugin>) {
      val optionalPlugin = pluginCreationResult.plugin
      plugin!!.optionalDescriptors[configurationFile] = optionalPlugin
      plugin.extensions.putAll(optionalPlugin.extensions)
    } else {
      val errors = (pluginCreationResult as PluginCreationFail<IdePlugin>)
          .errorsAndWarnings
          .filter { e -> e.level === PluginProblem.Level.ERROR }
      registerProblem(OptionalDependencyDescriptorResolutionProblem(pluginDependency.id, configurationFile, errors))
    }
  }

  fun setPluginVersion(pluginVersion: String) {
    plugin?.pluginVersion = pluginVersion
  }

  fun setOriginalFile(originalFile: File) {
    plugin?.originalFile = originalFile
  }

  private fun IdePluginImpl.setInfoFromBean(bean: PluginBean) {
    pluginName = bean.name?.trim()
    pluginId = bean.id?.trim() ?: pluginName
    url = bean.url?.trim()
    pluginVersion = if (bean.pluginVersion != null) bean.pluginVersion.trim { it <= ' ' } else null
    definedModules.addAll(bean.modules)
    extensions.putAll(bean.extensions)
    applicationListeners.addAll(bean.applicationListeners)
    projectListeners.addAll(bean.projectListeners)
    useIdeClassLoader = bean.useIdeaClassLoader == true

    val ideaVersionBean = bean.ideaVersion
    if (ideaVersionBean != null) {
      sinceBuild = if (ideaVersionBean.sinceBuild != null) IdeVersion.createIdeVersion(ideaVersionBean.sinceBuild) else null
      var untilBuild: String? = ideaVersionBean.untilBuild
      if (untilBuild != null && untilBuild.isNotEmpty()) {
        if (untilBuild.endsWith(".*")) {
          val idx = untilBuild.lastIndexOf('.')
          untilBuild = untilBuild.substring(0, idx + 1) + Integer.MAX_VALUE
        }
        this.untilBuild = IdeVersion.createIdeVersion(untilBuild)
      }
    }

    if (bean.dependencies != null) {
      for (dependencyBean in bean.dependencies) {
        if (dependencyBean.dependencyId != null) {
          val isModule = dependencyBean.dependencyId.startsWith("com.intellij.modules.")
          val isOptional = java.lang.Boolean.TRUE == dependencyBean.optional
          val dependency = PluginDependencyImpl(dependencyBean.dependencyId, isOptional, isModule)
          dependencies += dependency

          if (dependency.isOptional && dependencyBean.configFile != null) {
            optionalDependenciesConfigFiles[dependency] = dependencyBean.configFile
          }
        }
      }
    }

    val vendorBean = bean.vendor
    if (vendorBean != null) {
      vendor = if (vendorBean.name != null) vendorBean.name.trim { it <= ' ' } else null
      vendorUrl = vendorBean.url
      vendorEmail = vendorBean.email
    }
    val productDescriptorBean = bean.productDescriptor
    if (productDescriptorBean != null) {

      productDescriptor = ProductDescriptor(
          productDescriptorBean.code,
          LocalDate.parse(productDescriptorBean.releaseDate, releaseDateFormatter),
          Integer.parseInt(productDescriptorBean.releaseVersion)
      )
    }
    changeNotes = bean.changeNotes
    description = bean.description
  }

  private fun validatePluginBean(bean: PluginBean) {
    if (validateDescriptor) {
      validateAttributes(bean)
      validateId(bean.id)
      validateName(bean.name)
      validateVersion(bean.pluginVersion)
      validateDescription(bean.description)
      validateChangeNotes(bean.changeNotes)
      validateVendor(bean.vendor)
      validateIdeaVersion(bean.ideaVersion)
      validateProductDescriptor(bean.productDescriptor)

      if (bean.dependencies != null) {
        validateDependencies(bean.dependencies)
      }

      if (bean.modules?.any { it.isEmpty() } == true) {
        registerProblem(InvalidModuleBean(descriptorPath))
      }
    }
  }

  private fun validateDependencies(dependencies: List<PluginDependencyBean>) {
    for (dependencyBean in dependencies) {
      if (dependencyBean.dependencyId.isNullOrBlank() || dependencyBean.dependencyId.contains("\n")) {
        registerProblem(InvalidDependencyId(descriptorPath, dependencyBean.dependencyId))
      } else if (dependencyBean.optional == true && dependencyBean.configFile == null) {
        registerProblem(OptionalDependencyConfigFileNotSpecified(dependencyBean.dependencyId))
      } else if (dependencyBean.optional == false) {
        registerProblem(SuperfluousNonOptionalDependencyDeclaration(dependencyBean.dependencyId))
      }
    }
  }

  private fun validateProductDescriptor(productDescriptor: ProductDescriptorBean?) {
    if (productDescriptor != null) {
      validateProductCode(productDescriptor.code)
      validateReleaseDate(productDescriptor.releaseDate)
      validateReleaseVersion(productDescriptor.releaseVersion)
    }
  }

  private fun validateProductCode(productCode: String?) {
    if (productCode.isNullOrEmpty()) {
      registerProblem(PropertyNotSpecified("code", descriptorPath))
    } else {
      validatePropertyLength("Product code", productCode, MAX_PRODUCT_CODE_LENGTH)
    }
  }

  private fun validateReleaseDate(releaseDate: String?) {
    if (releaseDate.isNullOrEmpty()) {
      registerProblem(PropertyNotSpecified("release-date", descriptorPath))
    } else {
      try {
        LocalDate.parse(releaseDate, releaseDateFormatter)
      } catch (e: DateTimeParseException) {
        registerProblem(ReleaseDateWrongFormat)
      }
    }
  }

  private fun validateReleaseVersion(releaseVersion: String?) {
    if (releaseVersion.isNullOrEmpty()) {
      registerProblem(PropertyNotSpecified("release-version", descriptorPath))
    } else {
      try {
        Integer.valueOf(releaseVersion)
      } catch (e: NumberFormatException) {
        registerProblem(NotNumber("release-version", descriptorPath))
      }
    }
  }


  private fun validateAttributes(bean: PluginBean) {
    if (bean.url != null) {
      validatePropertyLength("plugin url", bean.url, MAX_PROPERTY_LENGTH)
    }
  }

  private fun validateVersion(pluginVersion: String?) {
    if (pluginVersion.isNullOrEmpty()) {
      registerProblem(PropertyNotSpecified("version", descriptorPath))
    } else {
      validatePropertyLength("version", pluginVersion, MAX_VERSION_LENGTH)
    }
  }

  private fun validatePlugin(plugin: IdePluginImpl) {
    val dependencies = plugin.dependencies
    dependencies.map { it.id }
        .groupingBy { it }
        .eachCount()
        .filterValues { it > 1 }
        .map { it.key }
        .forEach { duplicatedDependencyId -> registerProblem(DuplicatedDependencyWarning(duplicatedDependencyId)) }

    if (dependencies.count { it.isModule } == 0) {
      registerProblem(NoModuleDependencies(descriptorPath))
    }

    val sinceBuild = plugin.sinceBuild
    val untilBuild = plugin.untilBuild
    if (sinceBuild != null && untilBuild != null && sinceBuild > untilBuild) {
      registerProblem(SinceBuildGreaterThanUntilBuild(descriptorPath, sinceBuild, untilBuild))
    }

    val listenersAvailableSinceBuild = IdeVersion.createIdeVersion("193")
    if (sinceBuild != null && sinceBuild < listenersAvailableSinceBuild) {
      if (plugin.applicationListeners.isNotEmpty()) {
        registerProblem(ElementAvailableOnlySinceNewerVersion("applicationListeners", listenersAvailableSinceBuild, sinceBuild, untilBuild))
      }
      if (plugin.projectListeners.isNotEmpty()) {
        registerProblem(ElementAvailableOnlySinceNewerVersion("projectListeners", listenersAvailableSinceBuild, sinceBuild, untilBuild))
      }
    }

    validateListeners(plugin.applicationListeners)
    validateListeners(plugin.projectListeners)
  }

  private fun validateListeners(listenersElements: List<Element>) {
    val mandatoryAttributes = listOf("class", "topic")
    for (listener in listenersElements) {
      for (mandatoryAttribute in mandatoryAttributes) {
        val hasAttribute = listener.getAttributeValue(mandatoryAttribute) != null
        if (!hasAttribute) {
          registerProblem(ElementMissingAttribute("listener", mandatoryAttribute))
        }
      }
    }
  }

  private fun resolveDocumentAndValidateBean(
      originalDocument: Document,
      documentUrl: URL,
      documentPath: String,
      pathResolver: ResourceResolver,
      icons: List<PluginIcon>
  ): IdePluginImpl? {
    val document = resolveXIncludesOfDocument(originalDocument, documentUrl, documentPath, pathResolver) ?: return null
    val bean = readDocumentIntoXmlBean(document) ?: return null
    validatePluginBean(bean)
    if (hasErrors()) return null

    val plugin = IdePluginImpl()
    plugin.underlyingDocument = document
    plugin.icons.addAll(icons)
    plugin.setInfoFromBean(bean)

    val themeFiles = readPluginThemes(plugin, documentUrl, pathResolver) ?: return null
    plugin.declaredThemes.addAll(themeFiles)

    validatePlugin(plugin)
    return if (hasErrors()) null else plugin
  }

  private fun readPluginThemes(plugin: IdePlugin, documentUrl: URL, pathResolver: ResourceResolver): List<IdeTheme>? {
    val themePaths = plugin.extensions[INTELLIJ_THEME_EXTENSION].mapNotNull { it.getAttribute("path")?.value }

    val themes = arrayListOf<IdeTheme>()

    for (themePath in themePaths) {
      val absolutePath = if (themePath.startsWith("/")) themePath else "/$themePath"
      when (val resolvedTheme = pathResolver.resolveResource(absolutePath, documentUrl)) {
        is ResourceResolver.Result.Found -> {
          val theme = try {
            jsonMapper.readValue(resolvedTheme.resourceStream, IdeTheme::class.java)
          } catch (e: Exception) {
            registerProblem(UnableToReadTheme(descriptorPath, themePath, e.localizedMessage))
            return null
          }
          themes += theme
        }
        is ResourceResolver.Result.NotFound -> {
          registerProblem(UnableToFindTheme(descriptorPath, themePath))
        }
        is ResourceResolver.Result.Failed -> {
          registerProblem(UnableToReadTheme(descriptorPath, themePath, resolvedTheme.exception.localizedMessage))
        }
      }
    }
    return themes
  }

  private fun resolveXIncludesOfDocument(
      document: Document,
      documentUrl: URL,
      documentPath: String,
      pathResolver: ResourceResolver
  ): Document? = try {
    XIncluder.resolveXIncludes(document, documentUrl, documentPath, pathResolver)
  } catch (e: XIncluderException) {
    LOG.info("Unable to resolve <xi:include> elements of descriptor '$descriptorPath' from '$pluginFile'", e)
    registerProblem(XIncludeResolutionErrors(descriptorPath, e.message))
    null
  }

  private fun readDocumentIntoXmlBean(document: Document): PluginBean? {
    return try {
      PluginBeanExtractor.extractPluginBean(document)
    } catch (e: Exception) {
      registerProblem(UnableToReadDescriptor(descriptorPath, e.localizedMessage))
      LOG.info("Unable to read plugin descriptor $descriptorPath of $pluginFile", e)
      null
    }

  }

  private fun registerProblem(problem: PluginProblem) {
    problems += problem
  }

  private fun hasErrors() = problems.any { it.level === PluginProblem.Level.ERROR }

  private fun validateId(id: String?) {
    if (id != null) {
      if ("com.your.company.unique.plugin.id" == id) {
        registerProblem(PropertyWithDefaultValue(descriptorPath, PropertyWithDefaultValue.DefaultProperty.ID))
      } else {
        validatePropertyLength("id", id, MAX_PROPERTY_LENGTH)
        validateNewlines("id", id)
      }
    }
  }

  private fun validateName(name: String?) {
    when {
      name.isNullOrBlank() -> registerProblem(PropertyNotSpecified("name", descriptorPath))
      "Plugin display name here" == name -> registerProblem(PropertyWithDefaultValue(descriptorPath, PropertyWithDefaultValue.DefaultProperty.NAME))
      "plugin".contains(name) -> registerProblem(PluginWordInPluginName(descriptorPath))
      else -> {
        validatePropertyLength("name", name, MAX_PROPERTY_LENGTH)
        validateNewlines("name", name)
      }
    }
  }

  private fun validateDescription(htmlDescription: String?) {
    if (htmlDescription.isNullOrEmpty()) {
      registerProblem(PropertyNotSpecified("description", descriptorPath))
      return
    }
    validatePropertyLength("description", htmlDescription, MAX_LONG_PROPERTY_LENGTH)

    val textDescription = Jsoup.parseBodyFragment(htmlDescription).text()

    if (textDescription.length < 40) {
      registerProblem(ShortDescription())
      return
    }

    if (textDescription.contains("Enter short description for your plugin here.") || textDescription.contains("most HTML tags may be used")) {
      registerProblem(DefaultDescription(descriptorPath))
      return
    }

    val latinSymbols = latinSymbolsRegex.findAll(textDescription).count()
    if (latinSymbols < 40) {
      registerProblem(NonLatinDescription())
    }
  }

  private fun validateChangeNotes(changeNotes: String?) {
    if (changeNotes.isNullOrBlank()) {
      //Too many plugins don't specify the change-notes, so it's too strict to require them.
      //But if specified, let's check that the change-notes are long enough.
      return
    }

    if (changeNotes.length < 40) {
      registerProblem(ShortChangeNotes(descriptorPath))
      return
    }

    if (changeNotes.contains("Add change notes here") || changeNotes.contains("most HTML tags may be used")) {
      registerProblem(DefaultChangeNotes(descriptorPath))
    }

    validatePropertyLength("<change-notes>", changeNotes, MAX_LONG_PROPERTY_LENGTH)
  }

  private fun validateNewlines(propertyName: String, propertyValue: String) {
    if (propertyValue.trim().contains("\n")) {
      registerProblem(ContainsNewlines(propertyName, descriptorPath))
    }
  }

  private fun validatePropertyLength(propertyName: String, propertyValue: String, maxLength: Int) {
    if (propertyValue.length > maxLength) {
      registerProblem(TooLongPropertyValue(descriptorPath, propertyName, propertyValue.length, maxLength))
    }
  }

  private fun validateVendor(vendorBean: PluginVendorBean?) {
    if (vendorBean == null) {
      registerProblem(PropertyNotSpecified("vendor", descriptorPath))
      return
    }

    if (vendorBean.url.isNullOrBlank() && vendorBean.email.isNullOrBlank() && vendorBean.name.isNullOrBlank()) {
      registerProblem(PropertyNotSpecified("vendor", descriptorPath))
      return
    }

    if ("YourCompany" == vendorBean.name) {
      registerProblem(PropertyWithDefaultValue(descriptorPath, PropertyWithDefaultValue.DefaultProperty.VENDOR))
    }
    validatePropertyLength("vendor", vendorBean.name, MAX_PROPERTY_LENGTH)

    if ("https://www.yourcompany.com" == vendorBean.url) {
      registerProblem(PropertyWithDefaultValue(descriptorPath, PropertyWithDefaultValue.DefaultProperty.VENDOR_URL))
    }
    validatePropertyLength("vendor url", vendorBean.url, MAX_PROPERTY_LENGTH)

    if ("support@yourcompany.com" == vendorBean.email) {
      registerProblem(PropertyWithDefaultValue(descriptorPath, PropertyWithDefaultValue.DefaultProperty.VENDOR_EMAIL))
    }
    validatePropertyLength("vendor email", vendorBean.email, MAX_PROPERTY_LENGTH)
  }

  private fun validateSinceBuild(sinceBuild: String?) {
    if (sinceBuild == null) {
      registerProblem(SinceBuildNotSpecified(descriptorPath))
    } else {
      val sinceBuildParsed = IdeVersion.createIdeVersionIfValid(sinceBuild)
      if (sinceBuildParsed == null) {
        registerProblem(InvalidSinceBuild(descriptorPath, sinceBuild))
      } else {
        if (sinceBuildParsed.baselineVersion < 130 && sinceBuild.endsWith(".*")) {
          registerProblem(InvalidSinceBuild(descriptorPath, sinceBuild))
        }
        if (sinceBuildParsed.baselineVersion > 2000) {
          registerProblem(ErroneousSinceBuild(descriptorPath, sinceBuildParsed))
        }
      }
    }
  }

  private fun validateUntilBuild(untilBuild: String) {
    val untilBuildParsed = IdeVersion.createIdeVersionIfValid(untilBuild)
    if (untilBuildParsed == null) {
      registerProblem(InvalidUntilBuild(descriptorPath, untilBuild))
    } else {
      if (untilBuildParsed.baselineVersion > 2000) {
        registerProblem(ErroneousUntilBuild(descriptorPath, untilBuildParsed))
      }
    }
  }

  private fun validateIdeaVersion(versionBean: IdeaVersionBean?) {
    if (versionBean == null) {
      registerProblem(PropertyNotSpecified("idea-version", descriptorPath))
      return
    }

    val sinceBuild = versionBean.sinceBuild
    validateSinceBuild(sinceBuild)

    val untilBuild = versionBean.untilBuild
    if (untilBuild != null) {
      validateUntilBuild(untilBuild)
    }
  }

}
