package app.pixelbridge.android.ui

import android.text.format.DateUtils
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.NorthEast
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.SouthWest
import androidx.compose.material.icons.rounded.SyncAlt
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pixelbridge.android.BridgeState
import app.pixelbridge.android.History

class HomeActions(
    val sendClipboard: () -> Unit,
    val sendFiles: () -> Unit,
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
)

/** What the Mac-features card needs to know. */
data class FeatureState(
    val autoClipAvailable: Boolean = false,
    val autoClip: Boolean = true,
    val notificationAccess: Boolean = false,
    val mirrorNotifications: Boolean = true,
    val callAlerts: Boolean = true,
    val smsGranted: Boolean = false,
    val answerGranted: Boolean = false,
)

@Composable
fun AppBackground(content: @Composable () -> Unit) {
    val c = BridgeTheme.colors
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(0f to c.bgTop, 0.45f to c.bgBottom, 1f to c.bgBottom))
    ) { content() }
}

// region Home

@Composable
fun HomeScreen(
    state: BridgeState,
    macName: String,
    history: List<History.Entry>,
    receiveClipboard: Boolean,
    backgroundAllowed: Boolean,
    features: FeatureState,
    actions: HomeActions,
) {
    val connected = state is BridgeState.Connected
    AppBackground {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
        ) {
            TopBar(actions)
            Spacer(Modifier.height(8.dp))
            MacHero(name = macName, state = state)
            Spacer(Modifier.height(28.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                ActionTile(
                    icon = Icons.Rounded.ContentPaste, label = "Send clipboard\nto Mac",
                    tile = BridgeTheme.colors.blueTile, tint = BridgeTheme.colors.blueIcon,
                    enabled = connected, onClick = actions.sendClipboard, modifier = Modifier.weight(1f),
                )
                ActionTile(
                    icon = Icons.Rounded.UploadFile, label = "Send files\nto Mac",
                    tile = BridgeTheme.colors.greenTile, tint = BridgeTheme.colors.greenIcon,
                    enabled = connected, onClick = actions.sendFiles, modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(18.dp))
            RecentCard(history, actions)
            Spacer(Modifier.height(18.dp))
            SectionTitle("On your Mac")
            FeaturesCard(features, actions)
            Spacer(Modifier.height(18.dp))
            SectionTitle("Settings")
            SettingsCard(receiveClipboard, backgroundAllowed, actions)
            Spacer(Modifier.height(20.dp))
            Text(
                if (features.autoClipAvailable && features.autoClip) "Copy anything here and it's ready to paste on your Mac."
                else "Tip: after copying, tap Share in the clipboard bubble and choose Mac.",
                style = Muted.copy(fontSize = MaterialTheme.typography.bodySmall.fontSize),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun TopBar(actions: HomeActions) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(Brand),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.SyncAlt, null, tint = Color.White, modifier = Modifier.size(20.dp)) }
        Spacer(Modifier.width(10.dp))
        Text("PixelBridge", style = MaterialTheme.typography.titleMedium, color = BridgeTheme.colors.text)
        Spacer(Modifier.weight(1f))
        Box {
            IconButton(
                onClick = { menu = true },
                modifier = Modifier.clip(CircleShape).background(BridgeTheme.colors.card.copy(alpha = 0.7f)),
            ) { Icon(Icons.Rounded.MoreVert, "More", tint = BridgeTheme.colors.text) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Reconnect") }, onClick = { menu = false; actions.reconnect() })
                DropdownMenuItem(text = { Text("Scan new pairing code") }, onClick = { menu = false; actions.scanQr() })
                DropdownMenuItem(text = { Text("Enter pairing code") }, onClick = { menu = false; actions.enterCode() })
                DropdownMenuItem(text = { Text("Unpair this Mac") }, onClick = { menu = false; actions.unpair() })
            }
        }
    }
}

@Composable
fun MacHero(name: String, state: BridgeState, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        MacIllustration(active = state is BridgeState.Connected, modifier = Modifier.padding(top = 16.dp))
        Spacer(Modifier.height(18.dp))
        Text(
            name, style = MaterialTheme.typography.titleLarge, color = BridgeTheme.colors.text,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        StatusPill(state)
    }
}

@Composable
private fun StatusPill(state: BridgeState) {
    val c = BridgeTheme.colors
    val (color, label) = when (state) {
        is BridgeState.Connected -> c.online to "Connected · Wi-Fi"
        is BridgeState.Connecting -> c.waiting to "Connecting…"
        BridgeState.Searching -> c.waiting to "Looking for your Mac…"
        BridgeState.NotPaired -> c.muted to "Not paired"
        is BridgeState.Error -> c.offline to state.message
    }
    val pulse = rememberInfiniteTransition(label = "pulse")
    val a by pulse.animateFloat(0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")
    Row(
        Modifier
            .clip(CircleShape)
            .background(c.card.copy(alpha = if (c.dark) 0.6f else 0.75f))
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(8.dp)
                .alpha(if (state is BridgeState.Connected) 1f else a)
                .clip(CircleShape).background(color)
        )
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = c.text)
    }
}

/** A stylised MacBook: dark bezel, gradient "wallpaper", aluminium base. Greyed out when offline. */
@Composable
fun MacIllustration(active: Boolean, modifier: Modifier = Modifier) {
    val c = BridgeTheme.colors
    Canvas(modifier.size(width = 236.dp, height = 156.dp)) {
        val w = size.width
        val baseH = 12.dp.toPx()
        val screenW = w * 0.80f
        val screenH = size.height - baseH - 10.dp.toPx()
        val left = (w - screenW) / 2
        val bezel = 6.dp.toPx()

        // soft floor shadow
        drawOval(
            Brush.radialGradient(listOf(Color.Black.copy(alpha = if (c.dark) 0.45f else 0.16f), Color.Transparent),
                center = Offset(w / 2, size.height - 4.dp.toPx()), radius = w / 2),
            topLeft = Offset(0f, size.height - 14.dp.toPx()), size = Size(w, 18.dp.toPx()),
        )
        // lid
        drawRoundRect(c.laptopBezel, Offset(left, 0f), Size(screenW, screenH), CornerRadius(12.dp.toPx()))
        // wallpaper
        val sx = left + bezel
        val sy = bezel
        val sw = screenW - bezel * 2
        val sh = screenH - bezel * 2
        val wall = if (active) {
            Brush.linearGradient(listOf(Color(0xFF8EC5FF), Color(0xFF3E5BFF), Color(0xFF7B3FF2)),
                start = Offset(sx, sy), end = Offset(sx + sw, sy + sh))
        } else {
            Brush.linearGradient(listOf(Color(0xFFB9C0D2), Color(0xFF8E96AC)),
                start = Offset(sx, sy), end = Offset(sx + sw, sy + sh))
        }
        drawRoundRect(wall, Offset(sx, sy), Size(sw, sh), CornerRadius(7.dp.toPx()))
        // flowing ribbons on the wallpaper
        val ribbon = Color.White.copy(alpha = if (active) 0.32f else 0.22f)
        clipRect(sx, sy, sx + sw, sy + sh) { for (i in 0..2) {
            val y0 = sy + sh * (0.55f + i * 0.13f)
            val p = Path().apply {
                moveTo(sx, y0)
                cubicTo(sx + sw * 0.3f, y0 - sh * 0.45f, sx + sw * 0.6f, y0 + sh * 0.25f, sx + sw, y0 - sh * 0.35f)
            }
            drawPath(p, ribbon.copy(alpha = ribbon.alpha * (1f - i * 0.25f)), style = Stroke(width = (10 - i * 3).dp.toPx()))
        } }
        drawCircle(
            Brush.radialGradient(listOf(Color.White.copy(alpha = 0.35f), Color.Transparent),
                center = Offset(sx + sw * 0.7f, sy + sh * 0.3f), radius = sw * 0.35f),
            radius = sw * 0.35f, center = Offset(sx + sw * 0.7f, sy + sh * 0.3f),
        )
        // camera
        drawCircle(Color(0xFF3A4050), radius = 1.6.dp.toPx(), center = Offset(w / 2, bezel / 2 + 0.5f))
        // base
        val baseTop = screenH
        drawRoundRect(
            Brush.verticalGradient(listOf(c.laptopBase, c.laptopBase.copy(alpha = 0.85f)), startY = baseTop, endY = baseTop + baseH),
            Offset(0f, baseTop), Size(w, baseH), CornerRadius(3.dp.toPx(), 3.dp.toPx()),
        )
        drawRoundRect(
            Color.Black.copy(alpha = 0.12f), Offset(w / 2 - 22.dp.toPx(), baseTop), Size(44.dp.toPx(), 4.dp.toPx()),
            CornerRadius(2.dp.toPx()),
        )
        drawRoundRect(
            Color.Black.copy(alpha = 0.10f), Offset(10.dp.toPx(), baseTop + baseH - 2.dp.toPx()),
            Size(w - 20.dp.toPx(), 2.dp.toPx()), CornerRadius(1.dp.toPx()),
        )
    }
}

@Composable
private fun ActionTile(
    icon: ImageVector, label: String, tile: Color, tint: Color,
    enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier,
) {
    val c = BridgeTheme.colors
    Surface(
        onClick = onClick,
        modifier = modifier.alpha(if (enabled) 1f else 0.55f),
        shape = RoundedCornerShape(24.dp),
        color = c.card,
        border = BorderStroke(1.dp, c.cardBorder),
    ) { Column(Modifier.padding(18.dp)) {
        Box(
            Modifier.size(48.dp).clip(RoundedCornerShape(15.dp)).background(tile),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = tint, modifier = Modifier.size(26.dp)) }
        Spacer(Modifier.height(16.dp))
        Text(label, style = MaterialTheme.typography.titleMedium, color = c.text)
    } }
}

@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val c = BridgeTheme.colors
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(c.card)
            .border(1.dp, c.cardBorder, RoundedCornerShape(24.dp))
    ) { content() }
}

@Composable
private fun RecentCard(history: List<History.Entry>, actions: HomeActions) {
    val c = BridgeTheme.colors
    Card {
        Column(Modifier.padding(vertical = 8.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Recent", style = MaterialTheme.typography.titleMedium, color = c.text, modifier = Modifier.weight(1f))
                if (history.isNotEmpty()) TextButton(onClick = actions.clearHistory) { Text("Clear") }
                else Spacer(Modifier.height(48.dp))
            }
            if (history.isEmpty()) {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconTile(Icons.Rounded.Inventory2, c.amberTile, c.amberIcon)
                    Spacer(Modifier.width(14.dp))
                    Text("Things you copy or send between your Mac and phone will show up here.", style = Muted)
                }
                Spacer(Modifier.height(8.dp))
            } else {
                history.take(5).forEach { HistoryRow(it, onClick = { actions.openEntry(it) }) }
            }
        }
    }
}

@Composable
private fun HistoryRow(e: History.Entry, onClick: () -> Unit) {
    val c = BridgeTheme.colors
    val (icon, tile, tint) = when (e.kind) {
        History.Kind.CLIP_IN, History.Kind.CLIP_OUT -> Triple(Icons.Rounded.ContentPaste, c.blueTile, c.blueIcon)
        History.Kind.IMAGE_IN, History.Kind.IMAGE_OUT -> Triple(Icons.Rounded.Image, c.violetTile, c.violetIcon)
        History.Kind.FILE_IN, History.Kind.FILE_OUT -> Triple(Icons.Rounded.Description, c.greenTile, c.greenIcon)
    }
    val verb = when (e.kind) {
        History.Kind.CLIP_IN -> "Copied from Mac"
        History.Kind.CLIP_OUT -> "Sent to Mac clipboard"
        History.Kind.IMAGE_IN -> "Image copied from Mac"
        History.Kind.IMAGE_OUT -> "Image sent to Mac"
        History.Kind.FILE_IN -> "File received from Mac"
        History.Kind.FILE_OUT -> "File sent to Mac"
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(icon, tile, tint)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(e.title.ifBlank { "Empty" }, style = MaterialTheme.typography.bodyLarge, color = c.text,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("$verb · ${ago(e.time)}", style = Muted.copy(fontSize = MaterialTheme.typography.bodySmall.fontSize),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Icon(if (e.incoming) Icons.Rounded.SouthWest else Icons.Rounded.NorthEast, null,
            tint = c.muted, modifier = Modifier.size(18.dp))
    }
}

private fun ago(t: Long): String {
    val now = System.currentTimeMillis()
    return if (now - t < 60_000) "Just now"
    else DateUtils.getRelativeTimeSpanString(t, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()
}

@Composable
private fun IconTile(icon: ImageVector, tile: Color, tint: Color, size: Int = 44) {
    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape((size * 0.32f).dp)).background(tile),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = tint, modifier = Modifier.size((size * 0.52f).dp)) }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = BridgeTheme.colors.text,
        modifier = Modifier.padding(start = 6.dp, bottom = 10.dp))
}

@Composable
private fun FeaturesCard(f: FeatureState, actions: HomeActions) {
    val c = BridgeTheme.colors
    Card {
        Column(Modifier.padding(vertical = 6.dp)) {
            if (f.autoClipAvailable) {
                SettingRow(Icons.Rounded.ContentCopy, c.blueTile, c.blueIcon, "Copy here, paste on Mac",
                    "Phone copies go to your Mac automatically", onClick = { actions.setAutoClipboard(!f.autoClip) }) {
                    BridgeSwitch(f.autoClip, actions.setAutoClipboard)
                }
            } else {
                SettingRow(Icons.Rounded.ContentCopy, c.blueTile, c.blueIcon, "Copy here, paste on Mac",
                    "One-time setup from your Mac", onClick = actions.explainAutoClipboard, badge = "Set up")
            }
            Divider()
            if (f.notificationAccess) {
                SettingRow(Icons.Rounded.NotificationsActive, c.violetTile, c.violetIcon, "Notifications",
                    "Show and reply from your Mac", onClick = { actions.setMirrorNotifications(!f.mirrorNotifications) }) {
                    BridgeSwitch(f.mirrorNotifications, actions.setMirrorNotifications)
                }
                Divider()
                SettingRow(Icons.Rounded.Call, c.greenTile, c.greenIcon, "Ring on Mac",
                    "Your Mac rings when a call comes in", onClick = { actions.setCallAlerts(!f.callAlerts) }) {
                    BridgeSwitch(f.callAlerts, actions.setCallAlerts)
                }
                if (f.callAlerts && !f.answerGranted) {
                    Divider()
                    SettingRow(Icons.Rounded.Call, c.greenTile, c.greenIcon, "Answer from Mac",
                        "Pick up calls from your Mac", onClick = actions.requestAnswerCalls, badge = "Allow")
                }
            } else {
                SettingRow(Icons.Rounded.NotificationsActive, c.violetTile, c.violetIcon, "Notifications & calls",
                    "Allow notification access", onClick = actions.openNotificationAccess, badge = "Allow")
            }
            Divider()
            SettingRow(Icons.Rounded.Sms, c.amberTile, c.amberIcon, "Text message history",
                if (f.smsGranted) "Open Messages from the Mac menu" else "Allow access to SMS",
                onClick = actions.requestSms, badge = if (f.smsGranted) null else "Allow")
        }
    }
}

@Composable
private fun BridgeSwitch(checked: Boolean, onChange: (Boolean) -> Unit) = Switch(
    checked = checked, onCheckedChange = onChange,
    colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary),
)

@Composable
private fun SettingsCard(receiveClipboard: Boolean, backgroundAllowed: Boolean, actions: HomeActions) {
    val c = BridgeTheme.colors
    Card {
        Column(Modifier.padding(vertical = 6.dp)) {
            SettingRow(Icons.Rounded.FolderOpen, c.greenTile, c.greenIcon, "Received files",
                "Downloads › PixelBridge", onClick = actions.openReceived)
            Divider()
            SettingRow(Icons.Rounded.ContentPaste, c.blueTile, c.blueIcon, "Copy from Mac automatically",
                "Mac copies go straight to this phone", onClick = { actions.setReceiveClipboard(!receiveClipboard) }) {
                Switch(
                    checked = receiveClipboard, onCheckedChange = actions.setReceiveClipboard,
                    colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary),
                )
            }
            Divider()
            SettingRow(Icons.Rounded.TouchApp, c.violetTile, c.violetIcon, "Quick Settings tile",
                "One tap sends your clipboard", onClick = actions.addTile)
            if (!backgroundAllowed) {
                Divider()
                SettingRow(Icons.Rounded.BatteryChargingFull, c.amberTile, c.amberIcon, "Stay connected",
                    "Allow PixelBridge to run in the background", onClick = actions.allowBackground)
            }
        }
    }
}

@Composable
private fun Divider() = HorizontalDivider(
    Modifier.padding(start = 78.dp, end = 20.dp), color = BridgeTheme.colors.cardBorder,
)

@Composable
private fun SettingRow(
    icon: ImageVector, tile: Color, tint: Color, title: String, subtitle: String,
    onClick: () -> Unit, badge: String? = null, trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(icon, tile, tint)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                color = BridgeTheme.colors.text)
            Text(subtitle, style = Muted.copy(fontSize = MaterialTheme.typography.bodySmall.fontSize))
        }
        if (trailing != null) trailing()
        else if (badge != null) Text(
            badge, style = MaterialTheme.typography.labelLarge.copy(fontSize = MaterialTheme.typography.labelMedium.fontSize),
            color = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.primary)
                .padding(horizontal = 14.dp, vertical = 7.dp),
        )
        else Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = BridgeTheme.colors.muted)
    }
}

// endregion

// region Pairing

@Composable
fun PairScreen(state: BridgeState, onScan: () -> Unit, onEnterCode: () -> Unit) {
    val c = BridgeTheme.colors
    AppBackground {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(40.dp))
            MacIllustration(active = false)
            Spacer(Modifier.height(28.dp))
            Text("Connect your Mac", style = MaterialTheme.typography.headlineMedium, color = c.text)
            Spacer(Modifier.height(8.dp))
            Text(
                if (state is BridgeState.Error) state.message
                else "Share your clipboard and send files between this phone and your Mac over Wi-Fi.",
                style = Muted.copy(fontSize = MaterialTheme.typography.bodyLarge.fontSize,
                    color = if (state is BridgeState.Error) c.offline else c.muted),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(28.dp))
            Card {
                Column(Modifier.padding(vertical = 10.dp)) {
                    Step(1, "Open PixelBridge on your Mac", "It lives in the menu bar")
                    Step(2, "Click the menu bar icon", "Then choose Pair Phone…")
                    Step(3, "Scan the QR code", "Phone and Mac must share a Wi-Fi network")
                }
            }
            Spacer(Modifier.height(32.dp))
            Button(
                onClick = onScan,
                modifier = Modifier.fillMaxWidth().height(58.dp),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            ) {
                Icon(Icons.Rounded.QrCodeScanner, null)
                Spacer(Modifier.width(10.dp))
                Text("Scan QR code", style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = onEnterCode,
                modifier = Modifier.fillMaxWidth().height(58.dp),
                shape = CircleShape,
                border = BorderStroke(1.5.dp, c.cardBorder),
            ) { Text("Enter code instead", style = MaterialTheme.typography.labelLarge, color = c.text) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Step(n: Int, title: String, subtitle: String) {
    val c = BridgeTheme.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).border(1.5.dp, c.cardBorder, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Text("$n", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary) }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium), color = c.text)
            Text(subtitle, style = Muted.copy(fontSize = MaterialTheme.typography.bodySmall.fontSize))
        }
    }
}

@Composable
fun EnterCodeDialog(initialHost: String, onDismiss: () -> Unit, onPair: (code: String, host: String) -> Unit) {
    var code by remember { mutableStateOf("") }
    var host by remember { mutableStateOf(initialHost) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enter pairing code") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("It's under the QR code in the Mac app.", style = Muted)
                OutlinedTextField(
                    code, { code = it.uppercase() }, label = { Text("Code") }, singleLine = true,
                    placeholder = { Text("ABCD-EFGH-…") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
                    shape = RoundedCornerShape(14.dp),
                )
                OutlinedTextField(
                    host, { host = it.trim() }, label = { Text("Mac IP address (optional)") }, singleLine = true,
                    placeholder = { Text("192.168.1.20") }, shape = RoundedCornerShape(14.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onPair(code, host) }, enabled = code.isNotBlank()) { Text("Pair") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        shape = RoundedCornerShape(28.dp),
        containerColor = BridgeTheme.colors.card,
    )
}

@Composable
fun AutoClipHelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Copy here, paste on Mac") },
        text = {
            Text("Android only lets the app you're looking at read the clipboard, so this needs a one-time " +
                "setup from your Mac:\n\n1. On this phone: Settings › System › Developer options › turn on Wireless debugging.\n" +
                "2. On your Mac: PixelBridge menu › Set Up Phone…, and follow the steps.\n\n" +
                "After that, anything you copy here is ready to paste on your Mac.")
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Got it") } },
        shape = RoundedCornerShape(28.dp),
        containerColor = BridgeTheme.colors.card,
    )
}

@Composable
fun ConfirmPairDialog(name: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pair with $name?") },
        text = { Text("Only continue if this code is showing on your own Mac.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Pair") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        shape = RoundedCornerShape(28.dp),
        containerColor = BridgeTheme.colors.card,
    )
}

// endregion
