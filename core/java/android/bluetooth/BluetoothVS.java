/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.bluetooth;

import android.bluetooth.IBluetoothVS;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;

/**
 * System private API used to send and receive vendor specific HCI commands and events
 * from the bluetooth controller.
 * {@hide}
 */
public class BluetoothVS {

    private static final String TAG = "BluetoothVS";

    public interface BluetoothVSCallbacks {
        /** Interface has completed initialization and is ready to use. */
        void onInterfaceReady();

        /** Some error occured and this VS interface instance is no longer usable. */
        void onInterfaceDown();

        /**
         * A command complete was received for a previously send VS command.
         *
         * @param commandOpcode opcode of the command being responded to.
         * @param returnParams The parameters that were contained in the
         *                     command complete response.
         */
        void onCommandCompleteReceived(short commandOpcode, byte[] returnParams);

        /**
         * A VS event that matches the current event filter was received.
         * @see BluetoothVS#setVendorSpecificEventFilter
         *
         * @param params The parameters contained in the received event.
         */
        void onEventReceived(byte[] params);
    }

    private final ServiceConnection mConnection = new ServiceConnection() {

        @Override
        public void onServiceDisconnected(ComponentName name) {
            synchronized(mConnection) {
                mService = null;
            }
            release(false);
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            synchronized(mConnection) {
                mService = IBluetoothVS.Stub.asInterface(service);
                try {
                    mService.registerVSCallback(mVSCallbackStub);
                } catch (RemoteException e) {
                    Log.e(TAG,"",e);
                    release(false);
                }
            }
        }
    };

    private final IBluetoothVSCallback mVSCallbackStub = new IBluetoothVSCallback.Stub() {
        @Override
        public void vendorSpecificCommandCompleteReceived(int opcode, byte[] parameters) {
            synchronized (mConnection) {
                if (!mActive || mReleased) {
                    return;
                }

                mCallbacks.onCommandCompleteReceived((short)opcode, parameters);
            }
        }

        @Override
        public void vendorSpecificEventReceived(byte[] params) {
            synchronized (mConnection) {
                // Need to deal with the fact that multiple onInterfaceReady calls may be received.
                if (!mActive || mReleased) {
                    return;
                }

                mCallbacks.onEventReceived(params);
            }
        }

        @Override
        public void onInterfaceReady() {
            synchronized (mConnection) {
                if (mActive || mReleased) {
                    return;
                }

                mActive = true;
                mCallbacks.onInterfaceReady();
            }
        }

        @Override
        public void onInterfaceDown() {
            release(false);
        }
    };

    private final BluetoothVSCallbacks mCallbacks;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Context mContext;
    private boolean mActive = false;
    private boolean mReleased = false;
    private IBluetoothVS mService;

    /**
     * Instantiates a new instance of the vendor specific command interface.
     *
     * @param context The context used for binding to the bluetooth service.
     * @param callbacks The callbacks for this interface instance. The client
     *                  should wait for a call to
     *                  {@link BluetoothVSCallbacks#onInterfaceReady} before
     *                  calling any interface methods.
     */
    public BluetoothVS(Context context, BluetoothVSCallbacks callbacks) {
        mCallbacks = callbacks;
        mContext = context;
        Intent intent = new Intent(IBluetoothVS.class.getName());
        if(!context.bindService(intent, mConnection, Context.BIND_AUTO_CREATE))
        {
            release(false);
        }
    }

    /**
     * Sends a vendor specific hci command to the bluetooth controller
     *
     * @param opcode The opcode to use for the param.
     * @param parameters The paraemeter bytes to send in the command.
     */
    public void sendVendorSpecificCommand(short opcode, byte [] parameters) {
        synchronized (mConnection) {
            if (mService == null) return;
            try {
                mService.sendVendorSpecificCommand(opcode, parameters);
            } catch (RemoteException e) {
                Log.e(TAG, "", e);
                release(false);
            }
        }
    }

    /**
     * Set an event filter describing which vendor specific event to
     * listen for.
     *
     * If there is no filter set, no event will be received. Any events
     * whose parameter length is less then the mask and value length will
     * also be ignored.
     *
     * @param mask a bit mask defining which bits in an event will be
     *             checked by the filter.
     * @param value Comparison value for the filter. The bits in an event
     *              message selected by the bit musk must match the same
     *              bits in this value array for it to be accepted.
     */
    public void setVendorSpecificEventFilter(byte[] mask, byte[] value) {
        synchronized (mConnection) {
            if (mService == null) return;
            try {
                mService.setVSEventFilter(mVSCallbackStub, mask, value);
            } catch (RemoteException e) {
                Log.e(TAG, "", e);
                release(false);
            }
        }
    }

    /**
     * Clear the event filter being used.
     *
     * No more events will be received until a new filter is set using
     * {@link #setVendorSpecificEventFilter}
     */
    public void clearVendorSpecificEventFilter() {
        synchronized (mConnection) {
            if (mService == null) return;
            try {
                mService.setVSEventFilter(mVSCallbackStub, null, null);
            } catch (RemoteException e) {
                Log.e(TAG, "", e);
                release (false);
            }
        }
    }

    /**
     * Used to release the binding to the Bluetooth service.
     *
     * @param suppressEvent Indicates that the onInterfaceDown event should
     *                      not be sent to the callback.
     */
    private void release(boolean suppressEvent) {
        synchronized(mConnection) {
            if(mReleased) return;
            mReleased = true;

            if(mService != null) {
                try {
                    mService.unregisterVSCallback(mVSCallbackStub);
                } catch (RemoteException e) {Log.e(TAG,"",e);}
            }

            mContext.unbindService(mConnection);
            if(!suppressEvent)
            {
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        mCallbacks.onInterfaceDown();
                    }
                });
            }
        }
    }

    /**
     * Indicate that the client is finished using the vendor specific
     * command interface.
     *
     * A new instance of the BluetoothVS class should be created to send
     * future commands.
     */
    public void release() {
        release(true);
    }
}
