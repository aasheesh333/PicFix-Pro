#include <android/bitmap.h>
#include <android/log.h>
#include <jni.h>

#include <cstdlib>
#include <cstring>
#include <string>

#include "net.h"
#include "mat.h"
#include "gpu.h"

#define LOG_TAG "RealESRGAN_JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static ncnn::Net* g_net = nullptr;
static int g_scale = 4;
static std::string g_input_blob_name;
static std::string g_output_blob_name;

static long getAvailableMemKB()
{
    long val = 0;
    FILE* fp = fopen("/proc/meminfo", "r");
    if (fp)
    {
        char line[256];
        while (fgets(line, sizeof(line), fp))
        {
            if (sscanf(line, "MemAvailable: %ld kB", &val) == 1)
                break;
        }
        fclose(fp);
    }
    return val;
}

static int autoTileSize(int scale)
{
    long availKB = getAvailableMemKB();
    if (availKB > 1900LL * 1024)
        return 200;
    if (availKB > 550LL * 1024)
        return 100;
    if (availKB > 190LL * 1024)
        return 64;
    return 32;
}

static bool loadModel(const char* param_path, const char* model_path, int gpu_id)
{
    if (g_net)
    {
        delete g_net;
        g_net = nullptr;
    }

    g_net = new ncnn::Net();

    g_net->opt.use_vulkan_compute = false;
    g_net->opt.use_fp16_packed = true;
    g_net->opt.use_fp16_storage = true;
    g_net->opt.use_fp16_arithmetic = false;
    g_net->opt.use_int8_storage = true;
    g_net->opt.use_int8_arithmetic = false;

    bool use_gpu = (gpu_id >= 0) && (ncnn::get_gpu_count() > 0);
    if (use_gpu)
    {
        g_net->opt.use_vulkan_compute = true;
        int dev = gpu_id;
        if (dev >= ncnn::get_gpu_count())
            dev = 0;
        g_net->set_vulkan_device(dev);
        LOGI("Using Vulkan device %d", dev);
    }

    int ret = g_net->load_param(param_path);
    if (ret != 0)
    {
        if (use_gpu)
        {
            LOGW("load_param failed with Vulkan (ret=%d), retrying on CPU", ret);
            delete g_net;
            g_net = new ncnn::Net();
            g_net->opt.use_vulkan_compute = false;
            g_net->opt.use_fp16_packed = true;
            g_net->opt.use_fp16_storage = true;
            g_net->opt.use_fp16_arithmetic = false;
            g_net->opt.use_int8_storage = true;
            g_net->opt.use_int8_arithmetic = false;

            ret = g_net->load_param(param_path);
            if (ret != 0)
            {
                LOGE("load_param failed on CPU too: %d", ret);
                delete g_net;
                g_net = nullptr;
                return false;
            }

            ret = g_net->load_model(model_path);
            if (ret != 0)
            {
                LOGE("load_model failed on CPU: %d", ret);
                delete g_net;
                g_net = nullptr;
                return false;
            }
        }
        else
        {
            LOGE("load_param failed: %d", ret);
            delete g_net;
            g_net = nullptr;
            return false;
        }
    }
    else
    {
        ret = g_net->load_model(model_path);
        if (ret != 0)
        {
            if (use_gpu)
            {
                LOGW("load_model failed with Vulkan (ret=%d), retrying on CPU", ret);
                delete g_net;
                g_net = new ncnn::Net();
                g_net->opt.use_vulkan_compute = false;
                g_net->opt.use_fp16_packed = true;
                g_net->opt.use_fp16_storage = true;
                g_net->opt.use_fp16_arithmetic = false;
                g_net->opt.use_int8_storage = true;
                g_net->opt.use_int8_arithmetic = false;

                ret = g_net->load_param(param_path);
                if (ret != 0)
                {
                    LOGE("load_param failed on CPU retry: %d", ret);
                    delete g_net;
                    g_net = nullptr;
                    return false;
                }

                ret = g_net->load_model(model_path);
                if (ret != 0)
                {
                    LOGE("load_model failed on CPU retry: %d", ret);
                    delete g_net;
                    g_net = nullptr;
                    return false;
                }
            }
            else
            {
                LOGE("load_model failed: %d", ret);
                delete g_net;
                g_net = nullptr;
                return false;
            }
        }
    }

    const std::vector<int>& in_idxs = g_net->input_indexes();
    const std::vector<int>& out_idxs = g_net->output_indexes();
    if (!in_idxs.empty())
    {
        const std::vector<ncnn::Blob>& blobs = g_net->blobs();
        g_input_blob_name = blobs[in_idxs[0]].name;
    }
    else
    {
        g_input_blob_name = "0";
    }
    if (!out_idxs.empty())
    {
        const std::vector<ncnn::Blob>& blobs = g_net->blobs();
        g_output_blob_name = blobs[out_idxs[0]].name;
    }
    else
    {
        const std::vector<ncnn::Blob>& blobs = g_net->blobs();
        g_output_blob_name = blobs.back().name;
    }

    LOGI("Input blob: '%s', Output blob: '%s'",
         g_input_blob_name.c_str(), g_output_blob_name.c_str());

    return true;
}

static jobject createArgbBitmap(JNIEnv* env, int w, int h)
{
    jclass bitmap_cls = env->FindClass("android/graphics/Bitmap");
    jmethodID create_method = env->GetStaticMethodID(
        bitmap_cls, "createBitmap",
        "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
    jclass config_cls = env->FindClass("android/graphics/Bitmap$Config");
    jfieldID argb8888_fid = env->GetStaticFieldID(
        config_cls, "ARGB_8888", "Landroid/graphics/Bitmap$Config;");
    jobject argb8888 = env->GetStaticObjectField(config_cls, argb8888_fid);
    return env->CallStaticObjectMethod(bitmap_cls, create_method, w, h, argb8888);
}

static void reportProgress(JNIEnv* env, jobject listener, jmethodID mid, float progress)
{
    if (listener && mid)
        env->CallVoidMethod(listener, mid, progress);
}

extern "C" {

JNIEXPORT jint JNICALL
Java_com_dhanuk_photodoctorpro_nativ_RealESRGANNativeLib_nativeInit(
    JNIEnv* env, jobject thiz, jstring param_path, jstring model_path,
    jint scale, jint gpu_id)
{
    const char* param_c = env->GetStringUTFChars(param_path, nullptr);
    const char* model_c = env->GetStringUTFChars(model_path, nullptr);
    if (!param_c || !model_c)
    {
        LOGE("null param_path or model_path");
        if (param_c) env->ReleaseStringUTFChars(param_path, param_c);
        if (model_c) env->ReleaseStringUTFChars(model_path, model_c);
        return -1;
    }

    LOGI("Loading model: param=%s model=%s scale=%d gpu=%d",
         param_c, model_c, scale, gpu_id);

    bool ok = loadModel(param_c, model_c, gpu_id);

    env->ReleaseStringUTFChars(param_path, param_c);
    env->ReleaseStringUTFChars(model_path, model_c);

    if (!ok)
        return -1;

    g_scale = scale;

    LOGI("Model loaded successfully (scale=%d, vulkan=%d)",
         g_scale, g_net->opt.use_vulkan_compute ? 1 : 0);
    return 0;
}

JNIEXPORT jobject JNICALL
Java_com_dhanuk_photodoctorpro_nativ_RealESRGANNativeLib_nativeEnhance(
    JNIEnv* env, jobject thiz, jobject bitmap, jobject progress_listener)
{
    if (!g_net)
    {
        LOGE("Net not initialized");
        return nullptr;
    }

    jmethodID on_progress_mid = nullptr;
    if (progress_listener)
    {
        jclass listener_cls = env->GetObjectClass(progress_listener);
        if (listener_cls)
            on_progress_mid = env->GetMethodID(listener_cls, "onProgress", "(F)V");
    }

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS)
    {
        LOGE("AndroidBitmap_getInfo failed");
        return nullptr;
    }

    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888)
    {
        LOGE("Bitmap format is not RGBA_8888");
        return nullptr;
    }

    int w = info.width;
    int h = info.height;
    int scale = g_scale;
    int out_w = w * scale;
    int out_h = h * scale;

    LOGI("Enhancing %dx%d -> %dx%d (scale=%d)", w, h, out_w, out_h, scale);

    void* input_pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &input_pixels) != ANDROID_BITMAP_RESULT_SUCCESS)
    {
        LOGE("AndroidBitmap_lockPixels failed");
        return nullptr;
    }

    int tile_size = autoTileSize(scale);
    int TILE_SIZE_X = tile_size;
    int TILE_SIZE_Y = tile_size;
    int prepadding = 10;

    int xtiles = (w + TILE_SIZE_X - 1) / TILE_SIZE_X;
    int ytiles = (h + TILE_SIZE_Y - 1) / TILE_SIZE_Y;
    int total_tiles = xtiles * ytiles;
    int completed = 0;

    jobject out_bitmap = createArgbBitmap(env, out_w, out_h);
    if (!out_bitmap)
    {
        AndroidBitmap_unlockPixels(env, bitmap);
        LOGE("Failed to create output bitmap");
        return nullptr;
    }

    AndroidBitmapInfo out_info;
    AndroidBitmap_getInfo(env, out_bitmap, &out_info);
    void* output_pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, out_bitmap, &output_pixels) != ANDROID_BITMAP_RESULT_SUCCESS)
    {
        AndroidBitmap_unlockPixels(env, bitmap);
        env->DeleteLocalRef(out_bitmap);
        LOGE("AndroidBitmap_lockPixels on output failed");
        return nullptr;
    }

    LOGI("Tiling: tile=%d xtiles=%d ytiles=%d prepadding=%d",
         tile_size, xtiles, ytiles, prepadding);

    const float mean_vals[3] = {0.f, 0.f, 0.f};
    const float norm_vals[3] = {1.f / 255.f, 1.f / 255.f, 1.f / 255.f};
    const float out_mean[3] = {0.f, 0.f, 0.f};
    const float out_norm[3] = {255.f, 255.f, 255.f};

    ncnn::Option opt_default;

    for (int yi = 0; yi < ytiles; yi++)
    {
        for (int xi = 0; xi < xtiles; xi++)
        {
            int tile_x0 = xi * TILE_SIZE_X;
            int tile_y0 = yi * TILE_SIZE_Y;
            int tile_x1 = (xi + 1) * TILE_SIZE_X;
            int tile_y1 = (yi + 1) * TILE_SIZE_Y;
            if (tile_x1 > w) tile_x1 = w;
            if (tile_y1 > h) tile_y1 = h;

            int pad_x0 = tile_x0 - prepadding;
            int pad_y0 = tile_y0 - prepadding;
            int pad_x1 = tile_x1 + prepadding;
            int pad_y1 = tile_y1 + prepadding;
            if (pad_x0 < 0) pad_x0 = 0;
            if (pad_y0 < 0) pad_y0 = 0;
            if (pad_x1 > w) pad_x1 = w;
            if (pad_y1 > h) pad_y1 = h;

            int crop_w = pad_x1 - pad_x0;
            int crop_h = pad_y1 - pad_y0;

            ncnn::Mat in_tile = ncnn::Mat::from_pixels_roi(
                (const unsigned char*)input_pixels,
                ncnn::Mat::PIXEL_RGBA2RGB,
                w, h,
                pad_x0, pad_y0, crop_w, crop_h);

            if (in_tile.empty())
            {
                LOGE("from_pixels_roi failed for tile (%d,%d)", xi, yi);
                AndroidBitmap_unlockPixels(env, out_bitmap);
                AndroidBitmap_unlockPixels(env, bitmap);
                env->DeleteLocalRef(out_bitmap);
                return nullptr;
            }

            in_tile.substract_mean_normalize(mean_vals, norm_vals);

            ncnn::Extractor ex = g_net->create_extractor();
            ex.input(g_input_blob_name.c_str(), in_tile);

            ncnn::Mat out_tile;
            int ret = ex.extract(g_output_blob_name.c_str(), out_tile);
            if (ret != 0 || out_tile.empty())
            {
                LOGE("Inference failed for tile (%d,%d): ret=%d", xi, yi, ret);
                AndroidBitmap_unlockPixels(env, out_bitmap);
                AndroidBitmap_unlockPixels(env, bitmap);
                env->DeleteLocalRef(out_bitmap);
                return nullptr;
            }

            out_tile.substract_mean_normalize(out_mean, out_norm);

            int sx = (tile_x0 - pad_x0) * scale;
            int sy = (tile_y0 - pad_y0) * scale;
            int valid_w = (tile_x1 - tile_x0) * scale;
            int valid_h = (tile_y1 - tile_y0) * scale;

            ncnn::Mat valid_out(valid_w, valid_h, 3);
            for (int c = 0; c < 3; c++)
            {
                const float* src_ptr = out_tile.channel(c);
                float* dst_ptr = valid_out.channel(c);
                for (int y = 0; y < valid_h; y++)
                {
                    memcpy(dst_ptr + y * valid_w,
                           src_ptr + (sy + y) * out_tile.w + sx,
                           valid_w * sizeof(float));
                }
            }

            unsigned char* out_rgba = (unsigned char*)output_pixels;
            int dst_x = tile_x0 * scale;
            int dst_y = tile_y0 * scale;

            for (int y = 0; y < valid_h; y++)
            {
                const float* ptr_r = valid_out.channel(0) + y * valid_w;
                const float* ptr_g = valid_out.channel(1) + y * valid_w;
                const float* ptr_b = valid_out.channel(2) + y * valid_w;

                unsigned char* row = out_rgba +
                    (dst_y + y) * out_info.stride + dst_x * 4;

                for (int x = 0; x < valid_w; x++)
                {
                    float r = ptr_r[x];
                    float g = ptr_g[x];
                    float b = ptr_b[x];
                    row[x * 4 + 0] = (unsigned char)(r < 0.f ? 0 : r > 255.f ? 255 : r);
                    row[x * 4 + 1] = (unsigned char)(g < 0.f ? 0 : g > 255.f ? 255 : g);
                    row[x * 4 + 2] = (unsigned char)(b < 0.f ? 0 : b > 255.f ? 255 : b);
                    row[x * 4 + 3] = 255;
                }
            }
        }

        completed += xtiles;
        reportProgress(env, progress_listener, on_progress_mid,
                       (float)completed / total_tiles);
    }

    AndroidBitmap_unlockPixels(env, out_bitmap);
    AndroidBitmap_unlockPixels(env, bitmap);

    reportProgress(env, progress_listener, on_progress_mid, 1.0f);

    LOGI("Enhancement complete");
    return out_bitmap;
}

JNIEXPORT void JNICALL
Java_com_dhanuk_photodoctorpro_nativ_RealESRGANNativeLib_nativeCleanup(
    JNIEnv* env, jobject thiz)
{
    if (g_net)
    {
        delete g_net;
        g_net = nullptr;
    }
    g_scale = 4;
    g_input_blob_name.clear();
    g_output_blob_name.clear();
    LOGI("Cleanup done");
}

JNIEXPORT jboolean JNICALL
Java_com_dhanuk_photodoctorpro_nativ_RealESRGANNativeLib_isVulkanAvailable(
    JNIEnv* env, jobject thiz)
{
    int gpu_count = ncnn::get_gpu_count();
    LOGI("Vulkan GPU count: %d", gpu_count);
    return gpu_count > 0 ? JNI_TRUE : JNI_FALSE;
}

}
