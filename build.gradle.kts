import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.3.50"
}

group = "fr.delthas"
version = "0.0.1-SNAPSHOT"

repositories {
    mavenCentral()
	mavenLocal()
}

dependencies {
    implementation(kotlin("stdlib-jdk8"))
    implementation("org.json:json:20190722")
    implementation("org.jsoup:jsoup:1.12.1")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.9.9")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.9.9")
    implementation("com.fasterxml.jackson.core:jackson-core:2.9.9")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.9.9")
    implementation("com.sparkjava:spark-core:2.9.1")
}

tasks.withType<KotlinCompile> {
    kotlinOptions.freeCompilerArgs = listOf("-Xjvm-default=enable")
    kotlinOptions.jvmTarget = "1.8"
}

tasks.jar {
    enabled = false
}

val jarCore by tasks.registering(Jar::class) {
    from(sourceSets.main.get().output)

    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get().filter { it.name.endsWith("jar") }.map { zipTree(it) }
    })

    manifest {
        attributes(
                "Main-Class" to "fr.delthas.giteart.GiteartKt"
        )
    }

    archiveName = "giteart.jar"
}

tasks.assemble {
    dependsOn(jarCore)
}
