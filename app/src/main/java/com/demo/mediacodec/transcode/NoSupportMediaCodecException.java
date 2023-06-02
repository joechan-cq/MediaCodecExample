package com.demo.mediacodec.transcode;

import com.demo.mediacodec.MediaCodecUtils;

/**
 * @author : chenqiao
 * @date : 2023/6/2 10:11
 */
public class NoSupportMediaCodecException extends Exception {

    private final MediaCodecUtils.OutputLevel outputLevel;

    public NoSupportMediaCodecException(String msg, MediaCodecUtils.OutputLevel outputLevel) {
        super(msg);
        this.outputLevel = outputLevel;
    }

    public NoSupportMediaCodecException(String message, Throwable cause,
                                        MediaCodecUtils.OutputLevel outputLevel) {
        super(message, cause);
        this.outputLevel = outputLevel;
    }

    public NoSupportMediaCodecException(Throwable cause, MediaCodecUtils.OutputLevel outputLevel) {
        super(cause);
        this.outputLevel = outputLevel;
    }

    public MediaCodecUtils.OutputLevel getOutputLevel() {
        return outputLevel;
    }
}
