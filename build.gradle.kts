plugins {
    id("java-library")
    id("xyz.jpenilla.run-velocity") version "3.1.0"
    id("com.gradleup.shadow") version "9.6.1"
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.codemc.io/repository/maven-releases/")   // PacketEvents
}

dependencies {
    compileOnly("com.velocitypowered:velocity-api:3.5.0-SNAPSHOT")
    implementation("com.github.retrooper:packetevents-velocity:2.14.0")
    implementation("org.xerial:sqlite-jdbc:3.53.4.0")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

tasks {
    jar {
        enabled = false
    }

    shadowJar {
        mergeServiceFiles()
        exclude("module-info.class")
        exclude("META-INF/versions/**/module-info.class")

        relocate("com.github.retrooper.packetevents", "me.pintoadmin.velocityServerDialog.shaded.com.github.retrooper.packetevents.api")
        relocate("io.github.retrooper.packetevents", "me.pintoadmin.velocityServerDialog.shaded.io.github.retrooper.packetevents.impl")
        archiveClassifier.set("")
    }

    build {
        dependsOn(shadowJar)
    }

    runVelocity {
        velocityVersion("3.5.0-SNAPSHOT")
        downloadPlugins {
            modrinth("packetevents", "2.14.0+velocity")
        }
    }

    processResources {
        val props = mapOf("version" to version)
        filesMatching("velocity-plugin.json") {
            expand(props)
        }
    }
}
