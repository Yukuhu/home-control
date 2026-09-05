plugins {
    java
    id("org.springframework.boot") version "4.1.1"
    id("com.google.protobuf") version "0.10.0"
}

group = "dev.andre"
// CI passes the version computed from conventional commits; local builds get an
// honest SNAPSHOT rather than claiming to be a release.
version = (findProperty("releaseVersion") as String? ?: "0.0.0-SNAPSHOT")

java {
    toolchain { languageVersion = JavaLanguageVersion.of(25) }
}

repositories { mavenCentral() }

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.1"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("com.google.protobuf:protobuf-java:4.36.1")
    implementation("org.jmdns:jmdns:3.6.3")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.85")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-webmvc-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:4.36.1" }
}

tasks.withType<Test> {
    useJUnitPlatform()
    testLogging { showExceptions = true }
}
