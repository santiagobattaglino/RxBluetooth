package com.github.ivbaranov.rxbluetooth.example;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
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
import com.github.ivbaranov.rxbluetooth.predicates.BtPredicate;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import io.reactivex.Single;
import io.reactivex.SingleEmitter;
import io.reactivex.SingleOnSubscribe;
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
    // UUID uuid = UUID.fromString("0000110a-0000-1000-8000-00805f9b34fb");
    private UUID mUUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    // Connection from bluetoothSocket
    private BluetoothConnection bluetoothConnection;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        start = (Button) findViewById(R.id.start);
        stop = (Button) findViewById(R.id.stop);
        result = (ListView) findViewById(R.id.result);

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

        bluetoothServiceIntent = new Intent(MainActivity.this, BluetoothService.class);

        rxBluetooth = new RxBluetooth(this);

        result.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @SuppressLint("CheckResult")
            @Override
            public void onItemClick(AdapterView<?> parent, View view, final int position, long id) {

                compositeDisposable.add(rxBluetooth.observeFetchDeviceUuids(devices.get(position))
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribeOn(Schedulers.computation())
                        .subscribe(new Consumer<Parcelable[]>() {
                            @Override
                            public void accept(final Parcelable[] uuids) {
                                Log.d(TAG, "observeFetchDeviceUUIDs UUID[0]: " + uuids[0]);
                                //connectAsServer("servername", uuids[0].toString());
                                //connectAsClient(devices.get(position), uuids[0].toString());

                                connectAsServerInsecure("servername", UUID.fromString(uuids[0].toString()))
                                        .observeOn(AndroidSchedulers.mainThread())
                                        .subscribeOn(Schedulers.computation())
                                        .subscribe(new Consumer<BluetoothSocket>() {
                                            @Override
                                            public void accept(BluetoothSocket socket) {
                                                Log.d(TAG, "connectAsServer. Socket Remote Device Name: " + socket.getRemoteDevice().getName());
                                                connectAsClient(socket.getRemoteDevice(), uuids[0].toString());
                                            }
                                        }, new Consumer<Throwable>() {
                                            @Override
                                            public void accept(Throwable throwable) {
                                                Log.d(TAG, "Error connectAsServer: " + throwable.getMessage());
                                            }
                                        });

                            }
                        }, new Consumer<Throwable>() {
                            @Override
                            public void accept(Throwable throwable) {
                                Log.d(TAG, "Error observeFetchDeviceUUIDs: " + throwable.getMessage());
                            }
                        }));

            }
        });

        if (!rxBluetooth.isBluetoothAvailable()) {
            // handle the lack of bluetooth support
            Log.d(TAG, "Bluetooth is not supported!");
        } else {
            initEventListeners();
            // check if bluetooth is currently enabled and ready for use
            if (!rxBluetooth.isBluetoothEnabled()) {
                // to enable bluetooth via startActivityForResult()
                Log.d(TAG, "Enabling Bluetooth");
                rxBluetooth.enableBluetooth(this, REQUEST_ENABLE_BT);
            } else {
                // you are ready
                Log.d(TAG, "Bluetooth Enabled!");
                start.setBackgroundColor(getResources().getColor(R.color.colorActive, null));
            }
        }
    }

    private void connectAsServer(final String uuid) {
        compositeDisposable.add(rxBluetooth.connectAsServer("servername", UUID.fromString(uuid))
                .observeOn(AndroidSchedulers.mainThread())
                .subscribeOn(Schedulers.computation())
                .subscribe(new Consumer<BluetoothSocket>() {
                    @Override
                    public void accept(BluetoothSocket socket) {
                        Log.d(TAG, "connectAsServer. Socket Remote Device Name: " + socket.getRemoteDevice().getName());
                        connectAsClient(socket.getRemoteDevice(), uuid);
                    }
                }, new Consumer<Throwable>() {
                    @Override
                    public void accept(Throwable throwable) {
                        Log.d(TAG, "Error connectAsServer: " + throwable.getMessage());
                    }
                }));
    }

    public Single<BluetoothSocket> connectAsServerInsecure(final String name, final UUID uuid) {
        return Single.create(new SingleOnSubscribe<BluetoothSocket>() {
            @Override
            public void subscribe(@io.reactivex.annotations.NonNull SingleEmitter<BluetoothSocket> emitter) {
                try {
                    try (BluetoothServerSocket bluetoothServerSocket = BluetoothAdapter.getDefaultAdapter().listenUsingInsecureRfcommWithServiceRecord(name, uuid)) {
                        emitter.onSuccess(bluetoothServerSocket.accept());
                    }
                } catch (IOException e) {
                    emitter.onError(e);
                }
            }
        });
    }

    private void connectAsClient(BluetoothDevice device, String uuid) {
        compositeDisposable.add(rxBluetooth.connectAsClient(device, UUID.fromString(uuid))
                .observeOn(AndroidSchedulers.mainThread())
                .subscribeOn(Schedulers.computation())
                .subscribe(new Consumer<BluetoothSocket>() {
                    @Override
                    public void accept(BluetoothSocket socket) {
                        Log.d(TAG, "connectAsClient. Socket Remote Device Name: " + socket.getRemoteDevice().getName());

                        // Create connection
                        /*bluetoothConnection = new BluetoothConnection(socket);

                        // Observe String Stream Read
                        bluetoothConnection.observeStringStream()
                                .observeOn(AndroidSchedulers.mainThread())
                                .subscribeOn(Schedulers.io())
                                .subscribe(new Consumer<String>() {
                                    @Override
                                    public void accept(String string) throws Exception {
                                        Log.d(TAG, "observeStringStream: " + string);
                                    }
                                }, new Consumer<Throwable>() {
                                    @Override
                                    public void accept(Throwable throwable) throws Exception {
                                        Log.d(TAG, "Error observeStringStream: " + throwable.getMessage());
                                    }
                                });*/
                    }
                }, new Consumer<Throwable>() {
                    @Override
                    public void accept(Throwable throwable) {
                        Log.d(TAG, "Error connectAsClient: " + throwable.getMessage());
                    }
                }));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (rxBluetooth != null) {
            // Make sure we're not doing discovery anymore
            Log.d(TAG, "cancelDiscovery");
            rxBluetooth.cancelDiscovery();
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

    private void initEventListeners() {

        // Devices
        compositeDisposable.add(rxBluetooth.observeDevices()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribeOn(Schedulers.computation())
                .subscribe(new Consumer<BluetoothDevice>() {
                    @Override
                    public void accept(@NonNull BluetoothDevice bluetoothDevice) {
                        Log.d(TAG, "observeDevices device found: " + bluetoothDevice.getName());
                        addDevice(bluetoothDevice);
                    }
                }));

        // Discovery
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

        // BT State
        compositeDisposable.add(rxBluetooth.observeBluetoothState()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribeOn(Schedulers.computation())
                .subscribe(new Consumer<Integer>() {
                    @Override
                    public void accept(Integer integer) {
                        switch (integer) {
                            case BluetoothAdapter.STATE_OFF:
                                start.setBackgroundColor(getResources().getColor(R.color.colorInactive, null));
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

        // Bond
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

        // Connection
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

        // ACL
        compositeDisposable.add(rxBluetooth.observeAclEvent() //
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

        start.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                devices.clear();
                setAdapter(devices);
                if (ContextCompat.checkSelfPermission(MainActivity.this,
                        android.Manifest.permission.ACCESS_COARSE_LOCATION)
                        != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(MainActivity.this,
                            new String[]{android.Manifest.permission.ACCESS_COARSE_LOCATION},
                            REQUEST_PERMISSION_COARSE_LOCATION);
                } else {
                    rxBluetooth.startDiscovery();
                }
            }
        });
        stop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                rxBluetooth.cancelDiscovery();
            }
        });
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
                String devName = device.getName();
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
}
