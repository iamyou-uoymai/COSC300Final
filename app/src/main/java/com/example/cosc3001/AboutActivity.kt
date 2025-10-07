package com.example.cosc3001

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class AboutActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val colorScheme = darkColorScheme(
                primary = Color(0xFF6A5AE0),
                onPrimary = Color.White,
                primaryContainer = Color(0xFF4B43B2),
                onPrimaryContainer = Color.White,
                secondary = Color(0xFF3D8BFF),
                onSecondary = Color.White,
                background = Color(0xFF101425),
                onBackground = Color(0xFFF2F4F8),
                surface = Color(0xFF1A2133),
                onSurface = Color(0xFFF2F4F8),
                surfaceVariant = Color(0xFF1A2133),
                onSurfaceVariant = Color(0xFFB0B8C4)
            )
            MaterialTheme(colorScheme = colorScheme) {
                AboutScreen(onHomeClick = { startActivity(Intent(this@AboutActivity, HomeActivity::class.java)) })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onHomeClick: () -> Unit = {}) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("About") },
                actions = {
                    IconButton(onClick = onHomeClick) {
                        Icon(imageVector = Icons.Default.Home, contentDescription = "Home")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Logo
            Image(
                painter = painterResource(id = R.drawable.ic_launcher_foreground),
                contentDescription = "DinoScope AR Logo",
                modifier = Modifier.size(120.dp)
            )

            // App Name
            Text(
                text = "DinoScope AR",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            // Version
            Text(
                text = "Version ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // About Text
            Text(
                text = """
                    DinoScope AR brings prehistoric creatures to life through Augmented Reality!
                    Simply scan a QR code, and a fully detailed 3D dinosaur will appear in your real-world environment.

                    Each model is scientifically inspired and includes descriptive information about the dinosaur’s history, appearance, and behavior. Whether you’re a student, teacher, or dinosaur enthusiast, DinoScope AR makes learning interactive and fun.
                """.trimIndent(),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Justify,
                modifier = Modifier.fillMaxWidth()
            )

            // Features
            Text(
                text = "Features include:",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = """
                    - QR-based AR Tracking — Scan a QR code to anchor lifelike 3D dinosaurs in your environment.
                    - High-quality 3D Models — View accurate representations of famous dinosaurs like the T. rex, Triceratops, and Velociraptor.
                    - Text-to-Speech Narration — Hear fun facts about each dinosaur, powered by Google Gemini.
                    - Cloud Integration — Models and descriptions are stored securely using Supabase.
                    - Analytics — Tracks user interactions and viewing times to improve the AR experience.
                """.trimIndent(),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth()
            )

            // Powered By
            Text(
                text = "Powered by:",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = """
                    - Google ARCore
                    - SceneView
                    - Supabase
                    - Google Gemini AI
                    - OpenCV and ZXing
                """.trimIndent(),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth()
            )

            // Developer Note
            Text(
                text = "Developer Note:",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = """
                    This app combines cutting-edge AR technology with educational storytelling to make prehistoric exploration immersive and engaging for all ages.
                """.trimIndent(),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Justify,
                modifier = Modifier.fillMaxWidth()
            )

            // Copyright
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "© 2025 DinoScope AR Team",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun AboutScreenPreview() {
    MaterialTheme {
        AboutScreen()
    }
}
