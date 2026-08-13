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
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.TypeConverters
import com.google.android.apps.muzei.room.converter.UriTypeConverter
import kotlinx.coroutines.flow.Flow

/**
 * DAO for [ImageMetadata] — the per-image source of truth for favorite flag and saved viewport.
 */
@Dao
@TypeConverters(UriTypeConverter::class)
abstract class ImageMetadataDao {

    /**
     * Ensures a row exists for [imageUri]. Ignores conflicts so it is safe to call on every
     * artwork load without overwriting existing favorite/framing data.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun ensureRow(metadata: ImageMetadata)

    @Query("SELECT * FROM image_metadata WHERE imageUri = :imageUri")
    abstract fun getByImageUriFlow(imageUri: Uri): Flow<ImageMetadata?>

    @Query("SELECT * FROM image_metadata WHERE imageUri = :imageUri")
    abstract suspend fun getByImageUri(imageUri: Uri): ImageMetadata?

    @Query("UPDATE image_metadata SET is_favorite = :isFavorite WHERE imageUri = :imageUri")
    abstract suspend fun setFavorite(imageUri: Uri, isFavorite: Boolean)

    @Query("""UPDATE image_metadata SET
        saved_viewport_left = :left,
        saved_viewport_top = :top,
        saved_viewport_right = :right,
        saved_viewport_bottom = :bottom
        WHERE imageUri = :imageUri""")
    abstract suspend fun updateSavedViewport(
        imageUri: Uri,
        left: Float?,
        top: Float?,
        right: Float?,
        bottom: Float?
    )

    /**
     * Returns a random favorited image, or null if none exist. Used by the favorites-boost
     * feature to pick an image to show. Pass [excludeUri] (typically the current artwork) to
     * avoid picking the image that is already showing, so that "Next" always rotates to a
     * different favorite; pass null to consider every favorite.
     */
    @Query("""SELECT * FROM image_metadata WHERE is_favorite = 1
        AND (:excludeUri IS NULL OR imageUri != :excludeUri)
        ORDER BY RANDOM() LIMIT 1""")
    abstract suspend fun getRandomFavorite(excludeUri: Uri?): ImageMetadata?
}
