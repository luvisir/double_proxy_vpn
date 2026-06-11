package com.lynn.proxyvpn;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.Icon;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

public class ProxyVpnService extends VpnService {
    public static final String ACTION_START = "com.lynn.proxyvpn.START";
    public static final String ACTION_STOP = "com.lynn.proxyvpn.STOP";
    public static final String ACTION_STATUS = "com.lynn.proxyvpn.STATUS";
    public static final String EXTRA_STATUS = "status";
    private static final String CHANNEL_ID = "eproxy_vpn";
    private static volatile ProxyVpnService activeService;
    private static volatile boolean running;
    private static volatile long startedAtMillis;
    private ParcelFileDescriptor vpnInterface;
    private Process tun2SocksProcess;
    private LocalSocksProxyBridge localProxyBridge;
    private int rawTunFd = -1;
    private volatile boolean stopping;
    private final Object stopLock = new Object();

    @Override
    public void onCreate() {
        super.onCreate();
        activeService = this;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopVpn(true);
            return START_NOT_STICKY;
        }

        startForeground(1, notification());
        startVpn();
        return START_STICKY;
    }

    private void startVpn() {
        if (vpnInterface != null || rawTunFd >= 0) {
            sendStatus("全局代理已启动");
            return;
        }
        stopping = false;

        Builder builder = new Builder()
                .setSession("Proxy VPN")
                .addAddress("10.10.0.2", 32)
                .addDnsServer("1.1.1.1")
                .addRoute("0.0.0.0", 0)
                .setMtu(1500);

        try {
            builder.addDisallowedApplication(getPackageName());
        } catch (PackageManager.NameNotFoundException ignored) {
        }

        vpnInterface = builder.establish();
        if (vpnInterface == null) {
            sendStatus("VPN 授权失败或系统未建立 VPN");
            stopSelf();
            return;
        }

        rawTunFd = vpnInterface.detachFd();
        vpnInterface = null;

        try {
            ProxyConfig config = ProxyConfig.load(this);
            localProxyBridge = new LocalSocksProxyBridge(this, config);
            localProxyBridge.start();
            String proxyUrl = Tun2SocksRunner.localSocksProxyUrl(localProxyBridge.port());
            tun2SocksProcess = Tun2SocksRunner.start(this, rawTunFd, proxyUrl);
            watchTun2Socks(tun2SocksProcess);
            running = true;
            startedAtMillis = System.currentTimeMillis();
            sendStatus("全局代理已启动");
        } catch (IOException e) {
            running = false;
            startedAtMillis = 0;
            if (localProxyBridge != null) {
                localProxyBridge.close();
                localProxyBridge = null;
            }
            closeRawTunFd();
            sendStatus("启动失败: " + e.getMessage());
            stopSelf();
        }
    }

    private void stopVpn(boolean stopService) {
        synchronized (stopLock) {
            stopping = true;
            running = false;
            startedAtMillis = 0;
            if (tun2SocksProcess != null) {
                tun2SocksProcess.destroy();
                tun2SocksProcess = null;
            }
            if (localProxyBridge != null) {
                localProxyBridge.close();
                localProxyBridge = null;
            }
            closeRawTunFd();
            if (vpnInterface != null) {
                try {
                    vpnInterface.close();
                } catch (IOException ignored) {
                }
                vpnInterface = null;
            }
            stopForeground(true);
            sendStatus("全局代理已停止");
            if (stopService) {
                stopSelf();
            }
        }
    }

    @Override
    public void onDestroy() {
        if (activeService == this) {
            activeService = null;
        }
        super.onDestroy();
    }

    private void watchTun2Socks(Process process) {
        Thread thread = new Thread(() -> {
            String lastLine = "";
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.trim().isEmpty()) {
                        lastLine = line;
                    }
                }
                int exitCode = process.waitFor();
                if (!stopping && tun2SocksProcess == process) {
                    sendStatus("内核退出(" + exitCode + "): " + lastLine);
                    stopVpn(true);
                }
            } catch (Exception e) {
                if (!stopping && tun2SocksProcess == process) {
                    sendStatus("内核监控失败: " + e.getMessage());
                    stopVpn(true);
                }
            }
        }, "tun2socks-watch");
        thread.start();
    }

    private void closeRawTunFd() {
        if (rawTunFd >= 0) {
            try {
                ParcelFileDescriptor.adoptFd(rawTunFd).close();
            } catch (IOException ignored) {
            }
            rawTunFd = -1;
        }
    }

    private void sendStatus(String status) {
        Intent intent = new Intent(ACTION_STATUS);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_STATUS, status);
        sendBroadcast(intent);
    }

    private Notification notification() {
        createChannel();
        Intent launchIntent = new Intent(this, MainActivity.class);
        launchIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                launchIntent,
                Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE : PendingIntent.FLAG_UPDATE_CURRENT
        );

        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        builder
                .setContentTitle("EProxy 正在运行")
                .setContentText("全局代理服务已启动")
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(pendingIntent)
                .setOngoing(true);
        if (Build.VERSION.SDK_INT >= 23) {
            builder.setLargeIcon(Icon.createWithResource(this, R.mipmap.ic_launcher));
        }
        return builder.build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "EProxy",
                NotificationManager.IMPORTANCE_LOW
        );
        manager.createNotificationChannel(channel);
    }

    public static boolean isRunning() {
        return running;
    }

    public static long getStartedAtMillis() {
        return startedAtMillis;
    }

    public static void requestStop(Context context) {
        ProxyVpnService service = activeService;
        if (service != null) {
            new Thread(() -> service.stopVpn(false), "eproxy-ui-stop").start();
            return;
        }
        Intent intent = new Intent(context, ProxyVpnService.class);
        intent.setAction(ACTION_STOP);
        context.startService(intent);
    }
}
