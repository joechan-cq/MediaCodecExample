package com.demo.mediacodec;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Bundle;
import android.util.Range;
import android.widget.TextView;

import androidx.annotation.Nullable;

/**
 * @author : chenqiao
 * @date : 2023/1/6 1:45 PM
 */
public class EncodersInfoActivity extends BaseActivity {

    TextView encodersInfoTv;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_encoders_info);
        encodersInfoTv = findViewById(R.id.tv_encoders_info);

        MediaCodecList mediaCodecList = new MediaCodecList(MediaCodecList.ALL_CODECS);
        MediaCodecInfo[] codecInfos = mediaCodecList.getCodecInfos();
        StringBuilder s = new StringBuilder();
        for (MediaCodecInfo codecInfo : codecInfos) {
            if (codecInfo.isEncoder()) {
                s.append(codecInfo.getName()).append(":\n");
                String[] supportedTypes = codecInfo.getSupportedTypes();
                if (supportedTypes != null) {
                    for (String type : supportedTypes) {
                        MediaCodecInfo.CodecCapabilities caps = codecInfo.getCapabilitiesForType(type);
                        if (caps == null) {
                            continue;
                        }
                        MediaFormat defaultFormat = caps.getDefaultFormat();
                        String mimeType = caps.getMimeType();
                        s.append("defaultFormat: ").append(defaultFormat).append("\n");
                        MediaCodecInfo.VideoCapabilities vcaps = caps.getVideoCapabilities();
                        if (vcaps != null) {
                            Range<Integer> wRange = vcaps.getSupportedWidths();
                            s.append("widthRange: ").append(wRange.getLower()).append("-").append(wRange.getUpper()).append("\n");
                            Range<Integer> hRange = vcaps.getSupportedHeights();
                            s.append("heightRange: ").append(hRange.getLower()).append("-").append(hRange.getUpper()).append("\n");
                            Range<Integer> bitrateRange = vcaps.getBitrateRange();
                            s.append("bitrateRange: ").append(bitrateRange.getLower()).append("-").append(bitrateRange.getUpper()).append("\n");
                        }
                        if (caps.colorFormats != null && caps.colorFormats.length > 0) {
                            s.append("colorFormat:[\n");
                            s.append("\t");
                            for (int colorFormat : caps.colorFormats) {
                                s.append(colorFormat).append(" ");
                            }
                            s.append("\n]\n");
                        }
                        if (caps.profileLevels != null) {
                            s.append("profileLevels:[");
                            for (MediaCodecInfo.CodecProfileLevel profileLevel : caps.profileLevels) {
                                s.append("\n");
                                s.append("\tprofile:").append(profileLevel.profile).append(" ").append("level:").append(profileLevel.level);
                                if ("video/avc".equalsIgnoreCase(mimeType)) {
                                    if (profileLevel.profile >= MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10) {
                                        s.append("(HDR)");
                                    }
                                } else if ("video/hevc".equalsIgnoreCase(mimeType)) {
                                    if (profileLevel.profile >= MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10) {
                                        s.append("(HDR)");
                                    }
                                }
                            }
                            s.append("\n]\n");
                        }
                        MediaCodecInfo.EncoderCapabilities enCaps = caps.getEncoderCapabilities();
                        if (enCaps == null) {
                            continue;
                        }
                        boolean support = enCaps.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ);
                        s.append("BITRATE_MODE_CQ support: ").append(support).append("\n");
                        support = enCaps.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
                        s.append("BITRATE_MODE_VBR support: ").append(support).append("\n");
                        support = enCaps.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
                        s.append("BITRATE_MODE_CBR support: ").append(support).append("\n");
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                            Range<Integer> qualityRange = enCaps.getQualityRange();
                            s.append("qualityRange: ").append(qualityRange.getLower()).append("-").append(qualityRange.getUpper()).append("\n");
                        }
                        Range<Integer> complexityRange = enCaps.getComplexityRange();
                        s.append("complexityRange: ").append(complexityRange.getLower()).append("-").append(complexityRange.getUpper()).append("\n");
                    }
                    s.append("\n");
                }
            }
        }

        encodersInfoTv.setText(s);
    }
}
