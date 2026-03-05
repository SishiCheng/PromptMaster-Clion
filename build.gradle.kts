plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.20"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.20"
    id("org.jetbrains.intellij.platform") version "2.11.0"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    intellijPlatform {
        clion(providers.gradleProperty("platformVersion"))

        bundledPlugins(
            "com.intellij.clion",
            // Classic C/C++ Engine — always present for compilation.
            // Declared as optional dependency in plugin.xml so our plugin
            // still loads in CLion 2025.3 Nova mode where it is suppressed.
            "com.intellij.cidr.lang",
        )

        pluginVerifier()
    }

    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = providers.gradleProperty("pluginUntilBuild")
        }
    }
}

tasks {
    wrapper {
        gradleVersion = "8.13"
    }

    // buildSearchableOptions requires IDE launch; disable in headless/CI
    buildSearchableOptions {
        enabled = false
    }
}
