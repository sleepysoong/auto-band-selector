package com.sleepysoong.autobandselector.debug

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens

class BackdropSampleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { BackdropSample() }
    }
}

@Composable
private fun BackdropSample() {
    val backdrop = rememberLayerBackdrop()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
            drawRect(Color(0xFFDFE7EB))
            repeat(12) { index ->
                val x = size.width * index / 8f
                drawLine(
                    color = if (index % 2 == 0) Color(0xFF6091A4) else Color.White,
                    start = Offset(x, 0f),
                    end = Offset(x - size.width / 2f, size.height),
                    strokeWidth = 18.dp.toPx()
                )
            }
        }
        Box(
            Modifier
                .padding(24.dp)
                .size(280.dp, 180.dp)
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { RoundedCornerShape(28.dp) },
                    effects = {
                        blur(8.dp.toPx())
                        lens(20.dp.toPx(), 32.dp.toPx())
                    },
                    onDrawSurface = {
                        drawRect(Color.White.copy(alpha = if (Build.VERSION.SDK_INT >= 31) 0.3f else 1f))
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            BasicText("Backdrop 2.0.1", style = TextStyle(color = Color.Black, fontSize = 22.sp))
        }
    }
}
