package com.android.grafika.transcode;

import android.graphics.SurfaceTexture;
import android.util.Log;
import android.view.Surface;

import com.android.grafika.MoviePlayer;
import com.android.grafika.TextureMovieEncoder;

import java.io.File;

/**
 * Created by zhujinchang on 2017/2/13.
 */

public class SingleThreadTranscodeTask implements Runnable, MovieTranscoder.Callback, MoviePlayer.FrameCallback {

    private static final String TAG = SingleThreadTranscodeTask.class.getSimpleName();

    private MovieTranscoder mMovieTranscoder;
    private TextureMovieEncoder.EncoderConfig mEncoderConfig;

    private MoviePlayer mMoviePlayer;

    private File mInputFile;
    private File mOutputFile;
    private long mStartUsecs;
    private long mEndUsecs;
    private boolean isSuccess = false;

    private final Object mStopLock = new Object();
    private boolean isStoped = false;

    private boolean waitForEncode = false;
    private final Object mCodecLock = new Object();

    public SingleThreadTranscodeTask(File inputFile, File outputFile, int width, int height, int bitRate,
                                     long startUsecs, long endUsecsg) {
        mEncoderConfig = new TextureMovieEncoder.EncoderConfig(outputFile, width, height, bitRate, null);
        mInputFile = inputFile;
        mOutputFile = outputFile;
        mStartUsecs = startUsecs;
        mEndUsecs = endUsecsg;
        mMovieTranscoder = new MovieTranscoder();
    }

    public boolean isSuccess() {
        return isSuccess;
    }

    @Override
    public void run() {
        try {
            mMovieTranscoder.startRecording(mEncoderConfig);
            mMovieTranscoder.setCallback(this);
            SurfaceTexture surfaceTexture = mMovieTranscoder.createSurfaceTexture();
            mMoviePlayer = new MoviePlayer(mInputFile, new Surface(surfaceTexture), this);
            mMoviePlayer.setLoopMode(false);
            mMoviePlayer.play(mStartUsecs);
            mMovieTranscoder.stopRecording();

            synchronized (mStopLock) {
                if (!isStoped) {
                    mStopLock.wait();
                }
                isSuccess = true;
            }
        } catch (Exception e) {
            isSuccess = false;
        }
    }

    @Override
    public void onSwappedBuffer(long pts) {
        synchronized (mCodecLock) {
            waitForEncode = false;
            mCodecLock.notify();
        }
        Log.d(TAG, "Swapped buffer: " + pts);
    }

    @Override
    public void onEncodeStop() {
        synchronized (mStopLock) {
            isStoped = true;
            mStopLock.notify();
        }
    }

    @Override
    public boolean preRender(long presentationTimeUsec) {
        Log.d(TAG, "Pre-render: " + presentationTimeUsec);

        if (presentationTimeUsec == 0) {
            return false; // Skip 0, it is not a video frame
        }

        if (presentationTimeUsec <= mStartUsecs) {
            return false;
        }

        if (presentationTimeUsec > mEndUsecs) {
            mMoviePlayer.requestStop();
            return false;
        }

        synchronized (mCodecLock) {
            if (waitForEncode) {
                try {
                    mCodecLock.wait();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            waitForEncode = true;
        }

        mMovieTranscoder.setIncomingFramePts(presentationTimeUsec);
        return true;
    }

    @Override
    public void postRender() {
    }

    @Override
    public void loopReset() {
    }
}
