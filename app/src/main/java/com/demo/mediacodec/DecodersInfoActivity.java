package com.demo.mediacodec;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Bundle;
import android.widget.TextView;

import androidx.annotation.Nullable;

/**
 * @author : chenqiao
 * @date : 2023/1/6 1:45 PM
 */
public class DecodersInfoActivity extends BaseActivity {

    TextView decodersInfoTv;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_decoders_info);
        decodersInfoTv = findViewById(R.id.tv_decoders_info);
        MediaCodecList mediaCodecList = new MediaCodecList(MediaCodecList.ALL_CODECS);
        MediaCodecInfo[] codecInfos = mediaCodecList.getCodecInfos();
        StringBuilder s = new StringBuilder();
        for (MediaCodecInfo codecInfo : codecInfos) {
            if (!codecInfo.isEncoder()) {
                s.append(codecInfo.getName()).append("：\n");
                String[] supportedTypes = codecInfo.getSupportedTypes();
                for (String type : supportedTypes) {
                    try {
                        MediaCodecInfo.CodecCapabilities cap =
                                codecInfo.getCapabilitiesForType(type);
                        s.append("\t").append(type).append("：");
                        MediaFormat format = cap.getDefaultFormat();
                        s.append(format).append("\n");

                        if (cap.colorFormats != null && cap.colorFormats.length > 0) {
                            s.append("colorFormat:[\n");
                            for (int colorFormat : cap.colorFormats) {
                                s.append(colorFormat).append(" ");
                            }
                            s.append("\n]\n");
                        }

                        MediaCodecInfo.VideoCapabilities videoCap =
                                cap.getVideoCapabilities();
                        if (videoCap != null) {
                            s.append("\twidthRange:").append(videoCap.getSupportedWidths()).append(" heightRange:").append(videoCap.getSupportedHeights()).append("\n");
                        } else {
                            s.append("\n");
                        }
                    } catch (Exception ignore) {
                    }
                }
                s.append("\n");
            }
        }
        decodersInfoTv.setText(s);
    }
}
