pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        maven("https://maven.kikugie.dev/releases") { name = "KikuGie Releases" }
        maven("https://maven.kikugie.dev/snapshots") { name = "KikuGie Snapshots" }
    }

    plugins {
        // Modrinth's official upload plugin (used by the `modrinth` task).
        id("com.modrinth.minotaur") version "2.10.0"
    }
}

plugins {
    // Builds the mod for several Minecraft versions from one set of source files.
    id("dev.kikugie.stonecutter") version "0.10"
    // Picks the right Fabric Loom variant for each version (26.1+ and older, obfuscated versions).
    id("dev.kikugie.loom-back-compat") version "0.4.3"
    // Lets Gradle download the Java version each Minecraft version needs.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

stonecutter {
    create(rootProject) {
        // Every Minecraft version the mod is built for. Settings for each are in stonecutter.properties.toml.
        versions("26.1.2", "26.2", "26.3")
        vcsVersion = "26.2"
    }
}

rootProject.name = "generic-storage-mod"
