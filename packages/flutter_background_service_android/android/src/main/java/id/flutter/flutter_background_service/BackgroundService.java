package id.flutter.flutter_background_service;

import android.annotation.SuppressLint;
import android.app.ForegroundServiceStartNotAllowedException;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import io.flutter.FlutterInjector;
import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.embedding.engine.dart.DartExecutor;
import io.flutter.embedding.engine.loader.FlutterLoader;
import io.flutter.plugin.common.JSONMethodCodec;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;

import static android.os.Build.VERSION.SDK_INT;

public class BackgroundService extends Service implements MethodChannel.MethodCallHandler {
    private static final String TAG = "BackgroundService";
    private static final String LOCK_NAME = BackgroundService.class.getName()
            + ".Lock";
    public static volatile WakeLock lockStatic = null; // notice static
    public static final String ACTION_CANCEL = "ACTION_CANCEL";

    private static final String WAKE_LOCK_TAG = "id.flutter.flutter_background_service:BackgroundService";

    AtomicBoolean isRunning = new AtomicBoolean(false);
    private FlutterEngine backgroundEngine;
    private MethodChannel methodChannel;
    private Config config;
    private DartExecutor.DartEntrypoint dartEntrypoint;
    private String notificationTitle;
    private String notificationContent;
    private String notificationChannelId;
    private int notificationId;
    private String configForegroundTypes;
	private Handler mainHandler;

    private PowerManager.WakeLock wakeLock;

    synchronized public static PowerManager.WakeLock getLock(Context context) {
        if (lockStatic == null) {
            PowerManager mgr = (PowerManager) context
                    .getSystemService(Context.POWER_SERVICE);
            lockStatic = mgr.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    LOCK_NAME);
            lockStatic.setReferenceCounted(true);
        }

        return lockStatic;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        return super.onUnbind(intent);
    }

    @SuppressLint("WakelockTimeout")
    @Override
    public void onCreate() {
        super.onCreate();

        config = new Config(this);

        String notificationChannelId = config.getNotificationChannelId();
        if (notificationChannelId == null) {
            this.notificationChannelId = "FOREGROUND_DEFAULT";
            createNotificationChannel();
        } else {
            this.notificationChannelId = notificationChannelId;
        }

        notificationTitle = config.getInitialNotificationTitle();
        notificationContent = config.getInitialNotificationContent();
        notificationId = config.getForegroundNotificationId();
        configForegroundTypes = config.getForegroundServiceTypes();

        createInitialNotification();

        FlutterBackgroundServicePlugin.servicePipe.addListener(listener);

        mainHandler = new Handler(Looper.getMainLooper());

        PowerManager pm = (PowerManager)getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG);
        wakeLock.setReferenceCounted(false);
        Log.i(TAG, "Wake lock enabled");
        wakeLock.acquire();

        updateNotificationInfo();
        onStartCommand(null, -1, -1);
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

            FlutterBackgroundServicePlugin.servicePipe.removeListener(listener);
            methodChannel = null;
            dartEntrypoint = null;
        } finally {
            wakeLock.release();
        }
        super.onDestroy();
    }

    @Override
    public void onTimeout(int startId)
    {
        super.onTimeout(startId);
        try {
            if (FlutterBackgroundServicePlugin.servicePipe.hasListener()){
                FlutterBackgroundServicePlugin.servicePipe.invoke(new JSONObject(Map.of(
                    "method", "timeout"
                )));
            }
        } catch (Exception e) {
            Log.e(TAG, e.getMessage());
            e.printStackTrace();
            stopSelf();
        }
    }

    private final Pipe.PipeListener listener = new Pipe.PipeListener() {
        @Override
        public void onReceived(JSONObject object) {
            receiveData(object);
        }
    };

    private void createNotificationChannel() {
        if (SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = "Background service";

            int importance = NotificationManager.IMPORTANCE_LOW;
            NotificationChannel channel = new NotificationChannel(notificationChannelId, name, importance);
            channel.setShowBadge(false);

            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }
    }

    private void createInitialNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            createInitialNotification31();
        } else {
            createInitialNotification0();
        }
    }

    private void createInitialNotification0() {
        if (config.isForeground()) {
            NotificationCompat.Builder mBuilder = new NotificationCompat.Builder(this, notificationChannelId)
                    .setSmallIcon(R.drawable.outline_image_white_24)
                    .setAutoCancel(true)
                    .setOngoing(true)
                    .setContentTitle(notificationTitle);
            try {
				String[] foregroundTypes = null;
                if (configForegroundTypes != null && !configForegroundTypes.isEmpty()) {
                    foregroundTypes = configForegroundTypes.split(",");
                }
                Integer serviceType = ForegroundTypeMapper.getForegroundServiceType(foregroundTypes);
                ServiceCompat.startForeground(this, notificationId, mBuilder.build(), serviceType);
            } catch (SecurityException e) {
              Log.w(TAG, "Failed to start foreground service due to SecurityException - have you forgotten to request a permission? - " + e.getMessage());
            }
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.S)
    private void createInitialNotification31() {
        try {
            createInitialNotification0();
        } catch (ForegroundServiceStartNotAllowedException e) {
            Log.w(TAG, "Failed to start foreground service due to app exceeding time limit");
        }
    }

    protected void updateNotificationInfo() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            updateNotificationInfo31();
        } else {
            updateNotificationInfo0();
        }
    }

    private void updateNotificationInfo0() {
        if (config.isForeground()) {
            String packageName = getApplicationContext().getPackageName();
            Intent i = getPackageManager().getLaunchIntentForPackage(packageName);

            int flags = PendingIntent.FLAG_CANCEL_CURRENT;
            if (SDK_INT >= Build.VERSION_CODES.S) {
                flags |= PendingIntent.FLAG_MUTABLE;
            }

            PendingIntent pi = PendingIntent.getActivity(BackgroundService.this, 11, i, flags);
            NotificationCompat.Builder mBuilder = new NotificationCompat.Builder(this, notificationChannelId)
                    .setSmallIcon(R.drawable.outline_image_white_24)
                    .setAutoCancel(true)
                    .setOngoing(true)
                    .setContentTitle(notificationTitle)
                    .setContentIntent(pi);
            if (notificationContent != null) {
                mBuilder.setContentText(notificationContent);
            }

            Intent cancelIntent = new Intent(ACTION_CANCEL);
            cancelIntent.setClass(
                getApplicationContext(),
                BackgroundService.class
            );
            PendingIntent cancelPi = PendingIntent.getService(
                this,
                notificationId + 1,
                cancelIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0)
            );
            mBuilder.addAction(new NotificationCompat.Action(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(android.R.string.cancel),
                cancelPi
            ));

            try {
				String[] foregroundTypes = null;
                if (configForegroundTypes != null && !configForegroundTypes.isEmpty()) {
                    foregroundTypes = configForegroundTypes.split(",");
                }
                Integer serviceType = ForegroundTypeMapper.getForegroundServiceType(foregroundTypes);
                ServiceCompat.startForeground(this, notificationId, mBuilder.build(), serviceType);
            } catch (SecurityException e) {
              Log.w(TAG, "Failed to start foreground service due to SecurityException - have you forgotten to request a permission? - " + e.getMessage());
            }
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.S)
    private void updateNotificationInfo31() {
        try {
            updateNotificationInfo0();
        } catch (ForegroundServiceStartNotAllowedException e) {
            Log.w(TAG, "Failed to start foreground service due to app exceeding time limit");
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            switch (intent.getAction()) {
            case ACTION_CANCEL:
                onCancel();
                break;
            }
        } else {
            runService();
        }

        return START_NOT_STICKY;
    }

    @SuppressLint("WakelockTimeout")
    private void runService() {
        try {
            if (isRunning.get() || (backgroundEngine != null && !backgroundEngine.getDartExecutor().isExecutingDart())) {
                Log.v(TAG, "Service already running, using existing service");
                return;
            }

            Log.v(TAG, "Starting flutter engine for background service");
//            getLock(getApplicationContext()).acquire();

            updateNotificationInfo();

            FlutterLoader flutterLoader = FlutterInjector.instance().flutterLoader();
            // initialize flutter if it's not initialized yet
            if (!flutterLoader.initialized()) {
                flutterLoader.startInitialization(getApplicationContext());
            }

            flutterLoader.ensureInitializationComplete(getApplicationContext(), null);

            isRunning.set(true);
            backgroundEngine = new FlutterEngine(this);

            // remove FlutterBackgroundServicePlugin (because its only for UI)
            backgroundEngine.getPlugins().remove(FlutterBackgroundServicePlugin.class);

            backgroundEngine.getServiceControlSurface().attachToService(BackgroundService.this, null, config.isForeground());

            methodChannel = new MethodChannel(backgroundEngine.getDartExecutor().getBinaryMessenger(), "id.flutter/background_service_android_bg", JSONMethodCodec.INSTANCE);
            methodChannel.setMethodCallHandler(this);

            dartEntrypoint = new DartExecutor.DartEntrypoint(flutterLoader.findAppBundlePath(), "package:flutter_background_service_android/flutter_background_service_android.dart", "entrypoint");

            final List<String> args = new ArrayList<>();
            long backgroundHandle = config.getBackgroundHandle();
            args.add(String.valueOf(backgroundHandle));


            backgroundEngine.getDartExecutor().executeDartEntrypoint(dartEntrypoint, args);

        } catch (UnsatisfiedLinkError e) {
            notificationContent = "Error " + e.getMessage();
            updateNotificationInfo();

            Log.w(TAG, "UnsatisfiedLinkError: After a reboot this may happen for a short period and it is ok to ignore then!" + e.getMessage());
        }
    }

    public void receiveData(JSONObject data) {
        if (methodChannel == null) return;
        try {
            final JSONObject arg = data;
            mainHandler.post(() -> {
                if (methodChannel == null) return;
                methodChannel.invokeMethod("onReceiveData", arg);
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @SuppressLint("WakelockTimeout")
    @Override
    public void onMethodCall(@NonNull MethodCall call, @NonNull MethodChannel.Result result) {
        String method = call.method;

        try {
            if (method.equalsIgnoreCase("setNotificationInfo")) {
                JSONObject arg = (JSONObject) call.arguments;
                if (arg.has("title")) {
                    notificationTitle = arg.getString("title");
                    notificationContent = arg.isNull("content") ? null : arg.getString("content");
                    updateNotificationInfo();
                    result.success(true);
                }
                return;
            }

            if (method.equalsIgnoreCase("setForegroundMode")) {
                JSONObject arg = (JSONObject) call.arguments;
                boolean value = arg.getBoolean("value");
                config.setIsForeground(value);
                if (value) {
                    updateNotificationInfo();
                    backgroundEngine.getServiceControlSurface().onMoveToForeground();
                } else {
                    stopForeground(true);
                    backgroundEngine.getServiceControlSurface().onMoveToBackground();
                }

                result.success(true);
                return;
            }

            if (method.equalsIgnoreCase("isForegroundMode")) {
                boolean value = config.isForeground();
                result.success(value);
                return;
            }

            if (method.equalsIgnoreCase("stopService")) {
                stopSelf();
                result.success(true);
                return;
            }

            if (method.equalsIgnoreCase("sendData")) {
                try {
                    if (FlutterBackgroundServicePlugin.mainPipe.hasListener()){
                        FlutterBackgroundServicePlugin.mainPipe.invoke((JSONObject) call.arguments);
                    }

                    result.success(true);
                } catch (Exception e) {
                    result.error("send-data-failure", e.getMessage(), e);
                }
                return;
            }

            if(method.equalsIgnoreCase("openApp")){
                try{
                    String packageName=  getPackageName();
                    Intent launchIntent= getPackageManager().getLaunchIntentForPackage(packageName);
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);

                        startActivity(launchIntent);
                        result.success(true);

                    }
                }catch (Exception e){
                    result.error("open app failure", e.getMessage(),e);

                }
                return;

            }

            if (method.equalsIgnoreCase("pauseWakeLock")) {
                Log.i(TAG, "Wake lock paused");
                wakeLock.release();
                result.success(true);
                return;
            }

            if (method.equalsIgnoreCase("resumeWakeLock")) {
                Log.i(TAG, "Wake lock resumed");
                wakeLock.acquire();
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
        try {
            if (FlutterBackgroundServicePlugin.servicePipe.hasListener()){
                FlutterBackgroundServicePlugin.servicePipe.invoke(new JSONObject(Map.of(
                    "method", "cancel"
                )));
            }
        } catch (Exception e) {
            Log.e(TAG, e.getMessage());
            e.printStackTrace();
        }
    }
}
