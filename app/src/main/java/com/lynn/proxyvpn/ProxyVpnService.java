package com.lynn.proxyvpn;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
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
    private static final String CHANNEL_ID = "proxy_vpn";
    private ParcelFileDescriptor vpnInterface;
    private Process tun2SocksProcess;
    private LocalSocksProxyBridge localProxyBridge;
    private int rawTunFd = -1;
    private volatile boolean stopping;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopVpn();
            return START_NOT_STICKY;
        }

        startForeground(1, notification());
        startVpn();
        return START_STICKY;
    }

    private void startVpn() {
        if (vpnInterface != null || rawTunFd >= 0) {
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
            sendStatus("全局代理已启动");
        } catch (IOException e) {
            if (localProxyBridge != null) {
                localProxyBridge.close();
                localProxyBridge = null;
            }
            closeRawTunFd();
            sendStatus("启动失败: " + e.getMessage());
            stopSelf();
        }
    }

    private void stopVpn() {
        stopping = true;
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
        stopSelf();
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
                    stopVpn();
                }
            } catch (Exception e) {
                if (!stopping && tun2SocksProcess == process) {
                    sendStatus("内核监控失败: " + e.getMessage());
                    stopVpn();
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
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                launchIntent,
                Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0
        );

        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        return builder
                .setContentTitle("Proxy VPN 正在运行")
                .setContentText("全局代理服务已启动")
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Proxy VPN",
                NotificationManager.IMPORTANCE_LOW
        );
        manager.createNotificationChannel(channel);
    }
}
