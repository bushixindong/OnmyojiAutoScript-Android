// SPDX-License-Identifier: AGPL-3.0-only
// Direct AImageReader and EGL buffer import follow MaaMeow's capture/preview architecture.
#include <jni.h>
#include <android/hardware_buffer.h>
#include <android/native_window_jni.h>
#include <media/NdkImageReader.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <algorithm>
#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <time.h>
#include <unistd.h>

extern "C" JNIEXPORT void JNICALL Java_com_oas_android_bridge_NativeFrames_publish(
    JNIEnv*, jclass, jobject, jobject, jint, jint, jint, jlong);

namespace {
void fail(JNIEnv* env, const char* message) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
}

int64_t elapsedMillis() {
    timespec time{};
    clock_gettime(CLOCK_BOOTTIME, &time);
    return time.tv_sec * 1000LL + time.tv_nsec / 1000000LL;
}

class Preview {
    EGLDisplay display = EGL_NO_DISPLAY;
    EGLContext context = EGL_NO_CONTEXT;
    EGLSurface surface = EGL_NO_SURFACE;
    ANativeWindow* window = nullptr;
    GLuint program = 0, texture = 0;
    PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC clientBuffer = nullptr;
    PFNEGLCREATEIMAGEKHRPROC createImage = nullptr;
    PFNEGLDESTROYIMAGEKHRPROC destroyImage = nullptr;
    PFNGLEGLIMAGETARGETTEXTURE2DOESPROC bindImage = nullptr;

    static GLuint shader(GLenum type, const char* source) {
        GLuint id = glCreateShader(type);
        glShaderSource(id, 1, &source, nullptr);
        glCompileShader(id);
        GLint compiled = GL_FALSE;
        glGetShaderiv(id, GL_COMPILE_STATUS, &compiled);
        if (compiled == GL_TRUE) return id;
        glDeleteShader(id);
        return 0;
    }

public:
    ~Preview() { close(); }

    bool open(JNIEnv* env, jobject javaSurface) {
        window = ANativeWindow_fromSurface(env, javaSurface);
        if (!window) return false;
        display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        if (display == EGL_NO_DISPLAY || !eglInitialize(display, nullptr, nullptr)) return false;
        EGLConfig config;
        EGLint count;
        EGLint configOptions[] = {EGL_SURFACE_TYPE,EGL_WINDOW_BIT,EGL_RENDERABLE_TYPE,EGL_OPENGL_ES2_BIT,
            EGL_RED_SIZE,8,EGL_GREEN_SIZE,8,EGL_BLUE_SIZE,8,EGL_ALPHA_SIZE,8,EGL_NONE};
        if (!eglChooseConfig(display, configOptions, &config, 1, &count) || !count) return false;
        EGLint contextOptions[] = {EGL_CONTEXT_CLIENT_VERSION,2,EGL_NONE};
        context = eglCreateContext(display, config, EGL_NO_CONTEXT, contextOptions);
        surface = eglCreateWindowSurface(display, config, window, nullptr);
        if (context == EGL_NO_CONTEXT || surface == EGL_NO_SURFACE || !eglMakeCurrent(display,surface,surface,context)) return false;
        clientBuffer = reinterpret_cast<PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC>(eglGetProcAddress("eglGetNativeClientBufferANDROID"));
        createImage = reinterpret_cast<PFNEGLCREATEIMAGEKHRPROC>(eglGetProcAddress("eglCreateImageKHR"));
        destroyImage = reinterpret_cast<PFNEGLDESTROYIMAGEKHRPROC>(eglGetProcAddress("eglDestroyImageKHR"));
        bindImage = reinterpret_cast<PFNGLEGLIMAGETARGETTEXTURE2DOESPROC>(eglGetProcAddress("glEGLImageTargetTexture2DOES"));
        if (!clientBuffer || !createImage || !destroyImage || !bindImage) return false;
        GLuint vertex = shader(GL_VERTEX_SHADER,
            "attribute vec2 pos;attribute vec2 tex;varying vec2 uv;void main(){gl_Position=vec4(pos,0.,1.);uv=tex;}");
        GLuint fragment = shader(GL_FRAGMENT_SHADER,
            "#extension GL_OES_EGL_image_external : require\nprecision mediump float;varying vec2 uv;uniform samplerExternalOES pixels;void main(){gl_FragColor=texture2D(pixels,uv);}");
        if (!vertex || !fragment) return false;
        program = glCreateProgram();
        glAttachShader(program,vertex);glAttachShader(program,fragment);glLinkProgram(program);
        glDeleteShader(vertex);glDeleteShader(fragment);
        GLint linked = GL_FALSE;
        glGetProgramiv(program, GL_LINK_STATUS, &linked);
        if (linked != GL_TRUE) return false;
        glGenTextures(1,&texture);
        glBindTexture(GL_TEXTURE_EXTERNAL_OES,texture);
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES,GL_TEXTURE_MIN_FILTER,GL_LINEAR);
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES,GL_TEXTURE_MAG_FILTER,GL_LINEAR);
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
        return true;
    }

    bool draw(AHardwareBuffer* pixels, int width, int height) {
        if (!eglMakeCurrent(display,surface,surface,context)) return false;
        auto nativeBuffer = clientBuffer(pixels);
        EGLint imageOptions[] = {EGL_IMAGE_PRESERVED_KHR,EGL_TRUE,EGL_NONE};
        EGLImageKHR image = createImage(display,EGL_NO_CONTEXT,EGL_NATIVE_BUFFER_ANDROID,nativeBuffer,imageOptions);
        if (image == EGL_NO_IMAGE_KHR) return false;
        EGLint outputWidth = 0, outputHeight = 0;
        eglQuerySurface(display,surface,EGL_WIDTH,&outputWidth);
        eglQuerySurface(display,surface,EGL_HEIGHT,&outputHeight);
        float scale = std::min(outputWidth/static_cast<float>(width), outputHeight/static_cast<float>(height));
        int w = static_cast<int>(width*scale), h = static_cast<int>(height*scale);
        glClearColor(0,0,0,1);glClear(GL_COLOR_BUFFER_BIT);
        glViewport((outputWidth-w)/2,(outputHeight-h)/2,w,h);
        glUseProgram(program);
        glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_EXTERNAL_OES,texture);
        bindImage(GL_TEXTURE_EXTERNAL_OES,image);
        glUniform1i(glGetUniformLocation(program,"pixels"),0);
        const GLfloat positions[] = {-1,1,-1,-1,1,1,1,-1};
        const GLfloat coords[] = {0,0,0,1,1,0,1,1};
        GLint pos = glGetAttribLocation(program,"pos"), tex = glGetAttribLocation(program,"tex");
        glEnableVertexAttribArray(pos);glEnableVertexAttribArray(tex);
        glVertexAttribPointer(pos,2,GL_FLOAT,GL_FALSE,0,positions);
        glVertexAttribPointer(tex,2,GL_FLOAT,GL_FALSE,0,coords);
        glDrawArrays(GL_TRIANGLE_STRIP,0,4);
        bool success = glGetError() == GL_NO_ERROR && eglSwapBuffers(display,surface);
        // Keep the source AImage acquired until the GPU has finished sampling it.
        glFinish();
        destroyImage(display,image);
        return success;
    }

    void blank() {
        if (eglMakeCurrent(display,surface,surface,context)) {
            glClearColor(0,0,0,1);
            glClear(GL_COLOR_BUFFER_BIT);
            eglSwapBuffers(display,surface);
        }
    }

    void close() {
        if (display != EGL_NO_DISPLAY) {
            if (context != EGL_NO_CONTEXT && surface != EGL_NO_SURFACE && eglMakeCurrent(display,surface,surface,context)) {
                glClearColor(0,0,0,1);glClear(GL_COLOR_BUFFER_BIT);eglSwapBuffers(display,surface);
                if (texture) glDeleteTextures(1,&texture);
                if (program) glDeleteProgram(program);
            }
            eglMakeCurrent(display,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT);
            if (surface != EGL_NO_SURFACE) eglDestroySurface(display,surface);
            if (context != EGL_NO_CONTEXT) eglDestroyContext(display,context);
            eglTerminate(display);
            eglReleaseThread();
        }
        if (window) ANativeWindow_release(window);
    }
};

struct Frame {
    std::shared_ptr<AImage> image;
    int64_t serial = 0, time = 0;
};

struct Capture {
    AImageReader* reader = nullptr;
    ANativeWindow* window = nullptr;
    std::mutex lock;
    std::atomic<bool> closing{false};
    std::atomic<int> callbacks{0};
    Frame latest;
    std::unique_ptr<Preview> preview;
    int width, height;

    Capture(int width, int height) : width(width), height(height) { }
    ~Capture() {
        closing = true;
        if (reader) {
            AImageReader_setImageListener(reader,nullptr);
            while (callbacks.load() != 0) usleep(1000);
            { std::lock_guard<std::mutex> guard(lock); latest.image.reset(); }
            AImageReader_delete(reader);
        }
    }

    Frame frame() {
        std::lock_guard<std::mutex> guard(lock);
        return latest;
    }

    static void available(void* opaque, AImageReader* reader) {
        auto* capture = static_cast<Capture*>(opaque);
        capture->callbacks.fetch_add(1);
        if (!capture->closing.load()) {
            AImage* image = nullptr;
            if (AImageReader_acquireLatestImage(reader,&image) == AMEDIA_OK && image) {
                Frame incoming{std::shared_ptr<AImage>(image,AImage_delete),0,elapsedMillis()};
                {
                    std::lock_guard<std::mutex> guard(capture->lock);
                    incoming.serial = capture->latest.serial + 1;
                    std::swap(capture->latest,incoming);
                }
            }
        }
        capture->callbacks.fetch_sub(1);
    }
};

Capture* from(jlong handle) { return reinterpret_cast<Capture*>(handle); }
}

extern "C" JNIEXPORT jlong JNICALL Java_com_oas_android_bridge_NativeFrames_createHardwareCapture(
    JNIEnv* env,jclass,jint width,jint height) {
    auto capture = std::make_unique<Capture>(width,height);
    if (AImageReader_newWithUsage(width,height,AIMAGE_FORMAT_RGBA_8888,
            AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN | AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE,5,&capture->reader) != AMEDIA_OK) {
        fail(env,"NDK ImageReader unavailable");return 0;
    }
    AImageReader_ImageListener listener{capture.get(),Capture::available};
    if (AImageReader_setImageListener(capture->reader,&listener) != AMEDIA_OK ||
            AImageReader_getWindow(capture->reader,&capture->window) != AMEDIA_OK) {
        fail(env,"NDK ImageReader could not start");return 0;
    }
    return reinterpret_cast<jlong>(capture.release());
}

extern "C" JNIEXPORT jobject JNICALL Java_com_oas_android_bridge_NativeFrames_hardwareCaptureSurface(
    JNIEnv* env,jclass,jlong handle) {
    auto surface = ANativeWindow_toSurface(env,from(handle)->window);
    if (!surface) fail(env,"NDK ImageReader surface unavailable");
    return surface;
}

extern "C" JNIEXPORT jlong JNICALL Java_com_oas_android_bridge_NativeFrames_hardwareFrameCount(
    JNIEnv*,jclass,jlong handle) { return from(handle)->frame().serial; }

extern "C" JNIEXPORT jboolean JNICALL Java_com_oas_android_bridge_NativeFrames_readHardwareFrame(
    JNIEnv* env,jclass type,jlong handle,jobject store,jlong time) {
    auto frame = from(handle)->frame();
    if (!frame.image || elapsedMillis()-frame.time > 5000) return false;
    AHardwareBuffer* buffer = nullptr;
    if (AImage_getHardwareBuffer(frame.image.get(),&buffer) != AMEDIA_OK || !buffer) return false;
    AHardwareBuffer_Desc info{};
    AHardwareBuffer_describe(buffer,&info);
    void* data = nullptr;
    if (AHardwareBuffer_lock(buffer,AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN,-1,nullptr,&data) != 0) return false;
    jobject bytes = env->NewDirectByteBuffer(data,static_cast<jlong>(info.stride)*info.height*4);
    if (bytes) {
        Java_com_oas_android_bridge_NativeFrames_publish(env,type,store,bytes,info.width,info.height,info.stride*4,time);
        env->DeleteLocalRef(bytes);
    }
    AHardwareBuffer_unlock(buffer,nullptr);
    return bytes != nullptr && !env->ExceptionCheck();
}

extern "C" JNIEXPORT void JNICALL Java_com_oas_android_bridge_NativeFrames_hardwarePreviewWindow(
    JNIEnv* env,jclass,jlong handle,jobject javaSurface) {
    auto& old = from(handle)->preview;
    old.reset();
    if (!javaSurface) return;
    auto preview = std::make_unique<Preview>();
    if (!preview->open(env,javaSurface)) { fail(env,"HardwareBuffer EGL import is unavailable");return; }
    old = std::move(preview);
}

extern "C" JNIEXPORT jlong JNICALL Java_com_oas_android_bridge_NativeFrames_drawHardwarePreview(
    JNIEnv* env,jclass,jlong handle,jlong previous) {
    auto* capture = from(handle);
    auto frame = capture->frame();
    if (!frame.image || frame.serial <= previous || !capture->preview) return 0;
    AHardwareBuffer* buffer = nullptr;
    if (AImage_getHardwareBuffer(frame.image.get(),&buffer) != AMEDIA_OK || !buffer ||
            !capture->preview->draw(buffer,capture->width,capture->height)) {
        fail(env,"HardwareBuffer preview failed");return 0;
    }
    return frame.serial;
}

extern "C" JNIEXPORT void JNICALL Java_com_oas_android_bridge_NativeFrames_closeHardwareCapture(
    JNIEnv*,jclass,jlong handle) { delete from(handle); }

extern "C" JNIEXPORT void JNICALL Java_com_oas_android_bridge_NativeFrames_invalidateHardwareFrame(
    JNIEnv*,jclass,jlong handle) {
    auto* capture = from(handle);
    {
        std::lock_guard<std::mutex> guard(capture->lock);
        capture->latest.image.reset();
    }
}

extern "C" JNIEXPORT void JNICALL Java_com_oas_android_bridge_NativeFrames_blankHardwarePreview(
    JNIEnv*,jclass,jlong handle) {
    auto* capture = from(handle);
    if (capture->preview) capture->preview->blank();
}
