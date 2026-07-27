plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlinx.serialization)
    application
}

group = "com.khan366kos"
version = "0.0.1"

repositories {
    mavenCentral()
}

application {
    mainClass.set("com.khan366kos.lis.client.ktor.TestAppKt")
    applicationDefaultJvmArgs = listOf("-Dfile.encoding=UTF-8")
}

val ktorVersion: String by project

dependencies {
    implementation(libs.coroutines)

    implementation(libs.kotlinx.serialization.json)

    implementation(libs.logback.classic)

    implementation(libs.poi)

    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")

    testImplementation(kotlin("test-junit5"))
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(17)
}
