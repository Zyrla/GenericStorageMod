plugins {
    // Applies the correct Fabric Loom variant for the Minecraft version being built.
    id("dev.kikugie.loom-back-compat")
    // Uploads the jars to Modrinth.
    id("com.modrinth.minotaur")
}

version = "${sc.properties["mod.version"]}+${sc.current.version}"
base.archivesName = "generic-storage-mod"

/** Compares plain version numbers like "1.21.9" and "26.3" part by part. */
fun compareVersions(a: String, b: String): Int {
    val x = a.split('.').map { it.toIntOrNull() ?: 0 }
    val y = b.split('.').map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(x.size, y.size)) {
        val diff = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
        if (diff != 0) return diff
    }
    return 0
}

val requiredJava: JavaVersion = when {
    sc.current.parsed >= "26.1" -> JavaVersion.VERSION_25
    else -> JavaVersion.VERSION_21
}

dependencies {
    minecraft("com.mojang:minecraft:${sc.current.version}")
    // Applies Mojang's names on obfuscated (pre-26.1) versions; does nothing on 26.1+.
    loomx.applyMojangMappings()

    // `mod...` configurations work on every version; loom-back-compat converts them for 26.1+.
    modImplementation("net.fabricmc:fabric-loader:${sc.properties["deps.fabric_loader"]}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${sc.properties["deps.fabric_api"]}")
}

loom {
    splitEnvironmentSourceSets()

    mods {
        register("gsm") {
            sourceSet(sourceSets["main"])
            sourceSet(sourceSets["client"])
        }
    }

    runConfigs.all {
        runDirectory = rootProject.file("run") // every version shares the same run folder
    }
}

/**
 * Modrinth upload: `gradlew modrinth` uploads one Modrinth version per Minecraft version.
 * Needs a Modrinth access token in the MODRINTH_TOKEN environment variable (never put it in a file).
 * Add -PmodrinthDryRun to print what would be uploaded without uploading anything.
 */
modrinth {
    val mcReleases = sc.properties.rawOrNull("mod", "mc_releases")?.asList().orEmpty().map { it.toString() }
    val modVersion = sc.properties["mod.version"]

    token.set(providers.environmentVariable("MODRINTH_TOKEN"))
    projectId.set("genericstoragemod")
    versionNumber.set("$modVersion+${sc.current.version}")
    versionName.set("Generic Storage Mod $modVersion for Minecraft ${mcReleases.joinToString(", ")}")
    versionType.set("release")
    uploadFile.set(loomx.modJar)
    gameVersions.set(mcReleases)
    loaders.set(listOf("fabric"))
    changelog.set(rootProject.file("CHANGELOG.md").readText())
    dependencies {
        required.project("fabric-api")
    }
    debugMode.set(providers.gradleProperty("modrinthDryRun").isPresent)
}

java {
    targetCompatibility = requiredJava
    sourceCompatibility = requiredJava

    toolchain {
        languageVersion = JavaLanguageVersion.of(requiredJava.majorVersion)
    }
}

tasks {
    withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.release = requiredJava.majorVersion.toInt()
    }

    processResources {
        // Version overlays: src/main/overlays/<version>/ holds resource files (JSON data that changed format)
        // that replace the shared ones for that Minecraft version and every newer one. Newer overlays win.
        val overlays = rootProject.file("src/main/overlays").listFiles().orEmpty()
            .filter { it.isDirectory && sc.current.parsed >= it.name }
            .sortedWith(compareBy<File> { 0 }.thenComparator { a, b -> compareVersions(a.name, b.name) })
        overlays.forEach { from(it) }
        duplicatesStrategy = DuplicatesStrategy.INCLUDE // later (overlay) copies overwrite the shared file

        val props = mapOf(
            "version" to sc.properties["mod.version"],
            "minecraft" to sc.properties["mod.mc_compat"],
            "java" to requiredJava.majorVersion,
        )
        props.forEach { (key, value) -> inputs.property(key, value) }
        filesMatching("fabric.mod.json") { expand(props) }
    }

    // Ship the license inside the jar, as MIT requires.
    withType<Jar> {
        from(rootProject.file("LICENSE")) { rename { "${it}_generic-storage-mod" } }
    }

    // Builds the jar and copies it to build/libs/<mod version>/ in the main project folder.
    register<Copy>("buildAndCollect") {
        group = "build"
        description = "Builds this version's jar and copies it to build/libs/<mod version>/"
        from(loomx.modJar.flatMap { it.archiveFile })
        into(rootProject.layout.buildDirectory.dir("libs/${sc.properties["mod.version"]}"))
    }
}
