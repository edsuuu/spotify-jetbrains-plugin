import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.2.20"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "com.edsuuu.spotify"
version = "1.0.1"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

val localIdePath = providers.gradleProperty("localIdePath")
    .getOrElse("${System.getProperty("user.home")}/Applications/PhpStorm.app")

dependencies {
    intellijPlatform {
        local(file(localIdePath))
        pluginVerifier()
        testFramework(TestFrameworkType.Platform)
    }
    implementation("com.google.code.gson:gson:2.11.0")
    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "261"
            untilBuild = provider { null }
        }
    }
    sandboxContainer = layout.projectDirectory.dir("idea-sandbox")
}

tasks {
    runIde {
        providers.gradleProperty("runProjectPath").orNull?.let { args(it) }
    }
}

kotlin {
    jvmToolchain(21)
}
