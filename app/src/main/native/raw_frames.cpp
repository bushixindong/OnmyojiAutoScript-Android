// SPDX-License-Identifier: AGPL-3.0-only
// Native triple-buffer/NEON architecture inspired by MaaMeow bridge_frame_buffer.cpp.
#include <jni.h>
#include <atomic>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <grp.h>
#include <new>
#include <signal.h>
#include <string>
#include <sys/wait.h>
#include <unistd.h>
#if defined(__ARM_NEON)
#include <arm_neon.h>
#endif

namespace {
constexpr uint32_t MAGIC=0x4f415352, CAPACITY=1280*720*3;
constexpr size_t SLOT_BYTES=64+CAPACITY, STORE_BYTES=64+3*SLOT_BYTES;
struct alignas(64) Header { uint32_t magic,version,capacity,reserved; std::atomic<uint64_t> published; };
struct alignas(64) Slot { std::atomic<uint64_t> sequence; uint32_t width,height,length,reserved; uint64_t time; };
static_assert(sizeof(Header)==64 && sizeof(Slot)==64,"Shared-memory ABI alignment");
void fail(JNIEnv* env,const char* message) { env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),message); }
uint8_t* direct(JNIEnv* env,jobject memory,size_t required) {
    auto* p=static_cast<uint8_t*>(env->GetDirectBufferAddress(memory));
    if(!p || env->GetDirectBufferCapacity(memory)<static_cast<jlong>(required)) { fail(env,"Invalid native frame buffer"); return nullptr; }
    return p;
}
void rgb(const uint8_t* src,uint8_t* dst,int width,int height,int stride) {
    for(int y=0;y<height;y++) {
        const auto* s=src+static_cast<size_t>(y)*stride; auto* d=dst+static_cast<size_t>(y)*width*3; int x=0;
#if defined(__ARM_NEON)
        for(;x+16<=width;x+=16,s+=64,d+=48) {
            uint8x16x4_t rgba=vld4q_u8(s); uint8x16x3_t value={{rgba.val[0],rgba.val[1],rgba.val[2]}}; vst3q_u8(d,value);
        }
#endif
        for(;x<width;x++,s+=4,d+=3) { d[0]=s[0];d[1]=s[1];d[2]=s[2]; }
    }
}
}
extern "C" JNIEXPORT jint JNICALL Java_com_oas_android_bridge_NativeFrames_storeSize(JNIEnv*,jclass) { return STORE_BYTES; }
extern "C" JNIEXPORT void JNICALL Java_com_oas_android_bridge_NativeFrames_initialize(JNIEnv* env,jclass,jobject memory) {
    auto* p=direct(env,memory,STORE_BYTES); if(!p)return;
    memset(p,0,STORE_BYTES); auto* header=new(p) Header{}; header->magic=MAGIC;header->version=1;header->capacity=CAPACITY;
    for(int i=0;i<3;i++) new(p+64+i*SLOT_BYTES) Slot{};
}
extern "C" JNIEXPORT void JNICALL Java_com_oas_android_bridge_NativeFrames_publish(JNIEnv* env,jclass,jobject memory,jobject rgba,jint width,jint height,jint stride,jlong time) {
    if(width<=0||height<=0||static_cast<int64_t>(width)*height*3>CAPACITY||stride<width*4) { fail(env,"Invalid frame dimensions");return; }
    auto* p=direct(env,memory,STORE_BYTES);if(!p)return;
    auto* src=direct(env,rgba,static_cast<size_t>(height-1)*stride+width*4);if(!src)return;
    auto* header=reinterpret_cast<Header*>(p); if(header->magic!=MAGIC) {fail(env,"Invalid frame store");return;}
    uint64_t serial=(header->published.load(std::memory_order_relaxed)>>2)+1; int index=serial%3;
    auto* slot=reinterpret_cast<Slot*>(p+64+index*SLOT_BYTES);
    slot->sequence.store(serial*2+1,std::memory_order_release);
    slot->width=width;slot->height=height;slot->length=width*height*3;slot->time=time;
    rgb(src,reinterpret_cast<uint8_t*>(slot)+64,width,height,stride);
    slot->sequence.store(serial*2,std::memory_order_release);
    header->published.store((serial<<2)|index,std::memory_order_release);
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_com_oas_android_bridge_NativeFrames_read(JNIEnv* env,jclass,jobject memory,jlong now) {
    auto* p=direct(env,memory,STORE_BYTES);if(!p)return nullptr; auto* header=reinterpret_cast<Header*>(p);
    if(header->magic!=MAGIC||header->version!=1) {fail(env,"Unknown frame store protocol");return nullptr;}
    for(int attempt=0;attempt<8;attempt++) {
        uint64_t published=header->published.load(std::memory_order_acquire);if(!published) {fail(env,"No raw frame available");return nullptr;}
        auto* slot=reinterpret_cast<Slot*>(p+64+(published&3)*SLOT_BYTES);
        uint64_t sequence=slot->sequence.load(std::memory_order_acquire);if(sequence&1)continue;
        uint32_t width=slot->width,height=slot->height,length=slot->length;uint64_t time=slot->time;
        if(width==0||height==0||static_cast<uint64_t>(width)*height*3!=length||length>CAPACITY)continue;
        if(now<static_cast<jlong>(time)||now-static_cast<jlong>(time)>5000) {fail(env,"Raw frame is stale");return nullptr;}
        jbyteArray result=env->NewByteArray(16+length);if(!result)return nullptr;
        auto* bytes=env->GetByteArrayElements(result,nullptr);if(!bytes) {env->DeleteLocalRef(result);return nullptr;}
        uint32_t wire[]={MAGIC,width,height,length};memcpy(bytes,wire,16);memcpy(bytes+16,reinterpret_cast<uint8_t*>(slot)+64,length);
        std::atomic_thread_fence(std::memory_order_acquire);
        bool consistent=slot->sequence.load(std::memory_order_acquire)==sequence && sequence==(published>>2)*2;
        env->ReleaseByteArrayElements(result,bytes,consistent?0:JNI_ABORT);
        if(consistent)return result; env->DeleteLocalRef(result);
    }
    fail(env,"Frame changed while copying, retry capture");return nullptr;
}
extern "C" JNIEXPORT jintArray JNICALL Java_com_oas_android_bridge_NativeFrames_spawnShellBridge(JNIEnv* env,jclass,jstring apkPath,jstring token) {
    if(!apkPath||!token) {fail(env,"Missing shell bridge parameters");return nullptr;}
    const char* apkRaw=env->GetStringUTFChars(apkPath,nullptr);
    const char* tokenRaw=env->GetStringUTFChars(token,nullptr);
    std::string apk(apkRaw?apkRaw:""), tok(tokenRaw?tokenRaw:"");
    if(apkRaw)env->ReleaseStringUTFChars(apkPath,apkRaw);
    if(tokenRaw)env->ReleaseStringUTFChars(token,tokenRaw);
    if(apk.empty()||tok.empty()) {fail(env,"Empty shell bridge parameters");return nullptr;}
    int fds[2];
    if(pipe(fds)!=0) {fail(env,"Failed to create shell lifeline pipe");return nullptr;}
    fcntl(fds[1],F_SETFD,FD_CLOEXEC);
    pid_t pid=fork();
    if(pid<0) {close(fds[0]);close(fds[1]);fail(env,"Failed to fork shell bridge");return nullptr;}
    if(pid==0) {
        dup2(fds[0],STDIN_FILENO);
        if(fds[0]!=STDIN_FILENO)close(fds[0]);
        close(fds[1]);
        if(getuid()!=2000) {
            static const gid_t kGids[]={2000,1002,1004,1005,1007,1011,1013,1015,1024,1028,1065,1078,1079,1096,3001,3002,3003,3006,3007,3009,3010,3011,3012,3013};
            setgroups(sizeof(kGids)/sizeof(kGids[0]),kGids);
            if(setresgid(2000,2000,2000)!=0)_exit(11);
            if(setresuid(2000,2000,2000)!=0)_exit(12);
        }
        setenv("CLASSPATH",apk.c_str(),1);
        execl("/system/bin/app_process","/system/bin/app_process","/system/bin","--nice-name=com.oas.android:oas_shell","com.oas.android.bridge.ShellBridgeMain",tok.c_str(),static_cast<char*>(nullptr));
        _exit(127);
    }
    close(fds[0]);
    jint values[2]={static_cast<jint>(pid),static_cast<jint>(fds[1])};
    jintArray result=env->NewIntArray(2);
    if(result)env->SetIntArrayRegion(result,0,2,values);
    return result;
}
extern "C" JNIEXPORT void JNICALL Java_com_oas_android_bridge_NativeFrames_stopShellBridge(JNIEnv*,jclass,jint pid,jint writeFd) {
    if(writeFd>=0)close(writeFd);
    if(pid<=0)return;
    int status=0;
    for(int i=0;i<15;i++) {
        if(waitpid(pid,&status,WNOHANG)==pid)return;
        usleep(20000);
    }
    kill(pid,SIGTERM);
    waitpid(pid,&status,WNOHANG);
}
