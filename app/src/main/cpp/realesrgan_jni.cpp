#include <android/asset_manager.h>
#include <android/asset_manager_jniproxy.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <jni.h>

#include <cstdlib>
#include <cstring>

#include "net.h"
#include "mat.h"
#include "gpu.h"

#define LOG_TAG "RealESRGAN_JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static ncnn::Net* g_net = nullptr;
static int g_scale = 2;

class AssetDataReader : public ncnn::DataReader
{
public:
    AssetDataReader(AAssetManager* mgr, const std::string& filename)
    {
        asset_ = AAssetManager_open(mgr, filename.c_str(), AASSET_MODE_STREAMING);
    }

    ~AssetDataReader()
    {
        if (asset_)
            AAsset_close(asset_);
    }

    virtual int read(void* buf, int size) override
    {
        if (!asset_)
            return -1;
        int remaining = AAsset_getRemainingLength(asset_);
        int read_size = size < remaining ? size : remaining;
        if (read_size == 0)
            return 0;
        int n = AAsset_read(asset_, buf, read_size);
        return n > 0 ? n : -1;
    }

    virtual int scan(const char* format, void* p) override
    {
        if (!asset_)
            return -1;
        int remaining = AAsset_getRemainingLength(asset_);
        if (remaining <= 0)
            return -1;
        off_t seek_pos = AAsset_seek(asset_, 0, SEEK_CUR);
        const char* buf = (const char*)AAsset_getBuffer(asset_);
        if (!buf)
            return -1;
        int n = sscanf(buf + seek_pos, format, p);
        if (n == 1)
        {
            while (remaining > 0)
            {
                char c = buf[seek_pos];
                AAsset_seek(asset_, 1, SEEK_CUR);
                remaining--;
                seek_pos++;
                if (c == '\n' || c == '\r' || c == ' ' || c == '\t')
                    break;
            }
        }
        return n == 1 ? 0 : -1;
    }

private:
    AAsset* asset_ = nullptr;
};

static long getJavaHeap()
{
    long heap = 0;
    FILE* fp = fopen("/proc/meminfo", "r");
    if (fp)
    {
        char line[256];
        while (fgets(line, sizeof(line), fp))
        {
            long val;
            if (sscanf(line, "MemAvailable: %ld kB", &val) == 1)
            {
                heap = val * 1024;
                break;
            }
        }
        fclose(fp);
    }
    return heap;
}

static int autoTileSize(int scale)
{
    long avail = getJavaHeap();
    if (avail > 1900LL * 1024 * 1024)
        return 200 / scale > 0 ? 200 : 100;
    if (avail > 550LL * 1024 * 1024)
        return 100;
    if (avail > 190LL * 1024 * 1024)
        return 64;
    return 32;
}

static ncnn::Mat bitmapToNcnnMat(JNIEnv* env, jobject bitmap)
{
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS)
    {
        LOGE("AndroidBitmap_getInfo failed");
        return ncnn::Mat();
    }

    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888)
    {
        LOGE("Bitmap format is not RGBA_8888");
        return ncnn::Mat();
    }

    int w = info.width;
    int h = info.height;

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS)
    {
        LOGE("AndroidBitmap_lockPixels failed");
        return ncnn::Mat();
    }

    ncnn::Mat mat(w, h, 3);
    if (mat.empty())
    {
        AndroidBitmap_unlockPixels(env, bitmap);
        LOGE("Failed to allocate ncnn::Mat");
        return ncnn::Mat();
    }

    const unsigned char* rgba = (const unsigned char*)pixels;
    float* ptr_r = mat.channel(0);
    float* ptr_g = mat.channel(1);
    float* ptr_b = mat.channel(2);

    for (int y = 0; y < h; y++)
    {
        const unsigned char* row = rgba + y * w * 4;
        for (int x = 0; x < w; x++)
        {
            ptr_r[y * w + x] = row[x * 4 + 0] / 255.f;
            ptr_g[y * w + x] = row[x * 4 + 1] / 255.f;
            ptr_b[y * w + x] = row[x * 4 + 2] / 255.f;
        }
    }

    AndroidBitmap_unlockPixels(env, bitmap);
    return mat;
}

static bool ncnnMatToBitmap(JNIEnv* env, const ncnn::Mat& mat, int out_w, int out_h, jobject* out_bitmap)
{
    jclass bitmap_cls = env->FindClass("android/graphics/Bitmap");
    jmethodID create_bitmap = env->GetStaticMethodID(
        bitmap_cls, "createBitmap",
        "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
    jclass config_cls = env->FindClass("android/graphics/Bitmap$Config");
    jfieldID argb8888_field = env->GetStaticFieldID(config_cls, "ARGB_8888",
        "Landroid/graphics/Bitmap$Config;");
    jobject argb8888 = env->GetStaticObject(config_cls, argb8888_field);

    jobject bitmap = env->CallStaticObjectMethod(bitmap_cls, create_bitmap, out_w, out_h, argb8888);
    if (!bitmap)
    {
        LOGE("Bitmap.createBitmap returned null");
        return false;
    }

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS)
    {
        env->DeleteLocalRef(bitmap);
        return false;
    }

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS)
    {
        env->DeleteLocalRef(bitmap);
        return false;
    }

    const float* ptr_r = mat.channel(0);
    const float* ptr_g = mat.channel(1);
    const float* ptr_b = mat.channel(2);

    unsigned char* rgba = (unsigned char*)pixels;
    for (int y = 0; y < out_h; y++)
    {
        unsigned char* row = rgba + y * out_w * 4;
        for (int x = 0; x < out_w; x++)
        {
            float r = ptr_r[y * out_w + x] * 255.f;
            float g = ptr_g[y * out_w + x] * 255.f;
            float b = ptr_b[y * out_w + x] * 255.f;
            row[x * 4 + 0] = (unsigned char)(r < 0.f ? 0 : r > 255.f ? 255 : r);
            row[x * 4 + 1] = (unsigned char)(g < 0.f ? 0 : g > 255.f ? 255 : g);
            row[x * 4 + 2] = (unsigned char)(b < 0.f ? 0 : b > 255.f ? 255 : b);
            row[x * 4 + 3] = 255;
        }
    }

    AndroidBitmap_unlockPixels(env, bitmap);
    *out_bitmap = bitmap;
    return true;
}

static ncnn::Mat extractRoi(const ncnn::Mat& src, int x0, int y0, int w, int h)
{
    ncnn::Mat roi(w, h, 3);
    if (roi.empty())
        return roi;

    for (int c = 0; c < 3; c++)
    {
        const float* src_ptr = src.channel(c);
        float* dst_ptr = roi.channel(c);
        for (int y = 0; y < h; y++)
        {
            memcpy(dst_ptr + y * w, src_ptr + (y0 + y) * src.w + x0, w * sizeof(float));
        }
    }
    return roi;
}

static void copyRoiToMat(const ncnn::Mat& roi, ncnn::Mat& dst, int dst_x, int dst_y)
{
    for (int c = 0; c < 3; c++)
    {
        const float* src_ptr = roi.channel(c);
        float* dst_ptr = dst.channel(c);
        for (int y = 0; y < roi.h; y++)
        {
            memcpy(dst_ptr + (dst_y + y) * dst.w + dst_x,
                   src_ptr + y * roi.w,
                   roi.w * sizeof(float));
        }
    }
}

static void reportProgress(JNIEnv* env, jobject listener, jmethodID mid, float progress)
{
    if (listener && mid)
        env->CallVoidMethod(listener, mid, progress);
}

extern "C" {

JNIEXPORT jint JNICALL
Java_com_dhanuk_photodoctorpro_nativ_RealESRGANNativeLib_nativeInit(
    JNIEnv* env, jobject thiz, jobject asset_manager, jstring model_dir,
    jstring model_name, jint scale, jint gpu_id)
{
    if (g_net)
    {
        delete g_net;
        g_net = nullptr;
    }

    g_net = new ncnn::Net();
    if (!g_net)
    {
        LOGE("Failed to allocate ncnn::Net");
        return -1;
    }

    g_net->opt.use_vulkan_compute = true;
    g_net->opt.use_fp16_packed = true;
    g_net->opt.use_fp16_storage = true;
    g_net->opt.use_fp16_arithmetic = false;
    g_net->opt.use_int8_storage = true;
    g_net->opt.use_int8_arithmetic = false;

    bool use_gpu = (gpu_id >= 0) || (gpu_id == -1);

    if (use_gpu && ncnn::get_gpu_count() > 0)
    {
        if (gpu_id >= 0)
        {
            g_net->set_vulkan_device(gpu_id);
            LOGI("Set Vulkan device %d", gpu_id);
        }
        else
        {
            g_net->set_vulkan_device(0);
            LOGI("Auto-selected Vulkan device 0");
        }
    }
    else
    {
        g_net->opt.use_vulkan_compute = false;
        LOGI("Vulkan not available, using CPU");
    }

    AAssetManager* mgr = AAssetManager_fromJava(env, asset_manager);
    if (!mgr)
    {
        LOGE("Failed to get AssetManager");
        delete g_net;
        g_net = nullptr;
        return -1;
    }

    const char* model_dir_c = env->GetStringUTFChars(model_dir, nullptr);
    const char* model_name_c = env->GetStringUTFChars(model_name, nullptr);

    std::string base_path = "models/" + std::string(model_dir_c) + "/" + std::string(model_name_c);
    std::string param_path = base_path + ".param";
    std::string bin_path = base_path + ".bin";

    LOGI("Loading model: %s", base_path.c_str());

    AssetDataReader param_dr(mgr, param_path);
    AssetDataReader bin_dr(mgr, bin_path);

    bool use_vulkan = g_net->opt.use_vulkan_compute;
    int ret = g_net->load_param(param_dr);
    if (ret != 0)
    {
        if (use_vulkan)
        {
            LOGW("load_param failed with Vulkan, retrying on CPU");
            delete g_net;
            g_net = new ncnn::Net();
            g_net->opt.use_vulkan_compute = false;
            g_net->opt.use_fp16_packed = true;
            g_net->opt.use_fp16_storage = true;
            g_net->opt.use_fp16_arithmetic = false;
            g_net->opt.use_int8_storage = true;
            g_net->opt.use_int8_arithmetic = false;

            AssetDataReader param_dr2(mgr, param_path);
            ret = g_net->load_param(param_dr2);
            if (ret != 0)
            {
                LOGE("load_param failed on CPU too: %d", ret);
                env->ReleaseStringUTFChars(model_dir, model_dir_c);
                env->ReleaseStringUTFChars(model_name, model_name_c);
                delete g_net;
                g_net = nullptr;
                return -1;
            }

            AssetDataReader bin_dr2(mgr, bin_path);
            ret = g_net->load_model(bin_dr2);
            if (ret != 0)
            {
                LOGE("load_model failed on CPU: %d", ret);
                env->ReleaseStringUTFChars(model_dir, model_dir_c);
                env->ReleaseStringUTFChars(model_name, model_name_c);
                delete g_net;
                g_net = nullptr;
                return -1;
            }
        }
        else
        {
            LOGE("load_param failed: %d", ret);
            env->ReleaseStringUTFChars(model_dir, model_dir_c);
            env->ReleaseStringUTFChars(model_name, model_name_c);
            delete g_net;
            g_net = nullptr;
            return -1;
        }
    }
    else
    {
        ret = g_net->load_model(bin_dr);
        if (ret != 0)
        {
            if (use_vulkan)
            {
                LOGW("load_model failed with Vulkan, retrying on CPU");
                delete g_net;
                g_net = new ncnn::Net();
                g_net->opt.use_vulkan_compute = false;
                g_net->opt.use_fp16_packed = true;
                g_net->opt.use_fp16_storage = true;
                g_net->opt.use_fp16_arithmetic = false;
                g_net->opt.use_int8_storage = true;
                g_net->opt.use_int8_arithmetic = false;

                AssetDataReader param_dr3(mgr, param_path);
                ret = g_net->load_param(param_dr3);
                if (ret != 0)
                {
                    LOGE("load_param failed on CPU retry: %d", ret);
                    env->ReleaseStringUTFChars(model_dir, model_dir_c);
                    env->ReleaseStringUTFChars(model_name, model_name_c);
                    delete g_net;
                    g_net = nullptr;
                    return -1;
                }

                AssetDataReader bin_dr3(mgr, bin_path);
                ret = g_net->load_model(bin_dr3);
                if (ret != 0)
                {
                    LOGE("load_model failed on CPU retry: %d", ret);
                    env->ReleaseStringUTFChars(model_dir, model_dir_c);
                    env->ReleaseStringUTFChars(model_name, model_name_c);
                    delete g_net;
                    g_net = nullptr;
                    return -1;
                }
            }
            else
            {
                LOGE("load_model failed: %d", ret);
                env->ReleaseStringUTFChars(model_dir, model_dir_c);
                env->ReleaseStringUTFChars(model_name, model_name_c);
                delete g_net;
                g_net = nullptr;
                return -1;
            }
        }
    }

    g_scale = scale;

    env->ReleaseStringUTFChars(model_dir, model_dir_c);
    env->ReleaseStringUTFChars(model_name, model_name_c);

    LOGI("Model loaded successfully (scale=%d, vulkan=%d)", g_scale,
         g_net->opt.use_vulkan_compute ? 1 : 0);
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
    jclass listener_cls = nullptr;
    if (progress_listener)
    {
        listener_cls = env->GetObjectClass(progress_listener);
        if (listener_cls)
            on_progress_mid = env->GetMethodID(listener_cls, "onProgress", "(F)V");
    }

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS)
    {
        LOGE("AndroidBitmap_getInfo failed");
        return nullptr;
    }

    int w = info.width;
    int h = info.height;
    int scale = g_scale;
    int out_w = w * scale;
    int out_h = h * scale;

    LOGI("Enhancing %dx%d -> %dx%d (scale=%d)", w, h, out_w, out_h, scale);

    ncnn::Mat inimage = bitmapToNcnnMat(env, bitmap);
    if (inimage.empty())
    {
        LOGE("bitmapToNcnnMat failed");
        return nullptr;
    }

    ncnn::Mat outimage(out_w, out_h, 3);
    if (outimage.empty())
    {
        LOGE("Failed to allocate output Mat %dx%d", out_w, out_h);
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

    LOGI("Tiling: tile=%d xtiles=%d ytiles=%d prepadding=%d",
         tile_size, xtiles, ytiles, prepadding);

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

            ncnn::Mat tile_in = extractRoi(inimage, pad_x0, pad_y0, crop_w, crop_h);
            if (tile_in.empty())
            {
                LOGE("extractRoi failed for tile (%d,%d)", xi, yi);
                return nullptr;
            }

            ncnn::Extractor ex = g_net->create_extractor();
            ex.input("input", tile_in);

            ncnn::Mat tile_out;
            int ret = ex.extract("output", tile_out);
            if (ret != 0 || tile_out.empty())
            {
                LOGE("Inference failed for tile (%d,%d): ret=%d", xi, yi, ret);
                return nullptr;
            }

            int sx = (tile_x0 - pad_x0) * scale;
            int sy = (tile_y0 - pad_y0) * scale;
            int valid_w = (tile_x1 - tile_x0) * scale;
            int valid_h = (tile_y1 - tile_y0) * scale;

            ncnn::Mat valid_out = extractRoi(tile_out, sx, sy, valid_w, valid_h);
            if (valid_out.empty())
            {
                LOGE("extractRoi on output tile failed");
                return nullptr;
            }

            int dst_x = tile_x0 * scale;
            int dst_y = tile_y0 * scale;
            copyRoiToMat(valid_out, outimage, dst_x, dst_y);
        }

        completed += xtiles;
        if (on_progress_mid && progress_listener)
        {
            float progress = (float)completed / total_tiles;
            reportProgress(env, progress_listener, on_progress_mid, progress);
        }
    }

    if (on_progress_mid && progress_listener)
        reportProgress(env, progress_listener, on_progress_mid, 1.0f);

    jobject out_bitmap = nullptr;
    if (!ncnnMatToBitmap(env, outimage, out_w, out_h, &out_bitmap))
    {
        LOGE("ncnnMatToBitmap failed");
        return nullptr;
    }

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
    g_scale = 2;
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
