import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.20.0"
}

group = "com.zusby.zkplugin"
version = "1.2.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

java {
    // IntelliJ 2026.3 platform classes are compiled for Java 25 (class file 69),
    // so the compiler must be JDK 25. Emit Java 21 bytecode to stay loadable on 2026.1/2026.2.
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
    options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:removal"))
}

dependencies {
    intellijPlatform {
        // Since 2025.3 IntelliJ IDEA is a single unified distribution (no separate IC/IU)
        intellijIdea("2026.3")
        bundledPlugin("com.intellij.java")
        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.Plugin.Java)
    }
    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    buildSearchableOptions = false
    // No GUI forms; skipping bytecode instrumentation also avoids its JDK-layout lookup failing on some JDKs.
    instrumentCode = false

    pluginConfiguration {
        ideaVersion {
            sinceBuild = "261"
            untilBuild = provider { null }
        }
    }

    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }

    pluginVerification {
        ides {
            create(IntelliJPlatformType.IntellijIdea, "2026.1")
            create(IntelliJPlatformType.IntellijIdea, "2026.3")
        }
    }
}

tasks.test {
    // Forward -Dzk.perf=true to the test JVM to run ZkPerformanceTest
    systemProperty("zk.perf", System.getProperty("zk.perf") ?: "false")
    maxHeapSize = "3g"
}
