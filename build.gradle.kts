plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.25"
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.25"
    id("org.jetbrains.intellij.platform") version "2.11.0"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // Emit Java 17 bytecode for maximum compatibility.
        // CLion 2024.2 defaults to JBR 21 but corporate environments may
        // override the JDK via CLION_JDK / JDK_HOME to JBR 17.
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3") {
        // Exclude Kotlin stdlib — the IDE already bundles it.
        // Shipping our own copy causes classloading conflicts on Windows,
        // where the JVM picks up the bundled JAR before the platform's.
        exclude(group = "org.jetbrains.kotlin")
    }

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
