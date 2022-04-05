package id.flutter.flutter_background_service;

import android.annotation.SuppressLint;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.atomic.AtomicBoolean;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import io.flutter.FlutterInjector;
import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.embedding.engine.dart.DartExecutor;
import io.flutter.plugin.common.JSONMethodCodec;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.view.FlutterCallbackInformation;

import static android.os.Build.VERSION.SDK_INT;

public class BackgroundService extends Service implements MethodChannel.MethodCallHandler {
    private static final String TAG = "BackgroundService";
    private FlutterEngine backgroundEngine;
    private MethodChannel methodChannel;
    private DartExecutor.DartCallback dartCallback;

    private static final String WAKE_LOCK_TAG = "id.flutter.flutter_background_service:BackgroundService";
    static final String ACTION_CANCEL = "ACTION_CANCEL";
    private static final String ACTION_SWITCH_WAKE_LOCK = "ACTION_SWITCH_WAKE_LOCK";

    String notificationTitle = "Background Service";
    String notificationContent = null;
    Integer notificationMax = null;
    Integer notificationProgress = null;

    PowerManager.WakeLock wakeLock;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public void setForegroundServiceMode(boolean value) {
        SharedPreferences pref = getSharedPreferences("id.flutter.background_service", MODE_PRIVATE);
        pref.edit().putBoolean("is_foreground", value).apply();
    }

    public static boolean isForegroundService(Context context) {
        SharedPreferences pref = context.getSharedPreferences("id.flutter.background_service", MODE_PRIVATE);
        return pref.getBoolean("is_foreground", true);
    }

    public static String getNotificationTitle(Context context) {
        SharedPreferences pref = context.getSharedPreferences("id.flutter.background_service", MODE_PRIVATE);
        return pref.getString("title", null);
    }

    public static String getNotificationContent(Context context) {
        SharedPreferences pref = context.getSharedPreferences("id.flutter.background_service", MODE_PRIVATE);
        return pref.getString("content", null);
    }

    @SuppressLint("WakelockTimeout")
    @Override
    public void onCreate() {
        super.onCreate();
        PowerManager pm = (PowerManager)getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG);
        wakeLock.setReferenceCounted(false);
        if (isEnableWakeLock()) {
            Log.i(TAG, "Wake lock enabled");
            wakeLock.acquire();
        }

        createNotificationChannel();
        notificationTitle = getNotificationTitle(this);
        if (notificationTitle == null) {
            notificationTitle = getApplicationContext().getPackageName();
        }
        notificationContent = getNotificationContent(this);
        updateNotificationInfo();
    }

    @Override
    public void onDestroy() {
        try {
            stopForeground(true);
            isRunning.set(false);

            if (backgroundEngine != null) {
                backgroundEngine.getServiceControlSurface().detachFromService();
                backgroundEngine.destroy();
                backgroundEngine = null;
            }

            methodChannel = null;
            dartCallback = null;
        } finally {
            wakeLock.release();
        }
        super.onDestroy();
    }

    private void createNotificationChannel() {
        if (SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = "Background service";

            int importance = NotificationManager.IMPORTANCE_LOW;
            NotificationChannel channel = new NotificationChannel("FOREGROUND_DEFAULT", name, importance);
            channel.setShowBadge(false);

            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }
    }

    protected void updateNotificationInfo() {
        if (isForegroundService(this)) {

            String packageName = getApplicationContext().getPackageName();
            Intent i = getPackageManager().getLaunchIntentForPackage(packageName);

            PendingIntent pi;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S){
                pi = PendingIntent.getActivity(BackgroundService.this, 99778, i, PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_MUTABLE);
            } else {
                pi = PendingIntent.getActivity(BackgroundService.this, 99778, i, PendingIntent.FLAG_CANCEL_CURRENT);
            }

            NotificationCompat.Builder mBuilder = new NotificationCompat.Builder(this, "FOREGROUND_DEFAULT")
                    .setSmallIcon(R.drawable.outline_image_white_24)
                    .setAutoCancel(true)
                    .setOngoing(true)
                    .setContentTitle(notificationTitle)
                    .setContentIntent(pi);
            if (notificationContent != null) {
                mBuilder.setContentText(notificationContent);
            }
            if (notificationMax != null) {
                mBuilder.setProgress(
                    notificationMax,
                    notificationProgress == null ? 0 : notificationProgress,
                    notificationProgress == null
                );
            }

            Intent wakeLockIntent = new Intent(ACTION_SWITCH_WAKE_LOCK);
            wakeLockIntent.setClass(
                getApplicationContext(),
                BackgroundService.class
            );
            PendingIntent wakeLockPi = PendingIntent.getService(
                this,
                99780,
                wakeLockIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0)
            );
            mBuilder.addAction(new NotificationCompat.Action(
                android.R.drawable.ic_lock_lock,
                (isEnableWakeLock() ? "Disable" : "Enable") + " wake lock",
                wakeLockPi
            ));

            Intent cancelIntent = new Intent(ACTION_CANCEL);
            cancelIntent.setClass(
                getApplicationContext(),
                BackgroundService.class
            );
            PendingIntent cancelPi = PendingIntent.getService(
                this,
                99779,
                cancelIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0)
            );
            mBuilder.addAction(new NotificationCompat.Action(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(android.R.string.cancel),
                cancelPi
            ));

            startForeground(99778, mBuilder.build());
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            switch (intent.getAction()) {
            case ACTION_CANCEL:
                onCancel();
                break;

            case ACTION_SWITCH_WAKE_LOCK:
                onSwitchWakeLock();
                break;
            }
        } else {
            runService();
        }
        return START_STICKY;
    }

    AtomicBoolean isRunning = new AtomicBoolean(false);

    private void runService() {
        try {
            Log.d(TAG, "runService");
            if (isRunning.get() || (backgroundEngine != null && !backgroundEngine.getDartExecutor().isExecutingDart()))
                return;
            updateNotificationInfo();

            SharedPreferences pref = getSharedPreferences("id.flutter.background_service", MODE_PRIVATE);
            long callbackHandle = pref.getLong("callback_handle", 0);

            // initialize flutter if its not initialized yet
            if (!FlutterInjector.instance().flutterLoader().initialized()) {
                FlutterInjector.instance().flutterLoader().startInitialization(getApplicationContext());
            }

            FlutterInjector.instance().flutterLoader().ensureInitializationComplete(getApplicationContext(), null);
            FlutterCallbackInformation callback = FlutterCallbackInformation.lookupCallbackInformation(callbackHandle);
            if (callback == null) {
                Log.e(TAG, "callback handle not found");
                return;
            }

            isRunning.set(true);
            backgroundEngine = new FlutterEngine(this);
            backgroundEngine.getServiceControlSurface().attachToService(BackgroundService.this, null, isForegroundService(this));

            methodChannel = new MethodChannel(backgroundEngine.getDartExecutor().getBinaryMessenger(), "id.flutter/background_service_bg", JSONMethodCodec.INSTANCE);
            methodChannel.setMethodCallHandler(this);

            dartCallback = new DartExecutor.DartCallback(getAssets(), FlutterInjector.instance().flutterLoader().findAppBundlePath(), callback);
            backgroundEngine.getDartExecutor().executeDartCallback(dartCallback);
        } catch (UnsatisfiedLinkError e) {
            notificationContent = "Error " +e.getMessage();
            updateNotificationInfo();

            Log.w(TAG, "UnsatisfiedLinkError: After a reboot this may happen for a short period and it is ok to ignore then!" + e.getMessage());
        }
    }

    public void receiveData(JSONObject data) {
        if (methodChannel != null) {
            try {
                methodChannel.invokeMethod("onReceiveData", data);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void onMethodCall(@NonNull MethodCall call, @NonNull MethodChannel.Result result) {
        String method = call.method;

        try {
            if (method.equalsIgnoreCase("setNotificationInfo")) {
                JSONObject arg = (JSONObject) call.arguments;
                if (arg.has("title")) {
                    notificationTitle = arg.isNull("title") ? null : arg.getString("title");
                    notificationContent = arg.isNull("content") ? null : arg.getString("content");
					notificationMax = arg.isNull("max") ? null : arg.getInt("max");
					notificationProgress = arg.isNull("progress") ? null : arg.getInt("progress");
                    updateNotificationInfo();
                    result.success(true);
                    return;
                }
            }

            if (method.equalsIgnoreCase("setAutoStartOnBootMode")) {
                // auto start support removed
                result.success(true);
                return;
            }

            if (method.equalsIgnoreCase("setForegroundMode")) {
                JSONObject arg = (JSONObject) call.arguments;
                boolean value = arg.getBoolean("value");
                setForegroundServiceMode(value);
                if (value) {
                    updateNotificationInfo();
                } else {
                    stopForeground(true);
                }

                result.success(true);
                return;
            }

            if (method.equalsIgnoreCase("stopService")) {
                stopSelf();
                result.success(true);
                return;
            }

            if (method.equalsIgnoreCase("sendData")) {
                LocalBroadcastManager manager = LocalBroadcastManager.getInstance(this);
                Intent intent = new Intent("id.flutter/background_service");
                intent.putExtra("data", ((JSONObject) call.arguments).toString());
                manager.sendBroadcast(intent);
                result.success(true);
                return;
            }
        } catch (JSONException e) {
            Log.e(TAG, e.getMessage());
            e.printStackTrace();
        }

        result.notImplemented();
    }

    private void onCancel() {
        if (methodChannel != null) {
            try {
                methodChannel.invokeMethod("onCancel", null);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    @SuppressLint("WakelockTimeout")
    private void onSwitchWakeLock() {
        if (isEnableWakeLock()) {
            Log.i(TAG, "Wake lock disabled");
            wakeLock.release();
            persistEnableWakeLock(false);
        } else {
            Log.i(TAG, "Wake lock enabled");
            wakeLock.acquire();
            persistEnableWakeLock(true);
        }
        updateNotificationInfo();
    }

    private void persistEnableWakeLock(boolean flag) {
        SharedPreferences pref = getSharedPreferences("id.flutter.background_service", MODE_PRIVATE);
        pref.edit().putBoolean("is_enable_wake_lock", flag).apply();
    }

    private boolean isEnableWakeLock() {
        SharedPreferences pref = getSharedPreferences("id.flutter.background_service", MODE_PRIVATE);
        return pref.getBoolean("is_enable_wake_lock", true);
    }
}
