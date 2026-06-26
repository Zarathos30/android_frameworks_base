/*
 * Copyright (C) 2022 The Android Open Source Project
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

#include "BackdropFilterDrawable.h"

#include <SkColor.h>
#include <SkImage.h>
#include <SkImageFilter.h>
#include <SkSurface.h>

#include "RenderNode.h"
#include "RenderNodeDrawable.h"
#include "SkiaLayer.h"
#ifdef __ANDROID__
#include "include/gpu/ganesh/SkImageGanesh.h"
#endif

namespace android {
namespace uirenderer {
namespace skiapipeline {

static sk_sp<SkImage> makeScaledBackdropImage(SkCanvas* canvas, const sk_sp<SkImage>& image,
                                              float scale, sk_sp<SkSurface>* scaledSurface) {
    if (scale <= 0.0f || scale >= 1.0f || image == nullptr) {
        return nullptr;
    }
    const int scaledWidth = getScaledLayerContentSize(image->width(), scale);
    const int scaledHeight = getScaledLayerContentSize(image->height(), scale);
    if (scaledWidth >= image->width() && scaledHeight >= image->height()) {
        return nullptr;
    }
    if (scaledSurface == nullptr) {
        return nullptr;
    }
    const SkImageInfo imageInfo = canvas->imageInfo().makeWH(scaledWidth, scaledHeight);
    const auto* recordingContext = canvas->recordingContext();
    if (*scaledSurface == nullptr || (*scaledSurface)->imageInfo() != imageInfo ||
            (*scaledSurface)->getCanvas()->recordingContext() != recordingContext) {
        *scaledSurface = canvas->makeSurface(imageInfo);
    }
    if (*scaledSurface == nullptr) {
        return nullptr;
    }
    SkCanvas* scaledCanvas = (*scaledSurface)->getCanvas();
    scaledCanvas->clear(SK_ColorTRANSPARENT);
    scaledCanvas->drawImageRect(image, SkRect::MakeWH(image->width(), image->height()),
                                SkRect::MakeWH(scaledWidth, scaledHeight),
                                SkSamplingOptions(SkFilterMode::kLinear), nullptr,
                                SkCanvas::kStrict_SrcRectConstraint);
    return (*scaledSurface)->makeImageSnapshot();
}

struct BackdropScale {
    float inputScale;
    float filterScale;
};

static BackdropScale backdropScale(RenderNode* node) {
    const RenderProperties& properties = node->properties();
    const float targetScale = getRenderEffectLayerScaleForSize(
            properties.getWidth(), properties.getHeight());
    const float inheritedScale = node->inheritedRenderEffectLayerScale();
    if (inheritedScale <= 0.0f || inheritedScale >= 1.0f) {
        return {targetScale, targetScale};
    }
    const float effectiveScale = targetScale < inheritedScale ? targetScale : inheritedScale;
    return {effectiveScale / inheritedScale, effectiveScale};
}

void BackdropFilterDrawable::onDraw(SkCanvas* canvas) {
    const RenderProperties& properties = mTargetRenderNode->properties();
    auto* backdropFilter = properties.layerProperties().getBackdropImageFilter();
    auto* surface = canvas->getSurface();
    if (!backdropFilter || !surface) {
        mScaledBackdropSurface = nullptr;
        return;
    }

    SkRect srcBounds = SkRect::MakeWH(properties.getWidth(), properties.getHeight());

    float alphaMultiplier = 1.0f;
    RenderNodeDrawable::setViewProperties(properties, canvas, &alphaMultiplier, true);
    SkPaint paint;
    paint.setAlpha(properties.layerProperties().alpha() * alphaMultiplier);

    SkRect surfaceSubset;
    canvas->getTotalMatrix().mapRect(&surfaceSubset, srcBounds);
    if (!surfaceSubset.intersect(SkRect::MakeWH(surface->width(), surface->height()))) {
        mScaledBackdropSurface = nullptr;
        return;
    }

    auto backdropImage = surface->makeImageSnapshot(surfaceSubset.roundOut());
    sk_sp<SkImageFilter> scaledBackdropFilter;
    if (isLayerScaledBlurRenderEffect(backdropFilter)) {
        const BackdropScale scale = backdropScale(mTargetRenderNode);
        scaledBackdropFilter = makeLayerScaledBlurRenderEffect(backdropFilter, scale.filterScale);
        if (scaledBackdropFilter != nullptr) {
            bool useScaledFilter = scale.inputScale >= 1.0f;
            if (scale.inputScale < 1.0f) {
                sk_sp<SkImage> scaledBackdropImage =
                        makeScaledBackdropImage(canvas, backdropImage, scale.inputScale,
                                                &mScaledBackdropSurface);
                if (scaledBackdropImage != nullptr) {
                    backdropImage = scaledBackdropImage;
                    useScaledFilter = true;
                } else {
                    mScaledBackdropSurface = nullptr;
                }
            } else {
                mScaledBackdropSurface = nullptr;
            }
            if (useScaledFilter) {
                backdropFilter = scaledBackdropFilter.get();
            } else {
                scaledBackdropFilter = nullptr;
            }
        } else {
            mScaledBackdropSurface = nullptr;
        }
    } else {
        mScaledBackdropSurface = nullptr;
    }

    SkIRect imageBounds = SkIRect::MakeWH(backdropImage->width(), backdropImage->height());
    SkIPoint offset;
    SkIRect imageSubset;

#ifdef __ANDROID__
    if (canvas->recordingContext()) {
        backdropImage =
                SkImages::MakeWithFilter(canvas->recordingContext(), backdropImage, backdropFilter,
                                         imageBounds, imageBounds, &imageSubset, &offset);
    } else
#endif
    {
        backdropImage = SkImages::MakeWithFilter(backdropImage, backdropFilter, imageBounds,
                                                 imageBounds, &imageSubset, &offset);
    }

    canvas->save();
    canvas->resetMatrix();
    canvas->drawImageRect(backdropImage, SkRect::Make(imageSubset), surfaceSubset,
                          SkSamplingOptions(SkFilterMode::kLinear), &paint,
                          SkCanvas::kFast_SrcRectConstraint);
    canvas->restore();
}

}  // namespace skiapipeline
}  // namespace uirenderer
}  // namespace android
