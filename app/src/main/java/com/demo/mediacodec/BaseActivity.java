package com.demo.mediacodec;

import android.content.Intent;
import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

/**
 * @author : chenqiao
 * @date : 2022/12/27 4:01 PM
 */
public class BaseActivity extends AppCompatActivity {
    public static final int REQUEST_VIDEO_PICKER = 0xaa;

    public void openPicker() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("video/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, REQUEST_VIDEO_PICKER);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (REQUEST_VIDEO_PICKER == requestCode && resultCode == RESULT_OK && data != null) {
            Uri videoUri = data.getData();
            onVideoCallback(videoUri);
        }
    }

    protected void onVideoCallback(Uri videoUri) {

    }
}
