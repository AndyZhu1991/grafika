package com.android.grafika.transcode;

import android.graphics.SurfaceTexture;

import com.android.grafika.TextureMovieEncoder;

/**
 * Created by zhujinchang on 2017/2/10.
 */

public class MovieTranscoder extends TextureMovieEncoder implements SurfaceTexture.OnFrameAvailableListener {

    private Callback mCallback;

    private long mIncomingFramePts;

    public SurfaceTexture createSurfaceTexture() {
        final SurfaceTexture[] surfaceTexture = {null};
        final Object object = new Object();
        synchronized (object) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    int textureId = mFullScreen.createTextureObject();
                    mTextureId = textureId;
                    surfaceTexture[0] = new SurfaceTexture(textureId);
                    surfaceTexture[0].setOnFrameAvailableListener(MovieTranscoder.this);
                    synchronized (object) {
                        object.notify();
                    }
                }
            });
            try {
                object.wait();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        return surfaceTexture[0];
    }

    public void setCallback(Callback callback) {
        mCallback = callback;
    }

    @Override
    protected void handleFrameAvailable(float[] transform, long timestampNanos) {
        super.handleFrameAvailable(transform, timestampNanos);
        if (mCallback != null) {
            mCallback.onSwappedBuffer(timestampNanos / 1000);
        }
    }

    @Override
    protected void handleStopRecording() {
        super.handleStopRecording();
        if (mCallback != null) {
            mCallback.onEncodeStop();
        }
    }

    public void setIncomingFramePts(long incomingFramePts) {
        mIncomingFramePts = incomingFramePts;
    }

    @Override
    public void onFrameAvailable(SurfaceTexture surfaceTexture) {
        surfaceTexture.updateTexImage();
        float[] transform = new float[16];      // TODO - avoid alloc every frame
        surfaceTexture.getTransformMatrix(transform);
        long timestamp = mIncomingFramePts;// surfaceTexture.getTimestamp();
        if (timestamp == 0) {
            // Seeing this after device is toggled off/on with power button.  The
            // first frame back has a zero timestamp.
            //
            // MPEG4Writer thinks this is cause to abort() in native code, so it's very
            // important that we just ignore the frame.
            return;
        }
        handleFrameAvailable(transform, timestamp * 1000);
    }

    public interface Callback {
        /**
         * @param pts in micro seconds
         */
        void onSwappedBuffer(long pts);

        void onEncodeStop();
    }
}
