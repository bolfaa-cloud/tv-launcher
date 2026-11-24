package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.io.File;

/**
 * Анис ТВ: a looping, silent video behind Home or the clock screensaver, from wallpaper.mp4 in the theme directory
 * (written by the theme tools like the pictures). Cropped to fill the screen like the pictures and darkened by
 * the same dim. It stays invisible until its first frame is drawn, so the picture under it shows until then and
 * keeps showing if the video cannot be played.
 */
final class VideoWallpaper implements TextureView.SurfaceTextureListener {
    static final String FILE_NAME = "wallpaper.mp4";

    private final TextureView texture;
    private final File file;
    private final Runnable onFirstFrame;
    private MediaPlayer player;
    private Surface surface;
    private boolean prepared;
    private boolean wanted;
    private boolean shown;
    private int videoWidth, videoHeight;

    /** The theme's video, or null when there is none. */
    static File file(Context ctx) {
        File dir = Config.dir(ctx);
        if (dir == null) return null;
        File f = new File(dir, FILE_NAME);
        return f.isFile() && f.length() > 0 ? f : null;
    }

    /**
     * Adds the video and its shade to {@code parent} at {@code index}, full size. {@code onFirstFrame} runs on the
     * main thread once the video is on screen, for the caller to let go of whatever it showed under it.
     */
    static VideoWallpaper attach(ViewGroup parent, int index, File file, int dimPercent, Runnable onFirstFrame) {
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
        VideoWallpaper video = new VideoWallpaper(texture, file, onFirstFrame);
        texture.setSurfaceTextureListener(video);
        texture.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> video.crop());
        return video;
    }

    private VideoWallpaper(TextureView texture, File file, Runnable onFirstFrame) {
        this.texture = texture;
        this.file = file;
        this.onFirstFrame = onFirstFrame;
    }

    /** Plays while the screen it belongs to is visible. */
    void start() {
        wanted = true;
        if (player == null) {
            open();
        } else if (prepared && !player.isPlaying()) {
            player.start();
        }
    }

    /** Pauses while the screen is hidden; the decoder stays ready for a quick start(). */
    void stop() {
        wanted = false;
        if (player != null && prepared && player.isPlaying()) player.pause();
    }

    /** Lets go of the decoder (memory pressure, or the screen going away); start() opens it again. */
    void release() {
        if (player != null) {
            player.release();
            player = null;
        }
        prepared = false;
    }

    private void open() {
        if (surface == null || player != null) return;
        MediaPlayer mp = new MediaPlayer();
        try {
            mp.setSurface(surface);
            mp.setDataSource(file.getPath());
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
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START && !shown) {
                    shown = true;
                    texture.animate().alpha(1f).setDuration(600).withEndAction(onFirstFrame).start();
                }
                return false;
            });
            mp.setOnErrorListener((p, what, extra) -> {
                release();
                return true;
            });
            player = mp;
            mp.prepareAsync();
        } catch (Exception | Error failure) {
            mp.release();
            player = null;
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
        if (wanted) open();
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
