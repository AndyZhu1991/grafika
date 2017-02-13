package com.android.grafika;

import android.app.Activity;
import android.app.ProgressDialog;
import android.graphics.SurfaceTexture;
import android.media.MediaMetadataRetriever;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.v4.os.AsyncTaskCompat;
import android.util.Log;
import android.util.Pair;
import android.view.Surface;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class TranscodeActivity extends Activity implements View.OnClickListener,
        MoviePlayer.PlayerFeedback, OffscreenTextureMovieEncoder.Callback, AdapterView.OnItemSelectedListener {

    private static final String TAG = TranscodeActivity.class.getSimpleName();

    private Spinner mSpinner;
    private TextView mDurationText;
    private EditText mStartEdit;
    private EditText mEndEdit;
    private Button mStartButton;

    private List<File> mMovieFiles;
    private List<String> mMovieNames;
    private String mSelectedVideo;
    private long mVideoDuration;

    private OffscreenTextureMovieEncoder mMovieEncoder;
    private MoviePlayer mMoviePlayer;
    private MoviePlayer.PlayTask mPlayTask;

    private ProgressDialog mProgressDialog;

    private long mStartUsec;
    private long mEndUsec;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_transcode);

        initViews();
        init();
    }

    private void initViews() {
        mSpinner = (Spinner) findViewById(R.id.spinner);
        mDurationText = (TextView) findViewById(R.id.duration_text);
        mStartEdit = (EditText) findViewById(R.id.edit_start);
        mEndEdit = (EditText) findViewById(R.id.edit_end);
        mStartButton = (Button) findViewById(R.id.button);
        mStartButton.setOnClickListener(this);
    }

    private void init() {
        mMovieEncoder = new OffscreenTextureMovieEncoder();

        mMovieFiles = new ArrayList<>(Arrays.asList(MiscUtils.getFilesReal(getExternalCacheDir(), "*.mp4")));
        mMovieFiles.addAll(Arrays.asList(MiscUtils.getFilesReal(getExternalCacheDir(), "*.mp4")));
        mMovieNames = new ArrayList<>();
        for (File file: mMovieFiles) {
            mMovieNames.add(file.getName());
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, mMovieNames);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        // Apply the adapter to the spinner.
        mSpinner.setAdapter(adapter);
        mSpinner.setOnItemSelectedListener(this);

        mProgressDialog = new ProgressDialog(this);
        mProgressDialog.setIndeterminate(true);
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
        if (!checkCutRange()) {
            toast("Inavailable cut range!");
            return;
        }
        Pair<Integer, Integer> size = retriveVideoSize(mSelectedVideo);
        if (size == null) {
            toast("Video file error!");
            return;
        }
        getOutFile().delete();
        getOutFileWithAudio().delete();

        mMovieEncoder.startRecording(new TextureMovieEncoder.EncoderConfig(
                getOutFile(), size.first / 2, size.second / 2, 1000000, null));
        mMovieEncoder.setCallback(this);
        SurfaceTexture surfaceTexture = mMovieEncoder.createSurfaceTexture();
        try {
            mMoviePlayer = new MoviePlayer(new File(mSelectedVideo), new Surface(surfaceTexture),
                    new FrameControlCallback());
        } catch (IOException e) {
            toast("Movie player create error!");
            return;
        }
        mPlayTask = new MoviePlayer.PlayTask(mMoviePlayer, this);
        mPlayTask.setLoopMode(false);
        mPlayTask.setStartTime(mStartUsec);
        mPlayTask.execute();
        mStartButton.setEnabled(false);
        mProgressDialog.show();
    }

    private boolean checkCutRange() {
        try {
            mStartUsec = Long.valueOf(mStartEdit.getText().toString()) * 1000;
            mEndUsec = Long.valueOf(mEndEdit.getText().toString()) * 1000;
            return mStartUsec >= 0 && mStartUsec < mEndUsec && mEndUsec <= mVideoDuration * 1000;
        } catch (Exception e) {
            return false;
        }
    }

    private Pair<Integer, Integer> retriveVideoSize(String videoPath) {
        try {
            MediaMetadataRetriever metadataRetriever = new MediaMetadataRetriever();
            metadataRetriever.setDataSource(videoPath);
            int width = Integer.valueOf(metadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            int height = Integer.valueOf(metadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            return new Pair<>(width, height);
        } catch (Exception e) {
            return null;
        }
    }

    private File getOutFile() {
        return new File(getExternalCacheDir(), "transcode-test.mp4");
    }

    private File getOutFileWithAudio() {
        return new File(getExternalCacheDir(), "transcode-test-with-audio.mp4");
    }

    private void toast(String string) {
        Toast.makeText(this, string, Toast.LENGTH_SHORT).show();
    }

    private void onVideoSelected(String videoPath) {
        try {
            mSelectedVideo = videoPath;
            mVideoDuration = (long) (Mp4Util.getDurationSeconds(videoPath) * 1000);
            mDurationText.setText(String.valueOf(mVideoDuration));
            mStartEdit.setText("0");
            mEndEdit.setText(String.valueOf(mVideoDuration));
            mStartButton.setEnabled(true);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        onVideoSelected(mMovieFiles.get(position).getAbsolutePath());
    }

    @Override
    public void onNothingSelected(AdapterView<?> parent) {
    }

    @Override
    public void playbackStopped() {
        mMovieEncoder.stopRecording();
        mStartButton.setEnabled(true);
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
        AsyncTaskCompat.executeParallel(new AsyncTask<Object, Object, Object>() {
            @Override
            protected Object doInBackground(Object... params) {
                try {
                    Mp4Util.overrideAudio(getOutFile().getAbsolutePath(), mSelectedVideo,
                            mStartUsec / 1000000.0, mEndUsec / 1000000.0,
                            getOutFileWithAudio().getAbsolutePath());
                } catch (IOException e) {
                    e.printStackTrace();
                }
                return null;
            }

            @Override
            protected void onPostExecute(Object o) {
                toast("Complete!");
                mProgressDialog.dismiss();
            }
        });
    }

    private boolean waitForEncode = false;
    private final Object mCodecLock = new Object();

    class FrameControlCallback implements MoviePlayer.FrameCallback {

        @Override
        public boolean preRender(long presentationTimeUsec) {
            Log.d(TAG, "Pre-render: " + presentationTimeUsec);

            if (presentationTimeUsec == 0) {
                return false; // Skip 0, it is not a video frame
            }

            if (presentationTimeUsec <= mStartUsec) {
                return false;
            }

            if (presentationTimeUsec > mEndUsec) {
                mPlayTask.requestStop();
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

            mMovieEncoder.setIncomingFramePts(presentationTimeUsec);
            return true;
        }

        @Override
        public void postRender() {
        }

        @Override
        public void loopReset() {
        }
    }
}
