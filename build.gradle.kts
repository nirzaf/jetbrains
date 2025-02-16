import com.jetbrains.plugin.structure.base.utils.isDirectory
import java.net.URL
import java.nio.file.FileSystems
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.PathMatcher
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.EnumSet
import java.util.jar.JarFile
import java.util.zip.ZipFile
import org.jetbrains.changelog.markdownToHTML
import org.jetbrains.intellij.or
import org.jetbrains.intellij.tasks.RunPluginVerifierTask.FailureLevel
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

fun properties(key: String) = project.findProperty(key).toString()

val isForceBuild = properties("forceBuild") == "true"
val isForceAgentBuild =
    isForceBuild ||
        properties("forceCodyBuild") == "true" ||
        properties("forceAgentBuild") == "true"
val isForceCodeSearchBuild = isForceBuild || properties("forceCodeSearchBuild") == "true"

// As https://www.jetbrains.com/updates/updates.xml adds a new "IntelliJ IDEA" YYYY.N version, add
// it to this list.
// Remove unsupported old versions from this list.
// Update gradle.properties pluginSinceBuild, pluginUntilBuild to match the min, max versions in
// this list.
val versionsOfInterest = listOf("2023.2", "2023.3", "2024.1", "2024.2").sorted()
val versionsToValidate =
    when (project.properties["validation"]?.toString()) {
      "lite" -> listOf(versionsOfInterest.first(), versionsOfInterest.last())
      null,
      "full" -> versionsOfInterest
      else ->
          error(
              "Unexpected validation property: \"validation\" should be \"lite\" or \"full\" (default) was \"${project.properties["validation"]}\"")
    }
val skippedFailureLevels =
    EnumSet.of(
        FailureLevel.COMPATIBILITY_PROBLEMS, // blocked by: compatibility hack for IJ 2022.1 / 2024+
        FailureLevel.DEPRECATED_API_USAGES,
        FailureLevel.INTERNAL_API_USAGES,
        FailureLevel.SCHEDULED_FOR_REMOVAL_API_USAGES, // blocked by: Kotlin UI DSL Cell.align
        FailureLevel.EXPERIMENTAL_API_USAGES,
        FailureLevel.NOT_DYNAMIC)!!

plugins {
  id("java")
  id("jvm-test-suite")
  id("org.jetbrains.kotlin.jvm") version "2.0.20"
  id("org.jetbrains.intellij") version "1.17.4"
  id("org.jetbrains.changelog") version "2.2.1"
  id("com.diffplug.spotless") version "6.25.0"
}

val platformVersion: String by project
val javaVersion: String by project

group = properties("pluginGroup")

version = properties("pluginVersion")

repositories {
  maven { url = uri("https://www.jetbrains.com/intellij-repository/releases") }
  mavenCentral()
}

intellij {
  pluginName.set(properties("pluginName"))
  version.set(platformVersion)
  type.set(properties("platformType"))

  // Plugin Dependencies. Uses `platformPlugins` property from the gradle.properties file.
  plugins.set(properties("platformPlugins").split(',').map(String::trim).filter(String::isNotEmpty))

  updateSinceUntilBuild.set(false)
}

dependencies {
  // ActionUpdateThread.jar contains copy of the
  // com.intellij.openapi.actionSystem.ActionUpdateThread class
  compileOnly(files("libs/ActionUpdateThread.jar"))
  implementation("org.commonmark:commonmark:0.22.0")
  implementation("org.commonmark:commonmark-ext-gfm-tables:0.22.0")
  implementation("org.eclipse.lsp4j:org.eclipse.lsp4j.jsonrpc:0.23.1")
  implementation("io.github.java-diff-utils:java-diff-utils:4.12")
  testImplementation("org.awaitility:awaitility-kotlin:4.2.1")
  testImplementation("org.mockito:mockito-core:5.12.0")
  testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
  testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.0.0")
  testImplementation("org.mockito.kotlin:mockito-kotlin:5.3.1")
}

spotless {
  java {
    target("src/*/java/**/*.java")
    importOrder()
    removeUnusedImports()
    googleJavaFormat()
  }
  kotlinGradle {
    ktfmt()
    trimTrailingWhitespace()
  }
  kotlin {
    ktfmt()
    trimTrailingWhitespace()
    target("src/**/*.kt")
    targetExclude("src/main/kotlin/com/sourcegraph/cody/agent/protocol_generated/**/*.kt")
    toggleOffOn()
  }
}

java {
  toolchain { languageVersion.set(JavaLanguageVersion.of(properties("javaVersion").toInt())) }
}

tasks.named("classpathIndexCleanup") { dependsOn("compileIntegrationTestKotlin") }

fun download(url: String, output: File) {
  if (output.exists()) {
    println("Cached $output")
    return
  }
  println("Downloading... $url")
  assert(output.parentFile.mkdirs()) { output.parentFile }
  Files.copy(URL(url).openStream(), output.toPath())
}

fun copyRecursively(input: File, output: File) {
  if (!input.isDirectory) {
    throw IllegalArgumentException("not a directory: $input")
  }
  if (!output.isDirectory) {
    Files.createDirectories(output.toPath())
  }
  val inputPath = input.toPath()
  val outputPath = output.toPath()
  Files.walkFileTree(
      inputPath,
      object : SimpleFileVisitor<java.nio.file.Path>() {
        override fun visitFile(
            file: java.nio.file.Path?,
            attrs: BasicFileAttributes?
        ): FileVisitResult {
          if (file != null) {
            val destination = outputPath.resolve(file.fileName)
            if (!destination.parent.isDirectory) {
              Files.createDirectories(destination.parent)
            }
            println("Copy ${inputPath.relativize(file)}")
            Files.copy(file, outputPath.resolve(file.fileName), StandardCopyOption.REPLACE_EXISTING)
          }
          return super.visitFile(file, attrs)
        }
      })
}

fun unzip(input: File, output: File, excludeMatcher: PathMatcher? = null) {
  var first = true
  val outputPath = output.toPath()
  JarFile(input).use { zip ->
    val entries = zip.entries()
    while (entries.hasMoreElements()) {
      val element = entries.nextElement()
      if (element.name.endsWith("/")) {
        continue
      }
      zip.getInputStream(element).use { stream ->
        val dest = outputPath.resolve(element.name)
        if (!dest.parent.isDirectory) {
          Files.createDirectories(dest.parent)
        }
        if (first) {
          if (Files.isRegularFile(dest)) {
            println("Cached $output")
            return
          } else {
            println("Unzipping... $input")
          }
        }
        first = false
        if (excludeMatcher?.matches(dest) != true) {
          println("unzip: ${element.name}")
          Files.copy(stream, dest, StandardCopyOption.REPLACE_EXISTING)
        }
      }
    }
  }
}

val githubArchiveCache: File =
    Paths.get(System.getProperty("user.home"), ".sourcegraph", "caches", "jetbrains").toFile()

fun Test.sharedIntegrationTestConfig(buildCodyDir: File, mode: String) {
  group = "verification"
  testClassesDirs = sourceSets["integrationTest"].output.classesDirs
  classpath = sourceSets["integrationTest"].runtimeClasspath

  include("**/AllSuites.class")

  val resourcesDir = project.file("src/integrationTest/resources")
  systemProperties(
      "cody-agent.trace-path" to
          "${layout.buildDirectory.asFile.get()}/sourcegraph/cody-agent-trace.json",
      "cody-agent.directory" to buildCodyDir.parent,
      "sourcegraph.verbose-logging" to "true",
      "cody.autocomplete.enableFormatting" to
          (project.property("cody.autocomplete.enableFormatting") as String? ?: "true"),
      "cody.integration.testing" to "true",
      "cody.ignore.policy.timeout" to 500, // Increased to 500ms as CI tends to be slower
      "idea.test.execution.policy" to "com.sourcegraph.cody.test.NonEdtIdeaTestExecutionPolicy",
      "test.resources.dir" to resourcesDir.absolutePath)

  environment(
      "CODY_RECORDING_MODE" to mode,
      "CODY_RECORDING_NAME" to "integration-test",
      "CODY_RECORDING_DIRECTORY" to resourcesDir.resolve("recordings").absolutePath,
      "CODY_SHIM_TESTING" to "true",
      "CODY_TEMPERATURE_ZERO" to "true",
      "CODY_TELEMETRY_EXPORTER" to "testing",
      // Fastpass has custom bearer tokens that are difficult to record with Polly
      "CODY_DISABLE_FASTPATH" to "true",
  )

  useJUnit()
  dependsOn("buildCody")
}

val isWindows = System.getProperty("os.name").lowercase().contains("win")
val pnpmPath =
    if (isWindows) {
      "pnpm.cmd"
    } else {
      "pnpm"
    }

tasks {
  val codeSearchCommit = "9d86a4f7d183e980acfe5d6b6468f06aaa0d8acf"
  fun downloadCodeSearch(): File {
    val url =
        "https://github.com/sourcegraph/sourcegraph-public-snapshot/archive/$codeSearchCommit.zip"
    val destination = githubArchiveCache.resolve("$codeSearchCommit.zip")
    download(url, destination)
    return destination
  }

  fun unzipCodeSearch(): File {
    val zip = downloadCodeSearch()
    val dir = githubArchiveCache.resolve("code-search")
    unzip(zip, dir, FileSystems.getDefault().getPathMatcher("glob:**.go"))
    return dir.resolve("sourcegraph-public-snapshot-$codeSearchCommit")
  }

  fun buildCodeSearch(): File? {
    if (System.getenv("SKIP_CODE_SEARCH_BUILD") == "true") return null
    val destinationDir = rootDir.resolve("src").resolve("main").resolve("resources").resolve("dist")
    if (!isForceCodeSearchBuild && destinationDir.exists()) {
      println("Cached $destinationDir")
      return destinationDir
    }

    val sourcegraphDir = unzipCodeSearch()
    exec {
      workingDir(sourcegraphDir.toString())
      commandLine(pnpmPath, "install", "--frozen-lockfile")
    }
    exec {
      workingDir(sourcegraphDir.toString())
      commandLine(pnpmPath, "generate")
    }
    val jetbrainsDir = sourcegraphDir.resolve("client").resolve("jetbrains")
    exec {
      commandLine(pnpmPath, "build")
      workingDir(jetbrainsDir)
    }
    val buildOutput =
        jetbrainsDir.resolve("src").resolve("main").resolve("resources").resolve("dist")
    copyRecursively(buildOutput, destinationDir)
    return destinationDir
  }

  fun downloadNodeBinaries(): File {
    val nodeCommit = properties("nodeBinaries.commit")
    val nodeVersion = properties("nodeBinaries.version")
    val url = "https://github.com/sourcegraph/node-binaries/archive/$nodeCommit.zip"
    val zipFile = githubArchiveCache.resolve("$nodeCommit.zip")
    download(url, zipFile)
    val destination = githubArchiveCache.resolve("node").resolve("node-binaries-$nodeCommit")
    unzip(zipFile, destination.parentFile)
    return destination.resolve(nodeVersion)
  }

  fun downloadCody(): File {
    val codyCommit = properties("cody.commit")
    val fromEnvironmentVariable = System.getenv("CODY_DIR")
    if (!fromEnvironmentVariable.isNullOrEmpty()) {
      // "~" works fine from the terminal, however it breaks IntelliJ's run configurations
      val pathString =
          if (fromEnvironmentVariable.startsWith("~")) {
            System.getProperty("user.home") + fromEnvironmentVariable.substring(1)
          } else {
            fromEnvironmentVariable
          }
      return Paths.get(pathString).toFile()
    }
    val url = "https://github.com/sourcegraph/cody/archive/$codyCommit.zip"
    val zipFile = githubArchiveCache.resolve("$codyCommit.zip")
    download(url, zipFile)
    val destination = githubArchiveCache.resolve("cody").resolve("cody-$codyCommit")
    unzip(zipFile, destination.parentFile)
    return destination
  }

  val buildCodyDir = layout.buildDirectory.asFile.get().resolve("sourcegraph").resolve("agent")

  fun buildCody(): File {
    if (!isForceAgentBuild && (buildCodyDir.listFiles()?.size ?: 0) > 0) {
      println("Cached $buildCodyDir")
      return buildCodyDir
    }
    val codyDir = downloadCody()
    println("Using cody from codyDir=$codyDir")
    exec {
      workingDir(codyDir)
      commandLine(pnpmPath, "install", "--frozen-lockfile")
    }
    val agentDir = codyDir.resolve("agent")
    exec {
      workingDir(agentDir)
      commandLine(pnpmPath, "run", "build")
    }
    copy {
      from(agentDir.resolve("dist"))
      into(buildCodyDir)
    }
    copy {
      from(downloadNodeBinaries())
      into(buildCodyDir)
      eachFile { permissions { unix("rwxrwxrwx") } }
    }

    return buildCodyDir
  }

  fun copyProtocol() {
    val codyDir = downloadCody()
    val sourceDir =
        codyDir.resolve(
            Paths.get(
                    "agent",
                    "bindings",
                    "kotlin",
                    "lib",
                    "src",
                    "main",
                    "kotlin",
                    "com",
                    "sourcegraph",
                    "cody",
                    "agent",
                    "protocol_generated")
                .toString())
    val targetDir =
        layout.projectDirectory.asFile.resolve(
            "src/main/kotlin/com/sourcegraph/cody/agent/protocol_generated")

    targetDir.deleteRecursively()
    sourceDir.copyRecursively(targetDir, overwrite = true)
    // in each file replace the package name
    for (file in targetDir.walkTopDown()) {
      if (file.isFile && file.extension == "kt") {
        val content = file.readText()
        // This is only a temporary solution to inject the notice.
        // I've kept here so that it's clear where the files are modified.
        val newContent =
            """
        |/*
        | * Generated file - DO NOT EDIT MANUALLY
        | * They are copied from the cody agent project using the copyProtocol gradle task.
        | * This is only a temporary solution before we fully migrate to generated protocol messages.
        | */
        |
    """
                .trimMargin() + content
        file.writeText(newContent)
      }
    }
  }

  // System properties that are used for testing purposes. These properties
  // should be consistently set in different local dev environments, like `./gradlew :runIde`,
  // `./gradlew test` or when testing inside IntelliJ
  val agentProperties =
      mapOf<String, Any>(
          "cody-agent.trace-path" to
              "${layout.buildDirectory.asFile.get()}/sourcegraph/cody-agent-trace.json",
          "cody-agent.directory" to buildCodyDir.parent,
          "sourcegraph.verbose-logging" to "true",
          "cody-agent.panic-when-out-of-sync" to
              (System.getProperty("cody-agent.panic-when-out-of-sync") ?: "true"),
          "cody-agent.fullDocumentSyncEnabled" to
              (System.getProperty("cody-agent.fullDocumentSyncEnabled") ?: "false"),
          "cody.autocomplete.enableFormatting" to
              (project.property("cody.autocomplete.enableFormatting") ?: "true"))

  fun getIdeaInstallDir(ideaVersion: String, ideaType: String): File? {
    val gradleHome = project.gradle.gradleUserHomeDir
    val cacheDir =
        File(gradleHome, "caches/modules-2/files-2.1/com.jetbrains.intellij.idea/idea$ideaType")
    val ideaDir = File(cacheDir, ideaVersion)
    return ideaDir.walk().find { it.name == "idea$ideaType-$ideaVersion" }
  }

  register("copyProtocol") { copyProtocol() }
  register("buildCodeSearch") { buildCodeSearch() }
  register("buildCody") { buildCody() }

  processResources { dependsOn(":buildCodeSearch") }

  // Set the JVM compatibility versions
  properties("javaVersion").let {
    withType<JavaCompile> {
      sourceCompatibility = it
      targetCompatibility = it
    }
    withType<KotlinCompile> { kotlinOptions.jvmTarget = it }
  }

  patchPluginXml {
    version.set(properties("pluginVersion"))

    // Extract the <!-- Plugin description --> section from README.md and provide for the plugin's
    // manifest
    pluginDescription.set(
        projectDir
            .resolve("README.md")
            .readText()
            .lines()
            .run {
              val start = "<!-- Plugin description -->"
              val end = "<!-- Plugin description end -->"

              if (!containsAll(listOf(start, end))) {
                throw GradleException(
                    "Plugin description section not found in README.md:\n$start ... $end")
              }
              subList(indexOf(start) + 1, indexOf(end))
            }
            .joinToString("\n")
            .run { markdownToHTML(this) },
    )
  }

  buildPlugin {
    dependsOn(project.tasks.getByPath("buildCody"))
    from(
        fileTree(buildCodyDir) {
          include("*")
          include("webviews/**")
        },
    ) {
      into("agent/")
    }

    doLast {
      // Assert that agent binaries are included in the plugin
      val pluginPath = buildPlugin.get().outputs.files.first()
      ZipFile(pluginPath).use { zip ->
        fun assertExists(name: String) {
          val path = "Sourcegraph/agent/$name"
          if (zip.getEntry(path) == null) {
            throw Error("Agent binary '$path' not found in plugin zip $pluginPath")
          }
        }
        assertExists("node-macos-arm64")
        assertExists("node-macos-x64")
        assertExists("node-linux-arm64")
        assertExists("node-linux-x64")
        assertExists("node-win-x64.exe")
      }
    }
  }

  patchPluginXml {
    sinceBuild = properties("pluginSinceBuild")
    untilBuild = properties("pluginUntilBuild")
  }

  runIde {
    dependsOn(project.tasks.getByPath("buildCody"))
    jvmArgs("-Djdk.module.illegalAccess.silent=true")

    agentProperties.forEach { (key, value) -> systemProperty(key, value) }

    val platformRuntimeVersion = project.findProperty("platformRuntimeVersion")
    val platformRuntimeType = project.findProperty("platformRuntimeType")
    if (platformRuntimeVersion != null || platformRuntimeType != null) {
      val ideaInstallDir =
          getIdeaInstallDir(
              platformRuntimeVersion.or(project.property("platformVersion")).toString(),
              platformRuntimeType.or(project.property("platformType")).toString())
              ?: throw GradleException(
                  "Could not find IntelliJ install for version: $platformRuntimeVersion")
      ideDir.set(ideaInstallDir)
    }
    // TODO: we need to wait to switch to Platform Gradle Plugin 2.0.0 to be able to have separate
    // runtime plugins
    // https://github.com/JetBrains/intellij-platform-gradle-plugin/issues/1489
    // val platformRuntimePlugins = project.findProperty("platformRuntimePlugins")
  }

  runPluginVerifier {
    ideVersions.set(versionsToValidate)
    failureLevel.set(EnumSet.complementOf(skippedFailureLevels))
  }

  // Configure UI tests plugin
  // Read more: https://github.com/JetBrains/intellij-ui-test-robot
  runIdeForUiTests {
    systemProperty("robot-server.port", "8082")
    systemProperty("ide.mac.message.dialogs.as.sheets", "false")
    systemProperty("jb.privacy.policy.text", "<!--999.999-->")
    systemProperty("jb.consents.confirmation.enabled", "false")
  }

  signPlugin {
    certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
    privateKey.set(System.getenv("PRIVATE_KEY"))
    password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
  }

  publishPlugin {
    token.set(System.getenv("PUBLISH_TOKEN"))

    // pluginVersion is based on the SemVer (https://semver.org) and supports pre-release labels,
    // like 2.1.7-nightly
    // Specify pre-release label to publish the plugin in a custom Release Channel automatically.
    // Read more:
    // https://plugins.jetbrains.com/docs/intellij/deployment.html#specifying-a-release-channel
    val channel = properties("pluginVersion").split('-').getOrElse(1) { "default" }
    channels.set(listOf(channel))

    if (channel == "default") {
      // The published version WILL NOT be available right after the JetBrains approval.
      // Instead, we control if and when we want to make it available.
      // (Note: there is ~48h waiting time for JetBrains approval).
      hidden.set(true)
    }
  }

  test { dependsOn(project.tasks.getByPath("buildCody")) }

  configurations {
    create("integrationTestImplementation") { extendsFrom(configurations.testImplementation.get()) }
    create("integrationTestRuntimeClasspath") { extendsFrom(configurations.testRuntimeOnly.get()) }
  }

  sourceSets {
    create("integrationTest") {
      kotlin.srcDir("src/integrationTest/kotlin")
      compileClasspath += main.get().output
      runtimeClasspath += main.get().output
    }
  }

  register<Test>("integrationTest") {
    description = "Runs the integration tests."
    sharedIntegrationTestConfig(buildCodyDir, "replay")
    dependsOn("processIntegrationTestResources")
    project.properties["repeatTests"]?.let { systemProperty("repeatTests", it) }
  }

  register<Test>("passthroughIntegrationTest") {
    description = "Runs the integration tests, passing everything through to the LLM."
    sharedIntegrationTestConfig(buildCodyDir, "passthrough")
    dependsOn("processIntegrationTestResources")
  }

  register<Test>("recordingIntegrationTest") {
    description = "Runs the integration tests and records the responses."
    sharedIntegrationTestConfig(buildCodyDir, "record")
    dependsOn("processIntegrationTestResources")
  }

  named<Copy>("processIntegrationTestResources") {
    from(sourceSets["integrationTest"].resources)
    into("${layout.buildDirectory.asFile.get()}/resources/integrationTest")
    exclude("**/.idea/**")
    exclude("**/*.xml")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
  }

  withType<Test> {
    systemProperty(
        "idea.test.src.dir", "${layout.buildDirectory.asFile.get()}/resources/integrationTest")
  }

  withType<KotlinCompile> { dependsOn("copyProtocol") }

  named("classpathIndexCleanup") { dependsOn("processIntegrationTestResources") }

  named("check") { dependsOn("integrationTest") }

  test {
    jvmArgs("-Didea.ProcessCanceledException=disabled")
    agentProperties.forEach { (key, value) -> systemProperty(key, value) }
    dependsOn("buildCody")
  }
}
