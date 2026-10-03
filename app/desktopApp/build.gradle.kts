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

dependencies {
    implementation(project(":sharedCore"))
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.jna)
    implementation(libs.jna.platform)
    implementation(libs.icons.lucide.cmp)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(testFixtures(project(":sharedCore")))
}

compose.desktop {
    application {
        mainClass = "com.example.chatbar.desktop.MainKt"
        jvmArgs("-Dchatbar.desktop.applicationHome=\$ROOTDIR")

        nativeDistributions {
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
