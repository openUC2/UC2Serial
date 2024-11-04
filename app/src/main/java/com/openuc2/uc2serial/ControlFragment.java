package com.openuc2.uc2serial;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class ControlFragment extends Fragment implements SerialInputOutputManager.Listener {

    private String TAG = "ControlFragment";
    private static final String ACTION_USB_PERMISSION = "com.openuc2.uc2serial.USB_PERMISSION";

    private enum UsbPermission {
        Unknown, Requested, Granted, Denied
    }

    private static final String INTENT_ACTION_GRANT_USB = BuildConfig.APPLICATION_ID + ".GRANT_USB";
    private static final int WRITE_WAIT_MILLIS = 2000;
    private static final int READ_WAIT_MILLIS = 2000;

    private int deviceId, portNum, baudRate;
    private boolean withIoManager;

    private final BroadcastReceiver broadcastReceiver;
    private final Handler mainLooper;

    private SerialInputOutputManager usbIoManager;
    private UsbSerialPort usbSerialPort;
    private UsbPermission usbPermission = UsbPermission.Unknown;
    private boolean connected = false;

    // json strings
    String ledOn = "{'task':'/ledarr_act', 'led':{'LEDArrMode':1, 'led_array':[{'id':0, 'r':255, 'g':255, 'b':255}]}}";
    String ledOff = "{'task':'/ledarr_act', 'led':{'LEDArrMode':1, 'led_array':[{'id':0, 'r':0, 'g':0, 'b':0}]}}";

    String cmdZPlusplus = "{\"task\":\"/motor_act\", \"motor\": { \"steppers\": [ { \"stepperid\": 3, \"position\": 1000, \"speed\": 1000, \"isabs\": 0, \"isaccel\":0} ] } }";
    String cmdZPlus = "{\"task\":\"/motor_act\", \"motor\": { \"steppers\": [ { \"stepperid\": 3, \"position\": 100, \"speed\": 1000, \"isabs\": 0, \"isaccel\":0} ] } }";
    String cmdZMinusminus = "{\"task\":\"/motor_act\", \"motor\": { \"steppers\": [ { \"stepperid\": 3, \"position\": -1000, \"speed\": 1000, \"isabs\": 0, \"isaccel\":0} ] } }";
    String cmdZMinus = "{\"task\":\"/motor_act\", \"motor\": { \"steppers\": [ { \"stepperid\": 3, \"position\": -100, \"speed\": 1000, \"isabs\": 0, \"isaccel\":0} ] } }";

    public ControlFragment() {
        broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (INTENT_ACTION_GRANT_USB.equals(intent.getAction())) {
                    usbPermission = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                            ? UsbPermission.Granted
                            : UsbPermission.Denied;
                    connect();
                }
            }
        };
        mainLooper = new Handler(Looper.getMainLooper());
    }

    /*
     * Lifecycle
     */
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        setRetainInstance(true);
        deviceId = getArguments().getInt("device");
        portNum = getArguments().getInt("port");
        baudRate = getArguments().getInt("baud");
        withIoManager = getArguments().getBoolean("withIoManager");
    }

    @Override
    public void onResume() {
        super.onResume();
        getActivity().registerReceiver(broadcastReceiver, new IntentFilter(INTENT_ACTION_GRANT_USB));

        if (usbPermission == UsbPermission.Unknown || usbPermission == UsbPermission.Granted)
            mainLooper.post(this::connect);
    }

    @Override
    public void onPause() {
        if (connected) {
            status("disconnected");
            disconnect();
        }
        getActivity().unregisterReceiver(broadcastReceiver);
        super.onPause();
    }

    /*
     * UI
     */
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        // UI elements
        int axisA = 0;
        int axisX = 1;
        int axisY = 2;
        int axisZ = 3;
        int speed = 5000;
        int posCoarse = 1000;
        int posFine = 100;
        // Inflate the layout for this fragment
        View rootView = inflater.inflate(R.layout.fragment_control, container, false);

        // LED Array
        SeekBar sliderLEDArrayIntensity = rootView.findViewById(R.id.seekbar_led);

        sliderLEDArrayIntensity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            int ledVal = 0;

            @Override
            public void onProgressChanged(SeekBar seekBar, int i, boolean b) {
                ledVal = i;
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                send(getLEDDict(ledVal));
            }
        });

        // Laser 0 Controls (Slider)
        SeekBar sliderLaser0 = rootView.findViewById(R.id.seekbar_laser0);
        sliderLaser0.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            int laserVal = 0;

            @Override
            public void onProgressChanged(SeekBar seekBar, int i, boolean b) {
                laserVal = i;
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                // Send the laser control command
                send(getLaserDict(laserVal, 0));    
            }
        });

        // Laser 1 Controls (Slider)
        SeekBar sliderLaser1 = rootView.findViewById(R.id.seekbar_laser1);
        sliderLaser1.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            int laserVal = 0;

            @Override
            public void onProgressChanged(SeekBar seekBar, int i, boolean b) {
                laserVal = i;
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                // Send the laser control command
                send(getLaserDict(laserVal, 1));
            }
        });

        // Laser 2 Controls (Slider)
        SeekBar sliderLaser2 = rootView.findViewById(R.id.seekbar_laser2);
        sliderLaser2.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            int laserVal = 0;

            @Override
            public void onProgressChanged(SeekBar seekBar, int i, boolean b) {
                laserVal = i;
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                // Send the laser control command
                send(getLaserDict(laserVal, 2));
            }
        });

        // Laser 3 Controls (Slider)
        SeekBar sliderLaser3 = rootView.findViewById(R.id.seekbar_laser3);
        sliderLaser3.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            int laserVal = 0;

            @Override
            public void onProgressChanged(SeekBar seekBar, int i, boolean b) {
                laserVal = i;
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                // Send the laser control command
                send(getLaserDict(laserVal, 3));
            }
        });



        // Axis A Controls
        Button aMinusLarge = rootView.findViewById(R.id.button_a_minus_large);
        Button aMinusSmall = rootView.findViewById(R.id.button_a_minus_small);
        Button aPlusSmall = rootView.findViewById(R.id.button_a_plus_small);
        Button aPlusLarge = rootView.findViewById(R.id.button_a_plus_large);

        aMinusLarge.setOnClickListener(v -> send(getMotorControlDict(axisA, -posCoarse, speed)));
        aMinusSmall.setOnClickListener(v -> send(getMotorControlDict(axisA, -posFine, speed)));
        aPlusSmall.setOnClickListener(v -> send(getMotorControlDict(axisA, posFine, speed)));
        aPlusLarge.setOnClickListener(v -> send(getMotorControlDict(axisA, posCoarse, speed)));

        // Axis X Controls
        Button xMinusLarge = rootView.findViewById(R.id.button_x_minus_large);
        Button xMinusSmall = rootView.findViewById(R.id.button_x_minus_small);
        Button xPlusSmall = rootView.findViewById(R.id.button_x_plus_small);
        Button xPlusLarge = rootView.findViewById(R.id.button_x_plus_large);

        xMinusLarge.setOnClickListener(v -> send(getMotorControlDict(axisX, -posCoarse, speed)));
        xMinusSmall.setOnClickListener(v -> send(getMotorControlDict(axisX, -posFine, speed)));
        xPlusSmall.setOnClickListener(v -> send(getMotorControlDict(axisX, posFine, speed)));
        xPlusLarge.setOnClickListener(v -> send(getMotorControlDict(axisX, posCoarse, speed)));

        // Axis Y Controls
        Button yMinusLarge = rootView.findViewById(R.id.button_y_minus_large);
        Button yMinusSmall = rootView.findViewById(R.id.button_y_minus_small);
        Button yPlusSmall = rootView.findViewById(R.id.button_y_plus_small);
        Button yPlusLarge = rootView.findViewById(R.id.button_y_plus_large);

        yMinusLarge.setOnClickListener(v -> send(getMotorControlDict(axisY, -posCoarse, speed)));
        yMinusSmall.setOnClickListener(v -> send(getMotorControlDict(axisY, -posFine, speed)));
        yPlusSmall.setOnClickListener(v -> send(getMotorControlDict(axisY, posFine, speed)));
        yPlusLarge.setOnClickListener(v -> send(getMotorControlDict(axisY, posCoarse, speed)));

        // Axis Z Controls
        Button zMinusLarge = rootView.findViewById(R.id.button_z_minus_large);
        Button zMinusSmall = rootView.findViewById(R.id.button_z_minus_small);
        Button zPlusSmall = rootView.findViewById(R.id.button_z_plus_small);
        Button zPlusLarge = rootView.findViewById(R.id.button_z_plus_large);

        zMinusLarge.setOnClickListener(v -> send(getMotorControlDict(axisZ, -posCoarse, speed)));
        zMinusSmall.setOnClickListener(v -> send(getMotorControlDict(axisZ, -posFine, speed)));
        zPlusSmall.setOnClickListener(v -> send(getMotorControlDict(axisZ, posFine, speed)));
        zPlusLarge.setOnClickListener(v -> send(getMotorControlDict(axisZ, posCoarse, speed)));

        return rootView;
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.menu_control, menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.clear) {
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
    }

    /*
     * Serial
     */
    @Override
    public void onNewData(byte[] data) {
        mainLooper.post(() -> {
            receive(data);
        });
    }

    @Override
    public void onRunError(Exception e) {
        mainLooper.post(() -> {
            status("connection lost: " + e.getMessage());
            disconnect();
        });
    }

    private void requestUsbPermission(UsbDevice device) {
        UsbManager usbManager = (UsbManager) getActivity().getSystemService(Context.USB_SERVICE);
        PendingIntent permissionIntent = PendingIntent.getBroadcast(getActivity(), 0, new Intent(ACTION_USB_PERMISSION),
                PendingIntent.FLAG_IMMUTABLE);
        usbManager.requestPermission(device, permissionIntent);
    }

    /*
     * Serial + UI
     */
    private void connect() {
        UsbDevice device = null;
        UsbManager usbManager = (UsbManager) getActivity().getSystemService(Context.USB_SERVICE);
        for (UsbDevice v : usbManager.getDeviceList().values())
            if (v.getDeviceId() == deviceId)
                device = v;
        if (device == null) {
            status("connection failed: device not found");
            return;
        }
        UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(device);
        if (driver == null) {
            driver = CustomProber.getCustomProber().probeDevice(device);
        }
        if (driver == null) {
            status("connection failed: no driver for device");
            return;
        }
        if (driver.getPorts().size() < portNum) {
            status("connection failed: not enough ports at device");
            return;
        }
        usbSerialPort = driver.getPorts().get(portNum);
        UsbDeviceConnection usbConnection = usbManager.openDevice(driver.getDevice());
        if (usbConnection == null && usbPermission == UsbPermission.Unknown
                && !usbManager.hasPermission(driver.getDevice())) {
            usbPermission = UsbPermission.Requested;
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_IMMUTABLE : 0;
            PendingIntent usbPermissionIntent = PendingIntent.getBroadcast(getActivity(), 0,
                    new Intent(INTENT_ACTION_GRANT_USB), flags);
            usbManager.requestPermission(driver.getDevice(), usbPermissionIntent);
            return;
        }
        if (usbConnection == null) {
            if (!usbManager.hasPermission(driver.getDevice()))
                status("connection failed: permission denied");
            else {
                requestUsbPermission(device);
                status("connection failed: open failed");
            }
            return;
        }

        try {
            usbSerialPort.open(usbConnection);
            usbSerialPort.setParameters(baudRate, 8, 1, UsbSerialPort.PARITY_NONE);
            if (withIoManager) {
                usbIoManager = new SerialInputOutputManager(usbSerialPort, this);
                usbIoManager.start();
            }
            status("connected");
            connected = true;
        } catch (Exception e) {
            status("connection failed: " + e.getMessage());
            disconnect();
        }
    }

    private void disconnect() {
        connected = false;
        if (usbIoManager != null) {
            usbIoManager.setListener(null);
            usbIoManager.stop();
        }
        usbIoManager = null;
        try {
            usbSerialPort.close();
        } catch (IOException ignored) {
        }
        usbSerialPort = null;
    }

    private void send(String str) {
        if (!connected) {
            Toast.makeText(getActivity(), "not connected", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            byte[] data = (str + '\n').getBytes();
            usbSerialPort.write(data, WRITE_WAIT_MILLIS);
        } catch (Exception e) {
            onRunError(e);
        }
    }

    private void read() {
        if (!connected) {
            Toast.makeText(getActivity(), "not connected", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            byte[] buffer = new byte[8192];
            int len = usbSerialPort.read(buffer, READ_WAIT_MILLIS);
            receive(Arrays.copyOf(buffer, len));
        } catch (IOException e) {
            // when using read with timeout, USB bulkTransfer returns -1 on timeout _and_
            // errors
            // like connection loss, so there is typically no exception thrown here on error
            status("connection lost: " + e.getMessage());
            disconnect();
        }
    }

    private void receive(byte[] data) {
        String str = new String(data, StandardCharsets.UTF_8);
        Log.d("TEST", str);
    }

    void status(String str) {
    }

    String getLEDDict(int intensity) {
        // Create the JSON object
        String jsonString = "{}";
        try {
            JSONObject jsonDict = new JSONObject();
            // Add the task key-value pair to the JSON object
            jsonDict.put("task", "/ledarr_act");

            // Create the LED object and add the LEDArrayMode key-value pair to it
            JSONObject led = new JSONObject();
            led.put("LEDArrMode", 1);

            // Create the LED array and add the LED object to it
            JSONArray ledArray = new JSONArray();
            JSONObject ledObject = new JSONObject();
            ledObject.put("id", 0);
            ledObject.put("r", intensity);
            ledObject.put("g", intensity);
            ledObject.put("b", intensity);
            ledArray.put(ledObject);

            // Add the LED array to the LED object
            led.put("led_array", ledArray);

            // Add the LED object to the JSON object
            jsonDict.put("led", led);

            // Convert the JSON object to a string for sending
            jsonString = jsonDict.toString();
            Log.d("TEST", "JSON: " + jsonString);


        } catch (JSONException e) {
            Log.e(TAG, String.valueOf(e));
        }
        return jsonString;
    }

    String getLaserDict(int intensity, int laserId) {
        // Create the JSON object
        String jsonString = "{}";
        try {
            //  {"task": "/laser_act", "LASERid":1, "LASERval": 1024}
            //   {"task": "/laser_act", "LASERid":1, "LASERval": 1024}
            JSONObject jsonDict = new JSONObject();
            // Add the task key-value pair to the JSON object
            jsonDict.put("task", "/laser_act");
            jsonDict.put("LASERid", laserId);
            jsonDict.put("LASERval", intensity);
            // Convert the JSON object to a string for sending
            jsonString = jsonDict.toString();
            Log.d("TEST", "JSON: " + jsonString);
        } catch (JSONException e) {
            Log.e(TAG, String.valueOf(e));
        }
        return jsonString;
    }

    public static String getMotorControlDict(int axis, int position, int speed) {
        JSONObject json = new JSONObject();
        JSONArray steppers = new JSONArray();
        JSONObject stepper = new JSONObject();
        try {
            stepper.put("stepperid", axis);
            stepper.put("position", position);
            stepper.put("speed", speed);
            stepper.put("isabs", 0);
            stepper.put("isaccel", 0);
            steppers.put(stepper);
            json.put("task", "/motor_act");
            json.put("motor", new JSONObject().put("steppers", steppers));
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return json.toString();
    }

}
