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
    public static String findDecoderByFormat(MediaFormat mediaFormat) {
        return findDecoderByFormat(mediaFormat, false);
    }

    @Nullable
    public static String findDecoderByFormat(MediaFormat mediaFormat, boolean onlySoftware) {
        MediaCodecList codecList = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
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
                    MediaCodecInfo.CodecCapabilities capabilities =
                            codecInfo.getCapabilitiesForType(mimeType);
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
        MediaCodecList codecList = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
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
                    MediaCodecInfo.CodecCapabilities capabilities =
                            codecInfo.getCapabilitiesForType(mimeType);
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
    public static MediaFormat createOutputFormat(@NonNull Context ctx, @NonNull Uri srcUri,
                                                 @NonNull MediaFormat inputVideoFormat,
                                                 @NonNull TranscodeConfig config,
                                                 @NonNull VideoOutputConfig outputConfig) {
        MediaFormat outputFormat;
        String inMimeType = inputVideoFormat.getString(MediaFormat.KEY_MIME);
        int inFrameRate = inputVideoFormat.getInteger(MediaFormat.KEY_FRAME_RATE);

        boolean isH265 = false;
        String mime;
        if (config.h265) {
            isH265 = true;
            if (outputConfig.outputLevel != OutputLevel.NO_HDR && MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION.equals(inMimeType) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
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
                outputFormat = MediaFormat.createVideoFormat(mime, config.outWidth,
                        config.outHeight);
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

        //这里的设置是为了让能够获取编码器，实际输出帧率并不受这个控制。而是受render绘制影响
        if (inFrameRate > 0 && inFrameRate < config.fps) {
            config.fps = inFrameRate;
            outputFormat.setInteger(MediaFormat.KEY_FRAME_RATE, inFrameRate);
        } else {
            outputFormat.setInteger(MediaFormat.KEY_FRAME_RATE, config.fps);
        }

        //O及以上可以通过KEY_MAX_FPS_TO_ENCODER来控制输出帧率
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            outputFormat.setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, config.fps);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            //O以上就有了，Q以上才开放。也有说Android 6也有用
            //https://github.com/Genymobile/scrcpy/issues/488#issuecomment-567321437
            outputFormat.setFloat("max-fps-to-encoder", config.fps);
        }
        outputFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

        if (Build.VERSION.SDK_INT > 23 && isH265) {
            //不去生成H264的HDR视频
            //设置Color相关参数，使其尽量保证HDR视频转码后仍然是HDR视频
            int colorTransfer = 0;
            int colorStandard = MediaFormat.COLOR_STANDARD_BT709;
            if (inputVideoFormat.containsKey(MediaFormat.KEY_COLOR_STANDARD)) {
                colorStandard = inputVideoFormat.getInteger(MediaFormat.KEY_COLOR_STANDARD);
            }
            outputConfig.isHDR =
                    outputConfig.outputLevel != OutputLevel.NO_HDR && colorStandard == MediaFormat.COLOR_STANDARD_BT2020;
            if (outputConfig.isHDR) {
                outputConfig.isHDRVivid = MediaCodecUtils.isHDRVivid(ctx, null, srcUri, null);
            }
            if (outputConfig.isHDR) {
                if (inputVideoFormat.containsKey(MediaFormat.KEY_COLOR_STANDARD)) {
                    outputFormat.setInteger(MediaFormat.KEY_COLOR_STANDARD, colorStandard);
                }
                if (inputVideoFormat.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) {
                    colorTransfer = inputVideoFormat.getInteger(MediaFormat.KEY_COLOR_TRANSFER);
                    outputFormat.setInteger(MediaFormat.KEY_COLOR_TRANSFER, colorTransfer);
                }
                if (inputVideoFormat.containsKey(MediaFormat.KEY_COLOR_RANGE)) {
                    outputFormat.setInteger(MediaFormat.KEY_COLOR_RANGE,
                            inputVideoFormat.getInteger(MediaFormat.KEY_COLOR_RANGE));
                }
                if (inputVideoFormat.containsKey(MediaFormat.KEY_HDR_STATIC_INFO)) {
                    outputFormat.setByteBuffer(MediaFormat.KEY_HDR_STATIC_INFO,
                            inputVideoFormat.getByteBuffer(MediaFormat.KEY_HDR_STATIC_INFO));
                }
                if (outputConfig.outputLevel != OutputLevel.NO_PROFILE) {
                    if (outputConfig.isDolby) {
                        //如果是杜比
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                            outputFormat.setInteger(MediaFormat.KEY_PROFILE,
                                    MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheSt);
                        } else {
                            outputFormat.setInteger(MediaFormat.KEY_PROFILE,
                                    MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheStn);
                        }
                        if (!outputFormat.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) {
                            outputFormat.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_HLG);
                        }
                        if (!outputFormat.containsKey(MediaFormat.KEY_COLOR_RANGE)) {
                            outputFormat.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED);
                        }

                        int level = getDolbyVisionLevel(config.fps, Math.max(config.outWidth, config.outHeight));
                        if (level > 0) {
                            outputFormat.setInteger(MediaFormat.KEY_LEVEL, level);
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
                                        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10);
                                //TODO 可能还是要降级成HEVCProfileMain10
                                break;
                            default:
                                break;
                        }
                    }
                }
            }
        }

//        if (isH265) {
//            //FIXME 开启B帧，能够一定程度提高清晰度，但不是所有设备都支持B帧，可能会因此报错。
//            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
//                outputFormat.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 1);
//            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
//                outputFormat.setInteger(MediaFormat.KEY_LATENCY, 1);
//            }
//        }

        return outputFormat;
    }

    private static int getDolbyVisionLevel(int fps, int maxSize) {
        int level = 0;
        if (maxSize <= 1920) {
            if (fps <= 24) {
                level = MediaCodecInfo.CodecProfileLevel.DolbyVisionLevelFhd24;
            } else if (fps > 24 && fps <= 30) {
                level = MediaCodecInfo.CodecProfileLevel.DolbyVisionLevelFhd30;
            } else if (fps > 30 && fps <= 60) {
                level = MediaCodecInfo.CodecProfileLevel.DolbyVisionLevelFhd60;
            } else {
                level = MediaCodecInfo.CodecProfileLevel.DolbyVisionLevelFhd60;
            }
        } else if (maxSize <= 3840) {
            if (fps <= 24) {
                level = MediaCodecInfo.CodecProfileLevel.DolbyVisionLevelUhd24;
            } else if (fps > 24 && fps <= 30) {
                level = MediaCodecInfo.CodecProfileLevel.DolbyVisionLevelUhd30;
            } else if (fps > 30 && fps <= 48) {
                level = MediaCodecInfo.CodecProfileLevel.DolbyVisionLevelUhd48;
            } else if (fps > 48 && fps <= 60) {
                level = MediaCodecInfo.CodecProfileLevel.DolbyVisionLevelUhd60;
            } else {
                level = MediaCodecInfo.CodecProfileLevel.DolbyVisionLevelUhd60;
            }
        }
        return level;
    }
}
