package devchats.server

import devchats.protocol.CHANNEL_KIND_TEXT
import devchats.protocol.ChannelInfo
import devchats.protocol.MessageInfo
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID

/** Цвет баннера сервера по умолчанию. */
private const val DEFAULT_BANNER_COLOR: String = "#5865F2"

/** Цвет роли по умолчанию. */
private const val DEFAULT_ROLE_COLOR: String = "#99AAB5"

/** Сервер (гильдия): локальное сообщество с каналами и настройками. */
data class ServerInfo(
    val id: String,
    val name: String,
    val description: String,
    val avatarPath: String?,
    val bannerColor: String = DEFAULT_BANNER_COLOR,
    val joinLeaveEnabled: Boolean = false,
    val joinLeaveChannelIds: List<String> = emptyList(),
    val afkEnabled: Boolean = false,
    val afkChannelId: String? = null,
    val emojiAutocomplete: Boolean = true,
    val emojiConvert: Boolean = true,
)

/** Эмодзи сервера: картинка + имя (`:name:`). */
data class EmojiInfo(
    val id: String,
    val serverId: String,
    val name: String,
    val imagePath: String,
)

/** Звук звуковой панели: файл, кнопка активации, границы обрезки (мс). */
data class SoundInfo(
    val id: String,
    val serverId: String,
    val name: String,
    val filePath: String,
    val keyBind: String,
    val trimStartMs: Long,
    val trimEndMs: Long,
)

/** Роль сервера. [position] — порядок (больше = выше в списке). */
data class RoleInfo(
    val id: String,
    val serverId: String,
    val name: String,
    val color: String,
    val position: Int,
    val showSeparately: Boolean,
    val mentionable: Boolean,
)

/** Идентичность узла: nodeId (UUID) + имя + аккаунт (логин/пароль/аватар). Одна строка. */
object IdentityTable : Table("identity") {
    val id = integer("id").autoIncrement()
    val nodeId = text("node_id")
    val displayName = text("display_name")
    val login = text("login").nullable()
    val passwordHash = text("password_hash").nullable()
    val avatarPath = text("avatar_path").nullable()
    override val primaryKey = PrimaryKey(id)
}

/** Произвольные настройки узла: key → value. */
object SettingsTable : Table("settings") {
    val key = text("key")
    val value = text("value")
    override val primaryKey = PrimaryKey(key)
}

/** Известные узлы (пиры): кто может подключаться к нашему узлу и их статус. */
object PeersTable : Table("peers") {
    val nodeId = text("node_id")
    val displayName = text("display_name")
    val address = text("address").nullable()
    val status = text("status")
    val lastSeen = long("last_seen").nullable()
    override val primaryKey = PrimaryKey(nodeId)
}

/** Сервера, которые хостит наш узел. */
object ServersTable : Table("servers") {
    val id = text("id")
    val name = text("name")
    val description = text("description").default("")
    val avatarPath = text("avatar_path").nullable()
    val bannerColor = text("banner_color").default(DEFAULT_BANNER_COLOR)
    val joinLeaveEnabled = bool("join_leave_enabled").default(false)
    val joinLeaveChannels = text("join_leave_channels").default("")
    val afkEnabled = bool("afk_enabled").default(false)
    val afkChannelId = text("afk_channel_id").nullable()
    val emojiAutocomplete = bool("emoji_autocomplete").default(true)
    val emojiConvert = bool("emoji_convert").default(true)
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)
}

/** Каналы: [serverId] = null — «старый» канал вне сервера (обратная совместимость). */
object ChannelsTable : Table("channels") {
    val id = text("id")
    val name = text("name")
    val kind = text("kind")
    val serverId = text("server_id").nullable()
    val description = text("description").default("")
    override val primaryKey = PrimaryKey(id)
}

/** Эмодзи сервера. */
object ServerEmojisTable : Table("server_emojis") {
    val id = text("id")
    val serverId = text("server_id")
    val name = text("name")
    val imagePath = text("image_path")
    val position = integer("position").default(0)
    override val primaryKey = PrimaryKey(id)
}

/** Звуки звуковой панели сервера. */
object ServerSoundsTable : Table("server_sounds") {
    val id = text("id")
    val serverId = text("server_id")
    val name = text("name")
    val filePath = text("file_path")
    val keyBind = text("key_bind").default("")
    val trimStartMs = long("trim_start_ms").default(0)
    val trimEndMs = long("trim_end_ms").default(0)
    val position = integer("position").default(0)
    override val primaryKey = PrimaryKey(id)
}

/** Роли сервера. */
object ServerRolesTable : Table("server_roles") {
    val id = text("id")
    val serverId = text("server_id")
    val name = text("name")
    val color = text("color").default(DEFAULT_ROLE_COLOR)
    val position = integer("position").default(0)
    val showSeparately = bool("show_separately").default(false)
    val mentionable = bool("mentionable").default(false)
    override val primaryKey = PrimaryKey(id)
}

/**
 * Файлы (передачи). [localPath] — файл на диске, [receivedBytes] — прогресс.
 */
object FilesTable : Table("files") {
    val fileId = text("file_id")
    val fileName = text("file_name")
    val size = long("size")
    val sha256 = text("sha256")
    val channelId = text("channel_id").nullable()
    val peerNodeId = text("peer_node_id")
    val direction = text("direction")
    val status = text("status")
    val localPath = text("local_path")
    val receivedBytes = long("received_bytes").default(0)
    val abortReason = text("abort_reason").nullable()
    val timestamp = long("timestamp")
    override val primaryKey = PrimaryKey(fileId)
}

/**
 * Сообщения.
 *
 * DM: [channelId] = null, [authorNodeId] — автор, [toNodeId] — получатель.
 * Канал: [channelId] = id канала, [authorNodeId] — автор, [toNodeId] = null.
 * Исходящие неотправленные: [delivered] = false (только DM).
 */
object MessagesTable : Table("messages") {
    val id = long("id").autoIncrement()
    val messageId = text("message_id")
    val channelId = text("channel_id").nullable()
    val authorNodeId = text("author_node_id")
    val toNodeId = text("to_node_id").nullable()
    val text = text("text")
    val timestamp = long("timestamp")
    val delivered = bool("delivered").default(true)
    override val primaryKey = PrimaryKey(id)
}

/**
 * SQLite-хранилище узла (в `~/.devchats/devchats.db` по умолчанию).
 *
 * M2: идентичность, настройки, пиры. M3: каналы и сообщения (DM + история).
 * M4: файлы. M7: аккаунты (логин/пароль/аватар) и сервера.
 */
class NodeStore(private val dbPath: Path) {

    private val database: Database = Database.connect(
        url = "jdbc:sqlite:${dbPath.toAbsolutePath()}",
        driver = "org.sqlite.JDBC",
    )

    init {
        transaction(database) {
            SchemaUtils.create(
                IdentityTable, SettingsTable, PeersTable, ServersTable, ChannelsTable, MessagesTable, FilesTable,
                ServerEmojisTable, ServerSoundsTable, ServerRolesTable,
            )
            // Миграции старых БД: добавляем недостающие колонки (для новых — уже созданы, ALTER упадёт и будет проглочен).
            try { exec("ALTER TABLE identity ADD COLUMN login TEXT") } catch (_: Exception) {}
            try { exec("ALTER TABLE identity ADD COLUMN password_hash TEXT") } catch (_: Exception) {}
            try { exec("ALTER TABLE identity ADD COLUMN avatar_path TEXT") } catch (_: Exception) {}
            try { exec("ALTER TABLE channels ADD COLUMN server_id TEXT") } catch (_: Exception) {}
            try { exec("ALTER TABLE channels ADD COLUMN description TEXT DEFAULT ''") } catch (_: Exception) {}
            try { exec("ALTER TABLE servers ADD COLUMN banner_color TEXT DEFAULT '$DEFAULT_BANNER_COLOR'") } catch (_: Exception) {}
            try { exec("ALTER TABLE servers ADD COLUMN join_leave_enabled INTEGER DEFAULT 0") } catch (_: Exception) {}
            try { exec("ALTER TABLE servers ADD COLUMN join_leave_channels TEXT DEFAULT ''") } catch (_: Exception) {}
            try { exec("ALTER TABLE servers ADD COLUMN afk_enabled INTEGER DEFAULT 0") } catch (_: Exception) {}
            try { exec("ALTER TABLE servers ADD COLUMN afk_channel_id TEXT") } catch (_: Exception) {}
            try { exec("ALTER TABLE servers ADD COLUMN emoji_autocomplete INTEGER DEFAULT 1") } catch (_: Exception) {}
            try { exec("ALTER TABLE servers ADD COLUMN emoji_convert INTEGER DEFAULT 1") } catch (_: Exception) {}
        }
    }

    // --- идентичность ---

    /** Загружает конфигурацию узла, создавая идентичность при первом запуске. */
    fun loadOrCreateConfig(): NodeConfig = transaction(database) {
        var row = IdentityTable.selectAll().firstOrNull()
        if (row == null) {
            val newId = UUID.randomUUID().toString()
            IdentityTable.insert {
                it[IdentityTable.nodeId] = newId
                it[IdentityTable.displayName] = DEFAULT_DISPLAY_NAME
            }
            row = IdentityTable.selectAll().firstOrNull()
        }
        val port = settings()[SETTING_PORT]?.toIntOrNull() ?: Node.DEFAULT_PORT
        NodeConfig(
            nodeId = row?.get(IdentityTable.nodeId) ?: error("identity не создана"),
            displayName = row?.get(IdentityTable.displayName) ?: DEFAULT_DISPLAY_NAME,
            port = port,
        )
    }

    // --- аккаунт ---

    /** Зарегистрирован ли аккаунт на этом устройстве. */
    fun hasAccount(): Boolean = transaction(database) {
        IdentityTable.selectAll().firstOrNull()?.get(IdentityTable.login) != null
    }

    /**
     * Регистрирует аккаунт узла: создаёт идентичность (nodeId), сохраняет
     * логин и хеш пароля (SHA-256 с солью). Возвращает конфигурацию узла.
     */
    fun register(login: String, password: String, displayName: String, avatarPath: String? = null): NodeConfig {
        if (hasAccount()) error("Аккаунт уже зарегистрирован на этом устройстве")
        if (login.isBlank() || password.length < 4) error("Логин не может быть пустым, пароль — короче 4 символов")
        val nodeId = UUID.randomUUID().toString()
        val salt = UUID.randomUUID().toString().replace("-", "").take(16)
        val hash = hashPassword(salt, password)
        transaction(database) {
            IdentityTable.deleteAll()
            IdentityTable.insert {
                it[IdentityTable.nodeId] = nodeId
                it[IdentityTable.displayName] = displayName.ifBlank { login }
                it[IdentityTable.login] = login.trim()
                it[IdentityTable.passwordHash] = "$salt:$hash"
                it[IdentityTable.avatarPath] = avatarPath
            }
        }
        return NodeConfig(nodeId, displayName.ifBlank { login })
    }

    /** Проверяет логин/пароль; возвращает конфигурацию узла или null. */
    fun login(login: String, password: String): NodeConfig? = transaction(database) {
        val row = IdentityTable.selectAll().firstOrNull() ?: return@transaction null
        val stored = row[IdentityTable.passwordHash] ?: return@transaction null
        if (row[IdentityTable.login] != login.trim()) return@transaction null
        if (hashPassword(stored.substringBefore(':'), password) != stored.substringAfter(':')) return@transaction null
        NodeConfig(row[IdentityTable.nodeId], row[IdentityTable.displayName])
    }

    /** Обновляет отображаемое имя. */
    fun setDisplayName(name: String) {
        if (name.isBlank()) return
        transaction(database) {
            IdentityTable.update({ IdentityTable.id eq identityId() }) {
                it[IdentityTable.displayName] = name.trim()
            }
        }
    }

    /** Копирует картинку в директорию `avatars` узла и возвращает новый путь. */
    fun saveAvatar(sourcePath: String): String {
        val dir = dbPath.parent.resolve("avatars")
        Files.createDirectories(dir)
        val nodeId = transaction(database) {
            IdentityTable.selectAll().firstOrNull()?.get(IdentityTable.nodeId)
        } ?: "node"
        val ext = sourcePath.substringAfterLast('.', "png").lowercase().takeIf { it.length in 1..5 } ?: "png"
        val dest = dir.resolve("$nodeId-${System.currentTimeMillis()}.$ext")
        Files.copy(Paths.get(sourcePath), dest, StandardCopyOption.REPLACE_EXISTING)
        return dest.toString()
    }

    /** Сохраняет путь к аватару узла (файл уже скопирован в [saveAvatar]). */
    fun setAvatar(path: String) {
        transaction(database) {
            IdentityTable.update({ IdentityTable.id eq identityId() }) {
                it[IdentityTable.avatarPath] = path
            }
        }
    }

    /** Путь к аватару узла (null — аватара нет). */
    fun avatarPath(): String? = transaction(database) {
        IdentityTable.selectAll().firstOrNull()?.get(IdentityTable.avatarPath)
    }

    // --- пиры ---

    /** Статус пира; null — пир неизвестен (запрос на подключение). */
    fun peerStatus(nodeId: String): PeerStatus? = transaction(database) {
        PeersTable.selectAll()
            .where { PeersTable.nodeId eq nodeId }
            .firstOrNull()
            ?.get(PeersTable.status)
            ?.let { PeerStatus.valueOf(it) }
    }

    fun upsertPeer(peer: Peer) {
        transaction(database) {
            PeersTable.upsert(PeersTable.nodeId) {
                it[PeersTable.nodeId] = peer.nodeId
                it[displayName] = peer.displayName
                it[address] = peer.address
                it[status] = peer.status.name
            }
        }
    }

    fun setPeerStatus(nodeId: String, status: PeerStatus) {
        transaction(database) {
            PeersTable.update({ PeersTable.nodeId eq nodeId }) {
                it[PeersTable.status] = status.name
            }
        }
    }

    fun deletePeer(nodeId: String) {
        transaction(database) {
            PeersTable.deleteWhere { PeersTable.nodeId eq nodeId }
        }
    }

    fun markSeen(nodeId: String) {
        transaction(database) {
            PeersTable.update({ PeersTable.nodeId eq nodeId }) {
                it[lastSeen] = System.currentTimeMillis()
            }
        }
    }

    fun peersByStatus(status: PeerStatus): List<Peer> = transaction(database) {
        PeersTable.selectAll()
            .where { PeersTable.status eq status.name }
            .map { row ->
                Peer(
                    nodeId = row[PeersTable.nodeId],
                    displayName = row[PeersTable.displayName],
                    address = row[PeersTable.address],
                    status = status,
                )
            }
    }

    // --- сервера ---

    /** Все сервера узла (порядок создания). */
    fun servers(): List<ServerInfo> = transaction(database) {
        ServersTable.selectAll()
            .orderBy(ServersTable.createdAt)
            .map(::toServerInfo)
    }

    /** Сервер по id; null — не найден. */
    fun server(id: String): ServerInfo? = transaction(database) {
        ServersTable.selectAll().where { ServersTable.id eq id }.firstOrNull()?.let(::toServerInfo)
    }

    fun addServer(name: String, description: String = "", avatarPath: String? = null): ServerInfo {
        val cleanName = name.trim().ifEmpty { "Новый сервер" }
        return transaction(database) {
            val id = UUID.randomUUID().toString()
            ServersTable.insert {
                it[ServersTable.id] = id
                it[ServersTable.name] = cleanName
                it[ServersTable.description] = description.trim()
                it[ServersTable.avatarPath] = avatarPath
                it[ServersTable.createdAt] = System.currentTimeMillis()
            }
            ServerInfo(id, cleanName, description.trim(), avatarPath)
        }
    }

    fun updateServer(id: String, name: String, description: String) {
        transaction(database) {
            ServersTable.update({ ServersTable.id eq id }) {
                it[ServersTable.name] = name.trim().ifEmpty { "Сервер" }
                it[ServersTable.description] = description.trim()
            }
        }
    }

    /** Сохраняет путь к аватару сервера (файл уже скопирован в [saveAvatar]). */
    fun setServerAvatar(id: String, path: String) {
        transaction(database) {
            ServersTable.update({ ServersTable.id eq id }) {
                it[ServersTable.avatarPath] = path
            }
        }
    }

    /** Обновляет остальные настройки сервера (баннер, вход/выход, бездействие, эмодзи). */
    fun updateServerConfig(
        id: String,
        bannerColor: String,
        joinLeaveEnabled: Boolean,
        joinLeaveChannelIds: List<String>,
        afkEnabled: Boolean,
        afkChannelId: String?,
        emojiAutocomplete: Boolean,
        emojiConvert: Boolean,
    ) {
        transaction(database) {
            ServersTable.update({ ServersTable.id eq id }) {
                it[ServersTable.bannerColor] = bannerColor
                it[ServersTable.joinLeaveEnabled] = joinLeaveEnabled
                it[ServersTable.joinLeaveChannels] = joinLeaveChannelIds.joinToString(",")
                it[ServersTable.afkEnabled] = afkEnabled
                it[ServersTable.afkChannelId] = afkChannelId
                it[ServersTable.emojiAutocomplete] = emojiAutocomplete
                it[ServersTable.emojiConvert] = emojiConvert
            }
        }
    }

    /** Копирует медиафайл в поддиректорию сервера (`emojis`/`sounds`) и возвращает путь. */
    fun saveServerFile(serverId: String, subdir: String, sourcePath: String): String {
        val dir = dbPath.parent.resolve(subdir).resolve(serverId)
        Files.createDirectories(dir)
        val ext = sourcePath.substringAfterLast('.', "bin").lowercase().takeIf { it.length in 1..8 } ?: "bin"
        val dest = dir.resolve("${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}.$ext")
        Files.copy(Paths.get(sourcePath), dest, StandardCopyOption.REPLACE_EXISTING)
        return dest.toString()
    }

    // --- эмодзи ---

    fun serverEmojis(serverId: String): List<EmojiInfo> = transaction(database) {
        ServerEmojisTable.selectAll()
            .where { ServerEmojisTable.serverId eq serverId }
            .orderBy(ServerEmojisTable.position)
            .map { row -> EmojiInfo(row[ServerEmojisTable.id], serverId, row[ServerEmojisTable.name], row[ServerEmojisTable.imagePath]) }
    }

    fun addEmoji(serverId: String, name: String, imagePath: String): EmojiInfo {
        val clean = name.trim().removePrefix(":").removeSuffix(":").ifEmpty { "эмодзи" }
        return transaction(database) {
            val id = UUID.randomUUID().toString()
            val pos = (ServerEmojisTable.selectAll().where { ServerEmojisTable.serverId eq serverId }.count()).toInt() + 1
            ServerEmojisTable.insert {
                it[ServerEmojisTable.id] = id
                it[ServerEmojisTable.serverId] = serverId
                it[ServerEmojisTable.name] = clean
                it[ServerEmojisTable.imagePath] = imagePath
                it[ServerEmojisTable.position] = pos
            }
            EmojiInfo(id, serverId, clean, imagePath)
        }
    }

    fun deleteEmoji(id: String) {
        transaction(database) { ServerEmojisTable.deleteWhere { ServerEmojisTable.id eq id } }
    }

    // --- звуковая панель ---

    fun serverSounds(serverId: String): List<SoundInfo> = transaction(database) {
        ServerSoundsTable.selectAll()
            .where { ServerSoundsTable.serverId eq serverId }
            .orderBy(ServerSoundsTable.position)
            .map { row ->
                SoundInfo(
                    id = row[ServerSoundsTable.id],
                    serverId = serverId,
                    name = row[ServerSoundsTable.name],
                    filePath = row[ServerSoundsTable.filePath],
                    keyBind = row[ServerSoundsTable.keyBind],
                    trimStartMs = row[ServerSoundsTable.trimStartMs],
                    trimEndMs = row[ServerSoundsTable.trimEndMs],
                )
            }
    }

    fun addSound(serverId: String, name: String, filePath: String, keyBind: String): SoundInfo {
        val clean = name.trim().ifEmpty { "звук" }
        return transaction(database) {
            val id = UUID.randomUUID().toString()
            val pos = (ServerSoundsTable.selectAll().where { ServerSoundsTable.serverId eq serverId }.count()).toInt() + 1
            ServerSoundsTable.insert {
                it[ServerSoundsTable.id] = id
                it[ServerSoundsTable.serverId] = serverId
                it[ServerSoundsTable.name] = clean
                it[ServerSoundsTable.filePath] = filePath
                it[ServerSoundsTable.keyBind] = keyBind.trim()
                it[ServerSoundsTable.position] = pos
            }
            SoundInfo(id, serverId, clean, filePath, keyBind.trim(), 0, 0)
        }
    }

    fun updateSound(id: String, keyBind: String, trimStartMs: Long, trimEndMs: Long) {
        transaction(database) {
            ServerSoundsTable.update({ ServerSoundsTable.id eq id }) {
                it[ServerSoundsTable.keyBind] = keyBind.trim()
                it[ServerSoundsTable.trimStartMs] = trimStartMs
                it[ServerSoundsTable.trimEndMs] = trimEndMs
            }
        }
    }

    fun deleteSound(id: String) {
        transaction(database) { ServerSoundsTable.deleteWhere { ServerSoundsTable.id eq id } }
    }

    // --- роли ---

    fun serverRoles(serverId: String): List<RoleInfo> = transaction(database) {
        ServerRolesTable.selectAll()
            .where { ServerRolesTable.serverId eq serverId }
            .orderBy(ServerRolesTable.position, SortOrder.DESC)
            .map { row ->
                RoleInfo(
                    id = row[ServerRolesTable.id],
                    serverId = serverId,
                    name = row[ServerRolesTable.name],
                    color = row[ServerRolesTable.color],
                    position = row[ServerRolesTable.position],
                    showSeparately = row[ServerRolesTable.showSeparately],
                    mentionable = row[ServerRolesTable.mentionable],
                )
            }
    }

    fun addRole(serverId: String, name: String): RoleInfo {
        val clean = name.trim().ifEmpty { "Новая роль" }
        return transaction(database) {
            val id = UUID.randomUUID().toString()
            val pos = (ServerRolesTable.selectAll().where { ServerRolesTable.serverId eq serverId }.count()).toInt() + 1
            ServerRolesTable.insert {
                it[ServerRolesTable.id] = id
                it[ServerRolesTable.serverId] = serverId
                it[ServerRolesTable.name] = clean
                it[ServerRolesTable.color] = DEFAULT_ROLE_COLOR
                it[ServerRolesTable.position] = pos
            }
            RoleInfo(id, serverId, clean, DEFAULT_ROLE_COLOR, pos, false, false)
        }
    }

    fun updateRole(id: String, name: String, color: String, showSeparately: Boolean, mentionable: Boolean) {
        transaction(database) {
            ServerRolesTable.update({ ServerRolesTable.id eq id }) {
                it[ServerRolesTable.name] = name.trim().ifEmpty { "Роль" }
                it[ServerRolesTable.color] = color
                it[ServerRolesTable.showSeparately] = showSeparately
                it[ServerRolesTable.mentionable] = mentionable
            }
        }
    }

    fun deleteRole(id: String) {
        transaction(database) { ServerRolesTable.deleteWhere { ServerRolesTable.id eq id } }
    }

    /** Переупорядочивает роли: первый элемент [orderedIds] — самая верхняя. */
    fun reorderRoles(serverId: String, orderedIds: List<String>) {
        transaction(database) {
            val max = orderedIds.size
            orderedIds.forEachIndexed { index, roleId ->
                ServerRolesTable.update({ ServerRolesTable.id eq roleId }) {
                    it[ServerRolesTable.position] = max - index
                }
            }
        }
    }

    /** Удаляет сервер вместе с каналами, сообщениями, эмодзи, звуками и ролями. */
    fun deleteServer(id: String) {
        val channelIds = transaction(database) {
            ChannelsTable.selectAll().where { ChannelsTable.serverId eq id }.map { it[ChannelsTable.id] }
        }
        for (channelId in channelIds) deleteChannel(channelId)
        transaction(database) {
            ServerEmojisTable.deleteWhere { ServerEmojisTable.serverId eq id }
            ServerSoundsTable.deleteWhere { ServerSoundsTable.serverId eq id }
            ServerRolesTable.deleteWhere { ServerRolesTable.serverId eq id }
            ServersTable.deleteWhere { ServersTable.id eq id }
        }
    }

    // --- каналы ---

    /** Все каналы узла (для протокола channel.list). */
    fun channels(): List<ChannelInfo> = transaction(database) {
        ChannelsTable.selectAll()
            .orderBy(ChannelsTable.name)
            .map(::toChannelInfo)
    }

    /** Каналы сервера [serverId]. */
    fun channels(serverId: String): List<ChannelInfo> = transaction(database) {
        ChannelsTable.selectAll()
            .where { ChannelsTable.serverId eq serverId }
            .orderBy(ChannelsTable.name)
            .map(::toChannelInfo)
    }

    /** Канал по id; null — не найден. */
    fun channel(id: String): ChannelInfo? = transaction(database) {
        ChannelsTable.selectAll().where { ChannelsTable.id eq id }.firstOrNull()?.let(::toChannelInfo)
    }

    /** Создаёт канал в сервере [serverId]. */
    fun addChannel(serverId: String, name: String, kind: String, description: String = ""): ChannelInfo {
        val cleanName = name.trim().ifEmpty { "новый-канал" }
        return transaction(database) {
            val id = UUID.randomUUID().toString()
            ChannelsTable.insert {
                it[ChannelsTable.id] = id
                it[ChannelsTable.name] = cleanName
                it[ChannelsTable.kind] = kind
                it[ChannelsTable.serverId] = serverId
                it[ChannelsTable.description] = description.trim()
            }
            ChannelInfo(id, cleanName, kind, description.trim())
        }
    }

    /** Канал вне сервера (обратная совместимость с прежним API и тестами). */
    fun addChannel(name: String, kind: String = CHANNEL_KIND_TEXT): ChannelInfo {
        val cleanName = name.trim().ifEmpty { "новый-канал" }
        return transaction(database) {
            val id = UUID.randomUUID().toString()
            ChannelsTable.insert {
                it[ChannelsTable.id] = id
                it[ChannelsTable.name] = cleanName
                it[ChannelsTable.kind] = kind
                it[ChannelsTable.description] = ""
            }
            ChannelInfo(id, cleanName, kind, "")
        }
    }

    /** Обновляет название, тип и описание канала. */
    fun updateChannel(id: String, name: String, kind: String, description: String) {
        transaction(database) {
            ChannelsTable.update({ ChannelsTable.id eq id }) {
                it[ChannelsTable.name] = name.trim().ifEmpty { "новый-канал" }
                it[ChannelsTable.kind] = kind
                it[ChannelsTable.description] = description.trim()
            }
        }
    }

    /** Удаляет канал вместе с сообщениями и записями файлов. */
    fun deleteChannel(id: String) {
        transaction(database) {
            MessagesTable.deleteWhere { MessagesTable.channelId eq id }
            FilesTable.deleteWhere { FilesTable.channelId eq id }
            ChannelsTable.deleteWhere { ChannelsTable.id eq id }
        }
    }

    // --- сообщения ---

    fun insertMessage(
        messageId: String,
        channelId: String?,
        authorNodeId: String,
        toNodeId: String?,
        text: String,
        timestamp: Long,
        delivered: Boolean = true,
    ): MessageInfo {
        transaction(database) {
            MessagesTable.insert {
                it[MessagesTable.messageId] = messageId
                it[MessagesTable.channelId] = channelId
                it[MessagesTable.authorNodeId] = authorNodeId
                it[MessagesTable.toNodeId] = toNodeId
                it[MessagesTable.text] = text
                it[MessagesTable.timestamp] = timestamp
                it[MessagesTable.delivered] = delivered
            }
        }
        return MessageInfo(messageId, channelId, authorNodeId, text, timestamp)
    }

    /** Исходящие DM для [peerNodeId], ожидающие доставки. */
    fun pendingOutboxFor(peerNodeId: String): List<MessageInfo> = transaction(database) {
        MessagesTable.selectAll()
            .where { (MessagesTable.toNodeId eq peerNodeId) and (MessagesTable.delivered eq false) }
            .orderBy(MessagesTable.id)
            .map(::toMessageInfo)
    }

    fun markAllDelivered(peerNodeId: String) {
        transaction(database) {
            MessagesTable.update({ (MessagesTable.toNodeId eq peerNodeId) and (MessagesTable.delivered eq false) }) {
                it[MessagesTable.delivered] = true
            }
        }
    }

    fun markDelivered(messageId: String) {
        transaction(database) {
            MessagesTable.update({ MessagesTable.messageId eq messageId }) {
                it[MessagesTable.delivered] = true
            }
        }
    }

    /** Переписка DM с [peerNodeId] (оба направления), от старых к новым. */
    fun dmConversation(peerNodeId: String): List<MessageInfo> = transaction(database) {
        MessagesTable.selectAll()
            .where {
                MessagesTable.channelId.isNull() and
                    ((MessagesTable.authorNodeId eq peerNodeId) or (MessagesTable.toNodeId eq peerNodeId))
            }
            .orderBy(MessagesTable.id)
            .map(::toMessageInfo)
    }

    /** Сообщения канала (история), от старых к новым. */
    fun channelMessages(channelId: String, limit: Int = 200): List<MessageInfo> = transaction(database) {
        MessagesTable.selectAll()
            .where { MessagesTable.channelId eq channelId }
            .orderBy(MessagesTable.id)
            .limit(limit)
            .map(::toMessageInfo)
    }

    // --- файлы ---

    /** Директория файлов узла: `<dataDir>/files`. */
    fun filesDir(): Path = dbPath.parent.resolve("files")

    /** Регистрирует исходящую передачу (локальный файл уже на диске). */
    fun startOutgoingFile(
        fileId: String,
        fileName: String,
        size: Long,
        sha256: String,
        channelId: String?,
        peerNodeId: String,
        sourcePath: String,
    ): FileRecord {
        transaction(database) {
            FilesTable.insert {
                it[FilesTable.fileId] = fileId
                it[FilesTable.fileName] = fileName
                it[FilesTable.size] = size
                it[FilesTable.sha256] = sha256
                it[FilesTable.channelId] = channelId
                it[FilesTable.peerNodeId] = peerNodeId
                it[FilesTable.direction] = FileDirection.Out.name
                it[FilesTable.status] = FileStatus.Transferring.name
                it[FilesTable.localPath] = sourcePath
                it[FilesTable.receivedBytes] = 0
                it[FilesTable.timestamp] = System.currentTimeMillis()
            }
        }
        return fileRecord(fileId) ?: error("файл не создан")
    }

    /**
     * Регистрирует входящую передачу; при повторном предложении того же fileId
     * учитывает уже принятые байты (докачка).
     */
    fun beginIncomingFile(
        fileId: String,
        fileName: String,
        size: Long,
        sha256: String,
        channelId: String?,
        authorNodeId: String,
    ): FileRecord {
        Files.createDirectories(filesDir())
        val temp = filesDir().resolve("$fileId.tmp")
        val offset = if (Files.exists(temp)) Files.size(temp) else 0L
        transaction(database) {
            FilesTable.upsert(FilesTable.fileId) {
                it[FilesTable.fileId] = fileId
                it[FilesTable.fileName] = fileName
                it[FilesTable.size] = size
                it[FilesTable.sha256] = sha256
                it[FilesTable.channelId] = channelId
                it[FilesTable.peerNodeId] = authorNodeId
                it[FilesTable.direction] = FileDirection.In.name
                it[FilesTable.status] = FileStatus.Transferring.name
                it[FilesTable.localPath] = temp.toString()
                it[FilesTable.receivedBytes] = offset
                it[FilesTable.timestamp] = System.currentTimeMillis()
            }
        }
        return fileRecord(fileId) ?: error("файл не создан")
    }

    /** Дописывает чанк и возвращает принятый объём байт. */
    fun appendChunk(fileId: String, data: ByteArray): Long {
        val record = fileRecord(fileId) ?: return 0L
        if (record.status != FileStatus.Transferring) return record.receivedBytes
        val path = Paths.get(record.localPath)
        try {
            Files.createDirectories(path.parent)
            Files.write(path, data, StandardOpenOption.APPEND, StandardOpenOption.CREATE)
        } catch (_: Exception) {
            return record.receivedBytes
        }
        val total = Files.size(path)
        updateFileProgress(fileId, total)
        return total
    }

    fun updateFileProgress(fileId: String, bytes: Long) {
        transaction(database) {
            FilesTable.update({ FilesTable.fileId eq fileId }) {
                it[FilesTable.receivedBytes] = bytes
            }
        }
    }

    /**
     * Завершает передачу: проверяет размер и SHA-256, переносит файл
     * в постоянную директорию. При несовпадении — удаляет и помечает ошибкой.
     */
    fun completeFile(fileId: String): FileRecord {
        val record = fileRecord(fileId) ?: error("файл не найден: $fileId")
        val temp = Paths.get(record.localPath)
        val valid = Files.exists(temp) && Files.size(temp) == record.size && sha256Of(temp) == record.sha256
        return if (valid) {
            val finalPath = filesDir().resolve(fileId)
            Files.move(temp, finalPath, StandardCopyOption.REPLACE_EXISTING)
            transaction(database) {
                FilesTable.update({ FilesTable.fileId eq fileId }) {
                    it[FilesTable.status] = FileStatus.Complete.name
                    it[FilesTable.localPath] = finalPath.toString()
                }
            }
            fileRecord(fileId) ?: error("файл не найден")
        } else {
            runCatching { Files.deleteIfExists(temp) }
            transaction(database) {
                FilesTable.update({ FilesTable.fileId eq fileId }) {
                    it[FilesTable.status] = FileStatus.Aborted.name
                    it[FilesTable.abortReason] = "Контрольная сумма не совпала"
                }
            }
            fileRecord(fileId) ?: error("файл не найден")
        }
    }

    /** Прерывает передачу и удаляет неполный файл. */
    fun abortFile(fileId: String, reason: String): FileRecord? {
        val record = fileRecord(fileId) ?: return null
        runCatching { Files.deleteIfExists(Paths.get(record.localPath)) }
        transaction(database) {
            FilesTable.update({ FilesTable.fileId eq fileId }) {
                it[FilesTable.status] = FileStatus.Aborted.name
                it[FilesTable.abortReason] = reason
            }
        }
        return fileRecord(fileId)
    }

    fun fileRecord(fileId: String): FileRecord? = transaction(database) {
        FilesTable.selectAll().where { FilesTable.fileId eq fileId }.firstOrNull()?.let(::toFileRecord)
    }

    /** Личные файлы переписки с [peerNodeId]. */
    fun filesForDm(peerNodeId: String): List<FileRecord> = transaction(database) {
        FilesTable.selectAll()
            .where { (FilesTable.channelId.isNull()) and (FilesTable.peerNodeId eq peerNodeId) }
            .orderBy(FilesTable.timestamp)
            .map(::toFileRecord)
    }

    /** Файлы канала [channelId]. */
    fun filesForChannel(channelId: String): List<FileRecord> = transaction(database) {
        FilesTable.selectAll()
            .where { FilesTable.channelId eq channelId }
            .orderBy(FilesTable.timestamp)
            .map(::toFileRecord)
    }

    // --- внутреннее ---

    private fun identityId(): Int = transaction(database) {
        IdentityTable.selectAll().firstOrNull()?.get(IdentityTable.id) ?: error("идентичность не создана")
    }

    private fun toServerInfo(row: ResultRow): ServerInfo = ServerInfo(
        id = row[ServersTable.id],
        name = row[ServersTable.name],
        description = row[ServersTable.description],
        avatarPath = row[ServersTable.avatarPath],
        bannerColor = row[ServersTable.bannerColor],
        joinLeaveEnabled = row[ServersTable.joinLeaveEnabled],
        joinLeaveChannelIds = row[ServersTable.joinLeaveChannels].split(',').filter { it.isNotBlank() },
        afkEnabled = row[ServersTable.afkEnabled],
        afkChannelId = row[ServersTable.afkChannelId],
        emojiAutocomplete = row[ServersTable.emojiAutocomplete],
        emojiConvert = row[ServersTable.emojiConvert],
    )

    private fun toChannelInfo(row: ResultRow): ChannelInfo = ChannelInfo(
        id = row[ChannelsTable.id],
        name = row[ChannelsTable.name],
        kind = row[ChannelsTable.kind],
        description = row[ChannelsTable.description],
    )

    private fun hashPassword(salt: String, password: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest("$salt:$password".toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun toFileRecord(row: ResultRow): FileRecord = FileRecord(
        fileId = row[FilesTable.fileId],
        fileName = row[FilesTable.fileName],
        size = row[FilesTable.size],
        sha256 = row[FilesTable.sha256],
        channelId = row[FilesTable.channelId],
        peerNodeId = row[FilesTable.peerNodeId],
        direction = FileDirection.valueOf(row[FilesTable.direction]),
        status = FileStatus.valueOf(row[FilesTable.status]),
        localPath = row[FilesTable.localPath],
        receivedBytes = row[FilesTable.receivedBytes],
        abortReason = row[FilesTable.abortReason],
        timestamp = row[FilesTable.timestamp],
    )

    private fun sha256Of(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun toMessageInfo(row: ResultRow): MessageInfo = MessageInfo(
        messageId = row[MessagesTable.messageId],
        channelId = row[MessagesTable.channelId],
        authorNodeId = row[MessagesTable.authorNodeId],
        text = row[MessagesTable.text],
        timestamp = row[MessagesTable.timestamp],
    )

    private fun settings(): Map<String, String> = transaction(database) {
        SettingsTable.selectAll().associate { it[SettingsTable.key] to it[SettingsTable.value] }
    }

    companion object {
        const val DEFAULT_DISPLAY_NAME: String = "Пользователь"
        private const val SETTING_PORT: String = "port"

        /**
         * Директория данных узла: `~/.devchats`, если не задана переменная
         * окружения `DEVCHATS_HOME` (позволяет запустить второй экземпляр
         * на той же машине с отдельной идентичностью — удобно для теста)
         * или системное свойство `devchats.home` (удобно при запуске из IDE:
         * `-Ddevchats.home=...` в конфигурации запуска).
         */
        fun defaultDataDir(): Path {
            val env = System.getenv("DEVCHATS_HOME")?.takeIf { it.isNotBlank() }
            val prop = System.getProperty("devchats.home")?.takeIf { it.isNotBlank() }
            return when {
                env != null -> Paths.get(env)
                prop != null -> Paths.get(prop)
                else -> Paths.get(System.getProperty("user.home"), ".devchats")
            }
        }

        /** Открывает хранилище по умолчанию, создавая директорию при необходимости. */
        fun openDefault(): NodeStore {
            val dir = defaultDataDir()
            Files.createDirectories(dir)
            return NodeStore(dir.resolve("devchats.db"))
        }
    }
}
