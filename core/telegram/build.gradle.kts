import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.dagger.hilt.android)
    alias(libs.plugins.ksp)
}

val localProperties = Properties().apply {
    val propFile = rootProject.file("local.properties")
    if (propFile.exists()) {
        propFile.inputStream().use { load(it) }
    }
}

fun resolveBuildSecret(vararg names: String): String? =
    names.firstNotNullOfOrNull { name ->
        (project.findProperty(name) as? String)?.takeIf { it.isNotBlank() }
            ?: localProperties.getProperty(name)?.takeIf { it.isNotBlank() }
            ?: System.getenv(name)?.takeIf { it.isNotBlank() }
    }

val resolvedTgApiId: Int = resolveBuildSecret("TG_API_ID", "TELEGRAM_API_ID")
    ?.toIntOrNull()
    ?: 0
val resolvedTgApiHash: String = resolveBuildSecret("TG_API_HASH", "TELEGRAM_API_HASH")
    .orEmpty()
    .replace("\"", "\\\"")
val resolvedAppVersionName: String =
    (project.findProperty("APP_VERSION_NAME") as? String)?.takeIf { it.isNotBlank() }
        ?: "0.0.1"

android {
    namespace = "com.hitarashi.multibox.core.telegram"
    compileSdk = 37

    defaultConfig {
        minSdk = 30
        consumerProguardFiles("consumer-rules.pro")

        buildConfigField("int", "TG_API_ID", resolvedTgApiId.toString())
        buildConfigField("String", "TG_API_HASH", "\"$resolvedTgApiHash\"")
        buildConfigField("String", "APP_VERSION_NAME", "\"$resolvedAppVersionName\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all { it.useJUnitPlatform() }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

dependencies {
    api(project(":core:model"))

    implementation(libs.tdlib)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.timber)

    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.junit.jupiter.params)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testImplementation(libs.junit)
    testRuntimeOnly(libs.junit.vintage.engine)
    testRuntimeOnly(libs.junitplatformlauncher)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.truth)
    testImplementation(kotlin("test"))
}
