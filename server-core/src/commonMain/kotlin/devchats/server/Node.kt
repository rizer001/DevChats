package devchats.server

/** Версия приложения (синхронизировать с version в build.gradle.kts). */
const val APP_VERSION: String = "0.1.0"

/**
 * Узел DevChats — серверная часть, общая для десктоп-приложения
 * (встраивается) и будущего постоянного сервера на Linux.
 */
object Node {

    /** Порт узла по умолчанию. */
    const val DEFAULT_PORT: Int = 4293
}
