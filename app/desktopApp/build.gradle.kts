import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.security.MessageDigest
import java.util.UUID
import java.nio.file.Files
import java.nio.file.StandardOpenOption

plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

val desktopModelCatalogResources = layout.buildDirectory.dir("generated/desktop-model-catalog-resources")
// Desktop release identity is independent of the Android version. This does not publish a release.
val desktopVersion = providers.gradleProperty("desktopVersion").getOrElse("0.1.0-preview.1")
require(desktopVersion.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.-]+)?")))
// Windows compares only three numeric fields; never silently strip Preview identity for installers.
val explicitInstallerVersion = providers.gradleProperty("desktopInstallerVersion")
val desktopInstallerVersion = explicitInstallerVersion.getOrElse(desktopVersion.substringBefore('-'))
fun windowsVersion(value: String): List<Int> {
    require(value.matches(Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)"))) { "DESKTOP_INSTALLER_VERSION_INVALID" }
    val parts = value.split('.').map { it.toIntOrNull() ?: error("DESKTOP_INSTALLER_VERSION_INVALID") }
    require(parts[0] in 0..255 && parts[1] in 0..255 && parts[2] in 0..65535) { "DESKTOP_INSTALLER_VERSION_RANGE" }
    return parts
}
windowsVersion(desktopInstallerVersion)
val desktopPackagingProfile = providers.gradleProperty("desktopPackagingProfile").getOrElse("desktop")
require(desktopPackagingProfile in setOf("desktop", "synthetic")) { "DESKTOP_PACKAGING_PROFILE_INVALID" }
val desktopNativeName = if (desktopPackagingProfile == "synthetic") "ChatChatBarDesktopSynthetic" else "ChatChatBarDesktop"
// Desktop-owned, stable product identities. Synthetic install/upgrade cannot replace the ordinary product.
val desktopUpgradeUuid = if (desktopPackagingProfile == "synthetic") "58e95fd1-709d-5813-9b5b-f518a4612bae" else "8e4fddbd-141f-5c1d-bfb2-1b48a6e46b81"
val previousInstallerVersion = providers.gradleProperty("desktopPreviousInstallerVersion")
val verifyDesktopInstallerIdentity by tasks.registering {
    group = "verification"
    val receipt = layout.buildDirectory.file("reports/desktop-installer/identity.properties")
    outputs.file(receipt)
    outputs.upToDateWhen { false }
    doLast {
        require(explicitInstallerVersion.isPresent) { "DESKTOP_INSTALLER_VERSION_EXPLICIT_REQUIRED" }
        val current = windowsVersion(desktopInstallerVersion)
        previousInstallerVersion.orNull?.let { previous ->
            val old = windowsVersion(previous)
            val differing = current.zip(old).firstOrNull { it.first != it.second }
            require(differing != null && differing.first > differing.second) { "DESKTOP_INSTALLER_VERSION_NOT_INCREASING" }
        }
        val target = receipt.get().asFile
        target.parentFile.mkdirs()
        target.writeText("version=$desktopVersion\ninstallerVersion=$desktopInstallerVersion\nprofile=$desktopPackagingProfile\npackageName=$desktopNativeName\nupgradeUuid=$desktopUpgradeUuid\npreviousInstallerVersion=${previousInstallerVersion.orNull ?: "UNSPECIFIED"}\n")
        println("DESKTOP_INSTALLER_IDENTITY_VERIFIED")
    }
}
tasks.configureEach {
    if (name in setOf("packageExe", "packageMsi", "packageReleaseExe", "packageReleaseMsi")) dependsOn(verifyDesktopInstallerIdentity)
}
// JDK17 generates recursive installation-directory cleanup and removes old products before
// the rollback transaction. Correct only the generated unsigned MSI; its CAB payload is unchanged.
tasks.withType<AbstractJPackageTask>().configureEach {
    if (targetFormat == TargetFormat.Msi) {
        val safetyTool = rootProject.projectDir.parentFile.resolve("tools/desktop/windows/msi_safety.py")
        // WiX3 file APIs reject MAX_PATH-length generated inputs. Keep this task-owned
        // jpackage scratch short instead of suppressing installer validation errors.
        val scratchId = UUID.randomUUID().toString()
        val packageScratch = project.projectDir.resolve("build/jpkg/$scratchId")
        freeArgs.addAll("--verbose", "--temp", packageScratch.absolutePath)
        doFirst {
            check(!packageScratch.exists()) { "DESKTOP_PACKAGE_SCRATCH_ALREADY_EXISTS" }
            Files.createDirectories(packageScratch.parentFile.toPath())
            Files.writeString(packageScratch.parentFile.resolve("$scratchId.owner").toPath(),
                "CCB_DESKTOP_JPACKAGE_SCRATCH\n$desktopPackagingProfile\n", StandardOpenOption.CREATE_NEW)
        }
        inputs.file(safetyTool)
        inputs.file(safetyTool.parentFile.resolve("directory_guard.c"))
        inputs.file(safetyTool.parentFile.resolve("build_directory_guard.py"))
        doLast {
            val artifact = destinationDir.get().asFile.resolve("$desktopNativeName-$desktopInstallerVersion.msi")
            check(artifact.isFile) { "DESKTOP_MSI_OUTPUT_MISSING" }
            val digest = MessageDigest.getInstance("SHA-256")
            artifact.inputStream().use { source ->
                val buffer = ByteArray(65536)
                while (true) { val count = source.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            val retained = layout.buildDirectory.dir("reports/windows-installer-safety/$name-${UUID.randomUUID()}").get().asFile
            check(retained.mkdirs())
            val raw = retained.resolve("original-jpackage-output.msi")
            Files.move(artifact.toPath(), raw.toPath())
            // A failed correction leaves no apparently deliverable unsafe MSI in the output folder.
            val result = providers.exec {
                commandLine("python", "-I", safetyTool.absolutePath, "--msi", raw.absolutePath, "--workspace", retained.resolve("verified").absolutePath,
                    "--expected-sha256", hash, "--profile", desktopPackagingProfile, "--output", artifact.absolutePath)
            }.standardOutput.asText.get()
            logger.lifecycle(result.trim())
        }
    }
}
val desktopBuildResources = layout.buildDirectory.dir("generated/desktop-build-resources")
val desktopCommit = providers.exec {
    commandLine("git", "-C", rootProject.projectDir.parentFile.absolutePath, "rev-parse", "HEAD")
}.standardOutput.asText.map { it.trim() }
val writeDesktopBuildIdentity by tasks.registering {
    inputs.property("version", desktopVersion)
    inputs.property("commit", desktopCommit)
    inputs.property("installerVersion", desktopInstallerVersion)
    inputs.property("packagingProfile", desktopPackagingProfile)
    outputs.dir(desktopBuildResources)
    doLast {
        val directory = desktopBuildResources.get().asFile.apply { mkdirs() }
        directory.resolve("desktop-build.properties").writeText("version=$desktopVersion\ncommit=${desktopCommit.get()}\ninstallerVersion=$desktopInstallerVersion\ninstallerVersionAssigned=${explicitInstallerVersion.isPresent}\npackagingProfile=$desktopPackagingProfile\n")
    }
}
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
        resources.srcDir(desktopBuildResources)
    }
}

tasks.named("processResources") {
    dependsOn(syncDesktopModelCatalogResources)
    dependsOn(writeDesktopBuildIdentity)
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
    implementation(libs.commonmark)
    implementation(libs.jsoup)
    // D-038: selected official v1.4.5 archive codec uses this exact Tink release.
    implementation("com.google.crypto.tink:tink:1.23.0")
    implementation("com.google.code.gson:gson:2.13.2")

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
            // The first Preview uses the corrected MSI. A bootstrap EXE must not embed an uncorrected MSI.
            targetFormats(TargetFormat.Msi)
            packageName = desktopNativeName
            packageVersion = desktopInstallerVersion
            description = "ChatChatBar Desktop"
            vendor = "ChatChatBar"
            windows {
                upgradeUuid = desktopUpgradeUuid
                perUserInstall = true
                // Keep programs separate from the default LOCALAPPDATA/ChatChatBarDesktop data root.
                // JDK17's nested per-user path omits intermediate directory cleanup (ICE64).
                // A distinct single-level program folder avoids the data root without bypassing ICE.
                installationPath = "${desktopNativeName}App"
                shortcut = desktopPackagingProfile == "desktop"
                menu = desktopPackagingProfile == "desktop"
                if (desktopPackagingProfile == "desktop") menuGroup = desktopNativeName
                // 256px PNG-in-ICO derived from baseline 5e76a9c mipmap-xxxhdpi/ic_launcher.png.
                iconFile.set(project.file("src/main/resources/brand/ccb.ico"))
            }
        }
    }
}
