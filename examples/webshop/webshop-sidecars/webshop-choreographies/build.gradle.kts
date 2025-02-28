/*
 * This file was generated by the Gradle 'init' task.
 *
 * This generated file contains a sample Java library project to get you started.
 * For more details on building Java & JVM projects, please refer to https://docs.gradle.org/8.10.2/userguide/building_java_projects.html in the Gradle documentation.
 */

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

import org.apache.tools.ant.taskdefs.condition.Os
import com.google.protobuf.gradle.*

group = "dev.chords"
version = "0.1.0"

plugins {
    // Apply the java-library plugin for API and implementation separation.
    `java-library`

    // Protobuf plugin used to compile .proto sources
    id("com.google.protobuf") version "0.9.4"
}

repositories {
    // Use Maven Central for resolving dependencies.
    mavenCentral()
    mavenLocal()
}

var grpcVersion = "1.68.0"

dependencies {
    // Use JUnit Jupiter for testing.
    testImplementation(libs.junit.jupiter)

    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    implementation(project(":reactive-middleware"))

    // ## gRPC ##
    runtimeOnly ("io.grpc:grpc-netty-shaded:${grpcVersion}")
    api("com.google.protobuf:protobuf-java:3.6.1")
    api ("io.grpc:grpc-protobuf:${grpcVersion}")
    api ("io.grpc:grpc-stub:${grpcVersion}")

    if (JavaVersion.current().isJava9Compatible()) {
        // Workaround for @javax.annotation.Generated
        // see: https://github.com/grpc/grpc-java/issues/3633
        implementation("javax.annotation:javax.annotation-api:1.3.1")
    }
}

var archSuffix = if (Os.isFamily(Os.FAMILY_MAC)) { ":osx-x86_64" } else { "" }

protobuf {
  protoc {
    // The artifact spec for the Protobuf Compiler
    artifact = "com.google.protobuf:protoc:3.6.1$archSuffix"
  }
  plugins {
    // Optional: an artifact spec for a protoc plugin, with "grpc" as
    // the identifier, which can be referred to in the "plugins"
    // container of the "generateProtoTasks" closure.
    id("grpc") {
      artifact = "io.grpc:protoc-gen-grpc-java:1.15.1$archSuffix"
    }
  }
  generateProtoTasks {
    ofSourceSet("main").forEach {
      it.plugins {
        // Apply the "grpc" plugin whose spec is defined above, without
        // options. Note the braces cannot be omitted, otherwise the
        // plugin will not be added. This is because of the implicit way
        // NamedDomainObjectContainer binds the methods.
        id("grpc") { }
      }
    }
  }
}

tasks.register("compileChoral") {
    val choreographies = listOf(
        "ChorPlaceOrder",
    )

    doLast {
        choreographies.forEach { name: String ->
            val process = ProcessBuilder()
                .command(listOf(
                    "choral", "epp",
                    "--sources=${layout.projectDirectory.dir("src/main/choral")}",
                    "--headers=${layout.projectDirectory.dir("src/main/choral")}",
                    "--target=${layout.buildDirectory.dir("generated/choral").get()}",
                    name
                ))
                .redirectOutput(ProcessBuilder.Redirect.PIPE)
                .redirectError(ProcessBuilder.Redirect.PIPE)
                .directory(rootProject.projectDir)
                .start()

            process.waitFor(60, TimeUnit.SECONDS)

            if (process.exitValue() != 0) {
                val output = BufferedReader(InputStreamReader(process.inputStream)).readText()
                throw GradleException("Failed to generate choreography '$name':\n\n$output")
            }
        }
    }
}

tasks.compileJava {
    dependsOn("compileChoral")
}

sourceSets {
   main {
      java {
         srcDir(layout.buildDirectory.dir("generated/choral").get())
      }
   }
}

// Apply a specific Java toolchain to ease working on different environments.
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.named<Test>("test") {
    // Use JUnit Platform for unit tests.
    useJUnitPlatform()
}
