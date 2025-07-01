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
    // Javalin Web
//    implementation("io.javalin:javalin:5.6.0")

//    implementation("com.squareup.okhttp3:okhttp:3.14.6")

    // Temporal SDK
    implementation("io.temporal:temporal-sdk:$temporalVersion")
    implementation("io.temporal:temporal-opentracing:$temporalVersion")
    implementation("io.temporal:temporal-testing:$temporalVersion")
    testImplementation("io.temporal:temporal-testing:$temporalVersion")

    implementation("org.slf4j:slf4j-simple:2.0.17")

    // Needed for SDK related functionality
//    implementation(platform("com.fasterxml.jackson:jackson-bom:2.15.2"))
//    implementation("com.fasterxml.jackson.core:jackson-databind")
//    implementation("com.fasterxml.jackson.core:jackson-core")

//    implementation("io.micrometer:micrometer-registry-prometheus")

//    implementation(group = "ch.qos.logback", name = "logback-classic", version = "1.4.7")
//    implementation(group = "com.jayway.jsonpath", name = "json-path", version = "2.8.0")

//    implementation(platform("io.opentelemetry:opentelemetry-bom:$otelVersion"))
//    implementation("io.opentelemetry:opentelemetry-sdk")
//    implementation("io.opentelemetry:opentelemetry-exporter-jaeger")
//    implementation("io.opentelemetry:opentelemetry-extension-trace-propagators")
//    implementation("io.opentelemetry:opentelemetry-opentracing-shim:$otelVersionAlpha")
//    implementation("io.opentelemetry:opentelemetry-semconv:$otelVersionAlpha")
//    implementation("io.jaegertracing:jaeger-client:1.8.1")

    // Used in samples
//    implementation(group = "commons-configuration", name = "commons-configuration", version = "1.10")
//    implementation(group = "io.cloudevents", name = "cloudevents-core", version = "2.5.0")
//    implementation(group = "io.cloudevents", name = "cloudevents-api", version = "2.5.0")
//    implementation(group = "io.cloudevents", name = "cloudevents-json-jackson", version = "2.5.0")
//    implementation(group = "io.serverlessworkflow", name = "serverlessworkflow-api", version = "4.0.3.Final")
//    implementation(group = "io.serverlessworkflow", name = "serverlessworkflow-validation", version = "4.0.3.Final")
//    implementation(group = "io.serverlessworkflow", name = "serverlessworkflow-spi", version = "4.0.3.Final")
//    implementation(group = "io.serverlessworkflow", name = "serverlessworkflow-util", version = "4.0.3.Final")
//    implementation(group = "net.thisptr", name = "jackson-jq", version = "1.0.0-preview.20230409")

    // we don"t update it to 2.1.0 because 2.1.0 requires Java 11
//    implementation("com.codingrodent:jackson-json-crypto:1.1.0")

//    testImplementation "junit:junit:4.13.2"
//    testImplementation "org.mockito:mockito-core:5.3.1"
//
//    testImplementation(platform("org.junit:junit-bom:5.9.3"))
//    testImplementation "org.junit.jupiter:junit-jupiter-api"
//    testRuntimeOnly "org.junit.jupiter:junit-jupiter-engine"
//    testRuntimeOnly "org.junit.vintage:junit-vintage-engine"

//    dependencies {
//        errorproneJavac("com.google.errorprone:javac:9+181-r4173-1")
//        if (JavaVersion.current().isJava11Compatible()) {
//            errorprone("com.google.errorprone:error_prone_core:2.20.0")
//        } else {
//            errorprone("com.google.errorprone:error_prone_core:2.20.0")
//        }
//    }
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

//tasks.named<JavaExec>("runWorker") {
//    dependsOn("classes")
//    mainClass = "io.temporal.samples.ordersaga.Worker"
////    classpath = sourceSets.main.runtimeClassPath
//}
//
//tasks.named<JavaExec>("runCaller") {
//    dependsOn("classes")
//    mainClass = "io.temporal.samples.ordersaga.Caller"
////    classpath = sourceSets.main.runtimeClasspath
//}

tasks.named<Test>("test") {
    // Use JUnit Platform for unit tests.
    useJUnitPlatform()
}

//task execute(type: JavaExec) {
//    mainClass = findProperty("mainClass") ? = ""
//    classpath = sourceSets.main.runtimeClasspath
//    if (project.hasProperty("arg")) {
//        args project.getProperty("arg").split("\\s+")
//    }
//}

