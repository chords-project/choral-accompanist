/*
 * This file was generated by the Gradle 'init' task.
 *
 * This generated file contains a sample Java application project to get you started.
 * For more details on building Java & JVM projects, please refer to https://docs.gradle.org/8.10.2/userguide/building_java_projects.html in the Gradle documentation.
 */

plugins {
    // Apply the application plugin to add support for building a CLI application in Java.
    application

    id("com.google.cloud.tools.jib")
}

repositories {
    // Use Maven Central for resolving dependencies.
    mavenCentral()
    mavenLocal()
}

dependencies {
    // Use JUnit Jupiter for testing.
    testImplementation(libs.junit.jupiter)

    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // This dependency is used by the application.
    implementation(libs.guava)

    implementation(project(":reactive-middleware"))
    implementation(project(":webshop-choreographies"))
}

// Apply a specific Java toolchain to ease working on different environments.
java {
    toolchain {
        //languageVersion = JavaLanguageVersion.of(24)
        languageVersion = JavaLanguageVersion.of(21)
    }
}

//jib {
//    from.image = "openjdk:24-jdk"
//    container.mainClass = "dev.chords.microservices.cartservice.Main"
//}

application {
    // Define the main class for the application.
    mainClass = "dev.chords.microservices.cartservice.Main"
}

tasks.named<Test>("test") {
    // Use JUnit Platform for unit tests.
    useJUnitPlatform()
}

tasks.register<Copy>("copyApp") {
    dependsOn("distTar")

    from(layout.buildDirectory.dir("distributions"))
    include("*.tar")
    into("../../../src/cartservice/choral-build/")
}
