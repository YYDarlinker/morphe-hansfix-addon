group = "io.github.yydarlinker.hansfix"

patches {
    // TODO: Update this section with your project details.
    about {
        name = "YYDarlinker HansFix Addon - PREPARATION ONLY"
        description = "Independent expert-mode companion to official Morphe patches. Not implemented or released yet."
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
