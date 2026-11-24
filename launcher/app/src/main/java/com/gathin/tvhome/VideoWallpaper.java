package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Анис ТВ: looping, silent video wallpapers behind Home or the clock screensaver. The theme directory holds either a
 * collection in videos/ (rotated every wallpaper_rotation_minutes, like the pictures) or a single wallpaper.mp4.
 * Cropped to fill the screen like the pictures and darkened by the same dim. Invisible until a first frame is drawn,
 * so the picture under it shows until then, and keeps showing if no video can be played. Home and the screensaver
 * share the rotation's place through preferences, so the screensaver carries on with the video Home was showing.
 */
final class VideoWallpaper implements TextureView.SurfaceTextureListener {
    static final String FILE_NAME = "wallpaper.mp4";
    static final String COLLECTION = "videos";
    /** Bigger files are skipped: a TV decoder chokes on them and they are not wallpapers. */
    private static final long MAX_BYTES = 200L * 1024 * 1024;
    private static final long CHECK_MS = 15_000L;
    private static final String PREFS = "video_rotation";

    private final TextureView texture;
    private final List<File> files;
    private final long intervalMs;
    private final Runnable onFirstFrame;
    private final SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final Runnable check = this::rotateIfDue;
    private MediaPlayer player;
    private Surface surface;
    private File playing;
    private boolean prepared;
    private boolean wanted;
    private boolean firstFrameReported;
    private int videoWidth, videoHeight;

    /** The theme's videos: the videos/ collection by name, or the single wallpaper.mp4, or none. */
    static List<File> files(Context ctx) {
        File dir = Config.dir(ctx);
        if (dir == null) return Collections.emptyList();
        List<File> out = new ArrayList<>();
        File[] found = new File(dir, COLLECTION).listFiles((d, n) -> ThemeFiles.collectionVideo(n));
        if (found != null) {
            Arrays.sort(found);
            for (File f : found) if (f.isFile() && f.length() > 0 && f.length() <= MAX_BYTES) out.add(f);
        }
        if (out.isEmpty()) {
            File single = new File(dir, FILE_NAME);
            if (single.isFile() && single.length() > 0 && single.length() <= MAX_BYTES) out.add(single);
        }
        return out;
    }

    /**
     * Adds the video and its shade to {@code parent} at {@code index}, full size. {@code onFirstFrame} runs on the
     * main thread once a video is on screen, for the caller to let go of whatever it showed under it.
     */
    static VideoWallpaper attach(ViewGroup parent, int index, List<File> files, long intervalMs, int dimPercent,
                                 Runnable onFirstFrame) {
        Context ctx = parent.getContext();
        TextureView texture = new TextureView(ctx);
        texture.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        texture.setFocusable(false);
        texture.setAlpha(0f);
        parent.addView(texture, index, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        int dim = Math.max(0, Math.min(90, dimPercent));
        if (dim > 0) {
            View shade = new View(ctx);
            shade.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            shade.setFocusable(false);
            shade.setBackgroundColor((dim * 255 / 100) << 24);
            parent.addView(shade, index + 1, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        VideoWallpaper video = new VideoWallpaper(texture, files, intervalMs, onFirstFrame);
        texture.setSurfaceTextureListener(video);
        texture.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> video.crop());
        return video;
    }

    private VideoWallpaper(TextureView texture, List<File> files, long intervalMs, Runnable onFirstFrame) {
        this.texture = texture;
        this.files = new ArrayList<>(files);
        this.intervalMs = intervalMs;
        this.onFirstFrame = onFirstFrame;
        this.prefs = texture.getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Plays while the screen it belongs to is visible. */
    void start() {
        wanted = true;
        if (player == null) {
            open(due() ? next() : current());
        } else if (due()) {
            switchTo(next());
        } else if (prepared && !player.isPlaying()) {
            player.start();
        }
        scheduleCheck();
    }

    /** Pauses while the screen is hidden; the decoder stays ready for a quick start(). */
    void stop() {
        wanted = false;
        handler.removeCallbacks(check);
        if (player != null && prepared && player.isPlaying()) player.pause();
    }

    /** Lets go of the decoder (memory pressure, or the screen going away); start() opens it again. */
    void release() {
        handler.removeCallbacks(check);
        if (player != null) {
            player.release();
            player = null;
        }
        prepared = false;
    }

    // --- rotation -------------------------------------------------------------------------------------------

    /** The video the rotation is on (saved by Home or the screensaver), or the first one. */
    private File current() {
        String name = prefs.getString("name", "");
        for (File f : files) if (f.getName().equals(name)) return f;
        return files.isEmpty() ? null : files.get(0);
    }

    private boolean due() {
        if (files.size() < 2 || intervalMs <= 0) return false;
        long changed = prefs.getLong("changed", 0);
        return changed == 0 || System.currentTimeMillis() - changed >= intervalMs
                || System.currentTimeMillis() < changed;   // the clock went back
    }

    /** Another video than the current one, at random, so the collection does not always run in the same order. */
    private File next() {
        File now = current();
        if (files.size() < 2) return now;
        File pick;
        do {
            pick = files.get(random.nextInt(files.size()));
        } while (pick.equals(now));
        return pick;
    }

    private void remember(File f) {
        if (f == null) return;
        boolean same = f.getName().equals(prefs.getString("name", ""));
        SharedPreferences.Editor e = prefs.edit().putString("name", f.getName());
        if (!same || prefs.getLong("changed", 0) == 0) e.putLong("changed", System.currentTimeMillis());
        e.apply();
    }

    private void scheduleCheck() {
        handler.removeCallbacks(check);
        if (wanted && files.size() > 1 && intervalMs > 0) handler.postDelayed(check, CHECK_MS);
    }

    private void rotateIfDue() {
        if (!wanted) return;
        if (due()) switchTo(next());
        scheduleCheck();
    }

    /** Fades the current video out, then plays {@code f} in the same player; it fades in on its first frame. */
    private void switchTo(File f) {
        if (f == null || f.equals(playing) && player != null) {
            remember(f);
            return;
        }
        texture.animate().cancel();
        texture.animate().alpha(0f).setDuration(400).withEndAction(() -> {
            release();
            open(f);
            scheduleCheck();   // release() took the rotation check off
        }).start();
    }

    // --- playback -------------------------------------------------------------------------------------------

    private void open(File f) {
        if (f == null || surface == null || player != null) {
            if (f != null) playing = f;   // played once the surface is there
            return;
        }
        playing = f;
        remember(f);
        MediaPlayer mp = new MediaPlayer();
        try {
            mp.setSurface(surface);
            mp.setDataSource(f.getPath());
            mp.setLooping(true);
            mp.setVolume(0f, 0f);
            mp.setOnPreparedListener(p -> {
                prepared = true;
                if (wanted) p.start();
            });
            mp.setOnVideoSizeChangedListener((p, w, h) -> {
                videoWidth = w;
                videoHeight = h;
                crop();
            });
            mp.setOnInfoListener((p, what, extra) -> {
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    texture.animate().cancel();
                    texture.animate().alpha(1f).setDuration(600).withEndAction(() -> {
                        if (!firstFrameReported) {
                            firstFrameReported = true;
                            onFirstFrame.run();
                        }
                    }).start();
                }
                return false;
            });
            mp.setOnErrorListener((p, what, extra) -> {
                // Drop the broken one and try another; with none left the picture underneath stays.
                files.remove(f);
                release();
                if (!files.isEmpty() && wanted) open(next());
                return true;
            });
            player = mp;
            prepared = false;
            mp.prepareAsync();
        } catch (Exception | Error failure) {
            mp.release();
            player = null;
            files.remove(f);
            if (!files.isEmpty() && wanted) open(next());
        }
    }

    /** centerCrop for a TextureView: scale the video up until it covers the view, keep its proportions. */
    private void crop() {
        int vw = texture.getWidth(), vh = texture.getHeight();
        if (vw == 0 || vh == 0 || videoWidth == 0 || videoHeight == 0) return;
        float scale = Math.max(vw / (float) videoWidth, vh / (float) videoHeight);
        Matrix m = new Matrix();
        m.setScale(videoWidth * scale / vw, videoHeight * scale / vh, vw / 2f, vh / 2f);
        texture.setTransform(m);
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture st, int width, int height) {
        surface = new Surface(st);
        if (wanted) open(playing != null ? playing : current());
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture st, int width, int height) {
        crop();
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        release();
        if (surface != null) {
            surface.release();
            surface = null;
        }
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture st) {
    }
}
