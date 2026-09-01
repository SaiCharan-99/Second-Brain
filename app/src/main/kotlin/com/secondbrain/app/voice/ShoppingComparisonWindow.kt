package com.secondbrain.app.voice

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPainter
import androidx.compose.ui.unit.dp
import com.secondbrain.app.AppColors
import com.secondbrain.model.Product
import java.awt.image.BufferedImage

/**
 * Stage 4 (D-098), authorized by D-094: a click-driven comparison view for
 * `commerce_prepare_list`'s candidates, instead of reading ten products aloud
 * per grocery item. Opens automatically when [VoiceController.UiState.shoppingComparison]
 * is non-null (a `commerce_prepare_list` tool result just came back) and stays
 * open across multiple picks — a photographed list is usually several items,
 * and closing after every click would be worse than the speech flow it
 * replaces. "Done" is the only way out; picking is not a commitment to
 * anything beyond the Saved Cart (Stage 5's checkout bridge is the next,
 * separate, explicit step).
 */
@Composable
fun ShoppingComparisonWindow(controller: VoiceController) {
    val state by controller.state.collectAsState()
    val requests = state.shoppingComparison ?: return

    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
        Surface(
            modifier = Modifier.width(720.dp).height(560.dp).padding(24.dp),
            color = AppColors.Surface,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, AppColors.BorderStrong),
        ) {
            Column(Modifier.padding(20.dp).fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("PICK WHAT YOU WANT", style = MaterialTheme.typography.labelLarge, color = AppColors.Blue)
                    Button(onClick = { controller.dismissComparison() }) { Text("Done") }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Tap a product to add it to your saved list. Nothing is ordered yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.Muted,
                )
                Spacer(Modifier.height(12.dp))

                LazyColumn(Modifier.weight(1f)) {
                    items(requests) { request ->
                        ComparisonRow(request, controller)
                        Spacer(Modifier.height(16.dp))
                    }
                }

                if (state.statusLine.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(state.statusLine, style = MaterialTheme.typography.bodySmall, color = AppColors.Muted)
                }
            }
        }
    }
}

@Composable
private fun ComparisonRow(request: VoiceController.ComparisonRequest, controller: VoiceController) {
    Column {
        Text(request.query, style = MaterialTheme.typography.titleSmall, color = AppColors.Ink)
        when {
            request.error != null -> Text(
                "Search failed: ${request.error}",
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.Amber,
            )
            request.candidates.isEmpty() -> Text(
                "Nothing matched.",
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.Muted,
            )
            else -> Row(
                Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                request.candidates.forEach { product ->
                    CandidateCard(product, request.query, controller)
                }
            }
        }
    }
}

@Composable
private fun CandidateCard(product: Product, query: String, controller: VoiceController) {
    var quantity by remember(product.id) { mutableIntStateOf(1) }
    var added by remember(product.id) { mutableStateOf(false) }
    var thumbnail by remember(product.id) { mutableStateOf<BufferedImage?>(null) }

    LaunchedEffect(product.imageUrl) {
        product.imageUrl?.let { thumbnail = ImageCache.load(it) }
    }

    Surface(
        modifier = Modifier.width(150.dp),
        color = AppColors.Canvas,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, AppColors.Border),
    ) {
        Column(Modifier.padding(10.dp)) {
            Box(
                Modifier.fillMaxWidth().height(80.dp).background(AppColors.Surface, RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center,
            ) {
                val bmp = thumbnail
                if (bmp != null) {
                    Image(painter = bmp.toPainter(), contentDescription = product.name, modifier = Modifier.fillMaxSize())
                } else {
                    Text("no photo", style = MaterialTheme.typography.labelSmall, color = AppColors.Muted)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(product.name, style = MaterialTheme.typography.labelMedium, maxLines = 2, color = AppColors.Ink)
            product.size?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = AppColors.Muted) }
            Text(product.price.format(), style = MaterialTheme.typography.labelMedium, color = AppColors.Ink)
            if (!product.available) {
                Text("Out of stock", style = MaterialTheme.typography.labelSmall, color = AppColors.Amber)
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = { if (quantity > 1) quantity-- }, modifier = Modifier.size(24.dp)) {
                    Text("-", style = MaterialTheme.typography.labelLarge)
                }
                Text("$quantity", style = MaterialTheme.typography.labelMedium)
                IconButton(onClick = { quantity++ }, modifier = Modifier.size(24.dp)) {
                    Text("+", style = MaterialTheme.typography.labelLarge)
                }
            }
            Spacer(Modifier.height(4.dp))
            if (added) {
                OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) { Text("Added") }
            } else {
                Button(
                    onClick = {
                        controller.addToSavedCart(product, query, quantity)
                        added = true
                    },
                    enabled = product.available,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Add") }
            }
        }
    }
}
