// SPDX-License-Identifier: AGPL-3.0-only
package com.oas.android.bridge;

import android.graphics.SurfaceTexture;
import android.opengl.*;
import android.os.Handler;
import android.os.SystemClock;
import android.view.Surface;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/** GPU fan-out: display -> OES texture -> live window and throttled recognition reader.
 * All methods, including destruction, run on the frame thread. No encoded video or PNG preview.
 * Architecture reference: MaaMeow bridge_preview.cpp (see THIRD_PARTY.md).
 */
final class DisplayRenderer extends FramePipeline {
    private final Handler handler;
    private EGLDisplay egl = EGL14.EGL_NO_DISPLAY;
    private EGLContext context = EGL14.EGL_NO_CONTEXT;
    private EGLSurface idle = EGL14.EGL_NO_SURFACE, capture = EGL14.EGL_NO_SURFACE, preview = EGL14.EGL_NO_SURFACE;
    private EGLConfig config;
    private SurfaceTexture texture;
    private Surface input, previewWindow;
    private int program, textureId, width, height;
    private boolean closed;
    private long lastCapture, lastPreview, lastComposition;
    private boolean captureRequested;
    private final Runnable capturePending = this::renderCapture;
    volatile long compositionFrames;
    private final float[] transform = new float[16];
    private final FloatBuffer vertices = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder())
        .asFloatBuffer().put(new float[]{-1,-1,0,0, 1,-1,1,0, -1,1,0,1, 1,1,1,1});

    DisplayRenderer(Handler handler, Surface captureWindow, int width, int height) {
        this.handler = handler;
        try {
            egl = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] version = new int[2];
            check(EGL14.eglInitialize(egl, version, 0, version, 1), "eglInitialize");
            EGLConfig[] configs = new EGLConfig[1]; int[] count = new int[1];
            check(EGL14.eglChooseConfig(egl, new int[]{EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT | EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_ALPHA_SIZE,8,EGL14.EGL_NONE},
                0, configs, 0, 1, count, 0) && count[0] > 0, "eglChooseConfig");
            config = configs[0];
            context = EGL14.eglCreateContext(egl, config, EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE},0);
            check(context != EGL14.EGL_NO_CONTEXT, "eglCreateContext");
            idle = EGL14.eglCreatePbufferSurface(egl,config,new int[]{EGL14.EGL_WIDTH,1,EGL14.EGL_HEIGHT,1,EGL14.EGL_NONE},0);
            current(idle);
            int vertex = shader(GLES20.GL_VERTEX_SHADER,
                "attribute vec2 p; attribute vec2 t; uniform mat4 m; varying vec2 uv; void main(){gl_Position=vec4(p,0.,1.);uv=(m*vec4(t,0.,1.)).xy;}");
            int fragment = shader(GLES20.GL_FRAGMENT_SHADER,
                "#extension GL_OES_EGL_image_external : require\nprecision mediump float; uniform samplerExternalOES image; varying vec2 uv; void main(){gl_FragColor=texture2D(image,uv);}");
            program = GLES20.glCreateProgram(); GLES20.glAttachShader(program,vertex); GLES20.glAttachShader(program,fragment);
            GLES20.glLinkProgram(program); GLES20.glDeleteShader(vertex); GLES20.glDeleteShader(fragment);
            int[] result = new int[1]; GLES20.glGetProgramiv(program,GLES20.GL_LINK_STATUS,result,0);
            if(result[0]==0) throw new IllegalStateException(GLES20.glGetProgramInfoLog(program));
            int[] ids = new int[1]; GLES20.glGenTextures(1,ids,0); textureId=ids[0];
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,textureId);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);
            texture = new SurfaceTexture(textureId); texture.setDefaultBufferSize(width,height);
            input = new Surface(texture);
            setCapture(captureWindow,width,height);
            texture.setOnFrameAvailableListener(ignored -> render(),handler);
        } catch(RuntimeException failure) { close(); throw failure; }
    }

    Surface input() { return input; }
    long compositionCount() { return compositionFrames; }

    @Override void invalidate() {
        lastComposition = 0;
        if (!attached) return;
        try {
            current(preview);
            GLES20.glClearColor(0,0,0,1);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            EGL14.eglSwapBuffers(egl,preview);
        } finally {
            previewAt = 0;
            current(idle);
        }
    }

    void setCapture(Surface window, int width, int height) {
        current(idle);
        EGLSurface replacement = window(window);
        if(capture != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(egl,capture);
        capture = replacement; this.width=width; this.height=height;
        texture.setDefaultBufferSize(width,height);
        lastCapture = lastComposition = 0;
        captureRequested = false;
        handler.removeCallbacks(capturePending);
    }

    /** Called on the render thread. An idle live preview never causes an RGB readback. */
    void requestCapture() {
        captureRequested = true;
        renderCapture();
    }

    private void renderCapture() {
        handler.removeCallbacks(capturePending);
        if (closed || !captureRequested || lastComposition == 0) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastComposition > 5000) return;
        long wait = lastCapture + captureIntervalMs - now;
        if (wait > 0) {
            handler.postDelayed(capturePending, wait);
            return;
        }
        captureRequested = false;
        try {
            draw(capture, width, height, false);
            lastCapture = now;
        } catch (RuntimeException failure) {
            android.util.Log.w("OAS", "On-demand capture failed", failure);
        } finally {
            current(idle);
        }
    }

    /** Takes ownership of the Binder-unparceled Surface reference. */
    void setPreview(Surface window) {
        current(idle);
        if(preview != EGL14.EGL_NO_SURFACE) {
            try { current(preview); GLES20.glClearColor(0,0,0,1); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT); EGL14.eglSwapBuffers(egl,preview); }
            catch(RuntimeException ignored) { }
            current(idle); EGL14.eglDestroySurface(egl,preview); preview=EGL14.EGL_NO_SURFACE;
        }
        if(previewWindow != null) previewWindow.release();
        previewWindow=null; attached=false; previewAt=0; error="";
        if(window != null) {
            try { preview=window(window); previewWindow=window; attached=true; }
            catch(RuntimeException failure) { window.release(); throw failure; }
        }
    }

    private void render() {
        if(closed) return;
        try {
            current(idle); texture.updateTexImage(); texture.getTransformMatrix(transform);
            compositionFrames++;
            long now=SystemClock.elapsedRealtime();
            lastComposition = now;
            if (captureRequested && now - lastCapture >= captureIntervalMs) renderCapture();
            if(attached && now-lastPreview>=previewIntervalMs) {
                try {
                    int[] w=new int[1],h=new int[1];
                    EGL14.eglQuerySurface(egl,preview,EGL14.EGL_WIDTH,w,0);
                    EGL14.eglQuerySurface(egl,preview,EGL14.EGL_HEIGHT,h,0);
                    draw(preview,w[0],h[0],true);
                    lastPreview=now; previewAt=now; previewFrames++;
                } catch(RuntimeException failure) {
                    String message=failure.getMessage(); setPreview(null); error=message;
                }
            }
            current(idle);
        } catch(RuntimeException failure) {
            error=failure.getMessage(); android.util.Log.e("OAS","GPU display render failed",failure);
        }
    }

    private void draw(EGLSurface target,int targetWidth,int targetHeight,boolean fit) {
        current(target);
        GLES20.glViewport(0,0,targetWidth,targetHeight);
        GLES20.glClearColor(0,0,0,1); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        if(fit) {
            float scale=Math.min(targetWidth/(float)width,targetHeight/(float)height);
            int w=Math.round(width*scale),h=Math.round(height*scale);
            GLES20.glViewport((targetWidth-w)/2,(targetHeight-h)/2,w,h);
        }
        GLES20.glUseProgram(program); GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,textureId);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program,"image"),0);
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program,"m"),1,false,transform,0);
        int p=GLES20.glGetAttribLocation(program,"p"),t=GLES20.glGetAttribLocation(program,"t");
        vertices.position(0); GLES20.glVertexAttribPointer(p,2,GLES20.GL_FLOAT,false,16,vertices); GLES20.glEnableVertexAttribArray(p);
        vertices.position(2); GLES20.glVertexAttribPointer(t,2,GLES20.GL_FLOAT,false,16,vertices); GLES20.glEnableVertexAttribArray(t);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);
        check(EGL14.eglSwapBuffers(egl,target),"eglSwapBuffers");
    }

    private EGLSurface window(Surface surface) {
        if(!surface.isValid()) throw new IllegalStateException("预览窗口已失效");
        EGLSurface value=EGL14.eglCreateWindowSurface(egl,config,surface,new int[]{EGL14.EGL_NONE},0);
        check(value!=EGL14.EGL_NO_SURFACE,"eglCreateWindowSurface"); return value;
    }
    private void current(EGLSurface target) { check(EGL14.eglMakeCurrent(egl,target,target,context),"eglMakeCurrent"); }
    private static void check(boolean success,String operation) {
        if(!success) throw new IllegalStateException(operation+" failed: 0x"+Integer.toHexString(EGL14.eglGetError()));
    }
    private static int shader(int type,String source) {
        int shader=GLES20.glCreateShader(type); GLES20.glShaderSource(shader,source); GLES20.glCompileShader(shader);
        int[] result=new int[1]; GLES20.glGetShaderiv(shader,GLES20.GL_COMPILE_STATUS,result,0);
        if(result[0]==0) { String log=GLES20.glGetShaderInfoLog(shader); GLES20.glDeleteShader(shader); throw new IllegalStateException(log); }
        return shader;
    }
    @Override public void close() {
        closed=true; attached=false;
        handler.removeCallbacks(capturePending);
        if(texture != null) texture.setOnFrameAvailableListener(null);
        if(egl!=EGL14.EGL_NO_DISPLAY && context!=EGL14.EGL_NO_CONTEXT && idle!=EGL14.EGL_NO_SURFACE) {
            current(idle);
            if(preview!=EGL14.EGL_NO_SURFACE) { setPreview(null); }
            if(capture!=EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(egl,capture);
            if(program!=0) GLES20.glDeleteProgram(program);
            if(textureId!=0) GLES20.glDeleteTextures(1,new int[]{textureId},0);
        }
        if(input!=null) input.release(); if(texture!=null) texture.release();
        if(egl!=EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(egl,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT);
            if(idle!=EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(egl,idle);
            if(context!=EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(egl,context);
            EGL14.eglTerminate(egl); EGL14.eglReleaseThread();
        }
    }
}
