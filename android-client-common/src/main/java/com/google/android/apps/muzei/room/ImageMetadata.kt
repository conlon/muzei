/*
 * Copyright 2026 Google Inc.
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

package com.google.android.apps.muzei.room

import android.net.Uri
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.google.android.apps.muzei.room.converter.UriTypeConverter

/**
 * Per-image source of truth for user-set metadata (favorite flag and saved viewport).
 *
 * Keyed by [imageUri] (`content://<authority>/<providerArtworkId>`), which is stable
 * for the gallery source because MuzeiArtProvider deduplicates by TOKEN. One row is
 * created the first time an image is loaded; favorite/framing writes always target this
 * table rather than the rolling [Artwork] history log so that choices persist across
 * multiple appearances of the same image.
 */
@Entity(tableName = "image_metadata")
data class ImageMetadata(
        @field:TypeConverters(UriTypeConverter::class)
        @PrimaryKey
        val imageUri: Uri,
        val providerAuthority: String
) {
    @ColumnInfo(name = "is_favorite", defaultValue = "0")
    var isFavorite: Boolean = false

    @ColumnInfo(name = "saved_viewport_left")
    var savedViewportLeft: Float? = null

    @ColumnInfo(name = "saved_viewport_top")
    var savedViewportTop: Float? = null

    @ColumnInfo(name = "saved_viewport_right")
    var savedViewportRight: Float? = null

    @ColumnInfo(name = "saved_viewport_bottom")
    var savedViewportBottom: Float? = null

    val hasSavedViewport: Boolean
        get() = savedViewportLeft != null && savedViewportTop != null
                && savedViewportRight != null && savedViewportBottom != null
}
