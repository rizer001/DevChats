package devchats.server

/** Конфигурация узла DevChats. */
data class NodeConfig(
    /** Постоянный UUID узла (создаётся при первом запуске). */
    val nodeId: String,
    /** Отображаемое имя пользователя. */
    val displayName: String,
    /** Порт, на котором узел принимает подключения. */
    val port: Int = Node.DEFAULT_PORT,
)
