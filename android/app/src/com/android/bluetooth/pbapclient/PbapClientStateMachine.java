/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * Bluetooth Pbap PCE StateMachine
 *                      (Disconnected)
 *                           |    ^
 *                   CONNECT |    | DISCONNECTED
 *                           V    |
 *                 (Connecting) (Disconnecting)
 *                           |    ^
 *                 CONNECTED |    | DISCONNECT
 *                           V    |
 *                        (Connected)
 *
 * Valid Transitions:
 * State + Event -> Transition:
 *
 * Disconnected + CONNECT -> Connecting
 * Connecting + CONNECTED -> Connected
 * Connecting + TIMEOUT -> Disconnecting
 * Connecting + DISCONNECT -> Disconnecting
 * Connected + DISCONNECT -> Disconnecting
 * Disconnecting + DISCONNECTED -> (Safe) Disconnected
 * Disconnecting + TIMEOUT -> (Force) Disconnected
 * Disconnecting + CONNECT : Defer Message
 *
 */
package com.android.bluetooth.pbapclient;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothPbapClient;
import android.bluetooth.BluetoothUuid;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.HandlerThread;
import android.os.Message;
import android.os.ParcelUuid;
import android.os.Process;
import android.os.UserManager;
import android.provider.CallLog;
import android.util.Log;

import com.android.bluetooth.btservice.ProfileService;
import com.android.bluetooth.R;
import com.android.internal.util.IState;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;

import java.lang.IllegalStateException;
import java.util.ArrayList;
import java.util.List;

final class PbapClientStateMachine extends StateMachine {
    private static final boolean DBG = true;
    private static final String TAG = "PbapClientStateMachine";

    // Messages for handling connect/disconnect requests.
    private static final int MSG_CONNECT = 1;
    private static final int MSG_DISCONNECT = 2;
    private static final int MSG_SDP_COMPLETE = 9;

    // Messages for handling error conditions.
    private static final int MSG_CONNECT_TIMEOUT = 3;
    private static final int MSG_DISCONNECT_TIMEOUT = 4;

    // Messages for feedback from ConnectionHandler.
    static final int MSG_CONNECTION_COMPLETE = 5;
    static final int MSG_CONNECTION_FAILED = 6;
    static final int MSG_CONNECTION_CLOSED = 7;
    static final int MSG_RESUME_DOWNLOAD = 8;

    static final int CONNECT_TIMEOUT = 10000;
    static final int DISCONNECT_TIMEOUT = 3000;

    private final Object mLock;
    private State mDisconnected;
    private State mConnecting;
    private State mConnected;
    private State mDisconnecting;

    // mCurrentDevice may only be changed in Disconnected State.
    private BluetoothDevice mCurrentDevice = null;
    private PbapClientService mService;
    private Context mContext;
    private PbapClientConnectionHandler mConnectionHandler;
    private HandlerThread mHandlerThread = null;
    private UserManager mUserManager = null;

    // mMostRecentState maintains previous state for broadcasting transitions.
    private int mMostRecentState = BluetoothProfile.STATE_DISCONNECTED;

    PbapClientStateMachine(PbapClientService svc, Context context) {
        super(TAG);

        mService = svc;
        mContext = context;
        mLock = new Object();
        mUserManager = UserManager.get(mContext);
        mDisconnected = new Disconnected();
        mConnecting = new Connecting();
        mDisconnecting = new Disconnecting();
        mConnected = new Connected();
        removeUncleanAccounts();

        addState(mDisconnected);
        addState(mConnecting);
        addState(mDisconnecting);
        addState(mConnected);

        setInitialState(mDisconnected);
    }

    class Disconnected extends State {
        @Override
        public void enter() {
            Log.d(TAG, "Enter Disconnected: " + getCurrentMessage().what);
            onConnectionStateChanged(mCurrentDevice, mMostRecentState,
                    BluetoothProfile.STATE_DISCONNECTED);
            mMostRecentState = BluetoothProfile.STATE_DISCONNECTED;
            synchronized (mLock) {
                mCurrentDevice = null;
            }

        }

        @Override
        public boolean processMessage(Message message) {
            if (DBG) Log.d(TAG, "Processing MSG " + message.what + " from " + this.getName());
            switch (message.what) {
                case MSG_CONNECT:
                    if (message.obj instanceof BluetoothDevice) {
                        synchronized (mLock) {
                            mCurrentDevice = (BluetoothDevice) message.obj;
                        }
                        transitionTo(mConnecting);
                    } else {
                        Log.w(TAG, "Received CONNECT without valid device");
                        throw new IllegalStateException("invalid device");
                    }
                    break;

                case MSG_DISCONNECT:
                    Log.w(TAG, "Received unexpected disconnect while disconnected.");
                    // It is possible if something crashed for others to think we are connected
                    // already, just remind them.
                    if (message.obj instanceof BluetoothDevice) {
                        onConnectionStateChanged((BluetoothDevice) message.obj,
                                BluetoothProfile.STATE_DISCONNECTED,
                                BluetoothProfile.STATE_DISCONNECTED);
                    }
                    break;

                default:
                    Log.w(TAG, "Received unexpected message while disconnected.");
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    class Connecting extends State {
        private SDPBroadcastReceiver mSdpReceiver;

        @Override
        public void enter() {
            if (DBG) Log.d(TAG, "Enter Connecting: " + getCurrentMessage().what);
            onConnectionStateChanged(mCurrentDevice, mMostRecentState,
                    BluetoothProfile.STATE_CONNECTING);
            mSdpReceiver = new SDPBroadcastReceiver();
            mSdpReceiver.register();
            mCurrentDevice.sdpSearch(BluetoothUuid.PBAP_PSE);
            mMostRecentState = BluetoothProfile.STATE_CONNECTING;

            // Create a separate handler instance and thread for performing
            // connect/download/disconnect operations as they may be time consuming and error prone.
            mHandlerThread = new HandlerThread("PBAP PCE handler",
                    Process.THREAD_PRIORITY_BACKGROUND);
            mHandlerThread.start();
            mConnectionHandler = new PbapClientConnectionHandler.Builder()
                    .setLooper(mHandlerThread.getLooper())
                    .setContext(mContext)
                    .setClientSM(PbapClientStateMachine.this)
                    .setRemoteDevice(mCurrentDevice).build();

            sendMessageDelayed(MSG_CONNECT_TIMEOUT, CONNECT_TIMEOUT);
        }

        @Override
        public boolean processMessage(Message message) {
            if (DBG) Log.d(TAG, "Processing MSG " + message.what + " from " + this.getName());
            switch (message.what) {
                case MSG_DISCONNECT:
                    if (message.obj instanceof BluetoothDevice &&
                            ((BluetoothDevice) message.obj).equals(mCurrentDevice)) {
                        removeMessages(MSG_CONNECT_TIMEOUT);
                        transitionTo(mDisconnecting);
                    }
                    break;

                case MSG_CONNECTION_COMPLETE:
                    removeMessages(MSG_CONNECT_TIMEOUT);
                    transitionTo(mConnected);
                    break;

                case MSG_CONNECTION_FAILED:
                case MSG_CONNECT_TIMEOUT:
                    removeMessages(MSG_CONNECT_TIMEOUT);
                    transitionTo(mDisconnecting);
                    break;

                case MSG_CONNECT:
                    Log.w(TAG, "Connecting already in progress");
                    break;

                case MSG_SDP_COMPLETE:
                    mConnectionHandler.obtainMessage(PbapClientConnectionHandler.MSG_CONNECT,
                            message.obj).sendToTarget();
                    break;

                default:
                    Log.w(TAG, "Received unexpected message while Connecting");
                    return NOT_HANDLED;
            }
            return HANDLED;
        }

        @Override
        public void exit() {
            mSdpReceiver.unregister();
            mSdpReceiver = null;
        }

        private class SDPBroadcastReceiver extends BroadcastReceiver {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (DBG) Log.v(TAG, "onReceive" + action);
                if (action.equals(BluetoothDevice.ACTION_SDP_RECORD)) {
                    BluetoothDevice device = intent.getParcelableExtra(
                            BluetoothDevice.EXTRA_DEVICE);
                    if (!device.equals(getDevice())) {
                        Log.w(TAG, "SDP Record fetched for different device - Ignore");
                        return;
                    }
                    ParcelUuid uuid = intent.getParcelableExtra(BluetoothDevice.EXTRA_UUID);
                    if (DBG) Log.v(TAG, "Received UUID: " + uuid.toString());
                    if (DBG) Log.v(TAG, "expected UUID: " +
                            BluetoothUuid.PBAP_PSE.toString());
                    if (uuid.equals(BluetoothUuid.PBAP_PSE)) {
                        sendMessage(MSG_SDP_COMPLETE, intent
                                .getParcelableExtra(BluetoothDevice.EXTRA_SDP_RECORD));
                    }
                }
            }

            public void register() {
                IntentFilter filter = new IntentFilter();
                filter.addAction(BluetoothDevice.ACTION_SDP_RECORD);
                mContext.registerReceiver(this, filter);
            }

            public void unregister() {
                mContext.unregisterReceiver(this);
            }
        }
    }

    class Disconnecting extends State {
        @Override
        public void enter() {
            Log.d(TAG, "Enter Disconnecting: " + getCurrentMessage().what);
            onConnectionStateChanged(mCurrentDevice, mMostRecentState,
                    BluetoothProfile.STATE_DISCONNECTING);
            mMostRecentState = BluetoothProfile.STATE_DISCONNECTING;
            mConnectionHandler.obtainMessage(PbapClientConnectionHandler.MSG_DISCONNECT)
                    .sendToTarget();
            sendMessageDelayed(MSG_DISCONNECT_TIMEOUT, DISCONNECT_TIMEOUT);
        }

        @Override
        public boolean processMessage(Message message) {
            if (DBG) Log.d(TAG, "Processing MSG " + message.what + " from " + this.getName());
            switch (message.what) {
                case MSG_CONNECTION_CLOSED:
                    removeMessages(MSG_DISCONNECT_TIMEOUT);
                    mHandlerThread.quitSafely();
                    transitionTo(mDisconnected);
                    break;

                case MSG_CONNECT:
                case MSG_DISCONNECT:
                    deferMessage(message);
                    break;

                case MSG_DISCONNECT_TIMEOUT:
                    Log.w(TAG, "Disconnect Timeout, Forcing");
                    mConnectionHandler.abort();
                    break;

                case MSG_RESUME_DOWNLOAD:
                    // Do nothing.
                    break;

                default:
                    Log.w(TAG, "Received unexpected message while Disconnecting");
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    class Connected extends State {
        @Override
        public void enter() {
            Log.d(TAG, "Enter Connected: " + getCurrentMessage().what);
            onConnectionStateChanged(mCurrentDevice, mMostRecentState,
                    BluetoothProfile.STATE_CONNECTED);
            mMostRecentState = BluetoothProfile.STATE_CONNECTED;
            if (mUserManager.isUserUnlocked()) {
                mConnectionHandler.obtainMessage(PbapClientConnectionHandler.MSG_DOWNLOAD)
                        .sendToTarget();
            }
        }

        @Override
        public boolean processMessage(Message message) {
            if (DBG) Log.d(TAG, "Processing MSG " + message.what + " from " + this.getName());
            switch (message.what) {
                case MSG_CONNECT:
                    onConnectionStateChanged(mCurrentDevice, BluetoothProfile.STATE_CONNECTED,
                            BluetoothProfile.STATE_CONNECTED);


                    Log.w(TAG, "Received CONNECT while Connected, ignoring");
                    break;

                case MSG_DISCONNECT:
                    if ((message.obj instanceof BluetoothDevice) &&
                            ((BluetoothDevice) message.obj).equals(mCurrentDevice)) {
                        transitionTo(mDisconnecting);
                    }
                    break;

                case MSG_RESUME_DOWNLOAD:
                    mConnectionHandler.obtainMessage(PbapClientConnectionHandler.MSG_DOWNLOAD)
                            .sendToTarget();
                    break;

                default:
                    Log.w(TAG, "Received unexpected message while Connected");
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    private void onConnectionStateChanged(BluetoothDevice device, int prevState, int state) {
        if (device == null) {
            Log.w(TAG, "onConnectionStateChanged with invalid device");
            return;
        }
        Log.d(TAG, "Connection state " + device + ": " + prevState + "->" + state);
        Intent intent = new Intent(BluetoothPbapClient.ACTION_CONNECTION_STATE_CHANGED);
        intent.putExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, prevState);
        intent.putExtra(BluetoothProfile.EXTRA_STATE, state);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        mContext.sendBroadcast(intent, ProfileService.BLUETOOTH_PERM);
        mService.notifyProfileConnectionStateChanged(device, BluetoothProfile.PBAP_CLIENT, state,
                prevState);
    }

    public void connect(BluetoothDevice device) {
        Log.d(TAG, "Connect Request " + device.getAddress());
        sendMessage(MSG_CONNECT, device);
    }

    public void disconnect(BluetoothDevice device) {
        Log.d(TAG, "Disconnect Request " + device);
        sendMessage(MSG_DISCONNECT, device);
    }

    public void resumeDownload() {
        removeUncleanAccounts();
        sendMessage(MSG_RESUME_DOWNLOAD);
    }

    void doQuit() {
        removeUncleanAccounts();
        if (mHandlerThread != null) {
            mHandlerThread.quitSafely();
        }
        quitNow();
    }

    public int getConnectionState() {
        IState currentState = getCurrentState();
        if (currentState instanceof Disconnected) {
            return BluetoothProfile.STATE_DISCONNECTED;
        } else if (currentState instanceof Connecting) {
            return BluetoothProfile.STATE_CONNECTING;
        } else if (currentState instanceof Connected) {
            return BluetoothProfile.STATE_CONNECTED;
        } else if (currentState instanceof Disconnecting) {
            return BluetoothProfile.STATE_DISCONNECTING;
        }
        Log.w(TAG, "Unknown State");
        return BluetoothProfile.STATE_DISCONNECTED;
    }

    public List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) {
        int clientState = -1;
        BluetoothDevice currentDevice = null;
        synchronized (mLock) {
            clientState = getConnectionState();
            currentDevice = getDevice();
        }
        List<BluetoothDevice> deviceList = new ArrayList<BluetoothDevice>();
        for (int state : states) {
            if (clientState == state) {
                if (currentDevice != null) {
                    deviceList.add(currentDevice);
                }
            }
        }
        return deviceList;
    }

    public int getConnectionState(BluetoothDevice device) {
        if (device == null) {
            return BluetoothProfile.STATE_DISCONNECTED;
        }
        synchronized (mLock) {
            if (device.equals(mCurrentDevice)) {
                return getConnectionState();
            }
        }
        return BluetoothProfile.STATE_DISCONNECTED;
    }


    public BluetoothDevice getDevice() {
        /*
         * Disconnected is the only state where device can change, and to prevent the race
         * condition of reporting a valid device while disconnected fix the report here.  Note that
         * Synchronization of the state and device is not possible with current state machine
         * desingn since the actual Transition happens sometime after the transitionTo method.
         */
        if (getCurrentState() instanceof Disconnected) {
            return null;
        }
        return mCurrentDevice;
    }

    Context getContext() {
        return mContext;
    }

    private void removeUncleanAccounts() {
        // Find all accounts that match the type "pbap" and delete them.
        AccountManager accountManager = AccountManager.get(mContext);
        Account[] accounts = accountManager.getAccountsByType(
                mContext.getString(R.string.pbap_account_type));
        Log.w(TAG, "Found " + accounts.length + " unclean accounts");
        for (Account acc : accounts) {
            Log.w(TAG, "Deleting " + acc);
            // The device ID is the name of the account.
            accountManager.removeAccountExplicitly(acc);
        }
        try {
            mContext.getContentResolver().delete(CallLog.Calls.CONTENT_URI, null, null);
        } catch (IllegalArgumentException e) {
            // CallLogs could not be deleted, they may not exist yet.
        }
    }

    public void dump(StringBuilder sb) {
        ProfileService.println(sb, "mCurrentDevice: " + mCurrentDevice);
        ProfileService.println(sb, "StateMachine: " + this.toString());
    }
}
