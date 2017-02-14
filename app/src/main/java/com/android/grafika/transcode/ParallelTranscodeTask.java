package com.android.grafika.transcode;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Created by zhujinchang on 2017/2/14.
 */

public class ParallelTranscodeTask {

    private Context mContext;

    private File mInputFile;
    private File mOutputFile;

    private int mThreadCount;

    private int mWidth;
    private int mHeight;
    private int mBitrate;

    private long mStartUsec;
    private long mEndUsec;

    public ParallelTranscodeTask(Context context, File inputFile, File outputFile, int threadCount,
                                 int width, int height, int bitrate, long startUsec, long endUsec) {
        mContext = context;
        mInputFile = inputFile;
        mOutputFile = outputFile;
        mThreadCount = threadCount;
        mWidth = width;
        mHeight = height;
        mBitrate = bitrate;
        mStartUsec = startUsec;
        mEndUsec = endUsec;
    }

    /**
     * @return true if success
     */
    public boolean transcode() {
        List<String> tempVideos = new ArrayList<>();
        List<SingleThreadTranscodeTask> tasks = new ArrayList<>();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < mThreadCount; i++) {
            String tempFilePath = generateTempFilePath();
            tempVideos.add(tempFilePath);
            SingleThreadTranscodeTask task = new SingleThreadTranscodeTask(mInputFile,
                    new File(tempFilePath), mWidth, mHeight, mBitrate,
                    calcPieceStartUsec(i), calcPieceEndUsec(i));
            tasks.add(task);
            threads.add(new Thread(task));
        }

        for (Thread thread: threads) {
            thread.start();
        }

        for (Thread thread: threads) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                // Continue
            }
        }

        boolean transcodeSuccess = true;
        for (SingleThreadTranscodeTask task: tasks) {
            if (!task.isSuccess()) {
                transcodeSuccess = false;
                break;
            }
        }

        boolean overrideAudioSuccess = true;
        if (transcodeSuccess) {
            try {
                Mp4Util.overrideAudio(tempVideos, mInputFile.getAbsolutePath(), mStartUsec / 1000000.0,
                        mEndUsec / 1000000.0, mOutputFile.getAbsolutePath());
            } catch (IOException e) {
                overrideAudioSuccess = false;
            }
        }

        for (String tempVideo: tempVideos) {
            new File(tempVideo).delete();
        }

        return transcodeSuccess && overrideAudioSuccess;
    }

    private String generateTempFilePath() {
        return new File(mContext.getExternalCacheDir(), UUID.randomUUID() + ".mp4").getAbsolutePath();
    }

    private long calcPieceStartUsec(int index) {
        return mStartUsec + calcPieceLength() * index;
    }

    private long calcPieceEndUsec(int index) {
        return mStartUsec + calcPieceLength() * (index + 1);
    }

    private long calcPieceLength() {
        return (mEndUsec - mStartUsec) / mThreadCount;
    }
}
