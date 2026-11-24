package com.yuqiplus.androidsmb;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class ServerService extends Service {
    static final String STOP = "com.yuqiplus.androidsmb.STOP";
    private static final String CHANNEL = "sharing";
    private static final int NOTIFICATION_ID = 1445;

    enum Stage { STOPPED, WAITING_FOR_WIFI, STARTING, RUNNING, ERROR }

    record Status(Stage stage, String host, int port, String message) {
        boolean active() {
            return stage == Stage.WAITING_FOR_WIFI || stage == Stage.STARTING || stage == Stage.RUNNING;
        }

        String address() {
            return "smb://" + host + ":" + port + "/" + Settings.SHARE;
        }
    }

    static Status status = new Status(Stage.STOPPED, "", 0, "");
    static Runnable statusListener;

    private final Handler main = new Handler(Looper.getMainLooper());
    private ConnectivityManager connectivity;
    private ConnectivityManager.NetworkCallback callback;
    private final Map<Network, WifiEndpoint> wifiEndpoints = new LinkedHashMap<>();
    private Settings settings;
    private WifiEndpoint endpoint;
    private Process process;
    private Process stopping;
    private Runnable startupTimeout;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && STOP.equals(intent.getAction())) {
            finish("");
            return START_NOT_STICKY;
        }
        if (callback != null) {
            return START_STICKY;
        }

        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel(
            CHANNEL, "SMB 文件共享", NotificationManager.IMPORTANCE_LOW));
        Notification starting = notification("正在启动共享");
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, starting, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, starting);
        }

        try {
            settings = Settings.load(this);
            connectivity = getSystemService(ConnectivityManager.class);
            waitingForWifi();
            watchWifi();
            return START_STICKY;
        } catch (Exception exception) {
            Log.e("AndroidSMB", "Could not start the SMB service", exception);
            finish("无法启动 SMB 服务。");
            return START_NOT_STICKY;
        }
    }

    private void watchWifi() {
        ConnectivityManager.NetworkCallback watcher = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onLinkPropertiesChanged(Network network, LinkProperties properties) {
                updateWifi(network, WifiEndpoint.from(network, properties));
            }

            @Override
            public void onLost(Network network) {
                updateWifi(network, null);
            }
        };
        connectivity.registerNetworkCallback(new NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), watcher, main);
        callback = watcher;
    }

    private void updateWifi(Network network, WifiEndpoint connection) {
        if (callback == null) {
            return;
        }
        if (connection == null) {
            wifiEndpoints.remove(network);
        } else {
            wifiEndpoints.put(network, connection);
        }
        WifiEndpoint next = endpoint == null ? null : wifiEndpoints.get(endpoint.network());
        if (next == null && !wifiEndpoints.isEmpty()) {
            next = wifiEndpoints.values().iterator().next();
        }
        if (Objects.equals(endpoint, next)) {
            return;
        }

        stopEngine();
        endpoint = next;
        if (next == null) {
            waitingForWifi();
        } else if (stopping == null) {
            startEngine();
        } else {
            publish(new Status(Stage.STARTING, next.host(), settings.port, ""));
            updateNotification("正在恢复共享");
        }
    }

    private void waitingForWifi() {
        publish(new Status(Stage.WAITING_FOR_WIFI, "", settings.port, ""));
        updateNotification("正在等待 Wi-Fi");
    }

    private void startEngine() {
        publish(new Status(Stage.STARTING, endpoint.host(), settings.port, ""));
        updateNotification("正在启动共享");
        try {
            launch();
        } catch (Exception exception) {
            Log.e("AndroidSMB", "Could not start the SMB engine", exception);
            finish("无法启动 SMB 服务。");
        }
    }

    private void launch() throws Exception {
        File executable = new File(getApplicationInfo().nativeLibraryDir, "libsmbserver.so");
        JSONObject options = new JSONObject()
            .put("host", endpoint.host())
            .put("subnet", endpoint.subnet())
            .put("allowedNetworks", settings.allowedNetworks)
            .put("port", settings.port)
            .put("user", settings.user)
            .put("password", settings.password)
            .put("directory", Settings.directory(this).getAbsolutePath());
        Process child = new ProcessBuilder(executable.getAbsolutePath())
            .redirectErrorStream(true)
            .start();
        process = child;
        startupTimeout = () -> {
            if (process == child && status.stage() == Stage.STARTING) {
                finish("SMB 服务启动超时。");
            }
        };
        main.postDelayed(startupTimeout, 10_000);
        new Thread(() -> observe(child), "smb-process").start();
        child.getOutputStream().write((options + "\n").getBytes(StandardCharsets.UTF_8));
        child.getOutputStream().flush();
    }

    private void observe(Process child) {
        String reason = "SMB 服务已退出。";
        try (BufferedReader output = new BufferedReader(new InputStreamReader(
                child.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = output.readLine()) != null) {
                switch (line) {
                    case "READY" -> main.post(() -> ready(child));
                    case "INVALID_CLIENT_NETWORKS" ->
                        reason = "其他网段须填写内网 IPv4 或 CIDR，比如 10.20.30.45 或 10.20.30.0/24。";
                    case "PORT_IN_USE" -> reason = "此端口已被占用，请更换端口后重试。";
                    case "BIND_FAILED" -> reason = "无法监听当前 Wi-Fi 地址，请检查局域网权限。";
                    default -> { }
                }
            }
        } catch (Exception exception) {
            Log.e("AndroidSMB", "Could not read the SMB process status", exception);
        }
        try {
            child.waitFor();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        String message = reason;
        main.post(() -> {
            if (stopping == child) {
                stopping = null;
                if (callback != null && endpoint != null) {
                    startEngine();
                }
            } else if (process == child) {
                finish(message);
            }
        });
    }

    private void ready(Process child) {
        if (process != child || status.stage() != Stage.STARTING) {
            return;
        }
        main.removeCallbacks(startupTimeout);
        publish(new Status(Stage.RUNNING, status.host(), status.port(), ""));
        updateNotification(status.address());
    }

    private void updateNotification(String message) {
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification(message));
    }

    private Notification notification(String message) {
        int flags = PendingIntent.FLAG_IMMUTABLE;
        PendingIntent open = PendingIntent.getActivity(this, 0,
            new Intent(this, MainActivity.class), flags);
        PendingIntent stop = PendingIntent.getService(this, 1,
            new Intent(this, ServerService.class).setAction(STOP), flags);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_share)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(message)
            .setContentIntent(open)
            .addAction(new Notification.Action.Builder(null, "停止共享", stop).build())
            .setOngoing(true);
        if (Build.VERSION.SDK_INT >= 31) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return builder.build();
    }

    private void finish(String error) {
        release();
        publish(new Status(error.isEmpty() ? Stage.STOPPED : Stage.ERROR, "", 0, error));
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void release() {
        if (callback != null) {
            connectivity.unregisterNetworkCallback(callback);
            callback = null;
        }
        wifiEndpoints.clear();
        endpoint = null;
        stopEngine();
    }

    private void stopEngine() {
        if (startupTimeout != null) {
            main.removeCallbacks(startupTimeout);
            startupTimeout = null;
        }
        if (process != null) {
            stopping = process;
            process = null;
            stopping.destroyForcibly();
        }
    }

    @Override
    public void onDestroy() {
        release();
        if (status.active()) {
            publish(new Status(Stage.STOPPED, "", 0, ""));
        }
        super.onDestroy();
    }

    private static void publish(Status value) {
        status = value;
        if (statusListener != null) {
            statusListener.run();
        }
    }
}
