package app.pixelbridge.android

import android.Manifest
import android.annotation.SuppressLint
import android.app.StatusBarManager
import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var detail: TextView
    private lateinit var connectedActions: View
    private lateinit var pairButton: MaterialButton
    private val listener: (BridgeState) -> Unit = { render(it) }

    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        val clip = ClipData.newRawUri("files", uris[0])
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        val i = Intent(this, BridgeService::class.java).setAction(BridgeService.ACTION_SEND_URIS)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        i.clipData = clip
        ContextCompat.startForegroundService(this, i)
        Toast.makeText(this, "Sending to Mac…", Toast.LENGTH_SHORT).show()
    }

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Prefs.init(this)
        status = findViewById(R.id.status)
        detail = findViewById(R.id.detail)
        connectedActions = findViewById(R.id.connected_actions)
        pairButton = findViewById(R.id.btn_pair)

        pairButton.setOnClickListener { scanQr() }
        findViewById<MaterialButton>(R.id.btn_code).setOnClickListener { enterCodeDialog() }
        findViewById<MaterialButton>(R.id.btn_send_clip).setOnClickListener {
            startActivity(Intent(this, ClipboardSendActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.btn_send_files).setOnClickListener { pickFiles.launch(arrayOf("*/*")) }
        findViewById<MaterialButton>(R.id.btn_tile).setOnClickListener { addTile() }
        findViewById<MaterialButton>(R.id.btn_battery).setOnClickListener { askBatteryExemption() }
        findViewById<MaterialButton>(R.id.btn_reconnect).setOnClickListener { Bridge.reconnect() }
        findViewById<MaterialSwitch>(R.id.switch_receive).apply {
            isChecked = Prefs.receiveClipboard
            setOnCheckedChangeListener { _, on -> Prefs.receiveClipboard = on }
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Prefs.secret != null) BridgeService.start(this)
        handleDeepLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    override fun onStart() {
        super.onStart()
        Bridge.init(this)
        Bridge.addListener(listener)
    }

    override fun onStop() {
        Bridge.removeListener(listener)
        super.onStop()
    }

    private fun render(s: BridgeState) {
        status.text = BridgeService.statusText(s)
        detail.text = when (s) {
            is BridgeState.Connected -> "Copy on your Mac → paste here. Use the tile, share sheet or button below to send the other way."
            BridgeState.NotPaired -> "Open PixelBridge on your Mac, choose “Pair Phone…”, then scan the QR code."
            BridgeState.Searching -> "Make sure the Mac app is running and both devices are on the same Wi-Fi."
            is BridgeState.Connecting -> s.target
            is BridgeState.Error -> "Choose “Pair Phone…” on the Mac and scan the code again."
        }
        connectedActions.visibility = if (s is BridgeState.Connected) View.VISIBLE else View.GONE
        pairButton.text = if (Prefs.secret == null) "Scan pairing QR code" else "Pair again"
    }

    // region Pairing

    private fun scanQr() {
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(this, options).startScan()
            .addOnSuccessListener { code ->
                val info = code.rawValue?.let { Pairing.parseUri(it) }
                if (info == null) Toast.makeText(this, "That isn't a PixelBridge code", Toast.LENGTH_LONG).show()
                else applyPairing(info)
            }
            .addOnFailureListener { Toast.makeText(this, "Scanner unavailable: ${it.message}", Toast.LENGTH_LONG).show() }
    }

    /** Scanning the Mac's QR with the Pixel camera opens pixelbridge://pair… here; confirm before trusting it. */
    private fun handleDeepLink(intent: Intent?) {
        val data: Uri = intent?.data ?: return
        val info = Pairing.parseUri(data.toString()) ?: return
        setIntent(Intent(this, MainActivity::class.java))
        MaterialAlertDialogBuilder(this)
            .setTitle("Pair with ${info.name ?: "this Mac"}?")
            .setMessage("Only continue if this code is showing on your own Mac.")
            .setPositiveButton("Pair") { _, _ -> applyPairing(info) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun enterCodeDialog() {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val code = EditText(this).apply {
            hint = "ABCD-EFGH-…"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val host = EditText(this).apply {
            hint = "Mac IP address (optional), e.g. 192.168.1.20"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(Prefs.host.orEmpty())
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(code); addView(host)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Enter pairing code")
            .setMessage("Shown under the QR code in the Mac app.")
            .setView(box)
            .setPositiveButton("Pair") { _, _ ->
                val secret = Pairing.secretFromCode(code.text.toString())
                if (secret == null) {
                    Toast.makeText(this, "That code doesn't look right", Toast.LENGTH_LONG).show()
                } else {
                    val (h, p) = host.text.toString().trim().let { t ->
                        if (t.isEmpty()) null to Proto.DEFAULT_PORT
                        else t.substringBefore(':') to (t.substringAfter(':', "").toIntOrNull() ?: Proto.DEFAULT_PORT)
                    }
                    applyPairing(PairingInfo(secret, h, p, null))
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun applyPairing(info: PairingInfo) {
        Prefs.pair(info)
        BridgeService.start(this)
        Bridge.reconnect()
        Toast.makeText(this, "Paired. Connecting…", Toast.LENGTH_SHORT).show()
        render(Bridge.state)
    }

    // endregion

    private fun addTile() {
        if (Build.VERSION.SDK_INT >= 33) {
            getSystemService(StatusBarManager::class.java).requestAddTileService(
                ComponentName(this, ClipTileService::class.java), "Send clipboard to Mac",
                Icon.createWithResource(this, R.drawable.ic_bridge), mainExecutor) {}
        } else {
            Toast.makeText(this, "Edit Quick Settings and drag in “Send to Mac”", Toast.LENGTH_LONG).show()
        }
    }

    @SuppressLint("BatteryLife")
    private fun askBatteryExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "Already allowed to run in the background", Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
    }
}
