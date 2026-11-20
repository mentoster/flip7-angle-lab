package com.mentoster.flip7anglelab.fold

import android.content.Context
import android.util.Log
import android.os.SystemClock
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.cert.KadbCert
import com.flyfishxu.kadb.cert.OkioFilePrivateKeyStore
import com.flyfishxu.kadb.mdns.KadbMdnsAndroid
import com.flyfishxu.kadb.mdns.MdnsConfig
import com.flyfishxu.kadb.mdns.MdnsEndpoint
import com.flyfishxu.kadb.mdns.MdnsServiceType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okio.Path.Companion.toPath
import java.util.concurrent.atomic.AtomicBoolean

data class SamsungAngleSample(
    val angle: Float,
    val timestampNanos: Long,
)

/**
 * Exact Galaxy Z Flip7 reference angle, preferred in this order:
 * direct sysfs -> SensorsHub logcat with READ_LOGS -> local wireless ADB.
 */
object SamsungAdbAngleRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connecting = AtomicBoolean(false)
    private val pairing = AtomicBoolean(false)
    private val _status = MutableStateFlow("Searching for Samsung exact angle…")
    private val _sample = MutableStateFlow<SamsungAngleSample?>(null)
    private val _sourceActive = MutableStateFlow(false)
    private val _pairEndpoint = MutableStateFlow<MdnsEndpoint?>(null)
    private val _connectEndpoint = MutableStateFlow<MdnsEndpoint?>(null)

    val status = _status.asStateFlow()
    val sample = _sample.asStateFlow()
    /** True only while the producer of the latest exact sample is connected. */
    val sourceActive = _sourceActive.asStateFlow()
    val pairEndpoint = _pairEndpoint.asStateFlow()
    val connectEndpoint = _connectEndpoint.asStateFlow()

    private var initialized = false
    private var adbInitialized = false
    private var appContext: Context? = null
    private var activeSource: String? = null
    private val directReader = DirectFlipRefAngleReader()
    private val logcatReader = SamsungLogcatAngleReader()
    private lateinit var mdns: KadbMdnsAndroid
    private lateinit var client: EmbeddedSamsungAngleClient

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val app = context.applicationContext
        appContext = app

        if (directReader.start(
                onAngle = { angle -> acceptAngle(angle.toFloat(), "Samsung exact · direct sysfs") },
                onFailure = { error ->
                    _sample.value = null
                    _sourceActive.value = false
                    activeSource = null
                    _status.value = "Direct sensor failed: ${safeMessage(error)}"
                    startLogcatOrAdb(app)
                },
            )
        ) {
            _status.value = "Samsung exact · direct sysfs"
            return
        }
        startLogcatOrAdb(app)
    }

    /** The developer READ_LOGS grant may be enabled after the first launch. */
    fun onForeground() {
        val app = appContext ?: return
        if (logcatReader.hasPermission(app) && !logcatReader.isRunning &&
            activeSource != "direct"
        ) {
            startLogcatOrAdb(app)
        }
    }

    @Synchronized
    private fun startLogcatOrAdb(app: Context) {
        if (logcatReader.isRunning) return
        val started = logcatReader.start(
            context = app,
            onAngle = { angle, timestamp ->
                _sample.value = SamsungAngleSample(angle, timestamp)
                activeSource = "logcat"
                _sourceActive.value = true
                _status.value = "Samsung exact · SensorsHub (READ_LOGS)"
            },
            onFailure = { error ->
                if (activeSource == "logcat" || activeSource == null) {
                    _sample.value = null
                    _sourceActive.value = false
                    activeSource = null
                }
                _status.value = "SensorsHub failed: ${safeMessage(error)}"
                Log.w("Flip7AngleLab", "SensorsHub reader stopped: ${safeMessage(error)}")
                initAdbFallback(app)
            },
        )
        if (started) {
            if (!_sourceActive.value) {
                _status.value = "Listening for Samsung FoldingAngle; grant log access if prompted"
            }
            // SensorsHub is on-change. A stationary hinge can legitimately
            // produce no new event for hours. Never replace a working reader
            // with a failing TLS/ADB fallback because of a five-second timer.
        } else {
            _status.value = "READ_LOGS not granted · use USB grant or Wireless debugging"
            initAdbFallback(app)
        }
    }

    private fun acceptAngle(angle: Float, label: String) {
        _sample.value = SamsungAngleSample(angle, SystemClock.elapsedRealtimeNanos())
        _sourceActive.value = true
        activeSource = "direct"
        _status.value = label
    }

    @Synchronized
    private fun initAdbFallback(app: Context) {
        if (adbInitialized) return
        adbInitialized = true
        KadbCert.configure(OkioFilePrivateKeyStore(app.filesDir.resolve("adb_private_key.pem").path.toPath()))
        client = EmbeddedSamsungAngleClient(
            onStatus = { value ->
                if (activeSource != "logcat" && activeSource != "direct") {
                    _status.value = value
                }
                if (activeSource == "adb" &&
                    value.contains("disconnected", ignoreCase = true)
                ) {
                    _sample.value = null
                    _sourceActive.value = false
                    activeSource = null
                }
            },
            onAngle = { angle, timestamp ->
                if (activeSource != "logcat" && activeSource != "direct") {
                    _sample.value = SamsungAngleSample(angle, timestamp)
                    _sourceActive.value = true
                    activeSource = "adb"
                }
            },
        )
        mdns = KadbMdnsAndroid(
            app,
            MdnsConfig(setOf(MdnsServiceType.TLS_CONNECT, MdnsServiceType.TLS_PAIRING)),
        )
        scope.launch {
            mdns.state.collect { state ->
                _pairEndpoint.value = state.pairDevices.firstOrNull()
                _connectEndpoint.value = state.connectDevices.firstOrNull()
            }
        }
        mdns.start()

        scope.launch {
            while (isActive) {
                val endpoint = _connectEndpoint.value
                if (endpoint != null && !client.isConnected && connecting.compareAndSet(false, true)) {
                    try {
                        _sample.value = null
                        client.connect(endpoint.host, endpoint.port)
                    } catch (error: Throwable) {
                        if (activeSource != "logcat" && activeSource != "direct") {
                            _status.value = "ADB connection failed: ${safeMessage(error)}"
                        }
                    } finally {
                        connecting.set(false)
                    }
                }
                delay(if (endpoint == null) 500L else 2_500L)
            }
        }
    }

    suspend fun pair(code: String): Result<Unit> {
        if (!adbInitialized) return Result.success(Unit)
        val endpoint = _pairEndpoint.value
            ?: return Result.failure(IllegalStateException("Open 'Pair device with pairing code'"))
        if (code.length != 6 || !code.all(Char::isDigit)) {
            return Result.failure(IllegalArgumentException("Enter the 6-digit pairing code"))
        }
        if (!pairing.compareAndSet(false, true)) {
            return Result.failure(IllegalStateException("Pairing is already in progress"))
        }
        return try {
            Kadb.pair(endpoint.host, endpoint.port, code, "Flip7AngleLab")
            _status.value = "Paired · connecting to Flip7 ref_angle…"
            Result.success(Unit)
        } catch (error: Throwable) {
            _status.value = "Pairing failed: ${safeMessage(error)}"
            Result.failure(error)
        } finally {
            pairing.set(false)
        }
    }

    private fun safeMessage(error: Throwable): String =
        error.message?.takeIf(String::isNotBlank) ?: error.javaClass.simpleName
}
