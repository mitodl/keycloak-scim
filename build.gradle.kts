plugins {
    java
    alias(libs.plugins.shadow)
    alias(libs.plugins.openrewrite)
}

group = "sh.libre.scim"
version = "1.0-SNAPSHOT"
description = "keycloak-scim"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

rewrite {
    activeRecipe(
        "org.openrewrite.staticanalysis.CodeCleanup",
        "org.openrewrite.staticanalysis.JavaApiBestPractices",
    )
    activeStyle("org.openrewrite.java.IntelliJ")
}

repositories {
    mavenCentral()
}

val integrationTest by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}

configurations {
    testImplementation {
        extendsFrom(configurations.compileOnly.get())
    }
    named(integrationTest.implementationConfigurationName) {
        extendsFrom(configurations.testImplementation.get())
    }
    named(integrationTest.runtimeOnlyConfigurationName) {
        extendsFrom(configurations.testRuntimeOnly.get())
    }
}

dependencies {
    rewrite(platform(libs.openrewrite.recipe.bom))
    rewrite("org.openrewrite.recipe:rewrite-migrate-java")

    compileOnly(libs.keycloak.core)
    compileOnly(libs.keycloak.server.spi)
    compileOnly(libs.keycloak.server.spi.private)
    compileOnly(libs.keycloak.services)
    compileOnly(libs.keycloak.model.jpa)
    compileOnly(libs.keycloak.ldap.federation)
    compileOnly(libs.guava)

    implementation(libs.resilience4j.retry)
    implementation(libs.jakarta.ws.rs)
    implementation(libs.jakarta.persistence)
    implementation(libs.scim.sdk.common)
    implementation(libs.scim.sdk.client)
    implementation(libs.commons.lang3)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    add(integrationTest.implementationConfigurationName, libs.testcontainers.core)
    add(integrationTest.implementationConfigurationName, libs.testcontainers.junit)
    add(integrationTest.implementationConfigurationName, libs.testcontainers.keycloak)
    add(integrationTest.implementationConfigurationName, libs.wiremock)
    add(integrationTest.implementationConfigurationName, libs.awaitility)
    add(integrationTest.runtimeOnlyConfigurationName, libs.slf4j.simple)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

tasks.register<Test>("integrationTest") {
    description = "Runs integration tests."
    group = "verification"
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    shouldRunAfter(tasks.test)
    dependsOn(tasks.named("shadowJar"))
    val shadowJarTask = tasks.named<Jar>("shadowJar")
    // docker-java ships with a default API version (1.32) that modern Docker
    // Engines reject. Pin to a version that all currently-supported engines accept.
    systemProperty("api.version", "1.43")
    doFirst {
        systemProperty("keycloak.plugin.jar", shadowJarTask.get().archiveFile.get().asFile.absolutePath)
    }
}

tasks.check {
    dependsOn("integrationTest")
}
