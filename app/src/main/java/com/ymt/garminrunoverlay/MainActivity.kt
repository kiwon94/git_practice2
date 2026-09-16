package com.ymt.garminrunoverlay

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsCadenceRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs

class MainActivity : ComponentActivity() {
    companion object {
        private const val APP_URL = "https://garmin-run-overlay-587q24.v2.appdeploy.ai/?native=1"
        private const val FILE_CHOOSER_REQUEST = 2001
    }

    private lateinit var webView: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pendingHealthDays = 30

    private val healthPermissions: Set<String> by lazy {
        setOf(
            HealthPermission.getReadPermission(ExerciseSessionRecord::class),
            HealthPermission.getReadPermission(HeartRateRecord::class),
            HealthPermission.getReadPermission(SpeedRecord::class),
            HealthPermission.getReadPermission(DistanceRecord::class),
            HealthPermission.getReadPermission(StepsCadenceRecord::class),
        )
    }

    private val healthPermissionLauncher: ActivityResultLauncher<Set<String>> =
        registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
            val exercisePermission = HealthPermission.getReadPermission(ExerciseSessionRecord::class)
            if (granted.contains(exercisePermission)) {
                scope.launch { readAndSendHealthConnect(pendingHealthDays, granted) }
            } else {
                sendHealthError("Health Connect의 운동 읽기 권한이 필요합니다.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CookieManager.getInstance().setAcceptCookie(true)
        webView = WebView(this)
        configureWebView(webView, null, true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        setContentView(webView)

        if (savedInstanceState != null) webView.restoreState(savedInstanceState)
        else webView.loadUrl(APP_URL)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(view: WebView, popupDialog: Dialog?, trustedMainView: Boolean) {
        val settings = view.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.setSupportMultipleWindows(true)
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.userAgentString = settings.userAgentString.replace("; wv", "")
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)

        if (trustedMainView) {
            view.addJavascriptInterface(HealthConnectBridge(), "HealthConnectBridge")
        }

        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                val scheme = uri.scheme
                if (scheme == "http" || scheme == "https") return false
                return try {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                    true
                } catch (_: ActivityNotFoundException) {
                    true
                }
            }
        }

        view.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                currentWebView: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams,
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                return try {
                    startActivityForResult(params.createIntent(), FILE_CHOOSER_REQUEST)
                    true
                } catch (_: ActivityNotFoundException) {
                    fileCallback = null
                    false
                }
            }

            override fun onCreateWindow(
                source: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message,
            ): Boolean {
                val dialog = Dialog(this@MainActivity, android.R.style.Theme_Material_Light_NoActionBar_Fullscreen)
                val child = WebView(this@MainActivity)
                configureWebView(child, dialog, false)
                dialog.setContentView(
                    child,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    ),
                )
                dialog.setOnDismissListener { child.destroy() }
                val transport = resultMsg.obj as WebView.WebViewTransport
                transport.webView = child
                resultMsg.sendToTarget()
                dialog.show()
                return true
            }

            override fun onCloseWindow(window: WebView) {
                if (popupDialog?.isShowing == true) popupDialog.dismiss()
            }
        }
    }

    private inner class HealthConnectBridge {
        @JavascriptInterface
        fun isAvailable(): Boolean =
            HealthConnectClient.getSdkStatus(this@MainActivity) == HealthConnectClient.SDK_AVAILABLE

        @JavascriptInterface
        fun syncRunningData(days: Int) {
            runOnUiThread {
                scope.launch { prepareHealthConnectSync(days.coerceIn(1, 30)) }
            }
        }

        @JavascriptInterface
        fun openHealthConnectSettings() {
            runOnUiThread {
                try {
                    startActivity(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS))
                } catch (_: Exception) {
                    sendHealthError("Health Connect 설정 화면을 열 수 없습니다.")
                }
            }
        }
    }

    private suspend fun prepareHealthConnectSync(days: Int) {
        if (HealthConnectClient.getSdkStatus(this) != HealthConnectClient.SDK_AVAILABLE) {
            sendHealthError("이 휴대폰에서 Health Connect를 사용할 수 없습니다.")
            return
        }
        val client = HealthConnectClient.getOrCreate(this)
        val granted = client.permissionController.getGrantedPermissions()
        val exercisePermission = HealthPermission.getReadPermission(ExerciseSessionRecord::class)
        if (!granted.contains(exercisePermission)) {
            pendingHealthDays = days
            healthPermissionLauncher.launch(healthPermissions)
            return
        }
        readAndSendHealthConnect(days, granted)
    }

    private suspend fun readAndSendHealthConnect(days: Int, granted: Set<String>) {
        try {
            val client = HealthConnectClient.getOrCreate(this)
            val now = Instant.now()
            val from = now.minus(days.toLong(), ChronoUnit.DAYS)
            val sessionResponse = client.readRecords(
                ReadRecordsRequest<ExerciseSessionRecord>(
                    timeRangeFilter = TimeRangeFilter.between(from, now),
                    ascendingOrder = false,
                ),
            )
            val running = sessionResponse.records.filter {
                it.exerciseType == ExerciseSessionRecord.EXERCISE_TYPE_RUNNING ||
                    it.exerciseType == ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL
            }
            val garmin = running.filter {
                it.metadata.dataOrigin.packageName.contains("garmin", ignoreCase = true)
            }
            val sessions = (if (garmin.isNotEmpty()) garmin else running).take(30)

            val activities = JSONArray()
            for (session in sessions) {
                activities.put(buildActivityJson(client, session, granted))
            }
            val root = JSONObject()
                .put("activities", activities)
                .put(
                    "message",
                    if (activities.length() == 0)
                        "Health Connect에서 최근 러닝을 찾지 못했습니다. Garmin Connect가 Health Connect에 운동 데이터를 공유하도록 설정되어 있는지 확인해 주세요."
                    else
                        "Health Connect에서 ${activities.length()}건의 러닝을 읽었습니다.",
                )
            sendHealthResult(root.toString())
        } catch (e: SecurityException) {
            sendHealthError("Health Connect 권한이 부족합니다. 앱의 Health Connect 권한을 다시 확인해 주세요.")
        } catch (e: Exception) {
            sendHealthError("Health Connect 읽기 실패: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private suspend fun buildActivityJson(
        client: HealthConnectClient,
        session: ExerciseSessionRecord,
        granted: Set<String>,
    ): JSONObject {
        val origin = session.metadata.dataOrigin
        val timeFilter = TimeRangeFilter.between(session.startTime, session.endTime)
        val originFilter = setOf(origin)

        val heartRateSamples = if (granted.contains(HealthPermission.getReadPermission(HeartRateRecord::class))) {
            client.readRecords(
                ReadRecordsRequest<HeartRateRecord>(
                    timeRangeFilter = timeFilter,
                    dataOriginFilter = originFilter,
                ),
            ).records.flatMap { record ->
                record.samples.map { TimedValue(it.time, it.beatsPerMinute.toDouble()) }
            }.sortedBy { it.time }
        } else emptyList()

        val speedSamples = if (granted.contains(HealthPermission.getReadPermission(SpeedRecord::class))) {
            client.readRecords(
                ReadRecordsRequest<SpeedRecord>(
                    timeRangeFilter = timeFilter,
                    dataOriginFilter = originFilter,
                ),
            ).records.flatMap { record ->
                record.samples.map { TimedValue(it.time, it.speed.inMetersPerSecond) }
            }.sortedBy { it.time }
        } else emptyList()

        val cadenceSamples = if (granted.contains(HealthPermission.getReadPermission(StepsCadenceRecord::class))) {
            client.readRecords(
                ReadRecordsRequest<StepsCadenceRecord>(
                    timeRangeFilter = timeFilter,
                    dataOriginFilter = originFilter,
                ),
            ).records.flatMap { record ->
                record.samples.map { TimedValue(it.time, it.rate) }
            }.sortedBy { it.time }
        } else emptyList()

        val distanceKm = if (granted.contains(HealthPermission.getReadPermission(DistanceRecord::class))) {
            client.readRecords(
                ReadRecordsRequest<DistanceRecord>(
                    timeRangeFilter = timeFilter,
                    dataOriginFilter = originFilter,
                ),
            ).records.sumOf { it.distance.inMeters } / 1000.0
        } else 0.0

        val durationSec = Duration.between(session.startTime, session.endTime).seconds.coerceAtLeast(1)
        val sampleSeconds = mutableListOf<Long>()
        var second = 0L
        while (second < durationSec) {
            sampleSeconds.add(second)
            second += 15L
        }
        if (sampleSeconds.lastOrNull() != durationSec) sampleSeconds.add(durationSec)

        val drafts = mutableListOf<PointDraft>()
        var rawDistanceKm = 0.0
        var previousTime = session.startTime
        var previousSpeed = interpolate(speedSamples, previousTime) ?: 0.0
        for (tSec in sampleSeconds) {
            val at = session.startTime.plusSeconds(tSec)
            val speedMps = interpolate(speedSamples, at)
            if (tSec > 0) {
                val dt = Duration.between(previousTime, at).toMillis().coerceAtLeast(0).toDouble() / 1000.0
                val currentForDistance = speedMps ?: previousSpeed
                rawDistanceKm += ((previousSpeed + currentForDistance) / 2.0) * dt / 1000.0
                previousSpeed = currentForDistance
                previousTime = at
            }
            drafts.add(
                PointDraft(
                    tSec = tSec,
                    rawDistanceKm = rawDistanceKm,
                    heartRate = interpolate(heartRateSamples, at),
                    cadence = interpolate(cadenceSamples, at),
                    speedKmh = speedMps?.times(3.6),
                ),
            )
        }

        val integratedEnd = drafts.lastOrNull()?.rawDistanceKm ?: 0.0
        val finalDistanceKm = when {
            distanceKm > 0.0 -> distanceKm
            integratedEnd > 0.0 -> integratedEnd
            else -> 0.0
        }
        val points = JSONArray()
        for (draft in drafts) {
            val mappedDistance = when {
                finalDistanceKm > 0.0 && integratedEnd > 0.0 -> draft.rawDistanceKm * finalDistanceKm / integratedEnd
                finalDistanceKm > 0.0 -> finalDistanceKm * draft.tSec.toDouble() / durationSec.toDouble()
                else -> draft.rawDistanceKm
            }
            val point = JSONObject()
                .put("tSec", draft.tSec)
                .put("distanceKm", mappedDistance)
            draft.heartRate?.let { point.put("heartRate", it) }
            draft.cadence?.let { point.put("cadence", it) }
            draft.speedKmh?.let { point.put("speedKmh", it) }
            points.put(point)
        }

        val avgHeartRate = heartRateSamples.map { it.value }.averageOrNull()
        val maxHeartRate = heartRateSamples.maxOfOrNull { it.value }
        val avgCadence = cadenceSamples.map { it.value }.averageOrNull()
        val avgSpeedKmh = if (finalDistanceKm > 0.0) finalDistanceKm * 3600.0 / durationSec.toDouble()
            else speedSamples.map { it.value * 3.6 }.averageOrNull()

        val sourcePackage = origin.packageName
        val sourceLabel = if (sourcePackage.contains("garmin", ignoreCase = true))
            "Garmin via Health Connect"
        else
            "Health Connect · $sourcePackage"

        val result = JSONObject()
            .put("externalId", "healthconnect:${session.metadata.id}")
            .put("date", session.startTime.atZone(ZoneId.systemDefault()).toLocalDate().toString())
            .put("name", session.title ?: "러닝")
            .put("source", sourceLabel)
            .put("distanceKm", finalDistanceKm)
            .put("durationSec", durationSec)
            .put("points", points)
        avgHeartRate?.let { result.put("avgHeartRate", it) }
        maxHeartRate?.let { result.put("maxHeartRate", it) }
        avgCadence?.let { result.put("avgCadence", it) }
        avgSpeedKmh?.let { result.put("avgSpeedKmh", it) }
        return result
    }

    private fun interpolate(samples: List<TimedValue>, at: Instant, maxGapSeconds: Long = 90): Double? {
        if (samples.isEmpty()) return null
        var low = 0
        var high = samples.lastIndex
        while (low <= high) {
            val mid = (low + high) ushr 1
            when {
                samples[mid].time < at -> low = mid + 1
                samples[mid].time > at -> high = mid - 1
                else -> return samples[mid].value
            }
        }
        val right = samples.getOrNull(low)
        val left = samples.getOrNull(low - 1)
        if (left != null && right != null) {
            val totalMs = Duration.between(left.time, right.time).toMillis()
            if (totalMs > 0 && totalMs <= maxGapSeconds * 2_000L) {
                val elapsedMs = Duration.between(left.time, at).toMillis().coerceIn(0, totalMs)
                val fraction = elapsedMs.toDouble() / totalMs.toDouble()
                return left.value + (right.value - left.value) * fraction
            }
        }
        val nearest = listOfNotNull(left, right).minByOrNull { abs(Duration.between(it.time, at).seconds) }
        return nearest?.takeIf { abs(Duration.between(it.time, at).seconds) <= maxGapSeconds }?.value
    }

    private fun List<Double>.averageOrNull(): Double? = if (isEmpty()) null else average()

    private fun sendHealthResult(payload: String) {
        runOnUiThread {
            val quoted = JSONObject.quote(payload)
            webView.evaluateJavascript(
                "window.__healthConnectImportResult && window.__healthConnectImportResult($quoted);",
                null,
            )
        }
    }

    private fun sendHealthError(message: String) {
        runOnUiThread {
            val quoted = JSONObject.quote(message)
            webView.evaluateJavascript(
                "window.__healthConnectError && window.__healthConnectError($quoted);",
                null,
            )
        }
    }

    private data class TimedValue(val time: Instant, val value: Double)
    private data class PointDraft(
        val tSec: Long,
        val rawDistanceKm: Double,
        val heartRate: Double?,
        val cadence: Double?,
        val speedKmh: Double?,
    )

    @Deprecated("Deprecated in Android")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == FILE_CHOOSER_REQUEST) {
            fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            fileCallback = null
        }
    }

    @Deprecated("Deprecated in Android")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::webView.isInitialized) webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        scope.cancel()
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }
}
