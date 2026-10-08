package com.eritv.app;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy;
import androidx.media3.ui.PlayerView;

/**
 * EriTV's only screen. No menus or channel banners.
 *
 * Live HLS segments are ephemeral: the player must not be continually reset
 * merely because it is temporarily buffering. The publisher's sliding live
 * window, not the configured maxBufferMs, limits how far ahead we can buffer.
 */
public class MainActivity extends AppCompatActivity {
    private static final String TAG = "EriTV";
    private static final String STREAM =
            "https://jmc-live.ercdn.net/eritreatv/eritreatv.m3u8";

    private static final long STALL_TIMEOUT_MS = 90_000L;
    private static final long BUFFERING_HINT_MS = 8_000L;
    private static final long RETRY_WINDOW_MS = 10 * 60_000L;
    private static final long HEALTHY_RESET_MS = 30_000L;
    private static final long WATCH_INTERVAL_MS = 5_000L;
    private static final long[] RETRY_DELAYS_MS = {
            3_000L, 5_000L, 8_000L, 13_000L, 20_000L, 30_000L
    };

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ExoPlayer player;
    private View pill;
    private TextView status;

    private long bufferingSinceMs;
    private long lastProgressMs;
    private long previousPositionMs;
    private long firstFailureMs;
    private long healthySinceMs;
    private int restartAttempts;
    private boolean reconnectScheduled;
    private boolean retriesExpired;

    private final Runnable reconnectTask = () -> {
        reconnectScheduled = false;
        if (player == null || retriesExpired || isFinishing() || isDestroyed()) return;

        // A transient network error may have resolved during the delay.
        // Never restart a healthy, playing channel.
        if (player.isPlaying()) {
            recovered();
            return;
        }
        if (retryWindowExpired(SystemClock.elapsedRealtime())) {
            markOffline();
            return;
        }

        long now = SystemClock.elapsedRealtime();
        Log.w(TAG, "Restarting failed EriTV stream (attempt " + restartAttempts + ")");
        bufferingSinceMs = now;
        lastProgressMs = now;
        previousPositionMs = 0L;

        // Only perform a full player restart for an actual failure/stall.
        player.stop();
        player.setMediaItem(MediaItem.fromUri(STREAM));
        player.prepare();
        player.play();
        // No 8-second forced restart! Initial live buffering can take longer.
    };

    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            if (player == null || isFinishing() || isDestroyed()) return;

            long now = SystemClock.elapsedRealtime();
            int state = player.getPlaybackState();
            long position = player.getCurrentPosition();

            if (player.isPlaying()) {
                // Live-window updates can cause the position to move backward.
                if (position > previousPositionMs + 250
                        || position < previousPositionMs - 1000) {
                    previousPositionMs = position;
                    lastProgressMs = now;
                    recovered();
                    if (healthySinceMs == 0L) healthySinceMs = now;
                    if (now - healthySinceMs >= HEALTHY_RESET_MS) {
                        firstFailureMs = 0L;
                        restartAttempts = 0;
                    }
                } else if (now - lastProgressMs >= STALL_TIMEOUT_MS) {
                    requestReconnect("Frozen picture");
                }
            } else if (state == Player.STATE_BUFFERING) {
                healthySinceMs = 0L;
                if (bufferingSinceMs == 0L) bufferingSinceMs = now;
                long bufferingFor = now - bufferingSinceMs;
                if (bufferingFor >= BUFFERING_HINT_MS && !reconnectScheduled
                        && !retriesExpired) showPill("Buffering…");
                if (bufferingFor >= STALL_TIMEOUT_MS) {
                    requestReconnect("Buffering timeout");
                }
            } else if (state == Player.STATE_ENDED) {
                healthySinceMs = 0L;
                requestReconnect("Unexpected stream end");
            }

            handler.postDelayed(this, WATCH_INTERVAL_MS);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        pill = findViewById(R.id.retryPill);
        status = findViewById(R.id.retryText);

        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent("EriTV/1.2 AndroidTV")
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(25_000);

        // Preserve the EriTV v1.1 buffer settings that users found stable.
        DefaultLoadControl buffers = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(15_000, 120_000, 5_000, 10_000)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build();

        // Let Media3 recover transient HLS playlist/segment failures itself
        // before treating an error as fatal and restarting the whole channel.
        DefaultMediaSourceFactory sources = new DefaultMediaSourceFactory(http)
                .setLoadErrorHandlingPolicy(new DefaultLoadErrorHandlingPolicy(6));

        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(sources)
                .setLoadControl(buffers)
                .build();
        ((PlayerView) findViewById(R.id.player)).setPlayer(player);

        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_BUFFERING) {
                    if (bufferingSinceMs == 0L) {
                        bufferingSinceMs = SystemClock.elapsedRealtime();
                    }
                } else {
                    bufferingSinceMs = 0L;
                }
            }

            @Override public void onPlayerError(PlaybackException error) {
                Log.w(TAG, "Playback error " + error.errorCodeName, error);
                healthySinceMs = 0L;

                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    // Android's recommended fix: rejoin the current live
                    // window instead of repeatedly requesting expired segments.
                    player.seekToDefaultPosition();
                    player.prepare();
                    player.play();
                    return;
                }
                requestReconnect("Stream error");
            }

            @Override public void onIsPlayingChanged(boolean isPlaying) {
                if (isPlaying) {
                    lastProgressMs = SystemClock.elapsedRealtime();
                    previousPositionMs = player.getCurrentPosition();
                    if (healthySinceMs == 0L) healthySinceMs = lastProgressMs;
                    recovered(); // Immediately remove spinner and pending restart.
                } else {
                    healthySinceMs = 0L;
                }
            }
        });

        long now = SystemClock.elapsedRealtime();
        lastProgressMs = now;
        bufferingSinceMs = now;
        player.setMediaItem(MediaItem.fromUri(STREAM));
        player.prepare();
        player.play();
        handler.postDelayed(watchdog, WATCH_INTERVAL_MS);
    }

    private void showPill(String message) {
        status.setText(message);
        pill.setVisibility(View.VISIBLE);
    }

    private void recovered() {
        if (reconnectScheduled) {
            handler.removeCallbacks(reconnectTask);
            reconnectScheduled = false;
        }
        if (!retriesExpired) pill.setVisibility(View.GONE);
    }

    private boolean retryWindowExpired(long now) {
        return firstFailureMs != 0L && now - firstFailureMs >= RETRY_WINDOW_MS;
    }

    private void requestReconnect(String reason) {
        if (player == null || reconnectScheduled || retriesExpired) return;
        long now = SystemClock.elapsedRealtime();
        if (firstFailureMs == 0L) firstFailureMs = now;
        if (retryWindowExpired(now)) {
            markOffline();
            return;
        }

        long delay = RETRY_DELAYS_MS[
                Math.min(restartAttempts, RETRY_DELAYS_MS.length - 1)];
        restartAttempts++;
        reconnectScheduled = true;
        healthySinceMs = 0L;
        showPill("Reconnecting…");
        Log.w(TAG, reason + "; will retry in " + delay + "ms");
        handler.postDelayed(reconnectTask, delay);
    }

    private void markOffline() {
        handler.removeCallbacks(reconnectTask);
        reconnectScheduled = false;
        retriesExpired = true;
        showPill("Offline · OK to retry");
        Log.w(TAG, "Could not restore EriTV after 10 minutes");
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        // No menu. OK only performs a manual reconnect after a long outage.
        if (retriesExpired && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER)) {
            retriesExpired = false;
            firstFailureMs = 0L;
            restartAttempts = 0;
            healthySinceMs = 0L;
            requestReconnect("Manual retry");
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (player != null) {
            ((PlayerView) findViewById(R.id.player)).setPlayer(null);
            player.release();
            player = null;
        }
        super.onDestroy();
    }
}
