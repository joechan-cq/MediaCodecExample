package com.demo.mediacodec;

import android.content.Context;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;

import com.demo.mediacodec.transcode.TranscodeConfig;
import com.demo.mediacodec.transcode.VideoOutputConfig;

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
    public static String findEncoderByFormat(MediaFormat mediaFormat) {
        return findEncoderByFormat(mediaFormat, false);
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

    /**
     * 判断视频是否是HDRVivid视频
     */
    public static boolean isHDRVivid(Context context, String originalFile, Uri originalFileUri,
                                     String input) {
        //API 24以下不支持HDR判定
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return false;
        }

        MediaMetadataRetriever retriever = null;
        try {
            retriever = new MediaMetadataRetriever();
            if (originalFile != null) {
                retriever.setDataSource(originalFile);
            } else if (originalFileUri != null) {
                retriever.setDataSource(context, originalFileUri);
            } else {
                retriever.setDataSource(input);
            }
            String s = retriever.extractMetadata(1001);
            if ("CUVA HDR Video".equals(s)) {
                return true;
            }
        } catch (Exception ignore) {
        } finally {
            if (retriever != null) {
                try {
                    retriever.close();
                } catch (Exception ignore) {
                }
            }
        }
        return false;
    }

    public enum OutputLevel {
        DEFAULT,
        NO_PROFILE,
        NO_HDR,
    }

    public enum EGLColorSpace {
        RGB888,
        RGBA1010102,
        YUVP10,
    }

    @NonNull
    public static MediaFormat createOutputFormat(@NonNull Context ctx, @NonNull Uri srcUri, @NonNull MediaFormat mOriVideoFormat, @NonNull TranscodeConfig config, @NonNull VideoOutputConfig outputConfig) {
        MediaFormat outputFormat;
        String inMimeType = mOriVideoFormat.getString(MediaFormat.KEY_MIME);
        boolean isH265 = false;
        String mime;
        if (config.h265) {
            isH265 = true;
            if (MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION.equals(inMimeType) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                //如果是杜比视界，那么需要检查能否使用杜比视界的mimeType
                mime = MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION;
                outputConfig.isDolby = true;
            } else {
                mime = MediaFormat.MIMETYPE_VIDEO_HEVC;
            }
        } else {
            mime = MediaFormat.MIMETYPE_VIDEO_AVC;
        }
        outputFormat = MediaFormat.createVideoFormat(mime, config.outWidth, config.outHeight);
        if (outputConfig.isDolby) {
            String codecName = MediaCodecUtils.findEncoderByFormat(outputFormat, false);
            if (codecName == null) {
                //说明没有杜比视界的编码器，降级到Hevc去
                mime = MediaFormat.MIMETYPE_VIDEO_HEVC;
                outputFormat = MediaFormat.createVideoFormat(mime, config.outWidth, config.outHeight);
                outputConfig.isDolby = false;
            }
        }
        outputFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        outputFormat.setInteger(MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);

        if (config.bitrate > 0) {
            outputFormat.setInteger(MediaFormat.KEY_BIT_RATE, config.bitrate);
        } else {
            outputFormat.setInteger(MediaFormat.KEY_BIT_RATE, 3 * 1024 * 1024);
        }
        if (config.fps > 0) {
            outputFormat.setInteger(MediaFormat.KEY_FRAME_RATE, config.fps);
        } else {
            outputFormat.setInteger(MediaFormat.KEY_FRAME_RATE, 25);
        }
        outputFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        if (Build.VERSION.SDK_INT > 23 && isH265) {
            //不去生成H264的HDR视频
            //设置Color相关参数，使其尽量保证HDR视频转码后仍然是HDR视频
            int colorTransfer = 0;
            int colorStandard = mOriVideoFormat.getInteger(MediaFormat.KEY_COLOR_STANDARD);
            outputConfig.isHDR = colorStandard == MediaFormat.COLOR_STANDARD_BT2020;
            if (outputConfig.isHDR) {
                outputConfig.isHDRVivid = MediaCodecUtils.isHDRVivid(ctx, null, srcUri, null);
            }
            if (mOriVideoFormat.containsKey(MediaFormat.KEY_COLOR_STANDARD)) {
                outputFormat.setInteger(MediaFormat.KEY_COLOR_STANDARD, colorStandard);
            }
            if (mOriVideoFormat.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) {
                colorTransfer = mOriVideoFormat.getInteger(MediaFormat.KEY_COLOR_TRANSFER);
                outputFormat.setInteger(MediaFormat.KEY_COLOR_TRANSFER, colorTransfer);
            }
            if (mOriVideoFormat.containsKey(MediaFormat.KEY_COLOR_RANGE)) {
                outputFormat.setInteger(MediaFormat.KEY_COLOR_RANGE,
                        mOriVideoFormat.getInteger(MediaFormat.KEY_COLOR_RANGE));
            }
            if (mOriVideoFormat.containsKey(MediaFormat.KEY_HDR_STATIC_INFO)) {
                outputFormat.setByteBuffer(MediaFormat.KEY_HDR_STATIC_INFO,
                        mOriVideoFormat.getByteBuffer(MediaFormat.KEY_HDR_STATIC_INFO));
            }
            if (outputConfig.isDolby) {
                //如果是杜比
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    outputFormat.setInteger(MediaFormat.KEY_PROFILE,
                            MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheSt);
                } else {
                    outputFormat.setInteger(MediaFormat.KEY_PROFILE,
                            MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheStn);
                }
            } else {
                outputFormat.setFeatureEnabled("hdr-editing", true);
                switch (colorTransfer) {
                    case MediaFormat.COLOR_TRANSFER_HLG:
                        //HLG（HGL10）
                        outputFormat.setInteger(MediaFormat.KEY_PROFILE,
                                MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10);
                        break;
                    case MediaFormat.COLOR_TRANSFER_ST2084:
                        //PQ（HDR10和HDR10+）
                        //TODO 怎么区分HDR10和HDR10+ HEVCProfileMain10HDR10Plus
                        outputFormat.setInteger(MediaFormat.KEY_PROFILE,
                                MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10);
                        //TODO 可能还是要降级成HEVCProfileMain10
                        break;
                    default:
                        break;
                }
            }
        }
        return outputFormat;
    }
}
