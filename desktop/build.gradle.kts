
import com.google.common.io.Files
import java.nio.file.Files as NioFiles
import com.unciv.build.BuildConfig
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("kotlin")
}

sourceSets {
    main {
        java.srcDir("src/")
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_1_8
    }
}
java {
    // required for building Unciv with a Java version higher than 24 (e.g. Java 25)
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_1_8
}

val mainClassName = "com.unciv.app.desktop.DesktopLauncher"
val assetsDir = file("../android/assets")
val discordDir = file("discord_rpc")
val deployFolder = file("../deploy")

tasks.register<JavaExec>("run") {
    dependsOn(tasks.getByName("classes"))
    mainClass.set(mainClassName)
    classpath = sourceSets.main.get().runtimeClasspath
    standardInput = System.`in`
    workingDir = assetsDir
    isIgnoreExitValue = true
}

tasks.register<JavaExec>("debug") {
    dependsOn(tasks.getByName("classes"))
    mainClass.set(mainClassName)
    classpath = sourceSets.main.get().runtimeClasspath
    standardInput = System.`in`
    workingDir = assetsDir
    isIgnoreExitValue = true
    debug = true
}

tasks.register<Jar>("dist") { // Compiles the jar file
    dependsOn(tasks.getByName("classes"))

    // META-INF/INDEX.LIST and META-INF/io.netty.versions.properties are duplicated, but I don't know why
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    from(files(sourceSets.main.get().output.resourcesDir))
    from(files(sourceSets.main.get().output.classesDirs))
    // see Laurent1967's comment on https://github.com/libgdx/libgdx/issues/5491
    from({
        (
            configurations.runtimeClasspath.get().resolve() // kotlin coroutine classes live here, thanks https://stackoverflow.com/a/59021222
            + configurations.compileClasspath.get().resolve()
        ).map { if (it.isDirectory) it else zipTree(it) }})
    from(files(assetsDir))
    exclude("mods", "SaveFiles", "MultiplayerFiles", "GameSettings.json", "lasterror.txt")
    // This is for the .dll and .so files to make the Discord RPC work on all desktops
    from(files(discordDir))
    archiveFileName.set("${BuildConfig.appName}.jar")

    manifest {
        attributes(mapOf("Main-Class" to mainClassName, "Specification-Version" to BuildConfig.appVersion))
    }
}

// Assembles the complete agent-match package (AI_AGENT_PLAN.md W6):
// Unciv.jar + the supervisor runner (agent-runner/dist + its node_modules)
// and, when -Dunciv.node.home points at a Node install, a bundled Node.
// The resulting layout matches DesktopAgentMatch discovery: <pkg>/agent-runner/dist
// and <pkg>/node.
tasks.register<Copy>("agentPackage") {
    group = "unciv"
    description = "Assembles Unciv.jar with the agent supervisor runner (and Node when -Dunciv.node.home is given)."
    dependsOn("dist")
    into(layout.buildDirectory.dir("agentPackage"))
    from(layout.buildDirectory.file("libs/Unciv.jar"))
    from(rootDir.resolve("agent-runner/dist")) { into("agent-runner/dist") }
    val nodeModules = rootDir.resolve("agent-runner/node_modules")
    if (nodeModules.isDirectory) from(nodeModules) { into("agent-runner/node_modules") }
    doLast {
        val nodeHome = System.getProperty("unciv.node.home")?.let { file(it) }
        if (nodeHome != null && nodeHome.isDirectory) {
            copy { from(nodeHome); into(layout.buildDirectory.dir("agentPackage/node")) }
        } else {
            println("NOTE: agentPackage has no bundled Node (pass -Dunciv.node.home=<node install dir>); the app will fall back to PATH discovery.")
        }
    }
}

// A native jpackage launcher keeps the running process associated with the
// app's bundle identity. A shell script exec'ing external java does not.
tasks.register("macApp") {
    group = "unciv"
    description = "Builds a native macOS app with a private Java runtime and agent runner."
    dependsOn("agentPackage")
    val destination = layout.buildDirectory.dir("native")
    // jpackage requires a fresh output directory. Keep previous bundles intact.
    outputs.upToDateWhen { false }
    doLast {
        check(System.getProperty("os.name").contains("Mac")) { "macApp must run on macOS" }
        val jpackage = File(System.getProperty("java.home"), "bin/jpackage")
        check(jpackage.canExecute()) { "macApp requires a JDK containing jpackage" }
        val input = layout.buildDirectory.dir("agentPackage").get().asFile
        check(input.resolve("Unciv.jar").isFile) { "Missing Unciv.jar" }
        check(input.resolve("agent-runner/dist/daemon.js").isFile) { "Build agent-runner before macApp" }
        check(input.resolve("agent-runner/node_modules").isDirectory) { "Install agent-runner dependencies before macApp" }
        val dest = destination.get().asFile.apply { mkdirs() }
        val staging = NioFiles.createTempDirectory(dest.toPath(), ".unciv-native-app-").toFile()
        val dataOptions = System.getProperty("unciv.data.dir")?.let {
            listOf("--java-options", "-Dunciv.data.dir=$it", "--java-options", "-XX:ErrorFile=$it/hs_err_pid%p.log")
        } ?: emptyList()
        // HotSpot's crash reporter can itself crash while printing compiled code, leaving no report at all.
        val crashReportOptions = listOf("--java-options", "-XX:+UnlockDiagnosticVMOptions",
            "--java-options", "-XX:ErrorLogPrintCodeLimit=0")
        val result = ProcessBuilder(listOf(
            jpackage.absolutePath, "--type", "app-image", "--name", "Unciv",
            "--app-version", BuildConfig.appVersion,
            "--mac-package-identifier", "com.unciv.app",
            "--input", input.absolutePath, "--main-jar", "Unciv.jar",
            "--main-class", mainClassName, "--dest", staging.absolutePath,
            "--java-options", "-Xmx4G",
            "--icon", rootDir.resolve("extraImages/Icons/Unciv.icns").absolutePath
        ) + dataOptions + crashReportOptions).inheritIO().start().waitFor()
        check(result == 0) { "jpackage failed ($result); staging retained at $staging" }
        val app = dest.resolve("Unciv.app")
        if (app.exists()) {
            check(app.renameTo(dest.resolve("Unciv-previous-${System.currentTimeMillis()}.app"))) {
                "Cannot preserve previous app at $app"
            }
        }
        check(staging.resolve("Unciv.app").renameTo(app)) { "Cannot move staged app from $staging to $app" }
        staging.delete() // now-empty temporary directory only
        println("Native macOS app: ${app.absolutePath}")
    }
}


enum class Platform(val desc: String) {
    Windows32("windows32"), Windows64("windows64"), Linux32("linux32"), Linux64("linux64"), MacOS("mac");
}

class PackrConfig(
    var platform: Platform? = null,
    var jdk: String? = null,
    var executable: String? = null,
    var classpath: List<String>? = null,
    var removePlatformLibs: List<String>? = null,
    var mainClass: String? = null,
    var vmArgs: List<String>? = null,
    var minimizeJre: String? = null,
    var cacheJre: File? = null,
    var resources: List<File>? = null,
    var outDir: File? = null,
    var platformLibsOutDir: File? = null,
    var iconResource: File? = null,
    var bundleIdentifier: String? = null
)

for (platform in Platform.entries) {
    val platformName = platform.toString()

    tasks.create("packr${platformName}") {
        // This task assumes that 'dist' has already been called - does not 'gradle depend' on it
        // so we can run 'dist' from one job and then run the packr builds from a different job

        // Needs to be here and not in doLast because the zip task depends on the outDir
        val jarFile = "$rootDir/desktop/build/libs/${BuildConfig.appName}.jar"
        val outputDir = file("packr")
        

        doLast {
            //  https://gist.github.com/seanf/58b76e278f4b7ec0a2920d8e5870eed6
            fun String.runCommand(workingDir: File) {
                val process = ProcessBuilder(*split(" ").toTypedArray())
                    .directory(workingDir)
                    .redirectOutput(ProcessBuilder.Redirect.PIPE)
                    .redirectError(ProcessBuilder.Redirect.PIPE)
                    .start()

                if (!process.waitFor(30, TimeUnit.SECONDS)) {
                    process.destroy()
                    throw RuntimeException("execution timed out: $this")
                }
                if (process.exitValue() != 0) {
                    throw RuntimeException("execution failed with code ${process.exitValue()}: $this")
                }
                println(process.inputStream.bufferedReader().readText())
            }


            if (outputDir.exists()) delete(outputDir)

            // Requires that both packr and the jre are downloaded, as per buildAndDeploy.yml, "Upload to itch.io"

            val jdkFile = when (platform) {
                Platform.Linux64 -> "jre-linux-64.tar.gz"
                Platform.Windows64 -> "jdk-windows-64.zip"
                else -> "jre-macOS.tar.gz"
            }

            val platformNameForPackrCmd =
                    if (platform == Platform.MacOS) "mac"
                    else platform.name.lowercase()

            val command = "java -jar $rootDir/packr-all-4.0.0.jar" +
                    " --platform $platformNameForPackrCmd" +
                    " --jdk $jdkFile" +
                    " --executable Unciv" +
                    " --classpath $jarFile" +
                    " --mainclass $mainClassName" +
                    " --vmargs Xmx4G " +
                    " --output $outputDir"
            command.runCommand(rootDir)
            Files.copy(File("$rootDir/extraImages/Icons/Unciv.ico"), File(outputDir, "Unciv.ico"))
        }

        tasks.register<Zip>("zip${platformName}") {
            archiveFileName.set("${BuildConfig.appName}-${platformName}.zip")
            from(outputDir)
            destinationDirectory.set(deployFolder)
        }

        finalizedBy("zip${platformName}")
    }
}

tasks.register<Zip>("zipLinuxFilesForJar") {
    archiveFileName.set("linuxFilesForJar.zip")
    from(file("linuxFilesForJar"))
    destinationDirectory.set(deployFolder)
}
