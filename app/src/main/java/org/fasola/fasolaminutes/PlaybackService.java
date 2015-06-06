package org.fasola.fasolaminutes;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Binder;
import android.os.IBinder;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;
import android.widget.MediaController;
import android.widget.RemoteViews;

import java.io.IOException;

/**
 * A singleton foreground service for music playback
 */
public class PlaybackService extends Service
        implements MediaPlayer.OnPreparedListener,
            MediaPlayer.OnCompletionListener,
            MediaPlayer.OnErrorListener,
            MediaController.MediaPlayerControl {

    private static final String TAG = "PlaybackService";

    /** Enqueue songs */
    public static final String ACTION_ENQUEUE_MEDIA = "org.fasola.fasolaminutes.media.ENQUEUE";
    /** Clear playlist, enqueue songs, and start playing from the top */
    public static final String ACTION_PLAY_MEDIA = "org.fasola.fasolaminutes.media.PLAY";
    /** Extra is {@code long leadId} */
    public static final String EXTRA_LEAD_ID = "org.fasola.fasolaminutes.media.EXTRA_LEAD_ID";
    /** Extra is {@code String url} */
    public static final String EXTRA_URL = "org.fasola.fasolaminutes.media.EXTRA_URL";
    /** Extra is {@code String[] urls} */
    public static final String EXTRA_URL_LIST = "org.fasola.fasolaminutes.media.EXTRA_URL_LIST";

    /** Play */
    public static final String ACTION_PLAY = "org.fasola.fasolaminutes.action.PLAY";
    /** Pause */
    public static final String ACTION_PAUSE = "org.fasola.fasolaminutes.action.PAUSE";
    /** Play/Pause toggle */
    public static final String ACTION_PLAY_PAUSE = "org.fasola.fasolaminutes.action.PLAY_PAUSE";
    /** Play the next song */
    public static final String ACTION_NEXT = "org.fasola.fasolaminutes.action.NEXT";
    /** Play the previous song */
    public static final String ACTION_PREVIOUS = "org.fasola.fasolaminutes.action.PREVIOUS";
    /** Stop playback */
    public static final String ACTION_STOP = "org.fasola.fasolaminutes.action.STOP";

    /** Broadcast sent when the {@link MediaPlayer} is prepared */
    public static final String BROADCAST_PREPARED = "org.fasola.fasolaminutes.mediaBroadcast.PREPARED";
    /** Broadcast sent when playback (of a single song) is completed */
    public static final String BROADCAST_COMPLETED = "org.fasola.fasolaminutes.mediaBroadcast.COMPLETED";
    /** Broadcast sent on {@link MediaPlayer} error */
    public static final String BROADCAST_ERROR = "org.fasola.fasolaminutes.mediaBroadcast.ERROR";

    MediaPlayer mMediaPlayer;
    boolean mIsPrepared;
    boolean mShouldPlay; // Should we play the song once it is prepared?
    Playlist mPlaylist;
    NotificationManager mNotificationManager;
    Notification mNotification;
    private static final int NOTIFICATION_ID = 1;

    // Singleton
    static PlaybackService mInstance;

    /**
     * Returns the {@link PlaybackService} singleton or {@code null} if it is not running
     *
     * <p>Call {@link Context#startService(Intent)} to create a PlaybackService
     */
    public static PlaybackService getInstance() {
        return mInstance;
    }

    //region Lifecycle functions
    //---------------------------------------------------------------------------------------------
    @Override
    public void onCreate() {
        super.onCreate();
        mInstance = this;
        mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        mPlaylist = Playlist.getInstance();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent.getAction();
        if (intent.getAction() == null)
            return START_STICKY;
        // Enqueue/play
        else if (action.equals(ACTION_PLAY_MEDIA) || action.equals(ACTION_ENQUEUE_MEDIA)) {
            boolean play = false;
            if (action.equals(ACTION_PLAY_MEDIA)) {
                mPlaylist.clear();
                play = true;
            }
            if (intent.hasExtra(EXTRA_LEAD_ID))
                enqueueLead(play, C.SongLeader.leadId, intent.getLongExtra(EXTRA_LEAD_ID, -1));
            else if (intent.hasExtra(EXTRA_URL))
                enqueueLead(play, C.SongLeader.audioUrl, intent.getStringExtra(EXTRA_URL));
            else if (intent.hasExtra(EXTRA_URL_LIST))
                enqueueLead(play, C.SongLeader.audioUrl, intent.getStringArrayExtra(EXTRA_URL_LIST));
        }
        // Controls
        else if (intent.getAction().equals(ACTION_PLAY)) {
            start();
        }
        else if (intent.getAction().equals(ACTION_PAUSE)) {
            pause();
        }
        else if (intent.getAction().equals(ACTION_PLAY_PAUSE)) {
            if (isPlaying())
                pause();
            else
                start();
        }
        else if (intent.getAction().equals(ACTION_NEXT)) {
            prepareNext();
        }
        else if (intent.getAction().equals(ACTION_PREVIOUS)) {
            preparePrevious();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
       if (mMediaPlayer != null)
           mMediaPlayer.release();
       mIsPrepared = false;
       mInstance = null;
    }

    private final IBinder mBinder = new MediaBinder();

    public class MediaBinder extends Binder {
        PlaybackService getService() {
            return PlaybackService.this;
        }
    }
    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }
    //---------------------------------------------------------------------------------------------
    //endregion


    /** Returns {@code true} if this service is running */
    public static boolean isRunning() {
        return mInstance != null;
    }

    /** Returns {@code true} if the {@link MediaPlayer} is prepared and ready to play */
    public boolean isPrepared() {
        return mIsPrepared;
    }

    // TODO: create a MediaPlayerControl class that constrols this PlaybackService
    // TODO: remove this method and delegate to the MediaPlayerControl
    public MediaPlayer getMediaPlayer () {
        return mMediaPlayer;
    }

    // TODO: use Playlist singleton
    public Playlist getPlaylist() {
        return mPlaylist;
    }

    // region MediaPlayerControl overrides
    //---------------------------------------------------------------------------------------------
    @Override
    public void start() {
        mShouldPlay = true;
        ensurePlayer().start();
        updateNotification();
    }

    @Override
    public void pause() {
        mShouldPlay = false;
        if (isPrepared()) {
            ensurePlayer().pause();
            updateNotification();
        }
    }

    @Override
    public int getDuration() {
        return isPrepared() ? ensurePlayer().getDuration() : 0;
    }

    @Override
    public int getCurrentPosition() {
        return isPrepared() ? ensurePlayer().getCurrentPosition() : 0;
    }

    @Override
    public void seekTo(int i) {
        if (isPrepared()) {
            ensurePlayer().seekTo(i);
            updateNotification();
        }
    }

    @Override
    public boolean isPlaying() {
        return isPrepared() && getMediaPlayer().isPlaying();
    }

    @Override
    public int getBufferPercentage() {
        return 0;
    }

    @Override
    public boolean canPause() {
        return true;
    }

    @Override
    public boolean canSeekBackward() {
        return isPrepared();
    }

    @Override
    public boolean canSeekForward() {
        return isPrepared();
    }

    @Override
    public int getAudioSessionId() {
        return 0;
    }
    //---------------------------------------------------------------------------------------------
    //endregion

    /**
     * Prepares the next song in the playlist
     *
     * @see #prepare()
     */
    public boolean prepareNext() {
        Playlist.getInstance().moveToNext();
        return prepare();
    }

    /**
     * Prepares the previous song in the playlist
     *
     * @see #prepare()
     */
    public boolean preparePrevious() {
        Playlist.getInstance().moveToPrevious();
        return prepare();
    }

    /**
     * Prepares the current song in the playlist
     *
     * <p>Unless pause() is called afterwards, the song will start playing once it is prepared
     *
     * @return {@code true} if there is a song to play or {@code false} if the playlist is empty
     * @see #onPrepared(MediaPlayer)
     */
    public boolean prepare() {
        // Get the song
        Playlist.Song song = Playlist.getInstance().getCurrent();
        if (song == null)
            return false;
        // Prepare player
        mIsPrepared = false;
        ensurePlayer();
        mMediaPlayer.stop();
        mMediaPlayer.reset();
        try {
            mMediaPlayer.setDataSource(song.url);
        } catch (IOException e) {
            // TODO: something useful... a broadcast?
            Log.e(TAG, "IOException with url: " + song.url);
        }
        mShouldPlay = true;
        mMediaPlayer.prepareAsync();
        updateNotification();
        return true;
    }

    /**
     * Enqueues and optionally starts playback of one or more songs
     *
     * <p>This method queries (async) the database for songs and adds them to the playlist
     *
     * @param start  {@code true} to start playback
     * @param column {@link SQL.Column} or {@code String} column name for a WHERE clause
     * @param args   values for the IN predicate for a WHERE clause
     * @see Playlist
     * @see Playlist#getSongQuery(Object, Object...)
     * @see Playlist#addAll(Cursor)
     */
    public void enqueueLead(final boolean start, Object column, Object... args) {
        MinutesLoader loader = new MinutesLoader(Playlist.getSongQuery(column, args));
        loader.startLoading(new MinutesLoader.FinishedCallback() {
            @Override
            public void onLoadFinished(Cursor cursor) {
                mPlaylist.addAll(cursor);
                if (start)
                    prepareNext();
            }
        });
    }

    /**
     * Constructs a MediaPlayer if necessary
     *
     * @return mMediaPlayer for simple uses
     */
    private MediaPlayer ensurePlayer() {
        if (mMediaPlayer == null) {
            mMediaPlayer = new MediaPlayer();
            mMediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
            mMediaPlayer.setOnPreparedListener(this);
            mMediaPlayer.setOnCompletionListener(this);
            mMediaPlayer.setOnErrorListener(this);
        }
        return mMediaPlayer;
    }

    /** Creates a new notification or returns an existing notification
     *
     * @return {@link Notification} that controls playback
     */
    private Notification getNotification() {
        if (mNotification != null)
            return mNotification;
        // RemoteViews
        RemoteViews remote = new RemoteViews(getPackageName(), R.layout.notification_playback);
        Intent playIntent = new Intent(this, PlaybackService.class);
        playIntent.setAction(ACTION_PLAY_PAUSE);
        remote.setOnClickPendingIntent(R.id.play_pause, PendingIntent.getService(
                this, 0, playIntent, PendingIntent.FLAG_UPDATE_CURRENT
        ));
        Intent nextIntent = new Intent(this, PlaybackService.class);
        nextIntent.setAction(ACTION_NEXT);
        remote.setOnClickPendingIntent(R.id.next, PendingIntent.getService(
                this, 0, nextIntent, PendingIntent.FLAG_UPDATE_CURRENT
        ));
        // Main Notification Intent
        Intent intent = new Intent(this, PlaylistActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT
        );
        // Build Notification
        return new Notification.Builder(getApplicationContext())
            .setSmallIcon(R.drawable.ic_stat_fasola)
            .setContent(remote)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .getNotification(); // build() was added in API 16
    }

    /** Updates the {@link Notification} with the current playing status
     *
     * <p>If no notification exists this service is in the background.  In this case,
     * {@link #startForeground(int, Notification)} is called, and a new notificaiton is created.
     *
     * @see #getNotification()
     */
    private void updateNotification() {
        Playlist.Song song = mPlaylist.getCurrent();
        Notification notification = getNotification();
        // Update content
        RemoteViews remote = notification.contentView;
        remote.setTextViewText(R.id.title, song.name);
        remote.setTextViewText(R.id.singing, song.singing);
        remote.setImageViewResource(R.id.play_pause, mMediaPlayer.isPlaying()
                ? android.R.drawable.ic_media_pause
                : android.R.drawable.ic_media_play);
        // Show or update notification
        if (mNotification == null) {
            Log.v(TAG, "Starting foreground service");
            startForeground(NOTIFICATION_ID, notification);
            mNotification = notification;
        } else
            mNotificationManager.notify(NOTIFICATION_ID, mNotification);
    }

    //region MediaPlayer Callbacks
    //---------------------------------------------------------------------------------------------
    @Override
    public void onPrepared(MediaPlayer mp) {
        Log.v(TAG, "Prepared; starting playback");
        mIsPrepared = true;
        if (mShouldPlay)
            start();
        LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent(BROADCAST_PREPARED));
    }

    @Override
    public void onCompletion(MediaPlayer mp) {
        Log.v(TAG, "Complete");
        mIsPrepared = false;
        LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent(BROADCAST_COMPLETED));
        // Start the next
        if (! prepareNext()) {
            Log.v(TAG, "End of playlist: stopping service");
            stopForeground(true);
            mNotification = null;
            stopSelf();
        }
    }

    @Override
    public boolean onError(MediaPlayer mp, int what, int extra) {
        Log.e(TAG, "Error: " + String.valueOf(what));
        mIsPrepared = false;
        LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent(BROADCAST_ERROR));
        return false;
    }
    //---------------------------------------------------------------------------------------------
    //endregion
}