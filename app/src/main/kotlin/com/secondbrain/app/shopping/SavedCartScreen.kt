package com.secondbrain.app.shopping

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.secondbrain.app.AppColors
import com.secondbrain.model.Money
import com.secondbrain.model.SavedItem
import com.secondbrain.app.voice.ImageCache
import androidx.compose.ui.graphics.toPainter
import java.awt.image.BufferedImage

/**
 * Stage 4/5 (D-098/D-099): the persistent Saved Cart — everything picked from
 * [com.secondbrain.app.voice.ShoppingComparisonWindow] across however many
 * photographed/dictated lists, until checked out or removed. Reachable from
 * the nav rail like Vault/Voice, not an overlay — unlike the comparison
 * window this has no reason to interrupt anything, and a shopping list is
 * exactly the kind of thing meant to be revisited over days (WF-4's own
 * grocery-session framing, extended past one sitting).
 */
@Composable
fun SavedCartScreen(controller: SavedCartController) {
    val state by controller.state.collectAsState()

    LaunchedEffect(Unit) { controller.refresh() }

    Column(Modifier.fillMaxSize().background(AppColors.Canvas).padding(24.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("SAVED CART", style = MaterialTheme.typography.labelLarge, color = AppColors.Blue)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { controller.selectAll() }) { Text("Select all") }
                OutlinedButton(onClick = { controller.clearSelection() }) { Text("Clear") }
            }
        }
        Spacer(Modifier.height(16.dp))

        if (state.items.isEmpty()) {
            Text(
                "Nothing saved yet. Photograph or dictate a grocery list from Voice, then pick from the comparison view.",
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.Muted,
            )
            return@Column
        }

        LazyColumn(Modifier.weight(1f)) {
            items(state.items, key = { it.id }) { item ->
                SavedCartRow(item, item.id in state.selected, controller)
                Spacer(Modifier.height(8.dp))
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    "${state.selected.size} selected, ${Money.ofPaise(controller.totalPaise).format()}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.Ink,
                )
                if (state.statusLine.isNotBlank()) {
                    Text(state.statusLine, style = MaterialTheme.typography.bodySmall, color = AppColors.Muted)
                }
            }
            Button(
                onClick = { controller.checkoutSelected() },
                enabled = state.commerceAvailable && !state.checkingOut && state.selected.isNotEmpty(),
            ) { Text(if (state.checkingOut) "Adding…" else "Checkout selected") }
        }
        if (!state.commerceAvailable) {
            Text(
                "Commerce isn't configured, so items can be saved here but not checked out yet.",
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.Amber,
            )
        }
    }
}

@Composable
private fun SavedCartRow(item: SavedItem, selected: Boolean, controller: SavedCartController) {
    var thumbnail by remember(item.id) { mutableStateOf<BufferedImage?>(null) }
    LaunchedEffect(item.imageUrl) { item.imageUrl?.let { thumbnail = ImageCache.load(it) } }

    Surface(
        color = AppColors.Surface,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, AppColors.Border),
    ) {
        Row(Modifier.padding(10.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = selected, onCheckedChange = { controller.toggleSelected(item.id) })
            Box(
                Modifier.size(48.dp).background(AppColors.Canvas, RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center,
            ) {
                val bmp = thumbnail
                if (bmp != null) Image(painter = bmp.toPainter(), contentDescription = item.name, modifier = Modifier.fillMaxSize())
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.bodyMedium, color = AppColors.Ink)
                Text(
                    listOfNotNull(item.size, "from \"${item.sourceQuery}\"").joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = AppColors.Muted,
                )
            }
            Text(item.unitPrice.format(), style = MaterialTheme.typography.bodyMedium, color = AppColors.Ink)
            Spacer(Modifier.width(12.dp))
            IconButton(onClick = { controller.updateQuantity(item.id, item.quantity - 1) }, modifier = Modifier.size(24.dp)) {
                Text("-", style = MaterialTheme.typography.labelLarge)
            }
            Text("${item.quantity}", style = MaterialTheme.typography.bodyMedium)
            IconButton(onClick = { controller.updateQuantity(item.id, item.quantity + 1) }, modifier = Modifier.size(24.dp)) {
                Text("+", style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.width(8.dp))
            Text(item.lineTotal.format(), style = MaterialTheme.typography.bodyMedium, color = AppColors.Ink)
            Spacer(Modifier.width(12.dp))
            OutlinedButton(onClick = { controller.remove(item.id) }) { Text("Remove") }
        }
    }
}
