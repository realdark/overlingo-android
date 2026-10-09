package com.overlingo.android.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.overlingo.android.R
import com.overlingo.android.ocr.ImageLoader
import com.overlingo.android.data.Language
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun CameraScreen(
    target: Language,
    onPhoto: (Bitmap, Int) -> Unit,
    onImageUri: (Uri) -> Unit,
    onTargetSelected: (String) -> Unit,
    onOpenText: () -> Unit,
    onOpenSettings: () -> Unit,
    onError: (UiMessage) -> Unit,
) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasPermission = it
    }
    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(onImageUri)
    }
    val openGallery = {
        pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (hasPermission) {
            CameraPreviewWithControls(onPhoto = onPhoto, onGallery = openGallery, onError = onError)
        } else {
            NoPermission(
                onRequest = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                onGallery = openGallery,
            )
        }

        // Top bar: target language, text translation, settings
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            TargetLanguageChip(target = target, onSelected = onTargetSelected, onDark = true)
            Spacer(Modifier.weight(1f))
            DarkIconButton(onClick = onOpenText) {
                Icon(Icons.Default.TextFields, contentDescription = stringResource(R.string.translate_text))
            }
            Spacer(Modifier.size(8.dp))
            DarkIconButton(onClick = onOpenSettings) {
                Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings))
            }
        }
    }
}

@Composable
private fun CameraPreviewWithControls(
    onPhoto: (Bitmap, Int) -> Unit,
    onGallery: () -> Unit,
    onError: (UiMessage) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val controller = remember {
        LifecycleCameraController(context).apply {
            setEnabledUseCases(CameraController.IMAGE_CAPTURE)
            // ~12 MP: the screen shows only the middle of the frame (see ImageLoader.cropToPreview),
            // so ~7 MP remain after cropping - small text is still clear enough
            // for ML Kit. Larger (48-200 MP) photos would take hundreds of MB of memory for no gain.
            imageCaptureResolutionSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(Size(4032, 3024), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                )
                .build()
            // By default the camera favors speed (noisier, blurrier photo); for text,
            // quality matters more than half a second.
            imageCaptureMode = ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
        }
    }
    // Separate thread for decoding the photo - so it doesn't block the UI.
    val executor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(lifecycleOwner) {
        controller.bindToLifecycle(lifecycleOwner)
        onDispose { controller.unbind() }
    }
    DisposableEffect(Unit) {
        onDispose { executor.shutdown() }
    }

    // The camera rotates the photo based on the orientation sensor. When the phone
    // lies almost flat (shooting a sheet on a table), the sensor can't
    // determine the orientation and keeps the old one - the photo came out sideways.
    // So we track when the phone is "lying flat" and then rotate to match the screen.
    val deviceFlat = remember { AtomicBoolean(false) }
    DisposableEffect(Unit) {
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                deviceFlat.set(orientation == ORIENTATION_UNKNOWN)
            }
        }
        if (listener.canDetectOrientation()) listener.enable()
        onDispose { listener.disable() }
    }
    val view = LocalView.current

    var capturing by remember { mutableStateOf(false) }
    var torch by remember { mutableStateOf(false) }
    // When the app goes to the background, the camera (and the torch) turn off -
    // the icon must reflect that, otherwise after returning it shows "on" while the torch is off.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) torch = false
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(Modifier.fillMaxSize()) {
        // Tap = focus, two fingers = zoom (built into PreviewView + controller).
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    this.controller = controller
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        Text(
            stringResource(R.string.camera_hint),
            color = Color.White,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(bottom = 130.dp)
                .background(Color(0x66000000), MaterialTheme.shapes.small)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )

        // Bottom bar: gallery - shutter - torch
        Row(
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(bottom = 24.dp),
        ) {
            DarkIconButton(onClick = onGallery, size = 56) {
                Icon(Icons.Default.PhotoLibrary, contentDescription = stringResource(R.string.pick_from_gallery))
            }

            ShutterButton(busy = capturing) {
                if (capturing) return@ShutterButton
                capturing = true
                // Rotation "as on screen": relative to the display's current rotation.
                val screenRotation = controller.cameraInfo
                    ?.getSensorRotationDegrees(view.display?.rotation ?: Surface.ROTATION_0)
                val callback = object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        val rotation = if (deviceFlat.get() && screenRotation != null) {
                            screenRotation
                        } else {
                            image.imageInfo.rotationDegrees
                        }
                        val bitmap = try {
                            // Only what's shown on screen (see ImageLoader.cropToPreview).
                            val crop = image.cropRect
                            ImageLoader.cropToPreview(
                                image.toBitmap(), image.width, image.height,
                                crop.left, crop.top, crop.right, crop.bottom,
                            )
                        } catch (e: Exception) {
                            capturing = false
                            onError(UiMessage(R.string.error_capture, e.message.orEmpty()))
                            return
                        } finally {
                            image.close()
                        }
                        onPhoto(bitmap, rotation)
                        capturing = false
                    }

                    override fun onError(exception: ImageCaptureException) {
                        capturing = false
                        onError(UiMessage(R.string.error_capture, exception.message.orEmpty()))
                    }
                }
                try {
                    controller.takePicture(executor, callback)
                } catch (e: IllegalStateException) {
                    // The camera isn't ready yet (or there is none) - show a message instead of crashing.
                    capturing = false
                    onError(UiMessage(R.string.error_capture, e.message.orEmpty()))
                }
            }

            DarkIconButton(
                onClick = {
                    torch = !torch
                    controller.enableTorch(torch)
                },
                size = 56,
            ) {
                Icon(
                    if (torch) Icons.Default.FlashOn else Icons.Default.FlashOff,
                    contentDescription = stringResource(R.string.flashlight),
                )
            }
        }
    }
}

@Composable
private fun ShutterButton(busy: Boolean, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(76.dp)
            .border(4.dp, Color.White, CircleShape)
            .padding(8.dp)
            .background(if (busy) Color.Gray else Color.White, CircleShape)
            .clickable(enabled = !busy, onClick = onClick),
    ) {
        if (busy) CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
    }
}

@Composable
fun DarkIconButton(onClick: () -> Unit, size: Int = 44, content: @Composable () -> Unit) {
    FilledTonalIconButton(
        onClick = onClick,
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = Color(0x99000000),
            contentColor = Color.White,
        ),
        modifier = Modifier.size(size.dp),
        content = content,
    )
}

@Composable
private fun NoPermission(onRequest: () -> Unit, onGallery: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize().padding(32.dp),
    ) {
        Text(
            stringResource(R.string.camera_permission_needed),
            color = Color.White,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRequest) { Text(stringResource(R.string.allow_camera)) }
        Spacer(Modifier.height(8.dp))
        Button(onClick = onGallery) { Text(stringResource(R.string.pick_from_gallery)) }
    }
}
