package com.demo.mediacodec.decode;

import android.content.ContentResolver;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.SurfaceView;
import android.view.View;
import android.widget.TextView;

import com.demo.mediacodec.AspectRatioFrameLayout;
import com.demo.mediacodec.BaseActivity;
import com.demo.mediacodec.MediaCodecUtils;
import com.demo.mediacodec.R;

import java.io.IOException;
import java.nio.ByteBuffer;

import androidx.annotation.Nullable;

/**
 * 解码渲染到Surface。
 * <p>
 * 流程：
 * MediaExtractor挑选视频轨道 -> 准备合适的解码器 -> 解码并渲染到Surface上。
 * 只有具有对某个视频完全支持的解码器才能进行播放。
 * 如果在非HDR设备上播放HDR视频，会获取不到解码器。
 *
 * @author : chenqiao
 * @date : 2022/12/27 3:58 PM
 */
public class DecodePlayActivity extends BaseActivity {

    private SurfaceView mSurfaceView;
    private AspectRatioFrameLayout mContainer;
    private TextView mDebugTv;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_decode_play);
        findViewById(R.id.btn_select_video).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                openPicker();
            }
        });
        mSurfaceView = findViewById(R.id.surface);
        mContainer = findViewById(R.id.video_container);
        mDebugTv = findViewById(R.id.tv_debug_info);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mMediaExtractor != null) {
            mMediaExtractor.release();
        }
        if (pf != null) {
            try {
                pf.close();
            } catch (IOException ignore) {
            }
        }
        if (mMediaCodec != null) {
            mMediaCodec.release();
        }

    }

    @Override
    protected void onVideoCallback(Uri videoUri) {
        decodeAndPlay(videoUri);
    }

    private void decodeAndPlay(Uri videoUri) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                StringBuilder log = new StringBuilder();
                selectVideoTrack(videoUri, log);
                prepareDecoder(log);
            }
        }).start();
    }

    /**
     * 轨道选择器
     */
    private MediaExtractor mMediaExtractor;

    /**
     * 视频格式
     */
    private MediaFormat mVideoFormat;

    /**
     * 解码器
     */
    private MediaCodec mMediaCodec;

    private ParcelFileDescriptor pf;

    /**
     * 挑选视频轨道
     */
    private void selectVideoTrack(Uri videoUri, StringBuilder log) {
        if (mMediaExtractor != null) {
            mMediaExtractor.release();
        }
        mMediaExtractor = new MediaExtractor();
        if (pf != null) {
            try {
                pf.close();
            } catch (IOException ignore) {
            }
        }
        try {
            ContentResolver contentResolver = getContentResolver();
            pf = contentResolver.openFileDescriptor(videoUri, "r");
            mMediaExtractor.setDataSource(pf.getFileDescriptor());
            int trackCount = mMediaExtractor.getTrackCount();
            for (int i = 0; i < trackCount; i++) {
                MediaFormat format = mMediaExtractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (!TextUtils.isEmpty(mime) && mime.startsWith("video")) {
                    //找到视频轨道
                    mVideoFormat = format;
                    //选中该视频轨道，后面读取轨道数据，就是读取的该轨道的
                    mMediaExtractor.selectTrack(i);
                    log.append("找到了视频轨道：").append(mVideoFormat).append("\n");
                    setDebugLog(log.toString());
                    return;
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        log.append("没有找到了视频轨道!").append("\n");
        setDebugLog(log.toString());
    }

    /**
     * 准备解码器
     */
    private void prepareDecoder(StringBuilder log) {
        if (mMediaCodec != null) {
            mMediaCodec.release();
            mMediaCodec = null;
        }
        if (mVideoFormat == null) {
            return;
        }
        boolean maybeSwitchWH = false;

        String mime = mVideoFormat.getString(MediaFormat.KEY_MIME);
        int width = mVideoFormat.getInteger(MediaFormat.KEY_WIDTH);
        int height = mVideoFormat.getInteger(MediaFormat.KEY_HEIGHT);
        int rotation;
        if (mVideoFormat.containsKey(MediaFormat.KEY_ROTATION)) {
            rotation = mVideoFormat.getInteger(MediaFormat.KEY_ROTATION);
        } else {
            rotation = 0;
            if (width < height) {
                maybeSwitchWH = true;
            }
        }
        int maxCache;
        if (mVideoFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            //使用读取到的size作为缓存大小，防止出现溢出
            maxCache = mVideoFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE);
        } else {
            maxCache = 500 * 1024;
        }

        //调整Surface尺寸
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mContainer.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
                if (rotation == 0 || rotation == 180) {
                    mContainer.setAspectRatio(width * 1f / height);
                } else {
                    mContainer.setAspectRatio(height * 1f / width);
                }
            }
        });

        String codecName = MediaCodecUtils.findDecoderByFormat(mVideoFormat);
        if (TextUtils.isEmpty(codecName)) {
            log.append("prepareDecoder: 完整format没有找到解码器！\n");
            log.append("prepareDecoder: 尝试降级！\n");
            if (MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION.equals(mime)) {
                //如果是杜比视界，那么尝试用HEVC的解码器去解
                mVideoFormat.setString(MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_VIDEO_HEVC);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    //因为杜比视界的profile和level是单独的，这里降级到HEVC的话，Profile和Level也要移除，否则还是会找不到解码器
                    mVideoFormat.removeKey(MediaFormat.KEY_PROFILE);
                    mVideoFormat.removeKey(MediaFormat.KEY_LEVEL);
                }
                codecName = MediaCodecUtils.findDecoderByFormat(mVideoFormat);
            } else if (MediaFormat.MIMETYPE_VIDEO_HEVC.equals(mime)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    log.append("prepareDecoder: 移除profile和level\n");
                    //HEVC的话，尝试移除Profile和Level
                    mVideoFormat.removeKey(MediaFormat.KEY_PROFILE);
                    mVideoFormat.removeKey(MediaFormat.KEY_LEVEL);
                }
                codecName = MediaCodecUtils.findDecoderByFormat(mVideoFormat);
                if (TextUtils.isEmpty(codecName)) {
                    log.append("prepareDecoder: 移除profile、level后format没有找到解码器！:").append(mVideoFormat).append("\n");
                    if (maybeSwitchWH) {
                        //Oppo有某些设备，竖屏拍摄的视频，不写rotation到metadata中，而是直接交换宽高（一般竖屏视频是1920x1080+90度，而这些特殊视频是1080x1920+0），
                        //导致这里因为解码器的宽高限制，无法获取到解码器.
                        log.append("prepareDecoder: 尝试交换Width和Height\n");
                        MediaFormat simpleFormat = MediaFormat.createVideoFormat(mime, height, width);
                        codecName = MediaCodecUtils.findDecoderByFormat(simpleFormat);
                        if (TextUtils.isEmpty(codecName)) {
                            log.append("prepareDecoder: 交换width、height也没有找到解码器！").append(simpleFormat).append("\n");
                        }
                    }
                }
            }
        }
        if (TextUtils.isEmpty(codecName)) {
            log.append("最终没有找到解码器!").append("\n");
            setDebugLog(log.toString());
            return;
        }

        log.append("找到解码器：").append(codecName).append("\n");
        setDebugLog(log.toString());

        try {
            //以同步模式进行解码
            MediaCodec decoder = MediaCodec.createByCodecName(codecName);
            mMediaCodec = decoder;
            decoder.configure(mVideoFormat, mSurfaceView.getHolder().getSurface(), null, 0);
            decoder.start();

            ByteBuffer byteBuffer = ByteBuffer.allocate(maxCache);
            int sampleSize;
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

            long startTime = System.nanoTime(); //ns

            //不停读取轨道数据
            while ((sampleSize = mMediaExtractor.readSampleData(byteBuffer, 0)) > 0) {
                long sampleTime = mMediaExtractor.getSampleTime(); //us

                //从decoder中取出输入缓冲队列
                int index = decoder.dequeueInputBuffer(10 * 1000L);
                if (index > -1) {
                    ByteBuffer inputBuffer = decoder.getInputBuffer(index);
                    //将从轨道读取的数据，填充进输入缓冲中
                    inputBuffer.clear();
                    inputBuffer.put(byteBuffer);
                    //将输入缓冲还给解码器
                    decoder.queueInputBuffer(index, 0, sampleSize, sampleTime, 0);
                }

                //从解码器中处理解码后的数据
                int outIndex = decoder.dequeueOutputBuffer(bufferInfo, 10 * 1000L);
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    //do nothing
                } else if (outIndex > -1) {
                    //检查是否到了渲染时间，没到的话sleep到渲染时间
                    if (System.nanoTime() - startTime < bufferInfo.presentationTimeUs * 1000L) {
                        SystemClock.sleep((bufferInfo.presentationTimeUs - (System.nanoTime() - startTime) / 1000) / 1000);
                    }
                    if (isFinishing() || isDestroyed()) {
                        break;
                    }
                    //这里直接将解码后的数据刷到Surface即可
                    try {
                        decoder.releaseOutputBuffer(outIndex, true);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }

                //获取接下来的轨道数据
                boolean hasNext = mMediaExtractor.advance();
                if (hasNext) {
                    byteBuffer.clear();
                } else {
                    break;
                }
            }

            if (mMediaCodec != null) {
                mMediaCodec.release();
            }
            if (mMediaExtractor != null) {
                mMediaExtractor.release();
            }
            log.append("解码完成，释放资源！").append("\n");
        } catch (Exception e) {
            e.printStackTrace();
            log.append("解码过程报错：" + e.getMessage());
        } finally {
            setDebugLog(log.toString());
        }
    }

    private void setDebugLog(String debugInfo) {
        runOnUiThread(() -> {
            mDebugTv.setText(debugInfo);
        });
    }
}
