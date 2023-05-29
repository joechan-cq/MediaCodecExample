package com.demo.mediacodec.transcode;

import android.content.ContentResolver;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;
import android.util.Log;
import android.view.Surface;

import com.demo.mediacodec.MediaCodecUtils;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

import androidx.annotation.NonNull;

/**
 * 视频转码的原理：
 * 准备好一个离屏渲染的Surface
 * 解码器解析视频数据，绘制到Surface上
 * 编码器将该Surface作为输入源，编码输出视频帧数据
 * 使用Muxer，向文件写入视频帧数据，最终生成转码视频。
 *
 * @author : chenqiao
 * @date : 2023/1/29 10:45 AM
 */
public class TranscodeRunner {

    private InputSurface mEncoderInputSurface;
    private OutputSurface mDecoderOutputSurface;

    public interface OnTranscodeListener {

        void onPrepareDone(MediaFormat videoFormat);

        void onError(Exception e);

        void onTranscodeProgress(int current);

        void onTranscodeDone(File output);

    }

    private final Context mContext;
    private final Uri mVideoUri;
    private TranscodeConfig mConfig;
    private ParcelFileDescriptor pf;
    private MediaFormat mOriVideoFormat;
    private MediaFormat mOutputFormat;
    private MediaFormat mRealOutputFormat;

    private String mOriVideoMime;
    private int mOriVideoWidth, mOriVideoHeight;
    private long mVideoDurationUs;

    private OnTranscodeListener listener;

    //媒体提取器
    private MediaExtractor mMediaExtractor;
    //视频轨道Id
    private int mVideoTrackerIndex;

    //编解码器
    private MediaCodec mDecoder, mEncoder;

    private MediaMuxer mMuxer;
    private int mVideoOutputTrackIndex;

    //解码回调线程
    private HandlerThread mDecodeCodecThread;
    private Handler mDecodeCodecHandler;

    //编码回调线程
    private HandlerThread mEncodeCodecThread;
    private Handler mEncodeCodecHandler;

    public TranscodeRunner(Context context, Uri uri) {
        mContext = context;
        mVideoUri = uri;
    }

    public void setTransCodeListener(OnTranscodeListener listener) {
        this.listener = listener;
    }

    public void prepareAsync() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                mMediaExtractor = new MediaExtractor();
                try {
                    ContentResolver contentResolver = mContext.getContentResolver();
                    pf = contentResolver.openFileDescriptor(mVideoUri, "r");
                    mMediaExtractor.setDataSource(pf.getFileDescriptor());
                    int trackCount = mMediaExtractor.getTrackCount();
                    for (int i = 0; i < trackCount; i++) {
                        MediaFormat format = mMediaExtractor.getTrackFormat(i);
                        String mime = format.getString(MediaFormat.KEY_MIME);
                        if (!TextUtils.isEmpty(mime) && mime.startsWith("video")) {
                            //找到视频轨道
                            mOriVideoFormat = format;
                            _getOriVideoInfo();
                            //选中该视频轨道，后面读取轨道数据，就是读取的该轨道的
                            mVideoTrackerIndex = i;
                            mMediaExtractor.selectTrack(i);
                            if (listener != null) {
                                listener.onPrepareDone(mOriVideoFormat);
                            }
                            return;
                        }
                    }
                    callError(new IOException("没有找到视频轨道！"));
                } catch (IOException e) {
                    e.printStackTrace();
                    callError(e);
                }
            }
        }).start();
    }

    public void startTranscode(@NonNull TranscodeConfig transcodeConfig) {
        mConfig = transcodeConfig;
        new Thread(new Runnable() {
            @Override
            public void run() {
                if (mOriVideoFormat == null) {
                    callError(new IOException("没有找到视频轨道！"));
                    return;
                }
                try {
                    prepareEncoder();
                    prepareDecoder();
                    _start();
                } catch (Exception e) {
                    e.printStackTrace();
                    callError(e);
                }
            }
        }).start();
    }

    public void reset() {
        mMediaExtractor.unselectTrack(mVideoTrackerIndex);
        mMediaExtractor.selectTrack(mVideoTrackerIndex);
        mMediaExtractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);

        if (mEncoderInputSurface != null) {
            mEncoderInputSurface.release();
        }
        mEncoderInputSurface = null;
        if (mDecoderOutputSurface != null) {
            mDecoderOutputSurface.release();
        }
        mDecoderOutputSurface = null;

        if (mDecoder != null) {
            try {
                mDecoder.release();
            } catch (Exception e) {
                Log.w("TranscodeRunner", "reset: ", e);
            }
            mDecoder = null;
        }
        if (mDecodeCodecThread != null) {
            mDecodeCodecThread.quitSafely();
        }
        mDecodeCodecThread = null;
        mDecodeCodecHandler = null;

        if (mEncoder != null) {
            try {
                mEncoder.stop();
                mEncoder.release();
            } catch (Exception e) {
                Log.w("TranscodeRunner", "reset: ", e);
            }
            mEncoder = null;
        }
        if (mEncodeCodecThread != null) {
            mEncodeCodecThread.quitSafely();
        }
        mEncodeCodecThread = null;
        mEncodeCodecHandler = null;

        if (mMuxer != null) {
            try {
                mMuxer.stop();
                mMuxer.release();
            } catch (Exception e) {
                Log.w("TranscodeRunner", "reset: ", e);
            }
            mMuxer = null;
        }
        mVideoOutputTrackIndex = 0;
    }

    public void release() {
        reset();
        if (pf != null) {
            try {
                pf.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        if (mMediaExtractor != null) {
            mMediaExtractor.release();
        }
    }

    private void callProgress(int current) {
        if (listener != null) {
            listener.onTranscodeProgress(current);
        }
    }

    private void callError(Exception e) {
        reset();
        if (listener != null) {
            listener.onError(e);
        }
    }

    private void _getOriVideoInfo() {
        mOriVideoMime = mOriVideoFormat.getString(MediaFormat.KEY_MIME);
        mOriVideoWidth = mOriVideoFormat.getInteger(MediaFormat.KEY_WIDTH);
        mOriVideoHeight = mOriVideoFormat.getInteger(MediaFormat.KEY_HEIGHT);
        mVideoDurationUs = mOriVideoFormat.getLong(MediaFormat.KEY_DURATION);
    }

    /**
     * 准备编码器
     */
    private void prepareEncoder() throws Exception {
        boolean isDolby = false;
        boolean isH265 = false;
        String mime;
        if (mConfig.h265) {
            isH265 = true;
            if (MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION.equals(mOriVideoMime)) {
                //如果是杜比视界，那么需要检查能否使用杜比视界的mimeType
                mime = MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION;
                isDolby = true;
            } else {
                mime = MediaFormat.MIMETYPE_VIDEO_HEVC;
            }
        } else {
            mime = MediaFormat.MIMETYPE_VIDEO_AVC;
        }
        mOutputFormat = MediaFormat.createVideoFormat(mime, mConfig.outWidth, mConfig.outHeight);
        if (isDolby) {
            String codecName = MediaCodecUtils.findEncoderByFormat(mOutputFormat, false);
            if (codecName == null) {
                //说明没有杜比视界的编码器，降级到Hevc去
                mime = MediaFormat.MIMETYPE_VIDEO_HEVC;
                mOutputFormat = MediaFormat.createVideoFormat(mime, mConfig.outWidth,
                        mConfig.outHeight);
                isDolby = false;
            }
        }
        mOutputFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        mOutputFormat.setInteger(MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
        if (mConfig.bitrate > 0) {
            mOutputFormat.setInteger(MediaFormat.KEY_BIT_RATE, mConfig.bitrate);
        } else {
            mOutputFormat.setInteger(MediaFormat.KEY_BIT_RATE, 2500000);
        }
        if (mConfig.fps > 0) {
            mOutputFormat.setInteger(MediaFormat.KEY_FRAME_RATE, mConfig.fps);
        } else {
            mOutputFormat.setInteger(MediaFormat.KEY_FRAME_RATE, 25);
        }
        mOutputFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        if (Build.VERSION.SDK_INT > 23 && isH265) {
            //不去生成H264的HDR视频
            //设置Color相关参数，使其尽量保证HDR视频转码后仍然是HDR视频
            int colorTransfer = 0;
            if (mOriVideoFormat.containsKey(MediaFormat.KEY_COLOR_STANDARD)) {
                mOutputFormat.setInteger(MediaFormat.KEY_COLOR_STANDARD,
                        mOriVideoFormat.getInteger(MediaFormat.KEY_COLOR_STANDARD));
            }
            if (mOriVideoFormat.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) {
                colorTransfer = mOriVideoFormat.getInteger(MediaFormat.KEY_COLOR_TRANSFER);
                mOutputFormat.setInteger(MediaFormat.KEY_COLOR_TRANSFER, colorTransfer);
            }
            if (mOriVideoFormat.containsKey(MediaFormat.KEY_COLOR_RANGE)) {
                mOutputFormat.setInteger(MediaFormat.KEY_COLOR_RANGE,
                        mOriVideoFormat.getInteger(MediaFormat.KEY_COLOR_RANGE));
            }
            if (mOriVideoFormat.containsKey(MediaFormat.KEY_HDR_STATIC_INFO)) {
                mOutputFormat.setByteBuffer(MediaFormat.KEY_HDR_STATIC_INFO,
                        mOriVideoFormat.getByteBuffer(MediaFormat.KEY_HDR_STATIC_INFO));
            }
            if (isDolby) {
                //如果是杜比
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    mOutputFormat.setInteger(MediaFormat.KEY_PROFILE,
                            MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheSt);
                } else {
                    mOutputFormat.setInteger(MediaFormat.KEY_PROFILE,
                            MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheStn);
                }
            } else {
                mOutputFormat.setFeatureEnabled("hdr-editing", true);
                switch (colorTransfer) {
                    case MediaFormat.COLOR_TRANSFER_HLG:
                        //HLG（HGL10）
                        mOutputFormat.setInteger(MediaFormat.KEY_PROFILE,
                                MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10);
                        break;
                    case MediaFormat.COLOR_TRANSFER_ST2084:
                        //PQ（HDR10和HDR10+）
                        //TODO 怎么区分HDR10和HDR10+ HEVCProfileMain10HDR10Plus
                        mOutputFormat.setInteger(MediaFormat.KEY_PROFILE,
                                MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10);
                        //TODO 可能还是要降级成HEVCProfileMain10
                        break;
                    default:
                        break;
                }
            }
        }

        //TODO 这里需要注意，部分设备硬件编码器是无法保留HDR的（但屏幕本身支持HDR视频显示），因此可能需要改成软件编码器才行
        String codecName = MediaCodecUtils.findEncoderByFormat(mOutputFormat, false);
        if (TextUtils.isEmpty(codecName)) {
            throw new RuntimeException("没有找到合适的编码器! outputFormat:" + mOutputFormat);
        }
        mEncodeCodecThread = new HandlerThread("EncodeCodecThread");
        mEncodeCodecThread.start();
        mEncodeCodecHandler = new Handler(mEncodeCodecThread.getLooper());

        mEncoder = MediaCodec.createByCodecName(codecName);

        mEncoder.setCallback(new MediaCodec.Callback() {
            @Override
            public void onInputBufferAvailable(@NonNull MediaCodec codec, int index) {
            }

            @Override
            public void onOutputBufferAvailable(@NonNull MediaCodec codec, int index,
                                                @NonNull MediaCodec.BufferInfo info) {
                ByteBuffer outputBuffer = codec.getOutputBuffer(index);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) == 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    mMuxer.writeSampleData(mVideoOutputTrackIndex, outputBuffer, info);
                    long presentationTimeUs = info.presentationTimeUs;
                    callProgress((int) (presentationTimeUs * 100 / mVideoDurationUs));
                    Log.i("Encoder", "编码pts: " + presentationTimeUs);
                }
                codec.releaseOutputBuffer(index, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    Log.i("Encoder", "编码已经完成");
                    _transcodeComplete();
                }
            }

            @Override
            public void onError(@NonNull MediaCodec codec,
                                @NonNull MediaCodec.CodecException e) {
                e.printStackTrace();
                callError(e);
            }

            @Override
            public void onOutputFormatChanged(@NonNull MediaCodec codec,
                                              @NonNull MediaFormat format) {
                Log.i("Encoder", "encoder output format: " + format);
                mRealOutputFormat = format;
                if (mMuxer == null) {
                    try {
                        prepareMuxer();
                    } catch (Exception e) {
                        e.printStackTrace();
                        callError(e);
                    }
                }
            }
        }, mEncodeCodecHandler);
        mEncoder.configure(mOutputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);

        Surface surface = mEncoder.createInputSurface();
        mEncoderInputSurface = new InputSurface(surface);
        //构造方法中创建了EGL环境后，这里立即进行绑定，后面OutputSurface初始化需要用到
        mEncoderInputSurface.makeCurrent();
    }

    /**
     * 准备解码器
     */
    private void prepareDecoder() throws Exception {
        String codecName = MediaCodecUtils.findDecoderByFormat(mOriVideoFormat, false);
        if (TextUtils.isEmpty(codecName)) {
            throw new RuntimeException("没有找到合适的解码器! videoFormat:" + mOriVideoFormat);
        }
        mDecodeCodecThread = new HandlerThread("DecodeCodecThread");
        mDecodeCodecThread.start();
        mDecodeCodecHandler = new Handler(mDecodeCodecThread.getLooper());

        mDecoder = MediaCodec.createByCodecName(codecName);
        //异步模式
        mDecoder.setCallback(new MediaCodec.Callback() {

            @Override
            public void onInputBufferAvailable(@NonNull MediaCodec codec, int index) {
                ByteBuffer inputBuffer = codec.getInputBuffer(index);
                int sampleSize = mMediaExtractor.readSampleData(inputBuffer, 0);
                if (sampleSize > 0) {
                    long sampleTime = mMediaExtractor.getSampleTime();
                    int flags = mMediaExtractor.getSampleFlags();
                    codec.queueInputBuffer(index, 0, sampleSize, sampleTime, flags);
                    mMediaExtractor.advance();
                } else {
                    codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                }
            }

            @Override
            public void onOutputBufferAvailable(@NonNull MediaCodec codec, int index,
                                                @NonNull MediaCodec.BufferInfo info) {
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) == 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    boolean render = info.size != 0;
                    codec.releaseOutputBuffer(index, render);
                    if (render) {
                        // 切换GL线程
                        // 为什么不用mDecoderOutputSurface.makeCurrent()
                        // ?因为OutputSurface内部没有创建EGLContext等参数
                        mEncoderInputSurface.makeCurrent();
                        //往OutputSurface上绘制图像
                        mDecoderOutputSurface.awaitNewImage();
                        mDecoderOutputSurface.drawImage();
                        //上屏
                        mEncoderInputSurface.setPresentationTime(info.presentationTimeUs * 1000);
                        mEncoderInputSurface.swapBuffers();
                        mEncoderInputSurface.makeUnCurrent();
                    }
                    Log.i("Decoder", "解码pts: " + info.presentationTimeUs);
                } else {
                    codec.releaseOutputBuffer(index, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        mEncoder.signalEndOfInputStream();
                        codec.stop();
                        codec.release();
                        Log.i("Decoder", "解码已经完成");
                    }
                }
            }

            @Override
            public void onError(@NonNull MediaCodec codec, @NonNull MediaCodec.CodecException e) {
                e.printStackTrace();
                callError(e);
            }

            @Override
            public void onOutputFormatChanged(@NonNull MediaCodec codec,
                                              @NonNull MediaFormat format) {
                Log.i("Decoder", "decoder output format: " + format);
            }
        }, mDecodeCodecHandler);
        mDecoderOutputSurface = new OutputSurface();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            mOriVideoFormat.setInteger("allow-frame-drop", 0);
        }
        mDecoder.configure(mOriVideoFormat, mDecoderOutputSurface.getSurface(), null, 0);
    }

    private void prepareMuxer() throws Exception {
        mMuxer = new MediaMuxer(mConfig.dstPath.getAbsolutePath(),
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        mVideoOutputTrackIndex = mMuxer.addTrack(mRealOutputFormat);
        mMuxer.start();
    }

    private void _start() {
        mEncoder.start();
        mDecoder.start();
    }

    private void _transcodeComplete() {
        reset();
        if (listener != null) {
            listener.onTranscodeDone(mConfig.dstPath);
        }
    }
}