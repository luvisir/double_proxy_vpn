package com.lynn.proxyvpn;

import android.net.VpnService;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

public final class LocalSocksProxyBridge implements Closeable {
    private static final int TIMEOUT_MS = 15000;
    private static final String DOH_HOST = "cloudflare-dns.com";
    private final VpnService vpnService;
    private final ProxyConfig config;
    private final ServerSocket serverSocket;
    private final List<Closeable> closeables = Collections.synchronizedList(new ArrayList<>());
    private volatile boolean running = true;

    public LocalSocksProxyBridge(VpnService vpnService, ProxyConfig config) throws IOException {
        this.vpnService = vpnService;
        this.config = config;
        this.serverSocket = new ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"));
    }

    public int port() {
        return serverSocket.getLocalPort();
    }

    public void start() {
        Thread thread = new Thread(this::acceptLoop, "local-socks-bridge");
        thread.start();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                closeables.add(socket);
                new Thread(() -> handleClient(socket), "local-socks-client").start();
            } catch (IOException e) {
                if (running) {
                    break;
                }
            }
        }
    }

    private void handleClient(Socket client) {
        try {
            client.setSoTimeout(TIMEOUT_MS);
            InputStream input = client.getInputStream();
            OutputStream output = client.getOutputStream();
            readSocksGreeting(input);
            output.write(new byte[]{0x05, 0x00});
            output.flush();

            SocksRequest request = readSocksRequest(input);
            if (request.command == 0x01) {
                handleConnect(client, output, request);
            } else if (request.command == 0x03) {
                handleUdpAssociate(client, output);
            } else {
                writeSocksFailure(output);
            }
        } catch (IOException e) {
            closeQuietly(client);
        }
    }

    private void handleConnect(Socket client, OutputStream clientOutput, SocksRequest request) throws IOException {
        Socket upstream = connectUpstream(request.host, request.port);
        closeables.add(upstream);
        writeSocksSuccess(clientOutput, "127.0.0.1", 0);
        Thread up = new Thread(() -> pipe(client, upstream), "local-socks-up");
        Thread down = new Thread(() -> pipe(upstream, client), "local-socks-down");
        up.start();
        down.start();
    }

    private void handleUdpAssociate(Socket control, OutputStream output) throws IOException {
        DatagramSocket udpSocket = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"));
        vpnService.protect(udpSocket);
        closeables.add(udpSocket);
        writeSocksSuccess(output, "127.0.0.1", udpSocket.getLocalPort());

        Thread keepAlive = new Thread(() -> {
            try {
                while (running && !control.isClosed() && control.getInputStream().read() != -1) {
                    // The TCP association staying open is the lifetime signal.
                }
            } catch (IOException ignored) {
            } finally {
                closeQuietly(udpSocket);
            }
        }, "local-socks-udp-control");
        keepAlive.start();

        Thread udpThread = new Thread(() -> relayUdp(udpSocket), "local-socks-udp");
        udpThread.start();
    }

    private void relayUdp(DatagramSocket udpSocket) {
        byte[] buffer = new byte[65535];
        while (running && !udpSocket.isClosed()) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                udpSocket.receive(packet);
                SocksUdpPacket udpPacket = parseSocksUdp(packet);
                if (udpPacket == null) {
                    continue;
                }

                if (udpPacket.port != 53) {
                    continue;
                }

                byte[] dnsResponse = queryDnsOverHttps(udpPacket.payload);
                byte[] wrapped = wrapSocksUdp(udpPacket.host, udpPacket.port, dnsResponse);
                DatagramPacket clientResponse = new DatagramPacket(
                        wrapped,
                        wrapped.length,
                        packet.getAddress(),
                        packet.getPort()
                );
                udpSocket.send(clientResponse);
            } catch (SocketTimeoutException ignored) {
            } catch (IOException ignored) {
            }
        }
    }

    private byte[] queryDnsOverHttps(byte[] dnsQuery) throws IOException {
        Socket upstream = connectUpstream(DOH_HOST, 443);
        closeables.add(upstream);
        SSLSocket tlsSocket = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                .createSocket(upstream, DOH_HOST, 443, true);
        tlsSocket.startHandshake();
        tlsSocket.setSoTimeout(TIMEOUT_MS);

        OutputStream output = tlsSocket.getOutputStream();
        String header = "POST /dns-query HTTP/1.1\r\n"
                + "Host: " + DOH_HOST + "\r\n"
                + "Accept: application/dns-message\r\n"
                + "Content-Type: application/dns-message\r\n"
                + "Content-Length: " + dnsQuery.length + "\r\n"
                + "Connection: close\r\n"
                + "\r\n";
        output.write(header.getBytes(StandardCharsets.UTF_8));
        output.write(dnsQuery);
        output.flush();

        HttpBinaryResponse response = readHttpBinaryResponse(tlsSocket.getInputStream());
        closeQuietly(tlsSocket);
        if (response.statusCode < 200 || response.statusCode >= 300) {
            throw new IOException("DoH 请求失败: " + response.statusLine);
        }
        return response.body;
    }

    private Socket connectUpstream(String targetHost, int targetPort) throws IOException {
        if (config.protocol.equals(ProxyConfig.PROTOCOL_SOCKS5)) {
            return connectViaSocks5(targetHost, targetPort);
        }
        return connectViaHttpProxy(targetHost, targetPort, config.protocol.equals(ProxyConfig.PROTOCOL_HTTPS));
    }

    private Socket connectViaHttpProxy(String targetHost, int targetPort, boolean tlsToProxy) throws IOException {
        Socket tcp = new Socket();
        vpnService.protect(tcp);
        tcp.connect(new InetSocketAddress(config.host, config.port), TIMEOUT_MS);
        tcp.setSoTimeout(TIMEOUT_MS);

        Socket proxySocket = tcp;
        if (tlsToProxy) {
            proxySocket = ((SSLSocketFactory) SSLSocketFactory.getDefault())
                    .createSocket(tcp, config.host, config.port, true);
            ((SSLSocket) proxySocket).startHandshake();
        }

        OutputStream output = proxySocket.getOutputStream();
        String target = targetHost + ":" + targetPort;
        StringBuilder request = new StringBuilder();
        request.append("CONNECT ").append(target).append(" HTTP/1.1\r\n");
        request.append("Host: ").append(target).append("\r\n");
        request.append("Proxy-Connection: Keep-Alive\r\n");
        if (config.username != null && !config.username.isEmpty()) {
            String raw = config.username + ":" + (config.password == null ? "" : config.password);
            String encoded = Base64.encodeToString(raw.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            request.append("Proxy-Authorization: Basic ").append(encoded).append("\r\n");
        }
        request.append("\r\n");
        output.write(request.toString().getBytes(StandardCharsets.UTF_8));
        output.flush();

        String response = readHttpHeader(proxySocket.getInputStream());
        if (!response.startsWith("HTTP/1.1 200") && !response.startsWith("HTTP/1.0 200")) {
            closeQuietly(proxySocket);
            throw new IOException("上游 HTTP CONNECT 失败: " + firstLine(response));
        }
        return proxySocket;
    }

    private Socket connectViaSocks5(String targetHost, int targetPort) throws IOException {
        Socket socket = new Socket();
        vpnService.protect(socket);
        socket.connect(new InetSocketAddress(config.host, config.port), TIMEOUT_MS);
        socket.setSoTimeout(TIMEOUT_MS);

        InputStream input = socket.getInputStream();
        OutputStream output = socket.getOutputStream();
        boolean hasAuth = config.username != null && !config.username.isEmpty();
        output.write(hasAuth ? new byte[]{0x05, 0x02, 0x00, 0x02} : new byte[]{0x05, 0x01, 0x00});
        output.flush();
        int version = input.read();
        int method = input.read();
        if (version != 0x05) {
            throw new IOException("上游 SOCKS5 握手失败");
        }
        if (method == 0x02) {
            writeSocks5Auth(output);
            if (input.read() != 0x01 || input.read() != 0x00) {
                throw new IOException("上游 SOCKS5 认证失败");
            }
        } else if (method != 0x00) {
            throw new IOException("上游 SOCKS5 不支持认证方式");
        }

        byte[] host = targetHost.getBytes(StandardCharsets.UTF_8);
        output.write(new byte[]{0x05, 0x01, 0x00, 0x03, (byte) host.length});
        output.write(host);
        output.write((targetPort >> 8) & 0xff);
        output.write(targetPort & 0xff);
        output.flush();

        byte[] header = readExact(input, 4);
        if (header[1] != 0x00) {
            throw new IOException("上游 SOCKS5 CONNECT 失败: " + (header[1] & 0xff));
        }
        skipSocksAddress(input, header[3] & 0xff);
        return socket;
    }

    private void writeSocks5Auth(OutputStream output) throws IOException {
        byte[] user = config.username.getBytes(StandardCharsets.UTF_8);
        byte[] pass = (config.password == null ? "" : config.password).getBytes(StandardCharsets.UTF_8);
        output.write(0x01);
        output.write(user.length);
        output.write(user);
        output.write(pass.length);
        output.write(pass);
        output.flush();
    }

    private static void readSocksGreeting(InputStream input) throws IOException {
        int version = input.read();
        int methods = input.read();
        if (version != 0x05 || methods <= 0) {
            throw new IOException("不是 SOCKS5 请求");
        }
        readExact(input, methods);
    }

    private static SocksRequest readSocksRequest(InputStream input) throws IOException {
        byte[] header = readExact(input, 4);
        if (header[0] != 0x05) {
            throw new IOException("SOCKS5 版本错误");
        }
        String host = readSocksHost(input, header[3] & 0xff);
        byte[] portBytes = readExact(input, 2);
        int port = ((portBytes[0] & 0xff) << 8) | (portBytes[1] & 0xff);
        return new SocksRequest(header[1] & 0xff, host, port);
    }

    private static String readSocksHost(InputStream input, int atyp) throws IOException {
        if (atyp == 0x01) {
            byte[] addr = readExact(input, 4);
            return (addr[0] & 0xff) + "." + (addr[1] & 0xff) + "." + (addr[2] & 0xff) + "." + (addr[3] & 0xff);
        }
        if (atyp == 0x03) {
            int length = input.read();
            return new String(readExact(input, length), StandardCharsets.UTF_8);
        }
        if (atyp == 0x04) {
            return InetAddress.getByAddress(readExact(input, 16)).getHostAddress();
        }
        throw new IOException("未知地址类型: " + atyp);
    }

    private static void writeSocksSuccess(OutputStream output, String host, int port) throws IOException {
        byte[] address = InetAddress.getByName(host).getAddress();
        output.write(new byte[]{0x05, 0x00, 0x00, 0x01});
        output.write(address);
        output.write((port >> 8) & 0xff);
        output.write(port & 0xff);
        output.flush();
    }

    private static void writeSocksFailure(OutputStream output) throws IOException {
        output.write(new byte[]{0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0});
        output.flush();
    }

    private static SocksUdpPacket parseSocksUdp(DatagramPacket packet) throws IOException {
        byte[] data = packet.getData();
        int offset = packet.getOffset();
        int length = packet.getLength();
        if (length < 10 || data[offset] != 0 || data[offset + 1] != 0 || data[offset + 2] != 0) {
            return null;
        }
        int cursor = offset + 3;
        int atyp = data[cursor++] & 0xff;
        String host;
        if (atyp == 0x01) {
            host = (data[cursor] & 0xff) + "." + (data[cursor + 1] & 0xff) + "." + (data[cursor + 2] & 0xff) + "." + (data[cursor + 3] & 0xff);
            cursor += 4;
        } else if (atyp == 0x03) {
            int hostLength = data[cursor++] & 0xff;
            host = new String(data, cursor, hostLength, StandardCharsets.UTF_8);
            cursor += hostLength;
        } else if (atyp == 0x04) {
            byte[] address = new byte[16];
            System.arraycopy(data, cursor, address, 0, 16);
            host = InetAddress.getByAddress(address).getHostAddress();
            cursor += 16;
        } else {
            return null;
        }
        int port = ((data[cursor] & 0xff) << 8) | (data[cursor + 1] & 0xff);
        cursor += 2;
        int payloadLength = offset + length - cursor;
        byte[] payload = new byte[payloadLength];
        System.arraycopy(data, cursor, payload, 0, payloadLength);
        return new SocksUdpPacket(host, port, payload);
    }

    private static byte[] wrapSocksUdp(String host, int port, byte[] payload) throws IOException {
        byte[] address = InetAddress.getByName(host).getAddress();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(new byte[]{0, 0, 0});
        output.write(address.length == 4 ? 0x01 : 0x04);
        output.write(address);
        output.write((port >> 8) & 0xff);
        output.write(port & 0xff);
        output.write(payload);
        return output.toByteArray();
    }

    private static String readHttpHeader(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] marker = new byte[]{'\r', '\n', '\r', '\n'};
        int matched = 0;
        while (true) {
            int value = input.read();
            if (value == -1) {
                throw new IOException("HTTP 响应提前关闭");
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
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private static HttpBinaryResponse readHttpBinaryResponse(InputStream input) throws IOException {
        String header = readHttpHeader(input);
        String[] lines = header.split("\r\n");
        if (lines.length == 0) {
            throw new IOException("HTTP 响应为空");
        }

        int statusCode = 0;
        String[] parts = lines[0].split(" ");
        if (parts.length >= 2) {
            try {
                statusCode = Integer.parseInt(parts[1]);
            } catch (NumberFormatException ignored) {
            }
        }
        if (statusCode == 0) {
            throw new IOException("无法解析 HTTP 状态: " + lines[0]);
        }

        int contentLength = -1;
        boolean chunked = false;
        for (String line : lines) {
            int separator = line.indexOf(':');
            if (separator <= 0) {
                continue;
            }
            String name = line.substring(0, separator).trim();
            String value = line.substring(separator + 1).trim();
            if ("Content-Length".equalsIgnoreCase(name)) {
                try {
                    contentLength = Integer.parseInt(value);
                } catch (NumberFormatException ignored) {
                }
            } else if ("Transfer-Encoding".equalsIgnoreCase(name) && value.toLowerCase().contains("chunked")) {
                chunked = true;
            }
        }

        byte[] body;
        if (chunked) {
            body = readChunkedBody(input);
        } else if (contentLength >= 0) {
            body = readExact(input, contentLength);
        } else {
            body = readRemaining(input);
        }
        return new HttpBinaryResponse(statusCode, lines[0], body);
    }

    private static byte[] readChunkedBody(InputStream input) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        while (true) {
            String line = readAsciiLine(input);
            int semicolon = line.indexOf(';');
            String sizeText = semicolon >= 0 ? line.substring(0, semicolon) : line;
            int chunkSize = Integer.parseInt(sizeText.trim(), 16);
            if (chunkSize == 0) {
                readAsciiLine(input);
                break;
            }
            body.write(readExact(input, chunkSize));
            readExact(input, 2);
        }
        return body.toByteArray();
    }

    private static String readAsciiLine(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int previous = -1;
        while (true) {
            int value = input.read();
            if (value == -1) {
                throw new IOException("HTTP 响应提前关闭");
            }
            if (previous == '\r' && value == '\n') {
                byte[] data = output.toByteArray();
                return new String(data, 0, Math.max(0, data.length - 1), StandardCharsets.US_ASCII);
            }
            output.write(value);
            previous = value;
        }
    }

    private static byte[] readRemaining(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static String firstLine(String text) {
        int index = text.indexOf("\r\n");
        return index >= 0 ? text.substring(0, index) : text;
    }

    private static void skipSocksAddress(InputStream input, int atyp) throws IOException {
        if (atyp == 0x01) {
            readExact(input, 6);
        } else if (atyp == 0x03) {
            int length = input.read();
            readExact(input, length + 2);
        } else if (atyp == 0x04) {
            readExact(input, 18);
        } else {
            throw new IOException("未知地址类型: " + atyp);
        }
    }

    private static byte[] readExact(InputStream input, int length) throws IOException {
        byte[] data = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = input.read(data, offset, length - offset);
            if (read == -1) {
                throw new IOException("连接提前关闭");
            }
            offset += read;
        }
        return data;
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
        synchronized (closeables) {
            for (Closeable closeable : closeables) {
                closeQuietly(closeable);
            }
            closeables.clear();
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

    private static final class SocksRequest {
        final int command;
        final String host;
        final int port;

        SocksRequest(int command, String host, int port) {
            this.command = command;
            this.host = host;
            this.port = port;
        }
    }

    private static final class SocksUdpPacket {
        final String host;
        final int port;
        final byte[] payload;

        SocksUdpPacket(String host, int port, byte[] payload) {
            this.host = host;
            this.port = port;
            this.payload = payload;
        }
    }

    private static final class HttpBinaryResponse {
        final int statusCode;
        final String statusLine;
        final byte[] body;

        HttpBinaryResponse(int statusCode, String statusLine, byte[] body) {
            this.statusCode = statusCode;
            this.statusLine = statusLine;
            this.body = body;
        }
    }
}
