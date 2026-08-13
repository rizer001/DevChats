package devchats.protocol

import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EnvelopeTest {

    @Test
    fun helloRoundtrip() {
        val original = Envelope(
            id = "id-1",
            payload = Hello(nodeId = "node-1", displayName = "Alice"),
        )
        val json = DevChatsJson.encodeToString(Envelope.serializer(), original)
        val decoded = DevChatsJson.decodeFromString(Envelope.serializer(), json)
        assertEquals(original, decoded)
    }

    @Test
    fun helloSerializesWithTypeDiscriminator() {
        val json = DevChatsJson.encodeToString(
            Envelope.serializer(),
            Envelope(id = "id-2", payload = Hello(nodeId = "node-2", displayName = "Bob")),
        )
        assertTrue("\"type\":\"hello\"" in json, "ожидали дискриминатор type=hello, получили: $json")
        assertTrue("\"v\":1" in json, "ожидали поле v, получили: $json")
    }

    @Test
    fun dmSendRoundtrip() {
        val original = Envelope(
            id = "id-3",
            payload = DmSend(messageId = "m-1", to = "node-9", text = "Привет!", timestamp = 1_700_000_000_000),
        )
        val json = DevChatsJson.encodeToString(Envelope.serializer(), original)
        val decoded = DevChatsJson.decodeFromString(Envelope.serializer(), json)
        assertEquals(original, decoded)
    }

    @Test
    fun helloAckRoundtrip() {
        val original = Envelope(
            id = "id-4",
            payload = HelloAck(nodeId = "node-b", displayName = "Bob", accepted = false, reason = "pending"),
        )
        val json = DevChatsJson.encodeToString(Envelope.serializer(), original)
        assertTrue("\"type\":\"hello.ack\"" in json, json)
        val decoded = DevChatsJson.decodeFromString(Envelope.serializer(), json)
        assertEquals(original, decoded)
    }

    @Test
    fun presenceRoundtrip() {
        val original = Envelope(id = "id-5", payload = Presence(nodeId = "node-a", online = true))
        val decoded = DevChatsJson.decodeFromString(
            Envelope.serializer(),
            DevChatsJson.encodeToString(Envelope.serializer(), original),
        )
        assertEquals(original, decoded)
    }

    @Test
    fun msgSendRoundtrip() {
        val original = Envelope(id = "id-6", payload = MsgSend("m-1", "ch-1", "node-a", "привет канал", 1_700_000_000_000L))
        val json = DevChatsJson.encodeToString(Envelope.serializer(), original)
        assertTrue("\"type\":\"msg.send\"" in json, json)
        assertEquals(original, DevChatsJson.decodeFromString(Envelope.serializer(), json))
    }

    @Test
    fun mailboxSyncAndChannelListRoundtrip() {
        for (payload in listOf<Message>(MailboxSync, ChannelListRequest)) {
            val original = Envelope(id = "id-7", payload = payload)
            assertEquals(original, DevChatsJson.decodeFromString(Envelope.serializer(), DevChatsJson.encodeToString(Envelope.serializer(), original)))
        }
    }

    @Test
    fun channelListItemsRoundtrip() {
        val original = Envelope(
            id = "id-8",
            payload = ChannelListItems(listOf(ChannelInfo("c1", "general"), ChannelInfo("c2", "помощь", "text"))),
        )
        assertEquals(original, DevChatsJson.decodeFromString(Envelope.serializer(), DevChatsJson.encodeToString(Envelope.serializer(), original)))
    }

    @Test
    fun msgHistoryItemsRoundtrip() {
        val original = Envelope(
            id = "id-9",
            payload = MsgHistoryItems("c1", listOf(MessageInfo("m-1", "c1", "node-a", "текст", 123L))),
        )
        assertEquals(original, DevChatsJson.decodeFromString(Envelope.serializer(), DevChatsJson.encodeToString(Envelope.serializer(), original)))
    }

    @Test
    fun fileMessagesRoundtrip() {
        val offer = Envelope(id = "id-10", payload = FileOffer("f-1", "photo.png", 1024L, "deadbeef", null, "node-a"))
        val done = Envelope(id = "id-12", payload = FileDone("f-1"))
        val abort = Envelope(id = "id-13", payload = FileAbort("f-1", "прервано"))
        val accept = Envelope(id = "id-14", payload = FileAccept("f-1", offset = 512L))

        for (env in listOf(offer, done, abort, accept)) {
            val decoded = DevChatsJson.decodeFromString(Envelope.serializer(), DevChatsJson.encodeToString(Envelope.serializer(), env))
            assertEquals(env, decoded)
        }

        // чанк: ByteArray нельзя сравнивать через assertEquals (сравнение ссылок)
        val chunk = Envelope(id = "id-11", payload = FileChunk("f-1", 0, byteArrayOf(1, 2, 3)))
        val decodedChunk = DevChatsJson.decodeFromString(
            Envelope.serializer(),
            DevChatsJson.encodeToString(Envelope.serializer(), chunk),
        ).payload as FileChunk
        assertEquals("f-1", decodedChunk.fileId)
        assertEquals(0, decodedChunk.index)
        assertContentEquals(byteArrayOf(1, 2, 3), decodedChunk.data)
    }

    @Test
    fun chunkDataIsBase64() {
        val json = DevChatsJson.encodeToString(
            Envelope.serializer(),
            Envelope(id = "id-15", payload = FileChunk("f-1", 0, byteArrayOf(1, 2, 3))),
        )
        assertTrue(json.contains("\"data\":\"AQID\""), json)
    }

    @Test
    fun callMessagesRoundtrip() {
        val offer = Envelope(id = "c1", payload = CallOffer("call-1", "node-b", "node-a", "Alice"))
        val accept = Envelope(id = "c2", payload = CallAccept("call-1", "node-a", "node-b"))
        val reject = Envelope(id = "c3", payload = CallReject("call-1", "node-a", "node-b", "занят"))
        val hangup = Envelope(id = "c4", payload = CallHangup("call-1", "node-a", "node-b"))

        for (env in listOf(offer, accept, reject, hangup)) {
            val decoded = DevChatsJson.decodeFromString(Envelope.serializer(), DevChatsJson.encodeToString(Envelope.serializer(), env))
            assertEquals(env, decoded)
        }
    }

    @Test
    fun callAudioRoundtripWithBase64() {
        val json = DevChatsJson.encodeToString(
            Envelope.serializer(),
            Envelope(id = "c5", payload = CallAudio("call-1", "node-a", "node-b", seq = 7, data = byteArrayOf(1, 2, 3))),
        )
        assertTrue("\"type\":\"call.audio\"" in json, json)
        assertTrue(json.contains("\"data\":\"AQID\""), json)

        val decoded = DevChatsJson.decodeFromString(Envelope.serializer(), json).payload as CallAudio
        assertEquals("call-1", decoded.callId)
        assertEquals(7, decoded.seq)
        assertContentEquals(byteArrayOf(1, 2, 3), decoded.data)
    }

    @Test
    fun videoMessagesRoundtrip() {
        val start = Envelope(id = "v1", payload = VideoStart("call-1", "node-b", "node-a", VIDEO_KIND_CAMERA, 640, 360))
        val stop = Envelope(id = "v3", payload = VideoStop("call-1", "node-b", "node-a", VIDEO_KIND_SCREEN))

        for (env in listOf(start, stop)) {
            assertEquals(env, DevChatsJson.decodeFromString(Envelope.serializer(), DevChatsJson.encodeToString(Envelope.serializer(), env)))
        }

        val json = DevChatsJson.encodeToString(
            Envelope.serializer(),
            Envelope(id = "v2", payload = VideoFrame("call-1", "node-b", "node-a", seq = 3, kind = VIDEO_KIND_CAMERA, data = byteArrayOf(0x10, 0x20))),
        )
        assertTrue("\"type\":\"video.frame\"" in json, json)
        assertTrue(json.contains("\"data\":\"ECA=\""), json)
        val decoded = DevChatsJson.decodeFromString(Envelope.serializer(), json).payload as VideoFrame
        assertEquals(3, decoded.seq)
        assertEquals(VIDEO_KIND_CAMERA, decoded.kind)
        assertContentEquals(byteArrayOf(0x10, 0x20), decoded.data)
    }

    @Test
    fun unknownMessageTypeFailsToDecode() {
        val json = """{"v":1,"id":"x","payload":{"type":"unknown","a":1}}"""
        assertFailsWith<SerializationException> {
            DevChatsJson.decodeFromString(Envelope.serializer(), json)
        }
    }
}
