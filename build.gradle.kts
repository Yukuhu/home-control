import net.ltgt.gradle.errorprone.errorprone

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
    `jacoco-report-aggregation`
    alias(libs.plugins.spring.boot)
    // Applied by protocols; declared here so that it loads with the build classpath's raised versions.
    alias(libs.plugins.protobuf) apply false
    alias(libs.plugins.sonarqube)
    alias(libs.plugins.test.retry)
    alias(libs.plugins.errorprone)
}

// Read here, in the root project: inside allprojects {}, `libs` would be looked up on a module that has none yet.
val jacocoVersion = libs.versions.jacoco.get()
val errorproneCore = libs.errorprone.core

// One coverage report for the whole build: the app's tests exercise the modules' code too, so a report per module
// would count only each module's own tests. SonarCloud reads it for every module.
val combinedCoverageReport =
    layout.buildDirectory.file("reports/jacoco/testCodeCoverageReport/testCodeCoverageReport.xml")

// What every Java project of the build shares: the root project, which is the app, and the modules beside it
// (docs/dev/architecture.md#modules).
allprojects {
    apply(plugin = "java")
    apply(plugin = "jacoco")
    apply(plugin = "net.ltgt.errorprone")

    group = "dev.andre"
    // CI passes the version computed from conventional commits; local builds get an
    // honest SNAPSHOT rather than claiming to be a release.
    version = (findProperty("releaseVersion") as String? ?: "0.0.0-SNAPSHOT")

    configure<JavaPluginExtension> {
        toolchain { languageVersion = JavaLanguageVersion.of(25) }
    }

    repositories { mavenCentral() }

    configure<JacocoPluginExtension> {
        toolVersion = jacocoVersion
    }

    // Error Prone checks the code as it compiles: a check at error level fails the build. Its warnings stay off for
    // now; a check worth having can be raised to an error by name. Generated protobuf code is not ours to fix.
    dependencies { "errorprone"(errorproneCore) }
    tasks.withType<JavaCompile>().configureEach {
        options.errorprone {
            disableAllWarnings = true
            disableWarningsInGeneratedCode = true
            excludedPaths = ".*/build/generated/.*"
        }
    }

    tasks.withType<JacocoReport>().configureEach {
        reports {
            xml.required = true
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging { showExceptions = true }
    }

    sonar {
        properties {
            property("sonar.coverage.jacoco.xmlReportPaths", combinedCoverageReport.get().asFile.absolutePath)
        }
    }

    // Resolve artifacts before CI fans out into builds and browser tests. Task inputs force
    // verification of every resolvable configuration without compiling or executing tests. The
    // build's own projects are left out of the files: their jars would need compiling first.
    tasks.register("verifyDependencyChecksums") {
        description = "Verifies dependency checksums across all configurations without compiling."
        group = "verification"
        inputs.files(configurations.filter { it.isCanBeResolved }.map { configuration ->
            configuration.incoming.artifactView {
                componentFilter { it !is ProjectComponentIdentifier }
            }.files
        })
        doLast { logger.lifecycle("Dependency checksums verified.") }
    }
}

// What only the modules need: Spring Boot's plugin gives the app's compiler -parameters, which the modules get the
// same way here. `test --tests` runs its filter in every project: a module without a match passes, and the app's
// `test` still fails when nothing matches, so a filter that matches no test anywhere fails the build. A module's
// test task asked for by its path, such as `:core:test --tests …`, fails when nothing matches, as the app's does.
subprojects {
    tasks.withType<JavaCompile>().configureEach {
        options.compilerArgs.add("-parameters")
    }
    tasks.withType<Test>().configureEach {
        filter.isFailOnNoMatchingTests = gradle.startParameter.taskNames.any { it == path || ":$it" == path }
    }
}

tasks.check {
    dependsOn(tasks.named("testCodeCoverageReport"))
}

tasks.named("sonar") {
    dependsOn(tasks.named("testCodeCoverageReport"))
}

sonar {
    properties {
        property("sonar.projectKey", "Yukuhu_home-control")
        property("sonar.organization", "yukuhu")
        property("sonar.gradle.scanAll", "true")
        property("sonar.javascript.lcov.reportPaths",
            "build/reports/browser-coverage/lcov.info,build/reports/pr-summary/lcov.info")
        // The Dockerfile names each base image by tag and digest: the digest pins it, and the tag is how Dependabot
        // knows which newer digest to propose. docker:S8431 asks for one of the two, which would stop the updates.
        property("sonar.issue.ignore.multicriteria", "tagAndDigest")
        property("sonar.issue.ignore.multicriteria.tagAndDigest.ruleKey", "docker:S8431")
        property("sonar.issue.ignore.multicriteria.tagAndDigest.resourceKey", "**/Dockerfile")
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
    implementation(project(":core"))
    implementation(project(":protocols"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.apache.httpcomponents.client5:httpclient5")
    implementation(libs.ph.css)
    implementation(libs.jmdns)
    // Argon2id for the login hash and the HOME_CONTROL_SECRET key.
    implementation(libs.bouncycastle.bcprov)
    // Bluetooth speakers (optional module, off by default). Only adapters/bluetooth/bluez/DbusBluezClient imports these.
    implementation(libs.bluez.dbus)
    implementation(libs.dbus.java.core)
    implementation(libs.dbus.java.unixsocket)

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-webmvc-test")
    // The fakes and recordings the protocol tests share with the app's tests and browser tests.
    testImplementation(testFixtures(project(":protocols")))
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
        "compose.yaml",
        "compose.bluetooth.yaml",
        "casaos/docker-compose.yml",
        "casaos/docker-compose.bluetooth.yml",
        "docs/user/bluetooth-speakers.md",
        "docs/bluetooth-speakers.md",
    ).withPropertyName("deploymentFiles").withPathSensitivity(PathSensitivity.RELATIVE)
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
        // The action holds the directory's file, not the build script, which the configuration cache cannot keep.
        val rawCoverage = browserCoverageDirectory.get().asFile
        doFirst { rawCoverage.deleteRecursively() }
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
