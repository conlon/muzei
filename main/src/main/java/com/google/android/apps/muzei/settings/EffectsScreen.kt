/*
 * Copyright 2025 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.apps.muzei.settings

import android.content.SharedPreferences
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Devices.PHONE
import androidx.compose.ui.tooling.preview.Devices.TABLET
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.google.android.apps.muzei.render.MuzeiBlurRenderer
import com.google.android.apps.muzei.theme.AppTheme
import net.nurik.roman.muzei.R
import kotlin.math.max

@Composable
fun EffectsScreen(
    prefs: SharedPreferences,
    blurPref: String,
    dimPref: String,
    greyPref: String,
    effectModePref: String,
    mosaicPref: String,
    mosaicOpacityPref: String,
    mosaicShapePref: String,
    modifier: Modifier = Modifier,
) {
    val blur =
        rememberPreferenceSourcedValue(prefs, blurPref, MuzeiBlurRenderer.DEFAULT_BLUR)
    val dim =
        rememberPreferenceSourcedValue(prefs, dimPref, MuzeiBlurRenderer.DEFAULT_MAX_DIM)
    val grey =
        rememberPreferenceSourcedValue(prefs, greyPref, MuzeiBlurRenderer.DEFAULT_GREY)
    val mosaic =
        rememberPreferenceSourcedValue(prefs, mosaicPref, MuzeiBlurRenderer.DEFAULT_MOSAIC)
    val mosaicOpacity =
        rememberPreferenceSourcedValue(prefs, mosaicOpacityPref, MuzeiBlurRenderer.DEFAULT_MOSAIC_OPACITY)
    val effectMode = rememberPreferenceSourcedStringValue(
        prefs, effectModePref, MuzeiBlurRenderer.DEFAULT_EFFECT_MODE
    )
    val mosaicShape = rememberPreferenceSourcedStringValue(
        prefs, mosaicShapePref, MuzeiBlurRenderer.DEFAULT_MOSAIC_SHAPE
    )
    EffectsScreen(
        effectMode = effectMode.value,
        onEffectModeChange = { effectMode.value = it },
        blur = blur.value,
        onBlurChange = { blur.value = it },
        onBlurChangeFinished = { blur.userControlled = false },
        mosaic = mosaic.value,
        onMosaicChange = { mosaic.value = it },
        onMosaicChangeFinished = { mosaic.userControlled = false },
        mosaicOpacity = mosaicOpacity.value,
        onMosaicOpacityChange = { mosaicOpacity.value = it },
        onMosaicOpacityChangeFinished = { mosaicOpacity.userControlled = false },
        mosaicShape = mosaicShape.value,
        onMosaicShapeChange = { mosaicShape.value = it },
        dim = dim.value,
        onDimChange = { dim.value = it },
        onDimChangeFinished = { dim.userControlled = false },
        grey = grey.value,
        onGreyChange = { grey.value = it },
        onGreyChangeFinished = { grey.userControlled = false },
        modifier = modifier
    )
}

@Composable
fun EffectsScreen(
    effectMode: String,
    onEffectModeChange: (String) -> Unit,
    blur: Int,
    onBlurChange: (Int) -> Unit,
    onBlurChangeFinished: (() -> Unit),
    mosaic: Int,
    onMosaicChange: (Int) -> Unit,
    onMosaicChangeFinished: (() -> Unit),
    mosaicOpacity: Int = MuzeiBlurRenderer.DEFAULT_MOSAIC_OPACITY,
    onMosaicOpacityChange: (Int) -> Unit = {},
    onMosaicOpacityChangeFinished: (() -> Unit) = {},
    mosaicShape: String,
    onMosaicShapeChange: (String) -> Unit,
    dim: Int,
    onDimChange: (Int) -> Unit,
    onDimChangeFinished: (() -> Unit),
    grey: Int,
    onGreyChange: (Int) -> Unit,
    onGreyChangeFinished: (() -> Unit),
    modifier: Modifier = Modifier,
) {
    val windowSize = with(LocalDensity.current) {
        val pixelSize = LocalWindowInfo.current.containerSize
        DpSize(pixelSize.width.toDp(), pixelSize.height.toDp())
    }
    val contentModifier = if (windowSize.width >= 600.dp && windowSize.height >= 600.dp) {
        Modifier.wrapContentSize().sizeIn(maxWidth = 500.dp)
    } else {
        Modifier.wrapContentHeight().padding(horizontal = 32.dp)
    }
    val isMosaic = effectMode == Prefs.EFFECT_MODE_MOSAIC
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        EffectModeSelector(
            mode = effectMode,
            onModeChange = onEffectModeChange,
            modifier = contentModifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
        )
        if (isMosaic) {
            MosaicShapeSelector(
                shape = mosaicShape,
                onShapeChange = onMosaicShapeChange,
                modifier = contentModifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
            )
        }
        EffectsGrid(
            modifier = contentModifier,
            effectMode = effectMode,
            blur = blur,
            onBlurChange = onBlurChange,
            onBlurChangeFinished = onBlurChangeFinished,
            mosaic = mosaic,
            onMosaicChange = onMosaicChange,
            onMosaicChangeFinished = onMosaicChangeFinished,
            mosaicOpacity = mosaicOpacity,
            onMosaicOpacityChange = onMosaicOpacityChange,
            onMosaicOpacityChangeFinished = onMosaicOpacityChangeFinished,
            dim = dim,
            onDimChange = onDimChange,
            onDimChangeFinished = onDimChangeFinished,
            grey = grey,
            onGreyChange = onGreyChange,
            onGreyChangeFinished = onGreyChangeFinished,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EffectModeSelector(
    mode: String,
    onModeChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val options = listOf(
        Prefs.EFFECT_MODE_BLUR to R.string.settings_effect_mode_blur,
        Prefs.EFFECT_MODE_MOSAIC to R.string.settings_effect_mode_mosaic,
    )
    SingleChoiceSegmentedButtonRow(modifier = modifier) {
        options.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = mode == value,
                onClick = { onModeChange(value) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = Color.White.copy(alpha = 0.18f),
                    activeContentColor = Color.White,
                    inactiveContainerColor = Color.Transparent,
                    inactiveContentColor = Color.White,
                ),
            ) {
                Text(text = stringResource(label))
            }
        }
    }
}

@Composable
private fun MosaicShapeSelector(
    shape: String,
    onShapeChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val options = listOf(
        Prefs.MOSAIC_SHAPE_SQUARE to R.string.settings_mosaic_shape_square,
        Prefs.MOSAIC_SHAPE_TRIANGLE to R.string.settings_mosaic_shape_triangle,
        Prefs.MOSAIC_SHAPE_HEXAGON to R.string.settings_mosaic_shape_hexagon,
        Prefs.MOSAIC_SHAPE_RANDOM to R.string.settings_mosaic_shape_random,
        Prefs.MOSAIC_SHAPE_MIXED1 to R.string.settings_mosaic_shape_mixed1,
        Prefs.MOSAIC_SHAPE_MIXED2 to R.string.settings_mosaic_shape_mixed2,
        Prefs.MOSAIC_SHAPE_MIXED3 to R.string.settings_mosaic_shape_mixed3,
        Prefs.MOSAIC_SHAPE_MIXED4 to R.string.settings_mosaic_shape_mixed4,
    )
    FlowRow(modifier = modifier) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = shape == value,
                onClick = { onShapeChange(value) },
                label = { Text(text = stringResource(label)) },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = Color.Transparent,
                    labelColor = Color.White,
                    selectedContainerColor = Color.White.copy(alpha = 0.18f),
                    selectedLabelColor = Color.White,
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = shape == value,
                    borderColor = Color.White.copy(alpha = 0.5f),
                    selectedBorderColor = Color.White,
                ),
            )
        }
    }
}

@Composable
private fun EffectsGrid(
    modifier: Modifier = Modifier,
    effectMode: String = MuzeiBlurRenderer.DEFAULT_EFFECT_MODE,
    blur: Int = MuzeiBlurRenderer.DEFAULT_BLUR,
    onBlurChange: (Int) -> Unit = {},
    onBlurChangeFinished: (() -> Unit) = {},
    mosaic: Int = MuzeiBlurRenderer.DEFAULT_MOSAIC,
    onMosaicChange: (Int) -> Unit = {},
    onMosaicChangeFinished: (() -> Unit) = {},
    mosaicOpacity: Int = MuzeiBlurRenderer.DEFAULT_MOSAIC_OPACITY,
    onMosaicOpacityChange: (Int) -> Unit = {},
    onMosaicOpacityChangeFinished: (() -> Unit) = {},
    dim: Int = MuzeiBlurRenderer.DEFAULT_MAX_DIM,
    onDimChange: (Int) -> Unit = {},
    onDimChangeFinished: (() -> Unit) = {},
    grey: Int = MuzeiBlurRenderer.DEFAULT_GREY,
    onGreyChange: (Int) -> Unit = {},
    onGreyChangeFinished: (() -> Unit) = {},
) {
    val sliderColors = SliderDefaults.colors(
        thumbColor = Color.White,
        activeTrackColor = Color.White,
        inactiveTrackColor = Color.DarkGray,
    )
    val isMosaic = effectMode == Prefs.EFFECT_MODE_MOSAIC
    val rowCount = 3 + (if (isMosaic) 1 else 0)
    Layout(
        content = {
            // Titles
            Text(
                text = stringResource(
                    if (isMosaic) R.string.settings_mosaic_amount_title
                    else R.string.settings_blur_amount_title
                ),
                modifier = Modifier.padding(vertical = 8.dp),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium
            )
            if (isMosaic) {
                Text(
                    text = stringResource(R.string.settings_mosaic_opacity_title),
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium
                )
            }
            Text(
                text = stringResource(R.string.settings_dim_amount_title),
                modifier = Modifier.padding(vertical = 8.dp),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = stringResource(R.string.settings_grey_amount_title),
                modifier = Modifier.padding(vertical = 8.dp),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium
            )
            // Sliders
            if (isMosaic) {
                Slider(
                    value = mosaic.toFloat(),
                    onValueChange = { onMosaicChange(it.toInt()) },
                    modifier = Modifier.padding(vertical = 8.dp),
                    valueRange = 0f..500f,
                    onValueChangeFinished = onMosaicChangeFinished,
                    colors = sliderColors,
                )
                Slider(
                    value = mosaicOpacity.toFloat(),
                    onValueChange = { onMosaicOpacityChange(it.toInt()) },
                    modifier = Modifier.padding(vertical = 8.dp),
                    valueRange = 0f..500f,
                    onValueChangeFinished = onMosaicOpacityChangeFinished,
                    colors = sliderColors,
                )
            } else {
                Slider(
                    value = blur.toFloat(),
                    onValueChange = { onBlurChange(it.toInt()) },
                    modifier = Modifier.padding(vertical = 8.dp),
                    valueRange = 0f..500f,
                    onValueChangeFinished = onBlurChangeFinished,
                    colors = sliderColors,
                )
            }
            Slider(
                value = dim.toFloat(),
                onValueChange = { onDimChange(it.toInt()) },
                modifier = Modifier.padding(vertical = 8.dp),
                valueRange = 0f..255f,
                onValueChangeFinished = onDimChangeFinished,
                colors = sliderColors,
            )
            Slider(
                value = grey.toFloat(),
                onValueChange = { onGreyChange(it.toInt()) },
                modifier = Modifier.padding(vertical = 8.dp),
                valueRange = 0f..500f,
                onValueChangeFinished = onGreyChangeFinished,
                colors = sliderColors,
            )
        },
        modifier = modifier
    ) { measurables, constraints ->
        val titlePlaceables = measurables.take(3).map { measurable ->
            measurable.measure(constraints.copy(minWidth = 0))
        }
        val maxTitleWidth = titlePlaceables.maxOf { it.width }
        val spacing = 16.dp.roundToPx()
        val sliderPlaceables = measurables.takeLast(3).map { measurable ->
            measurable.measure(
                constraints.copy(
                    minWidth = constraints.maxWidth - maxTitleWidth - spacing,
                    maxWidth = constraints.maxWidth - maxTitleWidth - spacing,
                )
            )
        }
        val columnHeights = titlePlaceables.mapIndexed { index, titlePlaceable ->
            max(titlePlaceable.height, sliderPlaceables[index].height)
        }
        val maxColumnHeight = columnHeights.max()

        val layoutWidth = constraints.maxWidth
        val layoutHeight = maxColumnHeight * 3
        layout(layoutWidth, layoutHeight) {
            titlePlaceables.forEachIndexed { index, titlePlaceable ->
                val height = titlePlaceable.height
                titlePlaceable.placeRelative(
                    0,
                    index * maxColumnHeight + (maxColumnHeight - height) / 2
                )
            }
            sliderPlaceables.forEachIndexed { index, sliderPlaceable ->
                val height = sliderPlaceable.height
                sliderPlaceable.placeRelative(
                    maxTitleWidth + spacing,
                    index * maxColumnHeight + (maxColumnHeight - height) / 2
                )
            }
        }
    }
}

@Preview(name = "Portrait", device = PHONE)
@Preview(
    name = "Landscape",
    device = "spec:width=411dp,height=891dp,orientation=landscape,dpi=420",
)
@Preview(name = "Tablet - Landscape", device = TABLET)
@Composable
fun EffectsScreenPreview() {
    AppTheme(
        dynamicColor = false
    ) {
        var effectMode by remember { mutableStateOf(MuzeiBlurRenderer.DEFAULT_EFFECT_MODE) }
        var blur by remember { mutableIntStateOf(MuzeiBlurRenderer.DEFAULT_BLUR) }
        var mosaic by remember { mutableIntStateOf(MuzeiBlurRenderer.DEFAULT_MOSAIC) }
        var mosaicShape by remember { mutableStateOf(MuzeiBlurRenderer.DEFAULT_MOSAIC_SHAPE) }
        var dim by remember { mutableIntStateOf(MuzeiBlurRenderer.DEFAULT_MAX_DIM) }
        var grey by remember { mutableIntStateOf(MuzeiBlurRenderer.DEFAULT_GREY) }
        EffectsScreen(
            effectMode = effectMode,
            onEffectModeChange = { effectMode = it },
            blur = blur,
            onBlurChange = { blur = it },
            onBlurChangeFinished = {},
            mosaic = mosaic,
            onMosaicChange = { mosaic = it },
            onMosaicChangeFinished = {},
            mosaicShape = mosaicShape,
            onMosaicShapeChange = { mosaicShape = it },
            dim = dim,
            onDimChange = { dim = it },
            onDimChangeFinished = {},
            grey = grey,
            onGreyChange = { grey = it },
            onGreyChangeFinished = {},
            modifier = Modifier.fillMaxSize(),
        )
    }
}
