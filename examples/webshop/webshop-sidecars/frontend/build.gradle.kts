/*
 * This file was generated by the Gradle 'init' task.
 *
 * This generated file contains a sample Java application project to get you started.
 * For more details on building Java & JVM projects, please refer to https://docs.gradle.org/8.10.2/userguide/building_java_projects.html in the Gradle documentation.
 */

plugins {
    java
    id("org.springframework.boot") version "3.3.5"
    id("io.spring.dependency-management") version "1.1.6"

    id("com.google.cloud.tools.jib")
}

group = "dev.chords"
version = "0.1.0"

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

    // Spring
    implementation("org.springframework.boot:spring-boot-starter-web")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Apply a specific Java toolchain to ease working on different environments.
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

jib {
    //from.image = "openjdk:24-jdk"
    //container.mainClass = "dev.chords.microservices.frontend.FrontendApplication"
}

tasks.named<Test>("test") {
    // Use JUnit Platform for unit tests.
    useJUnitPlatform()
}

tasks.register<Copy>("copyApp") {
    dependsOn("bootJar")

    from(layout.buildDirectory.dir("libs"))
    include("frontend-0.1.0.jar")
    into("../../../src/frontend/choral-build/")
}
