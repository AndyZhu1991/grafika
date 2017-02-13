/*
 * Copyright (c) 2016. BiliBili Inc.
 */

package com.android.grafika;

import com.coremedia.iso.IsoFile;
import com.coremedia.iso.boxes.Box;
import com.coremedia.iso.boxes.ChunkOffsetBox;
import com.coremedia.iso.boxes.Container;
import com.coremedia.iso.boxes.FreeBox;
import com.coremedia.iso.boxes.HandlerBox;
import com.coremedia.iso.boxes.MetaBox;
import com.coremedia.iso.boxes.MovieBox;
import com.coremedia.iso.boxes.UserDataBox;
import com.coremedia.iso.boxes.apple.AppleItemListBox;
import com.googlecode.mp4parser.DataSource;
import com.googlecode.mp4parser.FileDataSourceImpl;
import com.googlecode.mp4parser.authoring.Movie;
import com.googlecode.mp4parser.authoring.Track;
import com.googlecode.mp4parser.authoring.builder.DefaultMp4Builder;
import com.googlecode.mp4parser.authoring.container.mp4.MovieCreator;
import com.googlecode.mp4parser.authoring.tracks.AppendTrack;
import com.googlecode.mp4parser.authoring.tracks.CroppedTrack;
import com.googlecode.mp4parser.boxes.apple.AppleNameBox;
import com.googlecode.mp4parser.util.Matrix;
import com.googlecode.mp4parser.util.Path;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by zhujinchang on 10/26/16.
 */

public class Mp4Util {

    private static final String VIDEO_TRACK_HANDLER_KEY = "vide";
    private static final String SOUND_TRACK_HANDLER_KEY = "soun";

    public static double getDurationSeconds(String filePath) throws IOException {
        IsoFile isoFile = new IsoFile(filePath);
        return ((double) isoFile.getMovieBox().getMovieHeaderBox().getDuration())
                / isoFile.getMovieBox().getMovieHeaderBox().getTimescale();
    }


    public static void overrideAudio(String videoPath, String audioPath, double startTime,
                                     double endTime, String outMoviePath) throws IOException {
        //setVideoBgm(videoPath, audioPath, outMoviePath, true);
        Track videoTrack = null;
        for (Track track: MovieCreator.build(videoPath).getTracks()) {
            if (track.getHandler().equals(VIDEO_TRACK_HANDLER_KEY)) {
                videoTrack = track;
            }
        }

        Track audioTrack = null;
        for (Track track: MovieCreator.build(audioPath).getTracks()) {
            if (track.getHandler().equals(SOUND_TRACK_HANDLER_KEY)) {
                audioTrack = track;
            }
        }
        if (audioTrack == null) {
            return;
        }

        int[] samples = getClipSamples(audioTrack, startTime, endTime);
        CroppedTrack croppedTrack = new CroppedTrack(audioTrack, samples[0], samples[1]);
        saveTracks(outMoviePath, videoTrack, croppedTrack);
    }

    private static int[] getClipSamples(Track track, double startTime, double endTime) {
        int currentSample = 0;
        double currentTime = 0;
        double lastTime = -1;
        int startSample = -1;
        int endSample = -1;

        for (int i = 0; i < track.getSampleDurations().length; i++) {
            long delta = track.getSampleDurations()[i];

            if (currentTime > lastTime && currentTime <= startTime) {
                // current sample is still before the new starttime
                startSample = currentSample;
            }
            if (currentTime > lastTime && currentTime <= endTime) {
                // current sample is after the new start time and still before the new endtime
                endSample = currentSample;
            }
            lastTime = currentTime;
            currentTime += (double) delta / (double) track.getTrackMetaData().getTimescale();
            currentSample++;
        }

        return new int[] {startSample, endSample};
    }

    private static void saveTracks(String filePath, Track... tracks) throws IOException {
        Movie movie = new Movie();
        for (Track track: tracks) {
            movie.addTrack(track);
        }
        saveMovie(movie, filePath);
    }

    private static void saveMovie(Movie movie, String filePath) throws IOException {
        Container out = new DefaultMp4Builder().build(movie);
        FileChannel fc = new RandomAccessFile(String.format(filePath), "rw").getChannel();
        out.writeContainer(fc);
        fc.close();
    }
}
