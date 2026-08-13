package devchats.server

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NodeStoreTest {

    @Test
    fun createsIdentityOnFirstRun() {
        withTempDb { dbPath ->
            val store = NodeStore(dbPath)
            val config = store.loadOrCreateConfig()

            assertTrue(config.nodeId.isNotBlank(), "nodeId должен создаваться автоматически")
            assertEquals(NodeStore.DEFAULT_DISPLAY_NAME, config.displayName)
            assertEquals(Node.DEFAULT_PORT, config.port)
        }
    }

    @Test
    fun identityIsStableAcrossReopens() {
        withTempDb { dbPath ->
            val first = NodeStore(dbPath).loadOrCreateConfig()
            // повторное открытие той же БД — тот же nodeId
            val second = NodeStore(dbPath).loadOrCreateConfig()

            assertEquals(first.nodeId, second.nodeId)
            assertEquals(first.displayName, second.displayName)
        }
    }

    @Test
    fun dmConversationRoundtrip() {
        withTempDb { dbPath ->
            val store = NodeStore(dbPath)
            val me = "node-me"
            val peer = "node-peer"

            store.insertMessage("m1", null, me, peer, "привет", 100L, delivered = false)
            store.insertMessage("m2", null, peer, me, "привет!", 200L)

            val conversation = store.dmConversation(peer)
            assertEquals(listOf("m1", "m2"), conversation.map { it.messageId })
            assertEquals(null, conversation[0].channelId)
            assertEquals(peer, conversation[1].authorNodeId)
        }
    }

    @Test
    fun outboxLifecycle() {
        withTempDb { dbPath ->
            val store = NodeStore(dbPath)
            val me = "node-me"
            val peer = "node-peer"

            store.insertMessage("m1", null, me, peer, "офлайн-сообщение", 100L, delivered = false)
            store.insertMessage("m2", null, me, peer, "другое", 200L, delivered = false)

            assertEquals(listOf("m1", "m2"), store.pendingOutboxFor(peer).map { it.messageId })

            store.markAllDelivered(peer)
            assertTrue(store.pendingOutboxFor(peer).isEmpty(), "outbox должен опустеть после доставки")
        }
    }

    @Test
    fun channelsAndHistory() {
        withTempDb { dbPath ->
            val store = NodeStore(dbPath)
            val channel = store.addChannel("general", "text")

            store.insertMessage("c1", channel.id, "node-a", null, "первое", 100L)
            store.insertMessage("c2", channel.id, "node-b", null, "второе", 200L)

            assertEquals(listOf(channel), store.channels())
            assertEquals(listOf("c1", "c2"), store.channelMessages(channel.id).map { it.messageId })
            assertEquals(1, store.channelMessages(channel.id, limit = 1).size)
        }
    }

    @Test
    fun fileTransferLifecycle() {
        withTempDb { dbPath ->
            val store = NodeStore(dbPath)
            val content = "hello file".toByteArray()
            val sha = sha256Hex(content)
            val fileId = "f-1"

            val record = store.beginIncomingFile(fileId, "test.txt", content.size.toLong(), sha, null, "node-a")
            assertEquals(FileStatus.Transferring, record.status)
            assertEquals(0L, record.receivedBytes)

            store.appendChunk(fileId, content.copyOfRange(0, 5))
            store.appendChunk(fileId, content.copyOfRange(5, content.size))

            val completed = store.completeFile(fileId)
            assertEquals(FileStatus.Complete, completed.status)
            assertEquals(String(content), java.nio.file.Files.readString(java.nio.file.Paths.get(completed.localPath)))

            assertEquals(listOf(fileId), store.filesForDm("node-a").map { it.fileId })
        }
    }

    @Test
    fun corruptedFileIsAborted() {
        withTempDb { dbPath ->
            val store = NodeStore(dbPath)
            val content = "correct data".toByteArray()
            val wrongSha = sha256Hex("other data".toByteArray())
            val fileId = "f-bad"

            store.beginIncomingFile(fileId, "bad.txt", content.size.toLong(), wrongSha, null, "node-a")
            store.appendChunk(fileId, content)

            val aborted = store.completeFile(fileId)
            assertEquals(FileStatus.Aborted, aborted.status)
            assertTrue(!java.nio.file.Files.exists(java.nio.file.Paths.get(aborted.localPath)), "неполный файл должен быть удалён")
        }
    }

    @Test
    fun registerAndLogin() {
        withTempDb { dbPath ->
            val store = NodeStore(dbPath)
            assertTrue(!store.hasAccount(), "аккаунта ещё нет")

            val config = store.register("alice", "secret123", "Алиса")
            assertTrue(config.nodeId.isNotBlank())
            assertEquals("Алиса", config.displayName)
            assertTrue(store.hasAccount())

            assertEquals(null, store.login("alice", "wrong"), "неверный пароль")
            assertEquals(null, store.login("bob", "secret123"), "неверный логин")
            val logged = store.login("alice", "secret123")
            assertEquals(config.nodeId, logged?.nodeId)

            assertFailsWith<IllegalStateException> { store.register("bob", "secret123", "Боб") }
        }
    }

    @Test
    fun avatarIsSavedToDataDir() {
        withTempDb { dbPath ->
            val store = NodeStore(dbPath)
            store.register("alice", "secret123", "Алиса")

            val avatarSource = dbPath.parent.resolve("my-avatar.png")
            java.nio.file.Files.write(avatarSource, byteArrayOf(1, 2, 3, 4))
            val saved = store.saveAvatar(avatarSource.toString())

            assertTrue(java.nio.file.Files.exists(java.nio.file.Paths.get(saved)), "аватар должен скопироваться")
            store.setAvatar(saved)
            assertEquals(saved, store.avatarPath())
        }
    }

    @Test
    fun serverLifecycle() {
        withTempDb { dbPath ->
            val store = NodeStore(dbPath)
            assertTrue(store.servers().isEmpty(), "по умолчанию серверов нет")

            val srv = store.addServer("Мой сервер", "Описание")
            assertEquals(listOf("Мой сервер"), store.servers().map { it.name })

            store.updateServer(srv.id, "Новое имя", "Новое описание")
            assertEquals("Новое имя", store.servers().single().name)
            assertEquals("Новое описание", store.servers().single().description)

            store.deleteServer(srv.id)
            assertTrue(store.servers().isEmpty())
        }
    }

    @Test
    fun serverChannelsWithKinds() {
        withTempDb { dbPath ->
            val store = NodeStore(dbPath)
            val srv = store.addServer("Сервер")

            val text = store.addChannel(srv.id, "общий", "text", "для всех")
            val voice = store.addChannel(srv.id, "голос", "voice")
            assertEquals(setOf("общий", "голос"), store.channels(srv.id).map { it.name }.toSet())

            store.updateChannel(text.id, "переименован", "announcements", "новое")
            val updated = store.channel(text.id)
            assertEquals("переименован", updated?.name)
            assertEquals("announcements", updated?.kind)
            assertEquals("новое", updated?.description)

            store.insertMessage("c1", text.id, "node-a", null, "первое", 100L)
            store.deleteChannel(text.id)
            assertTrue(store.channels(srv.id).none { it.id == text.id })
            assertTrue(store.channelMessages(text.id).isEmpty(), "сообщения канала удалены")
            assertTrue(store.channels(srv.id).any { it.id == voice.id })
        }
    }

    @Test
    fun deleteServerRemovesItsChannels() {
        withTempDb { dbPath ->
            val store = NodeStore(dbPath)
            val srv = store.addServer("Сервер")
            store.addChannel(srv.id, "общий", "text")
            store.addChannel(srv.id, "голос", "voice")

            store.deleteServer(srv.id)
            assertTrue(store.servers().isEmpty())
            assertEquals(0, store.channels().size)
        }
    }

    @Test
    fun serverConfigRoundtrip() {
        withTempDb { dbPath ->
            val store = NodeStore(dbPath)
            val srv = store.addServer("Сервер")
            val voice = store.addChannel(srv.id, "голос", "voice")
            val text = store.addChannel(srv.id, "общий", "text")

            store.updateServerConfig(
                id = srv.id,
                bannerColor = "#E91E63",
                joinLeaveEnabled = true,
                joinLeaveChannelIds = listOf(voice.id),
                afkEnabled = true,
                afkChannelId = text.id,
                emojiAutocomplete = false,
                emojiConvert = false,
            )

            val loaded = store.server(srv.id)!!
            assertEquals("#E91E63", loaded.bannerColor)
            assertEquals(true, loaded.joinLeaveEnabled)
            assertEquals(listOf(voice.id), loaded.joinLeaveChannelIds)
            assertEquals(true, loaded.afkEnabled)
            assertEquals(text.id, loaded.afkChannelId)
            assertEquals(false, loaded.emojiAutocomplete)
            assertEquals(false, loaded.emojiConvert)
        }
    }

    @Test
    fun emojiLifecycle() {
        withTempDb { dbPath ->
            val store = NodeStore(dbPath)
            val srv = store.addServer("Сервер")

            val emoji = store.addEmoji(srv.id, ":test:", "/img/test.png")
            assertEquals("test", emoji.name, "имя эмодзи без двоеточий")
            assertEquals(listOf("test"), store.serverEmojis(srv.id).map { it.name })

            store.deleteEmoji(emoji.id)
            assertTrue(store.serverEmojis(srv.id).isEmpty())
        }
    }

    @Test
    fun soundLifecycle() {
        withTempDb { dbPath ->
            val store = NodeStore(dbPath)
            val srv = store.addServer("Сервер")

            val sound = store.addSound(srv.id, "аплодисменты", "/snd/applause.wav", "F1")
            assertEquals("F1", sound.keyBind)

            store.updateSound(sound.id, "F2", 100L, 2000L)
            val updated = store.serverSounds(srv.id).single()
            assertEquals("F2", updated.keyBind)
            assertEquals(100L, updated.trimStartMs)
            assertEquals(2000L, updated.trimEndMs)

            store.deleteSound(sound.id)
            assertTrue(store.serverSounds(srv.id).isEmpty())
        }
    }

    @Test
    fun roleLifecycleAndOrdering() {
        withTempDb { dbPath ->
            val store = NodeStore(dbPath)
            val srv = store.addServer("Сервер")

            val mod = store.addRole(srv.id, "Модератор")
            val admin = store.addRole(srv.id, "Админ")
            // верхняя — та, что добавлена позже (position больше)
            assertEquals(listOf("Админ", "Модератор"), store.serverRoles(srv.id).map { it.name })

            store.updateRole(admin.id, "Админ", "#E91E63", showSeparately = true, mentionable = true)
            val updated = store.serverRoles(srv.id).first { it.id == admin.id }
            assertEquals("#E91E63", updated.color)
            assertEquals(true, updated.showSeparately)
            assertEquals(true, updated.mentionable)

            // перестановка: модератор выше админа
            store.reorderRoles(srv.id, listOf(mod.id, admin.id))
            assertEquals(listOf("Модератор", "Админ"), store.serverRoles(srv.id).map { it.name })

            store.deleteRole(mod.id)
            assertEquals(listOf("Админ"), store.serverRoles(srv.id).map { it.name })
        }
    }

    @Test
    fun deleteServerRemovesEmojisSoundsRoles() {
        withTempDb { dbPath ->
            val store = NodeStore(dbPath)
            val srv = store.addServer("Сервер")
            store.addEmoji(srv.id, "test", "/img/test.png")
            store.addSound(srv.id, "звук", "/snd/s.wav", "F1")
            store.addRole(srv.id, "Роль")

            store.deleteServer(srv.id)
            assertTrue(store.serverEmojis(srv.id).isEmpty())
            assertTrue(store.serverSounds(srv.id).isEmpty())
            assertTrue(store.serverRoles(srv.id).isEmpty())
        }
    }

    @Test
    fun peerLifecycle() {
        withTempDb { dbPath ->
            val store = NodeStore(dbPath)

            assertNull(store.peerStatus("node-x"), "неизвестный пир")

            store.upsertPeer(Peer("node-x", "X", "10.0.0.1", PeerStatus.Pending))
            assertEquals(PeerStatus.Pending, store.peerStatus("node-x"))

            store.setPeerStatus("node-x", PeerStatus.Accepted)
            assertEquals(PeerStatus.Accepted, store.peerStatus("node-x"))
            assertEquals(listOf("node-x"), store.peersByStatus(PeerStatus.Accepted).map { it.nodeId })

            store.deletePeer("node-x")
            assertNull(store.peerStatus("node-x"))
        }
    }

    private fun sha256Hex(data: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(data).joinToString("") { "%02x".format(it) }
    }

    private fun withTempDb(block: (java.nio.file.Path) -> Unit) {
        val dir = createTempDirectory("devchats-test")
        try {
            block(dir.resolve("test.db"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
