import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/*
 * This file was generated by the Gradle 'init' task.
 *
 * This generated file contains a sample Java application project to get you started.
 * For more details on building Java & JVM projects, please refer to https://docs.gradle.org/8.10.2/userguide/building_java_projects.html in the Gradle documentation.
 */

plugins {
    // Apply the application plugin to add support for building a CLI application in Java.
    application

    id("com.google.protobuf") version "0.9.4"
    id("com.google.cloud.tools.jib") version "3.4.4"
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    // Use Maven Central for resolving dependencies.
    mavenCentral()

    // Choral can either be installed locally...
    mavenLocal()

    // ...or from the GitHub maven package repository
    val githubUsername = rootProject.findProperty("gpr.user") as String? ?: System.getenv("USERNAME")
    val githubToken = rootProject.findProperty("gpr.key") as String? ?: System.getenv("TOKEN")
    if (githubUsername != null && githubToken != null) {
        maven {
            url = uri("https://maven.pkg.github.com/choral-lang/choral")
            credentials {
                username = githubUsername
                password = githubToken
            }
        }
    }
}

var grpcVersion = "1.68.1"

dependencies {
    // Use JUnit Jupiter for testing.
    testImplementation(libs.junit.jupiter)

    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    implementation(project(":reactive-middleware"))

    implementation("eu.rekawek.toxiproxy:toxiproxy-java:2.1.7")

    // gRPC
    runtimeOnly("io.grpc:grpc-netty-shaded:${grpcVersion}")
    implementation("io.grpc:grpc-protobuf:${grpcVersion}")
    implementation("io.grpc:grpc-stub:${grpcVersion}")
    compileOnly("org.apache.tomcat:annotations-api:6.0.53")
}

application {
    // Define the main class for the application.
    mainClass = "dev.chords.microservices.benchmark.Benchmark"
}

jib {
    to.image = "accompanist-benchmark"
    container.mainClass = "dev.chords.microservices.benchmark.Benchmark"
}

tasks.register<JavaExec>("runA") {
    dependsOn("classes")
    mainClass = "dev.chords.microservices.benchmark.ServiceA"
    classpath = sourceSets.main.get().runtimeClasspath
}

tasks.register<JavaExec>("runB") {
    dependsOn("classes")
    mainClass = "dev.chords.microservices.benchmark.ServiceB"
    classpath = sourceSets.main.get().runtimeClasspath
}

tasks.named<Test>("test") {
    // Use JUnit Platform for unit tests.
    useJUnitPlatform()
}

// Compile choral code
tasks.register("compileChoral") {
    val choreographies = listOf(
        "SimpleChoreography",
        "GreeterChoreography",
        "ChainChoreography1",
        "ChainChoreography3",
        "ChainChoreography5",
    )

    doLast {
        choreographies.forEach { name: String ->
            val process = ProcessBuilder()
                .command(
                    listOf(
                        "choral", "epp",
                        "--sources=${layout.projectDirectory.dir("src/main/choral")}",
                        "--headers=${layout.projectDirectory.dir("src/main/choral")}",
                        "--target=${layout.buildDirectory.dir("generated/choral").get()}",
                        name
                    )
                )
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

// Compile protobuf code
protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.25.5"
    }
    plugins {
        create("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:1.68.1"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                create("grpc") {}
            }
        }
    }
}
