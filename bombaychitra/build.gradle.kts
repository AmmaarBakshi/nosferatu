// BombayChitraAdapter: everything that knows BombayChitra's data formats lives here, behind the engine's
// RoadNetwork interface. Plain Kotlin; the app supplies an Android SQLite blob reader.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

dependencies {
    api(project(":engine"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit4)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.sqlite.jdbc)
}

tasks.test {
    // Integration tests read a real BombayChitra database when one is available; they skip otherwise.
    systemProperty("bombaychitra.db", providers.gradleProperty("bombaychitra.db").orNull ?: "")
}

// Replays a saved run with BombayChitra's real graph: ./gradlew :bombaychitra:replay --args="trace.txt"
tasks.register<JavaExec>("replay") {
    group = "verification"
    description = "Replays a Nosferatu trace through the tracker with BombayChitra's graph."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.nosferatu.bombaychitra.ReplayCliKt")
    workingDir = projectDir
    systemProperty("bombaychitra.db", providers.gradleProperty("bombaychitra.db").orNull ?: "")
    systemProperty("nosferatu.root", rootDir.absolutePath)
}
