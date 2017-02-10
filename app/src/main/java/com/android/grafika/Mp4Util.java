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

    public static void mp4Cat(List<String> inMoviesPath, String outMoviePath, boolean portRotation)
            throws IOException {

        List<Movie> inMovies = new ArrayList<>();
        List<Closeable> closeables = new ArrayList<>();
        for (String movie: inMoviesPath) {
            DataSource dataSource = new FileDataSourceImpl(new File(movie));
            closeables.add(dataSource);
            inMovies.add(MovieCreator.build(dataSource));
        }

        List<Track> videoTracks = new ArrayList<>();
        List<Track> audioTracks = new ArrayList<>();
        List<Track> croppedAudioTracks = new ArrayList<>();

        for (Movie m : inMovies) {
            for (Track t : m.getTracks()) {
                if (t.getHandler().equals(SOUND_TRACK_HANDLER_KEY)) {
                    audioTracks.add(t);
                }
                if (t.getHandler().equals(VIDEO_TRACK_HANDLER_KEY)) {
                    videoTracks.add(t);
                }
            }
        }

        for (int i = 0; i < videoTracks.size() && i < audioTracks.size(); i++) {
            Track videoTrack = videoTracks.get(i);
            double videoDuration = getDurationSeconds(videoTrack);
            Track originAudioTrack = audioTracks.get(i);
            if (getDurationSeconds(originAudioTrack) > videoDuration * 1.3) { // Audio duration比video duration的1.3倍还长，出问题了
                int[] samples = getClipSamples(originAudioTrack, 0, videoDuration);
                if (samples[0] >= 0 && samples[1] >= 0) {
                    croppedAudioTracks.add(new CroppedTrack(originAudioTrack, samples[0], samples[1]));
                } else {
                    croppedAudioTracks.add(originAudioTrack);
                }
            } else {
                croppedAudioTracks.add(originAudioTrack);
            }
        }

        Movie result = new Movie();

        if (videoTracks.size() > 0) {
            Track videoTrack = new AppendTrack(videoTracks.toArray(new Track[videoTracks.size()]));
            if (portRotation) {
                videoTrack.getTrackMetaData().setMatrix(Matrix.ROTATE_90);
            }
            result.addTrack(videoTrack);
        }
        if (croppedAudioTracks.size() > 0) {
            result.addTrack(new AppendTrack(croppedAudioTracks.toArray(new Track[croppedAudioTracks.size()])));
        }

        Container out = new DefaultMp4Builder().build(result);

        FileChannel fc = new RandomAccessFile(outMoviePath, "rw").getChannel();
        out.writeContainer(fc);
        fc.close();
        for (Closeable closeable: closeables) {
            closeable.close();
        }
    }

    public static void writeRandomMetadata(String videoFilePath, String title) throws IOException {

        File videoFile = new File(videoFilePath);
        if (!videoFile.exists()) {
            throw new FileNotFoundException("File " + videoFilePath + " not exists");
        }

        if (!videoFile.canWrite()) {
            throw new IllegalStateException("No write permissions to file " + videoFilePath);
        }
        IsoFile isoFile = new IsoFile(videoFilePath);

        MovieBox moov = isoFile.getBoxes(MovieBox.class).get(0);
        FreeBox freeBox = findFreeBox(moov);

        boolean correctOffset = needsOffsetCorrection(isoFile);
        long sizeBefore = moov.getSize();
        long offset = 0;
        for (Box box : isoFile.getBoxes()) {
            if ("moov".equals(box.getType())) {
                break;
            }
            offset += box.getSize();
        }

        // Create structure or just navigate to Apple List Box.
        UserDataBox userDataBox;
        if ((userDataBox = Path.getPath(moov, "udta")) == null) {
            userDataBox = new UserDataBox();
            moov.addBox(userDataBox);
        }
        MetaBox metaBox;
        if ((metaBox = Path.getPath(userDataBox, "meta")) == null) {
            metaBox = new MetaBox();
            HandlerBox hdlr = new HandlerBox();
            hdlr.setHandlerType("mdir");
            metaBox.addBox(hdlr);
            userDataBox.addBox(metaBox);
        }
        AppleItemListBox ilst;
        if ((ilst = Path.getPath(metaBox, "ilst")) == null) {
            ilst = new AppleItemListBox();
            metaBox.addBox(ilst);

        }
        if (freeBox == null) {
            freeBox = new FreeBox(128 * 1024);
            metaBox.addBox(freeBox);
        }
        // Got Apple List Box

        AppleNameBox nam;
        if ((nam = Path.getPath(ilst, "©nam")) == null) {
            nam = new AppleNameBox();
        }
        nam.setDataCountry(0);
        nam.setDataLanguage(0);
        nam.setValue(title);
        ilst.addBox(nam);

        long sizeAfter = moov.getSize();
        long diff = sizeAfter - sizeBefore;
        // This is the difference of before/after

        // can we compensate by resizing a Free Box we have found?
        if (freeBox.getData().limit() > diff) {
            // either shrink or grow!
            freeBox.setData(ByteBuffer.allocate((int) (freeBox.getData().limit() - diff)));
            sizeAfter = moov.getSize();
            diff = sizeAfter - sizeBefore;
        }
        if (correctOffset && diff != 0) {
            correctChunkOffsets(moov, diff);
        }
        BetterByteArrayOutputStream baos = new BetterByteArrayOutputStream();
        moov.getBox(Channels.newChannel(baos));
        isoFile.close();
        FileChannel fc;
        if (diff != 0) {
            // this is not good: We have to insert bytes in the middle of the file
            // and this costs time as it requires re-writing most of the file's data
            fc = splitFileAndInsert(videoFile, offset, sizeAfter - sizeBefore);
        } else {
            // simple overwrite of something with the file
            fc = new RandomAccessFile(videoFile, "rw").getChannel();
        }
        fc.position(offset);
        fc.write(ByteBuffer.wrap(baos.getBuffer(), 0, baos.size()));
        fc.close();
    }

    private static FreeBox findFreeBox(Container c) {
        for (Box box : c.getBoxes()) {
            System.err.println(box.getType());
            if (box instanceof FreeBox) {
                return (FreeBox) box;
            }
            if (box instanceof Container) {
                FreeBox freeBox = findFreeBox((Container) box);
                if (freeBox != null) {
                    return freeBox;
                }
            }
        }
        return null;
    }

    private static boolean needsOffsetCorrection(IsoFile isoFile) {
        if (Path.getPath(isoFile, "moov[0]/mvex[0]") != null) {
            // Fragmented files don't need a correction
            return false;
        } else {
            // no correction needed if mdat is before moov as insert into moov want change the offsets of mdat
            for (Box box : isoFile.getBoxes()) {
                if ("moov".equals(box.getType())) {
                    return true;
                }
                if ("mdat".equals(box.getType())) {
                    return false;
                }
            }
            throw new RuntimeException("I need moov or mdat. Otherwise all this doesn't make sense");
        }
    }

    private static void correctChunkOffsets(MovieBox movieBox, long correction) {
        List<ChunkOffsetBox> chunkOffsetBoxes = Path.getPaths((Box) movieBox, "trak/mdia[0]/minf[0]/stbl[0]/stco[0]");
        if (chunkOffsetBoxes.isEmpty()) {
            chunkOffsetBoxes = Path.getPaths((Box) movieBox, "trak/mdia[0]/minf[0]/stbl[0]/st64[0]");
        }
        for (ChunkOffsetBox chunkOffsetBox : chunkOffsetBoxes) {
            long[] cOffsets = chunkOffsetBox.getChunkOffsets();
            for (int i = 0; i < cOffsets.length; i++) {
                cOffsets[i] += correction;
            }
        }
    }

    private static FileChannel splitFileAndInsert(File f, long pos, long length) throws IOException {
        FileChannel read = new RandomAccessFile(f, "r").getChannel();
        File tmp = File.createTempFile("ChangeMetaData", "splitFileAndInsert");
        FileChannel tmpWrite = new RandomAccessFile(tmp, "rw").getChannel();
        read.position(pos);
        tmpWrite.transferFrom(read, 0, read.size() - pos);
        read.close();
        FileChannel write = new RandomAccessFile(f, "rw").getChannel();
        write.position(pos + length);
        tmpWrite.position(0);
        long transferred = 0;
        while ((transferred += tmpWrite.transferTo(0, tmpWrite.size() - transferred, write)) != tmpWrite.size()) {
            System.out.println(transferred);
        }
        System.out.println(transferred);
        tmpWrite.close();
        tmp.delete();
        return write;
    }

    private static class BetterByteArrayOutputStream extends ByteArrayOutputStream {
        byte[] getBuffer() {
            return buf;
        }
    }

    public static double getDurationSeconds(String filePath) throws IOException {
        IsoFile isoFile = new IsoFile(filePath);
        return ((double) isoFile.getMovieBox().getMovieHeaderBox().getDuration())
                / isoFile.getMovieBox().getMovieHeaderBox().getTimescale();
    }

    private static double getDurationSeconds(Track track) {
        return track.getDuration() / (double) track.getTrackMetaData().getTimescale();
    }

    private static Track findTrack(List<Track> tracks, String trackHandlerKey) {
        for (Track track: tracks) {
            if (track.getHandler().equals(trackHandlerKey)) {
                return track;
            }
        }
        return null;
    }

    public static int getVideoRotation(String filePath) throws IOException {
        DataSource dataSource = new FileDataSourceImpl(new File(filePath));
        Track videoTrack = findTrack(MovieCreator.build(dataSource).getTracks(), VIDEO_TRACK_HANDLER_KEY);
        if (videoTrack == null) {
            return 0;
        }
        Matrix matrix = videoTrack
                .getTrackMetaData()
                .getMatrix();
        dataSource.close();
        if (matrix.equals(Matrix.ROTATE_0)) {
            return 0;
        } else if (matrix.equals(Matrix.ROTATE_90)) {
            return 90;
        } else if (matrix.equals(Matrix.ROTATE_180)) {
            return 180;
        } else if (matrix.equals(Matrix.ROTATE_270)) {
            return 270;
        } else {
            return 0;
        }
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
}
