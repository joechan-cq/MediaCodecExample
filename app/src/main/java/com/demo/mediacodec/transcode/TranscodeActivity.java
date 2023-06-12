package com.demo.mediacodec.transcode;

import android.app.ProgressDialog;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.TextView;
import android.widget.Toast;

import com.demo.mediacodec.BaseActivity;
import com.demo.mediacodec.R;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;

import androidx.annotation.Nullable;
import androidx.annotation.UiThread;

/**
 * 视频转码（没有音频相关）
 *
 * @author : chenqiao
 * @date : 2023/1/29 10:19 AM
 */
public class TranscodeActivity extends BaseActivity implements TranscodeRunner.OnTranscodeListener {

    private TextView mVideoInfoTv, mErrorTv;
    private TextInputEditText mDstWidthEdt, mDstHeightEdt, mDstBitrateEdt, mDstFpsEdt;
    private Button mTransCodeBtn;

    private TranscodeRunner transcodeRunner;
    private ProgressDialog mProgressDialog;

    private MaterialCheckBox mH265Cb, mKeepHdrCb, mForce8BitCb;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_transcode);

        findViewById(R.id.btn_select_video).setOnClickListener(v -> {
            openPicker();
        });

        mErrorTv = findViewById(R.id.tv_errorInfo);
        mH265Cb = findViewById(R.id.cb_h265);
        mH265Cb.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (!isChecked) {
                    mKeepHdrCb.setChecked(false);
                    mForce8BitCb.setChecked(false);
                }
            }
        });
        mKeepHdrCb = findViewById(R.id.cb_keep_hdr);
        mForce8BitCb = findViewById(R.id.cb_force_8_bit);
        mVideoInfoTv = findViewById(R.id.tv_ori_video_info);
        mDstWidthEdt = findViewById(R.id.edt_dst_width);
        mDstHeightEdt = findViewById(R.id.edt_dst_height);
        mDstBitrateEdt = findViewById(R.id.edt_dst_bitrate);
        mDstFpsEdt = findViewById(R.id.edt_dst_fps);

        TextWatcher watcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (mDstWidthEdt.getEditableText().length() > 0 && mDstHeightEdt.getEditableText().length() > 0) {
                    mTransCodeBtn.setEnabled(true);
                } else {
                    mTransCodeBtn.setEnabled(false);
                }
            }
        };
        mDstWidthEdt.addTextChangedListener(watcher);
        mDstHeightEdt.addTextChangedListener(watcher);
        mTransCodeBtn = findViewById(R.id.btn_transcode);

        mTransCodeBtn.setOnClickListener(v -> {
            mErrorTv.setText(null);
            File dstDir = getExternalCacheDir();
            TranscodeConfig config = new TranscodeConfig();
            config.dstPath = new File(dstDir, "output.mp4");
            config.h265 = mH265Cb.isChecked();
            config.outWidth = Integer.parseInt(mDstWidthEdt.getEditableText().toString());
            config.outHeight = Integer.parseInt(mDstHeightEdt.getEditableText().toString());
            config.bitrate = Integer.parseInt(mDstBitrateEdt.getEditableText().toString());
            config.fps = Integer.parseInt(mDstFpsEdt.getEditableText().toString());
            config.keepHdr = mKeepHdrCb.isChecked();
            if (config.keepHdr && !config.h265) {
                Toast.makeText(this, "仅支持H265编码的HDR效果", Toast.LENGTH_SHORT).show();
            }
            config.force8Bit = mForce8BitCb.isChecked();
            try {
                if (config.dstPath.exists()) {
                    config.dstPath.delete();
                }
            } catch (Exception ignore) {
            }
            transcodeRunner.startTranscode(config);
        });
    }

    @Override
    protected void onVideoCallback(Uri videoUri) {
        if (transcodeRunner != null) {
            transcodeRunner.release();
        }
        transcodeRunner = new TranscodeRunner(this, videoUri);
        transcodeRunner.setTransCodeListener(this);
        transcodeRunner.prepareAsync();
    }

    @Override
    public void onPrepareDone(MediaFormat videoFormat) {
        runOnUiThread(() -> {
            mVideoInfoTv.setText("视频轨道信息：" + videoFormat);
            int width = videoFormat.getInteger(MediaFormat.KEY_WIDTH);
            int height = videoFormat.getInteger(MediaFormat.KEY_HEIGHT);
            int rotation = 0;
            if (videoFormat.containsKey(MediaFormat.KEY_ROTATION)) {
                rotation = videoFormat.getInteger(MediaFormat.KEY_ROTATION);
            }
            if (rotation == 90 || rotation == 270) {
                width += height;
                height = width - height;
                width = width - height;
            }
            mDstWidthEdt.setText(String.valueOf(width));
            mDstHeightEdt.setText(String.valueOf(height));
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N && videoFormat.containsKey(MediaFormat.KEY_COLOR_STANDARD)) {
                int colorStandard = videoFormat.getInteger(MediaFormat.KEY_COLOR_STANDARD);
                if (colorStandard == MediaFormat.COLOR_STANDARD_BT2020) {
                    mH265Cb.setChecked(true);
                    mKeepHdrCb.setEnabled(true);
                    mKeepHdrCb.setChecked(true);
                } else {
                    mH265Cb.setChecked(false);
                    mKeepHdrCb.setEnabled(false);
                    mKeepHdrCb.setChecked(false);
                    mForce8BitCb.setEnabled(false);
                    mForce8BitCb.setChecked(false);
                }
            } else {
                mKeepHdrCb.setEnabled(false);
                mKeepHdrCb.setChecked(false);
                mForce8BitCb.setEnabled(false);
                mForce8BitCb.setChecked(false);
            }
        });
    }

    @Override
    public void onError(Exception e) {
        runOnUiThread(() -> {
            StringWriter sw = new StringWriter();
            PrintWriter writer = new PrintWriter(sw);
            e.printStackTrace(writer);
            mErrorTv.setText("出错：" + sw);
            try {
                sw.close();
            } catch (IOException ex) {
                ex.printStackTrace();
            }
            dismissProgressDialog();
        });
    }

    @Override
    public void onTranscodeProgress(int current) {
        runOnUiThread(() -> {
            showOrUpdateProgress(current);
        });
    }

    @Override
    public void onTranscodeDone(File output) {
        runOnUiThread(() -> {
            dismissProgressDialog();
            showOpenVideoDialog(output);
        });
    }

    private void showOpenVideoDialog(File videoFile) {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        builder.setTitle("转码完成").setMessage("文件路径：" + videoFile.getAbsolutePath()).setCancelable(true);
        builder.show();
    }

    @UiThread
    private void showOrUpdateProgress(int current) {
        if (mProgressDialog == null) {
            mProgressDialog = new ProgressDialog(this);
            mProgressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
            mProgressDialog.setCancelable(false);
            mProgressDialog.setCanceledOnTouchOutside(false);
            mProgressDialog.setMax(100);
            mProgressDialog.setTitle("正在转码");
        }
        mProgressDialog.setProgress(current);
        if (!mProgressDialog.isShowing()) {
            mProgressDialog.show();
        }
    }

    @UiThread
    private void dismissProgressDialog() {
        if (mProgressDialog != null && mProgressDialog.isShowing()) {
            mProgressDialog.dismiss();
        }
    }
}
