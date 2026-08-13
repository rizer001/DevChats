package devchats.server

/** Направление передачи файла. */
enum class FileDirection { Out, In }

/** Статус передачи файла. */
enum class FileStatus { Transferring, Complete, Aborted }

/** Запись о передаче файла (хранится на узле и показывается в чате). */
data class FileRecord(
    val fileId: String,
    val fileName: String,
    val size: Long,
    val sha256: String,
    /** null — личный файл; иначе — канал. */
    val channelId: String?,
    /** Для DM — собеседник; для канала — автор. */
    val peerNodeId: String,
    val direction: FileDirection,
    val status: FileStatus,
    /** Исходный файл (исходящие) или файл на диске (входящие). */
    val localPath: String,
    val receivedBytes: Long,
    val abortReason: String? = null,
    /** Время начала передачи (для порядка в чате). */
    val timestamp: Long,
)
