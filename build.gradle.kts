plugins {
    kotlin("multiplatform") version libs.versions.kotlin.lang apply false
    kotlin("plugin.serialization") version libs.versions.kotlin.lang apply false
}

buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath(libs.sqldelight.gradle)
    }
}
