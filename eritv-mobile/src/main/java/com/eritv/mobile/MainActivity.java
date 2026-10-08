package com.eritv.mobile;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;

/**
 * Dedicated, single-channel mobile edition of the working EriTV Android TV app.
 * No menus or player controls: launch straight into live TV.
 */
public class MainActivity extends AppCompatActivity {
    private static final String STREAM =
            "https://jmc-live.ercdn.net/eritreatv/eritreatv.m3u8";

    // Intentionally identical to EriTV TV v1.1.1's successful settings.
    private static final long RETRY_LIMIT_MS = 600_000;
    private static final long STALL_MS = 90_000;
    private static final long WATCH_INTERVAL_MS = 5_000;

    private ExoPlayer player;
    private View retryPill;
    private TextView retryText;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private long retryStartMs;
    private long lastPositionMs;
    private long lastAdvanceMs;
    private boolean retrying;

    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            if (player == null) return;

            final long now = System.currentTimeMillis();
            final long position = player.getCurrentPosition();
            if (player.isPlaying() && position > lastPositionMs + 500) {
                lastPositionMs = position;
                lastAdvanceMs = now;
                clearRetry();
            } else if (player.getPlayWhenReady()
                    && lastAdvanceMs > 0
                    && now - lastAdvanceMs >= STALL_MS) {
                retry();
            }
            handler.postDelayed(this, WATCH_INTERVAL_MS);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);
        retryPill = findViewById(R.id.retryPill);
        retryText = findViewById(R.id.retryText);
        hideSystemUi();

        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent("EriTV/1.1 AndroidMobile")
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(12_000)
                .setReadTimeoutMs(15_000);

        DefaultLoadControl load = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(15_000, 120_000, 5_000, 10_000)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build();

        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(http))
                .setLoadControl(load)
                .build();

        // Respect incoming calls and other phone audio (Android audio focus).
        AudioAttributes audio = new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build();
        player.setAudioAttributes(audio, true);

        PlayerView playerView = findViewById(R.id.player);
        playerView.setUseController(false);
        playerView.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override public void onPlayerError(PlaybackException error) {
                retry();
            }

            @Override public void onIsPlayingChanged(boolean playing) {
                if (playing) {
                    lastPositionMs = player.getCurrentPosition();
                    lastAdvanceMs = System.currentTimeMillis();
                    clearRetry();
                }
            }
        });
    }

    @Override protected void onStart() {
        super.onStart();
        if (player == null) return;
        // Pauses while the phone app is in the background, and resumes on return.
        lastAdvanceMs = System.currentTimeMillis();
        lastPositionMs = player.getCurrentPosition();
        retryStartMs = 0;
        retrying = false;
        retryPill.setVisibility(View.GONE);
        if (player.getPlaybackState() == Player.STATE_IDLE) {
            startStream();
        } else {
            player.play();
        }
        handler.removeCallbacks(watchdog);
        handler.postDelayed(watchdog, WATCH_INTERVAL_MS);
    }

    @Override protected void onStop() {
        handler.removeCallbacksAndMessages(null);
        retrying = false;
        if (player != null) player.pause();
        super.onStop();
    }

    private void startStream() {
        if (player == null) return;
        lastAdvanceMs = System.currentTimeMillis();
        lastPositionMs = 0;
        player.setMediaItem(MediaItem.fromUri(STREAM));
        player.prepare();
        player.play();
    }

    private void clearRetry() {
        if (player != null && player.isPlaying()) {
            retrying = false;
            retryStartMs = 0;
            retryPill.setVisibility(View.GONE);
        }
    }

    private void retry() {
        if (player == null || !player.getPlayWhenReady()) return;
        long now = System.currentTimeMillis();
        if (retryStartMs == 0) retryStartMs = now;
        if (now - retryStartMs >= RETRY_LIMIT_MS) {
            retrying = false;
            retryText.setText("Offline");
            retryPill.setVisibility(View.VISIBLE);
            return;
        }
        if (retrying) return;
        retrying = true;
        retryText.setText("Reconnecting…");
        retryPill.setVisibility(View.VISIBLE);
        handler.postDelayed(() -> {
            if (player == null || !player.getPlayWhenReady()) return;
            retrying = false;
            player.stop();
            startStream();
            handler.postDelayed(() -> {
                if (player != null && !player.isPlaying()) retry();
            }, 8_000);
        }, 3_000);
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (player != null) {
            player.release();
            player = null;
        }
        super.onDestroy();
    }
}
