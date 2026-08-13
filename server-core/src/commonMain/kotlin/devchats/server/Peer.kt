package devchats.server

/** Статус узла-пира с точки зрения нашего узла. */
enum class PeerStatus {
    /** Запросил подключение, ждёт решения владельца. */
    Pending,

    /** Подключение разрешено. */
    Accepted,

    /** Узел заблокирован. */
    Blocked,
}

/** Известный нам узел (пир). */
data class Peer(
    val nodeId: String,
    val displayName: String,
    val address: String? = null,
    val status: PeerStatus,
)
