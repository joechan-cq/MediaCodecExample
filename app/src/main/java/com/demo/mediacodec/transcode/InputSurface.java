package com.demo.mediacodec.transcode;


import android.media.MediaCodec;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.text.TextUtils;
import android.util.Log;
import android.view.Surface;

import com.demo.mediacodec.MediaCodecUtils;

//代码来源http://androidxref.com/9.0.0_r3/xref/cts/tests/tests/media/src/android/media/cts

/**
 * Holds state associated with a Surface used for MediaCodec encoder input.
 * <p>
 * The constructor takes a Surface obtained from MediaCodec.createInputSurface(), and uses that
 * to create an EGL window surface.  Calls to eglSwapBuffers() cause a frame of data to be sent
 * to the video encoder.
 */
public class InputSurface {
    private static final String TAG = "InputSurface";

    private EGLDisplay mEGLDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext mEGLContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface mEGLSurface = EGL14.EGL_NO_SURFACE;
    private EGLConfig[] mConfigs = new EGLConfig[1];

    private Surface mSurface;
    private int mWidth;
    private int mHeight;

    /**
     * Creates an InputSurface from a Surface.
     */
    public InputSurface(Surface surface, VideoOutputConfig config) {
        if (surface == null) {
            throw new NullPointerException();
        }
        mSurface = surface;

        eglSetup(config);
    }

    /**
     * Prepares EGL.  We want a GLES 2.0 context and a surface that supports recording.
     */
    private void eglSetup(VideoOutputConfig config) {
        mEGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (mEGLDisplay == EGL14.EGL_NO_DISPLAY) {
            throw new RuntimeException("unable to get EGL14 display");
        }
        int[] version = new int[2];
        if (!EGL14.eglInitialize(mEGLDisplay, version, 0, version, 1)) {
            mEGLDisplay = null;
            throw new RuntimeException("unable to initialize EGL14");
        }

        if (!config.isHDR || config.force8Bit) {
            createSdrEGLContextAndWindow();
            config.eglColorSpace = MediaCodecUtils.EGLColorSpace.RGB888;
            Log.i("InputSurface", "使用RGBA8888");
        } else {
            try {
                if (config.isDolby) {
                    //杜比视界
                    Log.i("InputSurface", "使用RGBA1010102");
                    createRGBA1010102EGLContextAndWindow();
                    config.eglColorSpace = MediaCodecUtils.EGLColorSpace.RGBA1010102;
                } else if (config.isHDRVivid) {
                    //vivid
                    Log.i("InputSurface", "使用YUVP10");
                    createYUVP10EGLContextAndWindow();
                    config.eglColorSpace = MediaCodecUtils.EGLColorSpace.YUVP10;
                } else {
                    //不是杜比视界、不是hdr vivid。
                    Log.i("InputSurface", "不是杜比，也不是Vivid，使用RGBA1010102");
                    createRGBA1010102EGLContextAndWindow();
                    config.eglColorSpace = MediaCodecUtils.EGLColorSpace.RGBA1010102;
                }
            } catch (Exception e) {
                e.printStackTrace();
                Log.i("InputSurface", "eglSetup: 10bit位深EGL初始化失败，尝试使用RGBA8888");
                createSdrEGLContextAndWindow();
                config.eglColorSpace = MediaCodecUtils.EGLColorSpace.RGB888;
            }
        }


        mWidth = getWidth();
        mHeight = getHeight();
    }

    public void updateSize(int width, int height) {
        if (width != mWidth || height != mHeight) {
            Log.d(TAG, "re-create EGLSurface");
            releaseEGLSurface();
            createEGLSurface();
            mWidth = getWidth();
            mHeight = getHeight();
        }
    }

    private void createEGLSurface() {
        //EGLConfig[] configs = new EGLConfig[1];
        int[] surfaceAttribs = {
                EGL14.EGL_NONE
        };
        mEGLSurface = EGL14.eglCreateWindowSurface(mEGLDisplay, mConfigs[0], mSurface,
                surfaceAttribs, 0);
        checkEglError("eglCreateWindowSurface");
        if (mEGLSurface == null) {
            throw new RuntimeException("surface was null");
        }
    }

    private void releaseEGLSurface() {
        if (mEGLDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglDestroySurface(mEGLDisplay, mEGLSurface);
            mEGLSurface = EGL14.EGL_NO_SURFACE;
        }
    }

    /**
     * Discard all resources held by this class, notably the EGL context.  Also releases the
     * Surface that was passed to our constructor.
     */
    public void release() {
        if (mEGLDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglDestroySurface(mEGLDisplay, mEGLSurface);
            EGL14.eglDestroyContext(mEGLDisplay, mEGLContext);
            EGL14.eglReleaseThread();
            EGL14.eglTerminate(mEGLDisplay);
        }

        mSurface.release();

        mEGLDisplay = EGL14.EGL_NO_DISPLAY;
        mEGLContext = EGL14.EGL_NO_CONTEXT;
        mEGLSurface = EGL14.EGL_NO_SURFACE;

        mSurface = null;
    }

    /**
     * Makes our EGL context and surface current.
     */
    public void makeCurrent() {
        if (!EGL14.eglMakeCurrent(mEGLDisplay, mEGLSurface, mEGLSurface, mEGLContext)) {
            throw new RuntimeException("eglMakeCurrent failed");
        }
    }

    public void makeUnCurrent() {
        if (!EGL14.eglMakeCurrent(mEGLDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT)) {
            throw new RuntimeException("eglMakeCurrent failed");
        }
    }

    /**
     * Calls eglSwapBuffers.  Use this to "publish" the current frame.
     */
    public boolean swapBuffers() {
        return EGL14.eglSwapBuffers(mEGLDisplay, mEGLSurface);
    }

    /**
     * Returns the Surface that the MediaCodec receives buffers from.
     */
    public Surface getSurface() {
        return mSurface;
    }

    /**
     * Queries the surface's width.
     */
    public int getWidth() {
        int[] value = new int[1];
        EGL14.eglQuerySurface(mEGLDisplay, mEGLSurface, EGL14.EGL_WIDTH, value, 0);
        return value[0];
    }

    /**
     * Queries the surface's height.
     */
    public int getHeight() {
        int[] value = new int[1];
        EGL14.eglQuerySurface(mEGLDisplay, mEGLSurface, EGL14.EGL_HEIGHT, value, 0);
        return value[0];
    }

    /**
     * Sends the presentation time stamp to EGL.  Time is expressed in nanoseconds.
     */
    public void setPresentationTime(long nsecs) {
        EGLExt.eglPresentationTimeANDROID(mEGLDisplay, mEGLSurface, nsecs);
    }

    /**
     * Checks for EGL errors.
     */
    private void checkEglError(String msg) {
        int error;
        if ((error = EGL14.eglGetError()) != EGL14.EGL_SUCCESS) {
            throw new RuntimeException(msg + ": EGL error: 0x" + Integer.toHexString(error));
        }
    }

    public void configure(MediaCodec codec) {
        codec.setInputSurface(mSurface);
    }

    private void createSdrEGLContextAndWindow() {
        int[] attribList = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGLExt.EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE
        };
        int[] numConfigs = new int[1];
        if (!EGL14.eglChooseConfig(mEGLDisplay, attribList, 0, mConfigs, 0, mConfigs.length,
                numConfigs, 0)) {
            throw new RuntimeException("unable to find RGB888+recordable ES2 EGL config");
        }

        // Configure context for OpenGL ES 2.0.
        int[] attrib_list = {
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE
        };
        mEGLContext = EGL14.eglCreateContext(mEGLDisplay, mConfigs[0], EGL14.EGL_NO_CONTEXT,
                attrib_list, 0);
        checkEglError("eglCreateContext");
        if (mEGLContext == null) {
            throw new RuntimeException("null context");
        }

        // Create a window surface, and attach it to the Surface we received.
        createEGLSurface();
    }

    private void createRGBA1010102EGLContextAndWindow() {
        //TODO 需要检查是否支持
        int[] attribList = {
                EGL14.EGL_RED_SIZE, 10,
                EGL14.EGL_GREEN_SIZE, 10,
                EGL14.EGL_BLUE_SIZE, 10,
                EGL14.EGL_ALPHA_SIZE, 2,
                EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                EGL14.EGL_NONE
        };
        int[] numConfigs = new int[1];
        if (!EGL14.eglChooseConfig(mEGLDisplay, attribList, 0, mConfigs, 0, mConfigs.length,
                numConfigs, 0)) {
            throw new RuntimeException("unable to find RGB1010102 recordable ES2 EGL config");
        }

        // Configure context for OpenGL ES 2.0.
        int[] attrib_list = {
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE
        };
        mEGLContext = EGL14.eglCreateContext(mEGLDisplay, mConfigs[0], EGL14.EGL_NO_CONTEXT,
                attrib_list, 0);
        checkEglError("eglCreateContext");
        if (mEGLContext == null) {
            throw new RuntimeException("null context");
        }

        // Create a window surface, and attach it to the Surface we received.
        createEGLSurface();
    }

    private void createYUVP10EGLContextAndWindow() {
        String extensions = EGL14.eglQueryString(mEGLDisplay, EGL14.EGL_EXTENSIONS);
        if (TextUtils.isEmpty(extensions) || !extensions.contains(GLUtils.EGL_YUV_EXT_NAME)) {
            throw new RuntimeException("EGL not support YUV EXT");
        }
        int[] attribList = {
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_COLOR_BUFFER_TYPE, GLUtils.EGL_YUV_BUFFER_EXT,
                GLUtils.EGL_YUV_ORDER_EXT, GLUtils.EGL_YUV_ORDER_YUV_EXT,
                GLUtils.EGL_YUV_NUMBER_OF_PLANES_EXT, 2,
                GLUtils.EGL_YUV_SUBSAMPLE_EXT, GLUtils.EGL_YUV_SUBSAMPLE_4_2_0_EXT,
                GLUtils.EGL_YUV_DEPTH_RANGE_EXT, GLUtils.EGL_YUV_DEPTH_RANGE_LIMITED_EXT,
                GLUtils.EGL_YUV_CSC_STANDARD_EXT, GLUtils.EGL_YUV_CSC_STANDARD_2020_EXT,
                GLUtils.EGL_YUV_PLANE_BPP_EXT, GLUtils.EGL_YUV_PLANE_BPP_10_EXT,
                EGL14.EGL_NONE
        };
        int[] numConfigs = new int[1];
        if (!EGL14.eglChooseConfig(mEGLDisplay, attribList, 0, mConfigs, 0, mConfigs.length,
                numConfigs, 0)) {
            throw new RuntimeException("unable to find YUVP10 ES2 EGL config");
        }
        int[] v = new int[1];
        EGL14.eglGetConfigAttrib(mEGLDisplay, mConfigs[0], EGL14.EGL_NATIVE_VISUAL_ID, v, 0);

        int[] attrib_list = {
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,
                EGL14.EGL_NONE
        };
        mEGLContext = EGL14.eglCreateContext(mEGLDisplay, mConfigs[0], EGL14.EGL_NO_CONTEXT,
                attrib_list, 0);
        checkEglError("eglCreateContext");
        if (mEGLContext == null) {
            throw new RuntimeException("null context");
        }

        // Create a window surface, and attach it to the Surface we received.
        createEGLSurface();
    }

}