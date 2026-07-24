package com.fluxx.android

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fluxx.android.engine.FluxxEngine

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FluxxTheme {
                PingScreen()
            }
        }
    }
}

@Composable
fun FluxxTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF6200EE),
            background = Color(0xFF121212),
            surface = Color(0xFF1E1E1E),
            onPrimary = Color.White,
            onBackground = Color(0xFFE1E1E1)
        ),
        content = content
    )
}

@Composable
fun PingScreen() {
    var count by remember { mutableIntStateOf(0) }
    var nativeResult by remember { mutableStateOf("Tap to start ping") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF0F0C1B), Color(0xFF121212))
                )
            )
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "FLUXX JNI PING",
            fontSize = 28.sp,
            fontWeight = FontWeight.ExtraBold,
            color = Color(0xFF00E5FF),
            fontFamily = FontFamily.Monospace,
            letterSpacing = 2.sp,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Text(
            text = "JNI Connection Test & Diagnostic Panel",
            fontSize = 14.sp,
            color = Color.Gray,
            modifier = Modifier.padding(bottom = 48.dp)
        )

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1B1827))
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Input Value: $count",
                    fontSize = 18.sp,
                    color = Color.White,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                
                Text(
                    text = "Native Engine Output:",
                    fontSize = 14.sp,
                    color = Color.Gray,
                    modifier = Modifier.padding(bottom = 4.dp)
                )

                Text(
                    text = nativeResult,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF00E5FF)
                )
            }
        }

        Button(
            onClick = {
                try {
                    val result = FluxxEngine.ping(count)
                    nativeResult = result.toString()
                    count = result
                } catch (e: UnsatisfiedLinkError) {
                    nativeResult = "Link Error: Library not loaded"
                    Log.e("MainActivity", "JNI Error: ", e)
                } catch (e: Exception) {
                    nativeResult = "Error: ${e.message}"
                    Log.e("MainActivity", "JNI Error: ", e)
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6200EE)),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text(
                text = "Ping Engine (Send $count)",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
