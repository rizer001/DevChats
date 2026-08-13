package devchats.server

/** Статус встроенного сервера узла (для UI). */
sealed interface ServerStatus {
    data object Starting : ServerStatus
    data object Running : ServerStatus
    data object Stopped : ServerStatus
    data class Failed(val reason: String) : ServerStatus
}
