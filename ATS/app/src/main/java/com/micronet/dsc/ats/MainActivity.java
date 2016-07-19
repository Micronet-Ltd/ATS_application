package com.micronet.dsc.ats;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.Resources;
import android.os.Handler;
import android.os.IBinder;
import android.os.StrictMode;
//import android.support.v7.app.ActionBarActivity;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import 	android.widget.Toast;
import 	android.content.Context;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

public class MainActivity extends Activity {

    public static final String TAG = "ATS-Activity";


    public static final boolean SHOW_ACTIVITY = false; // enable this for testing, disable for production

    CheckBox checkBoxOverride;
    CheckBox checkBoxIgnition;
    CheckBox checkBoxInput1;
    CheckBox checkBoxInput2;
    CheckBox checkBoxInput3;
    CheckBox checkBoxInput4;
    CheckBox checkBoxInput5;
    CheckBox checkBoxInput6;
    Button buttonVoltage;
    EditText editVoltage;
    TextView textLog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {


        Log.v(TAG,"onCreate()");


        // All we want to do is launch the service, toast the user, and exit

        Context context = getApplicationContext();
        Intent i = new Intent(context, MainService.class);
        context.startService(i);

        Toast.makeText(context, "ATS Service is activated", Toast.LENGTH_SHORT).show();


        // To show the debug-activity screen comment out the finish and return lines;
        if (!SHOW_ACTIVITY) {
            super.onCreate(savedInstanceState);
            finish();
        } else {

            setTheme(R.style.AppTheme);

            super.onCreate(savedInstanceState);
            setContentView(R.layout.activity_main);


            textLog = (TextView) findViewById(R.id.textLog);
            textLog.setMovementMethod(new ScrollingMovementMethod());


            TextView textSerial = (TextView) findViewById(R.id.textSerial);
            String serialstr = IoService.getHardwareDeviceId();
            if (serialstr != null) {
                textSerial.setText("Serial Number: " + serialstr);
            }


            checkBoxOverride = (CheckBox) findViewById(R.id.checkBoxOverride);
            checkBoxOverride.setChecked(Io.DEFAULT_ALWAYS_OVERRIDE);

            checkBoxIgnition = (CheckBox) findViewById(R.id.checkBoxIgnition);
            checkBoxInput1 = (CheckBox) findViewById(R.id.checkBoxInput1);
            checkBoxInput2 = (CheckBox) findViewById(R.id.checkBoxInput2);
            checkBoxInput3 = (CheckBox) findViewById(R.id.checkBoxInput3);
            checkBoxInput4 = (CheckBox) findViewById(R.id.checkBoxInput4);
            checkBoxInput5 = (CheckBox) findViewById(R.id.checkBoxInput5);
            checkBoxInput6 = (CheckBox) findViewById(R.id.checkBoxInput6);
            buttonVoltage = (Button) findViewById(R.id.buttonVoltage);
            editVoltage = (EditText) findViewById(R.id.editTextVolts);

            if (!Io.DEFAULT_ALWAYS_OVERRIDE) {
                IoService.HardwareInputResults hardwareInputResults = IoService.getAllHardwareInputs();

                if (hardwareInputResults  != null) {
                    checkBoxIgnition.setChecked(hardwareInputResults.ignition_input);
                    checkBoxInput1.setChecked(hardwareInputResults.input1 == 1);
                    checkBoxInput2.setChecked(hardwareInputResults.input2 == 1);
                    checkBoxInput3.setChecked(hardwareInputResults.input3 == 1);
                    checkBoxInput4.setChecked(hardwareInputResults.input4 == 1);
                    checkBoxInput5.setChecked(hardwareInputResults.input5 == 1);
                    checkBoxInput6.setChecked(hardwareInputResults.input6 == 1);
                    editVoltage.setText("" + Io.DEFAULT_BATTERY_VOLTS);
                    //editVoltage.setText("13.4");
                    //editVoltage.setText(("" + hardwareResults.voltage));
                }
            } else {
                checkBoxIgnition.setChecked(Io.DEFAULT_IGNITION_STATE);
                checkBoxInput1.setChecked(Io.DEFAULT_INPUT1_STATE);
                checkBoxInput2.setChecked(Io.DEFAULT_INPUT2_STATE);
                checkBoxInput3.setChecked(Io.DEFAULT_INPUT3_STATE);
                checkBoxInput4.setChecked(Io.DEFAULT_INPUT4_STATE);
                checkBoxInput5.setChecked(Io.DEFAULT_INPUT5_STATE);
                checkBoxInput6.setChecked(Io.DEFAULT_INPUT6_STATE);
                editVoltage.setText("" + Io.DEFAULT_BATTERY_VOLTS);
            }


            checkBoxOverride.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (checkBoxOverride.isChecked()) {
                        Log.i(TAG, "Checked Override");
                        Io.DEFAULT_ALWAYS_OVERRIDE = true;
                    } else {
                        Log.i(TAG, "Un-Checked Override");
                        Io.DEFAULT_ALWAYS_OVERRIDE = false;
                    }
                }
            });


            checkBoxIgnition.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (checkBoxIgnition.isChecked()) {
                        Log.i(TAG, "Checked Ignition");
                        Io.DEFAULT_IGNITION_STATE = true;
                    } else {
                        Log.i(TAG, "Un-Checked Ignition");
                        Io.DEFAULT_IGNITION_STATE = false;
                    }
                }
            });

            checkBoxInput1.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (checkBoxInput1.isChecked()) {
                        Log.i(TAG, "Checked Input1");
                        Io.DEFAULT_INPUT1_STATE = true;
                    } else {
                        Log.i(TAG, "Un-Checked Input1");
                        Io.DEFAULT_INPUT1_STATE = false;
                    }
                }
            });


            checkBoxInput2.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (checkBoxInput1.isChecked()) {
                        Log.i(TAG, "Checked Input2");
                        Io.DEFAULT_INPUT2_STATE = true;
                    } else {
                        Log.i(TAG, "Un-Checked Input2");
                        Io.DEFAULT_INPUT2_STATE = false;
                    }
                }
            });

            checkBoxInput3.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (checkBoxInput3.isChecked()) {
                        Log.i(TAG, "Checked Input3");
                        Io.DEFAULT_INPUT3_STATE = true;
                    } else {
                        Log.i(TAG, "Un-Checked Input3");
                        Io.DEFAULT_INPUT3_STATE = false;
                    }
                }
            });


            checkBoxInput4.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (checkBoxInput4.isChecked()) {
                        Log.i(TAG, "Checked Input4");
                        Io.DEFAULT_INPUT4_STATE = true;
                    } else {
                        Log.i(TAG, "Un-Checked Input4");
                        Io.DEFAULT_INPUT4_STATE = false;
                    }
                }
            });


            checkBoxInput5.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (checkBoxInput1.isChecked()) {
                        Log.i(TAG, "Checked Input5");
                        Io.DEFAULT_INPUT5_STATE = true;
                    } else {
                        Log.i(TAG, "Un-Checked Input5");
                        Io.DEFAULT_INPUT5_STATE = false;
                    }
                }
            });


            checkBoxInput6.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (checkBoxInput6.isChecked()) {
                        Log.i(TAG, "Checked Input6");
                        Io.DEFAULT_INPUT6_STATE = true;
                    } else {
                        Log.i(TAG, "Un-Checked Input6");
                        Io.DEFAULT_INPUT6_STATE = false;
                    }
                }
            });


            buttonVoltage.setOnClickListener(new View.OnClickListener() {

                @Override
                public void onClick(View view) {
                    String strVoltage = editVoltage.getText().toString();
                    Log.i(TAG, "Set Default Voltage to " + strVoltage);
                    Io.DEFAULT_BATTERY_VOLTS = Double.parseDouble(strVoltage);
                }
            });


        }



    } // onCreate()


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onStart() {
        super.onStart();
        Log.v(TAG, "onStart()");

        // Bind to LocalService
        Intent intent = new Intent(this, MainService.class);
        bindService(intent, mConnection, Context.BIND_WAIVE_PRIORITY);

        Log.callbackInterface = myLogCallback;
    } // onStart()


    @Override
    public void onStop() {
        super.onStop();
        Log.v(TAG,"onStop()");

        Log.callbackInterface = null;
        if (mBound) {
            unbindService(mConnection);
            mBound = false;
        }

    } // onStop()

    @Override
    public void onResume() {
        super.onResume();
        Log.v(TAG, "onResume()");

    } // onResume()



    @Override
    public void onPause() {
        super.onPause();
        Log.v(TAG,"onPause()");

    } // onPause()

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.v(TAG,"onDestroy()");
    }


    Handler mainHandler  = new Handler();

    MainService mService;
    boolean mBound = false;
    /** Defines callbacks for service binding, passed to bindService() */
    private ServiceConnection mConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName className,
                                       IBinder service) {
            Log.v(TAG, "onServiceConnected()");
            // We've bound to LocalService, cast the IBinder and get LocalService instance
            MainService.LocalBinder binder = (MainService.LocalBinder) service;
            mService = binder.getService();
            mBound = true;
            mainHandler.postDelayed(updateTask, 1000); // update every second
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            mBound = false;
            Log.v(TAG, "onServiceDisconnected()");
        }
    };



    public void showStatus() {

        Log.v(TAG, "showStatus()");
        final List<QueueItem> list =  mService.queue.getAllItems();

        final IoService.HardwareInputResults hardwareInputResults = IoService.getAllHardwareInputs();


        MainActivity.this.runOnUiThread(new Runnable() {
            public void run() {
                Log.v(TAG, "showStatus.run()");

                if (!mBound) return;

                DateFormat df = DateFormat.getTimeInstance();
                df.setTimeZone(TimeZone.getTimeZone("UTC"));
                String dateutc = df.format(new Date());

                Context context = getApplicationContext();



                Log.v(TAG, "showStatus M1");


                TextView textStatus = (TextView) findViewById(R.id.textStatus);
                TextView textStatus2 = (TextView) findViewById(R.id.textStatus2);
                TextView textStatus3 = (TextView) findViewById(R.id.textStatus3);
                TextView textStatus4 = (TextView) findViewById(R.id.textStatus4);
                TextView textStatus5 = (TextView) findViewById(R.id.textStatus5);

                String hardwareStr;
                hardwareStr = "";

                if ((hardwareInputResults != null)) {

                    hardwareStr = dateutc + "; " + hardwareInputResults.voltage + "V; " +
                            "IGN " + hardwareInputResults.ignition_input + "; " +
                            "IN1 " + hardwareInputResults.input1 + "; " +
                            "IN2 " + hardwareInputResults.input2 + "; " +
                            "IN3 " + hardwareInputResults.input3 + "; " +
                            "IN4 " + hardwareInputResults.input4 + "; " +
                            "IN5 " + hardwareInputResults.input5 + "; " +
                            "IN6 " + hardwareInputResults.input6 + "; ";

                } // have hardware results
                textStatus.setText(hardwareStr);

                String posStr, posStr2, cumStr;

                posStr = "";
                posStr2 = "";
                cumStr = "";

                if  ((mService != null) &&
                    (mService.position != null) &&
                    (mService.position.savedLocation != null) &&
                        (mService.position.savedAcceleration != null)) {
                    posStr = "@" + mService.position.savedLocation.time_elapsed  +  ":: Time: " +
                            mService.position.savedLocation.time_location + "; " +
                            String.format( "%.5f", mService.position.savedLocation.latitude) + "  " +
                             String.format( "%.5f", mService.position.savedLocation.longitude) + "; " +
                            mService.position.savedLocation.accuracy_m + "m accuracy; " +
                            mService.position.savedLocation.fix_type + " fix; ";

                    posStr2 =
                            mService.position.savedLocation.speed_cms + " cm/s; " +
                            mService.position.savedLocation.bearing + " deg; " +


                            String.format( "%.2f", mService.position.savedAcceleration.forward_acceleration_cms2) + " fwd; " +
                            String.format( "%.2f", mService.position.savedAcceleration.orthogonal_acceleration_cms2) + " ortho"
                    ;




                    cumStr = "ODO: " + mService.position.virtual_odometer_m + " m; " +
                            "IDL: " + mService.position.continuous_idling_s + " s; " +
                            (mService.position.flagCornering ? " CORNERING" : "") +
                            (mService.position.flagAccelerating ? " ACCELERATING" : "") +
                            (mService.position.flagBraking ? " BRAKING" : "") +
                            (mService.position.flagSpeeding ? " SPEEDING" : "")
                    ;


                }
                textStatus2.setText(posStr);
                textStatus3.setText(posStr2);
                if (mService.position.flagMoving)
                    textStatus3.setTextColor(0xFF008000);
                else
                    textStatus3.setTextColor(0xFF800000);


                textStatus4.setText(cumStr);
                if (mService.position.flagIdling)
                    textStatus4.setTextColor(0xFF800000);
                else if (mService.position.flagMoving)
                    textStatus4.setTextColor(0xFF008000);
                else
                    textStatus4.setTextColor(0xFF000080);


                String strStatus5 = mService.position.lastIntegratorRunTime + " " +
                        mService.position.lastDifferentiatorRunTime +
                        " ; Queue: ";

                for (int i = 0; i < list.size() && i < 20; i++) {
                    strStatus5 = strStatus5 + list.get(i).event_type_id + ";";
                }
                textStatus5.setText(strStatus5);

                Log.v(TAG, "showStatus END");
            } //run
        });
    } // showStatus()


    ///////////////////////////////////////////////////////////////
    // updateTask()
    //  Timer that updates the UI
    ///////////////////////////////////////////////////////////////
    private Runnable updateTask = new Runnable() {

        @Override
        public void run() {

            Log.v(TAG, "updateTask()");

            showStatus();
            if (mBound) // we are still bound
                mainHandler.postDelayed(updateTask, 1000); // update every second

            Log.v(TAG, "updateTask() END");
        }
    };



    class MyLogCallback implements Log.LogCallbackInterface {

        @Override
        public void show(final String tag, final String text) {
            MainActivity.this.runOnUiThread(new Runnable() {
                public void run() {
                    textLog.append("\n" + tag + ": " + text);
                }
            });
        } // show
    };

    MyLogCallback myLogCallback = new MyLogCallback();


} // class
