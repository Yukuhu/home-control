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
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.apache.httpcomponents.client5:httpclient5")
    implementation("com.google.protobuf:protobuf-java:4.36.1")
    implementation("org.jmdns:jmdns:3.6.3")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.86")
    // Argon2id for the login hash and the HOME_CONTROL_SECRET key (already transitive via bcpkix; used directly now).
    implementation("org.bouncycastle:bcprov-jdk18on:1.86")
    // Bluetooth speakers (optional module, off by default). Only adapters/bluetooth/bluez/DbusBluezClient imports these.
    implementation("com.github.hypfvieh:bluez-dbus:0.3.5")
    implementation("com.github.hypfvieh:dbus-java-core:5.2.1")
    implementation("com.github.hypfvieh:dbus-java-transport-native-unixsocket:5.2.1")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-webmvc-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Child-JVM tests (class loading, fake mpv) start java with exactly the test runtime classpath.
tasks.named<Test>("test") {
    systemProperty("home-control.test.runtime-classpath", sourceSets["test"].runtimeClasspath.asPath)
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:4.36.1" }
}

tasks.withType<Test> {
    useJUnitPlatform()
    testLogging { showExceptions = true }
}

// Browser tests (Playwright for Java) live in their own source set so `build` never resolves
// Playwright (~200 MB driver bundle) and never needs installed browsers. Run: ./gradlew e2eTest
val playwrightVersion = "1.63.0"

sourceSets {
    create("e2e") {
        compileClasspath += sourceSets["main"].output + sourceSets["test"].output
        runtimeClasspath += sourceSets["main"].output + sourceSets["test"].output
    }
}

configurations["e2eImplementation"].extendsFrom(configurations["testImplementation"])
configurations["e2eRuntimeOnly"].extendsFrom(configurations["testRuntimeOnly"])

dependencies {
    "e2eImplementation"("com.microsoft.playwright:playwright:$playwrightVersion") {
        // Spring Boot's Logback is the SLF4J provider; two providers only produce warnings.
        exclude(group = "org.slf4j", module = "slf4j-simple")
    }
}

val e2eTest by tasks.registering(Test::class) {
    description = "Runs the Playwright browser tests (needs installed browsers, see installPlaywrightBrowsers)."
    group = "verification"
    testClassesDirs = sourceSets["e2e"].output.classesDirs
    classpath = sourceSets["e2e"].runtimeClasspath
    shouldRunAfter(tasks.test)
    // Fail fast with a clear error instead of downloading browsers in the middle of a test run.
    environment("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")
    systemProperty("e2e.browsers", (findProperty("e2eBrowsers") as String?) ?: "chromium,webkit")
    systemProperty("e2e.artifacts", layout.buildDirectory.dir("e2e-artifacts").get().asFile.absolutePath)
    maxParallelForks = 1
}

val installPlaywrightBrowsers by tasks.registering(JavaExec::class) {
    description = "Installs Playwright's Chromium and WebKit plus their OS packages (needs root or passwordless sudo)."
    group = "verification"
    classpath = configurations["e2eRuntimeClasspath"]
    mainClass = "com.microsoft.playwright.CLI"
    args("install", "--with-deps", "chromium", "webkit")
}
