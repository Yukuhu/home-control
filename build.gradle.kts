// Raises commons-lang3 and Jackson on the build classpath above the versions the Spring Boot plugin
// drags in; gradle/libs.versions.toml says why. A plugin's transitive dependencies can only be
// constrained here.
buildscript {
    dependencies {
        classpath(platform(libs.jackson.bom))
        constraints {
            classpath(libs.commons.lang3)
        }
    }
}

plugins {
    java
    jacoco
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.protobuf)
    alias(libs.plugins.sonarqube)
    alias(libs.plugins.test.retry)
}

group = "dev.andre"
// CI passes the version computed from conventional commits; local builds get an
// honest SNAPSHOT rather than claiming to be a release.
version = (findProperty("releaseVersion") as String? ?: "0.0.0-SNAPSHOT")

java {
    toolchain { languageVersion = JavaLanguageVersion.of(25) }
}

repositories { mavenCentral() }

jacoco {
    toolVersion = "0.8.15"
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required = true
    }
}

tasks.test {
    finalizedBy(tasks.jacocoTestReport)
    // ArchitectureTest compares the code with the committed store of frozen violations: a changed store must re-run it.
    inputs.dir("src/test/archunit-store").withPropertyName("archunitStore").withPathSensitivity(PathSensitivity.RELATIVE)
}

tasks.named("sonar") {
    dependsOn(tasks.jacocoTestReport)
}

sonar {
    properties {
        property("sonar.projectKey", "Yukuhu_home-control")
        property("sonar.organization", "yukuhu")
        property("sonar.gradle.scanAll", "true")
        property("sonar.javascript.lcov.reportPaths",
            "build/reports/browser-coverage/lcov.info,build/reports/pr-summary/lcov.info")
    }
}

dependencies {
    implementation(platform(libs.spring.boot.dependencies))
    // Raises Jackson above the version Spring Boot manages; gradle/libs.versions.toml says why.
    implementation(platform(libs.jackson.bom))
    // Raises Tomcat above the version Spring Boot manages; gradle/libs.versions.toml says why.
    constraints {
        implementation(libs.tomcat.embed.core)
        implementation(libs.tomcat.embed.el)
        implementation(libs.tomcat.embed.websocket)
    }
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.apache.httpcomponents.client5:httpclient5")
    implementation(libs.protobuf.java)
    implementation(libs.jmdns)
    implementation(libs.bouncycastle.bcpkix)
    // Argon2id for the login hash and the HOME_CONTROL_SECRET key (already transitive via bcpkix; used directly now).
    implementation(libs.bouncycastle.bcprov)
    // Bluetooth speakers (optional module, off by default). Only adapters/bluetooth/bluez/DbusBluezClient imports these.
    implementation(libs.bluez.dbus)
    implementation(libs.dbus.java.core)
    implementation(libs.dbus.java.unixsocket)

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-webmvc-test")
    // Package rules checked on every build (src/test/java/dev/andre/homecontrol/ArchitectureTest.java).
    testImplementation(libs.archunit.junit5)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Child-JVM tests (class loading, fake mpv) start java with exactly the test runtime classpath.
tasks.named<Test>("test") {
    systemProperty("home-control.test.runtime-classpath", sourceSets["test"].runtimeClasspath.asPath)
    // dbus-java reads a machine id for every connection, even to the D-Bus tests' own socket, and a build container's
    // /etc/machine-id can be empty: the tests bring their own.
    environment("DBUS_MACHINE_ID_LOCATION", file("src/test/resources/dbus/machine-id").absolutePath)
    // Most of the suite waits on sockets and timeouts rather than computing, so test classes run
    // in several JVMs at once. Capped at four, which is what a CI runner has.
    maxParallelForks = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
    // The deployment tests read these files from the repository rather than the classpath. As
    // inputs, a change to one of them alone runs the tests again instead of reusing a result.
    inputs.files(
        ".github/workflows/ci.yml",
        ".github/workflows/dependency-checksums.yml",
        ".github/actions/smoke-image/action.yml",
        "Dockerfile",
        "Dockerfile.dist",
        "compose.yaml",
        "compose.bluetooth.yaml",
        "casaos/docker-compose.yml",
        "casaos/docker-compose.bluetooth.yml",
        "docs/user/bluetooth-speakers.md",
        "docs/bluetooth-speakers.md",
    ).withPropertyName("deploymentFiles").withPathSensitivity(PathSensitivity.RELATIVE)
}

protobuf {
    protoc { artifact = libs.protoc.get().toString() }
}

tasks.withType<Test> {
    useJUnitPlatform()
    testLogging { showExceptions = true }
}

// Browser tests (Playwright for Java) live in their own source set so `build` never resolves
// Playwright (~200 MB driver bundle) and never needs installed browsers. Run: ./gradlew e2eTest

sourceSets {
    create("e2e") {
        compileClasspath += sourceSets["main"].output + sourceSets["test"].output
        runtimeClasspath += sourceSets["main"].output + sourceSets["test"].output
    }
}

configurations["e2eImplementation"].extendsFrom(configurations["testImplementation"])
configurations["e2eRuntimeOnly"].extendsFrom(configurations["testRuntimeOnly"])

dependencies {
    "e2eImplementation"(libs.playwright) {
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
    systemProperty("e2e.browsers", (findProperty("e2eBrowsers") as String?) ?: "chromium,firefox,webkit")
    systemProperty("e2e.artifacts", layout.buildDirectory.dir("e2e-artifacts").get().asFile.absolutePath)
    val browserCoverage = providers.gradleProperty("e2eCoverage").map(String::toBoolean).orElse(false)
    val browserCoverageDirectory = layout.buildDirectory.dir("coverage/browser-raw")
    inputs.property("browserCoverage", browserCoverage)
    systemProperty("e2e.coverage", browserCoverage.get().toString())
    systemProperty("e2e.coverage.dir", browserCoverageDirectory.get().asFile.absolutePath)
    if (browserCoverage.get()) {
        outputs.dir(browserCoverageDirectory)
        doFirst { delete(browserCoverageDirectory) }
    }
    maxParallelForks = 1
    // A browser test that fails is run once more, and passes the build if it then passes. The
    // merged report marks it as flaky, which the pull request summary shows, so a retry is never
    // silent. More than three failures is a real breakage, which retrying would only slow down.
    retry {
        maxRetries = 1
        maxFailures = 3
    }
    reports.junitXml.mergeReruns = true
    // Never taken from the build cache: the result depends on the installed browsers, which are
    // not an input Gradle tracks, and a cached report would show an old retry as if it just happened.
    outputs.cacheIf { false }
}

val installPlaywrightBrowsers by tasks.registering(JavaExec::class) {
    description = "Installs Playwright's Chromium, Firefox and WebKit plus their OS packages (needs root or passwordless sudo)." +
        " -Pe2eBrowsers=chromium installs one of them."
    group = "verification"
    classpath = configurations["e2eRuntimeClasspath"]
    mainClass = "com.microsoft.playwright.CLI"
    args(listOf("install", "--with-deps") + ((findProperty("e2eBrowsers") as String?) ?: "chromium,firefox,webkit").split(","))
}

// Resolve artifacts before CI fans out into builds and browser tests. Task inputs force
// verification of every resolvable configuration without compiling or executing tests.
tasks.register("verifyDependencyChecksums") {
    description = "Verifies dependency checksums across all configurations without compiling."
    group = "verification"
    inputs.files(configurations.filter { it.isCanBeResolved })
    doLast { logger.lifecycle("Dependency checksums verified.") }
}
