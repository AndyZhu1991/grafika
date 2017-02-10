package com.android.grafika;

import android.app.Activity;
import android.graphics.SurfaceTexture;
import android.media.MediaMetadataRetriever;
import android.os.Bundle;
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

        mMovieFiles = new ArrayList<>(Arrays.asList(MiscUtils.getFilesReal(getFilesDir(), "*.mp4")));
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

        mMovieEncoder.startRecording(new TextureMovieEncoder.EncoderConfig(
                getOutFile(), size.first, size.second, 1000000, null));
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
            boolean needRender = presentationTimeUsec > 0
                    && presentationTimeUsec >= mStartUsec && presentationTimeUsec < mEndUsec;
            if (needRender && waitForEncode) {
                synchronized (mCodecLock) {
                    try {
                        mCodecLock.wait();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
                return true;
            } else if (presentationTimeUsec >= mEndUsec) {
                mPlayTask.requestStop();
                return false;
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
