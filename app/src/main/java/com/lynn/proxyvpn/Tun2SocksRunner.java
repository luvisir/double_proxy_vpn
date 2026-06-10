package com.lynn.proxyvpn;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

public final class Tun2SocksRunner {
    private static final String NATIVE_EXECUTABLE_NAME = "libtun2socks.so";

    private Tun2SocksRunner() {
    }

    public static Process start(Context context, int tunFd, String proxyUrl) throws IOException {
        if (!Build.SUPPORTED_ABIS[0].contains("arm64")) {
            throw new IOException("当前测试版只内置 arm64 内核，设备架构: " + Build.SUPPORTED_ABIS[0]);
        }

        File executable = nativeExecutable(context);
        List<String> command = new ArrayList<>();
        command.add(executable.getAbsolutePath());
        command.add("-device");
        command.add("fd://0");
        command.add("-proxy");
        command.add(proxyUrl);
        command.add("-mtu");
        command.add("1500");
        command.add("-loglevel");
        command.add("info");

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        builder.redirectInput(ProcessBuilder.Redirect.INHERIT);
        return startWithTunAsStdin(builder, tunFd);
    }

    private static Process startWithTunAsStdin(ProcessBuilder builder, int tunFd) throws IOException {
        ParcelFileDescriptor tunParcelFileDescriptor = ParcelFileDescriptor.adoptFd(tunFd);
        FileDescriptor savedStdin = null;
        boolean tunDetached = false;
        try {
            savedStdin = Os.dup(FileDescriptor.in);
            Os.dup2(tunParcelFileDescriptor.getFileDescriptor(), 0);
            Process process = builder.start();
            tunParcelFileDescriptor.detachFd();
            tunDetached = true;
            return process;
        } catch (ErrnoException e) {
            throw new IOException("无法把 TUN fd 传给内核: " + e.getMessage(), e);
        } finally {
            if (savedStdin != null) {
                try {
                    Os.dup2(savedStdin, 0);
                    Os.close(savedStdin);
                } catch (ErrnoException ignored) {
                }
            }
            if (!tunDetached) {
                try {
                    tunParcelFileDescriptor.detachFd();
                } catch (IllegalStateException ignored) {
                }
            }
        }
    }

    public static String proxyUrl(ProxyConfig config) throws IOException {
        return proxyUrl(config, config.protocol.equals(ProxyConfig.PROTOCOL_SOCKS5) ? "socks5" : "http", config.host, config.port, true);
    }

    public static String localHttpProxyUrl(int port) throws IOException {
        return proxyUrl(new ProxyConfig(ProxyConfig.PROTOCOL_HTTP, "127.0.0.1", port, "", ""), "http", "127.0.0.1", port, false);
    }

    public static String localSocksProxyUrl(int port) throws IOException {
        return proxyUrl(new ProxyConfig(ProxyConfig.PROTOCOL_SOCKS5, "127.0.0.1", port, "", ""), "socks5", "127.0.0.1", port, false);
    }

    private static File nativeExecutable(Context context) throws IOException {
        File executable = new File(context.getApplicationInfo().nativeLibraryDir, NATIVE_EXECUTABLE_NAME);
        if (!executable.exists() || executable.length() == 0) {
            throw new IOException("找不到 tun2socks 内核: " + executable.getAbsolutePath());
        }
        return executable;
    }

    private static String proxyUrl(ProxyConfig config, String scheme, String host, int port, boolean includeAuth) throws IOException {
        String userInfo = null;
        if (includeAuth && config.username != null && !config.username.isEmpty()) {
            userInfo = config.username + ":" + (config.password == null ? "" : config.password);
        }
        try {
            return new URI(
                    scheme,
                    userInfo,
                    host,
                    port,
                    null,
                    null,
                    null
            ).toASCIIString();
        } catch (URISyntaxException e) {
            String auth = "";
            if (userInfo != null) {
                auth = Uri.encode(config.username) + ":" + Uri.encode(config.password == null ? "" : config.password) + "@";
            }
            return scheme + "://" + auth + host + ":" + port;
        }
    }
}
