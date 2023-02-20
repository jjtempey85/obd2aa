package uk.co.borconi.emil.obd2aa.services;


import static android.content.Context.NOTIFICATION_SERVICE;
import static android.content.Context.UI_MODE_SERVICE;

import android.annotation.SuppressLint;
import android.app.NotificationManager;
import android.app.UiModeManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;

import org.greenrobot.eventbus.EventBus;
import org.prowl.torque.remote.ITorqueService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import uk.co.borconi.emil.obd2aa.androidauto.OBD2AA;
import uk.co.borconi.emil.obd2aa.gauge.GaugeUpdate;
import uk.co.borconi.emil.obd2aa.helpers.NearbyCameras;
import uk.co.borconi.emil.obd2aa.helpers.PreferencesHelper;
import uk.co.borconi.emil.obd2aa.helpers.UnitConvertHelper;
import uk.co.borconi.emil.obd2aa.helpers.myGeoDecoder;
import uk.co.borconi.emil.obd2aa.pid.PIDToFetch;


/**
 * Created by Emil on 31/08/2017.
 */


public class OBD2Background {

    public static boolean isdebugging;
    static volatile boolean isrunning;
    private final Context context;
    private final OBD2AA mOBD2AA = null;
    private final List<PIDToFetch> aTorquePIDstoFetch = new ArrayList<>();
    NotificationManager mNotifyMgr;
    private ITorqueService torqueService;
    private String[] aGaugePIDs;
    private PreferencesHelper prefs;
    private SharedPreferences.Editor editor;
    private String[] aGaugeUnits;
    private boolean alternativepulling;
    private int nightMode = 0;
    private UiModeManager mUimodemanager = null;

    private boolean isSpeedCamShowing;
    private boolean ShowSpeedCamWarrning;
    private boolean isDemoMode;
    private Paint textpaint;
    private Paint paint;
    private RectF rectF;
    ServiceConnection connection;
    private boolean useImperial;
    private myGeoDecoder mgeodecoder;

    private NearbyCameras prevCamera;
    private boolean streetcard;
    private ExecutorService geodecoderexecutor;
    private int CameraRefreshFreq;
    private String mobile_filter, static_filter;



    public void CreateTorqueService()
    {

        if (torqueService == null)
        {
            ServiceConnection connection = new ServiceConnection() {
                public void onServiceConnected(ComponentName arg0, IBinder service) {
                    Log.d("HU", "SERVICE CONNECTED!");
                    torqueService = ITorqueService.Stub.asInterface(service);

                    try {
                        if (torqueService.getVersion() < 19) {
                            Log.d("OBD2-APP", "Incorrect version. You are using an old version of Torque with this plugin.\n\nThe plugin needs the latest version of Torque to run correctly.\n\nPlease upgrade to the latest version of Torque from Google Play");
                            return;
                        }
                    }
                    catch (RemoteException e)
                    {
                    }
                    Log.d("HU", "Have Torque service connection, starting fetching");
                }

                public void onServiceDisconnected(ComponentName name) {
                    torqueService = null;
                }
            };

        }
    }
    public OBD2Background(ITorqueService torqueService, Context context) {
        this.torqueService = torqueService;
        this.context = context;
        onCreate();
    }

    public void onCreate() {
        prefs = PreferencesHelper.getPreferences(context);
        mNotifyMgr = (NotificationManager) context.getSystemService(NOTIFICATION_SERVICE);
        editor = prefs.edit();
        mgeodecoder = new myGeoDecoder(context);
        textpaint = new Paint();
        textpaint.setARGB(255, 0, 0, 0);
        textpaint.setTextAlign(Paint.Align.CENTER);
        textpaint.setTextSize(110);
        textpaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        rectF = new RectF();
        rectF.set(16, 16, 240, 240);
        geodecoderexecutor = Executors.newSingleThreadExecutor();
        paint = new Paint();
        isDemoMode = prefs.isInDemoMode();
        useImperial = prefs.shouldUseImperial();
        int gaugeNumber = prefs.getNumberOfGauges();
        streetcard = prefs.shouldHaveStreetCard();


        aGaugePIDs = new String[gaugeNumber];
        aGaugeUnits = new String[gaugeNumber];
        isdebugging = prefs.isDebugging();
        alternativepulling = prefs.hasAlternativePulling();
        for (int i = 1; i <= gaugeNumber; i++) {
            aGaugePIDs[i - 1] = prefs.getPidForGauge(i);
            aGaugeUnits[i - 1] = prefs.getUnitForGauge(i);
            Log.d("OBD2AA", "Gounde number: " + i + " pid: " + prefs.getPidForGauge(i).split(",")[0] + " Unit: " + prefs.getUnitForGauge(i));
        }

        Log.d("OBD2AA", "OBD2 Background Service Created!");
        try
        {
            CreateTorqueService();

            dataFetcher();
        }
        catch (Exception e)
        {
            CreateTorqueService();

            dataFetcher();
        }

    }



    public void onDestroy() {
        Log.d("OBD2AA", "OBD2 Background Service on Destroy");
        isrunning = false;



        Intent sendIntent = new Intent();
        sendIntent.setAction("org.prowl.torque.REQUEST_TORQUE_QUIT"); //Stop torque
        context.sendBroadcast(sendIntent);
        android.os.Process.killProcess(android.os.Process.myPid()); //Do a kill.
    }

    private void dataFetcher()
    {

        isrunning = true;
        Thread thread = new Thread() {
            /* JADX WARNING: No exception handlers in catch block: Catch:{  } */
            @SuppressLint({"WrongConstant"})
            public void run() {
                int i;
                char c;
                double d;

                sortPids();
                while (OBD2Background.isrunning)
                {

                    // normal
                    try {
                        if (torqueService.isConnectedToECU())
                        {
                            List aTempGaugePIDs = Arrays.asList(aGaugePIDs);
                            float[] aiTorquePIDValues = torqueService.getPIDValues(aGaugePIDs);

                            long[] TorquePIDUpdateTime = torqueService.getPIDUpdateTime(aGaugePIDs);
                            for (PIDToFetch CurrTorquePID : aTorquePIDstoFetch) {
                                int TorqueIndexOfGaugePID = aTempGaugePIDs.indexOf(CurrTorquePID.getSinglePid());
                                if (TorquePIDUpdateTime[TorqueIndexOfGaugePID] == 0) {
                                    sleep(10);
                                    continue;
                                }
                                if ((TorquePIDUpdateTime[TorqueIndexOfGaugePID] == CurrTorquePID.getLastFetch())) {
                                    sleep(10);
                                    continue;
                                }
                                CurrTorquePID.putLastFetch(TorquePIDUpdateTime[TorqueIndexOfGaugePID]);

                                if (CurrTorquePID.getNeedsConversion()) {
                                    aiTorquePIDValues[TorqueIndexOfGaugePID] = UnitConvertHelper.ConvertValue(aiTorquePIDValues[TorqueIndexOfGaugePID], CurrTorquePID.getUnit());
                                }
                                if (EventBus.getDefault().hasSubscriberForEvent(GaugeUpdate.class)) {
                                    GaugeUpdate update = new GaugeUpdate(
                                            CurrTorquePID.getGaugeNumber(),
                                            Math.max(
                                                    Math.max(aiTorquePIDValues[TorqueIndexOfGaugePID], CurrTorquePID.getMinValue()),
                                                    Math.min(aiTorquePIDValues[TorqueIndexOfGaugePID], CurrTorquePID.getMaxValue())
                                            ));
                                    EventBus.getDefault().post(update);
                                }
                            }
                        }
                    } catch (RemoteException e) {
                        OBD2Background.isrunning = false;

                        throw new RuntimeException(e);
                    } catch (InterruptedException e) {
                        OBD2Background.isrunning = false;

                        throw new RuntimeException(e);
                    }
                    try {
                        Thread.sleep(150);
                    } catch (InterruptedException e) {
                        OBD2Background.isrunning = false;

                        throw new RuntimeException(e);

                    }
                }


            }

        };
        thread.start();
    }

    public String getUnit(String unit) {
        try {
            return torqueService.getPreferredUnit(unit);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        return unit;
    }

    private void sortPids() {

        Log.d("OBD2AA", "PIDS to string....");
        try {
            String[] pidsdesc = torqueService.getPIDInformation(aGaugePIDs);

            for (int i = 0; i < aGaugePIDs.length; i++) {
                //Build the pidstofetch object with correct data
                boolean needsconversion = !(torqueService.getPreferredUnit(aGaugeUnits[i]).equalsIgnoreCase(aGaugeUnits[i]));
                // Log.d("OBD2AA","Pid "+pids[i] + "Status: " +pidMap.get(pids[i]));

                String[] info = pidsdesc[i].split(",");
                // Log.d("OBD2AA"," Max val stored for pid (" + pids[i]+"): "+prefs.getFloat("maxval_" + (i+1), 0) +" Reported from Torque: " + parseInt(info[3]) + "units: " +units[i] + ",Locked: " + prefs.getBoolean("locked_"+(i+1),false) +"needconversion: "+needsconversion);

                if (!prefs.isLockedForGauge(i + 1) && prefs.getMaxValueForGauge(i + 1) != Float.parseFloat(info[3])) {
                    if (EventBus.getDefault().hasSubscriberForEvent(GaugeUpdate.class)) {
                        EventBus.getDefault().post(new GaugeUpdate(i, Float.parseFloat(info[3]), true, false));
                    }
                    editor.putString("maxval_" + (i + 1), info[3]);
                    editor.apply();

                }
                if (needsconversion) {
                    Float max = UnitConvertHelper.ConvertValue(Float.parseFloat(info[3]), aGaugeUnits[i]);
                    if (!prefs.isLockedForGauge(i + 1) && !prefs.getMaxValueForGauge(i + 1).equals(max)) {
                        if (EventBus.getDefault().hasSubscriberForEvent(GaugeUpdate.class)) {
                            EventBus.getDefault().post(new GaugeUpdate(i, max, true, false));
                        }
                        Log.d("OBD2AA", "Need to update Gauge_" + (i + 1) + "Max val: " + max);
                        editor.putString("maxval_" + (i + 1), max.toString());
                        editor.apply();
                    }

                    Float min = UnitConvertHelper.ConvertValue(Float.parseFloat(info[4]), aGaugeUnits[i]);
                    if (!prefs.isLockedForGauge(i + 1) && !prefs.getMinValueForGauge(i + 1).equals(min)) {
                        if (EventBus.getDefault().hasSubscriberForEvent(GaugeUpdate.class)) {
                            EventBus.getDefault().post(new GaugeUpdate(i, min, false, true));
                        }
                        Log.d("OBD2AA", "Need to update Gauge_" + (i + 1) + "Min val: " + min);
                        editor.putString("minval_" + (i + 1), min.toString());
                        editor.apply();
                    }
                }
                aTorquePIDstoFetch.add(new PIDToFetch(aGaugePIDs[i], true, 0, i, aGaugeUnits[i], needsconversion, prefs.getMaxValueForGauge(i + 1), prefs.getMinValueForGauge(i + 1)));
            }
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }
}
