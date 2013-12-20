package org.pcgod.mumbleclient.service;

import android.content.Context;
import android.os.AsyncTask;
import android.util.Log;

import com.google.protobuf.ByteString;

import junit.framework.Assert;

import net.sf.mumble.MumbleProto;
import net.sf.mumble.MumbleProto.ChannelRemove;
import net.sf.mumble.MumbleProto.ChannelState;
import net.sf.mumble.MumbleProto.CodecVersion;
import net.sf.mumble.MumbleProto.CryptSetup;
import net.sf.mumble.MumbleProto.Reject;
import net.sf.mumble.MumbleProto.ServerSync;
import net.sf.mumble.MumbleProto.TextMessage;
import net.sf.mumble.MumbleProto.UserRemove;
import net.sf.mumble.MumbleProto.UserState;

import org.pcgod.mumbleclient.Globals;
import org.pcgod.mumbleclient.service.audio.AudioOutput;
import org.pcgod.mumbleclient.service.audio.AudioOutputHost;
import org.pcgod.mumbleclient.service.model.Channel;
import org.pcgod.mumbleclient.service.model.Message;
import org.pcgod.mumbleclient.service.model.User;
import org.spongycastle.asn1.x500.X500Name;
import org.spongycastle.asn1.x509.SubjectPublicKeyInfo;
import org.spongycastle.cert.X509CertificateHolder;
import org.spongycastle.cert.X509v3CertificateBuilder;
import org.spongycastle.cert.jcajce.JcaX509CertificateConverter;
import org.spongycastle.jce.provider.BouncyCastleProvider;
import org.spongycastle.operator.ContentSigner;
import org.spongycastle.operator.OperatorCreationException;
import org.spongycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

public class MumbleProtocol {

    static {
        Security.insertProviderAt(new BouncyCastleProvider(), 1);
    }


    public enum MessageType {
        Version, UDPTunnel, Authenticate, Ping, Reject, ServerSync, ChannelRemove, ChannelState, UserRemove, UserState, BanList, TextMessage, PermissionDenied, ACL, QueryUsers, CryptSetup, ContextActionAdd, ContextAction, UserList, VoiceTarget, PermissionQuery, CodecVersion, UserStats, RequestBlob, ServerConfig
    }

    public static final String CERTIFICATE = "overwatch-android-certificate";

    public static final int UDPMESSAGETYPE_UDPVOICECELTALPHA = 0;
    public static final int UDPMESSAGETYPE_UDPPING = 1;
    public static final int UDPMESSAGETYPE_UDPVOICESPEEX = 2;
    public static final int UDPMESSAGETYPE_UDPVOICECELTBETA = 3;

    public static final int CODEC_NOCODEC = -1;
    public static final int CODEC_ALPHA = UDPMESSAGETYPE_UDPVOICECELTALPHA;
    public static final int CODEC_BETA = UDPMESSAGETYPE_UDPVOICECELTBETA;

    public static final int SAMPLE_RATE = 48000;
    public static final int FRAME_SIZE = SAMPLE_RATE / 100;

    /**
     * The time window during which the last successful UDP ping must have been
     * transmitted. If the time since the last successful UDP ping is greater
     * than this treshold the connection falls back on TCP tunneling.
     * <p/>
     * NOTE: This is the time when the last successfully received ping was SENT
     * by the client.
     * <p/>
     * 6000 gives 1 second reply-time as the ping interval is 5000 seconds
     * currently.
     */
    public static final int UDP_PING_TRESHOLD = 6000;

    private static final MessageType[] MT_CONSTANTS = MessageType.class.getEnumConstants();

    public Map<Integer, Channel> channels = new HashMap<Integer, Channel>();
    public Map<Integer, User> users = new HashMap<Integer, User>();
    public Channel currentChannel = null;
    public User currentUser = null;
    public boolean canSpeak = true;
    public int codec = CODEC_NOCODEC;
    private final AudioOutputHost audioHost;
    private final Context ctx;

    private AudioOutput ao;
    private Thread audioOutputThread;
    private Thread pingThread;

    private final MumbleProtocolHost host;
    private final MumbleConnection conn;

    private byte[] certificate;

    private boolean stopped = false;
    private boolean hasCert = false;

    public MumbleProtocol(
            final MumbleProtocolHost host,
            final AudioOutputHost audioHost,
            final MumbleConnection connection,
            final Context ctx) {
        this.host = host;
        this.audioHost = audioHost;
        this.conn = connection;
        this.ctx = ctx;

        this.host.setSynchronized(false);

        try {
            // generate certificate
            Log.d("MumbleProtocol", "Retrieving certificate");
            FileInputStream fis = ctx.openFileInput(CERTIFICATE);

            certificate = new byte[fis.available()];

            fis.read(certificate);

            fis.close();

            hasCert = true;

            conn.addCertificate(certificate, null);

        } catch (FileNotFoundException e) {

            new CertificateThread().execute();

            conn.addCertificate(certificate, null);

            Log.w("MumbleProtocol", "No certificate found, generating new certificate");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public final void joinChannel(final int channelId) {
        final UserState.Builder us = UserState.newBuilder();
        us.setSession(currentUser.session);
        us.setChannelId(channelId);
        conn.sendTcpMessage(MessageType.UserState, us);
    }

    public void createChannel(Channel channel) {

        ChannelState.Builder csb = ChannelState.newBuilder();

        csb.setParent(0);
        csb.setName(channel.name);
        csb.setDescription("test channel");
        csb.setPosition(channel.id); // test value
        csb.setTemporary(true);

        Log.d("MumbleProtocol", "creating channel");

        conn.sendTcpMessage(MumbleProtocol.MessageType.ChannelState, csb);
    }

    public void processTcp(final short type, final byte[] buffer)
            throws IOException {
        if (stopped) {
            return;
        }

        final MessageType t = MT_CONSTANTS[type];

        Channel channel;
        User user;

        switch (t) {
            case UDPTunnel:
                processUdp(buffer, buffer.length);
                break;
            case Ping:
                // ignore
                break;
            case CodecVersion:
                final boolean oldCanSpeak = canSpeak;
                final CodecVersion codecVersion = CodecVersion.parseFrom(buffer);
                codec = CODEC_NOCODEC;
                if (codecVersion.hasAlpha() &&
                        codecVersion.getAlpha() == Globals.CELT_VERSION) {
                    codec = CODEC_ALPHA;
                } else if (codecVersion.hasBeta() &&
                        codecVersion.getBeta() == Globals.CELT_VERSION) {
                    codec = CODEC_BETA;
                }
                canSpeak = canSpeak && (codec != CODEC_NOCODEC);

                if (canSpeak != oldCanSpeak) {
                    host.currentUserUpdated();
                }

                break;
            case Reject:
                final Reject reject = Reject.parseFrom(buffer);
                final String errorString = String.format(
                        "Connection rejected: %s",
                        reject.getReason());
                host.setError(errorString);
                Log.e(Globals.LOG_TAG, String.format(
                        "Received Reject message: %s",
                        reject.getReason()));
                break;
            case ServerSync:

                final ServerSync ss = ServerSync.parseFrom(buffer);

                // We do some things that depend on being executed only once here
                // so for now assert that there won't be multiple ServerSyncs.
                Assert.assertNull("A second ServerSync received.", currentUser);

                currentUser = findUser(ss.getSession());
                currentUser.isCurrent = true;
                currentChannel = currentUser.getChannel();

                pingThread = new Thread(new PingThread(conn), "Ping");
                pingThread.start();
                Log.d(Globals.LOG_TAG, ">>> " + t);

                ao = new AudioOutput(ctx, audioHost);
                audioOutputThread = new Thread(ao, "audio output");
                audioOutputThread.start();

                final UserState.Builder usb = UserState.newBuilder();
                usb.setSession(currentUser.session);
                conn.sendTcpMessage(MessageType.UserState, usb);

                host.setSynchronized(true);

                host.currentChannelChanged();
                host.currentUserUpdated();

                break;
            case ChannelState:
                final ChannelState cs = ChannelState.parseFrom(buffer);
                channel = findChannel(cs.getChannelId());
                if (channel != null) {
                    if (cs.hasName()) {
                        channel.name = cs.getName();
                    }
                    host.channelUpdated(channel);
                    break;
                }

                // New channel
                channel = new Channel();
                channel.id = cs.getChannelId();
                channel.name = cs.getName();
                channels.put(channel.id, channel);
                host.channelAdded(channel);
                break;
            case ChannelRemove:
                final ChannelRemove cr = ChannelRemove.parseFrom(buffer);
                channel = findChannel(cr.getChannelId());
                channel.removed = true;
                channels.remove(channel.id);
                host.channelRemoved(channel.id);
                break;
            case UserState:
                final UserState us = UserState.parseFrom(buffer);
                user = findUser(us.getSession());

                boolean added = false;
                boolean currentUserUpdated = false;
                boolean channelUpdated = false;

                if (user == null) {
                    user = new User();
                    user.session = us.getSession();
                    users.put(user.session, user);
                    added = true;

                }

                if (us.hasSelfDeaf() || us.hasSelfMute()) {
                    if (us.getSelfDeaf()) {
                        user.userState = User.USERSTATE_DEAFENED;
                    } else if (us.getSelfMute()) {
                        user.userState = User.USERSTATE_MUTED;
                    } else {
                        user.userState = User.USERSTATE_NONE;
                    }
                }

                if (us.hasMute()) {
                    user.muted = us.getMute();
                    user.userState = user.muted ? User.USERSTATE_MUTED
                            : User.USERSTATE_NONE;
                }

                if (us.hasDeaf()) {
                    user.deafened = us.getDeaf();
                    user.muted |= user.deafened;
                    user.userState = user.deafened ? User.USERSTATE_DEAFENED
                            : (user.muted ? User.USERSTATE_MUTED : User.USERSTATE_NONE);
                }

                if (us.hasSuppress()) {
                    user.userState = us.getSuppress() ? User.USERSTATE_MUTED
                            : User.USERSTATE_NONE;
                }

                if (us.hasName()) {
                    user.name = us.getName();
                }

                if (added || us.hasChannelId()) {
                    user.setChannel(channels.get(us.getChannelId()));
                    channelUpdated = true;
                }

                // If this is the current user, do extra updates on local state.
                if (currentUser != null && us.getSession() == currentUser.session) {
                    if (us.hasMute() || us.hasSuppress()) {
                        // TODO: Check the logic
                        // Currently Mute+Suppress true -> Either of them false results
                        // in canSpeak = true
                        if (us.hasMute()) {
                            canSpeak = (codec != CODEC_NOCODEC) && !us.getMute();
                        }
                        if (us.hasSuppress()) {
                            canSpeak = (codec != CODEC_NOCODEC) &&
                                    !us.getSuppress();
                        }
                    }

                    currentUserUpdated = true;
                }

                if (channelUpdated) {
                    host.channelUpdated(user.getChannel());
                }

                if (added) {
                    host.userAdded(user);
                } else {
                    host.userUpdated(user);
                }

                if (currentUserUpdated) {
                    host.currentUserUpdated();
                }
                if (currentUserUpdated && channelUpdated) {
                    currentChannel = user.getChannel();
                    host.currentChannelChanged();
                }

                // end UserState
                break;
            case UserRemove:
                final UserRemove ur = UserRemove.parseFrom(buffer);
                user = findUser(ur.getSession());
                users.remove(user.session);

                // Remove the user from the channel as well.
                user.getChannel().userCount--;

                host.channelUpdated(user.getChannel());
                host.userRemoved(user.session);
                break;
            case TextMessage:
                handleTextMessage(TextMessage.parseFrom(buffer));
                break;
            case CryptSetup:
                final CryptSetup cryptsetup = CryptSetup.parseFrom(buffer);

                Log.d(Globals.LOG_TAG, "MumbleConnection: CryptSetup");

                if (cryptsetup.hasKey() && cryptsetup.hasClientNonce() &&
                        cryptsetup.hasServerNonce()) {
                    // Full key setup
                    conn.cryptState.setKeys(
                            cryptsetup.getKey().toByteArray(),
                            cryptsetup.getClientNonce().toByteArray(),
                            cryptsetup.getServerNonce().toByteArray());
                } else if (cryptsetup.hasServerNonce()) {
                    // Server syncing its nonce to us.
                    Log.d(Globals.LOG_TAG, "MumbleConnection: Server sending nonce");
                    conn.cryptState.setServerNonce(cryptsetup.getServerNonce().toByteArray());
                } else {
                    // Server wants our nonce.
                    Log.d(
                            Globals.LOG_TAG,
                            "MumbleConnection: Server requesting nonce");
                    final CryptSetup.Builder nonceBuilder = CryptSetup.newBuilder();
                    nonceBuilder.setClientNonce(ByteString.copyFrom(conn.cryptState.getClientNonce()));
                    conn.sendTcpMessage(MessageType.CryptSetup, nonceBuilder);
                }
                break;

            case PermissionDenied:

                MumbleProto.PermissionDenied pd = MumbleProto.PermissionDenied.parseFrom(buffer);

                Log.w("MumbleProtocol", "Permission Denied: Type=" + pd.getType());

                switch (pd.getType()) {

                    case ChannelName:
                        Log.w("MumbleProtocol", "Permission denied: duplicate channel name");

                        // attempt to join channel
                        break;
                    case TemporaryChannel:

                        Log.w("MumbleProtocol", "Permission denied: Temporary channel");

                        Log.w("MumbleProtocol", "Name: " + pd.getName());
                        Log.w("MumbleProtocol", "Session: " + pd.getSession());
                        Log.w("MumbleProtocol", "Permission: " + pd.getPermission());

                        break;
                    default:

                        break;
                }

                break;
            case Version:
                MumbleProto.Version version = MumbleProto.Version.parseFrom(buffer);

                String msg = "version: " + version.getVersion();
                msg += "   OS: " + version.getOs();
                msg += "   OS version: " + version.getOsVersion();
                msg += "   Release: " + version.getRelease();

                Log.v("MumbleProtocol", msg);

                break;
            default:
                Log.w(Globals.LOG_TAG, "unhandled message type " + t);
        }
    }

    public void processUdp(final byte[] buffer, final int length) {
        if (stopped) {
            return;
        }

        final int type = buffer[0] >> 5 & 0x7;
        if (type == UDPMESSAGETYPE_UDPPING) {
            final long timestamp = ((long) (buffer[1] & 0xFF) << 56) |
                    ((long) (buffer[2] & 0xFF) << 48) |
                    ((long) (buffer[3] & 0xFF) << 40) |
                    ((long) (buffer[4] & 0xFF) << 32) |
                    ((long) (buffer[5] & 0xFF) << 24) |
                    ((long) (buffer[6] & 0xFF) << 16) |
                    ((long) (buffer[7] & 0xFF) << 8) |
                    ((buffer[8] & 0xFF));

            conn.refreshUdpLimit(timestamp + UDP_PING_TRESHOLD);
        } else {
            processVoicePacket(buffer);
        }
    }

    public final void sendChannelTextMessage(
            final String message,
            final Channel channel) {
        final TextMessage.Builder tmb = TextMessage.newBuilder();
        tmb.addChannelId(channel.id);
        tmb.setMessage(message);
        conn.sendTcpMessage(MessageType.TextMessage, tmb);

        final Message msg = new Message();
        msg.timestamp = System.currentTimeMillis();
        msg.message = message;
        msg.channel = channel;
        msg.direction = Message.DIRECTION_SENT;
        host.messageSent(msg);
    }

    public void stop() {
        stopped = true;
        stopThreads();
    }

    private Channel findChannel(final int id) {
        return channels.get(id);
    }

    private User findUser(final int session_) {
        return users.get(session_);
    }

    private void handleTextMessage(final TextMessage ts) {
        User u = null;
        if (ts.hasActor()) {
            u = findUser(ts.getActor());
        }

        final Message msg = new Message();
        msg.timestamp = System.currentTimeMillis();
        msg.message = ts.getMessage();
        msg.actor = u;
        msg.direction = Message.DIRECTION_RECEIVED;
        msg.channelIds = ts.getChannelIdCount();
        msg.treeIds = ts.getTreeIdCount();
        host.messageReceived(msg);
    }

    private void processVoicePacket(final byte[] buffer) {
        final int type = buffer[0] >> 5 & 0x7;
        final int flags = buffer[0] & 0x1f;

        // There is no speex support...
        if (type != UDPMESSAGETYPE_UDPVOICECELTALPHA &&
                type != UDPMESSAGETYPE_UDPVOICECELTBETA) {
            return;
        }

        // Don't try to decode the unsupported codec version.
        if (type != codec) {
            return;
        }

        final PacketDataStream pds = new PacketDataStream(buffer);
        // skip type / flags
        pds.skip(1);
        final long uiSession = pds.readLong();

        final User u = findUser((int) uiSession);
        if (u == null) {
            Log.e(Globals.LOG_TAG, "User session " + uiSession + " not found!");

            // This might happen if user leaves while there are still UDP packets
            // en route to the clients. In this case we should just ignore these
            // packets.
            return;
        }

        // Rewind the packet. Otherwise consumers are confusing to implement.
        pds.rewind();
        ao.addFrameToBuffer(u, pds, flags);
    }

    private void stopThreads() {
        if (ao != null) {
            ao.stop();
            try {
                audioOutputThread.join();
            } catch (final InterruptedException e) {
                Log.e(
                        Globals.LOG_TAG,
                        "Interrupted while waiting for audio thread to end",
                        e);
            }
        }

        if (pingThread != null) {
            pingThread.interrupt();
            try {
                pingThread.join();
            } catch (final InterruptedException e) {
                Log.e(
                        Globals.LOG_TAG,
                        "Interrupted while waiting for ping thread to end",
                        e);
            }
        }
    }

    public boolean hasCertificate() {
        return hasCert;
    }

    public byte[] getCertificate() {
        if (certificate.length == 0) {
            Log.d("MumbleProtocol", "Certificate is null");
        }
        return certificate;
    }

    private class CertificateThread extends AsyncTask<Void, Void, X509Certificate> {

        private final String ISSUER = "CN=Overwatch Android";
        private final Integer YEARS_VALID = 20;

        @Override
        protected X509Certificate doInBackground(Void... params) {

            X509Certificate cert = null;

            FileOutputStream out = null;

            if (!hasCert) {

                hasCert = true;

                try {
                    Log.d("Mumble: CertificateThread", "Creating Certificate");

                    out = ctx.openFileOutput(CERTIFICATE, Context.MODE_PRIVATE);

                    cert = generateCertificate(out);

                    out.close();
                } catch (FileNotFoundException e) {
                    e.printStackTrace();
                } catch (OperatorCreationException e) {
                    e.printStackTrace();
                } catch (CertificateException e) {
                    e.printStackTrace();
                } catch (NoSuchAlgorithmException e) {
                    e.printStackTrace();
                } catch (KeyStoreException e) {
                    e.printStackTrace();
                } catch (IOException e) {
                    e.printStackTrace();
                } finally {
                    if (out != null) {
                        try {
                            out.close();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                }
            } else {
                Log.d("MumbleProtocol", "Certificate already created");
            }

            return cert;
        }

        @Override
        public void onPostExecute(X509Certificate result) {
            Log.d("MumbleProtocol", "Certificate created: ");
            Log.d("MumbleProtocol", result.toString());
        }

        private X509Certificate generateCertificate(OutputStream out) throws NoSuchAlgorithmException, OperatorCreationException, CertificateException, KeyStoreException, IOException {

            BouncyCastleProvider provider = new BouncyCastleProvider();
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048, new SecureRandom());

            KeyPair keyPair = generator.generateKeyPair();

            SubjectPublicKeyInfo publicKeyInfo = SubjectPublicKeyInfo.getInstance(keyPair.getPublic().getEncoded());
            ContentSigner signer = new JcaContentSignerBuilder("SHA1withRSA").setProvider(provider).build(keyPair.getPrivate());

            Date startDate = new Date();
            Calendar calendar = Calendar.getInstance();
            calendar.setTime(startDate);
            calendar.add(Calendar.YEAR, YEARS_VALID);
            Date endDate = calendar.getTime();

            X509v3CertificateBuilder certBuilder = new X509v3CertificateBuilder(new X500Name(ISSUER),
                    BigInteger.ONE, startDate, endDate, new X500Name(ISSUER),
                    publicKeyInfo);

            X509CertificateHolder certificateHolder = certBuilder.build(signer);

            X509Certificate certificate = new JcaX509CertificateConverter().setProvider(provider).getCertificate(certificateHolder);

            KeyStore keyStore = KeyStore.getInstance("PKCS12", provider);
            keyStore.load(null, null);
            keyStore.setKeyEntry("overwatch", keyPair.getPrivate(), null, new X509Certificate[]{certificate});

            keyStore.store(out, "".toCharArray());

            return certificate;
        }
    }
}
