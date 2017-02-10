package com.android.grafika;

import android.app.Activity;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.util.Log;
import android.view.Surface;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;

public class TranscodeActivity extends Activity implements View.OnClickListener,
        MoviePlayer.PlayerFeedback, OffscreenTextureMovieEncoder.Callback {

    private Button mStartButton;

    private OffscreenTextureMovieEncoder mMovieEncoder;
    private MoviePlayer mMoviePlayer;
    private MoviePlayer.PlayTask mPlayTask;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_transcode);
        mStartButton = (Button) findViewById(R.id.button);
        mStartButton.setOnClickListener(this);

        init();
    }

    private void init() {
        mMovieEncoder = new OffscreenTextureMovieEncoder();
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.button:
                onStartClick();
                break;
        }
    }

    private void onStartClick() {
        mStartButton.setEnabled(false);

        mMovieEncoder.startRecording(new TextureMovieEncoder.EncoderConfig(
                getOutFile(), 1920, 1080, 2000000, null));
        mMovieEncoder.setCallback(this);
        SurfaceTexture surfaceTexture = mMovieEncoder.createSurfaceTexture();
        try {
            mMoviePlayer = new MoviePlayer(getInFile(), new Surface(surfaceTexture), new FrameControlCallback());
        } catch (IOException e) {
            toast("Movie player create error!");
            return;
        }
        mPlayTask = new MoviePlayer.PlayTask(mMoviePlayer, this);
        mPlayTask.setLoopMode(false);
        mPlayTask.execute();
    }

    private File getInFile() {
        //return ContentManager.getInstance().getPath(ContentManager.MOVIE_SLIDERS);
        return new File(getExternalCacheDir(), "eva01sample.mp4");
    }

    private File getOutFile() {
        return new File(getExternalCacheDir(), "transcode-test.mp4");
    }

    private void toast(String string) {
        Toast.makeText(this, string, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void playbackStopped() {
        mMovieEncoder.stopRecording();
        mStartButton.setEnabled(true);
        toast("Complete!");
    }

    @Override
    public void onSwappedBuffer(long pts) {
        synchronized (mCodecLock) {
            mCodecLock.notify();
            waitForEncode = false;
        }
        Log.d("Andy", "Swapped buffer: " + pts);
    }

    private boolean waitForEncode = false;
    private final Object mCodecLock = new Object();

    class FrameControlCallback implements MoviePlayer.FrameCallback {

        @Override
        public boolean preRender(long presentationTimeUsec) {
            Log.d("Andy", "Pre-render: " + presentationTimeUsec);
            boolean needRender = presentationTimeUsec > 0;
            if (needRender && waitForEncode) {
                synchronized (mCodecLock) {
                    try {
                        mCodecLock.wait();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
                return true;
            } else {
                return needRender;
            }
        }

        @Override
        public void postRender() {
            waitForEncode = true;
        }

        @Override
        public void loopReset() {
        }
    }
}
