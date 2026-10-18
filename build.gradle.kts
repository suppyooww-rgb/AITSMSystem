val kotlinVersion = providers.gradleProperty("kotlin_version").get()
val logbackVersion = providers.gradleProperty("logback_version").get()

plugins {
    kotlin("jvm") version "2.4.21"
    id("io.ktor.plugin") version "3.4.0"
    kotlin("plugin.serialization") version "2.4.21"
    id("org.owasp.dependencycheck") version "13.0.0"
}

dependencyCheck {
    failBuildOnCVSS = 9.0f
    scanBuildEnv = false
    scanConfigurations = listOf("compileClasspath", "runtimeClasspath")
    suppressionFile = "config/dependency-check-suppressions.xml"
    System.getenv("NVD_API_KEY")?.takeIf { it.isNotBlank() }?.let { nvd.apiKey = it }
}

group = "com.aitsm"
version = "0.0.1"

application {
    mainClass = "io.ktor.server.netty.EngineMain"
}

tasks.processResources {
    from("db/migrations") { into("db/migrations") }
    from("db/schema.sql") { into("db") }
}

dependencyLocking {
    lockAllConfigurations()
}


dependencies {
    implementation(enforcedPlatform("org.jetbrains.kotlin:kotlin-bom:$kotlinVersion"))
    implementation(enforcedPlatform("io.netty:netty-bom:4.2.19.Final"))
    implementation("io.ktor:ktor-server-core-jvm")
    implementation("io.ktor:ktor-server-netty-jvm")
    implementation("io.ktor:ktor-server-config-yaml-jvm")
    implementation("io.ktor:ktor-server-content-negotiation-jvm")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm")
    implementation("io.ktor:ktor-server-call-logging-jvm")
    implementation("io.ktor:ktor-server-cors-jvm")
    implementation("io.ktor:ktor-server-default-headers-jvm")
    implementation("io.ktor:ktor-server-status-pages-jvm")

    implementation("org.jetbrains.exposed:exposed-core:0.52.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.52.0")
    implementation("org.jetbrains.exposed:exposed-java-time:0.52.0")
    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("org.postgresql:postgresql:42.7.14")
    implementation("com.google.cloud.sql:postgres-socket-factory:1.29.0")

    implementation("org.mindrot:jbcrypt:0.4")
    implementation("jakarta.mail:jakarta.mail-api:2.1.3")
    runtimeOnly("org.eclipse.angus:angus-mail:2.0.4")
    implementation("com.google.zxing:core:3.5.4")
    implementation("com.google.zxing:javase:3.5.4")
    implementation("ch.qos.logback:logback-classic:$logbackVersion")

    testImplementation("io.ktor:ktor-server-test-host-jvm")
    testImplementation("org.apache.httpcomponents.client5:httpclient5:5.6.3")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:$kotlinVersion")
}
