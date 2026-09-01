package com.secondbrain.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.secondbrain.agent.ConfirmationGate
import com.secondbrain.app.vault.VaultBrowserController
import com.secondbrain.app.vault.VaultScreen
import com.secondbrain.app.voice.ProposalWindow
import com.secondbrain.app.voice.TypedInputOverlay
import com.secondbrain.app.voice.VoiceController
import com.secondbrain.app.voice.VoiceScreen
import com.secondbrain.ports.CalendarPort

/** ARCHITECTURE.md §7 Step 4: "window + navigation rail with two destinations." */
enum class Screen { VOICE, VAULT }

/**
 * The composition root's UI. All real state lives in [VoiceController] and
 * [VaultBrowserController]; this function owns only which screen is showing
 * and which note a Voice-screen "Open note" chip most recently asked for.
 */
@Composable
fun App(
    voiceController: VoiceController,
    vaultController: VaultBrowserController,
    /**
     * Since Step 5. Always non-null (see `VoiceController`'s own doc on why
     * `ConfirmationGate` is always constructed); the window it drives simply
     * never appears if nothing is ever proposed.
     */
    confirmationGate: ConfirmationGate,
    /** Optional: powers `ProposalWindow`'s live conflict recompute (EC-C4). Null when Google isn't configured. */
    calendarPort: CalendarPort? = null,
) {
    var screen by remember { mutableStateOf(Screen.VOICE) }
    var pendingNotePath by remember { mutableStateOf<String?>(null) }

    SecondBrainTheme {
        Box(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxSize().background(AppColors.Canvas)) {
                NavigationRail(containerColor = AppColors.Canvas) {
                    NavRailEntry(label = "Voice", selected = screen == Screen.VOICE) { screen = Screen.VOICE }
                    NavRailEntry(label = "Vault", selected = screen == Screen.VAULT) { screen = Screen.VAULT }
                }
                Box(Modifier.weight(1f).fillMaxSize()) {
                    when (screen) {
                        Screen.VOICE -> VoiceScreen(voiceController) { path ->
                            pendingNotePath = path
                            screen = Screen.VAULT
                        }
                        Screen.VAULT -> VaultScreen(vaultController, notePathToOpen = pendingNotePath)
                    }
                }
            }

            // Since Step 5: overlays whichever screen is showing - a proposal
            // or a typed-input request can be open regardless of which one the
            // user is looking at. ProposalWindow/TypedInputOverlay each render
            // nothing when there is nothing pending.
            ProposalWindow(confirmationGate, calendarPort)
            TypedInputOverlay(voiceController)
        }
    }
}

@Composable
private fun NavRailEntry(label: String, selected: Boolean, onClick: () -> Unit) {
    NavigationRailItem(
        selected = selected,
        onClick = onClick,
        icon = {
            Box(
                Modifier.size(8.dp).clip(CircleShape).background(if (selected) AppColors.Blue else AppColors.Muted),
            )
        },
        label = { Text(label) },
    )
}
