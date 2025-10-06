package com.example.cosc3001

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.Image
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.ar.core.Frame
import com.google.ar.core.Pose
import com.google.ar.core.exceptions.NotYetAvailableException
import com.google.ar.core.Config
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.Result
import com.google.zxing.ResultPoint
import com.google.zxing.common.HybridBinarizer
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.environment.Environment
import io.github.sceneview.loaders.EnvironmentLoader
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.node.ModelNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import org.opencv.core.Point
import java.io.File
import kotlin.math.sqrt
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.storage.Storage
import io.github.jan.supabase.storage.storage
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request

class MainActivity : AppCompatActivity() {

    private val androidTTS: AndroidTTS by lazy { AndroidTTS(this) }
    private val chatBackend: ChatBackend by lazy {
        val systemPrompt = """
            You are a knowledgeable, child-friendly virtual guide for an Augmented Reality dinosaur museum exhibit.
            ONLY provide information about these dinosaurs: Tyrannosaurus Rex (T-Rex), Spinosaurus, Triceratops, Velociraptor.
            If asked about any other creature, historical topic, technology, person, place, or unrelated subject, politely refuse and explain you are limited to the exhibit dinosaurs.
            Keep answers concise (<= 3 sentences), speech-friendly (no markdown, no lists unless asked), and factually grounded.
            If the user asks a very broad question like "tell me something" within context, share an interesting, accurate fact about one of the four dinosaurs.
            Avoid speculation beyond well-established paleontological consensus; clarify uncertainties briefly when relevant.
            Prefer common names first (e.g. T-Rex) then optionally the full scientific name once.
        """.trimIndent()
        val gemini = GeminiChatService(
            apiKey = BuildConfig.GEMINI_API_KEY,
            model = BuildConfig.GEMINI_MODEL,
            systemPrompt = systemPrompt
        )
        DinoBoundedChatBackend(gemini)
    }

    private enum class PlacementStyle { MinimalLift, BottomOnPlane }
    private enum class ModelOrientation { ZUpToY, YUp }

    // Choose model to sit directly on the QR plane by default
    private val placementStyle = PlacementStyle.BottomOnPlane
    private val modelOrientation = ModelOrientation.ZUpToY
    private val minimalLiftMeters = 0.02f

    // Carry ZXing result with the source image dimensions and rotation used for decoding
    private data class QRDecode(
        val result: Result,
        val width: Int,
        val height: Int,
        val rotationDeg: Int
    )

    private lateinit var arSceneView: ARSceneView
    private var modelPlaced = false
    private var qrOverlay: ImageView? = null
    private var overlayHidden = false

    // Cache the static environment
    private var staticEnvironment: Environment? = null

    // ZXing reader reused across frames to reduce allocations
    private val qrReader: MultiFormatReader = MultiFormatReader()
    private val hintsFast = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)
    )
    private val hintsHard = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.ALSO_INVERTED to true
    )

    // Simple throttle and concurrency guard
    @Volatile
    private var isDecoding = false
    private var lastDecodeMs = 0L
    private val minDecodeIntervalMs = 150L

    // Supabase client and config (explicit type for lazy inference)
    private val supabase get() = SupabaseProvider.client
    private val modelsTable = "models"
    private val modelsBucket = "glb_files"

    @Serializable
    private data class ModelRow(
        val uuid: String? = null,
        val qr_name: String,
        val description: String? = null,
        val scale_factor: Float? = null
    )

    // QR pose estimator helper
    private val qrPoseEstimator = QRPoseEstimator()

    // Tunable correction to fix lateral offset (meters). Positive shifts to the marker's local +X.
    private val lateralCorrectionMeters = 0.04f

    // Pose stabilization to avoid jitter: wait for N consistent poses before anchoring
    private val stabilizationSize = 8
    private val stabilizationPosThreshold = 0.008f  // 8 mm
    private val stabilizationRotThresholdDeg = 2.0f // 2 degrees
    private val poseBuffer = ArrayDeque<Pose>(stabilizationSize)

    // New: stabilization timeout to avoid never placing
    private val stabilizationTimeoutMs = 1500L
    private var stabilizationStartedAtMs: Long? = null

    private val REQ_CAMERA = 1001

    // Expandable chat FAB logic
    private var isChatExpanded = false

    // Target expanded width in dp
    private val chatExpandedWidthDp = 300
    private var chatDisabledByTTS = false
    private var chatRequestInFlight = false

    // Loading indicator visibility state
    private var modelLoadingVisible = false

    // Track current anchor node for reload/reset
    private var currentAnchorNode: AnchorNode? = null

    // Session id to invalidate in-flight fetches when user resets scanning
    private var scanSessionId: Long = 0L

    // Configurable delay (ms) after model placement to show reload button early (even if TTS still speaking)
    private val reloadEarlyDelayMs = 5000L

    // Track current focused dinosaur for UI hint
    private var currentFocusDino: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // Home button navigation
        findViewById<android.widget.ImageButton>(R.id.homeButton)?.setOnClickListener {
            val intent = Intent(this, HomeActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(intent)
        }
        // Request camera permission to enable QR scanning
        ensureCameraPermission()
        // Speak initial instruction guiding user to scan QR code
        androidTTS.speak("Point the camera at a dinosaur QR code to load its 3D model")

        // Initialize OpenCV (avoid deprecated initDebug)
        val openCvOk: Boolean = try {
            OpenCVLoader.initLocal()
        } catch (_: Throwable) {
            try {
                System.loadLibrary("opencv_java4")
                true
            } catch (_: Throwable) {
                false
            }
        }
        if (!openCvOk) {
            Log.e("OpenCV", "Unable to load OpenCV (initLocal/System.loadLibrary failed)")
        } else {
            Log.d("OpenCV", "OpenCV loaded successfully (local)")
        }

        arSceneView = findViewById(R.id.arSceneView)
        qrOverlay = findViewById(R.id.qrOverlay)
        loadQrOverlayGraphic()
        setupChatFabUI()
        // Subscribe to TTS state changes to disable chat while speaking
        androidTTS.addSpeechStateListener { speaking ->
            runOnUiThread { updateChatEnabledState(!speaking) }
        }

        // Optional: configure AR session
        arSceneView.planeRenderer.isEnabled = false // disable plane visualization (dots)
        arSceneView.session?.let { session ->
            try {
                val cfg = session.config
                cfg.planeFindingMode = Config.PlaneFindingMode.DISABLED
                session.configure(cfg)
                Log.d("ARConfig", "Plane finding disabled")
            } catch (t: Throwable) {
                Log.w("ARConfig", "Unable to disable plane finding: ${t.message}")
            }
        }

        // Load static IBL environment ONCE and disable AR light estimation
        val engine = arSceneView.engine
        lifecycleScope.launch {
            try {
                val envLoader = EnvironmentLoader(engine, this@MainActivity)
                val environment = withContext(Dispatchers.IO) {
                    envLoader.loadKTX1Environment("neutral_ibl.ktx")
                }
                if (environment != null) {
                    environment.indirectLight?.intensity = 50000f // strong, stable IBL
                    staticEnvironment = environment
                    arSceneView.environment = environment
                }
                // Disable ARCore light estimation
                arSceneView.lightEstimator = null
                Log.d("IBL", "Static KTX environment applied, AR light estimation disabled.")
            } catch (e: Exception) {
                Log.e("IBL", "Failed to load IBL: ${e.message}", e)
                arSceneView.lightEstimator = null
            }
        }

        startFrameUpdates()
        // Network connectivity / timeout probe (OkHttp)
        runNetworkTimeoutProbe()

        val reloadBtn = findViewById<ImageButton>(R.id.reloadScanButton)
        reloadBtn.setOnClickListener { hideReloadButton(); resetQrScanning(haptic = true) }
    }

    private fun runNetworkTimeoutProbe() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(java.time.Duration.ofSeconds(3))
                    .readTimeout(java.time.Duration.ofSeconds(5))
                    .writeTimeout(java.time.Duration.ofSeconds(5))
                    .build()
                val request = Request.Builder()
                    .url("https://www.googleapis.com/")
                    .get()
                    .build()
                val t0 = SystemClock.elapsedRealtime()
                client.newCall(request).execute().use { resp ->
                    val elapsed = SystemClock.elapsedRealtime() - t0
                    Log.d("NetProbe", "HTTP GET status=${resp.code} in ${elapsed}ms (OkHttp timeouts OK)")
                }
            } catch (t: Throwable) {
                Log.e("NetProbe", "OkHttp probe failed: ${t.javaClass.simpleName}: ${t.message}", t)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Network probe failed: ${t.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun startFrameUpdates() {
        arSceneView.onSessionUpdated = { session, frame ->
            // Convert frame to image for QR scanning
            processFrameForQRCode(frame)
        }
    }

    private fun processFrameForQRCode(frame: Frame) {
        if (modelPlaced) return
        // Throttle and avoid parallel decodes to keep FPS smooth
        val now = SystemClock.elapsedRealtime()
        if (isDecoding || (now - lastDecodeMs) < minDecodeIntervalMs) return

        try {
            val image = frame.acquireCameraImage() // returns Image in YUV_420_888 format
            // Immediately mark decoding in progress and copy out minimal data
            isDecoding = true
            val (nv21, imgWidth, imgHeight) = imageToNV21(image)
            image.close()

            // Capture camera parameters we need for pose, to avoid using Frame off-thread
            val intrinsics = frame.camera.imageIntrinsics
            val focalLength = intrinsics.focalLength.clone()
            val principalPoint = intrinsics.principalPoint.clone()
            val cameraPose = frame.camera.pose

            // Heavy work off the session callback thread
            lifecycleScope.launch(Dispatchers.Default) {
                try {
                    // 1) Fast NV21 decode with minimal hints (0° and 90° only)
                    var qr = scanQRCodeNV21Fast(nv21, imgWidth, imgHeight)
                    // 2) Harder attempt only if needed
                    if (qr == null) qr =
                        scanQRCodeNV21Fast(nv21, imgWidth, imgHeight, useHardHints = true)

                    qr?.let { qrDecoded ->
                        val qrText = qrDecoded.result.text.trim()
                        Log.d("QRCode", "Detected QR code: ${qrText}")
                        val points = qrDecoded.result.resultPoints
                        Log.d("QRCode", "resultPoints count: ${points?.size ?: 0}")
                        if (points != null && points.size >= 3) {
                            val qrSize = 0.075184 // meters (7.5184 cm)
                            val imagePoints = points.map { pt: ResultPoint ->
                                Point(
                                    pt.x.toDouble(),
                                    pt.y.toDouble()
                                )
                            }.toMutableList()
                            if (points.size == 3) {
                                val a = imagePoints[0]
                                val b = imagePoints[1]
                                val c = imagePoints[2]
                                var dX = b.x + (c.x - a.x)
                                var dY = b.y + (c.y - a.y)
                                dX = dX.coerceIn(0.0, qrDecoded.width.toDouble())
                                dY = dY.coerceIn(0.0, qrDecoded.height.toDouble())
                                imagePoints.add(Point(dX, dY))
                                Log.d(
                                    "QRCode",
                                    "Estimated fourth corner (clamped): ${imagePoints[3]}"
                                )
                            }

                            // Map points from rotated source back to original image coordinates if rotated 90° CCW
                            if (qrDecoded.rotationDeg == 90) {
                                // Original image dimensions (CPU image): imgWidth x imgHeight
                                val mapped = imagePoints.map { p ->
                                    val xr = p.x
                                    val yr = p.y
                                    val xo = yr
                                    val yo = (imgHeight - 1).toDouble() - xr
                                    Point(xo, yo)
                                }
                                imagePoints.clear()
                                imagePoints.addAll(mapped)
                                Log.d(
                                    "QRCode",
                                    "Mapped ${mapped.size} points from 90° CCW back to original coords"
                                )
                            }

                            // Use helper to estimate marker and upright poses
                            val cam = QRPoseEstimator.CameraIntrinsics(focalLength, principalPoint)
                            val estimate =
                                qrPoseEstimator.estimatePose(imagePoints, qrSize, cam, cameraPose)
                            if (!estimate.success) {
                                Log.w(
                                    "QRPose",
                                    "Pose estimation failed: ${estimate.reason ?: "unknown"}"
                                )
                                return@launch
                            }
                            val markerPose = estimate.markerCenterPose!!
                            val finalPose = estimate.uprightPose ?: markerPose

                            // Apply lateral correction along the QR marker's local X axis
                            val correctedPose = offsetPoseUsingAxes(
                                markerPose,
                                finalPose,
                                lateralCorrectionMeters,
                                0f,
                                0f
                            )
                            Log.d(
                                "Pose",
                                "Applied lateral correction ${lateralCorrectionMeters}m along marker X"
                            )

                            // Stabilization: collect corrected poses and anchor when stable
                            poseBuffer.addLast(correctedPose)
                            if (poseBuffer.size > stabilizationSize) poseBuffer.removeFirst()
                            if (stabilizationStartedAtMs == null) stabilizationStartedAtMs =
                                SystemClock.elapsedRealtime()

                            val stablePose = if (poseBuffer.size == stabilizationSize) {
                                val avg = averagePose(poseBuffer.toList())
                                val stable = poseBuffer.all { p ->
                                    val posDist = positionDistance(p, avg)
                                    val ang = quaternionAngleDeg(
                                        p.rotationQuaternion,
                                        avg.rotationQuaternion
                                    )
                                    posDist <= stabilizationPosThreshold && ang <= stabilizationRotThresholdDeg
                                }
                                if (stable) avg else null
                            } else null

                            val fallbackDue =
                                (SystemClock.elapsedRealtime() - (stabilizationStartedAtMs
                                    ?: 0L)) > stabilizationTimeoutMs
                            val poseToUse = when {
                                stablePose != null -> stablePose
                                fallbackDue && poseBuffer.isNotEmpty() -> averagePose(poseBuffer.toList())
                                else -> null
                            }

                            if (poseToUse == null) {
                                Log.d(
                                    "Pose",
                                    "Waiting for stable pose: ${poseBuffer.size}/$stabilizationSize samples"
                                )
                                return@launch
                            }

                            // Optional: debug plane at poseToUse center
                            // anchorDebugPlaneAtMarker(poseToUse, qrSize.toFloat())

                            // Supabase: fetch model row and public URL, then anchor the remote GLB with scale
                            try {
                                val sessionId = scanSessionId
                                withContext(Dispatchers.Main) { showModelLoading() }
                                val result =
                                    withContext(Dispatchers.IO) { fetchModelAndUrl(qrText) }
                                // If session invalidated during fetch, abort gracefully
                                if (sessionId != scanSessionId) {
                                    Log.d("Supabase", "Discarding model fetch for outdated session $sessionId")
                                    withContext(Dispatchers.Main) { hideModelLoading() }
                                    return@launch
                                }
                                if (result == null) {
                                    Log.w("Supabase", "No model row found for qr_name='${qrText}'")
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(
                                            this@MainActivity,
                                            "No model found for '$qrText'",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                } else {
                                    val (row, url) = result
                                    val modelDesc = row.description?.trim().orEmpty()
                                    val scale = row.scale_factor ?: 0.15f
                                    val placed = anchorModelFromUrl(poseToUse, url, scale, qrText)
                                    if (sessionId != scanSessionId) {
                                        Log.d("Supabase", "Discarding anchor result for outdated session $sessionId")
                                        withContext(Dispatchers.Main) { hideModelLoading() }
                                        return@launch
                                    }
                                    if (placed) {
                                        modelPlaced = true
                                        // Set chat focus to this dinosaur using qrText (backend will normalize)
                                        try { chatBackend.setActiveFocusDino(qrText) } catch (_: Exception) {}
                                        currentFocusDino = canonicalDisplayName(qrText)
                                        runOnUiThread { updateChatHint() }
                                        poseBuffer.clear()
                                        stabilizationStartedAtMs = null
                                        Log.d(
                                            "Supabase",
                                            "Model placed from URL with scale=$scale (stabilized or fallback)"
                                        )
                                        val sessionIdLocal = sessionId
                                        // Schedule early reload button appearance
                                        lifecycleScope.launch {
                                            delay(reloadEarlyDelayMs)
                                            if (sessionIdLocal == scanSessionId) {
                                                runOnUiThread { showReloadButton() }
                                            }
                                        }
                                        // Speak immediate instruction; description (if any) chains after delay but doesn't block early reload
                                        androidTTS.speak("Point the camera to the 3D model", flush = true, onDone = {
                                            if (modelDesc.isNotBlank()) {
                                                lifecycleScope.launch {
                                                    delay(5000)
                                                    if (sessionIdLocal == scanSessionId) {
                                                        androidTTS.speak(modelDesc, flush = false, onDone = {
                                                            // Ensure reload is visible (idempotent)
                                                            runOnUiThread { if (sessionIdLocal == scanSessionId) showReloadButton() }
                                                        })
                                                    }
                                                }
                                            } else {
                                                // If no description, rely on scheduled reload button appearance.
                                            }
                                        })
                                    } else {
                                        Log.e("Supabase", "Failed to place model from URL")
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e("Supabase", "Error fetching/placing model: ${e.message}", e)
                            } finally {
                                withContext(Dispatchers.Main) { hideModelLoading() }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("ARFrame", "Error processing frame off-thread: ${e.message}")
                } finally {
                    lastDecodeMs = SystemClock.elapsedRealtime()
                    isDecoding = false
                }
            }
        } catch (_: NotYetAvailableException) {
            // Image not ready this frame, try again next frame
        } catch (e: Exception) {
            Log.e("ARFrame", "Error preparing frame for decode: ${e.message}")
            isDecoding = false
        }
    }

    // Fetch models row and its public GLB URL
    private suspend fun fetchModelAndUrl(qrName: String): Pair<ModelRow, String>? =
        withContext(Dispatchers.IO) {
            try {
                val rows = supabase.postgrest[modelsTable]
                    .select {
                        filter { ModelRow::qr_name eq qrName }
                        limit(1)
                    }
                    .decodeList<ModelRow>()
                val row = rows.firstOrNull() ?: return@withContext null
                val path = "${qrName}.glb"
                val url = supabase.storage.from(modelsBucket).publicUrl(path)
                Log.d("Supabase", "Public URL for '${qrName}': ${url}")
                Pair(row, url)
            } catch (e: Exception) {
                Log.e("Supabase", "Query failed: ${e.message}", e)
                null
            }
        }

    // Load a remote GLB by public URL and anchor it with the given scale. Returns true if placed.
    private suspend fun anchorModelFromUrl(
        pose: Pose,
        url: String,
        scaleFactor: Float,
        qrName: String? = null
    ): Boolean {
        val engine = arSceneView.engine
        val session = arSceneView.session ?: return false
        val anchor = try {
            session.createAnchor(pose)
        } catch (e: Exception) {
            Log.e("ARModel", "Failed to create anchor: ${e.message}", e)
            return false
        }
        Log.d("ARModel", "Loading model from URL: ${url} with scale=${scaleFactor}")
        return try {
            staticEnvironment?.let { arSceneView.environment = it }
            val modelLoader = ModelLoader(engine, this@MainActivity)
            var modelInstance = withContext(Dispatchers.IO) {
                try {
                    modelLoader.loadModelInstance(url)
                } catch (e: Exception) {
                    Log.w("ARModel", "Direct URL load failed (${e.message}).", e)
                    null
                }
            }
            if (modelInstance == null && qrName != null) {
                // Fallback: download from Supabase public storage and load from cache
                try {
                    val path = if (qrName.endsWith(".glb", true)) qrName else "$qrName.glb"
                    Log.d(
                        "ARModel",
                        "Downloading fallback from Supabase: bucket=$modelsBucket, path=$path"
                    )
                    val bytes = withContext(Dispatchers.IO) {
                        supabase.storage.from(modelsBucket).downloadPublic(path)
                    }
                    val outFile = File(cacheDir, path)
                    outFile.parentFile?.mkdirs()
                    withContext(Dispatchers.IO) { outFile.writeBytes(bytes) }
                    Log.d("ARModel", "Loading model from cache: ${outFile.absolutePath}")
                    modelInstance = withContext(Dispatchers.IO) {
                        try {
                            modelLoader.loadModelInstance(outFile.absolutePath)
                        } catch (_: Throwable) {
                            null
                        }
                    }
                } catch (t: Throwable) {
                    Log.e("ARModel", "Fallback download/cache load failed: ${t.message}", t)
                }
            }
            if (modelInstance == null) return false
            // Attach nodes on the main thread to avoid threading issues
            withContext(Dispatchers.Main) {
                val anchorNode = AnchorNode(engine, anchor)
                currentAnchorNode = anchorNode
                val modelNode = ModelNode(modelInstance).apply {
                    scale = io.github.sceneview.math.Scale(scaleFactor)
                    val baseX = when (modelOrientation) {
                        ModelOrientation.ZUpToY -> -90.0f
                        ModelOrientation.YUp -> 0f
                    }
                    rotation = Rotation(x = baseX + 180.0f, y = 0f, z = 0f)
                    position = when (placementStyle) {
                        PlacementStyle.MinimalLift -> Position(0f, minimalLiftMeters, 0f)
                        PlacementStyle.BottomOnPlane -> {
                            val offsetY = try {
                                val bbox = modelInstance.asset.boundingBox
                                val center = bbox.center
                                val halfExtent = bbox.halfExtent
                                (halfExtent[1] - center[1]) * scaleFactor
                            } catch (e: Exception) {
                                Log.w("ARModel", "Bounding box unavailable: ${e.message}")
                                0f
                            }
                            Position(0f, offsetY, 0f)
                        }
                    }
                }
                anchorNode.addChildNode(modelNode)
                arSceneView.addChildNode(anchorNode)
                try { modelNode.playAnimation(0, loop = true) } catch (_: Throwable) {}
                hideQrOverlay()
            }
            true
        } catch (e: Exception) {
            Log.e("ARModel", "Error anchoring model from URL: ${e.message}", e)
            false
        }
    }

    private fun imageToNV21(image: Image): Triple<ByteArray, Int, Int> {
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)
        // NV21 layout: Y + V + U
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)
        return Triple(nv21, image.width, image.height)
    }

    private fun scanQRCodeNV21Fast(
        nv21: ByteArray,
        width: Int,
        height: Int,
        useHardHints: Boolean = false
    ): QRDecode? {
        val hints = if (useHardHints) hintsHard else hintsFast
        qrReader.setHints(hints)

        var src = PlanarYUVLuminanceSource(
            nv21, width, height, 0, 0, width, height, false
        )

        // Try 0° then 90° only (180°==0°, 270°==90° for QR)
        repeat(2) { idx ->
            val binary = BinaryBitmap(HybridBinarizer(src))
            try {
                val result = qrReader.decodeWithState(binary)
                Log.d(
                    "QRCode",
                    "Decoded on NV21 rotation #$idx (w=${src.width}, h=${src.height}), hard=$useHardHints"
                )
                return QRDecode(result, src.width, src.height, if (idx == 0) 0 else 90)
            } catch (_: Exception) {
                qrReader.reset()
            }
            // rotate for next attempt
            if (src.isRotateSupported) {
                val rotated = try {
                    src.rotateCounterClockwise()
                } catch (_: Exception) {
                    null
                }
                if (rotated is PlanarYUVLuminanceSource) {
                    src = rotated
                } else return null
            }
        }
        return null
    }

    // Offsets basePose by a local translation defined in axesPose's local frame, preserving basePose rotation
    private fun offsetPoseUsingAxes(
        axesPose: Pose,
        basePose: Pose,
        dx: Float,
        dy: Float,
        dz: Float
    ): Pose {
        // Compute world-space delta of the local offset using axesPose rotation
        val p0 = axesPose.transformPoint(floatArrayOf(0f, 0f, 0f))
        val p1 = axesPose.transformPoint(floatArrayOf(dx, dy, dz))
        val delta = floatArrayOf(p1[0] - p0[0], p1[1] - p0[1], p1[2] - p0[2])
        val newT = floatArrayOf(
            basePose.tx() + delta[0],
            basePose.ty() + delta[1],
            basePose.tz() + delta[2]
        )
        return Pose(newT, basePose.rotationQuaternion)
    }

    // Compute Euclidean distance between pose translations
    private fun positionDistance(a: Pose, b: Pose): Float {
        val dx = a.tx() - b.tx()
        val dy = a.ty() - b.ty()
        val dz = a.tz() - b.tz()
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    // Compute angle in degrees between two quaternions [x,y,z,w] (shortest arc)
    private fun quaternionAngleDeg(q1: FloatArray, q2: FloatArray): Float {
        var dot = q1[0] * q2[0] + q1[1] * q2[1] + q1[2] * q2[2] + q1[3] * q2[3]
        if (dot < 0f) dot = -dot
        dot = dot.coerceIn(-1f, 1f)
        val angle = 2.0 * Math.acos(dot.toDouble())
        return Math.toDegrees(angle).toFloat()
    }

    // Average pose: average position and average quaternion with hemisphere correction
    private fun averagePose(poses: List<Pose>): Pose {
        val n = poses.size
        var sx = 0f
        var sy = 0f
        var sz = 0f
        val q0 = poses[0].rotationQuaternion
        var qx = 0f
        var qy = 0f
        var qz = 0f
        var qw = 0f
        for (p in poses) {
            sx += p.tx(); sy += p.ty(); sz += p.tz()
            val q = p.rotationQuaternion
            var x = q[0]
            var y = q[1]
            var z = q[2]
            var w = q[3]
            // Hemisphere alignment to avoid canceling
            val dot = q0[0] * x + q0[1] * y + q0[2] * z + q0[3] * w
            if (dot < 0f) {
                x = -x; y = -y; z = -z; w = -w
            }
            qx += x; qy += y; qz += z; qw += w
        }
        val pos = floatArrayOf(sx / n, sy / n, sz / n)
        val norm = sqrt(qx * qx + qy * qy + qz * qz + qw * qw)
        val qAvg =
            if (norm > 1e-6f) floatArrayOf(qx / norm, qy / norm, qz / norm, qw / norm) else q0
        return Pose(pos, qAvg)
    }

    private fun ensureCameraPermission() {
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            Log.w("Permissions", "CAMERA permission not granted. Requesting…")
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
        } else {
            Log.d("Permissions", "CAMERA permission already granted")
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA) {
            val granted =
                grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            Log.d("Permissions", "CAMERA permission result: $granted")
            if (!granted) {
                Toast.makeText(
                    this,
                    "Camera permission is required for AR and QR scanning",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            androidTTS.shutdown()
        } catch (_: Exception) {
        }
    }

    private fun loadQrOverlayGraphic() {
        try {
            assets.open("qr.png").use { input ->
                val bmp = BitmapFactory.decodeStream(input)
                qrOverlay?.setImageBitmap(bmp)
            }
        } catch (e: Exception) {
            Log.w("Overlay", "qr.png not found in assets: ${e.message}")
        }
    }

    private fun hideQrOverlay() {
        if (overlayHidden) return
        val view = qrOverlay ?: return
        overlayHidden = true
        view.animate()?.cancel()
        view.animate()?.alpha(0f)?.setDuration(220)?.withEndAction {
            view.visibility = View.GONE
            Log.d("Overlay", "QR overlay hidden immediately after anchoring")
        }?.start()
    }

    private fun showQrOverlayAgain() {
        val view = qrOverlay ?: return
        overlayHidden = false
        view.visibility = View.VISIBLE
        view.alpha = 0f
        view.animate()?.cancel()
        view.animate()?.alpha(0.18f)?.setDuration(200)?.start()
    }

    private fun setupChatFabUI() {
        val container = findViewById<android.widget.LinearLayout>(R.id.chatFabContainer)
        val icon = findViewById<ImageView>(R.id.chatFabIcon)
        val edit = findViewById<android.widget.EditText>(R.id.chatEditText)
        val send = findViewById<ImageButton>(R.id.chatSendButton)

        val collapsedWidthPx = dpToPx(56)
        val expandedWidthPx = dpToPx(chatExpandedWidthDp)

        fun rotateIcon(expanding: Boolean) {
            val from = if (expanding) 0f else 45f
            val to = if (expanding) 45f else 0f
            ObjectAnimator.ofFloat(icon, View.ROTATION, from, to).apply {
                duration = 220
                start()
            }
        }

        fun expand() {
            if (isChatExpanded || chatDisabledByTTS || chatRequestInFlight) return
            isChatExpanded = true
            container.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            edit.visibility = View.VISIBLE
            send.visibility = View.VISIBLE
            edit.alpha = 0f
            send.alpha = 0f
            rotateIcon(true)
            val anim = ValueAnimator.ofInt(container.layoutParams.width, expandedWidthPx)
            anim.addUpdateListener { va ->
                container.layoutParams.width = va.animatedValue as Int
                container.requestLayout()
            }
            anim.duration = 220
            anim.start()
            edit.animate().alpha(1f).setDuration(180).start()
            send.animate().alpha(1f).setDuration(180).start()
            // Focus + keyboard
            edit.postDelayed({
                edit.requestFocus()
                val imm =
                    getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
            }, 240)
        }

        fun collapse(clear: Boolean = false, haptic: Boolean = true) {
            if (!isChatExpanded) return
            isChatExpanded = false
            if (haptic) container.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            rotateIcon(false)
            val anim = ValueAnimator.ofInt(container.layoutParams.width, collapsedWidthPx)
            anim.addUpdateListener { va ->
                container.layoutParams.width = va.animatedValue as Int
                container.requestLayout()
            }
            anim.duration = 200
            anim.start()
            edit.animate().alpha(0f).setDuration(140).withEndAction {
                edit.visibility = View.GONE
                if (clear) edit.setText("")
            }.start()
            send.animate().alpha(0f).setDuration(140).withEndAction {
                send.visibility = View.GONE
            }.start()
            val imm =
                getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(edit.windowToken, 0)
        }

        container.setOnClickListener { if (!isChatExpanded) expand() }
        icon.setOnClickListener { if (chatDisabledByTTS) return@setOnClickListener; if (!isChatExpanded) expand() else collapse() }
        send.setOnClickListener {
            if (chatDisabledByTTS || chatRequestInFlight) return@setOnClickListener
            val raw = edit.text?.toString().orEmpty()
            val text = raw.trim()
            if (text.isNotEmpty()) {
                val userQuestion = text // capture before clearing
                // Clear immediately so user sees the input emptied right after tapping send
                edit.setText("")
                container.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                chatRequestInFlight = true
                updateChatEnabledState(!androidTTS.isSpeaking)
                androidTTS.speak("Let me think", flush = false)
                // Collapse without waiting for animation end to clear (already cleared)
                collapse(clear = false, haptic = false)
                lifecycleScope.launch {
                    val answer = withContext(Dispatchers.IO) { chatBackend.sendMessage(userQuestion) }
                    androidTTS.speak(answer)
                    chatRequestInFlight = false
                    updateChatEnabledState(!androidTTS.isSpeaking)
                }
            } else {
                collapse(haptic = true)
            }
        }
        edit.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                send.performClick(); true
            } else false
        }
        container.post {
            container.layoutParams.width = collapsedWidthPx
            container.requestLayout()
            updateChatEnabledState(!androidTTS.isSpeaking)
            updateChatHint()
        }
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    private fun updateChatEnabledState(enabled: Boolean) {
        val container = findViewById<android.widget.LinearLayout>(R.id.chatFabContainer) ?: return
        val edit = findViewById<android.widget.EditText>(R.id.chatEditText)
        val send = findViewById<ImageButton>(R.id.chatSendButton)
        chatDisabledByTTS = !enabled
        if (!enabled || chatRequestInFlight) {
            // If currently expanded, collapse without extra haptic
            if (isChatExpanded) {
                // reuse collapse logic indirectly by simulating icon click safely
                isChatExpanded = false
                edit.visibility = View.GONE
                send.visibility = View.GONE
                val collapsedWidthPx = dpToPx(56)
                container.layoutParams.width = collapsedWidthPx
                container.requestLayout()
            }
            container.alpha = 0.5f
            container.isEnabled = false
            container.isClickable = false
            container.contentDescription = "Chat disabled during speech"
        } else {
            container.alpha = 1f
            container.isEnabled = true
            container.isClickable = true
            container.contentDescription = "Chat input"
        }
    }

    private fun updateChatHint() {
        val edit = findViewById<android.widget.EditText>(R.id.chatEditText) ?: return
        val focus = currentFocusDino
        edit.hint = if (focus.isNullOrBlank()) "Ask..." else "Ask about $focus..."
    }

    private fun canonicalDisplayName(raw: String): String {
        val l = raw.lowercase()
        return when {
            l.contains("trex") || l.contains("t-rex") || l.contains("tyrannosaurus") -> "T-Rex"
            l.contains("spino") -> "Spinosaurus"
            l.contains("tricerat") -> "Triceratops"
            l.contains("velociraptor") || l.contains("raptor") -> "Velociraptor"
            else -> raw.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
    }

    private fun showModelLoading() {
        val container = findViewById<View>(R.id.modelLoadingContainer) ?: return
        if (modelLoadingVisible) return
        modelLoadingVisible = true
        container.alpha = 0f
        container.visibility = View.VISIBLE
        container.animate()?.cancel()
        container.animate()?.alpha(1f)?.setDuration(180)?.start()
    }

    private fun hideModelLoading() {
        val container = findViewById<View>(R.id.modelLoadingContainer) ?: return
        if (!modelLoadingVisible) return
        modelLoadingVisible = false
        container.animate()?.cancel()
        container.animate()?.alpha(0f)?.setDuration(160)?.withEndAction {
            if (!modelLoadingVisible) { // ensure not re-shown mid-animation
                container.visibility = View.GONE
            }
        }?.start()
    }

    private fun showReloadButton() {
        val btn = findViewById<ImageButton>(R.id.reloadScanButton) ?: return
        if (btn.visibility == View.VISIBLE) return
        btn.visibility = View.VISIBLE
        btn.alpha = 0f
        btn.animate()?.cancel()
        btn.animate()?.alpha(1f)?.setDuration(200)?.start()
    }

    private fun hideReloadButton() {
        val btn = findViewById<ImageButton>(R.id.reloadScanButton) ?: return
        if (btn.visibility != View.VISIBLE) return
        btn.animate()?.cancel()
        btn.animate()?.alpha(0f)?.setDuration(160)?.withEndAction { btn.visibility = View.GONE }?.start()
    }

    private fun resetQrScanning(haptic: Boolean = false) {
        if (haptic) findViewById<View>(R.id.reloadScanButton)?.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        // Always hide reload button on reset
        hideReloadButton()
        // Invalidate any in-flight fetch/anchor attempts
        scanSessionId++
        // Remove existing model/anchor
        currentAnchorNode?.let { node ->
            try { arSceneView.removeChildNode(node) } catch (_: Throwable) {}
            try { node.anchor?.detach() } catch (_: Throwable) {}
            try { node.destroy() } catch (_: Throwable) {}
        }
        currentAnchorNode = null
        modelPlaced = false
        poseBuffer.clear()
        stabilizationStartedAtMs = null
        // Reset overlay
        showQrOverlayAgain()
        // Hide any loading indicator
        hideModelLoading()
        // Provide guidance again
        chatBackend.setActiveFocusDino(null)
        currentFocusDino = null
        updateChatHint()
        androidTTS.speak("Point the camera at a dinosaur QR code to load its 3D model", flush = false)
        Log.d("Reload", "QR scanning reset; session=${scanSessionId}")
    }
}
