import com.google.protobuf.gradle.*

plugins {
    kotlin("jvm") version "1.7.10" // Updated Kotlin version
    id("com.google.protobuf") version "0.8.18"
}

group = "com.android.adblib"
version = "1.0-SNAPSHOT"

repositories {
    google()
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib-jdk8"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.1") // Updated coroutines version
    implementation("com.google.protobuf:protobuf-java:3.17.2")

    testImplementation("junit:junit:4.13.2")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.17.2"
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                if (!asMap.containsKey("java")) {
                    create("java") {
                        option("lite")
                    }
                }
            }
        }
    }
}

sourceSets {
    main {
        java {
            srcDirs("src/com/android/adblib")
        }
        proto {
            srcDir("proto")
        }
    }
    test {
        java {
            srcDirs("test/src/com/android/adblib")
        }
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    sourceCompatibility = "1.8"
    targetCompatibility = "1.8"
}

tasks.withType<Test> {
    useJUnitPlatform()
}
