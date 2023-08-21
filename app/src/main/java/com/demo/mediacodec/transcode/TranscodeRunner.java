package com.demo.mediacodec.transcode;

import android.content.ContentResolver;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
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
    private int mOriVideoRotation;
    private int mOriVideoFps;
    private long mVideoDurationUs;

    private boolean mMaybeSwitchWH;

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

            private void innerPrepareEncoder(VideoOutputConfig outputConfig) throws Exception {
                try {
                    prepareEncoder(outputConfig);
                } catch (NoSupportMediaCodecException e) {
                    if (outputConfig.outputLevel == MediaCodecUtils.OutputLevel.DEFAULT) {
                        //降到NoProfile模式
                        outputConfig.outputLevel = MediaCodecUtils.OutputLevel.NO_PROFILE;
                        e.printStackTrace();
                        Log.w("TranscodeRunner", "prepareEncoder: 降级至NoProfile模式");
                        innerPrepareEncoder(outputConfig);
                    } else if (outputConfig.outputLevel == MediaCodecUtils.OutputLevel.NO_PROFILE) {
                        //降到NoHDR模式
                        outputConfig.outputLevel = MediaCodecUtils.OutputLevel.NO_HDR;
                        e.printStackTrace();
                        Log.w("TranscodeRunner", "prepareEncoder: 降级至NoHDR模式");
                        innerPrepareEncoder(outputConfig);
                    } else {
                        throw e;
                    }
                }
            }

            @Override
            public void run() {
                if (mOriVideoFormat == null) {
                    callError(new IOException("没有找到视频轨道！"));
                    return;
                }
                try {
                    VideoOutputConfig outputConfig =
                            new VideoOutputConfig(MediaCodecUtils.OutputLevel.DEFAULT);
                    if (!mConfig.keepHdr) {
                        outputConfig.outputLevel = MediaCodecUtils.OutputLevel.NO_HDR;
                    }
                    outputConfig.force8Bit = mConfig.force8Bit;
                    innerPrepareEncoder(outputConfig);
                    prepareDecoder(outputConfig);
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
        mOriVideoFps = mOriVideoFormat.getInteger(MediaFormat.KEY_FRAME_RATE);
        mMaybeSwitchWH = false;
        if (mOriVideoFormat.containsKey(MediaFormat.KEY_ROTATION)) {
            mOriVideoRotation = mOriVideoFormat.getInteger(MediaFormat.KEY_ROTATION);
        } else {
            mOriVideoRotation = 0;
            if (mOriVideoWidth < mOriVideoHeight) {
                mMaybeSwitchWH = true;
            }
        }
        mVideoDurationUs = mOriVideoFormat.getLong(MediaFormat.KEY_DURATION);
    }

    /**
     * 准备编码器
     */
    private void prepareEncoder(VideoOutputConfig outputConfig) throws Exception {
        mOutputFormat = MediaCodecUtils.createOutputFormat(mContext, mVideoUri, mOriVideoFormat,
                mConfig, outputConfig);

        String codecName = MediaCodecUtils.findEncoderByFormat(mOutputFormat);
        if (TextUtils.isEmpty(codecName)) {
            if (mConfig.outWidth < mConfig.outHeight) {
                //有些设备下面判断是否支持写的不够好，这里主动交换一下width和height，看能否获取出编码器
                MediaFormat tempF = MediaCodecUtils.createOutputFormat(mContext, mVideoUri
                        , mOriVideoFormat, mConfig, outputConfig);
                tempF.setInteger(MediaFormat.KEY_WIDTH, mConfig.outHeight);
                tempF.setInteger(MediaFormat.KEY_HEIGHT, mConfig.outWidth);
                codecName = MediaCodecUtils.findEncoderByFormat(tempF);
            }
        }
        if (TextUtils.isEmpty(codecName)) {
            throw new NoSupportMediaCodecException("没有找到合适的编码器! outputFormat:" + mOutputFormat,
                        outputConfig.outputLevel);
        }
        Log.i("TranscodeRunner", "使用编码器" +
                ": " + codecName);
        mEncodeCodecThread = new HandlerThread("EncodeCodecThread");
        mEncodeCodecThread.start();
        mEncodeCodecHandler = new Handler(mEncodeCodecThread.getLooper());

        if (mEncoder != null) {
            try {
                mEncoder.release();
            } catch (Exception ignore) {
            }
        }
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

                    synchronized (hdrInfoLock) {
                        hdrInfoLock.notifyAll();
                    }
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
        try {
            mEncoder.configure(mOutputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        } catch (Exception e) {
            throw new NoSupportMediaCodecException("编码器Configure失败！outputFormat:" + mOutputFormat
                    , e, outputConfig.outputLevel);
        }

        Surface surface = mEncoder.createInputSurface();
        try {
            mEncoderInputSurface = new InputSurface(surface, outputConfig);
            //构造方法中创建了EGL环境后，这里立即进行绑定，后面OutputSurface初始化需要用到
            mEncoderInputSurface.makeCurrent();
        } catch (RuntimeException e) {
            throw new NoSupportMediaCodecException("EGL环境初始化失败！outputFormat:" + mOutputFormat, e,
                    outputConfig.outputLevel);
        }
    }

    private int decodeFrameIndex;
    private int encodeFrameIndex;
    private final Object hdrInfoLock = new Object();

    /**
     * 准备解码器
     */
    private void prepareDecoder(VideoOutputConfig outputConfig) throws Exception {
        decodeFrameIndex = 0;
        encodeFrameIndex = 0;
        boolean isDolby = MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION.equals(mOriVideoMime);
        boolean useDolbyDec = false;
        String codecName = MediaCodecUtils.findDecoderByFormat(mOriVideoFormat);
        if (TextUtils.isEmpty(codecName)) {
            if (isDolby) {
                //如果是杜比视界，那么尝试用HEVC的解码器去解
                mOriVideoFormat.setString(MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_VIDEO_HEVC);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    //因为杜比视界的profile和level是单独的，这里降级到HEVC的话，Profile和Level也要移除，否则还是会找不到解码器
                    mOriVideoFormat.removeKey(MediaFormat.KEY_PROFILE);
                    mOriVideoFormat.removeKey(MediaFormat.KEY_LEVEL);
                }
                codecName = MediaCodecUtils.findDecoderByFormat(mOriVideoFormat);
            } else if (MediaFormat.MIMETYPE_VIDEO_HEVC.equals(mOriVideoMime)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    //HEVC的话，尝试移除Profile和Level
                    mOriVideoFormat.removeKey(MediaFormat.KEY_PROFILE);
                    mOriVideoFormat.removeKey(MediaFormat.KEY_LEVEL);
                }
                codecName = MediaCodecUtils.findDecoderByFormat(mOriVideoFormat);

                if (TextUtils.isEmpty(codecName)) {
                    if (mMaybeSwitchWH) {
                        //Oppo有某些设备，竖屏拍摄的视频，不写rotation到metadata中，而是直接交换宽高（一般竖屏视频是1920x1080+90度，而这些特殊视频是1080x1920+0），
                        //导致这里因为解码器的宽高限制，无法获取到解码器.
                        MediaFormat simpleFormat = MediaFormat.createVideoFormat(mOriVideoMime, mOriVideoHeight, mOriVideoWidth);
                        codecName = MediaCodecUtils.findDecoderByFormat(simpleFormat);
                    }
                }
            } else {
                throw new RuntimeException("没有找到合适的解码器! videoFormat:" + mOriVideoFormat);
            }
        } else {
            if (isDolby) {
                useDolbyDec = true;
            }
        }
        if (TextUtils.isEmpty(codecName)) {
            throw new RuntimeException("没有找到合适的解码器! videoFormat:" + mOriVideoFormat);
        }
        Log.i("TranscodeRunner", "使用解码器: " + codecName);
        mDecodeCodecThread = new HandlerThread("DecodeCodecThread");
        mDecodeCodecThread.start();
        mDecodeCodecHandler = new Handler(mDecodeCodecThread.getLooper());

        mDecoder = MediaCodec.createByCodecName(codecName);
        //异步模式
        mDecoder.setCallback(new MediaCodec.Callback() {

            @Override
            public void onInputBufferAvailable(@NonNull MediaCodec codec, int index) {
                ByteBuffer inputBuffer = null;
                try {
                    inputBuffer = codec.getInputBuffer(index);
                } catch (Exception ignore) {
                }
                if (inputBuffer == null) {
                    return;
                }
                int sampleSize = mMediaExtractor.readSampleData(inputBuffer, 0);
                if (sampleSize > 0) {
                    long sampleTime = mMediaExtractor.getSampleTime();
                    int flags = mMediaExtractor.getSampleFlags();
                    try {
                        codec.queueInputBuffer(index, 0, sampleSize, sampleTime, flags);
                    } catch (Exception ignore) {
                    }
                    mMediaExtractor.advance();
                } else {
                    codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                }
            }

            @Override
            public void onOutputBufferAvailable(@NonNull MediaCodec codec, int index,
                                                @NonNull MediaCodec.BufferInfo info) {
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) == 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    boolean render = info.size > 0;
                    if (render && Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                        //如果是Android O以下，进行手动丢帧来降低帧率
                        if (Math.abs(info.presentationTimeUs - mVideoDurationUs) < 100_000L) {
                            //最后100ms之内，不丢帧
                        } else {
                            if (mOriVideoFps > 0 && mConfig.fps < mOriVideoFps) {
                                //如果相比原视频需要降低帧率，那么需要计算是否需要丢帧
                                long oriTimeInternal = 1000000000L / mOriVideoFps;
                                long dstTimeInternal = 1000000000L / mConfig.fps;
                                long dstTime = encodeFrameIndex * dstTimeInternal;
                                int indexPre = (int) (dstTime / oriTimeInternal);
                                int indexAfter = indexPre + 1;
                                //比较pre和after对应的时间，看取哪个合适
                                long offset1 = Math.abs(oriTimeInternal * indexPre - dstTime);
                                long offset2 = Math.abs(oriTimeInternal * indexAfter - dstTime);
                                if (offset1 <= offset2) {
                                    //采用indexPre
                                    if (decodeFrameIndex != indexPre) {
                                        //和indexPre不等，则进行丢帧
                                        render = false;
                                    }
                                } else {
                                    //采用indexAfter
                                    if (decodeFrameIndex != indexAfter) {
                                        //和indexAfter不等，则进行丢帧
                                        render = false;
                                    }
                                }
                            }
                        }
                    }
                    byte[] hdr10Info = null;
                    if (outputConfig.isHDR && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        try {
                            MediaFormat format = codec.getOutputFormat();
                            ByteBuffer hdrByteBuffer = format.getByteBuffer(MediaFormat.KEY_HDR10_PLUS_INFO);
                            if (hdrByteBuffer != null) {
                                int limit = hdrByteBuffer.limit();
                                if (limit > 0) {
                                    hdr10Info = new byte[limit];
                                    hdrByteBuffer.get(hdr10Info);
                                }
                            }
                        } catch (Exception ignore) {
                        }
                    }
                    try {
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
                            encodeFrameIndex++;

                            if (hdr10Info != null) {
                                //hdr10+的元数据需要手动写给编码器
                                Bundle codecParameters = new Bundle();
                                codecParameters.putByteArray(MediaCodec.PARAMETER_KEY_HDR10_PLUS_INFO, hdr10Info);
                                if (mEncoder != null) {
                                    mEncoder.setParameters(codecParameters);
                                }
                            }
                        }
                        decodeFrameIndex++;
                        Log.i("Decoder", "解码pts: " + info.presentationTimeUs);
                    } catch (Exception ignore) {
                    }
                    if (hdr10Info != null) {
                        //因为是解码和编码是异步的，上面对编码器设置了Hdr10Info后，会使得编码器输出的一帧带上这个数据，
                        //但如果解码速度快过编码速度，就会出现Hdr10Info绑定的帧不正确的情况。所以这里有意地降低一下解码速度。
                        synchronized (hdrInfoLock) {
                            try {
                                hdrInfoLock.wait(100);
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                        }
                    }
                } else {
                    codec.releaseOutputBuffer(index, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        if (mEncoder != null) {
                            mEncoder.signalEndOfInputStream();
                        }
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
        mDecoderOutputSurface = new OutputSurface(outputConfig);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            mOriVideoFormat.setInteger("allow-frame-drop", 0);
        }
        if (isDolby && useDolbyDec) {
            Bundle transferBundle = new Bundle();
            String value = "transfer.hlg"; //还有一种是"transfer.dolby"
            transferBundle.putString("vendor.dolby.codec.transfer.value", value);
            mDecoder.setParameters(transferBundle);
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
        if (mEncoder != null) {
            mEncoder.start();
        }
        mDecoder.start();
    }

    private void _transcodeComplete() {
        reset();
        if (listener != null) {
            listener.onTranscodeDone(mConfig.dstPath);
        }
    }
}