/*
 * Copyright 2025-2026 AxionOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.compose.animation.scene

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.VectorConverter

internal fun AnimationSpec<Float>.durationMillis(
    initialValue: Float,
    targetValue: Float,
    initialVelocity: Float,
): Long {
    val converter = Float.VectorConverter
    return vectorize(converter)
        .getDurationNanos(
            converter.convertToVector(initialValue),
            converter.convertToVector(targetValue),
            converter.convertToVector(initialVelocity),
        ) / NANOS_PER_MILLISECOND
}

private const val NANOS_PER_MILLISECOND = 1_000_000L
