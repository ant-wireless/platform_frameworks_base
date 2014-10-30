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

/**
 * System private API for Bluetooth service vendor specific callbacks.
 * @hide
 */
interface IBluetoothVSCallback
{
    /**
     * Interface is now finished intialization and is ready to use.
     * Note that this may be called multiple times
     */
    void onInterfaceReady();

    /**
     * Some error occured and the VS interface is no longer usable.
     * This may happen withough {@link #onInterfaceReady()} ever being called.
     */
    void onInterfaceDown();

    /** A command complete was received for a previously sent VS command. */
    void vendorSpecificCommandCompleteReceived(int opcode, in byte [] parameters);

    /** A vendor specific event was received from the controller */
    void vendorSpecificEventReceived(in byte [] params);
}
