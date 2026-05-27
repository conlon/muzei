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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Devices.PHONE
import androidx.compose.ui.tooling.preview.Devices.TABLET
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.google.android.apps.muzei.theme.AppTheme
import com.google.android.apps.muzei.util.RadioButtonGroup
import com.google.android.apps.muzei.util.RadioButtonSectionHeader
import com.google.android.apps.muzei.util.only
import net.nurik.roman.muzei.R

private val DURATION_STEPS = listOf(3, 5, 10, 15, 20, 30, 45, 60, -1)

@Composable
private fun FocusDurationSlider(
    durationSeconds: Int,
    onDurationChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val stepIndex = DURATION_STEPS.indexOf(durationSeconds).let { if (it < 0) 0 else it }
    val label = if (durationSeconds == -1) {
        stringResource(R.string.gestures_focus_duration_until_lock)
    } else {
        stringResource(R.string.gestures_focus_duration_label, "${durationSeconds}s")
    }
    Column(modifier = modifier.padding(start = 48.dp, end = 16.dp, bottom = 8.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Slider(
            value = stepIndex.toFloat(),
            onValueChange = { onDurationChange(DURATION_STEPS[it.toInt()]) },
            valueRange = 0f..(DURATION_STEPS.size - 1).toFloat(),
            steps = DURATION_STEPS.size - 2,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White,
                inactiveTrackColor = Color.DarkGray,
            )
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GestureSettings(
    doubleTapSelectedOption: String,
    onDoubleTapSelectedOptionChange: (String) -> Unit,
    twoFingerSelectedOption: String,
    onTwoFingerSelectedOptionChange: (String) -> Unit,
    threeFingerSelectedOption: String,
    onThreeFingerSelectedOptionChange: (String) -> Unit,
    focusDurationSeconds: Int = Prefs.DEFAULT_TEMP_FOCUS_DURATION,
    onFocusDurationChange: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
    onUp: () -> Unit = {},
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(text = stringResource(R.string.gestures_title))
                },
                navigationIcon = {
                    IconButton(
                        onClick = onUp,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.navigate_up)
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentWidth()
                .padding(innerPadding.only(left = true, right = true))
                .widthIn(max = 500.dp)
                .padding(innerPadding.only(bottom = true, top = true))
                .verticalScroll(rememberScrollState()),
        ) {
            RadioButtonSectionHeader(
                title = stringResource(R.string.gestures_double_tap_title),
                description = stringResource(R.string.gestures_double_tap_description),
            )
            val gestureOptions = listOf(
                stringResource(R.string.gestures_tap_action_temporary_disable),
                stringResource(R.string.gestures_tap_action_next),
                stringResource(R.string.gestures_tap_action_view_details),
                stringResource(R.string.gestures_tap_action_none),
            )
            RadioButtonGroup(
                options = gestureOptions,
                selectedOption = doubleTapSelectedOption,
                onOptionSelected = onDoubleTapSelectedOptionChange,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            val tempDisableLabel = gestureOptions[0]
            AnimatedVisibility(visible = doubleTapSelectedOption == tempDisableLabel) {
                FocusDurationSlider(
                    durationSeconds = focusDurationSeconds,
                    onDurationChange = onFocusDurationChange,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                )
            }
            RadioButtonSectionHeader(
                title = stringResource(R.string.gestures_two_finger_tap_title),
                description = stringResource(R.string.gestures_two_finger_tap_description)
            )
            RadioButtonGroup(
                options = gestureOptions,
                selectedOption = twoFingerSelectedOption,
                onOptionSelected = onTwoFingerSelectedOptionChange,
                modifier = Modifier.padding(bottom = 16.dp),
            )
            RadioButtonSectionHeader(
                title = stringResource(R.string.gestures_three_finger_tap_title),
                description = stringResource(R.string.gestures_three_finger_tap_description)
            )
            RadioButtonGroup(
                options = gestureOptions,
                selectedOption = threeFingerSelectedOption,
                onOptionSelected = onThreeFingerSelectedOptionChange,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            AnimatedVisibility(visible = threeFingerSelectedOption == tempDisableLabel) {
                FocusDurationSlider(
                    durationSeconds = focusDurationSeconds,
                    onDurationChange = onFocusDurationChange,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
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
fun GestureSettingsPreview() {
    AppTheme(
        dynamicColor = false
    ) {
        val defaultDoubleTapOption = stringResource(R.string.gestures_tap_action_temporary_disable)
        var doubleTapSelectedOption by remember { mutableStateOf(defaultDoubleTapOption) }
        val defaultTwoFingerOption = stringResource(R.string.gestures_tap_action_none)
        var twoFingerSelectedOption by remember { mutableStateOf(defaultTwoFingerOption) }
        val defaultThreeFingerOption = stringResource(R.string.gestures_tap_action_none)
        var threeFingerSelectedOption by remember { mutableStateOf(defaultThreeFingerOption) }
        var focusDuration by remember { mutableStateOf(Prefs.DEFAULT_TEMP_FOCUS_DURATION) }
        GestureSettings(
            doubleTapSelectedOption = doubleTapSelectedOption,
            onDoubleTapSelectedOptionChange = { doubleTapSelectedOption = it },
            twoFingerSelectedOption = twoFingerSelectedOption,
            onTwoFingerSelectedOptionChange = { twoFingerSelectedOption = it },
            threeFingerSelectedOption = threeFingerSelectedOption,
            onThreeFingerSelectedOptionChange = { threeFingerSelectedOption = it },
            focusDurationSeconds = focusDuration,
            onFocusDurationChange = { focusDuration = it },
            modifier = Modifier.fillMaxSize(),
        )
    }
}