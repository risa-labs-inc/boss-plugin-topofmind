import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.3.0"
    id("org.jetbrains.compose") version "1.10.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.0"
}

group = "ai.rever.boss.plugin.dynamic"
version = "1.2.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Auto-detect CI environment
val useLocalDependencies = System.getenv("CI") != "true"
val bossPluginApiPath = "../boss-plugin-api"

repositories {
    google()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

dependencies {
    if (useLocalDependencies) {
        // Local development: use boss-plugin-api JAR from sibling repo.
        //
        // 1.0.90 is boss-plugin-api#44 (the tab-transfer surface: moveTabToPane, liveWorkspaceIds,
        // activePanelId, selectedTabId, allWindowTabs and friends, plus BossColors.accentText).
        // It is NOT released yet, so this resolves only against a locally built jar - CI takes the
        // latest published release instead and will pick 1.0.90 up once #44 merges.
        //
        // This pin said 1.0.88 first and #50 took it; it then said 1.0.89 and an unrelated release
        // took that on 2026-09-10, verified against the published jar rather than assumed. Because a
        // local jar of the matching name shadows the download, the local build stayed green while
        // CI compiled against bytes without these members and failed on references that plainly
        // existed on disk. If #44 slips behind another api merge, this number moves again.
        compileOnly(files("$bossPluginApiPath/build/libs/boss-plugin-api-1.0.90.jar"))
    } else {
        // CI: use downloaded JAR
        compileOnly(files("build/downloaded-deps/boss-plugin-api.jar"))
    }
    
    // Compose dependencies
    implementation(compose.desktop.currentOs)
    implementation(compose.runtime)
    implementation(compose.ui)
    implementation(compose.foundation)
    implementation(compose.material)
    implementation(compose.materialIconsExtended)
    
    // Decompose for ComponentContext
    implementation("com.arkivanov.decompose:decompose:3.3.0")
    implementation("com.arkivanov.essenty:lifecycle:2.5.0")
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    // The api is compileOnly for the plugin (the host provides it at runtime), so the tests need
    // it on their own runtime classpath from the same place - one conditional, not two answers.
    testImplementation(kotlin("test"))
    testImplementation(
        if (useLocalDependencies) {
            files("$bossPluginApiPath/build/libs/boss-plugin-api-1.0.90.jar")
        } else {
            files("build/downloaded-deps/boss-plugin-api.jar")
        }
    )
}

tasks.test {
    useJUnitPlatform()
}

// Task to build plugin JAR with compiled classes only
tasks.register<Jar>("buildPluginJar") {
    archiveFileName.set("boss-plugin-topofmind-${version}.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    
    manifest {
        attributes(
            "Implementation-Title" to "BOSS Top of Mind Plugin",
            "Implementation-Version" to version,
            "Main-Class" to "ai.rever.boss.plugin.dynamic.topofmind.TopofmindDynamicPlugin"
        )
    }
    
    // Include compiled classes
    from(sourceSets.main.get().output)
    
    // Include plugin manifest
    from("src/main/resources")
}

// Sync version from build.gradle.kts into plugin.json (single source of truth)
tasks.processResources {
    filesMatching("**/plugin.json") {
        filter { line ->
            line.replace(Regex(""""version"\s*:\s*"[^"]*""""), """"version": "\$version"""")
        }
    }
}

tasks.build {
    dependsOn("buildPluginJar")
}

// Fat JAR for out-of-process plugin execution
tasks.register<Jar>("shadowJar") {
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes(
            "Main-Class" to "ai.rever.boss.plugin.runtime.PluginProcessMainKt"
        )
    }
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    from(sourceSets.main.get().output)
    from("src/main/resources")
}
