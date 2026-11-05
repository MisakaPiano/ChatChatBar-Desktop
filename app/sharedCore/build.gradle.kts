import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("java-test-fixtures")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

val promptCalibrationInput = layout.buildDirectory.file("reports/prompt-budget-calibration/input.json")
tasks.named<org.gradle.api.tasks.testing.Test>("test") {
    outputs.file(promptCalibrationInput)
    systemProperty("ccb.prompt.calibration.output", promptCalibrationInput.get().asFile.absolutePath)
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.msgpack.core)
    implementation(libs.okhttp.sse)
    implementation(libs.jsoup)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testFixturesImplementation(libs.kotlinx.serialization.json)
    testFixturesImplementation(libs.kotlinx.coroutines.core)
}
