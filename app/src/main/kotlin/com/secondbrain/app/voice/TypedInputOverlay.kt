package com.secondbrain.app.voice

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.secondbrain.app.AppColors
import com.secondbrain.app.MonoTextStyle
import com.secondbrain.model.EmailAddressValidator

/**
 * D-008/D-054's "sanctioned typing escape hatch," as a UI: the model asked for
 * a value it should not trust ASR for, [VoiceController.handleTypedInput]
 * spoke the prompt, and this is where the user types the answer. Overlays the
 * screen the same way [ProposalWindow] does — the two never appear at once in
 * practice (a gate suspends the whole turn, so nothing calls
 * `request_typed_input` while one is open), but neither depends on that being
 * true to render correctly.
 */
@Composable
fun TypedInputOverlay(controller: VoiceController) {
    val state by controller.state.collectAsState()
    val request = state.pendingTypedInput ?: return
    var value by remember(request) { mutableStateOf("") }
    var error by remember(request) { mutableStateOf<String?>(null) }

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 420.dp).padding(24.dp),
            color = AppColors.Surface,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, AppColors.BorderStrong),
        ) {
            Column(Modifier.padding(24.dp)) {
                Text(request.prompt, style = MaterialTheme.typography.bodyMedium, color = AppColors.Ink)
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it; error = null },
                    singleLine = true,
                    textStyle = MonoTextStyle,
                    isError = error != null,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = AppColors.Dangling)
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { controller.cancelTypedInput() }) { Text("Cancel") }
                    Button(onClick = {
                        val shapeError = shapeErrorFor(request.kind, value)
                        if (shapeError != null) error = shapeError else controller.submitTypedInput(value)
                    }) { Text("Submit") }
                }
            }
        }
    }
}

private fun shapeErrorFor(kind: String, value: String): String? = when (kind) {
    "email" -> if (EmailAddressValidator.isValid(value)) null else "That doesn't look like an email address."
    else -> if (value.isBlank()) "Enter a value." else null
}
