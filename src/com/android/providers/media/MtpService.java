/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.providers.media;

import android.app.KeyguardManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.mtp.MtpDatabase;
import android.mtp.MtpServer;
import android.mtp.MtpStorage;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.storage.StorageEventListener;
import android.os.storage.StorageManager;
import android.provider.Settings;
import android.util.Log;

import java.util.HashMap;

public class MtpService extends Service {
    private static final String TAG = "MtpService";

    private class SettingsObserver extends ContentObserver {
        private ContentResolver mResolver;
        SettingsObserver() {
            super(new Handler());
        }

        void observe(Context context) {
            mResolver = context.getContentResolver();
            mResolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.USE_PTP_INTERFACE), false, this);
            onChange(false);
        }

        @Override
        public void onChange(boolean selfChange) {
            mPtpMode = (Settings.System.getInt(mResolver,
                    Settings.System.USE_PTP_INTERFACE, 0) != 0);
        }
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (Intent.ACTION_USER_PRESENT.equals(action)) {
                synchronized (mBinder) {
                    // Unhide the storage units when the user has unlocked the lockscreen
                    if (mMtpDisabled) {
                        for (MtpStorage storage : mStorageMap.values()) {
                            addStorageLocked(storage);
                        }
                        mMtpDisabled = false;
                    }
                }
            }
        }
    };

    private final StorageEventListener mStorageEventListener = new StorageEventListener() {
        public void onStorageStateChanged(String path, String oldState, String newState) {
            synchronized (mBinder) {
                Log.d(TAG, "onStorageStateChanged " + path + " " + oldState + " -> " + newState);
                if (Environment.MEDIA_MOUNTED.equals(newState)) {
                    volumeMountedLocked(path);
                } else if (Environment.MEDIA_MOUNTED.equals(oldState)) {
                    MtpStorage storage = mStorageMap.remove(path);
                    if (storage != null) {
                        removeStorageLocked(storage);
                    }
                }
            }
        }
    };

    private MtpDatabase mDatabase;
    private MtpServer mServer;
    private SettingsObserver mSettingsObserver;
    private StorageManager mStorageManager;
    private boolean mPtpMode;
    private boolean mMtpDisabled; // true if MTP is disabled due to secure keyguard
    private final HashMap<String, MtpStorage> mStorageMap = new HashMap<String, MtpStorage>();
    private String[] mExternalStoragePaths;
    private String[] mExternalStorageDescriptions;

    @Override
    public void onCreate() {
        mSettingsObserver = new SettingsObserver();
        mSettingsObserver.observe(this);

        mExternalStoragePaths = getResources().getStringArray(
                com.android.internal.R.array.config_externalStoragePaths);
        mExternalStorageDescriptions = getResources().getStringArray(
                com.android.internal.R.array.config_externalStorageDescriptions);

        // lock MTP if the keyguard is locked and secure
        KeyguardManager keyguardManager =
                (KeyguardManager)getSystemService(Context.KEYGUARD_SERVICE);
        mMtpDisabled = keyguardManager.isKeyguardLocked() && keyguardManager.isKeyguardSecure();
        registerReceiver(mReceiver, new IntentFilter(Intent.ACTION_USER_PRESENT));

        mStorageManager = (StorageManager)getSystemService(Context.STORAGE_SERVICE);
        synchronized (mBinder) {
            mStorageManager.registerListener(mStorageEventListener);
            String[] volumes = mStorageManager.getVolumeList();
            for (int i = 0; i < volumes.length; i++) {
                String path = volumes[i];
                String state = mStorageManager.getVolumeState(path);
                if (Environment.MEDIA_MOUNTED.equals(state)) {
                   volumeMountedLocked(path);
                }
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        synchronized (mBinder) {
            if (mServer == null) {
                Log.d(TAG, "starting MTP server");
                mDatabase = new MtpDatabase(this, MediaProvider.EXTERNAL_VOLUME,
                        mExternalStoragePaths[0]);
                mServer = new MtpServer(mDatabase);
                mServer.setPtpMode(mPtpMode);
                if (!mMtpDisabled) {
                    for (MtpStorage storage : mStorageMap.values()) {
                        addStorageLocked(storage);
                    }
                }
                mServer.start();
            }
        }

        return START_STICKY;
    }

    @Override
    public void onDestroy()
    {
        unregisterReceiver(mReceiver);
        mStorageManager.unregisterListener(mStorageEventListener);
        synchronized (mBinder) {
            if (mServer != null) {
                Log.d(TAG, "stopping MTP server");
                mServer.stop();
                mServer = null;
                mDatabase = null;
            }
        }
    }

    private final IMtpService.Stub mBinder =
            new IMtpService.Stub() {
        public void sendObjectAdded(int objectHandle) {
            synchronized (mBinder) {
                if (mServer != null) {
                    mServer.sendObjectAdded(objectHandle);
                }
            }
        }

        public void sendObjectRemoved(int objectHandle) {
            synchronized (mBinder) {
                if (mServer != null) {
                    mServer.sendObjectRemoved(objectHandle);
                }
            }
        }
    };

    @Override
    public IBinder onBind(Intent intent)
    {
        return mBinder;
    }

    private void volumeMountedLocked(String path) {
        for (int i = 0; i < mExternalStoragePaths.length; i++) {
            if (mExternalStoragePaths[i].equals(path)) {
                int storageId = MtpStorage.getStorageId(i);

                // reserve space setting only applies to internal storage
                long reserveSpace;
                if (i == 0) {
                    reserveSpace = getResources().getInteger(
                        com.android.internal.R.integer.config_mtpReserveSpaceMegabytes)
                        * 1024 * 1024;
                } else {
                    reserveSpace = 0;
                }

                MtpStorage storage = new MtpStorage(storageId, path,
                        mExternalStorageDescriptions[i], reserveSpace);
                mStorageMap.put(path, storage);
                if (!mMtpDisabled) {
                    addStorageLocked(storage);
                }
                break;
            }
        }
    }

    private void addStorageLocked(MtpStorage storage) {
        Log.d(TAG, "addStorageLocked " + storage.getStorageId() + " " + storage.getPath());
        if (mDatabase != null) {
            mDatabase.addStorage(storage);
        }
        if (mServer != null) {
            mServer.addStorage(storage);
        }
    }

    private void removeStorageLocked(MtpStorage storage) {
        Log.d(TAG, "removeStorageLocked " + storage.getStorageId() + " " + storage.getPath());
        if (mDatabase != null) {
            mDatabase.removeStorage(storage);
        }
        if (mServer != null) {
            mServer.removeStorage(storage);
        }
    }
}

