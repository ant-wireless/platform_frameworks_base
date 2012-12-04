/*
 * Copyright (C) 2011 The Android Open Source Project
 * Copyright (C) 2012 Dynastream Innovations
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

package android.server;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.IBluetoothStateChangeCallback;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Message;
import android.os.RemoteException;
import android.provider.Settings;
import android.util.Log;

import com.android.internal.util.IState;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;

import java.io.PrintWriter;

/**
 * Bluetooth Adapter StateMachine
 * All the states are at the same level, ie, no hierarchy.
 *
 *                                             USER_TURN_ON
 *                                      <-------------------------
 *                         (BluetoothOn)------------------------->(PerProcessState)
 *                           |  ^       \  ^           m1           /  ^     |  ^
 *                 TURN_OFF  |  | m4     \  \ m7               m10 /  /      |  |
 *         AIRPLANE_MODE_ON  |  |         \  \                    /  /       |  |
 *                           V  |       m8 V  \              <---/  /        |  |
 *                       (Switching)       (AntWirelessState)------/ m9     /  /
 *                           |  ^               /  ^                       /  /
 *     POWER_STATE_CHANGED & |  |           m6 /  / m5                    /  /
 * ALL_DEVICES_DISCONNECTED  |  | m3          V  /                    m2 /  /
 *                           |  |----------(HotOff)<--------------------/  / SCAN_MODE_CHANGED &
 *                           |------------> |    ^ -----------------------/  PER_PROCESS_TURN_ON
 *                                          /    |
 *                                         /     |  SERVICE_RECORD_LOADED
 *                                        |      |
 *                             TURN_COLD  |   (Warmup)
 *                                        \      ^
 *                                         \     |  TURN_HOT/TURN_ON
 *                                          |    |  AIRPLANE_MODE_OFF(when Bluetooth was on before)
 *                                          V    |
 *                                        (PowerOff)  <----- initial state
 *
 * Legend:
 * m1 =  TURN_HOT when the number of process wanting BT is non-zero.
 * m2 =  Transition to HotOff when number of process wanting BT on is 0 and ANT wireless if off.
 *       POWER_STATE_CHANGED will make the transition.
 * m3 =  TURN_ON(_CONTINUE)
 * m4 =  SCAN_MODE_CHANGED
 * m5 =  ANT_WIRELESS_TURN_ON This will also disable PSCAN on hci0.
 * m6 =  ANT_WIRELESS_TURN_OFF
 * m7 =  USER_TURN_ON This will also enable PSCAN on hci0.
 * m8 =  TURN_HOT when ANT wireless is on, and the number of process want BT on is 0.
                 this will also disable PSCAN.
 * m9 =  PER_PROCESS_TURN_ON & SCAN_MODE_CHANGED This will also enable PSCAN on hci0.
 * m10 = Transition to AntWirelessState when number of process wanting BT is 0 and ANT wireless is
         on. PER_PROCESS_TURN_OFF will make the transition. this will also disable PSCAN.
 * Note:
 * The diagram above shows all the states and messages that trigger normal state changes.
 * The diagram above does not capture everything:
 *   The diagram does not capture following messages.
 *   - messages that do not trigger state changes
 *     For example, PER_PROCESS_TURN_ON received in BluetoothOn state
 *   - unhandled messages
 *     For example, USER_TURN_ON received in BluetoothOn state
 *   - timeout messages
 *   The diagram does not capture error conditions and state recoveries.
 *   - For example POWER_STATE_CHANGED received in BluetoothOn state
 */
final class BluetoothAdapterStateMachine extends StateMachine {
    private static final String TAG = "BluetoothAdapterStateMachine";
    private static final boolean DBG = false;

    // Message(what) to take an action
    //
    // We get this message when user tries to turn on BT
    static final int USER_TURN_ON = 1;
    // We get this message when user tries to turn off BT
    static final int USER_TURN_OFF = 2;
    // Per process enable / disable messages
    static final int PER_PROCESS_TURN_ON = 3;
    static final int PER_PROCESS_TURN_OFF = 4;

    // Turn on Bluetooth Module, Load firmware, and do all the preparation
    // needed to get the Bluetooth Module ready but keep it not discoverable
    // and not connectable. This way the Bluetooth Module can be quickly
    // switched on if needed
    static final int TURN_HOT = 5;

    // ANT Wireless enable / disable messages to prevent Bluetooth from powering off
    // the chip when ANT is active.
    static final int ANT_WIRELESS_TURN_ON = 6;
    static final int ANT_WIRELESS_TURN_OFF = 7;

    // Message(what) to report a event that the state machine need to respond to
    //
    // Event indicates sevice records have been loaded
    static final int SERVICE_RECORD_LOADED = 51;
    // Event indicates all the remote Bluetooth devices has been disconnected
    static final int ALL_DEVICES_DISCONNECTED = 52;
    // Event indicates the Bluetooth scan mode has changed
    static final int SCAN_MODE_CHANGED = 53;
    // Event indicates the powered state has changed
    static final int POWER_STATE_CHANGED = 54;
    // Event indicates airplane mode is turned on
    static final int AIRPLANE_MODE_ON = 55;
    // Event indicates airplane mode is turned off
    static final int AIRPLANE_MODE_OFF = 56;

    // private internal messages
    //
    // USER_TURN_ON is changed to TURN_ON_CONTINUE after we broadcast the
    // state change intent so that we will not broadcast the intent again in
    // other state
    private static final int TURN_ON_CONTINUE = 101;
    // Unload firmware, turning off Bluetooth module power
    private static final int TURN_COLD = 102;
    // Device disconnecting timeout happens
    private static final int DEVICES_DISCONNECT_TIMEOUT = 103;
    // Prepare Bluetooth timeout happens
    private static final int PREPARE_BLUETOOTH_TIMEOUT = 104;
    // Bluetooth turn off wait timeout happens
    private static final int TURN_OFF_TIMEOUT = 105;
    // Bluetooth device power off wait timeout happens
    private static final int POWER_DOWN_TIMEOUT = 106;

    private Context mContext;
    private BluetoothService mBluetoothService;
    private BluetoothEventLoop mEventLoop;

    private BluetoothOn mBluetoothOn;
    private Switching mSwitching;
    private HotOff mHotOff;
    private WarmUp mWarmUp;
    private PowerOff mPowerOff;
    private PerProcessState mPerProcessState;
    private AntWirelessState mAntWirelessState;

    // this indicates when ANT Wireless is active, meaning bluetooth off should not
    // power down the chip
    private boolean mAntWirelessActive = false;
    // this holds the ANT Wireless callback, it is remembered for when chip power is lost
    private IBluetoothStateChangeCallback mAntWirelessCallback;

    // this is the BluetoothAdapter state that reported externally
    private int mPublicState;
    // When turning off, broadcast STATE_OFF in the last HotOff state
    // This is because we do HotOff -> PowerOff -> HotOff for USER_TURN_OFF
    private boolean mDelayBroadcastStateOff;

    // timeout value waiting for all the devices to be disconnected
    private static final int DEVICES_DISCONNECT_TIMEOUT_TIME = 3000;

    private static final int PREPARE_BLUETOOTH_TIMEOUT_TIME = 10000;

    private static final int TURN_OFF_TIMEOUT_TIME = 5000;
    private static final int POWER_DOWN_TIMEOUT_TIME = 20;

    BluetoothAdapterStateMachine(Context context, BluetoothService bluetoothService,
                                 BluetoothAdapter bluetoothAdapter) {
        super(TAG);
        mContext = context;
        mBluetoothService = bluetoothService;
        mEventLoop = new BluetoothEventLoop(context, bluetoothAdapter, bluetoothService, this);

        mBluetoothOn = new BluetoothOn();
        mSwitching = new Switching();
        mHotOff = new HotOff();
        mWarmUp = new WarmUp();
        mPowerOff = new PowerOff();
        mPerProcessState = new PerProcessState();
        mAntWirelessState = new AntWirelessState();

        addState(mBluetoothOn);
        addState(mSwitching);
        addState(mHotOff);
        addState(mWarmUp);
        addState(mPowerOff);
        addState(mPerProcessState);
        addState(mAntWirelessState);

        setInitialState(mPowerOff);
        mPublicState = BluetoothAdapter.STATE_OFF;
        mDelayBroadcastStateOff = false;
    }

    /**
     * Bluetooth module's power is off, firmware is not loaded.
     */
    private class PowerOff extends State {
        @Override
        public void enter() {
            if (DBG) log("Enter PowerOff: " + getCurrentMessage().what);
            if (mAntWirelessActive) {
                antWirelessCallback(false); // chip power lost, notify ANT Wireless
            }
        }
        @Override
        public boolean processMessage(Message message) {
            log("PowerOff process message: " + message.what);

            boolean retValue = HANDLED;
            switch(message.what) {
                case USER_TURN_ON:
                    // starts turning on BT module, broadcast this out
                    broadcastState(BluetoothAdapter.STATE_TURNING_ON);
                    transitionTo(mWarmUp);
                    if (prepareBluetooth()) {
                        // this is user request, save the setting
                        if ((Boolean) message.obj) {
                            persistSwitchSetting(true);
                        }
                        // We will continue turn the BT on all the way to the BluetoothOn state
                        deferMessage(obtainMessage(TURN_ON_CONTINUE));
                    } else {
                        Log.e(TAG, "failed to prepare bluetooth, abort turning on");
                        transitionTo(mPowerOff);
                        broadcastState(BluetoothAdapter.STATE_OFF);
                    }
                    break;
                case TURN_HOT:
                    if (prepareBluetooth()) {
                        transitionTo(mWarmUp);
                    }
                    break;
                case AIRPLANE_MODE_OFF:
                    if (getBluetoothPersistedSetting()) {
                        // starts turning on BT module, broadcast this out
                        broadcastState(BluetoothAdapter.STATE_TURNING_ON);
                        transitionTo(mWarmUp);
                        if (prepareBluetooth()) {
                            // We will continue turn the BT on all the way to the BluetoothOn state
                            deferMessage(obtainMessage(TURN_ON_CONTINUE));
                            transitionTo(mWarmUp);
                        } else {
                            Log.e(TAG, "failed to prepare bluetooth, abort turning on");
                            transitionTo(mPowerOff);
                            broadcastState(BluetoothAdapter.STATE_OFF);
                        }
                    } else if (mContext.getResources().getBoolean
                            (com.android.internal.R.bool.config_bluetooth_adapter_quick_switch)) {
                        sendMessage(TURN_HOT);
                    }
                    break;
                case PER_PROCESS_TURN_ON:
                    if (prepareBluetooth()) {
                        transitionTo(mWarmUp);
                    }
                    deferMessage(obtainMessage(PER_PROCESS_TURN_ON));
                    break;
                case PER_PROCESS_TURN_OFF:
                    perProcessCallback(false, (IBluetoothStateChangeCallback) message.obj);
                    break;
                case ANT_WIRELESS_TURN_ON:
                    if (prepareBluetooth()) {
                        transitionTo(mWarmUp);
                    }
                    deferMessage(message);
                    break;
                case ANT_WIRELESS_TURN_OFF:
                    antWirelessCallback(false, (IBluetoothStateChangeCallback) message.obj);
                    break;
                case USER_TURN_OFF:
                    Log.w(TAG, "PowerOff received: " + message.what);
                case AIRPLANE_MODE_ON: // ignore
                    break;
                default:
                    return NOT_HANDLED;
            }
            return retValue;
        }

        /**
         * Turn on Bluetooth Module, Load firmware, and do all the preparation
         * needed to get the Bluetooth Module ready but keep it not discoverable
         * and not connectable.
         * The last step of this method sets up the local service record DB.
         * There will be a event reporting the status of the SDP setup.
         */
        private boolean prepareBluetooth() {
            if (mBluetoothService.enableNative() != 0) {
                return false;
            }

            // try to start event loop, give 2 attempts
            int retryCount = 2;
            boolean eventLoopStarted = false;
            while ((retryCount-- > 0) && !eventLoopStarted) {
                mEventLoop.start();
                // it may take a moment for the other thread to do its
                // thing.  Check periodically for a while.
                int pollCount = 5;
                while ((pollCount-- > 0) && !eventLoopStarted) {
                    if (mEventLoop.isEventLoopRunning()) {
                        eventLoopStarted = true;
                        break;
                    }
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        log("prepareBluetooth sleep interrupted: " + pollCount);
                        break;
                    }
                }
            }

            if (!eventLoopStarted) {
                mBluetoothService.disableNative();
                return false;
            }

            // get BluetoothService ready
            if (!mBluetoothService.prepareBluetooth()) {
                mEventLoop.stop();
                mBluetoothService.disableNative();
                return false;
            }

            sendMessageDelayed(PREPARE_BLUETOOTH_TIMEOUT, PREPARE_BLUETOOTH_TIMEOUT_TIME);
            return true;
        }
    }

    /**
     * Turning on Bluetooth module's power, loading firmware, starting
     * event loop thread to listen on Bluetooth module event changes.
     */
    private class WarmUp extends State {

        @Override
        public void enter() {
            if (DBG) log("Enter WarmUp: " + getCurrentMessage().what);
        }

        @Override
        public boolean processMessage(Message message) {
            log("WarmUp process message: " + message.what);

            boolean retValue = HANDLED;
            switch(message.what) {
                case SERVICE_RECORD_LOADED:
                    removeMessages(PREPARE_BLUETOOTH_TIMEOUT);
                    transitionTo(mHotOff);
                    if (mDelayBroadcastStateOff) {
                        broadcastState(BluetoothAdapter.STATE_OFF);
                        mDelayBroadcastStateOff = false;
                    }
                    break;
                case PREPARE_BLUETOOTH_TIMEOUT:
                    Log.e(TAG, "Bluetooth adapter SDP failed to load");
                    shutoffBluetooth();
                    transitionTo(mPowerOff);
                    broadcastState(BluetoothAdapter.STATE_OFF);
                    break;
                case USER_TURN_ON: // handle this at HotOff state
                case TURN_ON_CONTINUE: // Once in HotOff state, continue turn bluetooth
                                       // on to the BluetoothOn state
                case AIRPLANE_MODE_ON:
                case AIRPLANE_MODE_OFF:
                case PER_PROCESS_TURN_ON:
                case PER_PROCESS_TURN_OFF:
                case ANT_WIRELESS_TURN_ON:
                case ANT_WIRELESS_TURN_OFF:
                    deferMessage(message);
                    break;
                case USER_TURN_OFF:
                    Log.w(TAG, "WarmUp received: " + message.what);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return retValue;
        }

    }

    /**
     * Bluetooth Module has powered, firmware loaded, event loop started,
     * SDP loaded, but the modules stays non-discoverable and
     * non-connectable.
     */
    private class HotOff extends State {
        @Override
        public void enter() {
            if (DBG) log("Enter HotOff: " + getCurrentMessage().what);
        }

        @Override
        public boolean processMessage(Message message) {
            log("HotOff process message: " + message.what);

            boolean retValue = HANDLED;
            switch(message.what) {
                case USER_TURN_ON:
                    broadcastState(BluetoothAdapter.STATE_TURNING_ON);
                    if ((Boolean) message.obj) {
                        persistSwitchSetting(true);
                    }
                    // let it fall to TURN_ON_CONTINUE:
                    //$FALL-THROUGH$
                case TURN_ON_CONTINUE:
                    mBluetoothService.switchConnectable(true);
                    transitionTo(mSwitching);
                    break;
                case AIRPLANE_MODE_ON:
                case TURN_COLD:
                    shutoffBluetooth();
                    // we cannot go to power off state yet, we need wait for the Bluetooth
                    // device power off. Unfortunately the stack does not give a event back
                    // so we wait a little bit here
                    sendMessageDelayed(POWER_DOWN_TIMEOUT,
                                       POWER_DOWN_TIMEOUT_TIME);
                    break;
                case POWER_DOWN_TIMEOUT:
                    transitionTo(mPowerOff);
                    if (!mDelayBroadcastStateOff) {
                        broadcastState(BluetoothAdapter.STATE_OFF);
                    }
                    break;
                case AIRPLANE_MODE_OFF:
                    if (getBluetoothPersistedSetting()) {
                        broadcastState(BluetoothAdapter.STATE_TURNING_ON);
                        transitionTo(mSwitching);
                        mBluetoothService.switchConnectable(true);
                    }
                    break;
                case PER_PROCESS_TURN_ON:
                    transitionTo(mPerProcessState);

                    // Resend the PER_PROCESS_TURN_ON message so that the callback
                    // can be sent through.
                    deferMessage(message);

                    mBluetoothService.switchConnectable(true);
                    break;
                case PER_PROCESS_TURN_OFF:
                    perProcessCallback(false, (IBluetoothStateChangeCallback)message.obj);
                    break;
                case ANT_WIRELESS_TURN_ON:
                    transitionTo(mAntWirelessState);

                    // Resend the ANT_WIRELESS_TURN_ON message so that the callback
                    // can be sent through.
                    deferMessage(message);

                    mBluetoothService.switchConnectable(true);
                    break;
                case ANT_WIRELESS_TURN_OFF:
                    antWirelessCallback(false, (IBluetoothStateChangeCallback)message.obj);
                    break;
                case USER_TURN_OFF: // ignore
                    break;
                case POWER_STATE_CHANGED:
                    if ((Boolean) message.obj) {
                        recoverStateMachine(TURN_HOT, null);
                    }
                    break;
                case TURN_HOT:
                    deferMessage(message);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return retValue;
        }

    }

    private class Switching extends State {

        @Override
        public void enter() {
            if (DBG) log("Enter Switching: " + getCurrentMessage().what);
        }
        @Override
        public boolean processMessage(Message message) {
            log("Switching process message: " + message.what);

            boolean retValue = HANDLED;
            switch(message.what) {
                case SCAN_MODE_CHANGED:
                    // This event matches mBluetoothService.switchConnectable action
                    if (mPublicState == BluetoothAdapter.STATE_TURNING_ON) {
                        // set pairable if it's not
                        mBluetoothService.setPairable();
                        mBluetoothService.initBluetoothAfterTurningOn();
                        transitionTo(mBluetoothOn);
                        broadcastState(BluetoothAdapter.STATE_ON);
                        // run bluetooth now that it's turned on
                        // Note runBluetooth should be called only in adapter STATE_ON
                        mBluetoothService.runBluetooth();
                    }
                    break;
                case POWER_STATE_CHANGED:
                    removeMessages(TURN_OFF_TIMEOUT);
                    if (!((Boolean) message.obj)) {
                        if (mPublicState == BluetoothAdapter.STATE_TURNING_OFF) {
                            transitionTo(mHotOff);
                            mBluetoothService.finishDisable();
                            mBluetoothService.cleanupAfterFinishDisable();
                            deferMessage(obtainMessage(TURN_COLD));
                            if (mContext.getResources().getBoolean
                                (com.android.internal.R.bool.config_bluetooth_adapter_quick_switch) &&
                                !mBluetoothService.isAirplaneModeOn()) {
                                deferMessage(obtainMessage(TURN_HOT));
                                mDelayBroadcastStateOff = true;
                            }
                        }
                    } else {
                        if (mPublicState != BluetoothAdapter.STATE_TURNING_ON) {
                            if (mContext.getResources().getBoolean
                            (com.android.internal.R.bool.config_bluetooth_adapter_quick_switch)) {
                                recoverStateMachine(TURN_HOT, null);
                            } else {
                                recoverStateMachine(TURN_COLD, null);
                            }
                        }
                    }
                    break;
                case ALL_DEVICES_DISCONNECTED:
                    removeMessages(DEVICES_DISCONNECT_TIMEOUT);
                    mBluetoothService.switchConnectable(false);
                    sendMessageDelayed(TURN_OFF_TIMEOUT, TURN_OFF_TIMEOUT_TIME);
                    break;
                case DEVICES_DISCONNECT_TIMEOUT:
                    sendMessage(ALL_DEVICES_DISCONNECTED);
                    // reset the hardware for error recovery
                    Log.e(TAG, "Devices failed to disconnect, reseting...");
                    deferMessage(obtainMessage(TURN_COLD));
                    if (mContext.getResources().getBoolean
                        (com.android.internal.R.bool.config_bluetooth_adapter_quick_switch)) {
                        deferMessage(obtainMessage(TURN_HOT));
                    }
                    break;
                case TURN_OFF_TIMEOUT:
                    transitionTo(mHotOff);
                    finishSwitchingOff();
                    // reset the hardware for error recovery
                    Log.e(TAG, "Devices failed to power down, reseting...");
                    deferMessage(obtainMessage(TURN_COLD));
                    if (mContext.getResources().getBoolean
                        (com.android.internal.R.bool.config_bluetooth_adapter_quick_switch)) {
                        deferMessage(obtainMessage(TURN_HOT));
                    }
                    break;
                case USER_TURN_ON:
                case AIRPLANE_MODE_OFF:
                case AIRPLANE_MODE_ON:
                case PER_PROCESS_TURN_ON:
                case PER_PROCESS_TURN_OFF:
                case ANT_WIRELESS_TURN_ON:
                case ANT_WIRELESS_TURN_OFF:
                case USER_TURN_OFF:
                    deferMessage(message);
                    break;

                default:
                    return NOT_HANDLED;
            }
            return retValue;
        }
    }

    private class BluetoothOn extends State {

        @Override
        public void enter() {
            if (DBG) log("Enter BluetoothOn: " + getCurrentMessage().what);
        }
        @Override
        public boolean processMessage(Message message) {
            log("BluetoothOn process message: " + message.what);

            boolean retValue = HANDLED;
            switch(message.what) {
                case USER_TURN_OFF:
                    if ((Boolean) message.obj) {
                        persistSwitchSetting(false);
                    }

                    if (mBluetoothService.isDiscovering()) {
                        mBluetoothService.cancelDiscovery();
                    }
                    if (!mBluetoothService.isApplicationStateChangeTrackerEmpty()) {
                        transitionTo(mPerProcessState);
                        deferMessage(obtainMessage(TURN_HOT));
                        break;
                    } else if (mAntWirelessActive) {
                        transitionTo(mAntWirelessState);
                        deferMessage(obtainMessage(TURN_HOT));
                        break;
                    }
                    //$FALL-THROUGH$ to AIRPLANE_MODE_ON
                case AIRPLANE_MODE_ON:
                    broadcastState(BluetoothAdapter.STATE_TURNING_OFF);
                    transitionTo(mSwitching);
                    if (mBluetoothService.getAdapterConnectionState() !=
                        BluetoothAdapter.STATE_DISCONNECTED) {
                        mBluetoothService.disconnectDevices();
                        sendMessageDelayed(DEVICES_DISCONNECT_TIMEOUT,
                                           DEVICES_DISCONNECT_TIMEOUT_TIME);
                    } else {
                        mBluetoothService.switchConnectable(false);
                        sendMessageDelayed(TURN_OFF_TIMEOUT, TURN_OFF_TIMEOUT_TIME);
                    }

                    if (message.what == AIRPLANE_MODE_ON || mBluetoothService.isAirplaneModeOn()) {
                        // We inform all the per process callbacks
                        allProcessesCallback(false);
                        antWirelessCallback(false);
                        deferMessage(obtainMessage(AIRPLANE_MODE_ON));
                    }
                    break;
                case AIRPLANE_MODE_OFF:
                case USER_TURN_ON:
                    Log.w(TAG, "BluetoothOn received: " + message.what);
                    break;
                case PER_PROCESS_TURN_ON:
                    perProcessCallback(true, (IBluetoothStateChangeCallback)message.obj);
                    break;
                case PER_PROCESS_TURN_OFF:
                    perProcessCallback(false, (IBluetoothStateChangeCallback)message.obj);
                    break;
                case ANT_WIRELESS_TURN_ON:
                    antWirelessCallback(true, (IBluetoothStateChangeCallback)message.obj);
                    break;
                case ANT_WIRELESS_TURN_OFF:
                    antWirelessCallback(false, (IBluetoothStateChangeCallback)message.obj);
                    break;
                case POWER_STATE_CHANGED:
                    if ((Boolean) message.obj) {
                        if (!mAntWirelessActive) {
                            // reset the state machine and send it TURN_ON_CONTINUE message
                            recoverStateMachine(USER_TURN_ON, false);
                        }
                    }
                    break;
                default:
                    return NOT_HANDLED;
            }
            return retValue;
        }

    }


    private class PerProcessState extends State {
        IBluetoothStateChangeCallback mCallback = null;
        boolean isTurningOn = false;

        @Override
        public void enter() {
            int what = getCurrentMessage().what;
            if (DBG) log("Enter PerProcessState: " + what);

            if (what == PER_PROCESS_TURN_ON) {
                isTurningOn = true;
            } else if (what == USER_TURN_OFF) {
                isTurningOn = false;
            } else {
                Log.e(TAG, "enter PerProcessState: wrong msg: " + what);
            }
        }

        @Override
        public boolean processMessage(Message message) {
            log("PerProcessState process message: " + message.what);

            boolean retValue = HANDLED;
            switch (message.what) {
                case PER_PROCESS_TURN_ON:
                    mCallback = (IBluetoothStateChangeCallback)getCurrentMessage().obj;

                    // If this is not the first application call the callback.
                    if (mBluetoothService.getNumberOfApplicationStateChangeTrackers() > 1) {
                        perProcessCallback(true, mCallback);
                    }
                    break;
                case SCAN_MODE_CHANGED:
                    if (isTurningOn) {
                        perProcessCallback(true, mCallback);
                        isTurningOn = false;
                    }
                    break;
                case POWER_STATE_CHANGED:
                    removeMessages(TURN_OFF_TIMEOUT);
                    if (!((Boolean) message.obj)) {
                        transitionTo(mHotOff);
                        if (!mContext.getResources().getBoolean
                            (com.android.internal.R.bool.config_bluetooth_adapter_quick_switch)) {
                            deferMessage(obtainMessage(TURN_COLD));
                        }
                    } else {
                        if (!isTurningOn) {
                            recoverStateMachine(TURN_COLD, null);
                            for (IBluetoothStateChangeCallback c:
                                     mBluetoothService.getApplicationStateChangeCallbacks()) {
                                perProcessCallback(false, c);
                                deferMessage(obtainMessage(PER_PROCESS_TURN_ON, c));
                            }
                            if (mAntWirelessActive) {
                                antWirelessCallback(false);
                                deferMessage(obtainMessage(ANT_WIRELESS_TURN_ON,
                                        mAntWirelessCallback));
                            }
                        }
                    }
                    break;
                case TURN_OFF_TIMEOUT:
                    transitionTo(mHotOff);
                    Log.e(TAG, "Power-down timed out, resetting...");
                    deferMessage(obtainMessage(TURN_COLD));
                    if (mContext.getResources().getBoolean
                        (com.android.internal.R.bool.config_bluetooth_adapter_quick_switch)) {
                        deferMessage(obtainMessage(TURN_HOT));
                    }
                    break;
                case USER_TURN_ON:
                    broadcastState(BluetoothAdapter.STATE_TURNING_ON);
                    persistSwitchSetting(true);
                    mBluetoothService.initBluetoothAfterTurningOn();
                    transitionTo(mBluetoothOn);
                    broadcastState(BluetoothAdapter.STATE_ON);
                    // run bluetooth now that it's turned on
                    mBluetoothService.runBluetooth();
                    break;
                case TURN_HOT:
                    broadcastState(BluetoothAdapter.STATE_TURNING_OFF);
                    if (mBluetoothService.getAdapterConnectionState() !=
                        BluetoothAdapter.STATE_DISCONNECTED) {
                        mBluetoothService.disconnectDevices();
                        sendMessageDelayed(DEVICES_DISCONNECT_TIMEOUT,
                                           DEVICES_DISCONNECT_TIMEOUT_TIME);
                        break;
                    }
                    //$FALL-THROUGH$ all devices are already disconnected
                case ALL_DEVICES_DISCONNECTED:
                    removeMessages(DEVICES_DISCONNECT_TIMEOUT);
                    finishSwitchingOff();
                    break;
                case DEVICES_DISCONNECT_TIMEOUT:
                    finishSwitchingOff();
                    Log.e(TAG, "Devices fail to disconnect, reseting...");
                    transitionTo(mHotOff);
                    deferMessage(obtainMessage(TURN_COLD));
                    for (IBluetoothStateChangeCallback c:
                             mBluetoothService.getApplicationStateChangeCallbacks()) {
                        perProcessCallback(false, c);
                        deferMessage(obtainMessage(PER_PROCESS_TURN_ON, c));
                    }
                    if (mAntWirelessActive) {
                        antWirelessCallback(false);
                        deferMessage(obtainMessage(ANT_WIRELESS_TURN_ON, mAntWirelessCallback));
                    }
                    break;
                case PER_PROCESS_TURN_OFF:
                    perProcessCallback(false, (IBluetoothStateChangeCallback)message.obj);
                    if (mBluetoothService.isApplicationStateChangeTrackerEmpty()) {
                        if (mAntWirelessActive) {
                            transitionTo(mAntWirelessState);
                            deferMessage(message);
                        } else {
                            mBluetoothService.switchConnectable(false);
                            sendMessageDelayed(TURN_OFF_TIMEOUT, TURN_OFF_TIMEOUT_TIME);
                        }
                    }
                    break;
                case AIRPLANE_MODE_ON:
                    mBluetoothService.switchConnectable(false);
                    sendMessageDelayed(TURN_OFF_TIMEOUT, TURN_OFF_TIMEOUT_TIME);
                    allProcessesCallback(false);
                    antWirelessCallback(false);
                    // we turn all the way to PowerOff with AIRPLANE_MODE_ON
                    deferMessage(obtainMessage(AIRPLANE_MODE_ON));
                    break;
                case USER_TURN_OFF:
                    Log.w(TAG, "PerProcessState received: " + message.what);
                    break;
                case ANT_WIRELESS_TURN_ON:
                    antWirelessCallback(true, (IBluetoothStateChangeCallback)message.obj);
                    break;
                case ANT_WIRELESS_TURN_OFF:
                    antWirelessCallback(false, (IBluetoothStateChangeCallback)message.obj);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return retValue;
        }
    }

    private class AntWirelessState extends State {
        boolean isTurningOn = false;
        boolean isTurningOff = false;

        @Override
        public void enter() {
            int what = getCurrentMessage().what;
            if (DBG) log("Enter AntWirelessState: " + what);

            if (what == ANT_WIRELESS_TURN_ON) {
                isTurningOn = true;
            } else if (what == USER_TURN_OFF || what == PER_PROCESS_TURN_OFF) {
                isTurningOn = false;
            } else {
                Log.e(TAG, "enter AntWirelessState: wrong msg: " + what);
            }
            isTurningOff = false;
        }

        @Override
        public boolean processMessage(Message message) {
            log("AntWirelessState process message: " + message.what);

            boolean retValue = HANDLED;
            switch (message.what) {
                case ANT_WIRELESS_TURN_ON:
                    if (mAntWirelessActive) { // already on
                        antWirelessCallback(true, (IBluetoothStateChangeCallback) message.obj);
                    } else {
                        // save the callback for when power on signal is received
                        mAntWirelessCallback = (IBluetoothStateChangeCallback) message.obj;
                    }
                    break;
                case ANT_WIRELESS_TURN_OFF:
                    // BlueZ wont let us turn off without PSCAN on, so if needed turn it on, and
                    // wait for the response, then switch connectable.
                    if (mBluetoothService.getHciScanMode() == BluetoothAdapter.SCAN_MODE_NONE) {
                        isTurningOff = true;
                        mBluetoothService.setHciScanMode(BluetoothAdapter.SCAN_MODE_CONNECTABLE);
                    } else {
                        mBluetoothService.switchConnectable(false);
                    }
                    sendMessageDelayed(TURN_OFF_TIMEOUT, TURN_OFF_TIMEOUT_TIME);
                    break;
                case SCAN_MODE_CHANGED:
                    if (isTurningOff) {
                        if (mBluetoothService.getHciScanMode() != BluetoothAdapter.SCAN_MODE_NONE) {
                            // Now that PSCAN is turned on, we can power off
                            mBluetoothService.switchConnectable(false);
                        }
                    }
                    break;
                case POWER_STATE_CHANGED:
                    removeMessages(TURN_OFF_TIMEOUT);
                    if (!((Boolean) message.obj)) {
                        // power is gone, notify callback
                        antWirelessCallback(false);
                        transitionTo(mHotOff);
                        if (!mContext.getResources().getBoolean
                            (com.android.internal.R.bool.config_bluetooth_adapter_quick_switch)) {
                            deferMessage(obtainMessage(TURN_COLD));
                        }
                    } else {
                        if (isTurningOn) {
                            log("ANT_WIRELESS_TURN_ON disable scan");
                            mBluetoothService.setHciScanMode(BluetoothAdapter.SCAN_MODE_NONE);
                            antWirelessCallback(true);
                        } else {
                            recoverStateMachine(TURN_COLD, null);
                            antWirelessCallback(false);
                            deferMessage(obtainMessage(ANT_WIRELESS_TURN_ON, mAntWirelessCallback));
                        }
                    }
                    break;
                case TURN_OFF_TIMEOUT:
                    transitionTo(mHotOff);
                    Log.e(TAG, "Power-down timed out, resetting...");
                    deferMessage(obtainMessage(TURN_COLD));
                    if (mContext.getResources().getBoolean
                        (com.android.internal.R.bool.config_bluetooth_adapter_quick_switch)) {
                        deferMessage(obtainMessage(TURN_HOT));
                    }
                    break;
                case USER_TURN_ON:
                    broadcastState(BluetoothAdapter.STATE_TURNING_ON);
                    log("USER_TURN_ON enable scan");
                    mBluetoothService.setHciScanMode(BluetoothAdapter.SCAN_MODE_CONNECTABLE);
                    antWirelessCallback(true);
                    persistSwitchSetting(true);
                    mBluetoothService.initBluetoothAfterTurningOn();
                    transitionTo(mBluetoothOn);
                    broadcastState(BluetoothAdapter.STATE_ON);
                    // run bluetooth now that it's turned on
                    mBluetoothService.runBluetooth();
                    break;
                case TURN_HOT:
                    broadcastState(BluetoothAdapter.STATE_TURNING_OFF);
                    if (mBluetoothService.getAdapterConnectionState() !=
                        BluetoothAdapter.STATE_DISCONNECTED) {
                        mBluetoothService.disconnectDevices();
                        sendMessageDelayed(DEVICES_DISCONNECT_TIMEOUT,
                                           DEVICES_DISCONNECT_TIMEOUT_TIME);
                        break;
                    }
                    //$FALL-THROUGH$ all devices are already disconnected
                case ALL_DEVICES_DISCONNECTED:
                    removeMessages(DEVICES_DISCONNECT_TIMEOUT);
                    finishSwitchingOff();
                    log("USER_TURN_OFF disable scan");
                    mBluetoothService.setHciScanMode(BluetoothAdapter.SCAN_MODE_NONE);
                    break;
                case DEVICES_DISCONNECT_TIMEOUT:
                    finishSwitchingOff();
                    Log.e(TAG, "Devices fail to disconnect, reseting...");
                    transitionTo(mHotOff);
                    deferMessage(obtainMessage(TURN_COLD));
                    antWirelessCallback(false);
                    deferMessage(obtainMessage(ANT_WIRELESS_TURN_ON, mAntWirelessCallback));
                    break;
                case PER_PROCESS_TURN_ON:
                    log("PER_PROCESS_TURN_ON enable scan");
                    mBluetoothService.setHciScanMode(BluetoothAdapter.SCAN_MODE_CONNECTABLE);
                    antWirelessCallback(true);
                    transitionTo(mPerProcessState);
                    deferMessage(message);
                    break;
                case PER_PROCESS_TURN_OFF:
                    log("PER_PROCESS_TURN_OFF disable scan");
                    mBluetoothService.setHciScanMode(BluetoothAdapter.SCAN_MODE_NONE);
                    break;
                case AIRPLANE_MODE_ON:
                    // BlueZ wont let us turn off without PSCAN on, so if needed turn it on, and
                    // wait for the response, then switch connectable.
                    if (mBluetoothService.getHciScanMode() == BluetoothAdapter.SCAN_MODE_NONE) {
                        isTurningOff = true;
                        mBluetoothService.setHciScanMode(BluetoothAdapter.SCAN_MODE_CONNECTABLE);
                    } else {
                        mBluetoothService.switchConnectable(false);
                    }
                    sendMessageDelayed(TURN_OFF_TIMEOUT, TURN_OFF_TIMEOUT_TIME);
                    // we turn all the way to PowerOff with AIRPLANE_MODE_ON
                    deferMessage(obtainMessage(AIRPLANE_MODE_ON));
                    break;
                case USER_TURN_OFF:
                    Log.w(TAG, "PerProcessState received: " + message.what);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return retValue;
        }
    }

    private void finishSwitchingOff() {
        mBluetoothService.finishDisable();
        broadcastState(BluetoothAdapter.STATE_OFF);
        mBluetoothService.cleanupAfterFinishDisable();
    }

    private void shutoffBluetooth() {
        mBluetoothService.shutoffBluetooth();
        mEventLoop.stop();
        mBluetoothService.cleanNativeAfterShutoffBluetooth();
    }

    private void perProcessCallback(boolean on, IBluetoothStateChangeCallback c) {
        if (c == null) return;

        try {
            c.onBluetoothStateChange(on);
        } catch (RemoteException e) {}
    }

    private void antWirelessCallback(boolean on, IBluetoothStateChangeCallback c) {
        mAntWirelessActive = on;
        mAntWirelessCallback = c; // keep the most recent callback reference
        perProcessCallback(on, c);
    }

    private void antWirelessCallback(boolean on) {
        antWirelessCallback(on, mAntWirelessCallback);
    }

    private void allProcessesCallback(boolean on) {
        for (IBluetoothStateChangeCallback c:
             mBluetoothService.getApplicationStateChangeCallbacks()) {
            perProcessCallback(on, c);
        }
        if (!on) {
            mBluetoothService.clearApplicationStateChangeTracker();
        }
    }

    /**
     * Return the public BluetoothAdapter state
     */
    int getBluetoothAdapterState() {
        return mPublicState;
    }

    BluetoothEventLoop getBluetoothEventLoop() {
        return mEventLoop;
    }

    private void persistSwitchSetting(boolean setOn) {
        long origCallerIdentityToken = Binder.clearCallingIdentity();
        Settings.Secure.putInt(mContext.getContentResolver(),
                               Settings.Secure.BLUETOOTH_ON,
                               setOn ? 1 : 0);
        Binder.restoreCallingIdentity(origCallerIdentityToken);
    }

    private boolean getBluetoothPersistedSetting() {
        ContentResolver contentResolver = mContext.getContentResolver();
        return (Settings.Secure.getInt(contentResolver,
                                       Settings.Secure.BLUETOOTH_ON, 0) > 0);
    }

    private void broadcastState(int newState) {

        log("Bluetooth state " + mPublicState + " -> " + newState);
        if (mPublicState == newState) {
            return;
        }

        Intent intent = new Intent(BluetoothAdapter.ACTION_STATE_CHANGED);
        intent.putExtra(BluetoothAdapter.EXTRA_PREVIOUS_STATE, mPublicState);
        intent.putExtra(BluetoothAdapter.EXTRA_STATE, newState);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        mPublicState = newState;

        mContext.sendBroadcast(intent, BluetoothService.BLUETOOTH_PERM);
    }

    /**
     * bluetoothd has crashed and recovered, the adapter state machine has to
     * reset itself and try to return to previous state
     */
    private void recoverStateMachine(int what, Object obj) {
        Log.e(TAG, "Get unexpected power on event, reset with: " + what);
        transitionTo(mHotOff);
        deferMessage(obtainMessage(TURN_COLD));
        deferMessage(obtainMessage(what, obj));
    }

    private void dump(PrintWriter pw) {
        IState currentState = getCurrentState();
        if (currentState == mPowerOff) {
            pw.println("Bluetooth OFF - power down\n");
        } else if (currentState == mWarmUp) {
            pw.println("Bluetooth OFF - warm up\n");
        } else if (currentState == mHotOff) {
            pw.println("Bluetooth OFF - hot but off\n");
        } else if (currentState == mSwitching) {
            pw.println("Bluetooth Switching\n");
        } else if (currentState == mBluetoothOn) {
            pw.println("Bluetooth ON\n");
        } else {
            pw.println("ERROR: Bluetooth UNKNOWN STATE ");
        }
    }

    private static void log(String msg) {
        Log.d(TAG, msg);
    }
}
