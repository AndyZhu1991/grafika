package com.android.grafika;

import android.app.Activity;
import android.app.ProgressDialog;
import android.media.MediaMetadataRetriever;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.v4.os.AsyncTaskCompat;
import android.util.Pair;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.android.grafika.transcode.Mp4Util;
import com.android.grafika.transcode.ParallelTranscodeTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class TranscodeActivity extends Activity implements View.OnClickListener,
        AdapterView.OnItemSelectedListener {

    private static final String TAG = TranscodeActivity.class.getSimpleName();

    private static final int TRANSCODE_THREAD_COUNT = 5;

    private Spinner mSpinner;
    private TextView mDurationText;
    private EditText mStartEdit;
    private EditText mEndEdit;
    private Button mStartButton;

    private List<File> mMovieFiles;
    private List<String> mMovieNames;
    private String mSelectedVideo;
    private long mVideoDuration;

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
        final Pair<Integer, Integer> size = retriveVideoSize(mSelectedVideo);
        if (size == null) {
            toast("Video file error!");
            return;
        }
        getOutFileWithAudio().delete();

        final long[] startMillis = new long[1];
        AsyncTaskCompat.executeParallel(new AsyncTask<Object, Object, Boolean>() {
            @Override
            protected void onPreExecute() {
                startMillis[0] = System.currentTimeMillis();
                mProgressDialog.show();
            }

            @Override
            protected Boolean doInBackground(Object... params) {
                return new ParallelTranscodeTask(TranscodeActivity.this, new File(mSelectedVideo),
                        getOutFileWithAudio(), TRANSCODE_THREAD_COUNT, size.first, size.second,
                        1000000, mStartUsec, mEndUsec)
                        .transcode();
            }

            @Override
            protected void onPostExecute(Boolean success) {
                mProgressDialog.dismiss();
                if (success) {
                    toast("Take " + (System.currentTimeMillis() - startMillis[0]) + " millis");
                } else {
                    toast("Failed!");
                }
            }
        });
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

    private File getOutFileWithAudio() {
        return new File(getExternalCacheDir(), "transcode-test-with-audio.mp4");
    }

    private void toast(String string) {
        Toast.makeText(this, string, Toast.LENGTH_LONG).show();
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
}
