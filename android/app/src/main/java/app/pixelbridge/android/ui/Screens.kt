package app.pixelbridge.android.ui

import android.text.format.DateUtils
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.pixelbridge.android.BridgeState
import app.pixelbridge.android.History
import app.pixelbridge.android.PairedMac
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable

class HomeActions(
    val sendClipboard: () -> Unit,
    val sendFiles: () -> Unit,
    val lockMac: () -> Unit,
    val openSettings: () -> Unit,
    val closeSettings: () -> Unit,
    val scanQr: () -> Unit,
    val enterCode: () -> Unit,
    val reconnect: () -> Unit,
    val unpair: () -> Unit,
    val openReceived: () -> Unit,
    val addTile: () -> Unit,
    val allowBackground: () -> Unit,
    val setReceiveClipboard: (Boolean) -> Unit,
    val openEntry: (History.Entry) -> Unit,
    val clearHistory: () -> Unit,
    val setAutoClipboard: (Boolean) -> Unit,
    val explainAutoClipboard: () -> Unit,
    val openNotificationAccess: () -> Unit,
    val setMirrorNotifications: (Boolean) -> Unit,
    val setCallAlerts: (Boolean) -> Unit,
    val requestSms: () -> Unit,
    val requestAnswerCalls: () -> Unit,
    val switchMac: (String) -> Unit,
    val forgetMac: (PairedMac) -> Unit,
)

/** What the settings screen needs to know about each feature's permissions and switches. */
data class FeatureState(
    val autoClipAvailable: Boolean = false,
    val autoClip: Boolean = true,
    val notificationAccess: Boolean = false,
    val mirrorNotifications: Boolean = true,
    val callAlerts: Boolean = true,
    val smsGranted: Boolean = false,
    val answerGranted: Boolean = false,
    val lite: Boolean = false,
)

// region Building blocks

@Composable
private fun Screen(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(BridgeTheme.colors.bg)) { content() }
}

@Composable
private fun LineIcon(icon: ImageVector, size: Dp = 22.dp, tint: Color = BridgeTheme.colors.text) =
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size))

/** Outlined box: the app's only container style. */
@Composable
private fun Outlined(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val c = BridgeTheme.colors
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier
            .clip(shape)
            .background(c.card)
            .border(1.dp, c.border, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    ) { content() }
}

@Composable
private fun MonoSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = BridgeTheme.colors
    Switch(
        checked = checked, onCheckedChange = onChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = c.bg, checkedTrackColor = c.text, checkedBorderColor = c.text,
            uncheckedThumbColor = c.muted, uncheckedTrackColor = c.bg, uncheckedBorderColor = c.faint,
        ),
    )
}

@Composable
private fun PillButton(text: String, onClick: () -> Unit) {
    val c = BridgeTheme.colors
    OutlinedButton(
        onClick = onClick, shape = CircleShape, border = BorderStroke(1.dp, c.faint),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        modifier = Modifier.height(34.dp),
    ) { Text(text, style = MaterialTheme.typography.labelMedium, color = c.text) }
}

/** Minimal laptop like Link to Windows': a dark screen and a thin light base. */
@Composable
fun MacIllustration(modifier: Modifier = Modifier, wallpaper: ImageBitmap? = null) {
    val c = BridgeTheme.colors
    Canvas(modifier.size(width = 208.dp, height = 128.dp)) {
        val w = size.width
        val screenW = w * 0.82f
        val screenH = size.height - 8.dp.toPx()
        val left = (w - screenW) / 2
        drawRect(c.screen, Offset(left, 0f), Size(screenW, screenH))
        if (wallpaper != null) {
            // The Mac's own desktop, inside a thin bezel, cropped to fill like macOS does.
            val bezel = 3.dp.toPx()
            val dstW = (screenW - 2 * bezel).toInt()
            val dstH = (screenH - 2 * bezel).toInt()
            val scale = maxOf(dstW.toFloat() / wallpaper.width, dstH.toFloat() / wallpaper.height)
            val srcW = (dstW / scale).toInt().coerceAtMost(wallpaper.width)
            val srcH = (dstH / scale).toInt().coerceAtMost(wallpaper.height)
            drawRect(Color.Black, Offset(left, 0f), Size(screenW, screenH))
            drawImage(
                wallpaper,
                srcOffset = IntOffset((wallpaper.width - srcW) / 2, (wallpaper.height - srcH) / 2),
                srcSize = IntSize(srcW, srcH),
                dstOffset = IntOffset((left + bezel).toInt(), bezel.toInt()),
                dstSize = IntSize(dstW, dstH),
                filterQuality = FilterQuality.High,
            )
        }
        drawRect(c.screenEdge, Offset(left, 0f), Size(screenW, screenH), style = Stroke(1.dp.toPx()))
        drawRoundRect(c.laptopBase, Offset(0f, screenH + 2.dp.toPx()), Size(w, 3.dp.toPx()), CornerRadius(2.dp.toPx()))
    }
}

// endregion

// region Home

@Composable
fun HomeScreen(
    state: BridgeState,
    macName: String,
    history: List<History.Entry>,
    macs: List<PairedMac>,
    currentMacId: String?,
    wallpaper: ImageBitmap?,
    actions: HomeActions,
) {
    val c = BridgeTheme.colors
    val connected = state is BridgeState.Connected
    Screen {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)
        ) {
            TopMenu(actions)
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                MacIllustration(Modifier.padding(top = 4.dp), wallpaper = if (connected) wallpaper else null)
                Spacer(Modifier.height(18.dp))
                Text(macName, style = MaterialTheme.typography.titleMedium, color = c.text,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                StatusLine(state)
            }
            Spacer(Modifier.height(26.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                ActionTile(PbIcons.Lock, "Lock Mac", connected, actions.lockMac, Modifier.weight(1f))
                ActionTile(PbIcons.Send, "Send files", connected, actions.sendFiles, Modifier.weight(1f))
            }
            Spacer(Modifier.height(22.dp))
            PairedDevicesCard(macs, currentMacId, state, actions)
            Spacer(Modifier.height(14.dp))
            RecentCard(history, actions)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun TopMenu(actions: HomeActions) {
    val c = BridgeTheme.colors
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Link to Mac", style = MaterialTheme.typography.bodyLarge, color = c.muted, modifier = Modifier.weight(1f))
        Box {
            IconButton(onClick = { open = true }) { LineIcon(PbIcons.More) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = c.card,
                shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, c.border)) {
                DropdownMenuItem(text = { Text("Settings", style = MaterialTheme.typography.bodyLarge) },
                    leadingIcon = { LineIcon(PbIcons.Settings, 20.dp) },
                    onClick = { open = false; actions.openSettings() })
                DropdownMenuItem(text = { Text("Reconnect", style = MaterialTheme.typography.bodyLarge) },
                    leadingIcon = { LineIcon(PbIcons.Refresh, 20.dp) },
                    onClick = { open = false; actions.reconnect() })
            }
        }
    }
}

@Composable
private fun StatusLine(state: BridgeState) {
    val c = BridgeTheme.colors
    val (icon, text) = when (state) {
        is BridgeState.Connected -> PbIcons.CheckCircle to "Connected"
        is BridgeState.Connecting -> PbIcons.Clock to "Connecting…"
        BridgeState.Searching -> PbIcons.Clock to "Looking for your Mac…"
        BridgeState.NotPaired -> PbIcons.XCircle to "Not paired"
        is BridgeState.Error -> PbIcons.XCircle to state.message
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        LineIcon(icon, 15.dp, c.text)
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = c.text)
    }
}

@Composable
private fun ActionTile(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val c = BridgeTheme.colors
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Outlined(Modifier.fillMaxWidth().height(60.dp), onClick = onClick) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                LineIcon(icon, 22.dp, if (enabled) c.text else c.faint)
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = if (enabled) c.muted else c.faint)
    }
}

@Composable
private fun RecentCard(history: List<History.Entry>, actions: HomeActions) {
    val c = BridgeTheme.colors
    Outlined(Modifier.fillMaxWidth()) {
        if (history.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 30.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LineIcon(PbIcons.Laptop, 22.dp, c.muted)
                    Spacer(Modifier.width(8.dp))
                    LineIcon(PbIcons.ArrowRight, 16.dp, c.muted)
                    Spacer(Modifier.width(8.dp))
                    LineIcon(PbIcons.Phone, 20.dp, c.muted)
                }
                Spacer(Modifier.height(16.dp))
                Text("Files and clipboard content you share with your Mac will show up here.",
                    style = MaterialTheme.typography.bodyMedium, color = c.text, textAlign = TextAlign.Center)
            }
        } else {
            Column(Modifier.padding(vertical = 8.dp)) {
                Text("Recent", style = MaterialTheme.typography.bodyLarge, color = c.text,
                    modifier = Modifier.padding(start = 18.dp, top = 8.dp, bottom = 6.dp))
                history.take(5).forEach { HistoryRow(it) { actions.openEntry(it) } }
            }
        }
    }
}

@Composable
private fun HistoryRow(e: History.Entry, onClick: () -> Unit) {
    val c = BridgeTheme.colors
    val icon = when (e.kind) {
        History.Kind.CLIP_IN, History.Kind.CLIP_OUT -> PbIcons.Clipboard
        History.Kind.IMAGE_IN, History.Kind.IMAGE_OUT -> PbIcons.Image
        History.Kind.FILE_IN, History.Kind.FILE_OUT -> PbIcons.File
    }
    val verb = when (e.kind) {
        History.Kind.CLIP_IN -> "Copied from Mac"
        History.Kind.CLIP_OUT -> "Copied to Mac"
        History.Kind.IMAGE_IN -> "Image from Mac"
        History.Kind.IMAGE_OUT -> "Image to Mac"
        History.Kind.FILE_IN -> "File received from Mac"
        History.Kind.FILE_OUT -> "File sent to Mac"
    }
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically) {
        LineIcon(icon, 22.dp, c.text)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(e.title.ifBlank { "Empty" }, style = MaterialTheme.typography.bodyLarge, color = c.text,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("$verb · ${ago(e.time)}", style = MaterialTheme.typography.bodySmall, color = c.muted,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(10.dp))
        LineIcon(if (e.incoming) PbIcons.ArrowDownLeft else PbIcons.ArrowUpRight, 16.dp, c.muted)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PairedDevicesCard(macs: List<PairedMac>, currentId: String?, state: BridgeState, actions: HomeActions) {
    val c = BridgeTheme.colors
    Outlined(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Text("Paired devices", style = MaterialTheme.typography.bodyLarge, color = c.text,
                modifier = Modifier.padding(start = 18.dp, top = 8.dp, bottom = 6.dp))
            macs.forEach { mac ->
                val connected = mac.id == currentId && state is BridgeState.Connected
                val status = when {
                    connected -> "Connected"
                    mac.id == currentId || (currentId == null && state is BridgeState.Connecting) -> "Connecting…"
                    mac.lastConnected > 0 -> "Last connected ${ago(mac.lastConnected).replaceFirstChar { it.lowercase() }}"
                    else -> "Not connected yet"
                }
                Row(
                    Modifier.fillMaxWidth()
                        .combinedClickable(onClick = { if (!connected) actions.switchMac(mac.id) },
                            onLongClick = { actions.forgetMac(mac) })
                        .padding(horizontal = 18.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LineIcon(PbIcons.Laptop, 22.dp, if (connected) c.text else c.muted)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(mac.displayName, style = MaterialTheme.typography.bodyLarge, color = c.text,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(status, style = MaterialTheme.typography.bodySmall, color = c.muted)
                    }
                    if (connected) LineIcon(PbIcons.CheckCircle, 18.dp, c.text)
                    else Text("Connect", style = MaterialTheme.typography.labelMedium, color = c.muted)
                }
            }
            Row(Modifier.fillMaxWidth().clickable(onClick = actions.scanQr).padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                LineIcon(PbIcons.Plus, 22.dp, c.text)
                Spacer(Modifier.width(16.dp))
                Text("Pair another Mac", style = MaterialTheme.typography.bodyLarge, color = c.text)
            }
            Text("Touch and hold a Mac to remove it.", style = MaterialTheme.typography.bodySmall, color = c.faint,
                modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 8.dp))
        }
    }
}

private fun ago(t: Long): String {
    val now = System.currentTimeMillis()
    return if (now - t < 60_000) "Just now"
    else DateUtils.getRelativeTimeSpanString(t, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()
}

// endregion

// region Settings

@Composable
fun SettingsScreen(
    state: BridgeState,
    macName: String,
    receiveClipboard: Boolean,
    backgroundAllowed: Boolean,
    hasHistory: Boolean,
    f: FeatureState,
    actions: HomeActions,
) {
    val c = BridgeTheme.colors
    Screen {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().background(c.bar).statusBarsPadding().height(64.dp).padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = actions.closeSettings) { LineIcon(PbIcons.Back, 24.dp) }
                Spacer(Modifier.width(10.dp))
                Text("Settings", style = MaterialTheme.typography.titleLarge, color = c.text)
            }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()
                .padding(bottom = 24.dp)) {
                Section("Linked Mac")
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).clip(CircleShape).border(1.dp, c.border, CircleShape),
                        contentAlignment = Alignment.Center) { LineIcon(PbIcons.Laptop, 22.dp) }
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(macName, style = MaterialTheme.typography.bodyLarge, color = c.text)
                        Text(if (state is BridgeState.Connected) "Connected" else "Not connected",
                            style = MaterialTheme.typography.bodySmall, color = c.muted)
                    }
                }
                ActionRow(PbIcons.Refresh, "Reconnect", actions.reconnect)
                ActionRow(PbIcons.Qr, "Pair a new Mac", actions.scanQr)
                ActionRow(PbIcons.Keyboard, "Enter pairing code", actions.enterCode)
                ActionRow(PbIcons.Unlink, "Unpair this Mac", actions.unpair)

                Section("On your Mac")
                if (f.autoClipAvailable) {
                    ToggleRow("Copy here, paste on Mac", "Anything you copy on this phone is ready to paste on your Mac.",
                        f.autoClip, actions.setAutoClipboard)
                } else {
                    ButtonRow("Copy here, paste on Mac", "Needs a one-time setup from your Mac.", "Set up",
                        actions.explainAutoClipboard)
                }
                if (f.lite) {
                    InfoRow("Notifications, calls and texts",
                        "Not in Link to Mac Lite. Install the full version from the project's GitHub page to use them.")
                } else if (f.notificationAccess) {
                    ToggleRow("Notifications", "Show this phone's notifications on your Mac and reply from there.",
                        f.mirrorNotifications, actions.setMirrorNotifications)
                    ToggleRow("Ring on Mac", "Your Mac rings when a call comes in.", f.callAlerts, actions.setCallAlerts)
                    if (f.callAlerts && !f.answerGranted) {
                        ButtonRow("Answer from Mac", "Pick up calls from your Mac. You talk on the phone.", "Allow",
                            actions.requestAnswerCalls)
                    }
                } else {
                    ButtonRow("Notifications and calls", "Show notifications and ring on your Mac.", "Allow",
                        actions.openNotificationAccess)
                }
                if (f.lite) {
                    // nothing more to show
                } else if (f.smsGranted) {
                    InfoRow("Text message history", "On your Mac, open the Link to Mac menu and choose Messages.")
                } else {
                    ButtonRow("Text message history", "Read your SMS conversations on your Mac.", "Allow", actions.requestSms)
                }

                Section("App settings")
                ToggleRow("Copy from Mac automatically", "Things you copy on your Mac are ready to paste here.",
                    receiveClipboard, actions.setReceiveClipboard)
                ToggleRow("Let app run in the background",
                    "To stay connected to your Mac, Link to Mac needs to keep running in the background.",
                    backgroundAllowed) { if (!backgroundAllowed) actions.allowBackground() }
                NavRow("Quick Settings tile", "Add a tile that sends your clipboard to your Mac.", actions.addTile)
                NavRow("Send clipboard now", "Send what's on this phone's clipboard to your Mac.", actions.sendClipboard)
                NavRow("Received files", "Downloads › Link to Mac", actions.openReceived)
                if (hasHistory) NavRow("Clear recent activity", "Remove everything from the Recent list.", actions.clearHistory)
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = BridgeTheme.colors.text,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 8.dp))
}

@Composable
private fun RowText(title: String, detail: String?, modifier: Modifier) {
    val c = BridgeTheme.colors
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.bodyLarge.copy(fontSize = MaterialTheme.typography.titleMedium.fontSize),
            color = c.text)
        if (detail != null) {
            Spacer(Modifier.height(2.dp))
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = c.muted)
        }
    }
}

@Composable
private fun ToggleRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        RowText(title, detail, Modifier.weight(1f))
        Spacer(Modifier.width(16.dp))
        MonoSwitch(checked, onChange)
    }
}

@Composable
private fun ButtonRow(title: String, detail: String, button: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        RowText(title, detail, Modifier.weight(1f))
        Spacer(Modifier.width(16.dp))
        PillButton(button, onClick)
    }
}

@Composable
private fun NavRow(title: String, detail: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        RowText(title, detail, Modifier.weight(1f))
        LineIcon(PbIcons.ChevronRight, 20.dp, BridgeTheme.colors.muted)
    }
}

@Composable
private fun InfoRow(title: String, detail: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        RowText(title, detail, Modifier.weight(1f))
        Spacer(Modifier.width(16.dp))
        LineIcon(PbIcons.CheckCircle, 20.dp, BridgeTheme.colors.muted)
    }
}

@Composable
private fun ActionRow(icon: ImageVector, title: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(44.dp), contentAlignment = Alignment.Center) { LineIcon(icon, 22.dp) }
        Spacer(Modifier.width(16.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge, color = BridgeTheme.colors.text)
    }
}

// endregion

// region Pairing

@Composable
fun PairScreen(state: BridgeState, onScan: () -> Unit, onEnterCode: () -> Unit) {
    val c = BridgeTheme.colors
    Screen {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(56.dp))
            MacIllustration()
            Spacer(Modifier.height(32.dp))
            Text("Connect your Mac", style = MaterialTheme.typography.headlineMedium, color = c.text)
            Spacer(Modifier.height(10.dp))
            Text(
                if (state is BridgeState.Error) state.message
                else "Share your clipboard, files, notifications and calls between this phone and your Mac.",
                style = MaterialTheme.typography.bodyLarge, color = c.muted, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(30.dp))
            Outlined(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(vertical = 8.dp)) {
                    Step(1, "Open Link to Mac on your Mac", "It lives in the menu bar")
                    Step(2, "Click the menu bar icon", "Then choose Pair Phone…")
                    Step(3, "Scan the QR code", "Phone and Mac must share a Wi-Fi network")
                }
            }
            Spacer(Modifier.height(32.dp))
            Button(onClick = onScan, modifier = Modifier.fillMaxWidth().height(54.dp), shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = c.text, contentColor = c.bg)) {
                Icon(PbIcons.Qr, null, Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text("Scan QR code", style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = onEnterCode, modifier = Modifier.fillMaxWidth().height(54.dp), shape = CircleShape,
                border = BorderStroke(1.dp, c.border)) {
                Text("Enter code instead", style = MaterialTheme.typography.labelLarge, color = c.text)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Step(n: Int, title: String, subtitle: String) {
    val c = BridgeTheme.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).clip(CircleShape).border(1.dp, c.faint, CircleShape), contentAlignment = Alignment.Center) {
            Text("$n", style = MaterialTheme.typography.bodyLarge, color = c.text)
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = c.text)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.muted)
        }
    }
}

// endregion

// region Dialogs

@Composable
fun EnterCodeDialog(initialHost: String, onDismiss: () -> Unit, onPair: (code: String, host: String) -> Unit) {
    var code by remember { mutableStateOf("") }
    var host by remember { mutableStateOf(initialHost) }
    val c = BridgeTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enter pairing code", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("It's under the QR code in the Mac app.", style = MaterialTheme.typography.bodyMedium, color = c.muted)
                OutlinedTextField(
                    code, { code = it.uppercase() }, label = { Text("Code") }, singleLine = true,
                    placeholder = { Text("ABCD-EFGH-…") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
                    shape = RoundedCornerShape(10.dp),
                )
                OutlinedTextField(
                    host, { host = it.trim() }, label = { Text("Mac IP address (optional)") }, singleLine = true,
                    placeholder = { Text("192.168.1.20") }, shape = RoundedCornerShape(10.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onPair(code, host) }, enabled = code.isNotBlank()) { Text("Pair", color = c.text) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = c.muted) } },
        shape = RoundedCornerShape(16.dp),
        containerColor = c.card,
    )
}

@Composable
fun AutoClipHelpDialog(onDismiss: () -> Unit) {
    val c = BridgeTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Copy here, paste on Mac", style = MaterialTheme.typography.titleLarge) },
        text = {
            Text("Android only lets the app you're looking at read the clipboard, so this needs a one-time " +
                "setup from your Mac:\n\n1. On this phone: Settings › System › Developer options › turn on Wireless debugging.\n" +
                "2. On your Mac: Link to Mac menu › Set Up Phone…, and follow the steps.\n\n" +
                "After that, anything you copy here is ready to paste on your Mac.",
                style = MaterialTheme.typography.bodyMedium)
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Got it", color = c.text) } },
        shape = RoundedCornerShape(16.dp),
        containerColor = c.card,
    )
}

@Composable
fun ForgetMacDialog(name: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val c = BridgeTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove $name?", style = MaterialTheme.typography.titleLarge) },
        text = { Text("This phone will stop connecting to it. You can pair it again any time from the Mac's menu.",
            style = MaterialTheme.typography.bodyMedium) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Remove", color = c.text) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = c.muted) } },
        shape = RoundedCornerShape(16.dp),
        containerColor = c.card,
    )
}

@Composable
fun ConfirmPairDialog(name: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val c = BridgeTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pair with $name?", style = MaterialTheme.typography.titleLarge) },
        text = { Text("Only continue if this code is showing on your own Mac.", style = MaterialTheme.typography.bodyMedium) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Pair", color = c.text) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = c.muted) } },
        shape = RoundedCornerShape(16.dp),
        containerColor = c.card,
    )
}

// endregion
