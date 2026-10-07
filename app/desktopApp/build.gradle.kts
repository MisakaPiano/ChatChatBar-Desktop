import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

val desktopModelCatalogResources = layout.buildDirectory.dir("generated/desktop-model-catalog-resources")
val syncDesktopModelCatalogResources by tasks.registering(Copy::class) {
    from(rootProject.projectDir.resolve("app/src/main/assets")) {
        include("presets/manifest.json")
        include("presets/models/**")
        include("presets/formats/**")
        include("presets/characters/**")
        include("presets/world_books/**")
        include("presets/novelai/**", "danbooru/**", "prompt_dictionary/**", "tag_completion/**", "tokenizers/**")
    }
    into(desktopModelCatalogResources.map { it.dir("chatbar-assets") })
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

sourceSets {
    main {
        resources.srcDir(desktopModelCatalogResources)
    }
}

tasks.named("processResources") {
    dependsOn(syncDesktopModelCatalogResources)
}

// Deliberately not part of check/test/run; this opt-in runner never accepts a credential.
tasks.register<JavaExec>("runNovelAiPhase7Smoke") {
    group = "verification"
    dependsOn("classes")
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.example.chatbar.desktop.DesktopNovelAiPhase7Smoke")
    systemProperty("ccb.phase7.liveConfirmed", providers.gradleProperty("phase7LiveConfirmed").getOrElse("false"))
    systemProperty("ccb.phase7.outputRoot", rootProject.projectDir.parentFile.resolve(".phase7-live-data").absolutePath)
    systemProperty("ccb.phase7.smokePrompt", providers.gradleProperty("phase7SmokePrompt").getOrElse(""))
}

// Offline reuse of the already accepted image. Never loads a credential or sends HTTP.
tasks.register<JavaExec>("verifyPhase7LocalFixture") {
    group = "verification"
    dependsOn("testClasses")
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.example.chatbar.desktop.DesktopPhase7LocalFixtureCheck")
    args(rootProject.projectDir.parentFile.resolve(".phase7-live-data").absolutePath)
}

dependencies {
    implementation(project(":sharedCore"))
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.sqlite.jdbc)
    implementation(libs.jna)
    implementation(libs.jna.platform)
    implementation(libs.icons.lucide.cmp)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.msgpack.core)
    testImplementation(testFixtures(project(":sharedCore")))
}

// Local manual product acceptance fixture; test classpath only, no real AI transport or credentials.
tasks.register<JavaExec>("runPhase7FinalProductFixture") {
    group = "verification"
    dependsOn("testClasses")
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.example.chatbar.desktop.DesktopFinalProductFixture")
}

compose.desktop {
    application {
        mainClass = "com.example.chatbar.desktop.MainKt"
        jvmArgs("-Dchatbar.desktop.applicationHome=\$ROOTDIR")

        nativeDistributions {
            // The packaged runtime must retain JDBC and native-library support for local tag catalogs.
            modules("java.sql", "jdk.unsupported")
            targetFormats(TargetFormat.Exe, TargetFormat.Msi)
            packageName = "ChatChatBarDesktop"
            packageVersion = "1.3.49"
            description = "ChatChatBar Desktop"
            vendor = "ChatChatBar"
            windows {
                // 256px PNG-in-ICO derived from baseline 5e76a9c mipmap-xxxhdpi/ic_launcher.png.
                iconFile.set(project.file("src/main/resources/brand/ccb.ico"))
            }
        }
    }
}
