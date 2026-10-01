// The domain model every other part of the application builds on. It depends on the JDK alone: with nothing else on
// its classpath, the compiler keeps Spring and the rest of the application out (docs/dev/architecture.md#modules).
plugins {
    `java-library`
}

base {
    archivesName = "home-control-core"
}

dependencies {
    testImplementation(platform(libs.spring.boot.dependencies))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
