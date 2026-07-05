plugins {
    application
}

group = "io.github.ddmfuhrmann.kindexer"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    // AST + symbol resolution (call graph). No regex for code structure.
    implementation("com.github.javaparser:javaparser-symbol-solver-core:3.28.2")
    // Canonical JSON (manifest, cache, enrichment I/O).
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.0")
    // The optional `sdk` enrichment provider talks to the Anthropic Messages API through the
    // JDK's built-in java.net.http client (ANTHROPIC_API_KEY) — no third-party SDK dependency,
    // so the build stays light and offline-buildable. The default `agent` provider needs no key.
}

application {
    mainClass = "io.github.ddmfuhrmann.kindexer.Cli"
}

// Silence the Gradle application plugin's default distribution noise; the .sh wrapper
// runs `installDist` and execs build/install/knowledge-index/bin/knowledge-index.
tasks.named<Test>("test") { enabled = false }
