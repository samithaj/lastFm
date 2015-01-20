/***************************************************************************
 *   Copyright 2005-2009 Last.fm Ltd.                                      *
 *   Portions contributed by Casey Link, Lukasz Wisniewski,                *
 *   Mike Jennings, and Michael Novak Jr.                                  *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU General Public License as published by  *
 *   the Free Software Foundation; either version 2 of the License, or     *
 *   (at your option) any later version.                                   *
 *                                                                         *
 *   This program is distributed in the hope that it will be useful,       *
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of        *
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the         *
 *   GNU General Public License for more details.                          *
 *                                                                         *
 *   You should have received a copy of the GNU General Public License     *
 *   along with this program; if not, write to the                         *
 *   Free Software Foundation, Inc.,                                       *
 *   51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.         *
 ***************************************************************************/
package fm.last.android.scrobbler;

import java.io.FileInputStream;
import java.io.ObjectInputStream;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.FileHandler;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import fm.last.android.utils.AsyncTaskEx;
import android.os.IBinder;
import android.os.RemoteException;
import android.preference.PreferenceManager;
import android.provider.MediaStore;
import android.widget.RemoteViews;
import android.widget.Toast;
import fm.last.android.AndroidLastFmServerFactory;
import fm.last.android.LastFMApplication;
import fm.last.android.LastFm;
import fm.last.android.R;
import fm.last.android.RadioWidgetProvider;
import fm.last.android.db.ScrobblerQueueDao;
import fm.last.android.db.TrackDurationCacheDao;
import fm.last.api.LastFmServer;
import fm.last.api.RadioTrack;
import fm.last.api.Session;
import fm.last.api.Track;
import fm.last.api.WSError;

/**
 * A Last.fm scrobbler for Android
 *
 * @author Sam Steele <sam@last.fm>
 *
 *         This is a scrobbler that can scrobble both our radio player as well
 *         as the built-in media player and other 3rd party apps that broadcast
 *         fm.last.android.metachanged notifications. We can't rely on
 *         com.android.music.metachanged due to a bug in the built-in media
 *         player that does not broadcast this notification when playing the
 *         first track, only when starting the next track.
 *
 *         Scrobbles and Now Playing data are serialized between launches, and
 *         will be sent when the track or network state changes. This service
 *         has a very short lifetime and is only started for a few seconds at a
 *         time when there's work to be done. This server is started when music
 *         state or network state change.
 *
 *         Scrobbles are submitted to the server after Now Playing info is sent,
 *         or when a network connection becomes available.
 *
 *         Sample code for a 3rd party to integrate with us is located at
 *         http://wiki.github.com/c99koder/lastfm-android/scrobbler-interface
 *
 */
public class ScrobblerService extends Service {
	private Session mSession;
	public static final String LOVE = "fm.last.android.LOVE";
	public static final String BAN = "fm.last.android.BAN";
	private Lock mScrobblerLock = new ReentrantLock();
	SubmitTracksTask mSubmissionTask = null;
	NowPlayingTask mNowPlayingTask = null;
	ClearNowPlayingTask mClearNowPlayingTask = null;
	ScrobblerQueueEntry mCurrentTrack = null;

	public static final String META_CHANGED = "fm.last.android.metachanged";
	public static final String PLAYBACK_FINISHED = "fm.last.android.playbackcomplete";
	public static final String PLAYBACK_STATE_CHANGED = "fm.last.android.playstatechanged";
	public static final String STATION_CHANGED = "fm.last.android.stationchanged";
	public static final String PLAYBACK_ERROR = "fm.last.android.playbackerror";
	public static final String PLAYBACK_PAUSED = "fm.last.android.playbackpaused";
	public static final String UNKNOWN = "fm.last.android.unknown";

	private Logger logger;
	
	private String player = null;
	
	@Override
	public void onCreate() {
		super.onCreate();

		logger = Logger.getLogger("fm.last.android.scrobbler");
		try {
			if (logger.getHandlers().length < 1) {
				FileHandler handler = new FileHandler(getFilesDir().getAbsolutePath() + "/scrobbler.log", 4096, 1, true);
				handler.setFormatter(new SimpleFormatter());
				logger.addHandler(handler);
			}
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		mSession = LastFMApplication.getInstance().session;

		if (mSession == null || !PreferenceManager.getDefaultSharedPreferences(this).getBoolean("scrobble", true)) {
			// User not authenticated, shutting down...
			stopSelf();
			return;
		}

		try {
			ScrobblerQueueEntry entry = ScrobblerQueueDao.getInstance().loadCurrentTrack();
			if (entry != null) {
				if (entry.startTime > System.currentTimeMillis()) {
					logger.info("Serialized start time is in the future! ignoring");
				}
				else {
					mCurrentTrack = entry;
				}
			}
		} catch (Exception e) {
			mCurrentTrack = null;
		}
		
		try {
			if (getFileStreamPath("queue.dat").exists()) {
				logger.info("Migrating old scrobble queue");
				FileInputStream fileStream = openFileInput("queue.dat");
				ObjectInputStream objectStream = new ObjectInputStream(fileStream);
				Object obj = objectStream.readObject();
				if (obj instanceof Integer) {
					Integer count = (Integer) obj;
					for (int i = 0; i < count.intValue(); i++) {
						obj = objectStream.readObject();
						if (obj != null && obj instanceof ScrobblerQueueEntry) {
							try {
								ScrobblerQueueDao.getInstance().addToQueue((ScrobblerQueueEntry) obj);
							} catch (IllegalStateException e) {
								break; //The queue is full!
							}
						}
					}
					logger.info("Imported " + count + " tracks");
				}
				objectStream.close();
				fileStream.close();
				deleteFile("queue.dat");
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	@Override
	public void onDestroy() {
		super.onDestroy();

		int queueSize = 0;
		try {
			queueSize = ScrobblerQueueDao.getInstance().getQueueSize();
		} catch (Exception e) { //If the db was locked, assume we should try again in an hour
			queueSize = 1;
		}
		
		try {
			Intent intent = new Intent("fm.last.android.scrobbler.FLUSH");
			PendingIntent alarmIntent = PendingIntent.getBroadcast(this, 0, intent, 0);
			AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
			am.cancel(alarmIntent); // cancel any pending alarm intents
			if (queueSize > 0) {
				// schedule an alarm to wake the device and try again in an hour
				logger.info("Scrobbles are pending, will retry in an hour");
				am.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 3600000, alarmIntent);
			}
			ScrobblerQueueDao.getInstance().saveCurrentTrack(mCurrentTrack);
		} catch (Exception e) {
			logger.severe("Unable to save current track state");
			e.printStackTrace();
		}
	}

	/*
	 * This will check the distance between the start time and the current time
	 * to determine whether this is a skip or a played track, and will add it to
	 * our scrobble queue.
	 */
	public void enqueueCurrentTrack() {
		if (mCurrentTrack != null) {
			long playTime = (System.currentTimeMillis() / 1000) - mCurrentTrack.startTime;

			int scrobble_perc = PreferenceManager.getDefaultSharedPreferences(this).getInt("scrobble_percentage", 50);
			int track_duration = (int) (mCurrentTrack.duration / 1000);

			scrobble_perc = (int)(track_duration * (scrobble_perc * 0.01));
			boolean played = (playTime > 30 && playTime > scrobble_perc) || (playTime > 240);
			
			if (played || mCurrentTrack.rating.length() > 0) {
				logger.info("Enqueuing track (Rating:" + mCurrentTrack.rating + ")");
				boolean queued = ScrobblerQueueDao.getInstance().addToQueue(mCurrentTrack);
				if (!queued) {			
					logger.severe("Scrobble queue is full!  Have " + ScrobblerQueueDao.MAX_QUEUE_SIZE + " scrobbles!");
				}
			}
			mCurrentTrack = null;
		}
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		onStart(intent, startId);
		return START_NOT_STICKY;
	}
	
	@Override
	public void onStart(Intent intent, int startId) {
		final Intent i = intent;
		
		if(i==null) {
			stopSelf();
			return;
		}
		
		/*
		 * The Android media player doesn't send a META_CHANGED notification for
		 * the first track, so we'll have to catch PLAYBACK_STATE_CHANGED and
		 * check to see whether the player is currently playing. We'll then send
		 * our own META_CHANGED intent to the scrobbler.
		 */
		if (intent.getAction().equals("com.android.music.playstatechanged") || intent.getAction().equals("com.android.music.metachanged")
				|| intent.getAction().equals("com.android.music.queuechanged")) {
			long id = -1;
			try {
				id = intent.getLongExtra("id", -1);
			} catch (Exception e) {
				//ignore this
			}
			if(id == -1)
				id = intent.getIntExtra("id", -1);
			if (PreferenceManager.getDefaultSharedPreferences(this).getBoolean("scrobble_music_player", true) && id != -1) {
				if(RadioWidgetProvider.isAndroidMusicInstalled(this)) {
					try {
				       bindService(new Intent().setClassName(RadioWidgetProvider.getAndroidMusicPackageName(this), "com.android.music.MediaPlaybackService"), new ServiceConnection() {
				    	   public void onServiceConnected(ComponentName comp, IBinder binder) {
				    		   com.android.music.IMediaPlaybackService s = com.android.music.IMediaPlaybackService.Stub.asInterface(binder);
				
				    		   try {
				    			   if (s.isPlaying()) {
				    				   i.setAction(META_CHANGED);
				    				   i.putExtra("position", s.position());
				    				   i.putExtra("duration", s.duration());
				    				   handleIntent(i);
				    			   } else { // Media player was paused
				    				   mCurrentTrack = null;
				    				   NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
				    				   nm.cancel(1338);
				    				   stopSelf();
				    			   }
				    		   } catch (RemoteException e) {
				    			   // TODO Auto-generated catch block
				    			   e.printStackTrace();
				    		   }
								try {
									LastFMApplication.getInstance().unbindService(this);
								} catch (IllegalArgumentException e) {
								}
				    	   }
				
				    	   public void onServiceDisconnected(ComponentName comp) {
				    	   }
				       }, 0);
					} catch (Exception e) {
						new IntentFromMediaDBTask(i).execute((Void)null);
					}
				} else {
					new IntentFromMediaDBTask(i).execute((Void)null);
				}
			} else {
				// Clear the current track in case the user has disabled
				// scrobbling of the media player
				// during the middle of this track.
				mCurrentTrack = null;
				stopIfReady();
			}
		} else if ((intent.getAction().equals("com.htc.music.playstatechanged") && intent.getIntExtra("id", -1) != -1)
				|| intent.getAction().equals("com.htc.music.metachanged")) {
			if (PreferenceManager.getDefaultSharedPreferences(this).getBoolean("scrobble_music_player", true)) {
				bindService(new Intent().setClassName("com.htc.music", "com.htc.music.MediaPlaybackService"), new ServiceConnection() {
					public void onServiceConnected(ComponentName comp, IBinder binder) {
						com.htc.music.IMediaPlaybackService s = com.htc.music.IMediaPlaybackService.Stub.asInterface(binder);

						try {
							if (s.isPlaying()) {
								i.setAction(META_CHANGED);
								i.putExtra("position", s.position());
								i.putExtra("duration", s.duration());
								i.putExtra("track", s.getTrackName());
								i.putExtra("artist", s.getArtistName());
								i.putExtra("album", s.getAlbumName());
								handleIntent(i);
							} else { // Media player was paused
								mCurrentTrack = null;
								NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
								nm.cancel(1338);
								stopSelf();
							}
						} catch (RemoteException e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						}
						try {
							LastFMApplication.getInstance().unbindService(this);
						} catch (IllegalArgumentException e) {
						}
					}

					public void onServiceDisconnected(ComponentName comp) {
					}
				}, 0);
			} else {
				// Clear the current track in case the user has disabled
				// scrobbling of the media player
				// during the middle of this track.
				mCurrentTrack = null;
				stopIfReady();
			}
		} else if(intent.getAction().equals("com.adam.aslfms.notify.playstatechanged")) {
			int state = intent.getIntExtra("state", -1);
			if(state > -1) {
				if(state < 2) { //start or resume
					i.setAction(META_CHANGED);
					//convert the duration from int to long
					long duration = intent.getIntExtra("duration", 0);
					i.removeExtra("duration");
					i.putExtra("duration", duration * 1000);
				} else if(state == 2) { //pause
					i.setAction(PLAYBACK_PAUSED);
				} else if(state == 3) { //complete
					i.setAction(PLAYBACK_FINISHED);
				}
				handleIntent(i);
			}
		} else if(intent.getAction().equals("net.jjc1138.android.scrobbler.action.MUSIC_STATUS")) {
			new IntentFromMediaDBTask(i).execute((Void)null);
		} else { //
			handleIntent(i);
		}
	}

	public long lookupDuration(String artist, String track) {
		logger.info("Duration was unavailable, looking it up!");
		
		final String[] columns = new String[] {
				MediaStore.Audio.AudioColumns.DURATION };
		
		//Search the artist/title on external storage
		Cursor cur = getContentResolver().query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, columns, 
				MediaStore.Audio.AudioColumns.ARTIST + " = ? and " + MediaStore.Audio.AudioColumns.TITLE + " = ?", new String[] { artist, track}, null);

		if(cur != null && cur.moveToFirst()) {
			return cur.getLong(cur.getColumnIndex(MediaStore.Audio.AudioColumns.DURATION));
		}
		
		//Search the artist/title on internal storage
		cur = getContentResolver().query(MediaStore.Audio.Media.INTERNAL_CONTENT_URI, columns, 
				MediaStore.Audio.AudioColumns.ARTIST + " = ? and " + MediaStore.Audio.AudioColumns.TITLE + " = ?", new String[] { artist, track}, null);

		if(cur != null && cur.moveToFirst()) {
			return cur.getLong(cur.getColumnIndex(MediaStore.Audio.AudioColumns.DURATION));
		}

		//Check to see if we've cached it from a previous network lookup
		long duration = TrackDurationCacheDao.getInstance().getDurationForTrack(artist, track);
		logger.info("Duration from cache: " + duration);
		if(duration > 0)
			return duration;
		
		//If we're allowed to connect to the network, look up the track info on Last.fm
		ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
		NetworkInfo ni = cm.getActiveNetworkInfo();
		if(ni != null) {
			boolean scrobbleWifiOnly = PreferenceManager.getDefaultSharedPreferences(this).getBoolean("scrobble_wifi_only", false);
			if (cm.getBackgroundDataSetting() && ni.isConnected() && (!scrobbleWifiOnly || (scrobbleWifiOnly && ni.getType() == ConnectivityManager.TYPE_WIFI))) {
				LastFmServer server = AndroidLastFmServerFactory.getServer();
				try {
					Track t = server.getTrackInfo(artist, track, "");
					TrackDurationCacheDao.getInstance().save(Collections.singleton(t));
					logger.info("Duration from network: " + t.getDuration());
					return Long.parseLong(t.getDuration());
				} catch (Exception e) {
				} catch (WSError e) {
				}
			}
		}
		return 0;
	}
	
	public Intent intentFromMediaDB(Intent intent) {
		final Intent i = intent;

		boolean playing = false;
		
		SharedPreferences settings = getSharedPreferences(LastFm.PREFS, 0);
		boolean mediaPlayerIsPlaying = settings.getBoolean("mediaPlayerIsPlaying", false);
		
		if(intent.getAction().endsWith("metachanged"))
			playing = mediaPlayerIsPlaying;
		else if(intent.getBooleanExtra("playing", false))
			playing = true;
		else if(intent.getBooleanExtra("playstate", false) || (intent.getSerializableExtra("playstate") == null && intent.getBooleanExtra("playing", true)))
			playing = true;
		
		SharedPreferences.Editor editor = settings.edit();
		editor.putBoolean("mediaPlayerIsPlaying", playing);
		editor.commit();
		
		if(!playing) {
			i.setAction(PLAYBACK_FINISHED);
		} else {
			i.setAction(META_CHANGED);
			
			//convert the duration from int to long
			long duration = intent.getIntExtra("secs", 0);
			i.removeExtra("secs");
			i.putExtra("duration", duration * 1000);

			//Try to find the duration in the media db, otherwise get it from last.fm
			if(i.getStringExtra("artist") != null && i.getStringExtra("track") != null) {
				duration = i.getLongExtra("duration", 0);
				if(duration == 0) {
					duration = lookupDuration(i.getStringExtra("artist"), i.getStringExtra("track"));
					i.putExtra("duration", duration);
				}
				return i;
			}
			
			//If there was no artist / track in the intent, try to look up the id in the media db
			long id = -1;
			try {
				id = intent.getIntExtra("id", -1);
			} catch (Exception e) {
				//ignore this
			}
			if(id == -1)
				id = intent.getLongExtra("id", -1);
			
			if(id != -1) {
				final String[] columns = new String[] {
					MediaStore.Audio.AudioColumns.ARTIST,
					MediaStore.Audio.AudioColumns.TITLE,
					MediaStore.Audio.AudioColumns.DURATION,
					MediaStore.Audio.AudioColumns.ALBUM,
					MediaStore.Audio.AudioColumns.TRACK, };
				
				Cursor cur = getContentResolver().query(
					ContentUris.withAppendedId(
						MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
						id), columns, null, null, null);
				
				if (cur == null) {
					logger.severe("could not open cursor to media in media store");
					return null;
				}
	
	            try {
					if (!cur.moveToFirst()) {
						//Search internal storage if external storage fails
						cur = getContentResolver().query(
								ContentUris.withAppendedId(
									MediaStore.Audio.Media.INTERNAL_CONTENT_URI,
									id), columns, null, null, null);
					}
					if (!cur.moveToFirst()) {
					        logger.info("no such media in media store: " + id);
					        cur.close();
				        	return null;
					} else {
						String artist = cur.getString(cur.getColumnIndex(MediaStore.Audio.AudioColumns.ARTIST));
						if(i.getStringExtra("artist") == null)
							i.putExtra("artist", artist);
						
						String track = cur.getString(cur.getColumnIndex(MediaStore.Audio.AudioColumns.TITLE));
						if(i.getStringExtra("track") == null)
							i.putExtra("track", track);
						
						String album = cur.getString(cur.getColumnIndex(MediaStore.Audio.AudioColumns.ALBUM));
						if(i.getStringExtra("album") == null)
							i.putExtra("album", album);
						
						duration = cur.getLong(cur.getColumnIndex(MediaStore.Audio.AudioColumns.DURATION));
						if (duration != 0) {
						    i.putExtra("duration", duration);
						}
					}
	            } finally {
	                    cur.close();
	            }
			}
		}
		return i;
	}
	
	public void handleIntent(Intent intent) {
		if(intent == null || intent.getAction() == null) {
			stopSelf();
			return;
		}
		
		/*if(intent.getAction() != null) { //Dump the intent to the log for troubleshooting buggy apps
			logger.info("Intent: " + intent.getAction());
			if(intent.getExtras() != null && intent.getExtras().size() > 0) {
				for(String key : intent.getExtras().keySet()) {
					logger.info("Key: " + key + " value: " + intent.getExtras().get(key));
				}
			}
		}*/
		
		if (intent.getAction().equals(META_CHANGED)) {
			long startTime = System.currentTimeMillis() / 1000;
			long position = intent.getLongExtra("position", 0) / 1000;
			if (position > 0) {
				startTime -= position;
			}

			String title = intent.getStringExtra("track");
			String artist = intent.getStringExtra("artist");
			player = intent.getStringExtra("player");

			if (mCurrentTrack != null) {
				int scrobble_perc = PreferenceManager.getDefaultSharedPreferences(this).getInt("scrobble_percentage", 50);
				long scrobblePoint = mCurrentTrack.duration * (scrobble_perc / 100);

				if (scrobblePoint > 240000)
					scrobblePoint = 240000;
				if (startTime < (mCurrentTrack.startTime + scrobblePoint) && mCurrentTrack.title.equals(title) && mCurrentTrack.artist.equals(artist)) {
					logger.warning("Ignoring duplicate scrobble");
					stopIfReady();
					return;
				}
				enqueueCurrentTrack();
			}
			mCurrentTrack = new ScrobblerQueueEntry();

			mCurrentTrack.startTime = startTime;
			mCurrentTrack.title = title;
			mCurrentTrack.artist = artist;
			mCurrentTrack.album = intent.getStringExtra("album");
			mCurrentTrack.duration = intent.getLongExtra("duration", 0);
			if(mCurrentTrack.duration == 0)
				mCurrentTrack.duration = (long)intent.getIntExtra("duration", 0);

			if (mCurrentTrack.title == null || mCurrentTrack.artist == null) {
				mCurrentTrack = null;
				stopIfReady();
				return;
			}
			String auth = intent.getStringExtra("trackAuth");
			if (auth != null && auth.length() > 0) {
				mCurrentTrack.trackAuth = auth;
			}
			boolean scrobbleRealtime = PreferenceManager.getDefaultSharedPreferences(this).getBoolean("scrobble_realtime", true);
			if (scrobbleRealtime || auth != null) {
				ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
				NetworkInfo ni = cm.getActiveNetworkInfo();
				if (ni != null) {
					boolean scrobbleWifiOnly = PreferenceManager.getDefaultSharedPreferences(this).getBoolean("scrobble_wifi_only", false);
					if (cm.getBackgroundDataSetting() && ni.isConnected() && (!scrobbleWifiOnly || (scrobbleWifiOnly && ni.getType() == ConnectivityManager.TYPE_WIFI) || auth != null && mNowPlayingTask == null)) {
						mNowPlayingTask = new NowPlayingTask(mCurrentTrack.toRadioTrack());
						mNowPlayingTask.execute();
					}
				}
			}

			if (auth == null) {
				NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
				nm.cancel(1338);

				Notification notification = new Notification(R.drawable.as_statusbar, null, System.currentTimeMillis());
				Intent metaIntent = new Intent(this, fm.last.android.activity.Metadata.class);
				metaIntent.putExtra("artist", mCurrentTrack.artist);
				metaIntent.putExtra("track", mCurrentTrack.title);
				PendingIntent contentIntent = PendingIntent.getActivity(this, 0, metaIntent, PendingIntent.FLAG_UPDATE_CURRENT);
				String info = mCurrentTrack.title + " - " + mCurrentTrack.artist;
				notification.setLatestEventInfo(this, getString(R.string.scrobbler_info_title), info, contentIntent);
				notification.flags |= Notification.FLAG_ONGOING_EVENT;
				try {
					Field f = Notification.class.getField("priority");
					f.setInt(notification, -2); //PRIORITY_MIN
				} catch (Exception e) {
					e.printStackTrace();
				}

				nm.notify(1338, notification);
			}
		}
		if (intent.getAction().equals(PLAYBACK_FINISHED) || intent.getAction().equals("com.android.music.playbackcomplete")
				|| intent.getAction().equals("com.htc.music.playbackcomplete")) {
			if(mCurrentTrack != null) {
				mClearNowPlayingTask = new ClearNowPlayingTask(mCurrentTrack.toRadioTrack());
				mClearNowPlayingTask.execute();
			}
			enqueueCurrentTrack();
			NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
			nm.cancel(1338);
		}
		if (intent.getAction().equals(PLAYBACK_PAUSED) && mCurrentTrack != null) {
			if(intent.getLongExtra("position", 0) > 0 || !intent.hasExtra("position")) { //Work-around for buggy DoubleTwist player
				mClearNowPlayingTask = new ClearNowPlayingTask(mCurrentTrack.toRadioTrack());
				mClearNowPlayingTask.execute();
				mCurrentTrack = null;
				NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
				nm.cancel(1338);
			}
		}
		if (intent.getAction().equals(LOVE) && mCurrentTrack != null) {
			mCurrentTrack.rating = "L";
			Toast.makeText(this, getString(R.string.scrobbler_trackloved), Toast.LENGTH_SHORT).show();
		}
		if (intent.getAction().equals(BAN) && mCurrentTrack != null) {
			mCurrentTrack.rating = "B";
			Toast.makeText(this, getString(R.string.scrobbler_trackbanned), Toast.LENGTH_SHORT).show();
		}
		if (intent.getAction().equals("fm.last.android.scrobbler.FLUSH") || mNowPlayingTask == null) {
			ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
			NetworkInfo ni = cm.getActiveNetworkInfo();
			if(ni != null) {
				boolean scrobbleWifiOnly = PreferenceManager.getDefaultSharedPreferences(this).getBoolean("scrobble_wifi_only", false);
				if (cm.getBackgroundDataSetting() && ni.isConnected() && (!scrobbleWifiOnly || (scrobbleWifiOnly && ni.getType() == ConnectivityManager.TYPE_WIFI))) {
					int queueSize = 0;
					try {
						queueSize = ScrobblerQueueDao.getInstance().getQueueSize();
					} catch (Exception e) {
						e.printStackTrace();
					}
					if (queueSize > 0 && mSubmissionTask == null) {
						mSubmissionTask = new SubmitTracksTask();
						mSubmissionTask.execute();
					}
				}
			}
		}
		stopIfReady();
	}

	public void stopIfReady() {
		if (mSubmissionTask == null && mNowPlayingTask == null && mClearNowPlayingTask == null)
			stopSelf();
	}

	/*
	 * We don't currently offer any bindable functions. Perhaps in the future we
	 * can add a function to get the queue size / last scrobbler result / etc.
	 */
	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

	private class IntentFromMediaDBTask extends AsyncTaskEx<Void, Void, Intent> {
		Intent intent;
		
		public IntentFromMediaDBTask(Intent i) {
			intent = i;
		}

		@Override
		public Intent doInBackground(Void... params) {
			return intentFromMediaDB(intent);
		}

		@Override
		public void onPostExecute(Intent result) {
			handleIntent(result);
		}
	}

	private class ClearNowPlayingTask extends AsyncTaskEx<Void, Void, Boolean> {
		RadioTrack mTrack;

		public ClearNowPlayingTask(RadioTrack track) {
			mTrack = track;
		}

		@Override
		public Boolean doInBackground(Void... params) {
			boolean success = false;
			LastFmServer server = AndroidLastFmServerFactory.getServer();

			try {
				mScrobblerLock.lock();
				server.removeNowPlaying(mTrack.getCreator(), mTrack.getTitle(), mTrack.getAlbum(), (long)(mTrack.getDuration() / 1000), ScrobblerService.this.player, mSession.getKey());
				success = true;
			} catch (Exception e) {
				e.printStackTrace();
				success = false;
			} catch (WSError e) {
				e.printStackTrace();
				success = false;
			} finally {
				mScrobblerLock.unlock();
			}
			return success;
		}

		@Override
		public void onPostExecute(Boolean result) {
			mClearNowPlayingTask = null;
			stopIfReady();
		}
	}

	private class NowPlayingTask extends AsyncTaskEx<Void, Void, Boolean> {
		RadioTrack mTrack;

		public NowPlayingTask(RadioTrack track) {
			mTrack = track;
		}

		@Override
		public void onPreExecute() {
			/* If we have any scrobbles in the queue, try to send them now */
			try {
				if (mSubmissionTask == null && ScrobblerQueueDao.getInstance().getQueueSize() > 0) {
					mSubmissionTask = new SubmitTracksTask();
					mSubmissionTask.execute();
				}
			} catch (Exception e) { //The scrobbler db might be locked, this isn't fatal as we can retry later
			}
		}

		@Override
		public Boolean doInBackground(Void... params) {
			boolean success = false;
			LastFmServer server = AndroidLastFmServerFactory.getServer();

			try {
				mScrobblerLock.lock();
				server.updateNowPlaying(mTrack.getCreator(), mTrack.getTitle(), mTrack.getAlbum(), (long)(mTrack.getDuration() / 1000), ScrobblerService.this.player, mSession.getKey());
				success = true;
			} catch (Exception e) {
				e.printStackTrace();
				success = false;
			} catch (WSError e) {
				e.printStackTrace();
				success = false;
			} finally {
				mScrobblerLock.unlock();
			}
			return success;
		}

		@Override
		public void onPostExecute(Boolean result) {
			if (mCurrentTrack != null)
				mCurrentTrack.postedNowPlaying = result;
			mNowPlayingTask = null;
			stopIfReady();
		}
	}

	private class SubmitTracksTask extends AsyncTaskEx<Void, Void, Boolean> {

		@Override
		public Boolean doInBackground(Void... p) {
			boolean success = false;
			mScrobblerLock.lock();
			logger.info("Going to submit " + ScrobblerQueueDao.getInstance().getQueueSize() + " tracks");
			LastFmServer server = AndroidLastFmServerFactory.getServer();
			
			ScrobblerQueueEntry e = null;
			while ((e = ScrobblerQueueDao.getInstance().nextQueueEntry()) != null) {
				try {
					success = false;
					if (e != null && e.title != null && e.artist != null && e.toRadioTrack() != null) {
						if (e.rating.equals("L")) {
							server.loveTrack(e.artist, e.title, mSession.getKey());
						}
						if (e.rating.equals("B")) {
							if(e.trackAuth.length() == 0) {
								//Local tracks can't be banned, so drop them
								logger.info("Removing banned local track from queue");
								ScrobblerQueueDao.getInstance().removeFromQueue(e);
								continue;
							}
							server.banTrack(e.artist, e.title, mSession.getKey());
						}
						if(!e.rating.equals("B") && !e.rating.equals("S"))
							server.scrobbleTrack(e.artist, e.title, e.album, e.startTime, (int)(e.duration / 1000), ScrobblerService.this.player, e.trackAuth, mSession.getKey());
						success = true;
					}
				} 
				catch (Exception ex) {
					logger.severe("Unable to submit track: " + ex.toString());
					ex.printStackTrace();
					success = false;
				}
				catch (WSError ex) {
					logger.severe("Unable to submit track: " + ex.toString());
					ex.printStackTrace();
					success = true; //Remove the track from the queue
				}
				if(success) {
					ScrobblerQueueDao.getInstance().removeFromQueue(e);
				} 
				else {
					logger.severe("Scrobble submission aborted");
					break;
				}
			}
			mScrobblerLock.unlock();
			return success;
		}

		@Override
		public void onPostExecute(Boolean result) {
			mSubmissionTask = null;
			stopIfReady();
		}
	}
}
