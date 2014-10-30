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

import android.bluetooth.IBluetoothStateChangeCallback;
import android.bluetooth.IBluetoothVSCallback;

/**
 * System private API for sending vendor specific commands and receiving
 * vendor specific events from the bluetooth controller.
 * {@hide}
 */
interface IBluetoothVS {
    /** Registers a set of callbacks to use with the interface. */
    void registerVSCallback(in IBluetoothVSCallback callback);

    /** Unregisters a set of callbacks that were previously registered. */
    void unregisterVSCallback(in IBluetoothVSCallback callback);

    /** Send a VS command to the controller. */
    void sendVendorSpecificCommand(int opcode, in byte [] parameters);

    /** Set up a filter describing which VS events a particular callback is interested in. */
    void setVSEventFilter(in IBluetoothVSCallback callback,
        in byte [] mask, in byte [] value);
}
