package org.pcgod.mumbleclient.service;

import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import junit.framework.Assert;

import net.sf.mumble.MumbleProto;

import org.pcgod.mumbleclient.R;
import org.pcgod.mumbleclient.service.audio.AudioOutputHost;
import org.pcgod.mumbleclient.service.audio.RecordThread;
import org.pcgod.mumbleclient.service.model.Channel;
import org.pcgod.mumbleclient.service.model.Message;
import org.pcgod.mumbleclient.service.model.User;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for providing the client an access to the connection.
 * <p/>
 * MumbleService manages the MumbleClient connection and provides access to
 * it for binding activities.
 *
 * @author Rantanen
 */
public class MumbleService extends Service {

    public static final String TAG = "MumbleService";

    public static final int CONNECTION_STATE_DISCONNECTED = 0;
    public static final int CONNECTION_STATE_CONNECTING = 1;
    public static final int CONNECTION_STATE_SYNCHRONIZING = 2;
    public static final int CONNECTION_STATE_CONNECTED = 3;

    private static final String[] CONNECTION_STATE_NAMES = {
            "Disconnected", "Connecting", "Synchronizing", "Connected"
    };

    public static final String ACTION_CONNECT = "mumbleclient.action.CONNECT";

    public static final String EXTRA_MESSAGE = "mumbleclient.extra.MESSAGE";
    public static final String EXTRA_CONNECTION_STATE = "mumbleclient.extra.CONNECTION_STATE";
    public static final String EXTRA_HOST = "mumbleclient.extra.HOST";
    public static final String EXTRA_PORT = "mumbleclient.extra.PORT";
    public static final String EXTRA_USERNAME = "mumbleclient.extra.USERNAME";
    public static final String EXTRA_PASSWORD = "mumbleclient.extra.PASSWORD";
    public static final String EXTRA_CERTIFICATE = "mumbleclient.extra.CERTIFICATE";
    public static final String EXTRA_USER = "mumbleclient.extra.USER";

    private MumbleConnection mClient;
    private MumbleProtocol mProtocol;

    private Thread mClientThread;
    private Thread mRecordThread;

    Notification mNotification;

    private final LocalBinder mBinder = new LocalBinder();
    final Handler handler = new Handler();

    int state;
    boolean synced;
    int serviceState;
    String errorString;
    final List<Message> messages = new LinkedList<Message>();
    final List<Channel> channels = new ArrayList<Channel>();
    final List<User> users = new ArrayList<User>();

    // Use concurrent hash map so we can modify the collection while iterating.
    private final Map<Object, IServiceObserver> observers = new ConcurrentHashMap<Object, IServiceObserver>();

    private static final Class<?>[] mStartForegroundSignature = new Class[]{
            int.class, Notification.class};
    private static final Class<?>[] mStopForegroundSignature = new Class[]{boolean.class};

    private Method mStartForeground;
    private Method mStopForeground;

    private final Object[] mStartForegroundArgs = new Object[2];
    private final Object[] mStopForegroundArgs = new Object[1];

    private ServiceProtocolHost mProtocolHost;
    private ServiceConnectionHost mConnectionHost;
    private ServiceAudioOutputHost mAudioHost;

    // connection parameters
    private String host;
    private int port;
    private String username;
    private String password;
    private byte[] certificate;

    boolean started = false;

    // Service Lifecycle methods
    @Override
    public IBinder onBind(final Intent intent) {
        Log.i(TAG, "MumbleService: onBind");

        return mBinder;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        Log.i(TAG, "onCreate");

        try {
            mStartForeground = getClass().getMethod(
                    "startForeground",
                    mStartForegroundSignature);
            mStopForeground = getClass().getMethod(
                    "stopForeground",
                    mStopForegroundSignature);
        } catch (final NoSuchMethodException e) {
            // Running on an older platform.
            mStartForeground = mStopForeground = null;
        }
        serviceState = CONNECTION_STATE_DISCONNECTED;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        // Make sure our notification is gone.
        hideNotification();

        new Thread(new Runnable() {
            @Override
            public void run() {
                disconnect();
            }
        }).start();

        Log.i(TAG, "MumbleService: Destroyed");
    }

    @Deprecated
    @Override
    public void onStart(final Intent intent, final int startId) {
//        handleCommand(intent);
    }

    @Override
    public int onStartCommand(
            final Intent intent,
            final int flags,
            final int startId) {

        int code = START_STICKY;

        if (!started) code = handleCommand(intent);

        started = true;

        return code;
    }

    public boolean canSpeak() {
        return mProtocol != null && mProtocol.canSpeak;
    }

    public void disconnect() {
        // Call disconnect on the connection.
        // It'll notify us with DISCONNECTED when it's done.
        this.setRecording(false);
        if (mClient != null) {
            mClient.disconnect();
        }
    }

    public List<Channel> getChannelList() {
        return Collections.unmodifiableList(channels);
    }

    public int getCodec() {
        if (mProtocol.codec == MumbleProtocol.CODEC_NOCODEC) {
            throw new IllegalStateException(
                    "Called getCodec on a connection with unsupported codec");
        }

        return mProtocol.codec;
    }

    public int getConnectionState() {
        return serviceState;
    }

    public Channel getCurrentChannel() {
        return mProtocol.currentChannel;
    }

    public User getCurrentUser() {
        return mProtocol.currentUser;
    }

    public String getError() {
        final String r = errorString;
        errorString = null;
        return r;
    }

    public List<Message> getMessageList() {
        return Collections.unmodifiableList(messages);
    }

    public List<User> getUserList() {
        return Collections.unmodifiableList(users);
    }

    public Handler getHandler() {
        return handler;
    }

    public boolean isConnected() {
        return serviceState == CONNECTION_STATE_CONNECTED;
    }

    public boolean isRecording() {
        return (mRecordThread != null);
    }

    public void joinChannel(final int channelId) {
        mProtocol.joinChannel(channelId);
    }

    public void createChannel(Channel channel) {

        MumbleProto.ChannelState.Builder csb = MumbleProto.ChannelState.newBuilder();
        String description = channel.name + " team channel";

        csb.setParent(0);
        csb.setName(channel.name);
        csb.setDescription(description);
        csb.setPosition(channel.id); // test value
        csb.setTemporary(true);

        Log.d(TAG, "creating channel");

        mClient.sendTcpMessage(MumbleProtocol.MessageType.ChannelState, csb);

    }

    public void registerObserver(final IServiceObserver observer) {
        observers.put(observer, observer);
    }

    public void sendChannelTextMessage(final String message, final Channel channel) {
        mProtocol.sendChannelTextMessage(message, channel);
    }

    public void sendUdpMessage(final byte[] buffer, final int length) {
        mClient.sendUdpMessage(buffer, length, false);
    }

    public void setRecording(final boolean state) {
        if (mProtocol != null && mProtocol.currentUser != null &&
                mRecordThread == null && state) {
            // start record
            // TODO check initialized
            mRecordThread = new Thread(new RecordThread(this), "record");
            mRecordThread.start();
            mAudioHost.setTalkState(
                    mProtocol.currentUser,
                    AudioOutputHost.STATE_TALKING);
        } else if (mRecordThread != null && !state) {
            // stop record
            mRecordThread.interrupt();
            mRecordThread = null;
            mAudioHost.setTalkState(
                    mProtocol.currentUser,
                    AudioOutputHost.STATE_PASSIVE);
        }
    }

    public void muteUser(final User user, boolean muted) {
        if (users.contains(user)) {
            user.setMuted(muted);
        }
    }

    public void deafen(boolean deafen) {
        MumbleProto.UserState.Builder usb = MumbleProto.UserState.newBuilder();

        usb.setSession(getCurrentUser().session);
        usb.setSelfDeaf(deafen);
        usb.setSelfMute(false);

        Log.d(TAG, "deafen");
        mClient.sendTcpMessage(MumbleProtocol.MessageType.UserState, usb);
    }

    public void muteSelf(boolean mute) {
        MumbleProto.UserState.Builder usb = MumbleProto.UserState.newBuilder();
        usb.setSession(getCurrentUser().session);
        usb.setSelfMute(mute);

        Log.d(TAG, "muteSelf");
        mClient.sendTcpMessage(MumbleProtocol.MessageType.UserState, usb);
    }

    public void muteAll() {
        // TODO: mute all users
        for (User u : users) {
            muteUser(u, true);
        }
    }

    public void deafenUser(boolean deafen) {
        Log.d(TAG, "MumbleService:deafenUser");
    }

    public void unregisterObserver(final IServiceObserver observer) {
        observers.remove(observer);
    }

    private void broadcastState() {
        for (final IServiceObserver observer : observers.values()) {
            try {
                observer.onConnectionStateChanged(serviceState);
            } catch (final RemoteException e) {
                Log.e(TAG, "Failed to update connection state", e);
            }
        }

        Log.d(TAG, "Number of observers: " + observers.size());

        Log.i(TAG, "MumbleService: Connection state changed to " +
                CONNECTION_STATE_NAMES[serviceState]);
    }

    private synchronized int handleCommand(final Intent intent) {
        // When using START_STICKY the onStartCommand can be called with
        // null intent after the whole service process has been killed.
        // Such scenario doesn't make sense for the service process so
        // returning START_NOT_STICKY for now.
        //
        // Leaving the null check in though just in case.
        //
        // TODO: Figure out the correct start type.
        if (intent == null) {
            return START_STICKY;
        }

        Log.i(TAG, "MumbleService: Starting service");

        host = intent.getStringExtra(EXTRA_HOST);
        port = intent.getIntExtra(EXTRA_PORT, -1);
        username = intent.getStringExtra(EXTRA_USERNAME);
        password = intent.getStringExtra(EXTRA_PASSWORD);
        certificate = intent.getByteArrayExtra(EXTRA_CERTIFICATE);

        if (mClient != null &&
                state != MumbleConnectionHost.STATE_DISCONNECTED &&
                mClient.isSameServer(host, port, username, password)) {

            Log.d(TAG, "Mumble not disconnected");

            return START_STICKY;
        }

        doConnectionDisconnect();

        mProtocolHost = new ServiceProtocolHost();
        mConnectionHost = new ServiceConnectionHost();
        mAudioHost = new ServiceAudioOutputHost();

        mClient = new MumbleConnection(
                mConnectionHost,
                host,
                port,
                username,
                password,
                intent.getByteArrayExtra(EXTRA_CERTIFICATE),
                new char[0]);

        mProtocol = new MumbleProtocol(
                mProtocolHost,
                mAudioHost,
                mClient,
                getApplicationContext());

        mClientThread = mClient.start(mProtocol);

        Log.d(TAG, "handleCommand finished");

        return START_STICKY;
    }

    public void reconnect() {
        Log.v(TAG, "Reconnecting...");

        doConnectionDisconnect();

        mProtocolHost = new ServiceProtocolHost();
        mConnectionHost = new ServiceConnectionHost();
        mAudioHost = new ServiceAudioOutputHost();

        mClient = new MumbleConnection(mConnectionHost, host, port, username, password);

        mProtocol = new MumbleProtocol(mProtocolHost, mAudioHost, mClient, getApplicationContext());

        mClientThread = mClient.start(mProtocol);
    }

    private void doConnectionDisconnect() {
        // First disable all hosts to prevent old callbacks from being processed.
        if (mProtocolHost != null) {
            mProtocolHost.disable();
            mProtocolHost = null;
        }

        if (mConnectionHost != null) {
            mConnectionHost.disable();
            mConnectionHost = null;
        }

        if (mAudioHost != null) {
            mAudioHost.disable();
            mAudioHost = null;
        }

        // Stop threads.
        if (mProtocol != null) {
            mProtocol.stop();
            mProtocol = null;
        }

        if (mClient != null && mClientThread != null) {
            mClient.disconnect();
            try {
                mClientThread.join();
            } catch (final InterruptedException e) {
                mClientThread.interrupt();
            }

            // Leave mClient reference intact as its state might still be queried.
            mClientThread = null;
        }

        // Broadcast state, this is synchronous with observers.
        state = MumbleConnectionHost.STATE_DISCONNECTED;
        updateConnectionState();

        hideNotification();

        // Now observers shouldn't need these anymore.
        users.clear();
        channels.clear();
    }

    public void hideNotification() {
        if (mNotification != null) {
            stopForegroundCompat(1);
            mNotification = null;
        }
    }

    public void showNotification() {
        mNotification = new Notification(
                R.drawable.icon,
                "Comms ready",
                System.currentTimeMillis());

        mNotification.setLatestEventInfo(MumbleService.this, "Overwatch", "Comms ready", null);

        startForegroundCompat(1, mNotification);
    }

    /**
     * This is a wrapper around the new startForeground method, using the older
     * APIs if it is not available.
     */
    void startForegroundCompat(final int id, final Notification notification) {
        // If we have the new startForeground API, then use it.
        if (mStartForeground != null) {
            mStartForegroundArgs[0] = Integer.valueOf(id);
            mStartForegroundArgs[1] = notification;
            try {
                mStartForeground.invoke(this, mStartForegroundArgs);
            } catch (final InvocationTargetException e) {
                // Should not happen.
                Log.w(TAG, "Unable to invoke startForeground", e);
            } catch (final IllegalAccessException e) {
                // Should not happen.
                Log.w(TAG, "Unable to invoke startForeground", e);
            }
            return;
        }

        // Fall back on the old API.
//		setForeground(true);
//		((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(
//			id,
//			notification);
        startForeground(id, notification);
    }

    /**
     * This is a wrapper around the new stopForeground method, using the older
     * APIs if it is not available.
     */
    void stopForegroundCompat(final int id) {
        // If we have the new stopForeground API, then use it.
        if (mStopForeground != null) {
            mStopForegroundArgs[0] = Boolean.TRUE;
            try {
                mStopForeground.invoke(this, mStopForegroundArgs);
            } catch (final InvocationTargetException e) {
                // Should not happen.
                Log.w(TAG, "Unable to invoke stopForeground", e);
            } catch (final IllegalAccessException e) {
                // Should not happen.
                Log.w(TAG, "Unable to invoke stopForeground", e);
            }
            return;
        }

        // Fall back on the old API.  Note to cancel BEFORE changing the
        // foreground state, since we could be killed at that point.
//		((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(id);
//		setForeground(false);
        stopForeground(true);
    }

    void updateConnectionState() {
        final int oldState = serviceState;

        switch (state) {
            case MumbleConnectionHost.STATE_CONNECTING:
                serviceState = CONNECTION_STATE_CONNECTING;
                break;
            case MumbleConnectionHost.STATE_CONNECTED:
                serviceState = synced ? CONNECTION_STATE_CONNECTED
                        : CONNECTION_STATE_SYNCHRONIZING;
                break;
            case MumbleConnectionHost.STATE_DISCONNECTED:
                serviceState = CONNECTION_STATE_DISCONNECTED;
                break;
            default:
                Assert.fail();
        }

        if (oldState != serviceState) {
            broadcastState();
        }
    }

    public int getState() {
        return state;
    }

    public boolean isMumbleConnectionRestarting() {
        if (mClient != null)
            return mClient.isRestarting();
        else
            return false;
    }

    public class LocalBinder extends Binder {
        public MumbleService getService() {
            return MumbleService.this;
        }
    }

    private class ServiceAudioOutputHost extends AbstractHost implements
            AudioOutputHost {
        abstract class ServiceProtocolMessage extends ProtocolMessage {
            @Override
            protected Iterable<IServiceObserver> getObservers() {
                return observers.values();
            }
        }

        @Override
        public void setTalkState(final User user, final int talkState) {
            handler.post(new ServiceProtocolMessage() {
                @Override
                public void process() {
                    user.talkingState = talkState;
                }

                @Override
                protected void broadcast(final IServiceObserver observer)
                        throws RemoteException {
                    observer.onUserUpdated(user);
                }
            });
        }
    }

    private class ServiceConnectionHost extends AbstractHost implements
            MumbleConnectionHost {
        abstract class ServiceProtocolMessage extends ProtocolMessage {
            @Override
            protected Iterable<IServiceObserver> getObservers() {
                return observers.values();
            }
        }

        public void setConnectionState(final int state) {
            handler.post(new ServiceProtocolMessage() {
                @Override
                public void process() {
                    if (MumbleService.this.state == state) {
                        return;
                    }

                    MumbleService.this.state = state;

                    // Handle foreground stuff
                    if (state == MumbleConnectionHost.STATE_CONNECTED) {
//                        showNotification();
                        updateConnectionState();
                    } else if (state == MumbleConnectionHost.STATE_DISCONNECTED) {
                        doConnectionDisconnect();
                    } else {
                        updateConnectionState();
                    }
                }

                @Override
                protected void broadcast(final IServiceObserver observer) {
                }
            });
        }

        @Override
        public void setError(final String error) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    errorString = error;
                }
            });
        }
    }

    /**
     * Connection host for MumbleConnection.
     * <p/>
     * MumbleConnection uses this interface to communicate back to
     * MumbleService. Since MumbleConnection processes the data packets in a
     * background thread these methods will be called from that thread.
     * MumbleService should expose itself as a single threaded Service so its
     * consumers don't need to bother with synchronizing. For this reason these
     * handlers should take care of the required synchronization.
     * <p/>
     * Also it is worth noting that in case a certain handler doesn't need
     * synchronizing for its own purposes it might need it to maintain the order
     * of events. Forwarding the CURRENT_USER_UPDATED event shouldn't be done
     * before the USER_ADDED event has been processed for that user. For this
     * reason even events like the CURRENT_USER_UPDATED are posted to the
     * MumbleService handler.
     */
    private class ServiceProtocolHost extends AbstractHost implements
            MumbleProtocolHost {
        abstract class ServiceProtocolMessage extends ProtocolMessage {
            @Override
            protected Iterable<IServiceObserver> getObservers() {
                return observers.values();
            }
        }

        @Override
        public void channelAdded(final Channel channel) {
            handler.post(new ServiceProtocolMessage() {
                @Override
                public void process() {
                    channels.add(channel);
                }

                @Override
                protected void broadcast(final IServiceObserver observer)
                        throws RemoteException {
                    observer.onChannelAdded(channel);
                }
            });
        }

        @Override
        public void channelRemoved(final int channelId) {
            handler.post(new ServiceProtocolMessage() {
                Channel channel;

                @Override
                public void process() {
                    for (int i = 0; i < channels.size(); i++) {
                        if (channels.get(i).id == channelId) {
                            channel = channels.remove(i);
                            break;
                        }
                    }
                }

                @Override
                protected void broadcast(final IServiceObserver observer)
                        throws RemoteException {
                    observer.onChannelRemoved(channel);
                }
            });
        }

        @Override
        public void channelUpdated(final Channel channel) {
            handler.post(new ServiceProtocolMessage() {
                @Override
                public void process() {
                    for (int i = 0; i < channels.size(); i++) {
                        if (channels.get(i).id == channel.id) {
                            channels.set(i, channel);
                            break;
                        }
                    }
                }

                @Override
                protected void broadcast(final IServiceObserver observer)
                        throws RemoteException {
                    observer.onChannelUpdated(channel);
                }
            });
        }

        public void currentChannelChanged() {
            handler.post(new ServiceProtocolMessage() {
                @Override
                public void process() {
                }

                @Override
                protected void broadcast(final IServiceObserver observer)
                        throws RemoteException {
                    observer.onCurrentChannelChanged();
                }
            });
        }

        @Override
        public void currentUserUpdated() {
            handler.post(new ServiceProtocolMessage() {
                @Override
                public void process() {
                    if (!canSpeak() && isRecording()) {
                        setRecording(false);
                    }
                }

                @Override
                protected void broadcast(final IServiceObserver observer)
                        throws RemoteException {
                    observer.onCurrentUserUpdated();
                }
            });
        }

        public void messageReceived(final Message msg) {
            handler.post(new ServiceProtocolMessage() {
                @Override
                public void process() {
                    messages.add(msg);
                }

                @Override
                protected void broadcast(final IServiceObserver observer)
                        throws RemoteException {
                    observer.onMessageReceived(msg);
                }
            });
        }

        public void messageSent(final Message msg) {
            handler.post(new ServiceProtocolMessage() {
                @Override
                public void process() {
                    messages.add(msg);
                }

                @Override
                protected void broadcast(final IServiceObserver observer)
                        throws RemoteException {
                    observer.onMessageSent(msg);
                }
            });
        }


        @Override
        public void setError(final String error) {
            handler.post(new ServiceProtocolMessage() {
                @Override
                protected void broadcast(final IServiceObserver observer) {
                }

                @Override
                protected void process() {
                    errorString = error;
                }
            });
        }

        @Override
        public void setSynchronized(final boolean synced) {
            handler.post(new ServiceProtocolMessage() {
                @Override
                public void process() {
                    MumbleService.this.synced = synced;
                    updateConnectionState();
                }

                @Override
                protected void broadcast(final IServiceObserver observer) {
                }
            });
        }

        @Override
        public void userAdded(final User user) {
            handler.post(new ServiceProtocolMessage() {
                @Override
                public void process() {
                    users.add(user);
                }

                @Override
                protected void broadcast(final IServiceObserver observer)
                        throws RemoteException {
                    observer.onUserAdded(user);
                }
            });
        }

        @Override
        public void userRemoved(final int userId) {
            handler.post(new ServiceProtocolMessage() {
                private User user;

                @Override
                public void process() {
                    for (int i = 0; i < users.size(); i++) {
                        if (users.get(i).session == userId) {
                            this.user = users.remove(i);
                            return;
                        }
                    }

                    Assert.fail("Non-existant user was removed");
                }

                @Override
                protected void broadcast(final IServiceObserver observer)
                        throws RemoteException {
                    observer.onUserRemoved(user);
                }
            });
        }

        @Override
        public void userUpdated(final User user) {
            handler.post(new ServiceProtocolMessage() {
                @Override
                public void process() {
                    for (int i = 0; i < users.size(); i++) {
                        if (users.get(i).session == user.session) {
                            users.set(i, user);

                            return;
                        }
                    }
                    Assert.fail("Non-existant user was updated");
                }

                @Override
                protected void broadcast(final IServiceObserver observer)
                        throws RemoteException {
                    observer.onUserUpdated(user);
                }
            });
        }

    }

}
