// The PC side of comics (CLAUDE.md 12, "PC comic tool"): crops, cleans up and shrinks pages and
// finds their panels, run by scripts/send-comics.ps1. Plain Kotlin on the JVM, sharing the
// app's own page-analysis code in tool/.../comics (everything but ComicDetails.kt).
// Build with:  .\gradlew.bat :comic-tools:installComicTools   (writes scripts/comic-tools/comic-tools.jar)
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
    sourceSets {
        main {
            kotlin.srcDir("../tool/src/main/kotlin/com/thelightphone/reader/comics")
            kotlin.exclude("**/ComicDetails.kt")
        }
    }
}

dependencies {
    testImplementation(libs.kotlin.test)
}

tasks.test {
    useJUnitPlatform()
}

// One jar holding the tool and the Kotlin library, so the send script needs only Java.
tasks.jar {
    manifest { attributes["Main-Class"] = "com.thelightphone.reader.pc.MainKt" }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.map { classpath -> classpath.map { if (it.isDirectory) it else zipTree(it) } })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/**")
}

tasks.register<Copy>("installComicTools") {
    from(tasks.jar)
    into(rootProject.file("scripts/comic-tools"))
    rename { "comic-tools.jar" }
}
