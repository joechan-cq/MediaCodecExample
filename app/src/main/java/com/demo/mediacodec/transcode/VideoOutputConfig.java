package com.demo.mediacodec.transcode;

import com.demo.mediacodec.MediaCodecUtils;

/**
 * @author : chenqiao
 * @date : 2023/6/1 10:44
 */
public class VideoOutputConfig {

    public MediaCodecUtils.OutputLevel outputLevel;

    public boolean isHDR;

    public boolean isHDRVivid;

    public boolean isDolby;

    public boolean force8Bit;

    public MediaCodecUtils.EGLColorSpace eglColorSpace;

    public VideoOutputConfig(MediaCodecUtils.OutputLevel outputLevel) {
        this.outputLevel = outputLevel;
    }
}
