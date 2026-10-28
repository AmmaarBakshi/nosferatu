// The running intelligence. Plain Kotlin on the JVM so every algorithm runs in unit tests and in trace replay
// exactly as on the phone. It must not depend on Android or on BombayChitra's formats.
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
    testImplementation(libs.junit4)
    testImplementation(libs.kotlin.test)
}
