/*
 * Copyright (C) 2016 The Android Open Source Project
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

#pragma once

#include <SkRect.h>
#include <SkSurface.h>
#include <algorithm>
#include <cmath>
#include <cstdint>

#include "Properties.h"
#include "Matrix.h"

namespace android {
namespace uirenderer {
namespace skiapipeline {

/**
 * An offscreen rendering target used to contain the contents a RenderNode.
 */
struct SkiaLayer {
    sk_sp<SkSurface> layerSurface;
    Matrix4 inverseTransformInWindow;
    bool hasRenderedSinceRepaint = false;
    float renderScale = 1.0f;
    int requestedWidth = 0;
    int requestedHeight = 0;
};


inline float getRenderEffectLayerScaleForSize(int width, int height) {
    if (Properties::renderEffectLargeLayerMinArea <= 0 ||
            Properties::renderEffectLargeLayerScale >= Properties::renderEffectLayerScale ||
            width <= 0 || height <= 0) {
        return Properties::renderEffectLayerScale;
    }
    const int64_t area = static_cast<int64_t>(width) * static_cast<int64_t>(height);
    return area >= Properties::renderEffectLargeLayerMinArea
            ? Properties::renderEffectLargeLayerScale : Properties::renderEffectLayerScale;
}

inline SkIRect scaleLayerRectOut(const SkIRect& rect, float scale) {
    if (scale >= 1.0f || rect.isEmpty()) {
        return rect;
    }
    return SkIRect::MakeLTRB(floorf(rect.left() * scale), floorf(rect.top() * scale),
                             ceilf(rect.right() * scale), ceilf(rect.bottom() * scale));
}

inline int getScaledLayerContentSize(float size, float scale) {
    return std::max(1, static_cast<int>(ceilf(size * scale)));
}

inline int getScaledLayerSurfaceSize(float size, float scale) {
    return static_cast<int>(
                   ceilf(std::max(1.0f, size * scale) / static_cast<float>(LAYER_SIZE))) *
            LAYER_SIZE;
}

} /* namespace skiapipeline */
} /* namespace uirenderer */
} /* namespace android */
