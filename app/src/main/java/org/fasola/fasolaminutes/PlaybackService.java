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
import android.os.Handler;
import android.os.IBinder;
import android.support.v4.app.TaskStackBuilder;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;
import android.view.View;
import android.widget.MediaController;
import android.widget.RemoteViews;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * A singleton foreground service for music playback
 */
public class PlaybackService extends Service
        implements MediaPlayer.OnPreparedListener,
            MediaPlayer.OnCompletionListener,
            MediaPlayer.OnErrorListener,
            MediaController.MediaPlayerControl,
            AudioManager.OnAudioFocusChangeListener {

    private static final String TAG = "PlaybackService";
    private static final int ERROR_LIMIT = 100;
    private static final int ERROR_DELAY_MS = 500;

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
    /** Index of EXTRA_URL_LIST that should start playing */
    public static final String EXTRA_PLAY_INDEX = "org.fasola.fasolaminutes.media.EXTRA_INDEX";

    /** Play */
    public static final String ACTION_PLAY = "org.fasola.fasolaminutes.action.PLAY";
    /** Pause */
    public static final String ACTION_PAUSE = "org.fasola.fasolaminutes.action.PAUSE";
    /** Play/Pause toggle */
    public static final String ACTION_PLAY_PAUSE = "org.fasola.fasolaminutes.action.PLAY_PAUSE";
    /** Play the next song */
    public static final String ACTION_NEXT = "org.fasola.fasolaminutes.action.NEXT";
    /** Play the previous song */
    public static final String ACTION_PREV = "org.fasola.fasolaminutes.action.PREV";
    /** Stop playback */
    public static final String ACTION_STOP = "org.fasola.fasolaminutes.action.STOP";
    /** Close notification */
    public static final String ACTION_CLOSE = "org.fasola.fasolaminutes.action.STOP";

    /** Broadcast sent when the {@link MediaPlayer} is loading */
    public static final String BROADCAST_LOADING = "org.fasola.fasolaminutes.mediaBroadcast.LOADING";
    /** Broadcast sent when the {@link MediaPlayer} is prepared */
    public static final String BROADCAST_PREPARED = "org.fasola.fasolaminutes.mediaBroadcast.PREPARED";
    /** Broadcast sent when the {@link MediaPlayer} has started playing */
    public static final String BROADCAST_PLAYING = "org.fasola.fasolaminutes.mediaBroadcast.PLAYING";
    /** Broadcast sent when the {@link MediaPlayer} is paused */
    public static final String BROADCAST_PAUSED = "org.fasola.fasolaminutes.mediaBroadcast.PAUSED";
    /** Broadcast sent when playback (of a single song) is completed */
    public static final String BROADCAST_COMPLETED = "org.fasola.fasolaminutes.mediaBroadcast.COMPLETED";
    /** Broadcast sent on {@link MediaPlayer} error */
    public static final String BROADCAST_ERROR = "org.fasola.fasolaminutes.mediaBroadcast.ERROR";

    /** All broadcast actions. */
    public static final String[] BROADCAST_ALL = {
            BROADCAST_LOADING, BROADCAST_PREPARED, BROADCAST_PLAYING,
            BROADCAST_PAUSED, BROADCAST_COMPLETED, BROADCAST_ERROR
    };

    MediaPlayer mMediaPlayer;
    boolean mIsPrepared;
    boolean mShouldPlay; // Should we play the song once it is prepared?
    Playlist.Song mSong; // Prepared song
    int mErrorCount; // Number of sequential errors
    NotificationManager mNotificationManager;
    Notification mNotification;
    private static final int NOTIFICATION_ID = 1;
    AudioManager mAudioManager;

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
        mAudioManager = (AudioManager)getSystemService(Context.AUDIO_SERVICE);
        mObserver.registerBroadcastReceiver(getApplicationContext());
        mObserver.registerPlaylistObserver();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction(): null;
        if (action == null)
            return START_STICKY;
        // Enqueue/play
        else if (action.equals(ACTION_PLAY_MEDIA) || action.equals(ACTION_ENQUEUE_MEDIA)) {
            int play = -1;
            if (action.equals(ACTION_PLAY_MEDIA))
                play = intent.getIntExtra(EXTRA_PLAY_INDEX, 0);
            if (intent.hasExtra(EXTRA_LEAD_ID))
                enqueueLead(play, C.SongLeader.leadId, intent.getLongExtra(EXTRA_LEAD_ID, -1));
            else if (intent.hasExtra(EXTRA_URL))
                enqueueLead(play, C.SongLeader.audioUrl, intent.getStringExtra(EXTRA_URL));
            else if (intent.hasExtra(EXTRA_URL_LIST))
                enqueueLead(play, C.SongLeader.audioUrl, (Object[])intent.getStringArrayExtra(EXTRA_URL_LIST));
        }
        // Controls
        else if (action.equals(ACTION_PLAY)) {
            start();
        }
        else if (action.equals(ACTION_PAUSE)) {
            pause();
        }
        else if (action.equals(ACTION_PLAY_PAUSE)) {
            if (isPlaying())
                pause();
            else
                start();
        }
        else if (action.equals(ACTION_NEXT)) {
            Playlist.getInstance().moveToNext();
            prepare();
        }
        else if (action.equals(ACTION_PREV)) {
            Playlist.getInstance().moveToPrev();
            prepare();
        }
        else if (action.equals(ACTION_CLOSE)) {
            stop();
        }
        return START_STICKY;
    }

    @Override
    public void onAudioFocusChange(int focusChange) {
        if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            pause();
        }
        else if (focusChange == AudioManager.AUDIOFOCUS_GAIN) {
            start();
        }
        else if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
            stop();
        }
    }

    @Override
    public void onDestroy() {
        stop();
        mObserver.unregister();
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

    // region MediaPlayerControl overrides
    //---------------------------------------------------------------------------------------------
    @Override
    public void start() {
        mShouldPlay = true;
        if (isPrepared()) {
            int result = mAudioManager.requestAudioFocus(this,
                                                         AudioManager.STREAM_MUSIC,
                                                         AudioManager.AUDIOFOCUS_GAIN);
            if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                ensurePlayer().start();
                LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent(BROADCAST_PLAYING));
            }
        }
        else {
            if (Playlist.getInstance().getCurrent() == null)
                Playlist.getInstance().moveToFirst();
            prepare();
        }
        updateNotification();
    }

    @Override
    public void pause() {
        mShouldPlay = false;
        if (isPrepared()) {
            ensurePlayer().pause();
            updateNotification();
            LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent(BROADCAST_PAUSED));
        }
    }

    /**
     * Stop playback, release resources, and stop the service
     * This is not a MediaPlayerControl override
     */
    public void stop() {
        mSong = null;
        mAudioManager.abandonAudioFocus(this);
        if (mMediaPlayer != null) {
            mMediaPlayer.release();
            mMediaPlayer = null;
        }
        mShouldPlay = false;
        mIsPrepared = false;
        mNotification = null;
        stopForeground(true);
        stopSelf();
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
        return isPrepared() && mMediaPlayer.isPlaying();
    }

    public boolean isPaused() {
        return ! mShouldPlay;
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
     * Prepares and plays the current song in the playlist (async)
     *
     * <p>Unless pause() is called afterwards, the song will start playing once it is prepared
     *
     * @return {@code true} if there is a song to play or {@code false} if the playlist is empty
     * @see #onPrepared(MediaPlayer)
     */
    public boolean prepare() {
        // Get the song
        mSong = Playlist.getInstance().getCurrent();
        if (mSong == null)
            return false;
        // Prepare player
        LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent(BROADCAST_LOADING));
        mIsPrepared = false;
        ensurePlayer();
        mMediaPlayer.stop();
        mMediaPlayer.reset();
        try {
            mMediaPlayer.setDataSource(mSong.url);
        } catch (IOException e) {
            // TODO: something useful... a broadcast?
            Log.e(TAG, "IOException with url: " + mSong.url);
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
     * @param playIndex index in {@code args} to start playing, or -1 to ignore
     * @param column {@link SQL.Column} or {@code String} column name for a WHERE clause
     * @param args   values for the IN predicate for a WHERE clause
     * @see Playlist
     * @see Playlist#getSongQuery(Object, Object...)
     * @see Playlist#addAll(Cursor)
     */
    public void enqueueLead(final int playIndex, final SQL.Column column, final Object... args) {
        MinutesLoader loader = new MinutesLoader(Playlist.getSongQuery(column, args));
        loader.startLoading(new MinutesLoader.FinishedCallback() {
            @Override
            public void onLoadFinished(Cursor cursor) {
                // Add all songs in args to the playlist in order.
                // Doing this using SQL would require a temp table and an extra join.
                // Creating a map and sorting manually is a lot faster.

                // Map audioUrl or leadId to Playlist.Song
                if (! cursor.moveToFirst())
                    return;
                int colIndex = cursor.getColumnIndex(column.getKey());
                HashMap<Object, Playlist.Song> songMap = new HashMap<>();
                do {
                    songMap.put(cursor.getString(colIndex), new Playlist.Song(cursor));
                } while (cursor.moveToNext());

                // Put songs in order
                ArrayList<Playlist.Song> songs = new ArrayList<>(args.length);
                for (Object arg : args)
                    songs.add(songMap.get(arg));

                // Add to the playlist and start playback
                Playlist pl = Playlist.getInstance();
                if (playIndex > -1) {
                    // If we're just playing songs, remove all previous songs from the playlist
                    pl.replaceWith(songs);
                    pl.moveToPosition(playIndex);
                    prepare();
                }
                else {
                    pl.addAll(songs);
                }
            }
        });
    }

    /**
     * Constructs a MediaPlayer if necessary
     *
     * @return mMediaPlayer for chaining
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
    private Notification createNotification() {
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
        Intent closeIntent = new Intent(this, PlaybackService.class);
        closeIntent.setAction(ACTION_CLOSE);
        remote.setOnClickPendingIntent(R.id.close, PendingIntent.getService(
                this, 0, closeIntent, PendingIntent.FLAG_UPDATE_CURRENT
        ));
        // Main Notification Intent
        Intent intent = new Intent(this, NowPlayingActivity.class);
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

    boolean mHasMainTask = true;
    public void setMainTaskRunning(boolean isRunning) {
        // Stop if the app is closing and playback is paused
        if (! isRunning && isPaused())
            stop();
        // Update notification to use synthesized back stack if the app is exiting
        else if (isRunning != mHasMainTask) {
            mHasMainTask = isRunning;
            updateNotification();
        }
    }

    /** Updates the {@link Notification} with the current playing status
     *
     * <p>If no notification exists this service is in the background.  In this case,
     * {@link #startForeground(int, Notification)} is called, and a new notification is created.
     *
     * @see #createNotification()
     */
    private void updateNotification() {
        Playlist.Song song = Playlist.getInstance().getCurrent();
        Notification notification = mNotification != null ? mNotification : createNotification();
        // Update content
        RemoteViews remote = notification.contentView;
        remote.setTextViewText(R.id.title, song != null ? song.name : "");
        remote.setTextViewText(R.id.singing, song != null ? song.singing : "");
        remote.setImageViewResource(R.id.play_pause, isPlaying()
                ? android.R.drawable.ic_media_pause
                : android.R.drawable.ic_media_play);
        remote.setViewVisibility(R.id.play_pause, isPrepared() ? View.VISIBLE : View.GONE);
        remote.setViewVisibility(R.id.loading, isPrepared() ? View.GONE : View.VISIBLE);
        // Update pending intent
        if (mHasMainTask) {
            // Launch NowPlayingActivity normally
            Intent intent = new Intent(this, NowPlayingActivity.class);
            notification.contentIntent = PendingIntent.getActivity(
                    this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
        }
        else {
            // Synthesize stack MainActivity -> NowPlayingActivity
            TaskStackBuilder stack = TaskStackBuilder.create(getApplicationContext());
            stack.addNextIntent(new Intent(this, MainActivity.class));
            stack.addNextIntent(new Intent(this, NowPlayingActivity.class));
            notification.contentIntent = stack.getPendingIntent(0, PendingIntent.FLAG_UPDATE_CURRENT);
        }
        // Show or update notification
        if (mNotification == null) {
            Log.v(TAG, "Starting foreground service");
            startForeground(NOTIFICATION_ID, notification);
            mNotification = notification;
        }
        else
            mNotificationManager.notify(NOTIFICATION_ID, mNotification);
    }

    /**
     * Observer that pauses playback if the song is removed from the playlist
     */
    PlaylistObserver mObserver = new PlaylistObserver() {
        {
            setFilter(Intent.ACTION_HEADSET_PLUG);
        }

        // Pause playback when song is removed from playlist
        @Override
        public void onPlaylistChanged() {
            if (mSong != null && ! Playlist.getInstance().contains(mSong))
                pause();
        }

        // Pause playback when headset is unplugged
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(Intent.ACTION_HEADSET_PLUG) &&
                    intent.getIntExtra("state", -1) == 0) {
                pause();
            }
        }
    };


    //region MediaPlayer Callbacks
    //---------------------------------------------------------------------------------------------
    @Override
    public void onPrepared(MediaPlayer mp) {
        Log.v(TAG, "Prepared; starting playback");
        mIsPrepared = true;
        mErrorCount = 0;
        if (mSong != null)
            mSong.status = Playlist.Song.STATUS_OK;
        LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent(BROADCAST_PREPARED));
        if (mShouldPlay)
            start();
    }

    @Override
    public void onCompletion(MediaPlayer mp) {
        Log.v(TAG, "Complete");
        mIsPrepared = false;
        LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent(BROADCAST_COMPLETED));
        // Start the next
        if (Playlist.getInstance().moveToNext() != null)
            prepare();
        else {
            Log.v(TAG, "End of playlist: stopping service");
            stop();
        }
    }

    @Override
    public boolean onError(MediaPlayer mp, int what, int extra) {
        Log.e(TAG, "Error: " + String.valueOf(what));
        if (mSong != null)
            mSong.status = Playlist.Song.STATUS_ERROR;
        mIsPrepared = false;
        ++mErrorCount;
        LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent(BROADCAST_ERROR));
        if (mErrorCount >= ERROR_LIMIT) {
            mErrorCount = 0;
            return false; // Give up on this song; move to the next
        }
        // Try to load the same song a few times
        Handler handler = new Handler();
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (mMediaPlayer != null)
                    prepare();
            }
        }, ERROR_DELAY_MS);
        return true;
    }
    //---------------------------------------------------------------------------------------------
    //endregion

    /**
     * A MediaPlayerControl implementation that wraps the PlaybackService singleton
     *
     * <p>If PlaybackService is not running, methods are either a no-op or return default values
     */
    public static class Control implements MediaController.MediaPlayerControl {
        /** Above this threshold PREV actually restarts the current song */
        public static final int RESTART_THRESHOLD = 15000;

        Context mContext;

        /**
         * Constructs a new MediaPlayerControl
         *
         * @param context A {@link Context} that will be used to start {@link PlaybackService}
         *                when {@link #start} is called.
         */
        public Control(Context context) {
            mContext = context.getApplicationContext();
        }

        /** NextListener for MediaController
         *
         * @see MediaController#setPrevNextListeners(View.OnClickListener, View.OnClickListener)
         */
        public View.OnClickListener nextListener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Playlist.getInstance().moveToNext();
                if (isRunning())
                    getInstance().prepare();
            }
        };

        /** NextListener for MediaController
         *
         * @see MediaController#setPrevNextListeners(View.OnClickListener, View.OnClickListener)
         */
        public View.OnClickListener prevListener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (getCurrentPosition() > RESTART_THRESHOLD)
                    seekTo(0);
                else {
                    Playlist.getInstance().moveToPrev();
                    if (isRunning())
                        getInstance().prepare();
                }
            }
        };

        @Override
        public boolean canPause() {
            return isRunning() && getInstance().canPause();
        }

        @Override
        public void start() {
            if (isRunning())
                getInstance().start();
            else {
                // Start the service and play
                Intent intent = new Intent(mContext, PlaybackService.class);
                intent.setAction(PlaybackService.ACTION_PLAY);
                mContext.startService(intent);
            }
        }

        /**
         * Play a particular song in the playlist
         *
         * @param pos playlist position
         */
        public void start(int pos) {
            Playlist.getInstance().moveToPosition(pos);
            if (isRunning())
                getInstance().prepare();
            start();
        }

        @Override
        public void pause() {
            if (isRunning()) getInstance().pause();
        }

        @Override
        public int getDuration() {
            return isRunning() ? getInstance().getDuration() : 0;
        }

        @Override
        public int getCurrentPosition() {
            return isRunning() ? getInstance().getCurrentPosition() : 0;
        }

        @Override
        public void seekTo(int pos) {
            if (isRunning()) getInstance().seekTo(pos);
        }

        @Override
        public boolean isPlaying() {
            return isRunning() && getInstance().isPlaying();
        }

        @Override
        public int getBufferPercentage() {
            return isRunning() ? getInstance().getBufferPercentage() : 0;
        }

        @Override
        public boolean canSeekBackward() {
            return isRunning() && getInstance().canSeekBackward();
        }

        @Override
        public boolean canSeekForward() {
            return isRunning() && getInstance().canSeekForward();
        }

        @Override
        public int getAudioSessionId() {
            return isRunning() ? getInstance().getAudioSessionId() : 0;
        }
    }
}