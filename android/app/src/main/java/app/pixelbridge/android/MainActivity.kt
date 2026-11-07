package app.pixelbridge.android

import android.Manifest
import android.app.DownloadManager
import android.app.StatusBarManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import app.pixelbridge.android.ui.ConfirmPairDialog
import app.pixelbridge.android.ui.AutoClipHelpDialog
import app.pixelbridge.android.ui.EnterCodeDialog
import app.pixelbridge.android.ui.FeatureState
import app.pixelbridge.android.ui.HomeActions
import app.pixelbridge.android.ui.HomeScreen
import app.pixelbridge.android.ui.PairScreen
import app.pixelbridge.android.ui.PixelBridgeTheme
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

class MainActivity : ComponentActivity() {
    private var bridgeState by mutableStateOf<BridgeState>(BridgeState.Searching)
    private var history by mutableStateOf<List<History.Entry>>(emptyList())
    private var paired by mutableStateOf(false)
    private var macName by mutableStateOf("Your Mac")
    private var receiveClipboard by mutableStateOf(true)
    private var backgroundAllowed by mutableStateOf(true)
    private var showCodeDialog by mutableStateOf(false)
    private var pendingPair by mutableStateOf<PairingInfo?>(null)
    private var features by mutableStateOf(FeatureState())
    private var showAutoClipHelp by mutableStateOf(false)

    private val stateListener: (BridgeState) -> Unit = { s ->
        bridgeState = s
        paired = Prefs.secret != null
        macName = Prefs.macName ?: "Your Mac"
    }
    private val historyListener: (List<History.Entry>) -> Unit = { history = it }

    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        val clip = ClipData.newRawUri("files", uris[0])
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        val i = Intent(this, BridgeService::class.java).setAction(BridgeService.ACTION_SEND_URIS)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        i.clipData = clip
        ContextCompat.startForegroundService(this, i)
        toast("Sending to Mac…")
    }

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private val answerPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        refreshFeatures()
    }
    private val smsPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refreshFeatures()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Bridge.init(this)
        paired = Prefs.secret != null
        receiveClipboard = Prefs.receiveClipboard

        val actions = HomeActions(
            sendClipboard = { startActivity(Intent(this, ClipboardSendActivity::class.java)) },
            sendFiles = { pickFiles.launch(arrayOf("*/*")) },
            scanQr = ::scanQr,
            enterCode = { showCodeDialog = true },
            reconnect = { Bridge.reconnect(); toast("Reconnecting…") },
            unpair = {
                Prefs.unpair(); paired = false; Bridge.reconnect()
            },
            openReceived = ::openDownloads,
            addTile = ::addTile,
            allowBackground = ::askBatteryExemption,
            setReceiveClipboard = { Prefs.receiveClipboard = it; receiveClipboard = it },
            openEntry = ::openEntry,
            clearHistory = { History.clear() },
            setAutoClipboard = { Prefs.autoSendClipboard = it; refreshFeatures() },
            explainAutoClipboard = { showAutoClipHelp = true },
            openNotificationAccess = {
                try { startActivity(NotificationMirror.accessSettingsIntent(this)) }
                catch (_: Exception) { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            },
            setMirrorNotifications = { Prefs.mirrorNotifications = it; refreshFeatures() },
            setCallAlerts = { Prefs.callAlerts = it; refreshFeatures() },
            requestAnswerCalls = { answerPermission.launch(Manifest.permission.ANSWER_PHONE_CALLS) },
            requestSms = {
                if (SmsStore.hasPermission(this)) toast("On your Mac: PixelBridge menu › Messages")
                else smsPermission.launch(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.READ_CONTACTS))
            },
        )

        setContent {
            PixelBridgeTheme {
                if (paired) {
                    HomeScreen(bridgeState, macName, history, receiveClipboard, backgroundAllowed, features, actions)
                } else {
                    PairScreen(bridgeState, onScan = ::scanQr, onEnterCode = { showCodeDialog = true })
                }
                if (showCodeDialog) {
                    EnterCodeDialog(
                        initialHost = Prefs.host.orEmpty(),
                        onDismiss = { showCodeDialog = false },
                        onPair = { code, host -> showCodeDialog = false; pairWithCode(code, host) },
                    )
                }
                if (showAutoClipHelp) {
                    AutoClipHelpDialog(onDismiss = { showAutoClipHelp = false })
                }
                pendingPair?.let { info ->
                    ConfirmPairDialog(
                        name = info.name ?: "this Mac",
                        onDismiss = { pendingPair = null },
                        onConfirm = { pendingPair = null; applyPairing(info) },
                    )
                }
            }
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (paired) BridgeService.start(this)
        handleDeepLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    override fun onStart() {
        super.onStart()
        Bridge.addListener(stateListener)
        History.addListener(historyListener)
    }

    override fun onResume() {
        super.onResume()
        backgroundAllowed = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        refreshFeatures()
        if (paired && Prefs.autoSendClipboard && AutoClipboard.isAvailable(this)) {
            AutoClipboard.start(this)
            BridgeService.clearAutoClipPrompt(this)
        }
    }

    override fun onStop() {
        Bridge.removeListener(stateListener)
        History.removeListener(historyListener)
        super.onStop()
    }

    private fun refreshFeatures() {
        features = FeatureState(
            autoClipAvailable = AutoClipboard.isAvailable(this),
            autoClip = Prefs.autoSendClipboard,
            notificationAccess = NotificationMirror.hasAccess(this),
            mirrorNotifications = Prefs.mirrorNotifications,
            callAlerts = Prefs.callAlerts,
            smsGranted = SmsStore.hasPermission(this),
            answerGranted = checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED,
        )
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // region Pairing

    private fun scanQr() {
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(this, options).startScan()
            .addOnSuccessListener { code ->
                val info = code.rawValue?.let { Pairing.parseUri(it) }
                if (info == null) toast("That isn't a PixelBridge code") else applyPairing(info)
            }
            .addOnFailureListener { toast("Scanner unavailable: ${it.message}") }
    }

    /** Scanning the Mac's QR with the Pixel camera opens pixelbridge://pair… here; confirm before trusting it. */
    private fun handleDeepLink(intent: Intent?) {
        val data: Uri = intent?.data ?: return
        val info = Pairing.parseUri(data.toString()) ?: return
        setIntent(Intent(this, MainActivity::class.java))
        pendingPair = info
    }

    private fun pairWithCode(code: String, hostText: String) {
        val secret = Pairing.secretFromCode(code)
        if (secret == null) { toast("That code doesn't look right"); return }
        val t = hostText.trim()
        val host = t.substringBefore(':').ifEmpty { null }
        val port = t.substringAfter(':', "").toIntOrNull() ?: Proto.DEFAULT_PORT
        applyPairing(PairingInfo(secret, host, port, null))
    }

    private fun applyPairing(info: PairingInfo) {
        Prefs.pair(info)
        paired = true
        macName = info.name ?: "Your Mac"
        BridgeService.start(this)
        Bridge.reconnect()
    }

    // endregion

    private fun openEntry(e: History.Entry) {
        when {
            e.kind == History.Kind.FILE_IN && e.uri != null -> {
                val uri = Uri.parse(e.uri)
                val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, contentResolver.getType(uri))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                try { startActivity(view) } catch (_: Exception) { openDownloads() }
            }
            e.kind == History.Kind.CLIP_IN || e.kind == History.Kind.CLIP_OUT -> {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("PixelBridge", e.title))
                if (Build.VERSION.SDK_INT < 33) toast("Copied")
            }
            else -> Unit
        }
    }

    private fun openDownloads() {
        try { startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)) } catch (_: Exception) { toast("Open Files › Downloads › PixelBridge") }
    }

    private fun addTile() {
        if (Build.VERSION.SDK_INT >= 33) {
            getSystemService(StatusBarManager::class.java).requestAddTileService(
                ComponentName(this, ClipTileService::class.java), "Send to Mac",
                Icon.createWithResource(this, R.drawable.ic_bridge), mainExecutor) { result ->
                if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) toast("The tile is already in Quick Settings")
            }
        } else {
            toast("Edit Quick Settings and drag in “Send to Mac”")
        }
    }

    private fun askBatteryExemption() {
        @Suppress("BatteryLife")
        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
    }
}
