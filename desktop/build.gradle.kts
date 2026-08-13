plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    jvm("desktop")

    sourceSets {
        val desktopMain by getting
        desktopMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(project(":protocol"))
            implementation(project(":client-core"))
            implementation(project(":server-core"))
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.websockets)
            implementation(libs.ktor.client.cio)
            implementation(libs.concentus)
            implementation(libs.webcam.capture)
        }
        val desktopTest by getting
        desktopTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

compose.desktop {
    application {
        mainClass = "devchats.desktop.MainKt"
    }
}

// ==== Portable-дистрибутив ====
// Собирает zip-архив: fat jar + лаунчеры (Windows/Linux/macOS). Всё данные
// (БД SQLite, принятые файлы) хранятся в папке `data` рядом с приложением —
// полный portable-режим. Требуется установленная Java (JDK 17+); вариант
// со встроенной JRE даёт штатный таск createDistributable.

val preparePortable by tasks.registering {
    val stagingDir = layout.buildDirectory.dir("portable/DevChats")
    inputs.property("scriptsVersion", "1")
    outputs.dir(stagingDir)
    doLast {
        val dir = stagingDir.get().asFile
        dir.mkdirs()
        dir.resolve("devchats.bat").writeText(
            """
            |@echo off
            |rem DevChats portable launcher (Windows)
            |rem All data (SQLite DB, received files) is stored in the "data"
            |rem folder next to this file. Override with DEVCHATS_HOME.
            |setlocal
            |set "APP_DIR=%~dp0"
            |if not defined DEVCHATS_HOME set "DEVCHATS_HOME=%APP_DIR%data"
            |if not exist "%DEVCHATS_HOME%" mkdir "%DEVCHATS_HOME%"
            |set "JAVA_BIN=java"
            |if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_BIN=%JAVA_HOME%\bin\java.exe"
            |"%JAVA_BIN%" -jar "%APP_DIR%DevChats.jar" %*
            |endlocal
            """.trimMargin().replace("\n", "\r\n")
        )
        dir.resolve("devchats.sh").writeText(
            "#!/bin/sh\n" +
                "# DevChats portable launcher (Linux/macOS)\n" +
                "# All data (SQLite DB, received files) is stored in the \"data\"\n" +
                "# folder next to this file. Override with DEVCHATS_HOME.\n" +
                "DIR=\"\$(cd \"\$(dirname \"\$0\")\" && pwd)\"\n" +
                "if [ -z \"\$DEVCHATS_HOME\" ]; then\n" +
                "  export DEVCHATS_HOME=\"\$DIR/data\"\n" +
                "fi\n" +
                "mkdir -p \"\$DEVCHATS_HOME\"\n" +
                "exec java -jar \"\$DIR/DevChats.jar\" \"\$@\"\n"
        )
        dir.resolve("README.txt").writeText(
            """
            |DevChats — децентрализованный мессенджер (portable-версия)
            |===========================================================
            |
            |Запуск:
            |  Windows:     дважды кликнуть devchats.bat
            |  Linux/macOS: ./devchats.sh   (или: sh devchats.sh)
            |
            |Данные:
            |  Всё хранится рядом с приложением, в папке data/:
            |    data/devchats.db  — база данных SQLite (аккаунт, сервера, сообщения)
            |    data/files/       — принятые файлы
            |  Чтобы перенести данные в другое место, задай переменную окружения
            |  DEVCHATS_HOME перед запуском.
            |
            |Подключение к другим узлам:
            |  Нажми «+» в списке ЛС и укажи IP:порт собеседника (порт узла
            |  показан внизу боковой панели). Оба узла должны быть в одной сети.
            |
            |Требования:
            |  Установленная Java (JDK 17+). Версия со встроенной JRE собирается
            |  штатным таском Gradle: ./gradlew :desktop:createDistributable
            |
            |Примечания:
            |  Программный рендеринг включён по умолчанию (стабильность на любых
            |  GPU). Для аппаратного задай SKIKO_RENDER_API=DIRECT3D (Windows)
            |  перед запуском.
            """.trimIndent()
        )
    }
}

val portableZip by tasks.registering(Zip::class) {
    group = "distribution"
    description = "Собирает portable-архив DevChats (zip): fat jar + лаунчеры Windows/Linux/macOS; данные хранятся в папке data рядом с приложением"
    dependsOn("packageUberJarForCurrentOS", preparePortable)

    from(layout.buildDirectory.dir("portable")) {
        exclude("**/*.sh")
    }
    from(layout.buildDirectory.dir("portable")) {
        include("**/*.sh")
        filePermissions { unix("rwxr-xr-x") }
    }
    from(providers.provider {
        tasks.named("packageUberJarForCurrentOS").get().outputs.files.singleFile
    }) {
        into("DevChats")
        rename { "DevChats.jar" }
    }

    archiveBaseName = "DevChats"
    archiveVersion = project.version.toString()
    archiveClassifier = "portable"
    destinationDirectory = layout.buildDirectory.dir("distributions")
}
