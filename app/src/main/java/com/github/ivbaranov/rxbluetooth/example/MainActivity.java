package com.github.ivbaranov.rxbluetooth.example;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Parcelable;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.text.TextUtils;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.github.ivbaranov.rxbluetooth.BluetoothConnection;
import com.github.ivbaranov.rxbluetooth.RxBluetooth;
import com.github.ivbaranov.rxbluetooth.events.AclEvent;
import com.github.ivbaranov.rxbluetooth.events.BondStateEvent;
import com.github.ivbaranov.rxbluetooth.events.ConnectionStateEvent;
import com.github.ivbaranov.rxbluetooth.events.ServiceEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.functions.Consumer;
import io.reactivex.schedulers.Schedulers;

public class MainActivity extends AppCompatActivity {
    private static final int REQUEST_PERMISSION_COARSE_LOCATION = 0;
    private static final int REQUEST_ENABLE_BT = 1;
    private static final String TAG = "MainActivity";

    private Button start;
    private Button stop;
    private ListView result;
    private Toolbar toolbar;

    private RxBluetooth rxBluetooth;
    private CompositeDisposable compositeDisposable = new CompositeDisposable();
    private List<BluetoothDevice> devices = new ArrayList<>();
    private Intent bluetoothServiceIntent;

    // Use 00001101-0000-1000-8000-00805F9B34FB for SPP service
    // (ex. Arduino) or use your own generated UUID.
    private UUID mUUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb");

    // Connection from bluetoothSocket
    private BluetoothConnection bluetoothConnection;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Service Instance
        bluetoothServiceIntent = new Intent(MainActivity.this, BluetoothService.class);

        // RxBluetooth Instance
        rxBluetooth = new RxBluetooth(this);

        // Toolbar
        toolbar = (Toolbar) findViewById(R.id.toolbar);
        toolbar.setTitle("RxBluetooth");
        toolbar.inflateMenu(R.menu.main_menu);
        toolbar.setOnMenuItemClickListener(new Toolbar.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                switch (item.getItemId()) {
                    case R.id.menu_enable_bt:
                        if (rxBluetooth.isBluetoothAvailable() && !rxBluetooth.isBluetoothEnabled()) {
                            Log.d(TAG, "Enabling Bluetooth");
                            rxBluetooth.enableBluetooth(MainActivity.this, REQUEST_ENABLE_BT);
                        }
                        return true;

                    case R.id.menu_service_start:
                        showToast("Starting service");
                        startService(bluetoothServiceIntent);
                        return true;

                    case R.id.menu_service_stop:
                        showToast("Stopping service");
                        stopService(bluetoothServiceIntent);
                        return true;
                }
                return false;
            }
        });

        // Devices ListView
        result = findViewById(R.id.result);
        result.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @SuppressLint("CheckResult")
            @Override
            public void onItemClick(AdapterView<?> parent, View view, final int position, long id) {
                doOnClickDeviceFound(devices.get(position));
            }
        });

        // Start Discovery
        start = findViewById(R.id.start);
        start.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doStart();
            }
        });

        // Stop Discovery
        stop = findViewById(R.id.stop);
        stop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                rxBluetooth.cancelDiscovery();
            }
        });

        // BT Availability
        if (!rxBluetooth.isBluetoothAvailable()) {
            Log.d(TAG, "Bluetooth is not supported");
        } else {
            Log.d(TAG, "Bluetooth supported");
            if (!rxBluetooth.isBluetoothEnabled()) {
                Log.d(TAG, "Enabling Bluetooth");
                rxBluetooth.enableBluetooth(this, REQUEST_ENABLE_BT);
            } else {
                doOnBTAvaliableAndEnabled();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (rxBluetooth != null) {
            // Make sure we're not doing discovery anymore
            Log.d(TAG, "cancelDiscovery");
            rxBluetooth.cancelDiscovery();
            bluetoothConnection.closeConnection();
        }
        Log.d(TAG, "compositeDisposable dispose");
        compositeDisposable.dispose();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String permissions[], @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSION_COARSE_LOCATION) {
            for (String permission : permissions) {
                if (android.Manifest.permission.ACCESS_COARSE_LOCATION.equals(permission)) {
                    // Start discovery if permission granted
                    Log.d(TAG, "startDiscovery");
                    rxBluetooth.startDiscovery();
                }
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_ENABLE_BT) {
            if (resultCode == RESULT_OK) {
                doOnBTAvaliableAndEnabled();
            } else {
                finish();
            }
        }
    }

    private void doOnClickDeviceFound(BluetoothDevice device) {
        Log.d(TAG, "onItemClick: " + device.getName() + " " + device.getAddress());

        if (rxBluetooth.isDiscovering())
            rxBluetooth.cancelDiscovery();

        if (device.getBondState() == BluetoothDevice.BOND_NONE) {
            device.createBond();
        } else {
            observeConnectAsClient(device, mUUID);
        }
    }

    private void doOnBTAvaliableAndEnabled() {
        Log.d(TAG, "Bluetooth Avaliable and Enabled");
        start.setBackgroundColor(getResources().getColor(R.color.colorActive, null));
        showBondedDevices();
        initEventObservers();
    }

    private void showBondedDevices() {
        Set<BluetoothDevice> bondedDevices = rxBluetooth.getBondedDevices();
        if (bondedDevices != null) {
            devices.addAll(bondedDevices);
            setAdapter(devices);
        }
    }

    private void doStart() {
        devices.clear();
        setAdapter(devices);

        if (ContextCompat.checkSelfPermission(MainActivity.this,
                android.Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(MainActivity.this,
                    new String[]{android.Manifest.permission.ACCESS_COARSE_LOCATION},
                    REQUEST_PERMISSION_COARSE_LOCATION);
        } else {
            showBondedDevices();
            rxBluetooth.startDiscovery();
        }
    }

    private void addDevice(BluetoothDevice device) {
        devices.add(device);
        setAdapter(devices);
    }

    private void setAdapter(List<BluetoothDevice> list) {
        result.setAdapter(new ArrayAdapter<BluetoothDevice>(this, android.R.layout.simple_list_item_2,
                android.R.id.text1, list) {
            @NonNull
            @Override
            public View getView(int position, View convertView, @NonNull ViewGroup parent) {
                View view = super.getView(position, convertView, parent);

                BluetoothDevice device = devices.get(position);

                String bondState = "Device Discovered";
                if (device.getBondState() == BluetoothDevice.BOND_BONDED) {
                    bondState = " (Device Bonded or Paired)";
                }
                String devName = String.format(Locale.getDefault(), "%s %s", device.getName(), bondState);

                String devAddress = device.getAddress();

                if (TextUtils.isEmpty(devName)) {
                    devName = "NO NAME";
                }
                TextView text1 = view.findViewById(android.R.id.text1);
                TextView text2 = view.findViewById(android.R.id.text2);

                text1.setText(devName);
                text2.setText(devAddress);
                return view;
            }
        });
    }

    private void showToast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private void initEventObservers() {
        observeDevices();
        observeDiscovery();
        observeBTState();
        observeScanMode();
        //observeProfile(BluetoothProfile.HEADSET);
        observeBond();
        observeConnectionState();
        //observeAclEvent();
    }

    private void observeDevices() {
        compositeDisposable.add(rxBluetooth.observeDevices()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribeOn(Schedulers.computation())
                .subscribe(new Consumer<BluetoothDevice>() {
                    @Override
                    public void accept(@NonNull BluetoothDevice bluetoothDevice) {
                        Log.d(TAG, "observeDevices device found: " + bluetoothDevice.getName() + " " + bluetoothDevice.getAddress());
                        addDevice(bluetoothDevice);
                    }
                }));
    }

    private void observeDiscovery() {
        compositeDisposable.add(rxBluetooth.observeDiscovery()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribeOn(Schedulers.computation())
                .subscribe(new Consumer<String>() {
                    @Override
                    public void accept(String action) {
                        switch (action) {
                            case BluetoothAdapter.ACTION_DISCOVERY_STARTED:
                                Log.d(TAG, "ACTION_DISCOVERY_STARTED");
                                start.setText(R.string.button_searching);
                                break;
                            case BluetoothAdapter.ACTION_DISCOVERY_FINISHED:
                                Log.d(TAG, "ACTION_DISCOVERY_FINISHED");
                                start.setText(R.string.button_restart);
                                break;
                        }

                    }
                }));
    }

    private void observeBTState() {
        compositeDisposable.add(rxBluetooth.observeBluetoothState()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribeOn(Schedulers.computation())
                .subscribe(new Consumer<Integer>() {
                    @Override
                    public void accept(Integer integer) {
                        switch (integer) {
                            case BluetoothAdapter.STATE_OFF:
                                start.setBackgroundColor(getResources().getColor(R.color.colorInactive, null));
                                devices.clear();
                                setAdapter(devices);
                                Log.d(TAG, "STATE_OFF");
                                break;
                            case BluetoothAdapter.STATE_TURNING_ON:
                                start.setBackgroundColor(getResources().getColor(R.color.colorInactive, null));
                                Log.d(TAG, "STATE_TURNING_ON");
                                break;
                            case BluetoothAdapter.STATE_ON:
                                start.setBackgroundColor(getResources().getColor(R.color.colorActive, null));
                                Log.d(TAG, "STATE_ON");
                                break;
                            case BluetoothAdapter.STATE_TURNING_OFF:
                                start.setBackgroundColor(getResources().getColor(R.color.colorInactive, null));
                                Log.d(TAG, "STATE_TURNING_OFF");
                                break;
                        }
                    }
                }));
    }

    private void observeScanMode() {
        compositeDisposable.add(rxBluetooth.observeScanMode()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribeOn(Schedulers.computation())
                .subscribe(new Consumer<Integer>() {
                    @Override
                    public void accept(Integer integer) {
                        switch (integer) {
                            case BluetoothAdapter.SCAN_MODE_NONE:
                                Log.d(TAG, "SCAN_MODE_NONE");
                                break;
                            case BluetoothAdapter.SCAN_MODE_CONNECTABLE:
                                Log.d(TAG, "SCAN_MODE_CONNECTABLE");
                                break;
                            case BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE:
                                Log.d(TAG, "SCAN_MODE_CONNECTABLE_DISCOVERABLE");
                                break;
                        }
                    }
                }));
    }

    private void observeProfile(int bluetoothProfile) {
        compositeDisposable.add(rxBluetooth.observeBluetoothProfile(bluetoothProfile)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribeOn(Schedulers.computation())
                .subscribe(new Consumer<ServiceEvent>() {
                    @Override
                    public void accept(ServiceEvent serviceEvent) {
                        switch (serviceEvent.getState()) {
                            case CONNECTED:
                                BluetoothProfile bluetoothProfile = serviceEvent.getBluetoothProfile();
                                List<BluetoothDevice> devices = bluetoothProfile.getConnectedDevices();
                                for (final BluetoothDevice device : devices) {
                                    Log.d(TAG, "observeBluetoothProfile CONNECTED: " + device.getName());
                                }
                                break;
                            case DISCONNECTED:
                                Log.d(TAG, "observeBluetoothProfile DISCONNECTED: " + serviceEvent.getBluetoothProfile());
                                break;
                        }
                    }
                }));
    }

    private void observeBond() {
        compositeDisposable.add(rxBluetooth.observeBondState()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribeOn(Schedulers.computation())
                .subscribe(new Consumer<BondStateEvent>() {
                    @Override
                    public void accept(BondStateEvent event) {
                        switch (event.getState()) {
                            case BluetoothDevice.BOND_NONE:
                                Log.d(TAG, "device unbonded");
                                break;
                            case BluetoothDevice.BOND_BONDING:
                                Log.d(TAG, "device bonding");
                                break;
                            case BluetoothDevice.BOND_BONDED:
                                Log.d(TAG, "device bonded");
                                break;
                        }
                    }
                }));
    }

    private void observeConnectionState() {
        compositeDisposable.add(rxBluetooth.observeConnectionState()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribeOn(Schedulers.computation())
                .subscribe(new Consumer<ConnectionStateEvent>() {
                    @Override
                    public void accept(ConnectionStateEvent event) {
                        switch (event.getState()) {
                            case BluetoothAdapter.STATE_DISCONNECTED:
                                Log.d(TAG, "disconnected: " + event.getBluetoothDevice().getName());
                                break;
                            case BluetoothAdapter.STATE_CONNECTING:
                                Log.d(TAG, "connecting: " + event.getBluetoothDevice().getName());
                                break;
                            case BluetoothAdapter.STATE_CONNECTED:
                                Log.d(TAG, "connected: " + event.getBluetoothDevice().getName());
                                break;
                            case BluetoothAdapter.STATE_DISCONNECTING:
                                Log.d(TAG, "disconnecting: " + event.getBluetoothDevice().getName());
                                break;
                        }
                    }
                }));
    }

    private void observeAclEvent() {
        compositeDisposable.add(rxBluetooth.observeAclEvent()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribeOn(Schedulers.computation())
                .subscribe(new Consumer<AclEvent>() {
                    @Override
                    public void accept(AclEvent aclEvent) {
                        switch (aclEvent.getAction()) {
                            case BluetoothDevice.ACTION_ACL_CONNECTED:
                                Log.d(TAG, "ACTION_ACL_CONNECTED: " + aclEvent.getBluetoothDevice().getName());
                                break;
                            case BluetoothDevice.ACTION_ACL_DISCONNECT_REQUESTED:
                                Log.d(TAG, "ACTION_ACL_DISCONNECT_REQUESTED: " + aclEvent.getBluetoothDevice().getName());
                                break;
                            case BluetoothDevice.ACTION_ACL_DISCONNECTED:
                                Log.d(TAG, "ACTION_ACL_DISCONNECTED: " + aclEvent.getBluetoothDevice().getName());
                                break;
                        }
                    }
                }));
    }

    private void observeFetchDeviceUuids(final BluetoothDevice device) {
        Log.d(TAG, "observeFetchDeviceUuids: " + device.getName());
        compositeDisposable.add(rxBluetooth.observeFetchDeviceUuids(device)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribeOn(Schedulers.computation())
                .subscribe(new Consumer<Parcelable[]>() {
                    @Override
                    public void accept(final Parcelable[] uuids) {
                        Log.d(TAG, "observeFetchDeviceUUIDs UUID[0]: " + uuids[0]);
                        mUUID = UUID.fromString(uuids[0].toString());
                        observeConnectAsServer("myserver", mUUID);
                    }
                }, new Consumer<Throwable>() {
                    @Override
                    public void accept(Throwable throwable) {
                        Log.d(TAG, "Error observeFetchDeviceUUIDs: " + throwable.getMessage());
                    }
                }));
    }

    private void observeConnectAsServer(String servername, final UUID uuid) {
        Log.d(TAG, "rxBluetooth.observeConnectAsServer");
        compositeDisposable.add(rxBluetooth.connectAsServer(servername, uuid)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribeOn(Schedulers.computation())
                .subscribe(new Consumer<BluetoothSocket>() {
                    @Override
                    public void accept(BluetoothSocket socket) {
                        Log.d(TAG, "observeConnectAsServer. Socket Remote Device Name: " + socket.getRemoteDevice().getName());
                    }
                }, new Consumer<Throwable>() {
                    @Override
                    public void accept(Throwable throwable) {
                        Log.d(TAG, "Error observeConnectAsServer: " + throwable.getMessage());
                    }
                }));
    }

    private void observeConnectAsClient(BluetoothDevice device, UUID uuid) {
        Log.d(TAG, "rxBluetooth.observeConnectAsClient");
        compositeDisposable.add(rxBluetooth.connectAsClient(device, uuid)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribeOn(Schedulers.computation())
                .subscribe(new Consumer<BluetoothSocket>() {
                    @Override
                    public void accept(BluetoothSocket socket) {
                        Log.d(TAG, "observeConnectAsClient. Socket Remote Device Name: " + socket.getRemoteDevice().getName());
                        createConnectionFromSocket(socket);
                    }
                }, new Consumer<Throwable>() {
                    @Override
                    public void accept(Throwable throwable) {
                        Log.d(TAG, "Error observeConnectAsClient: " + throwable.getMessage());
                    }
                }));
    }

    private void createConnectionFromSocket(BluetoothSocket socket) {
        try {
            Log.d(TAG, "Creating connection from socket");
            bluetoothConnection = new BluetoothConnection(socket);
            observeInputStream();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void observeInputStream() {
        //observeInputByteStream();
        observeInputStringStream();
    }

    private void observeInputByteStream() {
        compositeDisposable.add(bluetoothConnection.observeByteStream().observeOn(AndroidSchedulers.mainThread())
                .subscribeOn(Schedulers.io())
                .subscribe(new Consumer<Byte>() {
                    @Override
                    public void accept(Byte aByte) {
                        Log.d(TAG, "observeInputByteStream: " + aByte);
                    }
                }, new Consumer<Throwable>() {
                    @Override
                    public void accept(Throwable throwable) {
                        Log.d(TAG, "Error observeInputByteStream: " + throwable.getMessage());
                    }
                }));
    }

    private void observeInputStringStream() {
        compositeDisposable.add(bluetoothConnection.observeStringStream()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribeOn(Schedulers.io())
                .subscribe(new Consumer<String>() {
                    @Override
                    public void accept(String string) {
                        Log.d(TAG, "observeInputStringStream: " + string);
                    }
                }, new Consumer<Throwable>() {
                    @Override
                    public void accept(Throwable throwable) {
                        Log.d(TAG, "Error observeInputStringStream: " + throwable.getMessage());
                    }
                }));
    }

    private void sendTextToConnectedClient() {
        bluetoothConnection.send("text");
    }
}
