package com.abliveira.heartbt;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

public class BleHeartRateService extends Service {
    private static final String TAG = "BleHrService";
    private static final String CHANNEL_ID = "ForegroundServiceChannel";

    public static final String ACTION_STATUS_UPDATE = "com.abliveira.heartbt.STATUS_UPDATE";
    public static final String ACTION_STATUS_REQUEST = "com.abliveira.heartbt.STATUS_REQUEST";
    public static final String EXTRA_STATUS = "status";
    public static final String EXTRA_BPM = "bpm";

    private static final String AMAZFIT_MAC = BuildConfig.AMAZFIT_MAC;
    private static final String AUTH_KEY_HEX = BuildConfig.AUTH_KEY_HEX;

    private static final UUID AMAZFIT_AUTH_SERVICE = UUID.fromString("0000fee1-0000-1000-8000-00805f9b34fb");
    private static final UUID AMAZFIT_AUTH_CHAR = UUID.fromString("00000009-0000-3512-2118-0009af100700");
    private static final UUID AMAZFIT_HR_CHAR = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb");

    private static final UUID REAL_HR_SERVICE_UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb");
    private static final UUID REAL_HR_CHAR_UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb");
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private BluetoothManager bluetoothManager;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeAdvertiser advertiser;
    private BluetoothGattServer gattServer;
    private BluetoothGattCharacteristic virtualHrCharacteristic;
    private BluetoothDevice connectedClientDevice = null;

    private BluetoothGatt amazfitGatt;
    private int currentLiveBpm = 0;
    private long lastHrTimestamp = 0;
    private String currentStatus = "Connecting";
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final BroadcastReceiver statusRequestReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            sendStatusUpdate();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();

        if (BuildConfig.AMAZFIT_MAC.isEmpty() || BuildConfig.AUTH_KEY_HEX.isEmpty()) {
            Log.e(TAG, "SERVICE: Watch configuration is missing. Check the local.properties file.");
            stopSelf();
            return;
        }

        IntentFilter statusRequestFilter = new IntentFilter(ACTION_STATUS_REQUEST);
        ContextCompat.registerReceiver(
                this,
                statusRequestReceiver,
                statusRequestFilter,
                ContextCompat.RECEIVER_NOT_EXPORTED
        );

        initializeBluetooth();
        setupVirtualGattServer();
        startAdvertising();
        setCurrentStatus("Connecting");
        connectToAmazfit();
        startStatusMonitor();
    }

    private void startStatusMonitor() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                String watchStatus = (amazfitGatt != null) ? "CONNECTED" : "DISCONNECTED";
                String clientStatus = (connectedClientDevice != null) ? "CONNECTED" : "WAITING";

                // Re-enable HR notifications when the watch connection remains active but data stops arriving.
                if (amazfitGatt != null && (System.currentTimeMillis() - lastHrTimestamp > 15000)) {
                    Log.w(TAG, "HR: No heart rate data received for 15 seconds. Re-enabling watch notifications.");
                    enableLiveHeartRateNotifications(amazfitGatt);
                    lastHrTimestamp = System.currentTimeMillis();
                }

                Log.i(TAG, "STATUS: BPM=" + currentLiveBpm + " | Watch=" + watchStatus + " | Client=" + clientStatus);
                sendStatusUpdate();
                handler.postDelayed(this, 3000);
            }
        }, 3000);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createNotificationChannel();

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Heart Rate Relay Active")
                .setContentText("Relaying heart rate data between BLE devices")
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .build();

        startForeground(1, notification);
        return START_STICKY;
    }

    private void initializeBluetooth() {
        bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);

        if (bluetoothManager != null) {
            bluetoothAdapter = bluetoothManager.getAdapter();

            if (bluetoothAdapter != null) {
                advertiser = bluetoothAdapter.getBluetoothLeAdvertiser();
            }
        }
    }

    private void connectToAmazfit() {
        if (bluetoothAdapter == null) {
            Log.e(TAG, "WATCH: Bluetooth adapter is unavailable. Connection cannot be started.");
            return;
        }

        BluetoothDevice amazfitDevice = bluetoothAdapter.getRemoteDevice(AMAZFIT_MAC);

        setCurrentStatus("Connecting");

        Log.i(TAG, "WATCH: Connecting to configured watch at " + AMAZFIT_MAC + ".");

        try {
            amazfitGatt = amazfitDevice.connectGatt(
                    this,
                    false,
                    amazfitGattCallback,
                    BluetoothDevice.TRANSPORT_LE
            );
        } catch (SecurityException e) {
            Log.e(TAG, "WATCH: Connection failed due to missing Bluetooth permission: " + e.getMessage());
        }
    }

    private final BluetoothGattCallback amazfitGattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            try {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    setCurrentStatus("Connected");

                    Log.i(TAG, "WATCH: Connected. Requesting high connection priority.");
                    gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);

                    Log.i(TAG, "WATCH: Starting GATT service discovery.");
                    gatt.discoverServices();

                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.w(TAG, "WATCH: Disconnected. Reconnection attempt scheduled in 5 seconds.");

                    amazfitGatt = null;
                    currentLiveBpm = 0;

                    setCurrentStatus("Connecting");

                    handler.postDelayed(() -> connectToAmazfit(), 5000);
                }
            } catch (SecurityException e) {
                Log.e(TAG, "WATCH: Bluetooth operation failed due to missing permission: " + e.getMessage());
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "WATCH: GATT services discovered. Starting authentication.");
                activateAmazfitAuthNotification(gatt);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            if (AMAZFIT_AUTH_CHAR.equals(characteristic.getUuid())) {
                byte[] data = characteristic.getValue();

                if (data.length >= 3 && data[0] == 0x10 && data[1] == 0x02 && data[2] == 0x01) {
                    Log.i(TAG, "AUTH: Challenge received. Generating encrypted response.");

                    byte[] challenge = new byte[16];
                    System.arraycopy(data, 3, challenge, 0, 16);

                    sendEncryptedChallenge(gatt, challenge);

                } else if (data.length >= 3 && data[0] == 0x10 && data[2] == 0x01) {
                    Log.i(TAG, "AUTH: Authentication completed. Enabling heart rate notifications.");
                    enableLiveHeartRateNotifications(gatt);
                }
            } else if (AMAZFIT_HR_CHAR.equals(characteristic.getUuid())) {
                byte[] data = characteristic.getValue();

                int flags = data[0];
                int bpm = (flags & 0x01) == 0
                        ? (data[1] & 0xFF)
                        : (((data[2] & 0xFF) << 8) | (data[1] & 0xFF));

                if (bpm > 0) {
                    currentLiveBpm = bpm;
                    lastHrTimestamp = System.currentTimeMillis();

                    setCurrentStatus("Relaying");

                    Log.i(TAG, "HR: Received " + currentLiveBpm + " BPM from watch.");

                    forwardBpmToClient(currentLiveBpm);
                    sendStatusUpdate();
                } else {
                    Log.w(TAG, "HR: Watch reported 0 BPM. Value ignored.");
                }
            }
        }
    };

    private void activateAmazfitAuthNotification(BluetoothGatt gatt) {
        try {
            BluetoothGattService service = gatt.getService(AMAZFIT_AUTH_SERVICE);

            if (service == null) {
                Log.e(TAG, "AUTH: Authentication service FEE1 was not found on the watch.");
                return;
            }

            BluetoothGattCharacteristic authChar = service.getCharacteristic(AMAZFIT_AUTH_CHAR);

            gatt.setCharacteristicNotification(authChar, true);

            BluetoothGattDescriptor descriptor = authChar.getDescriptor(CCCD_UUID);
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            gatt.writeDescriptor(descriptor);

            // The watch requires a short delay between enabling authentication notifications and sending the key.
            handler.postDelayed(() -> {
                try {
                    Log.i(TAG, "AUTH: Sending initial authentication key.");

                    byte[] keyBytes = hexStringToByteArray(AUTH_KEY_HEX);
                    byte[] payload = new byte[2 + keyBytes.length];

                    payload[0] = 0x01;
                    payload[1] = 0x00;

                    System.arraycopy(keyBytes, 0, payload, 2, keyBytes.length);

                    authChar.setValue(payload);
                    gatt.writeCharacteristic(authChar);

                } catch (Exception e) {
                    Log.e(TAG, "AUTH: Failed to send initial authentication key: " + e.getMessage());
                }
            }, 1000);

        } catch (SecurityException e) {
            Log.e(TAG, "AUTH: Bluetooth operation failed due to missing permission: " + e.getMessage());
        }
    }

    private void sendEncryptedChallenge(BluetoothGatt gatt, byte[] challenge) {
        try {
            byte[] keyBytes = hexStringToByteArray(AUTH_KEY_HEX);

            SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");
            Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");

            cipher.init(Cipher.ENCRYPT_MODE, keySpec);

            byte[] encrypted = cipher.doFinal(challenge);
            byte[] payload = new byte[2 + encrypted.length];

            payload[0] = 0x03;
            payload[1] = 0x00;

            System.arraycopy(encrypted, 0, payload, 2, encrypted.length);

            BluetoothGattService service = gatt.getService(AMAZFIT_AUTH_SERVICE);
            BluetoothGattCharacteristic authChar = service.getCharacteristic(AMAZFIT_AUTH_CHAR);

            authChar.setValue(payload);
            gatt.writeCharacteristic(authChar);

            Log.i(TAG, "AUTH: Encrypted challenge response sent.");

        } catch (Exception e) {
            Log.e(TAG, "AUTH: Failed to process or send encrypted challenge response: " + e.getMessage());
        }
    }

    private void enableLiveHeartRateNotifications(BluetoothGatt gatt) {
        try {
            BluetoothGattService service = gatt.getService(REAL_HR_SERVICE_UUID);

            if (service == null) {
                Log.e(TAG, "HR: Heart Rate Service 180D was not found on the watch.");
                return;
            }

            BluetoothGattCharacteristic hrChar = service.getCharacteristic(AMAZFIT_HR_CHAR);

            gatt.setCharacteristicNotification(hrChar, true);

            BluetoothGattDescriptor descriptor = hrChar.getDescriptor(CCCD_UUID);
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            gatt.writeDescriptor(descriptor);

            Log.i(TAG, "HR: Watch heart rate notifications enabled.");

            lastHrTimestamp = System.currentTimeMillis();

        } catch (SecurityException e) {
            Log.e(TAG, "HR: Failed to enable watch notifications due to missing permission: " + e.getMessage());
        }
    }

    private void setupVirtualGattServer() {
        try {
            gattServer = bluetoothManager.openGattServer(this, gattServerCallback);

            BluetoothGattService hrService = new BluetoothGattService(
                    REAL_HR_SERVICE_UUID,
                    BluetoothGattService.SERVICE_TYPE_PRIMARY
            );

            virtualHrCharacteristic = new BluetoothGattCharacteristic(
                    REAL_HR_CHAR_UUID,
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                    BluetoothGattCharacteristic.PERMISSION_READ
            );

            BluetoothGattDescriptor descriptor = new BluetoothGattDescriptor(
                    CCCD_UUID,
                    BluetoothGattDescriptor.PERMISSION_WRITE
            );

            virtualHrCharacteristic.addDescriptor(descriptor);
            hrService.addCharacteristic(virtualHrCharacteristic);
            gattServer.addService(hrService);

            Log.i(TAG, "GATT SERVER: Virtual heart rate service registration requested.");

        } catch (SecurityException e) {
            Log.e(TAG, "GATT SERVER: Setup failed due to missing Bluetooth permission: " + e.getMessage());
        }
    }

    private void startAdvertising() {
        if (advertiser == null) {
            Log.e(TAG, "ADVERTISING: BLE advertiser is unavailable.");
            return;
        }

        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(true)
                .setTimeout(0)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .build();

        AdvertiseData data = new AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .addServiceUuid(new ParcelUuid(REAL_HR_SERVICE_UUID))
                .build();

        try {
            advertiser.startAdvertising(settings, data, advertiseCallback);
        } catch (SecurityException e) {
            Log.e(TAG, "ADVERTISING: Failed to start due to missing Bluetooth permission: " + e.getMessage());
        }
    }

    private void forwardBpmToClient(int bpm) {
        if (connectedClientDevice != null && gattServer != null && virtualHrCharacteristic != null) {
            byte[] payload = new byte[]{0x00, (byte) bpm};

            virtualHrCharacteristic.setValue(payload);

            try {
                gattServer.notifyCharacteristicChanged(
                        connectedClientDevice,
                        virtualHrCharacteristic,
                        false
                );

                Log.d(TAG, "RELAY: Sent " + bpm + " BPM to connected client.");

            } catch (SecurityException e) {
                Log.e(TAG, "RELAY: Failed to send heart rate data due to missing permission: " + e.getMessage());
            }
        }
    }

    private final BluetoothGattServerCallback gattServerCallback = new BluetoothGattServerCallback() {
        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                connectedClientDevice = device;

                Log.i(TAG, "CLIENT: Connected to virtual heart rate service.");
                sendStatusUpdate();

            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                connectedClientDevice = null;

                Log.w(TAG, "CLIENT: Disconnected from virtual heart rate service.");
                sendStatusUpdate();
            }
        }

        @Override
        public void onDescriptorWriteRequest(
                BluetoothDevice device,
                int requestId,
                BluetoothGattDescriptor descriptor,
                boolean preparedWrite,
                boolean responseNeeded,
                int offset,
                byte[] value
        ) {
            if (CCCD_UUID.equals(descriptor.getUuid())) {
                try {
                    gattServer.sendResponse(
                            device,
                            requestId,
                            BluetoothGatt.GATT_SUCCESS,
                            0,
                            null
                    );

                    Log.i(TAG, "CLIENT: Heart rate notification configuration accepted.");

                } catch (SecurityException e) {
                    Log.e(TAG, "CLIENT: Failed to acknowledge notification configuration: " + e.getMessage());
                }
            }
        }
    };

    private final AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            Log.i(TAG, "ADVERTISING: Virtual heart rate service is now discoverable.");
        }
    };

    private void setCurrentStatus(String status) {
        currentStatus = status;
        sendStatusUpdate();
    }

    private void sendStatusUpdate() {
        Intent intent = new Intent(ACTION_STATUS_UPDATE);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_STATUS, currentStatus);
        intent.putExtra(EXTRA_BPM, currentLiveBpm);
        sendBroadcast(intent);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "BLE Service",
                    NotificationManager.IMPORTANCE_LOW
            );

            NotificationManager manager = getSystemService(NotificationManager.class);

            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }

    private byte[] hexStringToByteArray(String s) {
        s = s.replace("0x", "").replace(" ", "");

        int len = s.length();
        byte[] data = new byte[len / 2];

        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) (
                    (Character.digit(s.charAt(i), 16) << 4)
                            + Character.digit(s.charAt(i + 1), 16)
            );
        }

        return data;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        handler.removeCallbacksAndMessages(null);

        try {
            unregisterReceiver(statusRequestReceiver);
        } catch (IllegalArgumentException ignored) {
        }

        try {
            if (amazfitGatt != null) {
                amazfitGatt.close();
            }

            if (advertiser != null) {
                advertiser.stopAdvertising(advertiseCallback);
            }

            if (gattServer != null) {
                gattServer.close();
            }

            Log.i(TAG, "SERVICE: Heart rate relay stopped and BLE resources released.");

        } catch (SecurityException e) {
            Log.e(TAG, "SERVICE: Failed to release one or more BLE resources: " + e.getMessage());
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}