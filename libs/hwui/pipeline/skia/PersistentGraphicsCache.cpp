/*
 * Copyright (C) 2025 The Android Open Source Project
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

#include "PersistentGraphicsCache.h"

#include <SkData.h>
#include <SkRefCnt.h>
#include <SkString.h>
#include <android-base/properties.h>
#include <ganesh/GrDirectContext.h>

#include <cstddef>
#include <memory>
#include <string>
#include <utility>

#include "Properties.h"
#include "ShaderCache.h"

#ifdef __linux__
#include <com_android_graphics_hwui_flags.h>
namespace hwui_flags = com::android::graphics::hwui::flags;
#else   // __linux__
namespace hwui_flags {
constexpr bool separate_pipeline_cache() {
    return false;
}
}  // namespace hwui_flags
#endif  // __linux__

namespace {

constexpr char kEnablePipelineCacheProperty[] = "persist.sys.hwui.pcache";
constexpr char kPipelineCacheSizeProperty[] = "persist.sys.hwui.pcache_mb";

bool useSeparatePipelineCache() {
    static const bool enabled = hwui_flags::separate_pipeline_cache() ||
            android::base::GetBoolProperty(kEnablePipelineCacheProperty, false);
    return enabled;
}

size_t maxPipelineSizeBytes() {
    static const int sizeMb = android::base::GetIntProperty<int>(kPipelineCacheSizeProperty, 2, 1,
                                                                 16);
    return static_cast<size_t>(sizeMb) * 1024 * 1024;
}

}  // namespace

namespace android {
namespace uirenderer {
namespace skiapipeline {

PersistentGraphicsCache& PersistentGraphicsCache::get() {
    static PersistentGraphicsCache cache;
    return cache;
}

void PersistentGraphicsCache::initPipelineCache(std::string path,
                                                useconds_t writeThrottleInterval) {
    if (!useSeparatePipelineCache()) {
        return;
    }

    mPipelineCache = std::make_unique<PipelineCache>(std::move(path), writeThrottleInterval);
}

void PersistentGraphicsCache::onVkFrameFlushed(GrDirectContext* context) {
    class RealGrDirectContext : public GrDirectContextWrapper {
    private:
        GrDirectContext* mContext;

    public:
        RealGrDirectContext(GrDirectContext* context) : mContext(context) {}

        bool canDetectNewVkPipelineCacheData() const override {
            return mContext->canDetectNewVkPipelineCacheData();
        }

        bool hasNewVkPipelineCacheData() const override {
            return mContext->hasNewVkPipelineCacheData();
        }

        void storeVkPipelineCacheData(size_t maxSize) override {
            return mContext->storeVkPipelineCacheData(maxSize);
        }

        GrDirectContext* unwrap() const override { return mContext; }
    };

    RealGrDirectContext wrapper(context);
    onVkFrameFlushed(&wrapper);
}

void PersistentGraphicsCache::onVkFrameFlushed(GrDirectContextWrapper* context) {
    if (!useSeparatePipelineCache() || mPipelineCache == nullptr) {
        ShaderCache::get().onVkFrameFlushed(context->unwrap());
        return;
    }

    mCanDetectNewVkPipelineCacheData = context->canDetectNewVkPipelineCacheData();
    if (context->hasNewVkPipelineCacheData()) {
        context->storeVkPipelineCacheData(maxPipelineSizeBytes());
    }
}

sk_sp<SkData> PersistentGraphicsCache::load(const SkData& key) {
    if (!useSeparatePipelineCache() || mPipelineCache == nullptr) {
        return ShaderCache::get().load(key);
    }

    auto data = mPipelineCache->tryLoad(key);
    if (data != nullptr) {
        return data;
    }

    return ShaderCache::get().load(key);
}

void PersistentGraphicsCache::store(const SkData& key, const SkData& data,
                                    const SkString& description) {
    if (!useSeparatePipelineCache() || mPipelineCache == nullptr) {
        ShaderCache::get().store(key, data, description);
        return;
    }

    if (mPipelineCache->canStore(description)) {
        if (mCanDetectNewVkPipelineCacheData) {
            mPipelineCache->store(key, data);
        } else if (mLastPipelineCacheSize != data.size()) {
            mPipelineCache->store(key, data);
            mLastPipelineCacheSize = data.size();
        }
        return;
    }

    ShaderCache::get().store(key, data, description);
}

}  // namespace skiapipeline
}  // namespace uirenderer
}  // namespace android
