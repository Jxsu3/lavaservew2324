/*
 * Copyright (c) 2017 Frederik Ar. Mikkelsen & NoobLance
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package lavalink.server.io

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import lavalink.server.config.ServerConfig
import lavalink.server.player.Player
import moe.kyokobot.koe.Koe
import moe.kyokobot.koe.KoeOptions
import org.json.JSONObject
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.TextWebSocketHandler
import java.util.*
import java.util.concurrent.ConcurrentHashMap

@Service
class SocketServer(
        private val serverConfig: ServerConfig,
        private val audioPlayerManager: AudioPlayerManager,
        koeOptions: KoeOptions
) : TextWebSocketHandler() {

    // userId <-> shardCount
    private val shardCounts = ConcurrentHashMap<String, Int>()
    val contextMap = HashMap<String, SocketContext>()
    @Suppress("LeakingThis")
    private val handlers = WebSocketHandlers(contextMap)
    private val resumableSessions = mutableMapOf<String, SocketContext>()
    private val koe = Koe.koe(koeOptions)

    companion object {
        private val log = LoggerFactory.getLogger(SocketServer::class.java)

        fun sendPlayerUpdate(socketContext: SocketContext, player: Player) {
            val json = JSONObject()
            json.put("op", "playerUpdate")
            json.put("guildId", player.guildId)
            json.put("state", player.state)

            socketContext.send(json)
        }
    }

    val contexts: Collection<SocketContext>
        get() = contextMap.values

    override fun afterConnectionEstablished(session: WebSocketSession) {
        val shardCount = Integer.parseInt(session.handshakeHeaders.getFirst("Num-Shards")!!)
        val userId = session.handshakeHeaders.getFirst("User-Id")!!
        val resumeKey = session.handshakeHeaders.getFirst("Resume-Key")

        shardCounts[userId] = shardCount

        var resumable: SocketContext? = null
        if (resumeKey != null) resumable = resumableSessions.remove(resumeKey)

        if (resumable != null) {
            contextMap[session.id] = resumable
            resumable.resume(session)
            log.info("Resumed session with key $resumeKey")
            return
        }

        shardCounts[userId] = shardCount

        contextMap[session.id] = SocketContext(
                audioPlayerManager,
                session,
                this,
                userId,
                koe.newClient(userId.toLong())
        )
        log.info("Connection successfully established from " + session.remoteAddress!!)
    }

    override fun afterConnectionClosed(session: WebSocketSession?, status: CloseStatus?) {
        val context = contextMap.remove(session!!.id) ?: return
        if (context.resumeKey != null) {
            resumableSessions.remove(context.resumeKey!!)?.let { removed ->
                log.warn("Shutdown resumable session with key ${removed.resumeKey} because it has the same key as a " +
                        "newly disconnected resumable session.")
                removed.shutdown()
            }

            resumableSessions[context.resumeKey!!] = context
            context.pause()
            log.info("Connection closed from {} with status {} -- " +
                    "Session can be resumed within the next {} seconds with key {}",
                    session.remoteAddress,
                    status,
                    context.resumeTimeout,
                    context.resumeKey
            )
            return
        }

        log.info("Connection closed from {} -- {}", session.remoteAddress, status)

        context.shutdown()
    }

    override fun handleTextMessage(session: WebSocketSession?, message: TextMessage?) {
        try {
            handleTextMessageSafe(session!!, message!!)
        } catch (e: Exception) {
            log.error("Exception while handling websocket message", e)
        }

    }

    private fun handleTextMessageSafe(session: WebSocketSession, message: TextMessage) {
        val json = JSONObject(message.payload)

        log.info(message.payload)

        if (!session.isOpen) {
            log.error("Ignoring closing websocket: " + session.remoteAddress!!)
            return
        }

        val context = contextMap[session.id]
                ?: throw IllegalStateException("No context for session ID ${session.id}. Broken websocket?")

        when (json.getString("op")) {
            // @formatter:off
            "voiceUpdate"       -> handlers.voiceUpdate(context, json)
            "play"              -> handlers.play(context, json)
            "stop"              -> handlers.stop(context, json)
            "pause"             -> handlers.pause(context, json)
            "seek"              -> handlers.seek(context, json)
            "volume"            -> handlers.volume(context, json)
            "destroy"           -> handlers.destroy(context, json)
            "configureResuming" -> handlers.configureResuming(context, json)
            "equalizer"         -> handlers.equalizer(context, json)
            else                -> log.warn("Unexpected operation: " + json.getString("op"))
            // @formatter:on
        }
    }

    internal fun onSessionResumeTimeout(context: SocketContext) {
        resumableSessions.remove(context.resumeKey)
        context.shutdown()
    }

    internal fun canResume(key: String) = resumableSessions[key]?.stopResumeTimeout() ?: false
}