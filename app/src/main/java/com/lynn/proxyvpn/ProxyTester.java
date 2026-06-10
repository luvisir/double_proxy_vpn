package com.lynn.proxyvpn;

import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

public final class ProxyTester {
    private static final int TIMEOUT_MS = 8000;
    private static final String TEST_HOST = "api.ipify.org";
    private static final int TEST_PORT = 443;

    private ProxyTester() {
    }

    public static String test(ProxyConfig config) throws IOException {
        if (!config.isValid()) {
            throw new IOException("代理配置不完整");
        }

        testProxyPort(config);

        try {
            String ip;
            if (config.protocol.equals(ProxyConfig.PROTOCOL_SOCKS5)) {
                ip = fetchIpViaSocks5(config);
            } else {
                boolean tlsToProxy = config.protocol.equals(ProxyConfig.PROTOCOL_HTTPS);
                ip = fetchIpViaHttpConnect(config, tlsToProxy);
            }
            return "代理端口可连接\n出口 IP: " + ip;
        } catch (IOException e) {
            throw new IOException(
                    "代理端口可连接，但代理请求失败: " + simpleError(e)
                            + "\n常见原因: 协议选错、用户名密码错误、代理服务商限制了设备/IP 白名单。"
            );
        }
    }

    private static void testProxyPort(ProxyConfig config) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(config.host, config.port), TIMEOUT_MS);
        } catch (IOException e) {
            throw new IOException(
                    "代理端口无法连接: " + simpleError(e)
                            + "\n请检查: host/端口是否正确、手机网络是否能访问该代理、代理服务商是否要求白名单、防火墙是否放行。"
            );
        }
    }

    private static String fetchIpViaHttpConnect(ProxyConfig config, boolean tlsToProxy) throws IOException {
        Socket tcpSocket = new Socket();
        tcpSocket.connect(new InetSocketAddress(config.host, config.port), TIMEOUT_MS);
        tcpSocket.setSoTimeout(TIMEOUT_MS);

        Socket proxySocket = tcpSocket;
        if (tlsToProxy) {
            proxySocket = ((SSLSocketFactory) SSLSocketFactory.getDefault())
                    .createSocket(tcpSocket, config.host, config.port, true);
            ((SSLSocket) proxySocket).startHandshake();
        }

        StringBuilder request = new StringBuilder();
        request.append("CONNECT ").append(TEST_HOST).append(":").append(TEST_PORT).append(" HTTP/1.1\r\n");
        request.append("Host: ").append(TEST_HOST).append(":").append(TEST_PORT).append("\r\n");
        request.append("Proxy-Connection: Keep-Alive\r\n");
        if (config.username != null && !config.username.isEmpty()) {
            String raw = config.username + ":" + (config.password == null ? "" : config.password);
            String encoded = Base64.encodeToString(raw.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            request.append("Proxy-Authorization: Basic ").append(encoded).append("\r\n");
        }
        request.append("\r\n");

        OutputStream output = proxySocket.getOutputStream();
        output.write(request.toString().getBytes(StandardCharsets.UTF_8));
        output.flush();

        HttpResponse connectResponse = readHttpHeaderResponse(proxySocket.getInputStream());
        if (connectResponse.statusCode == 407) {
            throw new IOException("代理认证失败 HTTP 407，请检查用户名密码");
        }
        if (connectResponse.statusCode < 200 || connectResponse.statusCode >= 300) {
            throw new IOException("CONNECT 失败: " + connectResponse.statusLine);
        }

        return fetchIpOverTls(proxySocket);
    }

    private static String fetchIpViaSocks5(ProxyConfig config) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(config.host, config.port), TIMEOUT_MS);
        socket.setSoTimeout(TIMEOUT_MS);

        InputStream input = socket.getInputStream();
        OutputStream output = socket.getOutputStream();
        boolean hasAuth = config.username != null && !config.username.isEmpty();

        if (hasAuth) {
            output.write(new byte[]{0x05, 0x02, 0x00, 0x02});
        } else {
            output.write(new byte[]{0x05, 0x01, 0x00});
        }
        output.flush();

        int version = input.read();
        int method = input.read();
        if (version != 0x05) {
            throw new IOException("SOCKS5 握手失败");
        }
        if (method == 0x02) {
            byte[] user = config.username.getBytes(StandardCharsets.UTF_8);
            byte[] pass = (config.password == null ? "" : config.password).getBytes(StandardCharsets.UTF_8);
            if (user.length > 255 || pass.length > 255) {
                throw new IOException("SOCKS5 用户名或密码过长");
            }
            output.write(0x01);
            output.write(user.length);
            output.write(user);
            output.write(pass.length);
            output.write(pass);
            output.flush();
            if (input.read() != 0x01 || input.read() != 0x00) {
                throw new IOException("SOCKS5 用户名密码认证失败");
            }
        } else if (method != 0x00) {
            throw new IOException("SOCKS5 不支持当前认证方式");
        }

        byte[] host = TEST_HOST.getBytes(StandardCharsets.UTF_8);
        output.write(new byte[]{0x05, 0x01, 0x00, 0x03, (byte) host.length});
        output.write(host);
        output.write((TEST_PORT >> 8) & 0xff);
        output.write(TEST_PORT & 0xff);
        output.flush();

        byte[] header = readExact(input, 4);
        if (header[1] != 0x00) {
            throw new IOException("SOCKS5 CONNECT 失败，状态码: " + (header[1] & 0xff));
        }
        skipSocksBindAddress(input, header[3] & 0xff);

        return fetchIpOverTls(socket);
    }

    private static String fetchIpOverTls(Socket socket) throws IOException {
        SSLSocket tlsSocket = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                .createSocket(socket, TEST_HOST, TEST_PORT, true);
        tlsSocket.startHandshake();
        tlsSocket.setSoTimeout(TIMEOUT_MS);

        OutputStream output = tlsSocket.getOutputStream();
        output.write(("GET / HTTP/1.1\r\nHost: " + TEST_HOST + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        output.flush();

        HttpResponse response = readHttpResponse(tlsSocket.getInputStream());
        if (response.statusCode < 200 || response.statusCode >= 300) {
            throw new IOException("出口 IP 请求失败: " + response.statusLine);
        }
        if (response.body.trim().isEmpty()) {
            throw new IOException("出口 IP 响应为空");
        }
        return response.body.trim();
    }

    private static HttpResponse readHttpResponse(InputStream input) throws IOException {
        String raw = readAll(input);
        int headerEnd = raw.indexOf("\r\n\r\n");
        if (headerEnd < 0) {
            throw new IOException("返回内容不是有效 HTTP 响应: " + raw);
        }

        String header = raw.substring(0, headerEnd);
        String body = raw.substring(headerEnd + 4).trim();
        String[] lines = header.split("\r\n");
        if (lines.length == 0) {
            throw new IOException("返回空响应头");
        }

        int statusCode = 0;
        String[] statusParts = lines[0].split(" ");
        if (statusParts.length >= 2) {
            try {
                statusCode = Integer.parseInt(statusParts[1]);
            } catch (NumberFormatException ignored) {
            }
        }
        if (statusCode == 0) {
            throw new IOException("无法解析响应状态: " + lines[0]);
        }
        return new HttpResponse(statusCode, lines[0], body);
    }

    private static HttpResponse readHttpHeaderResponse(InputStream input) throws IOException {
        byte[] marker = new byte[]{'\r', '\n', '\r', '\n'};
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int matched = 0;
        while (true) {
            int value = input.read();
            if (value == -1) {
                throw new IOException("连接提前关闭");
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
            if (output.size() > 8192) {
                throw new IOException("响应头过大");
            }
        }
        String raw = new String(output.toByteArray(), StandardCharsets.UTF_8);
        String[] lines = raw.split("\r\n");
        if (lines.length == 0) {
            throw new IOException("返回空响应头");
        }
        int statusCode = 0;
        String[] statusParts = lines[0].split(" ");
        if (statusParts.length >= 2) {
            try {
                statusCode = Integer.parseInt(statusParts[1]);
            } catch (NumberFormatException ignored) {
            }
        }
        if (statusCode == 0) {
            throw new IOException("无法解析响应状态: " + lines[0]);
        }
        return new HttpResponse(statusCode, lines[0], "");
    }

    private static String readAll(InputStream input) throws IOException {
        byte[] buffer = new byte[4096];
        StringBuilder builder = new StringBuilder();
        int read;
        while ((read = input.read(buffer)) != -1) {
            builder.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
        }
        return builder.toString();
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

    private static void skipSocksBindAddress(InputStream input, int atyp) throws IOException {
        int addressLength;
        if (atyp == 0x01) {
            addressLength = 4;
        } else if (atyp == 0x03) {
            addressLength = input.read();
            if (addressLength < 0) {
                throw new IOException("SOCKS5 响应不完整");
            }
        } else if (atyp == 0x04) {
            addressLength = 16;
        } else {
            throw new IOException("SOCKS5 地址类型未知: " + atyp);
        }
        readExact(input, addressLength + 2);
    }

    private static String simpleError(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return throwable.getClass().getSimpleName();
        }
        return message;
    }

    private static final class HttpResponse {
        final int statusCode;
        final String statusLine;
        final String body;

        HttpResponse(int statusCode, String statusLine, String body) {
            this.statusCode = statusCode;
            this.statusLine = statusLine;
            this.body = body;
        }
    }
}
