package com.accioeducacional.totemapp.services

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.accioeducacional.totemapp.BuildConfig
import com.accioeducacional.totemapp.MainActivity
import com.accioeducacional.totemapp.config.ServerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import okio.sink
import org.json.JSONObject
import kotlin.coroutines.coroutineContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class UpdatesService : Service() {

	private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
	private val httpClient = OkHttpClient.Builder()
		.connectTimeout(20, TimeUnit.SECONDS)
		.readTimeout(60, TimeUnit.SECONDS)
		.writeTimeout(60, TimeUnit.SECONDS)
		.build()

	private lateinit var pairingService: PairingService
	private var monitoringJobStarted = false
	private val updatesDir by lazy {
		File(filesDir, "updates").apply { if (!exists()) mkdirs() }
	}

	override fun onCreate() {
		super.onCreate()
		Log.i(TAG, "UpdatesService criado, iniciando foreground")
		pairingService = PairingService(this, ServerConfig.DEFAULT_SERVER_URL)
		ensureNotificationChannel()
		startForeground(NOTIFICATION_ID, buildNotification("Monitorando atualizações"))
		if (!monitoringJobStarted) {
			monitoringJobStarted = true
			Log.i(TAG, "Monitor loop inicializado")
			serviceScope.launch { monitorLoop() }
		}
	}

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		Log.d(TAG, "onStartCommand action=${intent?.action} startId=$startId")
		if (intent?.action == ACTION_FORCE_CHECK) {
			serviceScope.launch {
				runCatching { performUpdateCheck(forceImmediate = true) }
					.onFailure { Log.w(TAG, "Verificação manual falhou: ${it.message}") }
			}
		}
		return START_STICKY
	}

	override fun onDestroy() {
		serviceScope.cancel()
		super.onDestroy()
	}

	override fun onBind(intent: Intent?): IBinder? = null

	private suspend fun monitorLoop() {
		while (coroutineContext.isActive) {
			Log.d(TAG, "Iniciando ciclo de verificação")
			val waitTime = try {
				val outcome = performUpdateCheck(forceImmediate = false)
				val delayValue = when (outcome) {
					UpdateOutcome.NO_ACTION -> CHECK_INTERVAL_MILLIS
					UpdateOutcome.UPDATED -> POST_INSTALL_DELAY_MILLIS
				}
				Log.d(TAG, "Ciclo concluído com outcome=$outcome; aguardando ${delayValue / 1000}s")
				delayValue
			} catch (ex: Exception) {
				Log.e(TAG, "Falha no ciclo de atualização", ex)
				updateNotification("Erro ao verificar atualização")
				Log.d(TAG, "Agendando nova tentativa em ${RETRY_INTERVAL_MILLIS / 1000}s")
				RETRY_INTERVAL_MILLIS
			}
			delay(waitTime)
		}
	}

	private suspend fun performUpdateCheck(forceImmediate: Boolean): UpdateOutcome {
		val serverUrl = pairingService.getLastServerUrl().ifBlank { ServerConfig.DEFAULT_SERVER_URL }
		if (serverUrl.isBlank()) {
			updateNotification("Servidor indefinido para atualizações")
			return UpdateOutcome.NO_ACTION
		}

		Log.i(TAG, "Verificando updates em $serverUrl | versão local ${BuildConfig.VERSION_NAME}")

		val remoteVersion = fetchRemoteVersion(serverUrl)
			?: return UpdateOutcome.NO_ACTION

		Log.i(TAG, "Payload remoto version=${remoteVersion.versionName} download=${remoteVersion.downloadUrl}")

		val localSemver = parseSemanticVersion(BuildConfig.VERSION_NAME)
		val shouldInstall = when {
			remoteVersion.semanticVersion != null && localSemver != null ->
				compareSemanticVersions(remoteVersion.semanticVersion, localSemver) > 0
			remoteVersion.semanticVersion != null -> true
			else -> {
				Log.w(TAG, "Versão remota inválida: ${remoteVersion.versionName}")
				false
			}
		}

		Log.i(TAG, "Resultado da comparação: instalar=$shouldInstall remoto=${remoteVersion.semanticVersion} local=$localSemver")

		if (!shouldInstall) {
			if (forceImmediate) {
				updateNotification("Versão atual ${BuildConfig.VERSION_NAME}")
			}
			Log.i(TAG, "Nenhuma atualização necessária")
			return UpdateOutcome.NO_ACTION
		}

		updateNotification("Baixando versão ${remoteVersion.versionName}")
		val apkFile = downloadApk(serverUrl, remoteVersion.downloadUrl)
			?: return UpdateOutcome.NO_ACTION

		updateNotification("Instalando atualização")
		val installed = installPackage(apkFile)
		return if (installed) {
			updateNotification("Atualizado para ${remoteVersion.versionName}")
			scheduleSelfRestart()
			UpdateOutcome.UPDATED
		} else {
			updateNotification("Falha ao instalar atualização")
			UpdateOutcome.NO_ACTION
		}
	}

	private suspend fun fetchRemoteVersion(serverUrl: String): VersionPayload? = withContext(Dispatchers.IO) {
		val url = serverUrl.removeSuffix("/") + ServerConfig.VERSION_ENDPOINT
		Log.i(TAG, "Solicitando versão em $url")
		val request = Request.Builder()
			.get()
			.url(url)
			.header("Accept", "application/json")
			.build()

		return@withContext try {
			httpClient.newCall(request).execute().use { response ->
				if (!response.isSuccessful) {
					Log.w(TAG, "Versão não disponível: HTTP ${response.code}")
					return@use null
				}
				val body = response.body?.string()
				if (body.isNullOrBlank()) {
					Log.w(TAG, "Resposta vazia de versão")
					return@use null
				}
				val json = JSONObject(body)
				val versionName = json.optString("version")
				val downloadPath = json.optString("downloadUrl")
				if (versionName.isBlank() || downloadPath.isBlank()) {
					Log.w(TAG, "Payload de versão inválido: $body")
					return@use null
				}
				VersionPayload(
					versionName = versionName,
					downloadUrl = downloadPath,
					uploadedAt = json.optString("uploadedAt").takeIf { it.isNotBlank() },
					semanticVersion = parseSemanticVersion(versionName)
				)
			}
		} catch (ex: IOException) {
			Log.w(TAG, "Erro ao buscar versão: ${ex.message}")
			null
		}
	}

	private suspend fun downloadApk(baseUrl: String, appUrl: String): File? = withContext(Dispatchers.IO) {
		val resolvedUrl = resolveDownloadUrl(baseUrl, appUrl)
		val requestBuilder = Request.Builder()
			.url(resolvedUrl)
			.get()
			.header("Accept", "application/vnd.android.package-archive")

		return@withContext try {
			httpClient.newCall(requestBuilder.build()).execute().use { response ->
				if (!response.isSuccessful) {
					Log.w(TAG, "Falha ao baixar APK: HTTP ${response.code}")
					return@use null
				}
				val body = response.body ?: return@use null
				val targetFile = File(updatesDir, "update-${System.currentTimeMillis()}.apk")
				body.source().use { source ->
					targetFile.sink().buffer().use { sink ->
						sink.writeAll(source)
					}
				}
				targetFile
			}
		} catch (ex: IOException) {
			Log.e(TAG, "Erro ao baixar APK", ex)
			null
		}
	}

	private suspend fun installPackage(apkFile: File): Boolean = suspendCancellableCoroutine { continuation ->
		try {
			val installer = packageManager.packageInstaller
			val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
				setAppPackageName(packageName)
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
					setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
				}
			}
			val sessionId = installer.createSession(params)
			val session = installer.openSession(sessionId)
			apkFile.inputStream().use { input ->
				session.openWrite("app_update", 0, apkFile.length()).use { output ->
					input.copyTo(output)
					session.fsync(output)
				}
			}

			val receiver = object : BroadcastReceiver() {
				override fun onReceive(context: Context?, intent: Intent?) {
					intent ?: return
					val receivedSession = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
					if (receivedSession != sessionId) return
					val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
					val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
					unregisterReceiverSafe(this)
					if (status == PackageInstaller.STATUS_SUCCESS) {
						continuation.resume(true)
					} else {
						Log.e(TAG, "Instalação falhou: $message")
						continuation.resume(false)
					}
				}
			}

			registerReceiverCompat(receiver, IntentFilter(INSTALL_STATUS_ACTION))
			val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
				PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
			} else {
				PendingIntent.FLAG_UPDATE_CURRENT
			}
			val pendingIntent = PendingIntent.getBroadcast(
				this,
				sessionId,
				Intent(INSTALL_STATUS_ACTION).setPackage(packageName),
				pendingIntentFlags
			)
			session.commit(pendingIntent.intentSender)
			continuation.invokeOnCancellation {
				runCatching {
					unregisterReceiverSafe(receiver)
					session.abandon()
				}
			}
		} catch (ex: Exception) {
			Log.e(TAG, "Erro durante instalação", ex)
			continuation.resumeWithException(ex)
		}
	}

	private fun scheduleSelfRestart() {
		val alarmManager = getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
		val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
			?: Intent(this, MainActivity::class.java)
		launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
		val pendingIntent = PendingIntent.getActivity(
			this,
			0,
			launchIntent,
			PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
		)
		val triggerAt = System.currentTimeMillis() + RESTART_DELAY_MILLIS
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
			alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
		} else {
			@Suppress("DEPRECATION")
			alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
		}
	}

	private fun resolveDownloadUrl(baseUrl: String, path: String): String {
		if (path.startsWith("http://") || path.startsWith("https://")) {
			return path
		}
		val sanitizedBase = baseUrl.removeSuffix("/")
		val sanitizedPath = path.removePrefix("/")
		return "$sanitizedBase/$sanitizedPath"
	}

	private fun updateNotification(message: String) {
		val notification = buildNotification(message)
		NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
	}

	private fun buildNotification(message: String): Notification {
		return NotificationCompat.Builder(this, CHANNEL_ID)
			.setSmallIcon(android.R.drawable.stat_sys_download_done)
			.setContentTitle("Atualizações automáticas")
			.setContentText(message)
			.setStyle(NotificationCompat.BigTextStyle().bigText(message))
			.setOngoing(true)
			.setOnlyAlertOnce(true)
			.build()
	}

	private fun ensureNotificationChannel() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
			val channel = NotificationChannel(
				CHANNEL_ID,
				"Monitoramento de updates",
				NotificationManager.IMPORTANCE_LOW
			)
			channel.description = "Canal usado para monitorar atualizações silenciosas"
			manager.createNotificationChannel(channel)
		}
	}

	private fun registerReceiverCompat(receiver: BroadcastReceiver, filter: IntentFilter) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
		} else {
			@Suppress("DEPRECATION")
			registerReceiver(receiver, filter)
		}
	}

	private fun unregisterReceiverSafe(receiver: BroadcastReceiver) {
		runCatching { unregisterReceiver(receiver) }
	}

	private fun parseSemanticVersion(value: String?): List<Int>? {
		val sanitized = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
		val numericPart = sanitized.substringBefore('-')
		if (numericPart.isEmpty()) return null
		val parts = numericPart.split('.')
		if (parts.isEmpty()) return null
		return parts.map { segment ->
			segment.toIntOrNull() ?: return null
		}
	}

	private fun compareSemanticVersions(remote: List<Int>, local: List<Int>): Int {
		val max = maxOf(remote.size, local.size)
		for (index in 0 until max) {
			val remoteValue = remote.getOrElse(index) { 0 }
			val localValue = local.getOrElse(index) { 0 }
			if (remoteValue != localValue) {
				return remoteValue.compareTo(localValue)
			}
		}
		return 0
	}

	private enum class UpdateOutcome { NO_ACTION, UPDATED }

	private data class VersionPayload(
		val versionName: String,
		val downloadUrl: String,
		val uploadedAt: String?,
		val semanticVersion: List<Int>?
	)

	companion object {
		private const val TAG = "UpdatesService"
		private const val CHANNEL_ID = "accio.update"
		private const val NOTIFICATION_ID = 4012
		private const val CHECK_INTERVAL_MILLIS = 15 * 60 * 1000L
		private const val RETRY_INTERVAL_MILLIS = 2 * 60 * 1000L
		private const val POST_INSTALL_DELAY_MILLIS = 5_000L
		private const val RESTART_DELAY_MILLIS = 6_000L
		private const val ACTION_FORCE_CHECK = "com.accioeducacional.totemapp.action.FORCE_UPDATE_CHECK"
		private const val INSTALL_STATUS_ACTION = "com.accioeducacional.totemapp.action.INSTALL_STATUS"

		fun start(context: Context) {
			Log.i(TAG, "Solicitando startForegroundService")
			val intent = Intent(context, UpdatesService::class.java)
			ContextCompat.startForegroundService(context, intent)
		}

		fun checkNow(context: Context) {
			Log.i(TAG, "Solicitando startForegroundService (checkNow)")
			val intent = Intent(context, UpdatesService::class.java).apply { action = ACTION_FORCE_CHECK }
			ContextCompat.startForegroundService(context, intent)
		}
	}
}
