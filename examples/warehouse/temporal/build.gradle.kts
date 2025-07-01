plugins {
    // Apply the application plugin to add support for building a CLI application in Java.
    application
}

val temporalVersion = "1.30.1"
val otelVersion = "1.26.0"
val otelVersionAlpha = "${otelVersion}-alpha"

repositories {
    // Use Maven Central for resolving dependencies.
    mavenCentral()

    // Choral can either be installed locally...
    mavenLocal()

    // ...or from the GitHub maven package repository
    val githubUsername = project.findProperty("gpr.user") as String? ?: System.getenv("USERNAME")
    val githubToken = project.findProperty("gpr.key") as String? ?: System.getenv("TOKEN")
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

dependencies {
    // Temporal SDK
    implementation("io.temporal:temporal-sdk:$temporalVersion")
    implementation("io.temporal:temporal-opentracing:$temporalVersion")
    implementation("io.temporal:temporal-testing:$temporalVersion")
    testImplementation("io.temporal:temporal-testing:$temporalVersion")

    implementation("org.slf4j:slf4j-simple:2.0.17")

    // Postgres
    implementation("com.zaxxer:HikariCP:6.3.0")
    implementation("org.postgresql:postgresql:42.7.5")
}

// Apply a specific Java toolchain to ease working on different environments.
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

application {
    // Define the main class for the application.
    mainClass = "io.temporal.samples.ordersaga.Worker"
}

tasks.named<Test>("test") {
    // Use JUnit Platform for unit tests.
    useJUnitPlatform()
}
