extension {
    name = "extensions/hansfix-addon.mpe"
}

android {
    namespace = "io.github.yydarlinker.hansfix"
}

// The addon runtime is deliberately Java-only. Do not inject a second Kotlin
// runtime into an app already extended by the official bundle.
configurations.configureEach {
    if (name.endsWith("RuntimeClasspath")) {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk7")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
        exclude(group = "org.jetbrains", module = "annotations")
    }
}
