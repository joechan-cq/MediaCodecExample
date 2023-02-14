package com.demo.mediacodec;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;

import java.util.Locale;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * @author : chenqiao
 * @date : 2022/12/30 11:41 AM
 */
public class MediaCodecUtils {

    /**
     * 判断是否是软件Codec
     *
     * @param codecName 编码器名称
     * @return 是否是软件Codec
     */
    public static boolean isSoftwareCodec(@NonNull String codecName) {
        codecName = codecName.toLowerCase(Locale.ROOT);
        return codecName.startsWith("omx.google.") || codecName.startsWith("c2.android.")
                || (!codecName.startsWith("omx.") && !codecName.startsWith("c2."));
    }

    @Nullable
    public static String findDecoderByFormat(MediaFormat mediaFormat, boolean onlySoftware) {
        MediaCodecList codecList = new MediaCodecList(MediaCodecList.ALL_CODECS);
        if (!onlySoftware) {
            return codecList.findDecoderForFormat(mediaFormat);
        } else {
            MediaCodecInfo[] codecInfos = codecList.getCodecInfos();
            for (MediaCodecInfo codecInfo : codecInfos) {
                if (codecInfo.isEncoder()) {
                    continue;
                }
                if (onlySoftware && !isSoftwareCodec(codecInfo.getName())) {
                    continue;
                }
                String mimeType = mediaFormat.getString(MediaFormat.KEY_MIME);
                try {
                    MediaCodecInfo.CodecCapabilities capabilities = codecInfo.getCapabilitiesForType(mimeType);
                    if (capabilities.isFormatSupported(mediaFormat)) {
                        return codecInfo.getName();
                    }
                } catch (IllegalArgumentException ignore) {
                }
            }
            return null;
        }
    }

    @Nullable
    public static String findEncoderByFormat(MediaFormat mediaFormat, boolean onlySoftware) {
        MediaCodecList codecList = new MediaCodecList(MediaCodecList.ALL_CODECS);
        if (!onlySoftware) {
            return codecList.findEncoderForFormat(mediaFormat);
        } else {
            MediaCodecInfo[] codecInfos = codecList.getCodecInfos();
            for (MediaCodecInfo codecInfo : codecInfos) {
                if (!codecInfo.isEncoder()) {
                    continue;
                }
                if (onlySoftware && !isSoftwareCodec(codecInfo.getName())) {
                    continue;
                }
                String mimeType = mediaFormat.getString(MediaFormat.KEY_MIME);
                try {
                    MediaCodecInfo.CodecCapabilities capabilities = codecInfo.getCapabilitiesForType(mimeType);
                    if (capabilities.isFormatSupported(mediaFormat)) {
                        return codecInfo.getName();
                    }
                } catch (IllegalArgumentException ignore) {
                }
            }
            return null;
        }
    }
}
