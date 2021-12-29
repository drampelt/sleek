plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

repositories {
    mavenCentral()
    maven { url = uri("https://maven.pkg.jetbrains.space/public/p/ktor/eap") }
}

kotlin {
    val os = System.getProperty("os.name")
    val arch = System.getProperty("os.arch")
    val isMingwX64 = os.startsWith("Windows")
    val nativeTarget = when {
        os == "Mac OS X" -> if (arch == "aarch64") macosArm64("native") else macosX64("native")
        os == "Linux" -> linuxX64("native")
        isMingwX64 -> mingwX64("native")
        else -> throw GradleException("Host OS is not supported in Kotlin/Native.")
    }
    nativeTarget.apply {
        binaries {
            executable {
                entryPoint = "com.danielrampelt.sleek.main"
            }
        }
    }

    sourceSets {
        named("commonMain") {
            dependencies {
                implementation(libs.ktor.server.core)
                implementation(libs.ktor.server.cio)
                implementation(libs.ktor.server.hostCommon)
                implementation(libs.ktor.server.statusPages)
                // These don't support native servers yet but should with the full 2.0 release
//                implementation(libs.ktor.server.contentNegotiation)
//                implementation(libs.ktor.server.forwardedHeaders)
//                implementation(libs.ktor.server.defaultHeaders)
//                implementation(libs.ktor.server.callLogging)
//                implementation(libs.ktor.server.auth)
//                implementation(libs.ktor.serialization.kotlinxJson)
            }
        }
    }
}
