package com.aiaixyz.minemic

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.media.*
import android.net.nsd.*
import android.os.*
import androidx.core.content.ContextCompat
import com.aiaixyz.minemic.connection.*
import java.net.*
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class AndroidPhonePlatform(private val context: Context) : PhonePlatform {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val network = Executors.newSingleThreadExecutor { Thread(it, "MineMic-control").apply { isDaemon = true } }
    private val generation = AtomicInteger()
    @Volatile private var tcp: Socket? = null
    @Volatile private var udp: DatagramSocket? = null
    private var audioFailure: ((String) -> Unit)? = null
    private var listener: NsdManager.DiscoveryListener? = null
    private var discoveryEpoch = 0
    private val recording = AtomicBoolean(false)
    private val captureGeneration = AtomicInteger()
    @Volatile private var recorder: AudioRecord? = null
    private var captureThread: Thread? = null
    private val authenticator = HmacSha256()
    override fun monotonicMicros() = System.nanoTime() / 1000
    override fun deviceName() = Build.MODEL.take(64)
    override fun randomBytes(count: Int) = ByteArray(count).also { SecureRandom().nextBytes(it) }
    override fun hmac(body: ByteArray, key: ByteArray) = authenticator.authenticate(body, key)
    @Suppress("DEPRECATION")
    override fun discover(onFound: (LanComputer) -> Unit, onError: (String) -> Unit) {
        main.post {
            stopDiscoveryOnMain()
            val epoch = discoveryEpoch
            val queued = ArrayDeque<NsdServiceInfo>()
            var resolving = false
            fun resolveNext() {
                if (epoch != discoveryEpoch || resolving || queued.isEmpty()) return
                resolving = true
                nsd.resolveService(queued.removeFirst(), object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, error: Int) { main.post { resolving = false; resolveNext() } }
                    override fun onServiceResolved(info: NsdServiceInfo) { main.post {
                        if (epoch == discoveryEpoch) {
                            val host = info.host?.hostAddress
                            if (host != null && Wire.validIpv4(host)) onFound(LanComputer(host, info.port, info.serviceName))
                        }
                        resolving = false; resolveNext()
                    } }
                })
            }
            val value = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(type: String) {}
                override fun onDiscoveryStopped(type: String) {}
                override fun onStopDiscoveryFailed(type: String, code: Int) {}
                override fun onStartDiscoveryFailed(type: String, code: Int) { if (epoch == discoveryEpoch) onError("无法发现局域网 PC：$code") }
                override fun onServiceLost(info: NsdServiceInfo) {}
                override fun onServiceFound(info: NsdServiceInfo) { main.post {
                    if (epoch == discoveryEpoch && queued.size < 16 && info.serviceType.contains("_minemic._tcp")) {
                        queued.addLast(info); resolveNext()
                    }
                } }
            }
            listener = value
            try { nsd.discoverServices("_minemic._tcp.", NsdManager.PROTOCOL_DNS_SD, value) }
            catch (e: Exception) { onError(e.message ?: "发现失败") }
        }
    }
    private fun stopDiscoveryOnMain() {
        discoveryEpoch++
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }; listener = null
    }
    override fun stopDiscovery() { main.post { stopDiscoveryOnMain() } }
    override fun openControl(host: String, port: Int, onReady: () -> Unit, onLine: (String) -> Unit, onClosed: () -> Unit, onError: (String) -> Unit) {
        closeControl()
        val epoch = generation.get()
        // One reader per live attempt; closing the socket interrupts reads immediately.
        Thread({
            val socket = Socket()
            try {
                synchronized(this) {
                    if (epoch != generation.get()) { socket.close(); return@Thread }
                    tcp = socket
                }
                socket.tcpNoDelay = true; socket.connect(InetSocketAddress(host, port), 2500)
                if (epoch != generation.get()) return@Thread
                onReady()
                val input = socket.getInputStream(); val line = ByteArrayOutputStream(1024)
                while (epoch == generation.get()) {
                    val byte = input.read()
                    if (byte < 0) break
                    if (byte == 10) { onLine(line.toByteArray().toString(Charsets.UTF_8)); line.reset() }
                    else { if (line.size() >= 1024) error("PC 响应过长"); line.write(byte) }
                }
                if (epoch == generation.get()) onClosed()
            } catch (e: Exception) { if (epoch == generation.get()) onError(e.message ?: "连接异常") }
            finally { runCatching { socket.close() } }
        }, "MineMic-reader").apply { isDaemon = true; start() }
    }
    override fun sendControl(line: String) {
        val socket = tcp; val epoch = generation.get()
        network.execute { if (epoch == generation.get()) runCatching { socket?.getOutputStream()?.write((line + "\n").toByteArray()) } }
    }
    @Synchronized override fun closeControl() { generation.incrementAndGet(); val old = tcp; tcp = null; runCatching { old?.close() } }
    override fun finishControl(line: String, timeoutMillis: Int) {
        val old = synchronized(this) { generation.incrementAndGet(); tcp.also { tcp = null } } ?: return
        main.postDelayed({ runCatching { old.close() } }, timeoutMillis.coerceIn(1, 200).toLong())
        network.execute {
            try { old.getOutputStream().write((line + "\n").toByteArray()); old.shutdownOutput() }
            catch (_: Exception) { }
            finally { runCatching { old.close() } }
        }
    }
    override fun openAudio(host: String, port: Int, onReference: (ByteArray) -> Unit, onError: (String) -> Unit) {
        closeAudio()
        val socket = try {
            DatagramSocket().also { value ->
                try { value.connect(InetAddress.getByName(host), port); value.receiveBufferSize = 32768 }
                catch (e: Exception) { value.close(); throw e }
            }
        } catch (e: Exception) { onError(e.message ?: "音频通道打开失败"); return }
        synchronized(this) { udp = socket; audioFailure = onError }
        Thread({
            val storage = ByteArray(1201)
            while (!socket.isClosed) {
                try {
                    val packet = DatagramPacket(storage, storage.size); socket.receive(packet)
                    if (udp === socket && packet.length <= 1200) onReference(storage.copyOf(packet.length))
                } catch (e: Exception) { failAudio(socket, e); break }
            }
        }, "MineMic-reference").apply { isDaemon = true; start() }
    }
    override fun sendAudio(bytes: ByteArray) {
        val socket = udp ?: return
        try { socket.send(DatagramPacket(bytes, bytes.size)) }
        catch (e: Exception) { failAudio(socket, e) }
    }
    private fun failAudio(socket: DatagramSocket, error: Exception) {
        val callback = synchronized(this) {
            if (udp !== socket) return
            udp = null
            audioFailure.also { audioFailure = null }
        }
        socket.close(); callback?.invoke(error.message ?: "音频通道异常")
    }
    override fun closeAudio() {
        val old = synchronized(this) { val old = udp; udp = null; audioFailure = null; old }
        old?.close(); authenticator.clear()
    }
    @Synchronized override fun startAudio(onFrame: (CapturedAudio) -> Unit, onError: (String) -> Unit, onState: (Boolean, String) -> Unit) {
        stopAudio()
        val epoch = captureGeneration.get()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onError("请先允许麦克风权限"); return
        }
        try { ContextCompat.startForegroundService(context, Intent(context, MicForegroundService::class.java)) }
        catch (e: Exception) { onError("请回到前台启用麦克风：${e.message}"); return }
        recording.set(true)
        captureThread = Thread({
            var audio: AudioRecord? = null
            try {
                val minimum = AudioRecord.getMinBufferSize(48000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                check(minimum > 0) { "设备不支持 48 kHz 录音" }
                audio = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 48000, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, 3840))
                synchronized(this) {
                    if (epoch != captureGeneration.get()) return@Thread
                    recorder = audio
                }
                check(audio.state == AudioRecord.STATE_INITIALIZED) { "录音设备初始化失败" }
                audio.startRecording()
                check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "麦克风被其他应用占用" }
                onState(true, "手机麦克风正在传输")
                val frame = ByteArray(960); var offset = 0; var totalFrames = 0L
                val timestamp = AudioTimestamp()
                while (recording.get() && epoch == captureGeneration.get()) {
                    val count = audio.read(frame, offset, frame.size - offset, AudioRecord.READ_BLOCKING)
                    if (!recording.get() || epoch != captureGeneration.get()) break
                    check(count > 0) { "录音读取失败：$count" }
                    offset += count
                    if (offset == frame.size) {
                        totalFrames += 480
                        val captured = if (audio.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS)
                            timestamp.nanoTime / 1000 + (totalFrames - 480 - timestamp.framePosition) * 1000000 / 48000
                        else 0L // NS still works; do not align AEC using an invented hardware timestamp.
                        onFrame(CapturedAudio(frame.copyOf(), captured)); offset = 0
                    }
                }
            } catch (e: Exception) { synchronized(this) { if (epoch == captureGeneration.get() && recording.getAndSet(false)) onError(e.message ?: "录音失败") } }
            finally {
                runCatching { audio?.stop() }; audio?.release()
                synchronized(this) {
                    if (recorder === audio) recorder = null
                    if (epoch == captureGeneration.get()) context.stopService(Intent(context, MicForegroundService::class.java))
                }
            }
        }, "MineMic-microphone").apply { start() }
    }
    override fun stopAudio() {
        val old = synchronized(this) {
            recording.set(false); captureGeneration.incrementAndGet(); captureThread = null
            context.stopService(Intent(context, MicForegroundService::class.java))
            recorder.also { recorder = null }
        }
        // Stop interrupts a blocking read. The capture thread alone releases its
        // recorder; an old completion cannot stop a later session's service.
        if (old != null) Thread({ runCatching { old.stop() } }, "MineMic-stop-capture").apply { isDaemon = true; start() }
    }
}
