import com.unciv.build.BuildConfig
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent

// Java 21+ deprecates dynamic agent loading: https://openjdk.org/jeps/451
val mockitoAgent = configurations.create("mockitoAgent")

// Support Gradle 9 caching seeing the mockito agent
private class MockitoAgentArgumentProvider(
    @get:Classpath val agentJar: FileCollection
) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> =
        listOf("-javaagent:${agentJar.singleFile}")
}

dependencies {
    testImplementation(libs.junit)
    @Suppress("AvoidDuplicateDependencies") // false positive
    testImplementation(libs.mockito)
    @Suppress("AvoidDuplicateDependencies")
    mockitoAgent(libs.mockito) { isTransitive = false }
    // Agent orchestration loop tests drive DesktopAgentMatch with scripted runners.
    testImplementation(project(":desktop"))
}

tasks {
    register("writeAtlasRuntimeClasspath") {
        dependsOn("testClasses")
        doLast {
            val scratch = System.getenv("UNCIV_ATLAS_BUILD_ROOT")
                ?: error("Run through scripts/atlas/check.sh or set UNCIV_ATLAS_BUILD_ROOT")
            file("$scratch/atlas-runtime-classpath.txt").writeText(sourceSets.test.get().runtimeClasspath.asPath)
        }
    }
    test {
        workingDir = file("../android/assets")
        testLogging.lifecycle {
            events(
                    TestLogEvent.FAILED,
                    TestLogEvent.STANDARD_ERROR,
                    TestLogEvent.STANDARD_OUT
            )

            exceptionFormat = TestExceptionFormat.FULL
        }

        jvmArgumentProviders.add(MockitoAgentArgumentProvider(mockitoAgent))

        // Forward latency-test save file path to the test JVM
        System.getProperty("unciv.nextTurnSaveFile")?.let { systemProperty("unciv.nextTurnSaveFile", it) }
    }
}

sourceSets {
    test {
        java.srcDir("src")
    }
}

eclipse.project {
    name = "${BuildConfig.appName}-tests"
}
