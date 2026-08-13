package devchats.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Текущая версия протокола DevChats. */
const val PROTOCOL_VERSION: Int = 1

/**
 * Json-конфигурация протокола.
 *
 * - [Json.classDiscriminator] = "type" — дискриминатор полиморфных сообщений,
 *   как в спецификации: `{ "type": "hello", ... }`.
 * - [Json.encodeDefaults] — всегда пишем значения по умолчанию (например, `v`),
 *   чтобы конверт был самодостаточным.
 * - [Json.ignoreUnknownKeys] — неизвестные поля не ломают десериализацию
 *   (прямая совместимость между версиями).
 */
val DevChatsJson: Json = Json {
    classDiscriminator = "type"
    encodeDefaults = true
    ignoreUnknownKeys = true
}

/**
 * Конверт любого сообщения протокола.
 *
 * ```json
 * { "v": 1, "id": "uuid", "payload": { "type": "hello", "nodeId": "...", "displayName": "..." } }
 * ```
 *
 * [payload] сериализуется полиморфно на основе иерархии [Message].
 */
@Serializable
data class Envelope(
    val v: Int = PROTOCOL_VERSION,
    val id: String,
    val payload: Message,
)

/** Базовый класс всех сообщений протокола. */
@Serializable
sealed class Message

/**
 * Рукопожатие: клиент представляется узлу при подключении.
 *
 * [nodeId] — постоянный UUID узла (создаётся при первом запуске);
 * [displayName] — отображаемое имя; [protocolVersion] — версия протокола отправителя.
 */
@Serializable
@SerialName("hello")
data class Hello(
    val nodeId: String,
    val displayName: String,
    val protocolVersion: Int = PROTOCOL_VERSION,
) : Message()

/**
 * Ответ узла на [Hello].
 *
 * [accepted] = true — соединение принято; [accepted] = false — отклонено,
 * при этом [reason] = "pending" означает, что владелец узла ещё не решил
 * (запрос отправлен ему на подтверждение), а остальные значения — отказ
 * (заблокирован, несовместимая версия и т.д.).
 */
@Serializable
@SerialName("hello.ack")
data class HelloAck(
    val nodeId: String,
    val displayName: String,
    val protocolVersion: Int = PROTOCOL_VERSION,
    val accepted: Boolean,
    val reason: String? = null,
) : Message()

/** Смена присутствия узла (онлайн/офлайн); рассылается подключённым узлам. */
@Serializable
@SerialName("presence")
data class Presence(
    val nodeId: String,
    val online: Boolean,
) : Message()

/**
 * Личное сообщение: [text] получателю [to] (nodeId).
 *
 * Отправляется по активному соединению; если получатель офлайн, отправитель
 * кладёт сообщение в исходящую очередь и доставляет при синхронизации
 * почтового ящика ([MailboxSync]). Автор определяется сессией соединения.
 */
@Serializable
@SerialName("dm.send")
data class DmSend(
    val messageId: String,
    val to: String,
    val text: String,
    val timestamp: Long,
) : Message()

/**
 * Сообщение в канале. [authorNodeId] указывается явно: хост канала
 * ретранслирует сообщение другим подключённым узлам.
 */
@Serializable
@SerialName("msg.send")
data class MsgSend(
    val messageId: String,
    val channelId: String,
    val authorNodeId: String,
    val text: String,
    val timestamp: Long,
) : Message()

/**
 * Синхронизация почтового ящика: клиент просит узел отдать его исходящие
 * сообщения для этого клиента. Узел отвечает серией [DmSend].
 */
@Serializable
@SerialName("mailbox.sync")
data object MailboxSync : Message()

/** Запрос списка каналов узла. Ответ — [ChannelListItems]. */
@Serializable
@SerialName("channel.list")
data object ChannelListRequest : Message()

/** Ответ на [ChannelListRequest]: каналы узла. */
@Serializable
@SerialName("channel.list.items")
data class ChannelListItems(
    val channels: List<ChannelInfo>,
) : Message()

/** Запрос истории сообщений канала. Ответ — [MsgHistoryItems]. */
@Serializable
@SerialName("msg.history")
data class MsgHistoryRequest(
    val channelId: String,
    val limit: Int = 200,
    val before: Long? = null,
) : Message()

/** Ответ на [MsgHistoryRequest]: история сообщений канала. */
@Serializable
@SerialName("msg.history.items")
data class MsgHistoryItems(
    val channelId: String,
    val messages: List<MessageInfo>,
) : Message()

/** Размер чанка файла по умолчанию: 1 МБ. */
const val FILE_CHUNK_SIZE: Int = 1024 * 1024

/**
 * Предложение передачи файла. [authorNodeId] указывается явно — хост канала
 * ретранслирует предложение другим участникам. Данные идут следом чанками
 * [FileChunk], завершение — [FileDone].
 */
@Serializable
@SerialName("file.offer")
data class FileOffer(
    val fileId: String,
    val fileName: String,
    val size: Long,
    val sha256: String,
    /** null — личный файл; иначе — канал. */
    val channelId: String? = null,
    val authorNodeId: String,
    val chunkSize: Int = FILE_CHUNK_SIZE,
) : Message()

/** Согласие на приём файла; [offset] — позиция для докачки прерванной передачи. */
@Serializable
@SerialName("file.accept")
data class FileAccept(
    val fileId: String,
    val offset: Long = 0,
) : Message()

/** Чанк файла (данные в base64 — см. [Base64ByteArraySerializer]). */
@Serializable
@SerialName("file.chunk")
data class FileChunk(
    val fileId: String,
    val index: Int,
    @Serializable(with = Base64ByteArraySerializer::class)
    val data: ByteArray,
) : Message()

/** Завершение передачи файла. */
@Serializable
@SerialName("file.done")
data class FileDone(
    val fileId: String,
) : Message()

/** Прерывание передачи с причиной. */
@Serializable
@SerialName("file.abort")
data class FileAbort(
    val fileId: String,
    val reason: String,
) : Message()

// --- звонки (голос через Opus поверх WebSocket) ---

/**
 * Исходящий вызов: клиент хочет позвонить [to].
 *
 * Маршрутизируется как DM: по исходящему соединению или через входящую
 * сессию узла. Ответ — [CallAccept] или [CallReject].
 */
@Serializable
@SerialName("call.offer")
data class CallOffer(
    val callId: String,
    val to: String,
    val fromNodeId: String,
    val fromName: String? = null,
) : Message()

/** Собеседник принял вызов — можно начинать обмен [CallAudio]. */
@Serializable
@SerialName("call.accept")
data class CallAccept(
    val callId: String,
    val to: String,
    val fromNodeId: String,
) : Message()

/** Собеседник отклонил вызов (или занят — [reason] = "занят"). */
@Serializable
@SerialName("call.reject")
data class CallReject(
    val callId: String,
    val to: String,
    val fromNodeId: String,
    val reason: String? = null,
) : Message()

/** Завершение звонка любой стороной. */
@Serializable
@SerialName("call.hangup")
data class CallHangup(
    val callId: String,
    val to: String,
    val fromNodeId: String,
) : Message()

/**
 * Один кадр голоса: Opus-пакет (обычно 20 мс, ~960 сэмплов PCM 48 кГц).
 * Данные в base64 — см. [Base64ByteArraySerializer]. [seq] — порядковый номер
 * кадра (для будущего jitter-буфера).
 */
@Serializable
@SerialName("call.audio")
data class CallAudio(
    val callId: String,
    val to: String,
    val fromNodeId: String,
    val seq: Int,
    @Serializable(with = Base64ByteArraySerializer::class)
    val data: ByteArray,
) : Message()

// --- видео (JPEG-кадры поверх того же WebSocket-канала) ---

/** Вид трансляции видео: камера или экран. */
const val VIDEO_KIND_CAMERA: String = "camera"
const val VIDEO_KIND_SCREEN: String = "screen"

/**
 * Начало видеотрансляции в активном звонке: [kind] (camera/screen),
 * [width]×[height] — размер кадров, которые пойдут следом. Дальше — серия
 * [VideoFrame], завершение — [VideoStop].
 */
@Serializable
@SerialName("video.start")
data class VideoStart(
    val callId: String,
    val to: String,
    val fromNodeId: String,
    val kind: String,
    val width: Int,
    val height: Int,
) : Message()

/** Один кадр видео: JPEG в base64 (см. [Base64ByteArraySerializer]). */
@Serializable
@SerialName("video.frame")
data class VideoFrame(
    val callId: String,
    val to: String,
    val fromNodeId: String,
    val seq: Int,
    val kind: String,
    @Serializable(with = Base64ByteArraySerializer::class)
    val data: ByteArray,
) : Message()

/** Окончание видеотрансляции [kind] (отключили камеру/экран или завершили звонок). */
@Serializable
@SerialName("video.stop")
data class VideoStop(
    val callId: String,
    val to: String,
    val fromNodeId: String,
    val kind: String,
) : Message()

/** Типы каналов (в духе Discord). */
const val CHANNEL_KIND_TEXT: String = "text"
const val CHANNEL_KIND_VOICE: String = "voice"
const val CHANNEL_KIND_ANNOUNCEMENTS: String = "announcements"
const val CHANNEL_KIND_CONFERENCE: String = "conference"
const val CHANNEL_KIND_FORUM: String = "forum"

/** Все допустимые типы каналов (для выбора в UI и валидации). */
val ALL_CHANNEL_KINDS: List<String> = listOf(
    CHANNEL_KIND_TEXT,
    CHANNEL_KIND_VOICE,
    CHANNEL_KIND_ANNOUNCEMENTS,
    CHANNEL_KIND_CONFERENCE,
    CHANNEL_KIND_FORUM,
)

/** Описание канала (передаётся по сети и хранится на узле). */
@Serializable
data class ChannelInfo(
    val id: String,
    val name: String,
    val kind: String = CHANNEL_KIND_TEXT,
    val description: String = "",
)

/** Сообщение (хранится на узле и передаётся в истории каналов). */
@Serializable
data class MessageInfo(
    val messageId: String,
    /** null — личное сообщение; иначе — id канала. */
    val channelId: String? = null,
    val authorNodeId: String,
    val text: String,
    val timestamp: Long,
)
