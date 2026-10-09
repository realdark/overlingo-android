package com.overlingo.android.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.overlingo.android.export.OverlayStyle
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.overlingo.android.R
import com.overlingo.android.data.Language
import com.overlingo.android.ocr.TextBlock
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultScreen(
    photo: PhotoState,
    target: Language,
    style: OverlayStyle,
    speakingText: String?,
    downloadingModel: String?,
    exporting: Boolean,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onPermissionDenied: () -> Unit,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onToggleOriginal: () -> Unit,
    onToggleHidden: (Int) -> Unit,
    onShowAllHidden: () -> Unit,
    onTargetSelected: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onSpeak: (String) -> Unit,
    onCopied: () -> Unit,
) {
    var selectedBlock by rememberSaveable(photo.bitmap) { mutableStateOf<Int?>(null) }
    var showAll by rememberSaveable { mutableStateOf(false) }
    // Choosing what to read aloud: tap the blocks; they're read in the order tapped.
    var choosing by rememberSaveable(photo.bitmap) { mutableStateOf(false) }
    var chosen by rememberSaveable(photo.bitmap) { mutableStateOf(listOf<Int>()) }
    val hasResult = photo.phase == Phase.Done && photo.blocks.isNotEmpty() && photo.translations != null
    // Excluding hidden windows - they aren't read, aren't in "Full text" and aren't saved.
    val visible = remember(photo.translations, photo.hidden) { photo.visibleTranslations }
    val allTranslations = remember(visible) {
        visible.orEmpty().filter { it.isNotBlank() }.joinToString("\n\n")
    }

    fun finishChoosing() {
        choosing = false
        chosen = emptyList()
    }

    fun readChosen() {
        val text = chosen.mapNotNull { visible?.getOrNull(it) }.filter { it.isNotBlank() }.joinToString("\n\n")
        finishChoosing()
        if (text.isNotBlank()) onSpeak(text)
    }

    BackHandler(enabled = choosing) { finishChoosing() }
    // New translation (another language, new photo) - the selection is cleared.
    LaunchedEffect(hasResult) { if (!hasResult) finishChoosing() }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xCC000000)),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                title = { TargetLanguageChip(target = target, onSelected = onTargetSelected) },
                actions = {
                    IconButton(onClick = onToggleOriginal, enabled = photo.blocks.isNotEmpty()) {
                        Icon(
                            if (photo.showOriginal) Icons.Default.Translate else Icons.Default.Photo,
                            contentDescription = stringResource(
                                if (photo.showOriginal) R.string.show_translation else R.string.show_original
                            ),
                        )
                    }
                    ExportMenu(enabled = hasResult && !exporting, onSave = onSave, onShare = onShare, onDenied = onPermissionDenied)
                    IconButton(onClick = { showAll = true }, enabled = hasResult) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = stringResource(R.string.all_text))
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings))
                    }
                },
            )
        },
        floatingActionButton = {
            if (choosing) {
                ChooseToReadBar(
                    count = chosen.size,
                    onCancel = ::finishChoosing,
                    onReadAll = {
                        finishChoosing()
                        onSpeak(allTranslations)
                    },
                    onReadChosen = ::readChosen,
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 🔊: choose which blocks to read (or "All"); while reading, it stops playback.
                    if (hasResult) {
                        val speaking = speakingText != null
                        FloatingActionButton(
                            onClick = {
                                if (speaking) onSpeak(speakingText!!) else choosing = true
                            },
                        ) {
                            Icon(
                                if (speaking) Icons.Default.Stop else Icons.AutoMirrored.Filled.VolumeUp,
                                contentDescription = stringResource(
                                    if (speaking) R.string.stop_reading else R.string.choose_to_read
                                ),
                            )
                        }
                        Spacer(Modifier.size(12.dp))
                    }
                    ExtendedFloatingActionButton(
                        onClick = onBack,
                        icon = { Icon(Icons.Default.PhotoCamera, contentDescription = null) },
                        text = { Text(stringResource(R.string.new_photo)) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            PhotoWithOverlay(
                photo = photo,
                translations = visible,
                style = style,
                choosing = choosing,
                chosen = chosen,
                onBlockClick = { index ->
                    if (choosing) {
                        chosen = if (index in chosen) chosen - index else chosen + index
                    } else {
                        selectedBlock = index
                    }
                },
            )
            if (!choosing && photo.hidden.isNotEmpty() && !photo.showOriginal) {
                HiddenBlocksChip(
                    count = photo.hidden.size,
                    onShowAll = onShowAllHidden,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
                )
            }
            if (choosing) {
                Text(
                    stringResource(R.string.choose_to_read_hint),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp, start = 16.dp, end = 16.dp)
                        .background(Color(0xCC000000), RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            StatusCard(
                photo = photo,
                downloadingModel = downloadingModel,
                onRetry = onRetry,
                modifier = Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = 88.dp),
            )
        }
    }

    selectedBlock?.let { index ->
        val block = photo.blocks.getOrNull(index)
        if (block != null) {
            TextDetailsSheet(
                original = block.text,
                translation = photo.translations?.getOrNull(index).orEmpty(),
                speakingText = speakingText,
                onSpeak = onSpeak,
                onCopied = onCopied,
                onDismiss = { selectedBlock = null },
                hidden = index in photo.hidden,
                onToggleHidden = {
                    onToggleHidden(index)
                    selectedBlock = null
                },
            )
        }
    }
    if (showAll && hasResult) {
        TextDetailsSheet(
            original = photo.blocks.joinToString("\n\n") { it.text },
            translation = allTranslations,
            speakingText = speakingText,
            onSpeak = onSpeak,
            onCopied = onCopied,
            onDismiss = { showAll = false },
        )
    }
}

/**
 * The photo with translations drawn over the text. Two fingers zoom and
 * pan; the translations scale together with the photo. Tapping a block
 * opens the original and the translation.
 */
@Composable
private fun PhotoWithOverlay(
    photo: PhotoState,
    translations: List<String>?,
    style: OverlayStyle,
    choosing: Boolean,
    chosen: List<Int>,
    onBlockClick: (Int) -> Unit,
) {
    val image = remember(photo.bitmap) { photo.bitmap.asImageBitmap() }
    var scale by remember(photo.bitmap) { mutableFloatStateOf(1f) }
    var offset by remember(photo.bitmap) { mutableStateOf(Offset.Zero) }

    BoxWithConstraints(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxSize().clipToBounds(),
    ) {
        val density = LocalDensity.current
        val containerW = constraints.maxWidth.toFloat()
        val containerH = constraints.maxHeight.toFloat()
        // Scale factor photo (px) -> screen (px) at "fit entirely"
        val fit = min(containerW / photo.bitmap.width, containerH / photo.bitmap.height)
        val shownW = photo.bitmap.width * fit
        val shownH = photo.bitmap.height * fit

        // Gestures are captured on the whole screen (not just over the photo), in screen pixels.
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(photo.bitmap, shownW, shownH) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 5f)
                        val maxX = shownW * (scale - 1f) / 2f
                        val maxY = shownH * (scale - 1f) / 2f
                        offset = Offset(
                            (offset.x + pan.x).coerceIn(-maxX, maxX),
                            (offset.y + pan.y).coerceIn(-maxY, maxY),
                        )
                    }
                },
        ) {
            Box(
                modifier = Modifier
                    .size(with(density) { shownW.toDp() }, with(density) { shownH.toDp() })
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
            ) {
                Image(
                    bitmap = image,
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize(),
                )
                val measurer = rememberTextMeasurer()
                photo.blocks.forEachIndexed { index, block ->
                    val translation = translations?.getOrNull(index)
                    BlockOverlay(
                        block = block,
                        translation = translation?.takeIf { !photo.showOriginal && it.isNotBlank() },
                        fit = fit,
                        style = style,
                        measurer = measurer,
                        choosing = choosing,
                        // Position in the reading order (1, 2...) or null - not selected.
                        readingNumber = chosen.indexOf(index).takeIf { it >= 0 }?.plus(1),
                        onClick = { onBlockClick(index) },
                    )
                }
            }
        }
    }
}

/** One translated block: a dark rectangle over the original with the translation fitted inside. */
@Composable
private fun BlockOverlay(
    block: TextBlock,
    translation: String?,
    fit: Float,
    style: OverlayStyle,
    measurer: TextMeasurer,
    choosing: Boolean,
    readingNumber: Int?,
    onClick: () -> Unit,
) {
    val density = LocalDensity.current
    // Slightly larger than the text itself, so it also covers the edges of the letters.
    val pad = block.lineHeight * 0.2f
    val left = (block.box.left - pad) * fit
    val top = (block.box.top - pad) * fit
    val width = (block.box.width + 2 * pad) * fit
    val height = (block.box.height + 2 * pad) * fit
    val shape = RoundedCornerShape(3.dp)

    val modifier = Modifier
        .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
        .width(with(density) { width.toDp() })
        .heightIn(min = with(density) { height.toDp() })
        .clickable(onClick = onClick)

    val accent = MaterialTheme.colorScheme.primary
    // When choosing what to read: selected ones get a thick colored border and a number.
    val decorated = when {
        !choosing -> modifier
        readingNumber != null -> modifier.border(3.dp, accent, shape)
        else -> modifier.border(1.dp, Color.White.copy(alpha = 0.7f), shape)
    }

    if (translation == null) {
        // The original is visible; only a thin border shows that the block can be tapped.
        Box(decorated.border(1.dp, Color(style.outline), shape))
    } else {
        Box(
            contentAlignment = Alignment.CenterStart,
            modifier = decorated.background(Color(style.background), shape),
        ) {
            FittedText(
                text = translation,
                measurer = measurer,
                color = Color(style.text),
                scale = style.textScale,
                widthPx = width.roundToInt(),
                heightPx = height.roundToInt(),
                maxFontPx = block.lineHeight * fit * 1.1f,
            )
        }
    }

    // The number - on top, above the block itself.
    if (readingNumber != null) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .offset { IntOffset((left - with(density) { 10.dp.toPx() }).roundToInt(), (top - with(density) { 10.dp.toPx() }).roundToInt()) }
                .size(22.dp)
                .background(accent, CircleShape),
        ) {
            Text("$readingNumber", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** "Hidden: N · Show all" - above the photo while there are hidden windows. */
@Composable
private fun HiddenBlocksChip(count: Int, onShowAll: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        color = Color(0xCC000000),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.clickable(onClick = onShowAll),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Icon(Icons.Default.VisibilityOff, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            Spacer(Modifier.size(6.dp))
            Text(
                pluralStringResource(R.plurals.hidden_blocks, count, count),
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.size(10.dp))
            Text(
                stringResource(R.string.show_all_hidden),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

/** The bottom bar when choosing what to read: Cancel / All / Read (N). */
@Composable
private fun ChooseToReadBar(count: Int, onCancel: () -> Unit, onReadAll: () -> Unit, onReadChosen: () -> Unit) {
    Surface(
        color = Color(0xE6000000),
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.padding(start = 32.dp), // The FAB is on the right - keep the bar nearly full width
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
            OutlinedButton(onClick = onReadAll) { Text(stringResource(R.string.read_all)) }
            Button(onClick = onReadChosen, enabled = count > 0) {
                Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.read_chosen, count))
            }
        }
    }
}

/**
 * Text whose size is chosen so that it fits in the given rectangle
 * (binary search over the size). If it doesn't fit even at the smallest font,
 * the block is extended downward.
 */
@Composable
private fun FittedText(
    text: String,
    measurer: TextMeasurer,
    color: Color,
    scale: Float,
    widthPx: Int,
    heightPx: Int,
    maxFontPx: Float,
) {
    val density = LocalDensity.current
    val horizontalPadPx = with(density) { 2.dp.toPx() }
    val innerWidth = max(1, (widthPx - 2 * horizontalPadPx).roundToInt())

    fun style(fontPx: Float) = with(density) {
        TextStyle(color = color, fontSize = fontPx.toSp(), lineHeight = (fontPx * OverlayStyle.LINE_SPACING).toSp())
    }

    val fontPx = remember(text, innerWidth, heightPx, maxFontPx) {
        val minPx = OverlayStyle.MIN_FONT_PX
        var low = minPx
        var high = max(minPx, maxFontPx)
        repeat(7) { // ~0.5 px precision - more isn't visible
            val mid = (low + high) / 2f
            val measured = measurer.measure(
                AnnotatedString(text),
                style = style(mid),
                constraints = Constraints(maxWidth = innerWidth),
            )
            if (measured.size.height <= heightPx) low = mid else high = mid
        }
        low
    }

    // The "Text size" setting - applied on top of the fitted size; if larger, the window is extended.
    Text(text = text, style = style(fontPx * scale), modifier = Modifier.padding(horizontal = 2.dp))
}

/** Status card: recognizing, translating, error, "no text". */
@Composable
private fun StatusCard(photo: PhotoState, downloadingModel: String?, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val phase = photo.phase
    val noText = phase == Phase.Done && photo.blocks.isEmpty()
    if (phase == Phase.Done && !noText) return

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            when (phase) {
                Phase.Recognizing -> ProgressRow(stringResource(R.string.recognizing))
                Phase.Translating -> {
                    if (downloadingModel != null) {
                        ProgressRow(stringResource(R.string.downloading_offline_model, modelName(downloadingModel)))
                        Text(
                            stringResource(R.string.downloading_offline_model_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    } else {
                        ProgressRow(stringResource(R.string.translating))
                    }
                }
                is Phase.Failed -> {
                    Text(phase.message.text(), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onRetry, modifier = Modifier.align(Alignment.End)) {
                        Text(stringResource(R.string.retry))
                    }
                }
                Phase.Done -> {
                    Text(stringResource(R.string.no_text_found), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(R.string.no_text_found_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ProgressRow(label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(label)
    }
}

/** Bottom sheet: original and translation, with copy and read aloud. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextDetailsSheet(
    original: String,
    translation: String,
    speakingText: String?,
    onSpeak: (String) -> Unit,
    onCopied: () -> Unit,
    onDismiss: () -> Unit,
    /** null - no hide button (e.g. "Full text"). */
    hidden: Boolean? = null,
    onToggleHidden: () -> Unit = {},
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        ) {
            SheetSection(stringResource(R.string.translation_label), translation) {
                CopySpeakButtons(translation, speakingText, onSpeak, onCopied)
            }
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            SheetSection(stringResource(R.string.original_label), original) {
                CopySpeakButtons(original, speakingText, onSpeak, onCopied, speakable = false)
            }
            if (hidden != null) {
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                // A hidden window isn't shown on the photo and isn't included in the saved/shared one.
                OutlinedButton(onClick = onToggleHidden, modifier = Modifier.fillMaxWidth()) {
                    Icon(
                        if (hidden) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(if (hidden) R.string.show_block else R.string.hide_block))
                }
                if (!hidden) {
                    Text(
                        stringResource(R.string.hide_block_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SheetSection(label: String, text: String, actions: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        actions()
    }
    SelectionContainer {
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

/** The language name for an ML Kit code ("en" -> "English"). */
@Composable
fun modelName(code: String): String {
    val locale = uiLocale()
    return java.util.Locale.forLanguageTag(code).getDisplayName(locale).replaceFirstChar { it.titlecase(locale) }
}

/**
 * "Share" button with a menu: share the photo with translations or save it to the gallery.
 * On Android 8-9 saving needs the storage permission - it's requested on the first save.
 */
@Composable
private fun ExportMenu(enabled: Boolean, onSave: () -> Unit, onShare: () -> Unit, onDenied: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onSave() else onDenied()
    }
    val save = {
        val needsPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        if (needsPermission) permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) else onSave()
    }
    Box {
        IconButton(onClick = { open = true }, enabled = enabled) {
            Icon(Icons.Default.Share, contentDescription = stringResource(R.string.share_or_save))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.share_image)) },
                leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                onClick = {
                    open = false
                    onShare()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.save_to_gallery)) },
                leadingIcon = { Icon(Icons.Default.Download, contentDescription = null) },
                onClick = {
                    open = false
                    save()
                },
            )
        }
    }
}
