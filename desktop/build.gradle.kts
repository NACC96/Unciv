
import com.google.common.io.Files
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

// Assembles a double-clickable macOS Unciv.app bundle containing Unciv.jar,
// agent runner, launcher script, and Info.plist.
tasks.register("macApp") {
    group = "unciv"
    description = "Assembles a double-clickable macOS Unciv.app bundle containing Unciv.jar, agent runner, launcher script, and Info.plist."
    dependsOn("dist")
    val appDir = layout.buildDirectory.dir("Unciv.app")
    outputs.dir(appDir)
    doLast {
        val root = appDir.get().asFile
        val contents = root.resolve("Contents")
        val macOs = contents.resolve("MacOS")
        val resources = contents.resolve("Resources")
        macOs.mkdirs()
        resources.mkdirs()

        // 1. Copy Unciv.jar
        val jarFile = layout.buildDirectory.file("libs/Unciv.jar").get().asFile
        if (jarFile.isFile) {
            jarFile.copyTo(resources.resolve("Unciv.jar"), overwrite = true)
        }

        // 2. Copy agent-runner/dist and node_modules
        val runnerDist = rootDir.resolve("agent-runner/dist")
        if (runnerDist.isDirectory) {
            runnerDist.copyRecursively(resources.resolve("agent-runner/dist"), overwrite = true)
        }
        val nodeModules = rootDir.resolve("agent-runner/node_modules")
        if (nodeModules.isDirectory) {
            nodeModules.copyRecursively(resources.resolve("agent-runner/node_modules"), overwrite = true)
        }

        // 3. Copy bundled Node if provided via -Dunciv.node.home
        val nodeHome = System.getProperty("unciv.node.home")?.let { file(it) }
        if (nodeHome != null && nodeHome.isDirectory) {
            nodeHome.copyRecursively(resources.resolve("node"), overwrite = true)
        }

        // 4. Copy icons
        for (iconName in listOf("Unciv.icns", "Unciv.ico")) {
            val icon = rootDir.resolve("extraImages/Icons/$iconName")
            if (icon.isFile) {
                icon.copyTo(resources.resolve(iconName), overwrite = true)
            }
        }

        // 5. Write Info.plist from template
        val plistTemplate = file("macos/Info.plist.template").readText()
        contents.resolve("Info.plist").writeText(
            plistTemplate.replace("@VERSION@", BuildConfig.appVersion)
        )

        // 6. Copy launcher script
        val launcherSrc = file("macos/Unciv")
        val launcherDest = macOs.resolve("Unciv")
        launcherSrc.copyTo(launcherDest, overwrite = true)
        launcherDest.setExecutable(true, false)
        println("macOS app bundle assembled at: ${root.absolutePath}")
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
