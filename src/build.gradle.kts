plugins {
    kotlin("jvm")
}

repositories {
    google()
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib-jdk8"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.4.1")
    implementation("com.google.protobuf:protobuf-java:3.17.2")

    testImplementation("junit:junit:4.13.2")
}
