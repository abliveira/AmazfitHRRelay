package com.abliveira.amazfithrr;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_PERMISSIONS = 1;
    private static final int REQUEST_ENABLE_BLUETOOTH = 2;

    private TextView txtStatus;
    private TextView txtBpm;

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String status = intent.getStringExtra(AmazfitRelayService.EXTRA_STATUS);
            int bpm = intent.getIntExtra(AmazfitRelayService.EXTRA_BPM, 0);

            if (status != null) {
                txtStatus.setText(status);
            }

            if (bpm > 0) {
                txtBpm.setText(String.valueOf(bpm));
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Button btnStart = findViewById(R.id.btnStart);
        Button btnStop = findViewById(R.id.btnStop);
        View batteryWarning = findViewById(R.id.batteryWarning);

        txtStatus = findViewById(R.id.txtStatus);
        txtBpm = findViewById(R.id.txtBpm);

        btnStart.setOnClickListener(v -> {
            if (checkAndRequestPermissions()) {
                startHrServiceIfBluetoothEnabled();
            }
        });

        btnStop.setOnClickListener(v -> stopHrService());

        batteryWarning.setOnClickListener(v -> requestIgnoreBatteryOptimizations());
    }

    @Override
    protected void onStart() {
        super.onStart();

        IntentFilter filter = new IntentFilter(AmazfitRelayService.ACTION_STATUS_UPDATE);

        ContextCompat.registerReceiver(
                this,
                statusReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
        );

        sendBroadcast(
                new Intent(AmazfitRelayService.ACTION_STATUS_REQUEST)
                        .setPackage(getPackageName())
        );
    }

    @Override
    protected void onStop() {
        super.onStop();
        unregisterReceiver(statusReceiver);
    }

    private boolean checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED ||
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {

                ActivityCompat.requestPermissions(this, new String[]{
                        Manifest.permission.BLUETOOTH_ADVERTISE,
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.POST_NOTIFICATIONS
                }, REQUEST_PERMISSIONS);
                return false;
            }
        } else {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_PERMISSIONS);
                return false;
            }
        }
        return true;
    }

    private void requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent intent = new Intent();
            String packageName = getPackageName();
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);

            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                intent.setAction(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + packageName));
                startActivity(intent);
            } else {
                Toast.makeText(this, "Battery optimization is already disabled for this app.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void startHrService() {
        Intent intent = new Intent(this, AmazfitRelayService.class);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }

        Toast.makeText(this, "Heart rate relay started.", Toast.LENGTH_SHORT).show();
    }

    private void startHrServiceIfBluetoothEnabled() {
        BluetoothManager bluetoothManager = getSystemService(BluetoothManager.class);
        BluetoothAdapter bluetoothAdapter = bluetoothManager != null
                ? bluetoothManager.getAdapter()
                : null;

        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth is not available on this device.", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!bluetoothAdapter.isEnabled()) {
            startActivityForResult(
                    new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE),
                    REQUEST_ENABLE_BLUETOOTH
            );
            return;
        }

        startHrService();
    }

    private void stopHrService() {
        Intent intent = new Intent(this, AmazfitRelayService.class);
        stopService(intent);

        Toast.makeText(this, "Heart rate relay stopped.", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_PERMISSIONS) {
            boolean allPermissionsGranted = grantResults.length > 0;

            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allPermissionsGranted = false;
                    break;
                }
            }

            if (allPermissionsGranted) {
                startHrServiceIfBluetoothEnabled();
            } else {
                Toast.makeText(this, "Bluetooth and notification permissions are required to start the heart rate relay.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_ENABLE_BLUETOOTH) {
            if (resultCode == RESULT_OK) {
                startHrServiceIfBluetoothEnabled();
            } else {
                Toast.makeText(this, "Bluetooth must be enabled to start the heart rate relay.", Toast.LENGTH_SHORT).show();
            }
        }
    }
}
