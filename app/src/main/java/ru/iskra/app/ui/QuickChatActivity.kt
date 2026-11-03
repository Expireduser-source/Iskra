package ru.iskra.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Floating compact chat that opens over the current app when the character is tapped. */
class QuickChatActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            IskraTheme {
                val outside = remember { MutableInteractionSource() }
                val inside = remember { MutableInteractionSource() }
                Box(
                    Modifier.fillMaxSize()
                        .clickable(interactionSource = outside, indication = null) { finish() }
                        .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Column(
                        Modifier.fillMaxWidth().fillMaxHeight(0.55f)
                            .clickable(interactionSource = inside, indication = null) { }
                            .background(MaterialTheme.colorScheme.background, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                            .padding(top = 8.dp)
                    ) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Искра", fontSize = 18.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                            TextButton(onClick = { finish() }) { Text("Свернуть") }
                        }
                        ChatScreen(Modifier.weight(1f), compact = true)
                    }
                }
            }
        }
    }
}
