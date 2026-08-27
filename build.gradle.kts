import com.vanniktech.maven.publish.SonatypeHost
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.9.23"
    `java-library`
    id("org.jetbrains.dokka") version "1.9.20"
    id("io.gitlab.arturbosch.detekt") version "1.23.6"
    // Applies maven-publish and signing, and uploads to the Central Portal.
    id("com.vanniktech.maven.publish") version "0.30.0"
}

// The Maven coordinates are io.github.<github-user>, which Central verifies through
// GitHub account ownership. The Kotlin package namespace stays io.kotlinsnowflake.
group   = "io.github.deepakvijayakumar14"
version = "0.2.0"

repositories {
    mavenCentral()
}

dependencies {
    // Kotlin
    implementation(kotlin("stdlib"))
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")

    // Snowflake JDBC
    implementation("net.snowflake:snowflake-jdbc:3.16.0")

    // Connection pooling
    implementation("com.zaxxer:HikariCP:5.1.0")

    // Logging
    implementation("org.slf4j:slf4j-api:2.0.12")

    // Test
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0")
    testImplementation("io.mockk:mockk:1.13.10")
    testImplementation("ch.qos.logback:logback-classic:1.5.3")
    testImplementation("io.kotest:kotest-runner-junit5:5.8.1")
    testImplementation("io.kotest:kotest-assertions-core:5.8.1")
}

tasks.withType<KotlinCompile> {
    kotlinOptions {
        jvmTarget        = "17"
        freeCompilerArgs = listOf("-Xjsr305=strict", "-opt-in=kotlin.RequiresOptIn")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

java {
    // The sources and javadoc jars come from the publish plugin, which builds the
    // javadoc one from Dokka rather than the empty Java javadoc task.
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// -- Integration tests ------------------------------------------------------------
// Live tests against a real Snowflake account, in src/integrationTest/kotlin.
// Deliberately excluded from `check` so `./gradlew build` stays offline; run them
// explicitly with `./gradlew integrationTest` and SNOWFLAKE_* set in the environment.

val integrationTest: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}

configurations["integrationTestImplementation"]
    .extendsFrom(configurations.testImplementation.get())
configurations["integrationTestRuntimeOnly"]
    .extendsFrom(configurations.testRuntimeOnly.get())

tasks.register<Test>("integrationTest") {
    group       = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Runs integration tests against a live Snowflake account."

    testClassesDirs = integrationTest.output.classesDirs
    classpath       = integrationTest.runtimeClasspath
    shouldRunAfter(tasks.test)
}

// -- Publishing (Maven Central) ---------------------------------------------------
//
// Uploads to the Central Portal at central.sonatype.com. The old OSSRH host
// (s01.oss.sonatype.org) was decommissioned and no longer resolves.
//
// Credentials and the signing key are read from Gradle properties or the matching
// environment variables, so nothing secret lives in this file:
//
//   ORG_GRADLE_PROJECT_mavenCentralUsername   Portal user token name
//   ORG_GRADLE_PROJECT_mavenCentralPassword   Portal user token password
//   ORG_GRADLE_PROJECT_signingInMemoryKey     ASCII-armoured GPG private key
//   ORG_GRADLE_PROJECT_signingInMemoryKeyPassword
//
// Publish with: ./gradlew publishToMavenCentral

mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)

    // Central rejects unsigned artifacts, but signing every local build would make a
    // GPG key a prerequisite for `publishToMavenLocal` and for CI. Sign only when a
    // key is actually configured.
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }

    coordinates(group.toString(), "kotlin-snowflake", version.toString())

    pom {
        name.set("kotlin-snowflake")
        description.set("Coroutine-native Kotlin client for Snowflake with idiomatic query DSL")
        url.set("https://github.com/deepakvijayakumar14/kotlin-snowflake")
        inceptionYear.set("2026")

        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
            }
        }

        developers {
            developer {
                id.set("deepakvijayakumar14")
                name.set("Deepak Vijayakumar")
                url.set("https://github.com/deepakvijayakumar14")
            }
        }

        scm {
            connection.set("scm:git:git://github.com/deepakvijayakumar14/kotlin-snowflake.git")
            developerConnection.set("scm:git:ssh://github.com/deepakvijayakumar14/kotlin-snowflake.git")
            url.set("https://github.com/deepakvijayakumar14/kotlin-snowflake")
        }
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("$rootDir/config/detekt.yml"))
}
