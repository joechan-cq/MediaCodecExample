package com.demo.mediacodec.transcode;

import java.io.File;

/**
 * @author : chenqiao
 * @date : 2023/1/29 1:40 PM
 */
public class TranscodeConfig {
    public File dstPath;
    public boolean h265;
    public int outWidth;
    public int outHeight;
    public int bitrate;
    public int fps;
    public boolean force8Bit;
    public boolean keepHdr;
}
