/**
 QuasselDroid - Quassel client for Android
 Copyright (C) 2011 Martin Sandsmark <martin.sandsmark@kde.org>

 This program is free software: you can redistribute it and/or modify it
 under the terms of the GNU General Public License as published by the Free
 Software Foundation, either version 3 of the License, or (at your option)
 any later version, or under the terms of the GNU Lesser General Public
 License as published by the Free Software Foundation; either version 2.1 of
 the License, or (at your option) any later version.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License and the
 GNU Lesser General Public License along with this program.  If not, see
 <http://www.gnu.org/licenses/>.
 */

package com.iskrembilen.quasseldroid.io;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Message;
import android.preference.PreferenceManager;
import android.util.Log;
import android.util.Pair;
import android.widget.AbsListView;

import com.iskrembilen.quasseldroid.Buffer;
import com.iskrembilen.quasseldroid.BufferCollection;
import com.iskrembilen.quasseldroid.BufferInfo;
import com.iskrembilen.quasseldroid.CoreInfo;
import com.iskrembilen.quasseldroid.IrcMessage;
import com.iskrembilen.quasseldroid.IrcUser;
import com.iskrembilen.quasseldroid.Network;
import com.iskrembilen.quasseldroid.Network.ConnectionState;
import com.iskrembilen.quasseldroid.R;
import com.iskrembilen.quasseldroid.exceptions.UnsupportedProtocolException;
import com.iskrembilen.quasseldroid.io.CustomTrustManager.NewCertificateException;
import com.iskrembilen.quasseldroid.qtcomm.EmptyQVariantException;
import com.iskrembilen.quasseldroid.qtcomm.QDataInputStream;
import com.iskrembilen.quasseldroid.qtcomm.QDataOutputStream;
import com.iskrembilen.quasseldroid.qtcomm.QMetaType;
import com.iskrembilen.quasseldroid.qtcomm.QMetaTypeRegistry;
import com.iskrembilen.quasseldroid.qtcomm.QVariant;
import com.iskrembilen.quasseldroid.qtcomm.QVariantType;
import com.iskrembilen.quasseldroid.service.CoreConnService;
import com.iskrembilen.quasseldroid.util.MessageUtil;
import com.iskrembilen.quasseldroid.util.NetsplitHelper;
import com.iskrembilen.quasseldroid.util.QuasseldroidNotificationManager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.security.GeneralSecurityException;
import java.security.cert.CertificateException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.SocketFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;

public final class CoreConnection {

    private static final String TAG = CoreConnection.class.getSimpleName();

    private Socket socket;
    private QDataOutputStream outStream;
    private QDataInputStream inStream;
    private SwitchableDeflaterOutputStream deflater;
    private SwitchableInflaterInputStream inflater;

    private Map<Integer, Buffer> buffers;
    private CoreInfo coreInfo;
    private Map<Integer, Network> networks;

    private long coreId;
    private String address;
    private int port;
    private String username;
    private String password;
    private String clientVersion;

    private Handler handler;
    public Context applicationContext;
    private QuasseldroidNotificationManager notificationManager;
    private Timer heartbeatTimer;
    private ReadThread readThread;

    private boolean initComplete;
    private int networkInitsLeft;
    private boolean networkInitComplete;
    private LinkedList<List<QVariant<?>>> packageQueue;
    private String errorMessage;

    private int bufferViewId;

    //Used to create the ID of new channels we join
    private int maxBufferId = 0;

    private boolean usingSSL = false;
    private boolean usingCompression = false;

    private ExecutorService outputExecutor;


    public CoreConnection(long coreId, String address, int port, String username,
                          String password, String clientVersion, Handler serviceHandler,
                          Context appContext, QuasseldroidNotificationManager notificationManager) {
        this.coreId = coreId;
        this.address = address;
        this.port = port;
        this.username = username;
        this.password = password;
        this.handler = serviceHandler;
        this.clientVersion = clientVersion;
        this.applicationContext = appContext;
        this.notificationManager = notificationManager;
        outputExecutor = Executors.newSingleThreadExecutor();

        readThread = new ReadThread();
        readThread.start();
    }

    /**
     * Checks whether the core is available.
     */
    public boolean isConnected() {
        return (socket != null && !socket.isClosed() && readThread.running);
    }

    /**
     * requests the core to set a given buffer as read
     *
     * @param buffer the buffer id to set as read
     */
    public void requestMarkBufferAsRead(int buffer) {
        List<QVariant<?>> retFunc = new LinkedList<QVariant<?>>();
        retFunc.add(new QVariant<Integer>(RequestType.Sync.getValue(), QVariantType.Int));
        retFunc.add(new QVariant<String>("BufferSyncer", QVariantType.String));
        retFunc.add(new QVariant<String>("", QVariantType.String));
        retFunc.add(new QVariant<String>("requestMarkBufferAsRead", QVariantType.ByteArray));
        retFunc.add(new QVariant<Integer>(buffer, "BufferId"));

        try {
            sendQVariantList(retFunc);
        } catch (IOException e) {
            Log.e(TAG, "IOException", e);
            onDisconnected("Lost connection");
        }
    }

    public void requestRemoveBuffer(int buffer) {
        List<QVariant<?>> retFunc = new LinkedList<QVariant<?>>();
        retFunc.add(new QVariant<Integer>(RequestType.Sync.getValue(), QVariantType.Int));
        retFunc.add(new QVariant<String>("BufferSyncer", QVariantType.String));
        retFunc.add(new QVariant<String>("", QVariantType.String));
        retFunc.add(new QVariant<String>("requestRemoveBuffer", QVariantType.ByteArray));
        retFunc.add(new QVariant<Integer>(buffer, "BufferId"));

        try {
            sendQVariantList(retFunc);
        } catch (IOException e) {
            Log.e(TAG, "IOException", e);
            onDisconnected("Lost connection");
        }
    }

    public void requestTempHideBuffer(int bufferId) {
        List<QVariant<?>> retFunc = new LinkedList<QVariant<?>>();
        retFunc.add(new QVariant<Integer>(RequestType.Sync.getValue(), QVariantType.Int));
        retFunc.add(new QVariant<String>("BufferViewConfig", QVariantType.String));
        retFunc.add(new QVariant<String>(Integer.toString(bufferViewId), QVariantType.String));
        retFunc.add(new QVariant<String>("requestRemoveBuffer", QVariantType.String));
        retFunc.add(new QVariant<Integer>(bufferId, "BufferId"));

        try {
            sendQVariantList(retFunc);
        } catch (IOException e) {
            Log.e(TAG, "IOException while requestRemoveBuffer", e);
            onDisconnected("Lost connection");
        }
    }

    public void requestPermHideBuffer(int bufferId) {
        List<QVariant<?>> retFunc = new LinkedList<QVariant<?>>();
        retFunc.add(new QVariant<Integer>(RequestType.Sync.getValue(), QVariantType.Int));
        retFunc.add(new QVariant<String>("BufferViewConfig", QVariantType.String));
        retFunc.add(new QVariant<String>(Integer.toString(bufferViewId), QVariantType.String));
        retFunc.add(new QVariant<String>("requestRemoveBufferPermanently", QVariantType.String));
        retFunc.add(new QVariant<Integer>(bufferId, "BufferId"));

        try {
            sendQVariantList(retFunc);
        } catch (IOException e) {
            Log.e(TAG, "IOException while requestRemoveBufferPermanently", e);
            onDisconnected("Lost connection");
        }
    }

    public void requestDisconnectNetwork(int networkId) {
        List<QVariant<?>> retFunc = new LinkedList<QVariant<?>>();
        retFunc.add(new QVariant<Integer>(RequestType.Sync.getValue(), QVariantType.Int));
        retFunc.add(new QVariant<String>("Network", QVariantType.String));
        retFunc.add(new QVariant<String>(Integer.toString(networkId), QVariantType.String));
        retFunc.add(new QVariant<String>("requestDisconnect", QVariantType.ByteArray));

        try {
            sendQVariantList(retFunc);
        } catch (IOException e) {
            Log.e(TAG, "IOException", e);
            onDisconnected("Lost connection");
        }
    }

    public void requestConnectNetwork(int networkId) {
        List<QVariant<?>> retFunc = new LinkedList<QVariant<?>>();
        retFunc.add(new QVariant<Integer>(RequestType.Sync.getValue(), QVariantType.Int));
        retFunc.add(new QVariant<String>("Network", QVariantType.String));
        retFunc.add(new QVariant<String>(Integer.toString(networkId), QVariantType.String));
        retFunc.add(new QVariant<String>("requestConnect", QVariantType.ByteArray));

        try {
            sendQVariantList(retFunc);
        } catch (IOException e) {
            Log.e(TAG, "IOException", e);
            onDisconnected("Lost connection");
        }
    }

    public void requestSetLastMsgRead(int buffer, int msgid) {
        List<QVariant<?>> retFunc = new LinkedList<QVariant<?>>();
        retFunc.add(new QVariant<Integer>(RequestType.Sync.getValue(), QVariantType.Int));
        retFunc.add(new QVariant<String>("BufferSyncer", QVariantType.String));
        retFunc.add(new QVariant<String>("", QVariantType.String));
        retFunc.add(new QVariant<String>("requestSetLastSeenMsg", QVariantType.ByteArray));
        retFunc.add(new QVariant<Integer>(buffer, "BufferId"));
        retFunc.add(new QVariant<Integer>(msgid, "MsgId"));

        try {
            sendQVariantList(retFunc);
        } catch (IOException e) {
            Log.e(TAG, "IOException", e);
            onDisconnected("Lost connection");
        }
    }

    public void requestSetMarkerLine(int buffer, int msgid) {
        List<QVariant<?>> retFunc = new LinkedList<QVariant<?>>();
        retFunc.add(new QVariant<Integer>(RequestType.Sync.getValue(), QVariantType.Int));
        retFunc.add(new QVariant<String>("BufferSyncer", QVariantType.String));
        retFunc.add(new QVariant<String>("", QVariantType.String));
        retFunc.add(new QVariant<String>("requestSetMarkerLine", QVariantType.ByteArray));
        retFunc.add(new QVariant<Integer>(buffer, "BufferId"));
        retFunc.add(new QVariant<Integer>(msgid, "MsgId"));

        try {
            sendQVariantList(retFunc);
        } catch (IOException e) {
            Log.e(TAG, "IOException", e);
            onDisconnected("Lost connection");
        }
    }


    /**
     * Requests to unhide a temporarily hidden buffer
     */
    public void requestUnhideTempHiddenBuffer(int bufferId) {
        List<QVariant<?>> retFunc = new LinkedList<QVariant<?>>();
        retFunc.add(new QVariant<Integer>(RequestType.Sync.getValue(), QVariantType.Int));
        retFunc.add(new QVariant<String>("BufferViewConfig", QVariantType.String));
        retFunc.add(new QVariant<String>(Integer.toString(bufferViewId), QVariantType.String));
        retFunc.add(new QVariant<String>("requestAddBuffer", QVariantType.String));
        retFunc.add(new QVariant<Integer>(bufferId, "BufferId"));
        retFunc.add(new QVariant<Integer>(networks.get(buffers.get(bufferId).getInfo().networkId).getBufferCount(), QVariantType.Int));

        try {
            sendQVariantList(retFunc);
        } catch (IOException e) {
            Log.e(TAG, "IOException while requesting backlog", e);
            onDisconnected("Lost connection");
        }
    }
    
    /**
     * Requests to unhide a permanently hidden buffer
     */
    public void requestUnhidePermHiddenBuffer(int bufferId) {
        List<QVariant<?>> retFunc = new LinkedList<QVariant<?>>();
        retFunc.add(new QVariant<Integer>(RequestType.Sync.getValue(), QVariantType.Int));
        retFunc.add(new QVariant<String>("BufferViewConfig", QVariantType.String));
        retFunc.add(new QVariant<String>(Integer.toString(bufferViewId), QVariantType.String));
        retFunc.add(new QVariant<String>("requestAddBuffer", QVariantType.String));
        retFunc.add(new QVariant<Integer>(bufferId, "BufferId"));
        retFunc.add(new QVariant<Integer>(networks.get(buffers.get(bufferId).getInfo().networkId).getBufferCount(), QVariantType.Int));

        try {
            sendQVariantList(retFunc);
        } catch (IOException e) {
            Log.e(TAG, "IOException while requesting backlog", e);
            onDisconnected("Lost connection");
        }
    }

    /**
     * Requests more backlog for a give buffer
     *
     * @param buffer Buffer id to request more for
     */
    public void requestMoreBacklog(int buffer, int amount) {
        if (buffers.get(buffer).getUnfilteredSize() == 0) {
            requestBacklog(buffer, -1, -1, amount);
        } else {
//			Log.e(TAG, "GETTING: "+buffers.get(buffer).getUnfilteredBacklogEntry(0).messageId);
            requestBacklog(buffer, -1, buffers.get(buffer).getUnfilteredBacklogEntry(0).messageId, amount);
        }
    }

    /**
     * Requests all backlog from a given message ID until the current.
     */
    private void requestBacklog(int buffer, int firstMsgId) {
        requestBacklog(buffer, firstMsgId, -1);
    }

    /**
     * Requests backlog between two given message IDs.
     */
    private void requestBacklog(int buffer, int firstMsgId, int lastMsgId) {
        requestBacklog(buffer, firstMsgId, lastMsgId, -1);
    }

    private void requestBacklog(int buffer, int firstMsgId, int lastMsgId, int maxAmount) {
        List<QVariant<?>> retFunc = new LinkedList<QVariant<?>>();
        retFunc.add(new QVariant<Integer>(RequestType.Sync.getValue(), QVariantType.Int));
        retFunc.add(new QVariant<String>("BacklogManager", QVariantType.String));
        retFunc.add(new QVariant<String>("", QVariantType.String));
        retFunc.add(new QVariant<String>("requestBacklog", QVariantType.String));
        retFunc.add(new QVariant<Integer>(buffer, "BufferId"));
        retFunc.add(new QVariant<Integer>(firstMsgId, "MsgId"));
        retFunc.add(new QVariant<Integer>(lastMsgId, "MsgId"));
        retFunc.add(new QVariant<Integer>(maxAmount, QVariantType.Int));
        retFunc.add(new QVariant<Integer>(0, QVariantType.Int));

        try {
            sendQVariantList(retFunc);
        } catch (IOException e) {
            Log.e(TAG, "IOException while requesting backlog", e);
            onDisconnected("Lost connection");
        }
    }

    /**
     * Sends an IRC message to a given buffer
     *
     * @param buffer  buffer to send to
     * @param message content of message
     */
    public void sendMessage(int buffer, String message) {
        List<QVariant<?>> retFunc = new LinkedList<QVariant<?>>();
        retFunc.add(new QVariant<Integer>(RequestType.RpcCall.getValue(), QVariantType.Int));
        retFunc.add(new QVariant<String>("2sendInput(BufferInfo,QString)", QVariantType.String));
        retFunc.add(new QVariant<BufferInfo>(buffers.get(buffer).getInfo(), "BufferInfo"));
        retFunc.add(new QVariant<String>(message, QVariantType.String));
        try {
            sendQVariantList(retFunc);
        } catch (IOException e) {
            Log.e(TAG, "IOException while sending message", e);
            onDisconnected("Lost connection");
        }
        
        
        /* Check to unhide hidden buffer upon attempting to join */
    	if (message.startsWith("/join ")) {
    		Network currentNetwork = networks.get(buffers.get(buffer).getInfo().networkId);
    		Buffer targetBuffer = currentNetwork.getBuffers().getBuffer(message.split(" ")[1]);
    		if (targetBuffer != null) {
	    		if (targetBuffer.isPermanentlyHidden()) {
                    requestUnhidePermHiddenBuffer(targetBuffer.getInfo().id);
	        	} else if(targetBuffer.isTemporarilyHidden()) {
                    requestUnhideTempHiddenBuffer(targetBuffer.getInfo().id);
                }
    		}          
    	}
    }


    /**
     * Initiates a connection.
     *
     * @throws EmptyQVariantException
     * @throws UnsupportedProtocolException
     */
    @SuppressWarnings("unchecked")
    public void connect() throws UnknownHostException, IOException, GeneralSecurityException, CertificateException, NewCertificateException, EmptyQVariantException, UnsupportedProtocolException {
        updateInitProgress("Connecting...");
        // START CREATE SOCKETS
        //SocketFactory factory = (SocketFactory) SocketFactory.getDefault();
        socket = new Socket();
        socket.setKeepAlive(true);
        socket.connect(new InetSocketAddress(address, port), 10000);
        outStream = new QDataOutputStream(socket.getOutputStream());
        inStream = new QDataInputStream(socket.getInputStream());
        // END CREATE SOCKETS

        updateInitProgress("Attempting new-style handshake...");

        //Calculate and send magic value
        long magic = 0x42b33f00;
        magic = magic | 0x01;   //0x01 is encryption

        //DeflaterOutputStream SYNC_FLUSH support is required for compression, but Eclair doesn't support that.
        magic = magic | 0x02;   //0x02 is compression

        outStream.writeUInt(magic, 32);

        //Send supported protocols
        outStream.writeUInt(0x01, 32);      //0x01 is Legacy Protocol
        outStream.writeUInt(0x01 << 31, 32); //Bit 31 set to indicate end of list

        //Attempt to read core's response
        try {
            long responseValue = inStream.readUInt(32);

            //Check to make sure legacy protocol is in use
            if ((responseValue & 0x01) == 0) {
                throw new UnsupportedProtocolException("Core claims not to support legacy protocol!");
            }

            //Check if Encryption should be used
            if (((responseValue >> 24) & 0x01) > 0) {
                usingSSL = true;
            }

            //Check if Compression should be used
            if (((responseValue >> 24) & 0x02) > 0) {
                usingCompression = true;
            }
        } catch (IOException e) {
            //This means that the core supports only the legacy handshake, so reopen the connection
            //and try again.
            updateInitProgress("Legacy core detected, falling back to legacy handshake...");
            socket = new Socket();
            socket.setKeepAlive(true);
            socket.connect(new InetSocketAddress(address, port), 10000);
            outStream = new QDataOutputStream(socket.getOutputStream());
            inStream = new QDataInputStream(socket.getInputStream());
        }

        if (usingCompression) {
            Log.d(TAG, "Using compression.");
            deflater = new SwitchableDeflaterOutputStream(socket.getOutputStream());
            inflater = new SwitchableInflaterInputStream(socket.getInputStream());
            outStream = new QDataOutputStream(deflater);
            inStream = new QDataInputStream(inflater);
        }

        // START CLIENT INFO
        updateInitProgress("Sending client info...");
        Map<String, QVariant<?>> initial = new HashMap<String, QVariant<?>>();

        DateFormat dateFormat = new SimpleDateFormat("MMM dd yyyy HH:mm:ss", Locale.US);
        Date date = new Date();
        initial.put("ClientDate", new QVariant<String>(dateFormat.format(date), QVariantType.String));
        initial.put("UseSsl", new QVariant<Boolean>(true, QVariantType.Bool));
        initial.put("ClientVersion", new QVariant<String>("Quasseldroid " + clientVersion, QVariantType.String));
        initial.put("UseCompression", new QVariant<Boolean>(false, QVariantType.Bool));
        initial.put("MsgType", new QVariant<String>("ClientInit", QVariantType.String));
        initial.put("ProtocolVersion", new QVariant<Integer>(10, QVariantType.Int));

        sendQVariantMap(initial);
        // END CLIENT INFO

        // START CORE INFO
        updateInitProgress("Getting core info...");
        Map<String, QVariant<?>> reply = readQVariantMap();
        if(reply.get("MsgType").toString().equals("ClientInitAck")){
            coreInfo = new CoreInfo();
            coreInfo.setCoreInfo((String) reply.get("CoreInfo").getData());
            coreInfo.setSupportSsl((Boolean) reply.get("SupportSsl").getData());
            coreInfo.setConfigured((Boolean) reply.get("Configured").getData());
            coreInfo.setLoginEnabled((Boolean) reply.get("LoginEnabled").getData());
            coreInfo.setMsgType((String) reply.get("MsgType").getData());
            coreInfo.setProtocolVersion(((Long) reply.get("ProtocolVersion").getData()).intValue());
            coreInfo.setSupportsCompression((Boolean) reply.get("SupportsCompression").getData());
        }else{
            if(reply.get("MsgType").toString().equals("ClientInitReject")){
                throw new IOException((String) reply.get("Error").getData());
            }else{
                throw new IOException("Core sent unexpected \"" + reply.get("MsgType").toString() + "\" response!");
            }
        }

        if (!usingSSL && coreInfo.isSupportSsl()) {
            usingSSL = true;
        }

        //Check that the protocol version is at least 10
        if (coreInfo.getProtocolVersion() < 10)
            throw new UnsupportedProtocolException("Protocol version is old: " + coreInfo.getProtocolVersion());
        // END CORE INFO

        // START SSL CONNECTION
        if (usingSSL) {
            Log.d(TAG, "Using SSL.");
            SSLContext sslContext = SSLContext.getInstance("TLS");
            TrustManager[] trustManagers = new TrustManager[]{new CustomTrustManager(this)};
            sslContext.init(null, trustManagers, null);
            SSLSocketFactory sslSocketFactory = sslContext.getSocketFactory();
            SSLSocket sslSocket = (SSLSocket) sslSocketFactory.createSocket(socket, address, port, true);

            sslSocket.setUseClientMode(true);
            updateInitProgress("Starting SSL handshake...");
            sslSocket.startHandshake();
            Log.d(TAG, "SSL handshake complete.");

            if (usingCompression) {
                deflater.setOutputStream(sslSocket.getOutputStream());
                inflater.setInputStream(sslSocket.getInputStream());
            } else {
                outStream = new QDataOutputStream(sslSocket.getOutputStream());
                inStream = new QDataInputStream(sslSocket.getInputStream());
            }
            socket = sslSocket;
        } else {
            Log.w(TAG, "Core does not support SSL!");
        }
        // FINISHED SSL CONNECTION

        // START LOGIN
        updateInitProgress("Logging in...");
        Map<String, QVariant<?>> login = new HashMap<String, QVariant<?>>();
        login.put("MsgType", new QVariant<String>("ClientLogin", QVariantType.String));
        login.put("User", new QVariant<String>(username, QVariantType.String));
        login.put("Password", new QVariant<String>(password, QVariantType.String));
        sendQVariantMap(login);
        // FINISH LOGIN


        // START LOGIN ACK
        reply = readQVariantMap();
        if (!reply.get("MsgType").toString().equals("ClientLoginAck"))
            throw new GeneralSecurityException("Invalid password?");
        // END LOGIN ACK

        // START SESSION INIT
        updateInitProgress("Receiving session state...");
        reply = readQVariantMap();
		/*System.out.println("SESSION INIT: ");
		for (String key : reply.keySet()) {
			System.out.println("\t" + key + " : " + reply.get(key));
		}*/

        Map<String, QVariant<?>> sessionState = (Map<String, QVariant<?>>) reply.get("SessionState").getData();
        List<QVariant<?>> networkIds = (List<QVariant<?>>) sessionState.get("NetworkIds").getData();
        networks = new HashMap<Integer, Network>(networkIds.size());
        for (QVariant<?> networkId : networkIds) {
            Integer id = (Integer) networkId.getData();
            networks.put(id, new Network(id));
        }

        List<QVariant<?>> bufferInfos = (List<QVariant<?>>) sessionState.get("BufferInfos").getData();
        buffers = new HashMap<Integer, Buffer>(bufferInfos.size());
        QuasselDbHelper dbHelper = new QuasselDbHelper(applicationContext);
        ArrayList<Integer> bufferIds = new ArrayList<Integer>();
        for (QVariant<?> bufferInfoQV : bufferInfos) {
            BufferInfo bufferInfo = (BufferInfo) bufferInfoQV.getData();
            Buffer buffer = new Buffer(bufferInfo, dbHelper);
            buffers.put(bufferInfo.id, buffer);
            if (bufferInfo.type == BufferInfo.Type.StatusBuffer) {
                networks.get(bufferInfo.networkId).setStatusBuffer(buffer);
            } else {
                networks.get(bufferInfo.networkId).addBuffer(buffer);
            }
            bufferIds.add(bufferInfo.id);
        }
        dbHelper.open();
        dbHelper.cleanupEvents(bufferIds.toArray(new Integer[bufferIds.size()]));
        dbHelper.close();

        // END SESSION INIT

        // Now the fun part starts, where we play signal proxy

        // START SIGNAL PROXY INIT

        updateInitProgress("Requesting network and buffer information...");
        // We must do this here, to get network names early enough
        networkInitsLeft = 0;
        networkInitComplete = false;
        for (Network network : networks.values()) {
            networkInitsLeft += 1;
            sendInitRequest("Network", Integer.toString(network.getId()));
        }
        sendInitRequest("BufferSyncer", "");
        sendInitRequest("BufferViewManager", "");
        SharedPreferences options = PreferenceManager.getDefaultSharedPreferences(applicationContext);

        //Get backlog if user selected a fixed amount
        if (!options.getBoolean(applicationContext.getString(R.string.preference_fetch_to_last_seen), false)) {
            int backlogAmount = Integer.parseInt(options.getString(applicationContext.getString(R.string.preference_initial_backlog_limit), "1"));
            for (Buffer buffer : buffers.values()) {
                requestMoreBacklog(buffer.getInfo().id, backlogAmount);
            }
        }

        TimerTask sendPingAction = new TimerTask() {
            public void run() {
                List<QVariant<?>> packedFunc = new LinkedList<QVariant<?>>();
                packedFunc.add(new QVariant<Integer>(RequestType.HeartBeat.getValue(), QVariantType.Int));
                packedFunc.add(new QVariant<Calendar>(Calendar.getInstance(), QVariantType.Time));
                Log.d(TAG, "Sending heartbeat");
                try {
                    sendQVariantList(packedFunc);
                } catch (IOException e) {
                    Log.e(TAG, "IOException while sending ping", e);
                    onDisconnected("Lost connection");
                }
            }
        };
        heartbeatTimer = new Timer();
        heartbeatTimer.schedule(sendPingAction, 30000, 30000); // Send heartbeats every 30 seconds

        // END SIGNAL PROXY
        updateInitProgress("Connection established, waiting on networks...");

        // Notify the UI we have an open socket
        Message msg = handler.obtainMessage(R.id.CONNECTING);
        msg.sendToTarget();

        initComplete = false;
    }

    public void closeConnection() {
        readThread.running = false; //tell the while loop to quit
        Thread closeThread = new Thread(new Runnable() {
            public void run() {
                try {
                    if (socket != null)
                        socket.close();
                } catch (IOException e) {
                    Log.w(TAG, "IOException while closing socket", e);
                }
            }
        });
        closeThread.start();
    }

    /**
     * Disconnect from the core, as best as we can.
     */
    public synchronized void onDisconnected(String informationMessage) {
        Log.d(TAG, "Disconnected so closing connection");
        errorMessage = informationMessage;
        closeConnection();
    }

    /**
     * Type of a given request (should be pretty self-explanatory).
     */
    private enum RequestType {
        Invalid(0),
        Sync(1),
        RpcCall(2),
        InitRequest(3),
        InitData(4),
        HeartBeat(5),
        HeartBeatReply(6);

        // Below this line; java sucks. Hard.
        int value;

        RequestType(int value) {
            this.value = value;
        }

        public int getValue() {
            return value;
        }

        public static RequestType getForVal(int val) {
            for (RequestType type : values()) {
                if (type.value == val)
                    return type;
            }
            return Invalid;
        }
    }

    /**
     * Convenience function to send a given QVariant.
     *
     * @param data QVariant to send.
     */
    private synchronized void sendQVariant(QVariant<?> data) throws IOException {
        outputExecutor.execute(new OutputRunnable(data));
    }

    private class OutputRunnable implements Runnable {
        private QVariant<?> data;

        public OutputRunnable(QVariant<?> data) {
            this.data = data;
        }

        @Override
        public void run() {
            try {
                // See how much data we're going to send
                //TODO sandsmark: there must be a better way to to this then create new streams each time....
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                QDataOutputStream bos = new QDataOutputStream(baos);

                QMetaTypeRegistry.serialize(QMetaType.Type.QVariant, bos, data);
                // Tell the other end how much data to expect
                outStream.writeUInt(bos.size(), 32);

                // Sanity check, check that we can decode our own stuff before sending it off
                //QDataInputStream bis = new QDataInputStream(new ByteArrayInputStream(baos.toByteArray()));
                //QMetaTypeRegistry.instance().getTypeForId(QMetaType.Type.QVariant.getValue()).getSerializer().deserialize(bis, DataStreamVersion.Qt_4_2);

                // Send data
                outStream.write(baos.toByteArray());
                if (usingCompression) {
                    deflater.flush();
                }
                bos.close();
                baos.close();
            } catch (IOException e) {
                onDisconnected("Lost connection while sending information");
            }
        }
    }

    /**
     * Convenience function to send a given QVariantMap.
     *
     * @param data the given QVariantMap to send.
     */
    private void sendQVariantMap(Map<String, QVariant<?>> data) throws IOException {
        QVariant<Map<String, QVariant<?>>> bufstruct = new QVariant<Map<String, QVariant<?>>>(data, QVariantType.Map);
        sendQVariant(bufstruct);
    }

    /**
     * A convenience function to send a given QVariantList.
     *
     * @param data The QVariantList to send.
     */
    private void sendQVariantList(List<QVariant<?>> data) throws IOException {
        QVariant<List<QVariant<?>>> bufstruct = new QVariant<List<QVariant<?>>>(data, QVariantType.List);
        sendQVariant(bufstruct);
    }

    /**
     * A convenience function to read a QVariantMap.
     *
     * @throws EmptyQVariantException
     */
    private Map<String, QVariant<?>> readQVariantMap() throws IOException, EmptyQVariantException {
        // Length of this packet (why do they send this? no-one knows!).
        inStream.readUInt(32);
        QVariant<Map<String, QVariant<?>>> v = (QVariant<Map<String, QVariant<?>>>) QMetaTypeRegistry.unserialize(QMetaType.Type.QVariant, inStream);

        Map<String, QVariant<?>> ret = (Map<String, QVariant<?>>) v.getData();
        //		System.out.println(ret.toString());
        if (!readThread.running)
            throw new IOException(); //Stops crashing while connecting if we are told to disconnect, so 2 instances are not reading the network
        return ret;
    }

    /**
     * A convenience function to read a QVariantList.
     *
     * @throws EmptyQVariantException
     */
    private List<QVariant<?>> readQVariantList() throws IOException, EmptyQVariantException {
        inStream.readUInt(32); // Length
        QVariant<List<QVariant<?>>> v = (QVariant<List<QVariant<?>>>) QMetaTypeRegistry.unserialize(QMetaType.Type.QVariant, inStream);

        List<QVariant<?>> ret = (List<QVariant<?>>) v.getData();
        //		System.out.println(ret.toString());
        return ret;
    }

    /**
     * Convenience function to request an init of a given object.
     *
     * @param className  The class name of the object we want.
     * @param objectName The name of the object we want.
     */
    private void sendInitRequest(String className, String objectName) throws IOException {
        List<QVariant<?>> packedFunc = new LinkedList<QVariant<?>>();
        packedFunc.add(new QVariant<Integer>(RequestType.InitRequest.getValue(), QVariantType.Int));
        packedFunc.add(new QVariant<String>(className, QVariantType.String));
        packedFunc.add(new QVariant<String>(objectName, QVariantType.String));
        sendQVariantList(packedFunc);
    }

    private void updateInitProgress(String message) {
        Log.i(TAG, message);
        handler.obtainMessage(R.id.INIT_PROGRESS, message).sendToTarget();
    }

    private void sendConnectingEvent() {
        Log.i(TAG, "Sending Connecting event");
        handler.obtainMessage(R.id.CONNECTING).sendToTarget();
    }

    private void updateInitDone() {
        initComplete = true;
        handler.obtainMessage(R.id.INIT_DONE).sendToTarget();
    }

    private class ReadThread extends Thread {
        boolean running = false;

        CountDownTimer checkAlive = new CountDownTimer(45000, 45000) {
            @Override
            public void onTick(long millisUntilFinished) {
                //Do nothing, no use
            }

            @Override
            public void onFinish() {
                Log.i(TAG, "Timer finished, disconnection from core");
                CoreConnection.this.onDisconnected("Timed out");
            }
        };


        public void run() {
            try {
                String errorMessage = doRun();
                if (errorMessage != null) onDisconnected(errorMessage);
            } catch (EmptyQVariantException e) {
                Log.e(TAG, "Protocol error", e);
                onDisconnected("Protocol error!");
            }

            //Close everything
            if (heartbeatTimer != null) {
                heartbeatTimer.cancel(); // Has this stopped executing now? Nobody knows.
            }

            //Close streams and socket
            try {
                if (outStream != null) {
                    outStream.flush();
                    outStream.close();
                }
            } catch (IOException e) {
                Log.w(TAG, "IOException while closing outStream", e);
            }
            try {
                if (inStream != null)
                    inStream.close();
            } catch (IOException e) {
                Log.w(TAG, "IOException while closing inStream", e);
            }
            try {
                if (socket != null)
                    socket.close();
            } catch (IOException e) {
                Log.w(TAG, "IOException while closing socket", e);
            }

            handler.obtainMessage(R.id.LOST_CONNECTION, errorMessage).sendToTarget();
            handler = null;
            applicationContext = null;
        }

        public String doRun() throws EmptyQVariantException {
            this.running = true;
            errorMessage = null;
            packageQueue = new LinkedList<List<QVariant<?>>>();

            try {
                connect();
                // ↓↓↓↓ FIXME TODO HANDLE THESE YOU DICKWEEDS! ↓↓↓↓
            } catch (UnknownHostException e) {
                return "Unknown host!";
            } catch (UnsupportedProtocolException e) {
                handler.obtainMessage(R.id.UNSUPPORTED_PROTOCOL).sendToTarget();
                Log.w(TAG, e);
                closeConnection();
                return null;
            } catch (IOException e) {
                Log.w(TAG, "Got IOException while connecting");
                if (e.getCause() instanceof NewCertificateException) {
                    Log.w(TAG, "Got NewCertificateException while connecting");
                    handler.obtainMessage(R.id.NEW_CERTIFICATE, ((NewCertificateException) e.getCause()).hashedCert()).sendToTarget();
                    closeConnection();
                } else if (e.getCause() instanceof CertificateException) {
                    Log.w(TAG, "Got CertificateException while connecting");
                    handler.obtainMessage(R.id.INVALID_CERTIFICATE, e.getCause().getMessage()).sendToTarget();
                    closeConnection();
                } else {
                    e.printStackTrace();
                    return "IO error while connecting! " + e.getMessage();
                }
                return null;
            } catch (GeneralSecurityException e) {
                Log.w(TAG, "Invalid username/password combination");
                return "Invalid username/password combination.";
            } catch (EmptyQVariantException e) {
                e.printStackTrace();
                return "IO error while connecting!";
            }

            // Connection is valid, send notification to activity:
            sendConnectingEvent();

            List<QVariant<?>> packedFunc;
            final long startWait = System.currentTimeMillis();
            while (running) {
                try {
                    if (networkInitComplete && packageQueue.size() > 0) {
                        Log.e(TAG, "Queue not empty, retrieve element");
                        packedFunc = packageQueue.poll();
                    } else {
                        packedFunc = readQVariantList();
                    }

                    //Check if we where told to disconnect while reading qvariantlist
                    if (!running) {
                        break;
                    }
                    //Log.i(TAG, "Slow core is slow: " + (System.currentTimeMillis() - startWait) + "ms");

                    //We received a package, aka we are not disconnected, restart timer
                    //Log.i(TAG, "Package received, resetting countdown");
                    checkAlive.cancel();
                    checkAlive.start();

                    //if network init is not complete and we receive anything but a network init object, queue it
                    if (!networkInitComplete) {
                        if (RequestType.getForVal((Integer) packedFunc.get(0).getData()) != RequestType.InitData || !((String) packedFunc.get(1).getData()).equals("Network")) {
                            Log.e(TAG, "Package not network, queueing it");
                            packageQueue.add(packedFunc);
                            continue; //Read next packageFunc
                        }
                    }

                    long start = System.currentTimeMillis();
                    RequestType type = RequestType.getForVal((Integer) packedFunc.remove(0).getData());
                    String className = "", objectName;
	
	
					/*
					 * Here we handle different calls from the core.
					 */
                    switch (type) {
					/*
					 * A heartbeat is a simple request sent with fixed intervals,
					 * to make sure that both ends are still connected (apparently, TCP isn't good enough).
					 * TODO: We should use this, and disconnect automatically when the core has gone away.
					 */
                        case HeartBeat:
                            Log.d(TAG, "Got heartbeat");
                            List<QVariant<?>> packet = new LinkedList<QVariant<?>>();
                            packet.add(new QVariant<Integer>(RequestType.HeartBeatReply.getValue(), QVariantType.Int));
                            packet.add(new QVariant<Calendar>(Calendar.getInstance(), QVariantType.Time));
                            try {
                                sendQVariantList(packet);
                            } catch (IOException e) {
                                // TODO Auto-generated catch block
                                e.printStackTrace();
                            }
                            break;
                        case HeartBeatReply:
                            Log.d(TAG, "Got heartbeat reply");
                            if (packedFunc.size() != 0) {
                                Calendar calendarNow = Calendar.getInstance();
                                Calendar calendarSent = (Calendar) packedFunc.remove(0).getData();
                                int latency = (int) (calendarNow.getTimeInMillis() - calendarSent.getTimeInMillis()) / 2;
                                Log.d(TAG, "Latency: " + latency);
                                handler.obtainMessage(R.id.SET_CORE_LATENCY, latency, 0, null).sendToTarget();
                            }
                            break;
						/*
						 * This is when the core send us a new object to create.
						 * Since we don't actually create objects, we parse out the fields
						 * in the objects manually.
						 */
                        case InitData:
                            // The class name and name of the object we are about to create
                            className = (String) packedFunc.remove(0).getData();
                            objectName = (String) packedFunc.remove(0).getData();
	
						/*
						 * An object representing an IRC network, containing users and channels ("buffers"). 
						 */
                            if (className.equals("Network")) {
                                Log.d(TAG, "InitData: Network");
                                int networkId = Integer.parseInt(objectName);
                                Network network = networks.get(networkId);

                                Map<String, QVariant<?>> initMap = (Map<String, QVariant<?>>) packedFunc.remove(0).getData();
                                // Store the network name and associated nick for "our" user
                                network.setNick((String) initMap.get("myNick").getData());
                                network.setName((String) initMap.get("networkName").getData());
                                network.setLatency((Integer) initMap.get("latency").getData());
                                network.setServer((String) initMap.get("currentServer").getData());
                                boolean isConnected = (Boolean) initMap.get("isConnected").getData();
                                if (isConnected) network.setConnected(true);
                                else network.setConnectionState(ConnectionState.Disconnected);
                                if (network.getStatusBuffer() != null)
                                    network.getStatusBuffer().setActive(isConnected);

                                //we got enough info to tell service we are parsing network
                                Log.i(TAG, "Started parsing network " + network.getName());
                                updateInitProgress("Receiving network: " + network.getName());

                                // Horribly nested maps
                                Map<String, QVariant<?>> usersAndChans = (Map<String, QVariant<?>>) initMap.get("IrcUsersAndChannels").getData();
                                Map<String, QVariant<?>> channels = (Map<String, QVariant<?>>) usersAndChans.get("channels").getData();

                                //Parse out user objects for network
                                Map<String, QVariant<?>> userObjs = (Map<String, QVariant<?>>) usersAndChans.get("users").getData();
                                ArrayList<IrcUser> ircUsers = new ArrayList<IrcUser>();
                                HashMap<String, IrcUser> userTempMap = new HashMap<String, IrcUser>();
                                for (Map.Entry<String, QVariant<?>> element : userObjs.entrySet()) {
                                    IrcUser user = new IrcUser();
                                    user.name = element.getKey();
                                    Map<String, QVariant<?>> map = (Map<String, QVariant<?>>) element.getValue().getData();
                                    user.away = (Boolean) map.get("away").getData();
                                    user.awayMessage = (String) map.get("awayMessage").getData();
                                    user.ircOperator = (String) map.get("ircOperator").getData();
                                    user.nick = (String) map.get("nick").getData();
                                    if (map.containsKey("realName")) {
                                        user.realName = (String) map.get("realName").getData();
                                    }
                                    user.channels = (List<String>) map.get("channels").getData();

                                    ircUsers.add(user);
                                    userTempMap.put(user.nick, user);
                                }
                                network.setUserList(ircUsers);

                                // Parse out the topics
                                for (QVariant<?> channel : channels.values()) {
                                    Map<String, QVariant<?>> chan = (Map<String, QVariant<?>>) channel.getData();
                                    String chanName = (String) chan.get("name").getData();
                                    Map<String, QVariant<?>> userModes = (Map<String, QVariant<?>>) chan.get("UserModes").getData();
                                    String topic = (String) chan.get("topic").getData();

                                    boolean foundChannel = false;
                                    for (Buffer buffer : network.getBuffers().getBufferList(true)) {
                                        if (buffer.getInfo().name.equalsIgnoreCase(chanName)) {
                                            buffer.setTopic(topic);
                                            buffer.setActive(true);
                                            ArrayList<Pair<IrcUser, String>> usersToAdd = new ArrayList<Pair<IrcUser, String>>();
                                            for (Entry<String, QVariant<?>> nick : userModes.entrySet()) {
                                                IrcUser user = userTempMap.get(nick.getKey());
                                                if (user == null) {
                                                    Log.e(TAG, "Channel has nick that is does not match any user on the network: " + nick);
                                                    //TODO: WHY THE FUCK IS A  USER NULL HERE? HAPPENS ON MY OWN CORE, BUT NOT ON DEBUG CORE CONNECTED TO SAME CHANNEL. QUASSEL BUG? WHAT TO DO ABOUT IT

                                                    //this sync request did not seem to do anything
                                                    //
                                                    //												sendInitRequest("IrcUser", network.getId()+"/" +nick.getKey());
                                                    continue;
                                                    //

                                                }
                                                usersToAdd.add(new Pair<IrcUser, String>(user, (String) nick.getValue().getData()));
                                            }
                                            buffer.getUsers().addUsers(usersToAdd);
                                            foundChannel = true;
                                            break;
                                        }
                                    }
                                    if (!foundChannel)
                                        Log.e(TAG, "A channel in a network has no corresponding buffer object " + chanName);
                                }

                                Log.i(TAG, "Sending network " + network.getName() + " to service");
                                handler.obtainMessage(R.id.ADD_NETWORK, network).sendToTarget();


                                //sendInitRequest("BufferSyncer", "");
							/*sendInitRequest("BufferViewManager", "");
								sendInitRequest("AliasManager", "");
								sendInitRequest("NetworkConfig", "GlobalNetworkConfig");
								sendInitRequest("IgnoreListManager", "");*/

                                List<QVariant<?>> reqPackedFunc = new LinkedList<QVariant<?>>();
                                reqPackedFunc.add(new QVariant<Integer>(RequestType.Sync.getValue(), QVariantType.Int));
                                reqPackedFunc.add(new QVariant<String>("BufferSyncer", QVariantType.String));
                                reqPackedFunc.add(new QVariant<String>("", QVariantType.String));
                                reqPackedFunc.add(new QVariant<String>("requestPurgeBufferIds", QVariantType.String));
                                sendQVariantList(reqPackedFunc);

                                if (!initComplete) {
                                    networkInitsLeft -= 1;
                                    if (networkInitsLeft <= 0)
                                        networkInitComplete = true;
                                }

                                long endWait = System.currentTimeMillis();
                                Log.w(TAG, "Network parsed, took: " + (endWait - startWait));
							/*
							 * An object that is used to synchronize metadata about buffers,
							 * like the last seen message, marker lines, etc.
							 */
                            } else if (className.equals("BufferSyncer")) {
                                Log.d(TAG, "InitData: BufferSyncer");
                                // Parse out the last seen messages
                                updateInitProgress("Receiving last seen and marker lines");


                                List<QVariant<?>> lastSeen = (List<QVariant<?>>) ((Map<String, QVariant<?>>) packedFunc.get(0).getData()).get("LastSeenMsg").getData();
                                for (int i = 0; i < lastSeen.size(); i += 2) {
                                    int bufferId = (Integer) lastSeen.get(i).getData();
                                    int msgId = (Integer) lastSeen.get(i + 1).getData();
                                    if (buffers.containsKey(bufferId)) { // We only care for buffers we have open
                                        if (PreferenceManager.getDefaultSharedPreferences(applicationContext).getBoolean(applicationContext.getString(R.string.preference_fetch_to_last_seen), false)) {
                                            requestBacklog(bufferId, msgId);
                                        }
                                        Message msg = handler.obtainMessage(R.id.SET_LAST_SEEN_TO_SERVICE);
                                        msg.arg1 = bufferId;
                                        msg.arg2 = msgId;
                                        msg.sendToTarget();
                                    } else {
                                        Log.e(TAG, "Getting last seen message for buffer we don't have " + bufferId);
                                    }
                                }
                                // Parse out the marker lines for buffers if the core supports them
                                QVariant<?> rawMarkerLines = ((Map<String, QVariant<?>>) packedFunc.get(0).getData()).get("MarkerLines");
                                if (rawMarkerLines != null) {
                                    List<QVariant<?>> markerLines = (List<QVariant<?>>) rawMarkerLines.getData();
                                    for (int i = 0; i < markerLines.size(); i += 2) {
                                        int bufferId = (Integer) markerLines.get(i).getData();
                                        int msgId = (Integer) markerLines.get(i + 1).getData();
                                        if (buffers.containsKey(bufferId)) {
                                            Message msg = handler.obtainMessage(R.id.SET_MARKERLINE_TO_SERVICE);
                                            msg.arg1 = bufferId;
                                            msg.arg2 = msgId;
                                            msg.sendToTarget();
                                        } else {
                                            Log.e(TAG, "Getting markerlinemessage for buffer we don't have " + bufferId);
                                        }
                                    }
                                } else {
                                    Log.e(TAG, "Marker lines are null in BufferSyncer, should not happen");
                                }
	
							/*
							 * A class representing another user on a given IRC network.
							 */
                            } else if (className.equals("IrcUser")) {
                                Log.d(TAG, "InitData: IrcUser");
                                Map<String, QVariant<?>> userMap = (Map<String, QVariant<?>>) packedFunc.remove(0).getData();
                                Bundle bundle = new Bundle();
                                bundle.putString("awayMessage", (String) userMap.get("awayMessage").getData());
                                bundle.putSerializable("channels", (ArrayList<String>) userMap.get("channels").getData());
                                bundle.putBoolean("away", (Boolean) userMap.get("away").getData());
                                bundle.putString("ircOperator", (String) userMap.get("ircOperator").getData());
                                bundle.putString("nick", (String) userMap.get("nick").getData());
                                Message msg = handler.obtainMessage(R.id.NEW_USER_INFO);
                                int networkId = Integer.parseInt(objectName.split("/", 2)[0]);
                                msg.obj = bundle;
                                msg.arg1 = networkId;
                                msg.sendToTarget();

                            } else if (className.equals("IrcChannel")) {
                                Log.d(TAG, "InitData: IrcChannel");
                                //						System.out.println(packedFunc.toString() + " Object: "+objectName);
                                //						topic, UserModes, password, ChanModes, name
                                //For now only topic seems useful here, rest is added other places
                                Map<String, QVariant<?>> map = (Map<String, QVariant<?>>) packedFunc.remove(0).getData();

                                String bufferName = (String) map.get("name").getData();
                                String topic = (String) map.get("topic").getData();
                                String[] tmp = objectName.split("/", 2);
                                int networkId = Integer.parseInt(tmp[0]);
                                if (networks.get(networkId).getBuffers().hasBuffer(bufferName)) {
                                    Buffer buffer = networks.get(networkId).getBuffers().getBuffer(bufferName);
                                    Message msg = handler.obtainMessage(R.id.CHANNEL_TOPIC_CHANGED, networkId, buffer.getInfo().id, topic);
                                    msg.sendToTarget();
                                    msg = handler.obtainMessage(R.id.SET_BUFFER_ACTIVE, buffer.getInfo().id, 0, true);
                                    msg.sendToTarget();
                                } else {
                                    Log.e(TAG, "Could not find buffer for IrcChannel initData");
                                }
                            } else if (className.equals("BufferViewManager")) {
                                Log.d(TAG, "InitData: BufferViewManager");
                                Map<String, QVariant<?>> map = (Map<String, QVariant<?>>) packedFunc.remove(0).getData();
                                List<QVariant<?>> bufferViewList = (List<QVariant<?>>) map.get("BufferViewIds").getData();

                                int id = 0;
                                if (bufferViewList.isEmpty()) {
                                    Log.e(TAG, "BufferViewManager didn't return any views");
                                } else {
                                    QVariant firstBufferViewId = bufferViewList.get(0);
                                    id = (Integer) firstBufferViewId.getData();
                                }
                                Log.d(TAG, "Requesting BufferViewConfig with id: " + id);
                                sendInitRequest("BufferViewConfig", Integer.toString(id));
                                bufferViewId = id;
                            } else if (className.equals("BufferViewConfig")) {
                                Log.d(TAG, "InitData: BufferViewConfig");
                                Map<String, QVariant<?>> map = (Map<String, QVariant<?>>) packedFunc.remove(0).getData();
                                List<QVariant<?>> tempList = (List<QVariant<?>>) map.get("TemporarilyRemovedBuffers").getData();
                                List<QVariant<?>> permList = (List<QVariant<?>>) map.get("RemovedBuffers").getData();
                                List<QVariant<?>> orderList = (List<QVariant<?>>) map.get("BufferList").getData();
                                updateInitProgress("Receiving buffer list information");
                                BufferCollection.orderAlphabetical = (Boolean) map.get("sortAlphabetically").getData();
                                Log.w(TAG, "Setting alphabetical order to: "+Boolean.toString(BufferCollection.orderAlphabetical));


                                //TODO: maybe send this in a bulk to the service so it wont sort and shit every time
                                for (QVariant bufferId : tempList) {
                                    if (!buffers.containsKey(bufferId.getData())) {
                                        Log.e(TAG, "TempList, don't have buffer: " + bufferId.getData());
                                        continue;
                                    }
                                    Message msg = handler.obtainMessage(R.id.SET_BUFFER_TEMP_HIDDEN);
                                    msg.arg1 = ((Integer) bufferId.getData());
                                    msg.obj = true;
                                    msg.sendToTarget();
                                }

                                for (QVariant bufferId : permList) {
                                    if (!buffers.containsKey(bufferId.getData())) {
                                        Log.e(TAG, "TempList, don't have buffer: " + bufferId.getData());
                                        continue;
                                    }
                                    Message msg = handler.obtainMessage(R.id.SET_BUFFER_PERM_HIDDEN);
                                    msg.arg1 = ((Integer) bufferId.getData());
                                    msg.obj = true;
                                    msg.sendToTarget();
                                }

                                int order = 0;
                                for (QVariant bufferId : orderList) {
                                    int id = (Integer) bufferId.getData();
                                    if (id > maxBufferId) {
                                        maxBufferId = id;
                                    }
                                    if (!buffers.containsKey(id)) {
                                        Log.w(TAG, "Got buffer info for non-existent buffer id: " + id);
                                        continue;
                                    }
                                    Message msg = handler.obtainMessage(R.id.SET_BUFFER_ORDER);
                                    msg.arg1 = id;
                                    msg.arg2 = order;

                                    //FIXME: DEBUG PISS REMOVE
                                    ArrayList<Integer> keysString = new ArrayList<Integer>();
                                    ArrayList<Integer> buffersString = new ArrayList<Integer>();
                                    for (Entry<Integer, Buffer> b : buffers.entrySet()) {
                                        keysString.add(b.getKey());
                                        buffersString.add(b.getValue().getInfo().id);
                                    }
                                    Bundle bundle = new Bundle();
                                    bundle.putIntegerArrayList("keys", keysString);
                                    bundle.putIntegerArrayList("buffers", buffersString);
                                    msg.obj = bundle;

                                    msg.sendToTarget();

                                    order++;
                                }
                                updateInitDone();
                            }
						/*
						 * There are several objects that we don't care about (at the moment).
						 */
                            else {
                                Log.i(TAG, "Unparsed InitData: " + className + "(" + objectName + ").");
                            }
                            break;
						/*
						 * Sync requests are sent by the core whenever an object needs to be updated.
						 * Again, we just parse out whatever we need manually
						 */
                        case Sync:
						/* See above; parse out information about object, 
						 * and additionally a sync function name.
						 */
                            Object foo = packedFunc.remove(0).getData();
                            //System.out.println("FUCK" + foo.toString() + " balle " + foo.getClass().getName());
						/*if (foo.getClass().getName().equals("java.nio.ReadWriteHeapByteBuffer")) {
							try {
								System.out.println("faen i helvete: " + new String(((ByteBuffer)foo).array(), "UTF-8"));
							} catch (UnsupportedEncodingException e) {
								// TODO Auto-generated catch block
								e.printStackTrace();
							}						
						}*/
                            className = (String) foo; // This is either a byte buffer or a string
                            objectName = (String) packedFunc.remove(0).getData();
                            String function = packedFunc.remove(0).toString();
	
						/*
						 * The BacklogManager object is responsible for synchronizing backlog
						 * between the core and the client.
						 * 
						 * The receiveBacklog function is called in the client with a given (requested)
						 * amount of messages.
						 */
                            if (className.equals("BacklogManager") && function.equals("receiveBacklog")) {
                                Log.d(TAG, "Sync: BacklogManager -> receiveBacklog");
							/* Here we first just dump some unused data;
							 * the buffer id is embedded in the message itself (in a bufferinfo object),
							 * the rest of the arguments aren't used at all, apparently.
							 */
                                packedFunc.remove(0); // Buffer ID (Integer)
                                packedFunc.remove(0); // first message
                                packedFunc.remove(0); // last message
                                packedFunc.remove(0); // limit to how many messages to fetch
                                packedFunc.remove(0); // additional messages to fetch
                                List<QVariant<?>> data = (List<QVariant<?>>) (packedFunc.remove(0).getData());
                                Collections.reverse(data); // Apparently, we receive them in the wrong order

                                if (!initComplete) { //We are still initializing backlog for the first time
                                    updateInitProgress("Receiving backlog");
                                    boolean preferenceParseColors = PreferenceManager.getDefaultSharedPreferences(applicationContext).getBoolean(applicationContext.getString(R.string.preference_colored_text), false);
                                    for (QVariant<?> message : data) {
                                        IrcMessage msg = (IrcMessage) message.getData();
                                        Buffer buffer = buffers.get(msg.bufferInfo.id);

                                        if (buffer == null) {
                                            Log.e(TAG, "A message buffer is null:" + msg);
                                            continue;
                                        }

                                        if (!buffer.hasMessage(msg)) {
                                            /**
                                             * Check if we are highlighted in the message, TODO: Add
                                             * support for custom highlight masks
                                             */
                                            MessageUtil.checkMessageForHighlight(notificationManager, networks.get(buffer.getInfo().networkId).getNick(), buffer, msg);
                                            buffer.addBacklogMessage(msg);
                                        } else {
                                            Log.e(TAG, "Getting message buffer already have " + buffer.getInfo().name);
                                        }
                                    }
                                } else {
                                    // Send our the backlog messages to our listeners
                                    List<IrcMessage> messageList = new ArrayList<IrcMessage>();
                                    for (QVariant<?> message : data) {
                                        messageList.add((IrcMessage) message.getData());
                                    }
                                    Message msg = handler.obtainMessage(R.id.NEW_BACKLOGITEM_TO_SERVICE);
                                    msg.obj = messageList;
                                    msg.sendToTarget();
                                }
							/* 
							 * The addIrcUser function in the Network class is called whenever a new
							 * IRC user appears on a given network. 
							 */
                            } else if (className.equals("Network") && function.equals("addIrcUser")) {
                                Log.d(TAG, "Sync: Network -> addIrcUser");
                                String nick = (String) packedFunc.remove(0).getData();
                                IrcUser user = new IrcUser();
                                user.nick = nick.split("!")[0];
                                //If not done then we can add it right here, if we try to send it we might crash because service don't have the network yet
                                if (!initComplete) {
                                    networks.get(Integer.parseInt(objectName)).onUserJoined(user);
                                } else {
                                    handler.obtainMessage(R.id.NEW_USER_ADDED, Integer.parseInt(objectName), 0, user).sendToTarget();
                                }
                                sendInitRequest("IrcUser", objectName + "/" + nick.split("!")[0]);
                            } else if (className.equals("Network") && function.equals("setConnectionState")) {
                                Log.d(TAG, "Sync: Network -> setConnectionState");
                                int networkId = Integer.parseInt(objectName);
                                Network.ConnectionState state = ConnectionState.getForValue((Integer) packedFunc.remove(0).getData());
                                //If network has no status buffer it is the first time we are connecting to it
                                if (state == ConnectionState.Connecting && networks.get(networkId).getStatusBuffer() == null) {
                                    //Create the new buffer object for status buffer
                                    QuasselDbHelper dbHelper = new QuasselDbHelper(applicationContext);
                                    BufferInfo info = new BufferInfo();
                                    maxBufferId += 1;
                                    info.id = maxBufferId;
                                    info.networkId = networkId;
                                    info.type = BufferInfo.Type.StatusBuffer;
                                    Buffer buffer = new Buffer(info, dbHelper);
                                    buffers.put(info.id, buffer);
                                    handler.obtainMessage(R.id.SET_STATUS_BUFFER, networkId, 0, buffer).sendToTarget();
                                }
                                handler.obtainMessage(R.id.SET_CONNECTION_STATE, networkId, 0, state).sendToTarget();
                            } else if (className.equals("Network") && function.equals("addIrcChannel")) {
                                Log.d(TAG, "Sync: Network -> addIrcChannel");
                                int networkId = Integer.parseInt(objectName);
                                String bufferName = (String) packedFunc.remove(0).getData();
                                System.out.println(bufferName);
                                boolean hasBuffer = networks.get(networkId).getBuffers().hasBuffer(bufferName);

                                if (!hasBuffer) {
                                    //Create the new buffer object
                                    QuasselDbHelper dbHelper = new QuasselDbHelper(applicationContext);
                                    BufferInfo info = new BufferInfo();
                                    info.name = bufferName;
                                    info.id = -1;
                                    info.networkId = networkId;
                                    info.type = BufferInfo.Type.ChannelBuffer;
                                    Buffer buffer = new Buffer(info, dbHelper);
                                    Message msg = handler.obtainMessage(R.id.NEW_BUFFER_TO_SERVICE, buffer);
                                    msg.sendToTarget();
                                }
                                sendInitRequest("IrcChannel", objectName + "/" + bufferName);
                            } else if (className.equals("Network") && function.equals("setConnected")) {
                                Log.d(TAG, "Sync: Network -> setConnected");
                                boolean connected = (Boolean) packedFunc.remove(0).getData();
                                int networkId = Integer.parseInt(objectName);
                                handler.obtainMessage(R.id.SET_CONNECTED, networkId, 0, connected).sendToTarget();
                            } else if (className.equals("Network") && function.equals("setMyNick")) {
                                Log.d(TAG, "Sync: Network -> setMyNick");
                                String nick = (String) packedFunc.remove(0).getData();
                                int networkId = Integer.parseInt(objectName);
                                handler.obtainMessage(R.id.SET_MY_NICK, networkId, 0, nick).sendToTarget();
                            } else if (className.equals("Network") && function.equals("setLatency")) {
                                Log.d(TAG, "Sync: Network -> setLatency");
                                int networkLatency = (Integer) packedFunc.remove(0).getData();
                                int networkId = Integer.parseInt(objectName);
                                handler.obtainMessage(R.id.SET_NETWORK_LATENCY, networkId, networkLatency, null).sendToTarget();
                            } else if (className.equals("Network") && function.equals("setNetworkName")) {
                                Log.d(TAG, "Sync: Network -> setNetworkName");
                                String networkName = (String) packedFunc.remove(0).getData();
                                int networkId = Integer.parseInt(objectName);
                                handler.obtainMessage(R.id.SET_NETWORK_NAME, networkId, 0, networkName).sendToTarget();
                            } else if (className.equals("Network") && function.equals("setCurrentServer")) {
                                Log.d(TAG, "Sync: Network -> setCurrentServer");
                                String currentServer = (String) packedFunc.remove(0).getData();
                                int networkId = Integer.parseInt(objectName);
                                handler.obtainMessage(R.id.SET_NETWORK_CURRENT_SERVER, networkId, 0, currentServer).sendToTarget();
                            } else if (className.equals("IrcUser") && function.equals("partChannel")) {
                                Log.d(TAG, "Sync: IrcUser -> partChannel");
                                String[] tmp = objectName.split("/", 2);
                                int networkId = Integer.parseInt(tmp[0]);
                                String userName = tmp[1];
                                Bundle bundle = new Bundle();
                                bundle.putString("nick", userName);
                                bundle.putString("buffer", (String) packedFunc.remove(0).getData());
                                handler.obtainMessage(R.id.USER_PARTED, networkId, 0, bundle).sendToTarget();
                            } else if (className.equals("IrcUser") && function.equals("quit")) {
                                Log.d(TAG, "Sync: IrcUser -> quit");
                                String[] tmp = objectName.split("/", 2);
                                int networkId = Integer.parseInt(tmp[0]);
                                String userName = tmp[1];
                                handler.obtainMessage(R.id.USER_QUIT, networkId, 0, userName).sendToTarget();
                            } else if (className.equals("IrcUser") && function.equals("setNick")) {
                                Log.d(TAG, "Sync: IrcUser -> setNick");
							/*
							 * Does nothing, Why would we need a sync call, when we got a RPC call about renaming the user object
							 */
                            } else if (className.equals("IrcUser") && function.equals("setServer")) {
                                Log.d(TAG, "Sync: IrcUser -> setServer");
                                String[] tmp = objectName.split("/", 2);
                                int networkId = Integer.parseInt(tmp[0]);
                                Bundle bundle = new Bundle();
                                bundle.putString("nick", tmp[1]);
                                bundle.putString("server", (String) packedFunc.remove(0).getData());
                                handler.obtainMessage(R.id.SET_USER_SERVER, networkId, 0, bundle).sendToTarget();
                            } else if (className.equals("IrcUser") && function.equals("setAway")) {
                                Log.d(TAG, "Sync: IrcUser -> setAway");
                                String[] tmp = objectName.split("/", 2);
                                int networkId = Integer.parseInt(tmp[0]);
                                Bundle bundle = new Bundle();
                                bundle.putString("nick", tmp[1]);
                                bundle.putBoolean("away", (Boolean) packedFunc.remove(0).getData());
                                handler.obtainMessage(R.id.SET_USER_AWAY, networkId, 0, bundle).sendToTarget();
                            } else if (className.equals("IrcUser") && function.equals("setAwayMessage")) {
                                Log.d(TAG, "Sync: IrcUser -> setAwayMessage");
                                String[] tmp = objectName.split("/", 2);
                                int networkId = Integer.parseInt(tmp[0]);
                                Bundle bundle = new Bundle();
                                bundle.putString("nick", tmp[1]);
                                bundle.putString("awayMessage", (String) packedFunc.remove(0).getData());
                                handler.obtainMessage(R.id.SET_USER_AWAY_MESSAGE, networkId, 0, bundle).sendToTarget();
                            } else if (className.equals("IrcUser") && function.equals("setRealName")) {
                                Log.d(TAG, "Sync: IrcUser -> setRealName");
                                String[] tmp = objectName.split("/", 2);
                                int networkId = Integer.parseInt(tmp[0]);
                                Bundle bundle = new Bundle();
                                bundle.putString("nick", tmp[1]);
                                bundle.putString("realname", (String) packedFunc.remove(0).getData());
                                handler.obtainMessage(R.id.SET_USER_REALNAME, networkId, 0, bundle).sendToTarget();
                            } else if (className.equals("IrcChannel") && function.equals("joinIrcUsers")) {
                                Log.d(TAG, "Sync: IrcChannel -> joinIrcUsers");
                                List<String> nicks = (List<String>) packedFunc.remove(0).getData();
                                List<String> modes = (List<String>) packedFunc.remove(0).getData();
                                String[] tmp = objectName.split("/", 2);
                                int networkId = Integer.parseInt(tmp[0]);
                                String bufferName = tmp[1];

                                for (int i = 0; i < nicks.size(); i++) {
                                    Bundle bundle = new Bundle();
                                    bundle.putString("nick", nicks.get(i));
                                    bundle.putString("mode", modes.get(i));
                                    bundle.putString("buffername", bufferName);
                                    handler.obtainMessage(R.id.USER_JOINED, networkId, 0, bundle).sendToTarget();
                                }
                            } else if (className.equals("IrcChannel") && function.equals("addUserMode")) {
                                Log.d(TAG, "Sync: IrcChannel -> addUserMode");
                                String[] tmp = objectName.split("/", 2);
                                int networkId = Integer.parseInt(tmp[0]);
                                String channel = tmp[1];

                                String nick = (String) packedFunc.remove(0).getData();
                                String changedMode = (String) packedFunc.remove(0).getData();

                                Bundle bundle = new Bundle();
                                bundle.putString("nick", nick);
                                bundle.putString("mode", changedMode);
                                bundle.putString("channel", channel);
                                handler.obtainMessage(R.id.USER_ADD_MODE, networkId, 0, bundle).sendToTarget();


                            } else if (className.equals("IrcChannel") && function.equals("removeUserMode")) {
                                Log.d(TAG, "Sync: IrcChannel -> removeUserMode");
                                String[] tmp = objectName.split("/", 2);
                                int networkId = Integer.parseInt(tmp[0]);
                                String channel = tmp[1];

                                String nick = (String) packedFunc.remove(0).getData();
                                String changedMode = (String) packedFunc.remove(0).getData();

                                Bundle bundle = new Bundle();
                                bundle.putString("nick", nick);
                                bundle.putString("mode", changedMode);
                                bundle.putString("channel", channel);

                                handler.obtainMessage(R.id.USER_REMOVE_MODE, networkId, 0, bundle).sendToTarget();
                            } else if (className.equals("IrcChannel") && function.equals("setTopic")) {
                                Log.d(TAG, "Sync: IrcChannel -> setTopic");
                                String[] tmp = objectName.split("/", 2);
                                int networkId = Integer.parseInt(tmp[0]);
                                String bufferName = tmp[1];

                                String topic = (String) packedFunc.remove(0).getData();
                                if (networks.get(networkId).getBuffers().hasBuffer(bufferName)) {
                                    Buffer buffer = networks.get(networkId).getBuffers().getBuffer(bufferName);
                                    Message msg = handler.obtainMessage(R.id.CHANNEL_TOPIC_CHANGED, networkId, buffer.getInfo().id, topic);
                                    msg.sendToTarget();
                                } else {
                                    Log.e(TAG, "Could not find buffer for IrcChannel setTopic");
                                }
                            } else if (className.equals("BufferSyncer") && function.equals("setLastSeenMsg")) {
                                Log.d(TAG, "Sync: BufferSyncer -> setLastSeenMsg");
                                int bufferId = (Integer) packedFunc.remove(0).getData();
                                int msgId = (Integer) packedFunc.remove(0).getData();
                                Message msg = handler.obtainMessage(R.id.SET_LAST_SEEN_TO_SERVICE);
                                msg.arg1 = bufferId;
                                msg.arg2 = msgId;
                                msg.sendToTarget();

                            } else if (className.equals("BufferSyncer") && function.equals("setMarkerLine")) {
                                Log.d(TAG, "Sync: BufferSyncer -> setMarkerLine");
                                int bufferId = (Integer) packedFunc.remove(0).getData();
                                int msgId = (Integer) packedFunc.remove(0).getData();
                                Message msg = handler.obtainMessage(R.id.SET_MARKERLINE_TO_SERVICE);
                                msg.arg1 = bufferId;
                                msg.arg2 = msgId;
                                msg.sendToTarget();
	
							/*
							 * markBufferAsRead is called whenever a given buffer is set as read by the core. 
							 */
                            } else if (className.equals("BufferSyncer") && function.equals("markBufferAsRead")) {
                                Log.d(TAG, "Sync: BufferSyncer -> markBufferAsRead");
                                //TODO: this basically does shit. So find out if it effects anything and what it should do
                                //int buffer = (Integer) packedFunc.remove(0).getData();
                                //buffers.get(buffer).setRead();
                            } else if (className.equals("BufferSyncer") && function.equals("removeBuffer")) {
                                Log.d(TAG, "Sync: BufferSyncer -> removeBuffer");
                                int bufferId = (Integer) packedFunc.remove(0).getData();
                                if (buffers.containsKey(bufferId)) {
                                    int networkId = buffers.get(bufferId).getInfo().networkId;
                                    buffers.remove(bufferId);
                                    handler.obtainMessage(R.id.REMOVE_BUFFER, networkId, bufferId).sendToTarget();
                                }
                            } else if (className.equals("BufferSyncer") && function.equals("renameBuffer")) {
                                Log.d(TAG, "Sync: BufferSyncer -> renameBuffer");
                                int bufferId = (Integer) packedFunc.remove(0).getData();
                                String newName = (String) packedFunc.remove(0).getData();
                                Message msg = handler.obtainMessage(R.id.RENAME_BUFFER);
                                msg.arg1 = bufferId;
                                msg.arg2 = 0;
                                msg.obj = newName;
                                msg.sendToTarget();

                            } else if (className.equals("BufferViewConfig") && function.equals("addBuffer")) {
                                Log.d(TAG, "Sync: BufferViewConfig -> addBuffer");
                                int bufferId = (Integer) packedFunc.remove(0).getData();

                                if (bufferId > maxBufferId) {
                                    maxBufferId = bufferId;
                                }
                                if (buffers.containsKey(bufferId) && buffers.get(bufferId).isTemporarilyHidden()) {
                                    Message msg = handler.obtainMessage(R.id.SET_BUFFER_TEMP_HIDDEN);
                                    msg.arg1 = ((Integer) bufferId);
                                    msg.obj = false;
                                    msg.sendToTarget();
                                } else if (buffers.containsKey(bufferId) && buffers.get(bufferId).isPermanentlyHidden()) {
                                    Message msg = handler.obtainMessage(R.id.SET_BUFFER_PERM_HIDDEN);
                                    msg.arg1 = ((Integer) bufferId);
                                    msg.obj = false;
                                    msg.sendToTarget();
                                }

                                Message msg = handler.obtainMessage(R.id.SET_BUFFER_ORDER);
                                msg.arg1 = bufferId;
                                msg.arg2 = (Integer) packedFunc.remove(0).getData();
                                msg.sendToTarget();
                            } else if (className.equals("BufferViewConfig") && function.equals("removeBuffer")) {
                                Log.d(TAG, "Sync: BufferViewConfig -> removeBuffer");
                                int bufferId = (Integer) packedFunc.remove(0).getData();
                                if (!buffers.containsKey(bufferId)) {
                                    Log.e(TAG, "Dont't have buffer: " + bufferId);
                                    continue;
                                }
                                Message msg = handler.obtainMessage(R.id.SET_BUFFER_TEMP_HIDDEN);
                                msg.arg1 = ((Integer) bufferId);
                                msg.obj = true;
                                msg.sendToTarget();

                            } else if (className.equals("BufferViewConfig") && function.equals("removeBufferPermanently")) {
                                Log.d(TAG, "Sync: BufferViewConfig -> removeBufferPermanently");
                                int bufferId = (Integer) packedFunc.remove(0).getData();
                                if (!buffers.containsKey(bufferId)) {
                                    Log.e(TAG, "Dont't have buffer: " + bufferId);
                                    continue;
                                }
                                Message msg = handler.obtainMessage(R.id.SET_BUFFER_PERM_HIDDEN);
                                msg.arg1 = ((Integer) bufferId);
                                msg.obj = true;
                                msg.sendToTarget();

                            } else {
                                Log.i(TAG, "Unparsed Sync request: " + className + "::" + function);
                            }

                            break;
	
						/*
						 * Remote procedure calls are direct calls that are not associated with any objects.
						 */
                        case RpcCall:
                            // Contains a normalized function signature; see QMetaObject::normalizedSignature, I guess.
                            String functionName = packedFunc.remove(0).toString();
	
						/*
						 * This is called by the core when a new message should be displayed.
						 */
                            if (functionName.equals("2displayMsg(Message)")) {
                                //Log.d(TAG, "RpcCall: " + "2displayMsg(Message)");
                                IrcMessage message = (IrcMessage) packedFunc.remove(0).getData();

                                if (!networks.get(message.bufferInfo.networkId).containsBuffer(message.bufferInfo.id) &&
                                        message.bufferInfo.type == BufferInfo.Type.QueryBuffer) {
                                    // TODO: persist the db connections
                                    Buffer buffer = new Buffer(message.bufferInfo, new QuasselDbHelper(applicationContext));
                                    buffers.put(message.bufferInfo.id, buffer);
                                    Message msg = handler.obtainMessage(R.id.NEW_BUFFER_TO_SERVICE);
                                    msg.obj = buffer;
                                    msg.sendToTarget();
                                }

                                if (message.type == IrcMessage.Type.NetsplitJoin) {
                                    NetsplitHelper netsplitHelper = new NetsplitHelper(message.content.toString());
                                    for (String nick : netsplitHelper.getNicks()) {
                                        IrcUser user = new IrcUser();
                                        user.nick = nick;
                                        handler.obtainMessage(R.id.NEW_USER_ADDED, message.bufferInfo.networkId, 0, user).sendToTarget();
                                        sendInitRequest("IrcUser", message.bufferInfo.networkId + "/" + nick);
                                    }
                                }

                                if (message.type == IrcMessage.Type.NetsplitQuit) {
                                    NetsplitHelper netsplitHelper = new NetsplitHelper(message.content.toString());
                                    for (String nick : netsplitHelper.getNicks()) {
                                        handler.obtainMessage(R.id.USER_QUIT, message.bufferInfo.networkId, 0, nick).sendToTarget();
                                    }
                                }

                                BufferCollection col = networks.get(message.bufferInfo.networkId).getBuffers();
                                if (col.hasBuffer(message.bufferInfo.name)) {
                                    Buffer buffer;
                                    buffer = col.getBuffer(message.bufferInfo.name);
                                    buffer.getInfo().id = message.bufferInfo.id;
                                    col.addBuffer(buffer);
                                    buffers.put((Integer) buffer.getInfo().id, buffer);
                                }

                                Message msg = handler.obtainMessage(R.id.NEW_MESSAGE_TO_SERVICE);
                                msg.obj = message;
                                msg.sendToTarget();
                                //11-12 21:48:02.514: I/CoreConnection(277): Unhandled RpcCall: __objectRenamed__ ([IrcUser, 1/Kenji, 1/Kenj1]).
                            } else if (functionName.equals("__objectRenamed__") && ((String) packedFunc.get(0).getData()).equals("IrcUser")) {
                                packedFunc.remove(0); //Drop the "ircUser"
                                String[] tmp = ((String) packedFunc.remove(0).getData()).split("/", 2);
                                int networkId = Integer.parseInt(tmp[0]);
                                String newNick = tmp[1];
                                tmp = ((String) packedFunc.remove(0).getData()).split("/", 2);
                                String oldNick = tmp[1];

                                Bundle bundle = new Bundle();
                                bundle.putString("oldNick", oldNick);
                                bundle.putString("newNick", newNick);
                                handler.obtainMessage(R.id.USER_CHANGEDNICK, networkId, -1, bundle).sendToTarget();
                            } else if (functionName.equals("2networkCreated(NetworkId)")) {
                                Log.d(TAG, "RpcCall: " + "2networkCreated(NetworkId)");
                                int networkId = ((Integer) packedFunc.remove(0).getData());
                                Network network = new Network(networkId);
                                networks.put(networkId, network);
                                sendInitRequest("Network", Integer.toString(networkId));
                            } else if (functionName.equals("2networkRemoved(NetworkId)")) {
                                Log.d(TAG, "RpcCall: " + "2networkRemoved(NetworkId)");
                                int networkId = ((Integer) packedFunc.remove(0).getData());
                                networks.remove(networkId);
                                handler.obtainMessage(R.id.NETWORK_REMOVED, networkId, 0).sendToTarget();
                            } else {
                                Log.i(TAG, "Unhandled RpcCall: " + functionName + " (" + packedFunc + ").");
                            }
                            break;
                        default:
                            Log.i(TAG, "Unhandled request type: " + type.name());
                    }
                    long end = System.currentTimeMillis();
                    if (end - start > 500) {
                        System.err.println("Slow parsing (" + (end - start) + "ms)!: Request type: " + type.name() + " Class name:" + className);
                    }
                } catch (IOException e) {
                    CoreConnection.this.onDisconnected("Lost connection");
                    Log.w(TAG, "IO error, lost connection?", e);
                }
            }
            return null;
        }
    }

    public boolean isInitComplete() {
        return initComplete;
    }

    public long getCoreId() {
        return coreId;
    }
}

