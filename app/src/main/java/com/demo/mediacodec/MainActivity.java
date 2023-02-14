package com.demo.mediacodec;

import android.Manifest;
import android.content.Intent;
import android.os.Bundle;

import com.demo.mediacodec.decode.DecodePlayActivity;
import com.demo.mediacodec.transcode.TranscodeActivity;

import androidx.annotation.Nullable;

/**
 * @author : chenqiao
 * @date : 2022/12/27 3:41 PM
 */
public class MainActivity extends BaseActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        findViewById(R.id.btn_list_all_decoders).setOnClickListener(v -> {
            startActivity(new Intent(MainActivity.this, DecodersInfoActivity.class));
        });
        findViewById(R.id.btn_list_all_encoders).setOnClickListener(v -> {
            startActivity(new Intent(MainActivity.this, EncodersInfoActivity.class));
        });

        findViewById(R.id.btn_decode_play).setOnClickListener(v -> {
            //解码视频播放
            startActivity(new Intent(MainActivity.this, DecodePlayActivity.class));
        });

        findViewById(R.id.btn_transcode).setOnClickListener(v -> {
            startActivity(new Intent(MainActivity.this, TranscodeActivity.class));
        });

        requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 0xbb);
    }
}
