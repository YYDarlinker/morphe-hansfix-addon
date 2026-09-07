group = "io.github.yydarlinker.hansfix"

patches {
    // TODO: Update this section with your project details.
    about {
        name = "YYDarlinker HansFix Addon"
        description = "Requires official Captions; independent expert-mode addon"
        source = "https://github.com/YYDarlinker/morphe-hansfix-addon"
        author = "YYDarlinker"
        contact = "na"
        website = "https://github.com/YYDarlinker/morphe-hansfix-addon"
        license = "GPLv3"
    }
}

// Separate configuration so gson is available at runtime for the
// generatePatchesList task but never bundled into the APK.
val patchListGeneratorClasspath = configurations.create("patchListGeneratorClasspath")

dependencies {
    // Integration runtime only: never included in the production mpp or publishing classpath.
    testImplementation("app.morphe:morphe-patcher:1.12.0") {
        version { strictly("1.12.0") }
    }
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation("com.android.tools.build:apkzlib:9.1.1")
    testImplementation("com.google.guava:guava:33.5.0-jre")
    testImplementation(libs.gson)
    compileOnly(libs.gson)
    patchListGeneratorClasspath(libs.gson)
}

tasks {
    register<JavaExec>("generatePatchesList") {
        description = "Build patch with patch list"

        dependsOn(build)

        classpath = sourceSets["main"].runtimeClasspath + patchListGeneratorClasspath
        mainClass.set("util.PatchListGeneratorKt")
    }

    // Used by gradle-semantic-release-plugin.
    publish {
        dependsOn("generatePatchesList")
    }
}

// src/test contains a standalone JavaExec harness, not JUnit tests. Keep ordinary build/test
// valid when no framework tests are discovered; do not suppress actual test failures or --tests
// filter mismatches. Java runtime regression tests remain a separate CI Python/JDK invocation.
tasks.named<org.gradle.api.tasks.testing.Test>("test") {
    failOnNoDiscoveredTests.set(false)
}

// Standalone opt-in integration entry point. No build/publish/finalizedBy wiring and no sessions
// in Gradle's test worker: every requested mode runs once in a fresh JVM with a shared file lock.
tasks.register<JavaExec>("runIntegration") {
    group = "verification"
    description = "Run real separate-bundle Patcher 1.12.0 integration and write an unsigned APK"
    dependsOn(tasks.named("testClasses"))
    // Patcher's JAR loader is parent-first. Exclude local production classes/resources so the
    // addon MUST be loaded from the supplied mpp, not accidentally from sourceSets.main.output.
    classpath = sourceSets["test"].runtimeClasspath - sourceSets["main"].output
    mainClass.set("io.github.yydarlinker.hansfix.integration.IntegrationHarnessKt")
    maxHeapSize = "6G"
    systemProperty("hansfix.integration.repo", rootProject.projectDir.absolutePath)
    val properties = mapOf(
        "mode" to "mode", "input" to "input", "official" to "official", "addon" to "addon",
        "outputDir" to "output-dir", "expectedAddonFailure" to "expected-addon-failure",
        "inputSha" to "input-sha", "officialSha" to "official-sha"
    )
    properties.forEach { (property, argument) ->
        providers.gradleProperty("integration.$property").orNull?.let { args("--$argument", it) }
    }
}

// Read-only analysis of an already-produced APK; never patches, signs or installs it.
tasks.register<JavaExec>("runOutputAudit") {
    group = "verification"
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath - sourceSets["main"].output
    mainClass.set("io.github.yydarlinker.hansfix.audit.OutputAuditKt")
    maxHeapSize = "6G"
    systemProperty("hansfix.audit.repo", rootProject.projectDir.absolutePath)
    listOf("apk", "baseline", "report", "addon").forEach { key ->
        providers.gradleProperty("audit.$key").orNull?.let { args("--$key", it) }
    }
}
