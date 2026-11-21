// Notes' model and rules: Drive browsing, the encrypted Markdown body, the offline cache and its
// sync, version history, tags, sharing, the wiki-link graph and the Markdown parser the preview
// draws from. Plain Kotlin/JVM, so all of it is tested on the JVM in seconds against the shared
// library's in-memory server.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api("com.neutrino.shared:neutrino-core")
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    api(libs.okhttp)
    // CommonMark + GFM tables, strikethrough and task lists — what iOS gets from swift-markdown's
    // cmark-gfm and the web from its own parser.
    implementation(libs.commonmark)
    implementation(libs.commonmark.gfm.tables)
    implementation(libs.commonmark.gfm.strikethrough)
    implementation(libs.commonmark.task.list.items)

    testImplementation(testFixtures("com.neutrino.shared:neutrino-core"))
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    systemProperty("user.timezone", "Europe/Berlin")
    systemProperty("user.language", "en")
    systemProperty("user.country", "US")
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
