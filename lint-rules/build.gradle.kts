plugins {
    alias(libs.plugins.kotlin.jvm)
}

group = "com.thelightphone"
version = "1.0-SNAPSHOT"

val lintVersion = "32.4.0"

dependencies {
    compileOnly("com.android.tools.lint:lint-api:$lintVersion")
    compileOnly("com.android.tools.lint:lint-checks:$lintVersion")
}

kotlin {
    jvmToolchain((rootProject.ext["jvmTarget"] as String).toInt())
}
