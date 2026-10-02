// The Spring-free wire libraries: the device protocols, adapters.net, SSDP's wire code and the ICS parser. With
// nothing but core and these libraries on its classpath, the compiler keeps Spring and the rest of the application
// out (docs/dev/architecture.md#modules). Its test fixtures hold the fakes and recordings the app's tests share.
plugins {
    `java-library`
    `java-test-fixtures`
    id("com.google.protobuf")
}

base {
    archivesName = "home-control-protocols"
}

dependencies {
    api(project(":core"))
    // Versions for everything below; the Jackson BOM raises Jackson above the version Spring Boot manages
    // (gradle/libs.versions.toml says why). It comes first: met second, Spring Boot's lower Jackson would be
    // resolved before being raised, and its metadata is not among the verified checksums.
    api(platform(libs.jackson.bom))
    api(platform(libs.spring.boot.dependencies))
    // Parsers take and return Jackson trees, and the app maps keys to the generated protobuf classes: both are part
    // of this module's API.
    api("tools.jackson.core:jackson-databind")
    api(libs.protobuf.java)
    implementation(libs.bouncycastle.bcpkix)
    implementation("org.slf4j:slf4j-api")

    // TestTls signs the fakes' self-signed certificates.
    testFixturesImplementation(libs.bouncycastle.bcpkix)

    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.awaitility:awaitility")
    // OutputCaptureExtension, for a test that checks what a protocol logs; Logback writes that log.
    testImplementation("org.springframework.boot:spring-boot-test")
    testRuntimeOnly("ch.qos.logback:logback-classic")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

protobuf {
    protoc { artifact = libs.protoc.get().toString() }
}
