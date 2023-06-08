package com.demo.mediacodec.transcode;

/**
 * @author : chenqiao
 * @date : 2023/6/1 13:32
 */
public class GLUtils {
    //region YUV EXT
    //https://registry.khronos.org/EGL/extensions/EXT/EGL_EXT_yuv_surface.txt
    public static final String EGL_YUV_EXT_NAME = "EGL_EXT_yuv_surface";

    public static final int EGL_YUV_BUFFER_EXT = 0x3300;
    public static final int EGL_YUV_ORDER_EXT = 0x3301;
    public static final int EGL_YUV_ORDER_YUV_EXT = 0x3302;
    public static final int EGL_YUV_NUMBER_OF_PLANES_EXT = 0x3311;
    public static final int EGL_YUV_SUBSAMPLE_EXT = 0x3312;
    public static final int EGL_YUV_DEPTH_RANGE_EXT = 0x3317;
    public static final int EGL_YUV_CSC_STANDARD_EXT = 0x330A;
    public static final int EGL_YUV_PLANE_BPP_EXT = 0x331A;
    public static final int EGL_YUV_SUBSAMPLE_4_2_0_EXT = 0x3313;
    public static final int EGL_YUV_DEPTH_RANGE_LIMITED_EXT = 0x3318;
    public static final int EGL_YUV_DEPTH_RANGE_FULL_EXT = 0x3319;
    public static final int EGL_YUV_CSC_STANDARD_601_EXT = 0x330B;
    public static final int EGL_YUV_CSC_STANDARD_709_EXT = 0x330C;
    public static final int EGL_YUV_CSC_STANDARD_2020_EXT = 0x330D;
    public static final int EGL_YUV_PLANE_BPP_0_EXT = 0x331B;
    public static final int EGL_YUV_PLANE_BPP_8_EXT = 0x331C;
    public static final int EGL_YUV_PLANE_BPP_10_EXT = 0x331D;
    //endregion

    //region rgba glsl
    public static final String RGBA_VERTEX_SHADER = "" +
            "uniform mat4 uMVPMatrix;                                           \n" +
            "uniform mat4 uSTMatrix;                                            \n" +
            "attribute vec4 aPosition;                                          \n" +
            "attribute vec4 aTextureCoord;                                      \n" +
            "varying vec2 vTextureCoord;                                        \n" +
            "void main() {                                                      \n" +
            "  gl_Position = uMVPMatrix * aPosition;                            \n" +
            "  vTextureCoord = (uSTMatrix * aTextureCoord).xy;                  \n" +
            "}                                                                  \n";

    public static final String RGBA_FRAGMENT_SHADER = "" +
            "#extension GL_OES_EGL_image_external : require                     \n" +
            "precision mediump float;                                           \n" +
            "varying vec2 vTextureCoord;                                        \n" +
            "uniform samplerExternalOES sTexture;                               \n" +
            "void main() {                                                      \n" +
            "  gl_FragColor = texture2D(sTexture, vTextureCoord);               \n" +
            "}                                                                  \n";
    //endregion

    //region yuvp10 glsl

    public static final String YUV_VERTEX_SHADER = "" +
            "#version 300 es                                                       \n" +
            "precision highp float;                                                \n" +
            "uniform mat4 uMVPMatrix;                                              \n" +
            "uniform mat4 uSTMatrix;                                               \n" +
            "layout(location = 0) in vec4 aPosition;                               \n" +
            "layout(location = 1) in vec4 aTextureCoord;                           \n" +
            "                                                                      \n" +
            "out vec2 vTextureCoord;                                               \n" +
            "                                                                      \n" +
            "void main()                                                           \n" +
            "{                                                                     \n" +
            "    gl_Position = uMVPMatrix * aPosition;                             \n" +
            "    vTextureCoord = (uSTMatrix * aTextureCoord).xy;                   \n" +
            "}                                                                     \n";

//    public static final String YUV_FRAGMENT_SHADER = "" +
//            "#version 300 es                                                       \n" +
//            "#extension GL_EXT_YUV_target : require                                \n" +
//            "#extension GL_OES_EGL_image_external : require                        \n" +
//            "#extension GL_OES_EGL_image_external_essl3 : require                  \n" +
//            "precision highp float;                                                \n" +
//            "                                                                      \n" +
//            "uniform samplerExternalOES sTexture;                                  \n" +
//            "                                                                      \n" +
//            "in vec2 vTextureCoord;                                                \n" +
//            "layout (yuv) out vec4 color;                                          \n" +
//            "                                                                      \n" +
//            "void main()                                                           \n" +
//            "{                                                                     \n" +
//            "    vec3 rgbColor = texture(sTexture, vTextureCoord).rgb;             \n" +
//            "    color = vec4(rgb_2_yuv(rgbColor, itu_601_full_range), 1.0);       \n" +
//            "}                                                                     \n";

    public static final String YUV_FRAGMENT_SHADER = "" +
            "#version 300 es                                                       \n" +
            "#extension GL_EXT_YUV_target : require                                \n" +
            "#extension GL_OES_EGL_image_external : require                        \n" +
            "#extension GL_OES_EGL_image_external_essl3 : require                  \n" +
            "precision highp float;                                                \n" +
            "                                                                      \n" +
            "uniform __samplerExternal2DY2YEXT sTexture;                           \n" +
            "                                                                      \n" +
            "in vec2 vTextureCoord;                                                \n" +
            "layout (yuv) out vec4 color;                                          \n" +
            "                                                                      \n" +
            "void main()                                                           \n" +
            "{                                                                     \n" +
            "    color = texture(sTexture, vTextureCoord);                         \n" +
            "}                                                                     \n";

    //endregion

}
