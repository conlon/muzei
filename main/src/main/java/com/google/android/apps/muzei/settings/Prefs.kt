/*
 * Copyright 2014 Google Inc.
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

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.os.UserManagerCompat
import androidx.preference.PreferenceManager

/**
 * Preference constants/helpers.
 */
object Prefs {
    const val PREF_DOUBLE_TAP = "double_tap"
    const val PREF_TWO_FINGER_TAP = "two_finger_tap"
    const val PREF_THREE_FINGER_TAP = "three_finger_tap"
    const val PREF_TAP_ACTION_TEMP = "temp"
    const val PREF_TAP_ACTION_NEXT = "next"
    const val PREF_TAP_ACTION_VIEW_DETAILS = "view_details"
    const val PREF_TAP_ACTION_NONE = "none"
    const val PREF_PARALLAX_AMOUNT = "parallax_amount"
    const val DEFAULT_PARALLAX = 100
    const val PREF_TEMP_FOCUS_DURATION = "temp_focus_duration"
    const val DEFAULT_TEMP_FOCUS_DURATION = 3
    const val TEMP_FOCUS_UNTIL_LOCK = -1
    const val PREF_GREY_AMOUNT = "grey_amount"
    const val PREF_DIM_AMOUNT = "dim_amount"
    const val PREF_BLUR_AMOUNT = "blur_amount"
    const val PREF_LOCK_GREY_AMOUNT = "lock_grey_amount"
    const val PREF_LOCK_DIM_AMOUNT = "lock_dim_amount"
    const val PREF_LOCK_BLUR_AMOUNT = "lock_blur_amount"
    const val PREF_AUTO_FRAMING = "auto_framing"
    const val DEFAULT_AUTO_FRAMING = true
    const val PREF_FAVORITE_BOOST = "favorite_boost"
    const val DEFAULT_FAVORITE_BOOST = 0
    const val PREF_EFFECT_MODE = "effect_mode"
    const val PREF_LOCK_EFFECT_MODE = "lock_effect_mode"
    const val PREF_MOSAIC_AMOUNT = "mosaic_amount"
    const val PREF_LOCK_MOSAIC_AMOUNT = "lock_mosaic_amount"
    const val PREF_MOSAIC_SHAPE = "mosaic_shape"
    const val PREF_LOCK_MOSAIC_SHAPE = "lock_mosaic_shape"
    const val PREF_MOSAIC_OPACITY = "mosaic_opacity"
    const val PREF_LOCK_MOSAIC_OPACITY = "lock_mosaic_opacity"
    const val EFFECT_MODE_BLUR = "blur"
    const val EFFECT_MODE_MOSAIC = "mosaic"
    const val MOSAIC_SHAPE_SQUARE = "square"
    const val MOSAIC_SHAPE_EQUILATERAL = "equilateral"
    const val MOSAIC_SHAPE_IRREGULAR = "irregular"
    const val MOSAIC_SHAPE_HEXAGON = "hexagon"
    const val MOSAIC_SHAPE_CIRCLE = "circle"
    const val MOSAIC_SHAPE_RANDOM = "random"
    // Legacy shape values kept for migration only
    const val MOSAIC_SHAPE_TRIANGLE = "triangle"
    const val MOSAIC_SHAPE_MIXED1 = "mixed1"
    const val MOSAIC_SHAPE_MIXED2 = "mixed2"
    const val MOSAIC_SHAPE_MIXED3 = "mixed3"
    const val MOSAIC_SHAPE_MIXED4 = "mixed4"
    const val MOSAIC_SHAPE_GLITCH = "glitch"
    const val PREF_MOSAIC_FILTER = "mosaic_filter"
    const val PREF_LOCK_MOSAIC_FILTER = "lock_mosaic_filter"
    const val MOSAIC_FILTER_NONE = "none"
    const val MOSAIC_FILTER_RAINDROP = "raindrop"
    const val MOSAIC_FILTER_GLITCH1 = "glitch1"
    const val MOSAIC_FILTER_GLITCH2 = "glitch2"
    const val MOSAIC_FILTER_RECURSIVE = "recursive"
    const val PREF_GLITCH_H_DISPLACEMENT = "glitch_h_displacement"
    const val PREF_LOCK_GLITCH_H_DISPLACEMENT = "lock_glitch_h_displacement"
    const val PREF_GLITCH_V_DISPLACEMENT = "glitch_v_displacement"
    const val PREF_LOCK_GLITCH_V_DISPLACEMENT = "lock_glitch_v_displacement"
    const val PREF_GLITCH_CHANNEL_SPLIT = "glitch_channel_split"
    const val PREF_LOCK_GLITCH_CHANNEL_SPLIT = "lock_glitch_channel_split"
    const val PREF_GLITCH_PIXEL_SORT = "glitch_pixel_sort"
    const val PREF_LOCK_GLITCH_PIXEL_SORT = "lock_glitch_pixel_sort"
    const val PREF_LINK_EFFECTS = "link_effects"
    private const val PREF_DISABLE_BLUR_WHEN_LOCKED = "disable_blur_when_screen_locked_enabled"

    private const val WALLPAPER_PREFERENCES_NAME = "wallpaper_preferences"
    private const val PREF_MIGRATED = "migrated_from_default"
    private const val PREF_MOSAIC_FILTER_MIGRATED = "mosaic_filter_migrated"

    @Synchronized
    fun getSharedPreferences(context: Context): SharedPreferences {
        val deviceProtectedContext = ContextCompat.createDeviceProtectedStorageContext(context)
        if (UserManagerCompat.isUserUnlocked(context)) {
            // First migrate the wallpaper settings to their own file
            val defaultSharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
            val wallpaperPreferences = context.getSharedPreferences(
                    WALLPAPER_PREFERENCES_NAME, Context.MODE_PRIVATE)
            migratePreferences(defaultSharedPreferences, wallpaperPreferences)

            // Now migrate the file to device protected storage if available
            if (deviceProtectedContext != null) {
                val deviceProtectedPreferences = deviceProtectedContext.getSharedPreferences(
                        WALLPAPER_PREFERENCES_NAME, Context.MODE_PRIVATE)
                migratePreferences(wallpaperPreferences, deviceProtectedPreferences)
            }
        }
        // Now open the correct SharedPreferences
        val contextToUse = deviceProtectedContext ?: context
        return contextToUse.getSharedPreferences(WALLPAPER_PREFERENCES_NAME,
                Context.MODE_PRIVATE).also { sp ->
            if (sp.contains(PREF_DISABLE_BLUR_WHEN_LOCKED)) {
                sp.edit {
                    val disableBlurWhenLocked = sp.getBoolean(
                            PREF_DISABLE_BLUR_WHEN_LOCKED, false)
                    if (sp.contains(PREF_GREY_AMOUNT)) {
                        val greyAmount = sp.getInt(PREF_GREY_AMOUNT, 0)
                        putInt(PREF_LOCK_GREY_AMOUNT,
                                if (disableBlurWhenLocked) 0 else greyAmount)
                    } else if (disableBlurWhenLocked) {
                        putInt(PREF_LOCK_GREY_AMOUNT, 0)
                    }
                    if (sp.contains(PREF_DIM_AMOUNT)) {
                        val dimAmount = sp.getInt(PREF_DIM_AMOUNT, 0)
                        putInt(PREF_LOCK_DIM_AMOUNT,
                                if (disableBlurWhenLocked) 0 else dimAmount)
                    } else if (disableBlurWhenLocked) {
                        putInt(PREF_LOCK_DIM_AMOUNT, 0)
                    }
                    if (sp.contains(PREF_BLUR_AMOUNT)) {
                        val blurAmount = sp.getInt(PREF_BLUR_AMOUNT, 0)
                        putInt(PREF_LOCK_BLUR_AMOUNT,
                                if (disableBlurWhenLocked) 0 else blurAmount)
                    } else if (disableBlurWhenLocked) {
                        putInt(PREF_LOCK_BLUR_AMOUNT, 0)
                    }
                    remove(PREF_DISABLE_BLUR_WHEN_LOCKED)
                }
            }
            if (!sp.getBoolean(PREF_MOSAIC_FILTER_MIGRATED, false)) {
                sp.edit {
                    migrateMosaicShapeToFilter(sp, PREF_MOSAIC_SHAPE, PREF_MOSAIC_FILTER)
                    migrateMosaicShapeToFilter(sp, PREF_LOCK_MOSAIC_SHAPE, PREF_LOCK_MOSAIC_FILTER)
                    putBoolean(PREF_MOSAIC_FILTER_MIGRATED, true)
                }
            }
        }
    }

    private fun SharedPreferences.Editor.migrateMosaicShapeToFilter(
        sp: SharedPreferences,
        shapePrefKey: String,
        filterPrefKey: String,
    ) {
        val oldShape = sp.getString(shapePrefKey, MOSAIC_SHAPE_SQUARE) ?: MOSAIC_SHAPE_SQUARE
        val (newShape, newFilter) = when (oldShape) {
            MOSAIC_SHAPE_TRIANGLE  -> MOSAIC_SHAPE_EQUILATERAL to MOSAIC_FILTER_NONE
            MOSAIC_SHAPE_MIXED1    -> MOSAIC_SHAPE_SQUARE      to MOSAIC_FILTER_RECURSIVE
            MOSAIC_SHAPE_MIXED2    -> MOSAIC_SHAPE_CIRCLE      to MOSAIC_FILTER_RAINDROP
            MOSAIC_SHAPE_MIXED3    -> MOSAIC_SHAPE_IRREGULAR   to MOSAIC_FILTER_NONE
            MOSAIC_SHAPE_MIXED4    -> MOSAIC_SHAPE_IRREGULAR   to MOSAIC_FILTER_RAINDROP
            MOSAIC_SHAPE_GLITCH    -> MOSAIC_SHAPE_SQUARE      to MOSAIC_FILTER_GLITCH1
            MOSAIC_SHAPE_RANDOM    -> MOSAIC_SHAPE_SQUARE      to MOSAIC_FILTER_NONE
            else                   -> oldShape                 to MOSAIC_FILTER_NONE
        }
        putString(shapePrefKey, newShape)
        putString(filterPrefKey, newFilter)
    }

    private fun migratePreferences(source: SharedPreferences, destination: SharedPreferences) {
        if (source.getBoolean(PREF_MIGRATED, false)) {
            return
        }
        val sourceEditor = source.edit()
        val destinationEditor = destination.edit()

        val disableBlurWhenLocked = source.getBoolean(
                PREF_DISABLE_BLUR_WHEN_LOCKED, false)
        sourceEditor.remove(PREF_DISABLE_BLUR_WHEN_LOCKED)
        if (source.contains(PREF_GREY_AMOUNT)) {
            val greyAmount = source.getInt(PREF_GREY_AMOUNT, 0)
            destinationEditor.putInt(PREF_GREY_AMOUNT, greyAmount)
            destinationEditor.putInt(PREF_LOCK_GREY_AMOUNT,
                    if (disableBlurWhenLocked) 0 else greyAmount)
            sourceEditor.remove(PREF_GREY_AMOUNT)
        } else if (disableBlurWhenLocked) {
            destinationEditor.putInt(PREF_LOCK_GREY_AMOUNT, 0)
        }
        if (source.contains(PREF_DIM_AMOUNT)) {
            val dimAmount = source.getInt(PREF_DIM_AMOUNT, 0)
            destinationEditor.putInt(PREF_DIM_AMOUNT, dimAmount)
            destinationEditor.putInt(PREF_LOCK_DIM_AMOUNT,
                    if (disableBlurWhenLocked) 0 else dimAmount)
            sourceEditor.remove(PREF_DIM_AMOUNT)
        } else if (disableBlurWhenLocked) {
            destinationEditor.putInt(PREF_LOCK_DIM_AMOUNT, 0)
        }
        if (source.contains(PREF_BLUR_AMOUNT)) {
            val blurAmount = source.getInt(PREF_BLUR_AMOUNT, 0)
            destinationEditor.putInt(PREF_BLUR_AMOUNT, blurAmount)
            destinationEditor.putInt(PREF_LOCK_BLUR_AMOUNT,
                    if (disableBlurWhenLocked) 0 else blurAmount)
            sourceEditor.remove(PREF_BLUR_AMOUNT)
        } else if (disableBlurWhenLocked) {
            destinationEditor.putInt(PREF_LOCK_BLUR_AMOUNT, 0)
        }
        sourceEditor.putBoolean(PREF_MIGRATED, true)
        sourceEditor.apply()
        destinationEditor.apply()
    }
}
