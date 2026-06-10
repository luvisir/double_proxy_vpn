package com.lynn.proxyvpn;

import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

public final class HttpsProxyBridge implements Closeable {
    private final ProxyConfig config;
    private final ServerSocket serverSocket;
    private final List<Socket> sockets = Collections.synchronizedList(new ArrayList<>());
    private volatile boolean running = true;
    private Thread acceptThread;

    public HttpsProxyBridge(ProxyConfig config) throws IOException {
        this.config = config;
        this.serverSocket = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
    }

    public int port() {
        return serverSocket.getLocalPort();
    }

    public void start() {
        acceptThread = new Thread(this::acceptLoop, "https-proxy-bridge");
        acceptThread.start();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket client = serverSocket.accept();
                sockets.add(client);
                Thread thread = new Thread(() -> handle(client), "https-proxy-bridge-client");
                thread.start();
            } catch (IOException e) {
                if (running) {
                    break;
                }
            }
        }
    }

    private void handle(Socket client) {
        Socket remote = null;
        try {
            client.setSoTimeout(15000);
            String clientHeader = readHeader(client.getInputStream());
            String target = parseConnectTarget(clientHeader);

            Socket tcp = new Socket(config.host, config.port);
            tcp.setSoTimeout(15000);
            remote = ((SSLSocketFactory) SSLSocketFactory.getDefault())
                    .createSocket(tcp, config.host, config.port, true);
            ((SSLSocket) remote).startHandshake();
            sockets.add(remote);

            OutputStream remoteOutput = remote.getOutputStream();
            remoteOutput.write(buildRemoteConnect(target).getBytes(StandardCharsets.UTF_8));
            remoteOutput.flush();

            String remoteHeader = readHeader(remote.getInputStream());
            client.getOutputStream().write(remoteHeader.getBytes(StandardCharsets.UTF_8));
            client.getOutputStream().flush();

            if (!remoteHeader.startsWith("HTTP/1.1 200") && !remoteHeader.startsWith("HTTP/1.0 200")) {
                closeQuietly(remote);
                closeQuietly(client);
                return;
            }

            Socket finalRemote = remote;
            Thread upstream = new Thread(() -> pipe(client, finalRemote), "https-proxy-bridge-up");
            Thread downstream = new Thread(() -> pipe(finalRemote, client), "https-proxy-bridge-down");
            upstream.start();
            downstream.start();
        } catch (IOException e) {
            closeQuietly(remote);
            closeQuietly(client);
        }
    }

    private String buildRemoteConnect(String target) {
        StringBuilder builder = new StringBuilder();
        builder.append("CONNECT ").append(target).append(" HTTP/1.1\r\n");
        builder.append("Host: ").append(target).append("\r\n");
        builder.append("Proxy-Connection: Keep-Alive\r\n");
        if (config.username != null && !config.username.isEmpty()) {
            String raw = config.username + ":" + (config.password == null ? "" : config.password);
            String encoded = Base64.encodeToString(raw.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            builder.append("Proxy-Authorization: Basic ").append(encoded).append("\r\n");
        }
        builder.append("\r\n");
        return builder.toString();
    }

    private String parseConnectTarget(String header) throws IOException {
        String[] lines = header.split("\r\n");
        if (lines.length == 0) {
            throw new IOException("empty CONNECT request");
        }
        String[] parts = lines[0].split(" ");
        if (parts.length < 3 || !"CONNECT".equalsIgnoreCase(parts[0])) {
            throw new IOException("unsupported proxy request: " + lines[0]);
        }
        return parts[1];
    }

    private static String readHeader(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] marker = new byte[]{'\r', '\n', '\r', '\n'};
        int matched = 0;
        while (true) {
            int value = input.read();
            if (value == -1) {
                throw new IOException("connection closed");
            }
            output.write(value);
            if ((byte) value == marker[matched]) {
                matched++;
                if (matched == marker.length) {
                    break;
                }
            } else {
                matched = (byte) value == marker[0] ? 1 : 0;
            }
            if (output.size() > 16384) {
                throw new IOException("header too large");
            }
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void pipe(Socket source, Socket target) {
        try {
            InputStream input = source.getInputStream();
            OutputStream output = target.getOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
                output.flush();
            }
        } catch (IOException ignored) {
        } finally {
            closeQuietly(source);
            closeQuietly(target);
        }
    }

    @Override
    public void close() {
        running = false;
        closeQuietly(serverSocket);
        synchronized (sockets) {
            for (Socket socket : sockets) {
                closeQuietly(socket);
            }
            sockets.clear();
        }
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
        }
    }
}
