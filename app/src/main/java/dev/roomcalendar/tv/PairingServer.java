package dev.roomcalendar.tv;

import android.util.Base64;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

/** Temporary local pairing page. No external service, account or DB. Plain HTTP: trusted LAN only. */
final class PairingServer implements AutoCloseable {
    interface Listener { void onSubmitted(String feedUrl); }
    private final Listener listener;
    private ServerSocket server;
    private String token;
    private String address;
    private volatile long deadline;
    private volatile boolean running;
    private static final int PORT = 8765;

    PairingServer(Listener listener) { this.listener = listener; }

    synchronized String start() {
        close();
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        token = Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
        deadline = System.currentTimeMillis() + 15 * 60_000L;
        try {
            address = findLanAddress();
            if (address == null) return "";
            server = new ServerSocket(PORT, 8, InetAddress.getByName("0.0.0.0"));
            running = true;
            new Thread(this::runServer, "room-calendar-pair").start();
            return "http://" + address + ":" + PORT + "/connect?t=" + token;
        } catch (Exception e) { close(); return ""; }
    }

    private void runServer() {
        while (running && System.currentTimeMillis() < deadline) {
            try (Socket socket = server.accept()) {
                socket.setSoTimeout(5000);
                handle(socket);
            } catch (Exception ignored) {
                if (!running) break;
            }
        }
        close();
    }

    private void handle(Socket socket) throws Exception {
        BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        BufferedWriter output = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        String request = input.readLine();
        if (request == null || request.length() > 4096) return;
        String[] first = request.split(" ");
        if (first.length < 2) return;
        String method = first[0], path = first[1];
        int contentLength = 0;
        String line;
        while ((line = input.readLine()) != null && !line.isEmpty()) {
            if (line.length() > 8192) return;
            int at = line.indexOf(':');
            if (at > 0 && line.substring(0, at).equalsIgnoreCase("Content-Length")) {
                try { contentLength = Integer.parseInt(line.substring(at + 1).trim()); }
                catch (NumberFormatException e) { return; }
            }
        }
        if (!path.startsWith("/connect") || !token.equals(parseQuery(path).get("t")) ||
            System.currentTimeMillis() > deadline) {
            reply(output, 403, "Pairing link expired. Reopen pairing on the TV.");
            return;
        }
        if ("POST".equals(method)) {
            if (contentLength < 1 || contentLength > 12000) {
                reply(output, 413, "Invalid request size"); return;
            }
            char[] chars = new char[contentLength];
            int total = 0;
            while (total < chars.length) {
                int n = input.read(chars, total, chars.length - total);
                if (n < 0) break;
                total += n;
            }
            Map<String,String> values = parseParams(new String(chars, 0, total));
            String url = values.getOrDefault("url", "").trim();
            if (!MainActivity.isValidFeedUrl(url)) {
                reply(output, 400, page("That URL doesn't look like a Google Calendar secret iCal link.", true));
                return;
            }
            reply(output, 200, page("Calendar paired! You can close this page and look at your TV.", false));
            listener.onSubmitted(url);
            close();
        } else if ("GET".equals(method)) {
            reply(output, 200, page("", true));
        } else reply(output, 405, "Method not allowed");
    }

    private static String page(String notice, boolean showForm) {
        String intro = "<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>" +
            "<meta name='referrer' content='no-referrer'><title>Room Calendar pairing</title>" +
            "<style>body{background:#f7f8fc;color:#202a3b;font-family:system-ui,sans-serif;margin:0;padding:24px}" +
            ".card{background:white;max-width:540px;margin:9vh auto;padding:28px;border-radius:20px;box-shadow:0 18px 50px #172d5115}" +
            "h1{font-size:28px}p{line-height:1.6;color:#556377}input{width:100%;box-sizing:border-box;padding:14px;border:1px solid #ccd4e3;border-radius:9px;font-size:15px}" +
            "button{background:#3878ed;color:white;border:0;border-radius:9px;padding:14px 22px;margin-top:16px;font-size:16px;font-weight:bold}" +
            "small{display:block;color:#7a879a;margin-top:22px}</style></head><body><div class='card'>" +
            "<h1>Connect your Google Calendar</h1>" +
            "<p>On a computer, open Google Calendar → Settings → select your calendar → Integrate calendar → " +
            "<b>Secret address in iCal format</b>. Copy that address and paste it below.</p>";
        if (!notice.isEmpty()) intro += "<p><b>" + notice + "</b></p>";
        if (showForm) intro += "<form method='POST'><label for='url'>Private .ics address</label>" +
            "<input id='url' name='url' type='url' inputmode='url' placeholder='https://calendar.google.com/calendar/ical/…' required autocomplete='off'>" +
            "<button type='submit'>Connect calendar</button></form>";
        return intro + "<small>This page runs directly on your TV over your local Wi-Fi. Pair only on a trusted private network. " +
            "The calendar URL is a secret; never share it with other people.</small></div></body></html>";
    }

    private static Map<String,String> parseQuery(String path) {
        int q = path.indexOf('?');
        return parseParams(q >= 0 ? path.substring(q + 1) : "");
    }
    private static Map<String,String> parseParams(String params) {
        Map<String,String> result = new HashMap<>();
        for (String param : params.split("&")) {
            int eq = param.indexOf('=');
            if (eq > 0) {
                try { result.put(URLDecoder.decode(param.substring(0,eq), "UTF-8"),
                                  URLDecoder.decode(param.substring(eq+1), "UTF-8")); }
                catch (Exception ignored) { }
            }
        }
        return result;
    }

    private static void reply(BufferedWriter out, int code, String body) throws Exception {
        byte[] utf8 = body.getBytes(StandardCharsets.UTF_8);
        out.write("HTTP/1.1 " + code + " " + (code == 200 ? "OK" : "Error") + "\r\n");
        out.write("Content-Type: text/html; charset=utf-8\r\n");
        out.write("Cache-Control: no-store\r\n");
        out.write("Referrer-Policy: no-referrer\r\n");
        out.write("X-Content-Type-Options: nosniff\r\n");
        out.write("Connection: close\r\n");
        out.write("Content-Length: " + utf8.length + "\r\n\r\n");
        out.flush();
        // Write raw UTF-8 through the socket writer; content length matches UTF-8 bytes.
        out.write(body);
        out.flush();
    }

    private static String findLanAddress() {
        try {
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nic.isUp() || nic.isLoopback()) continue;
                for (InetAddress a : Collections.list(nic.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress() && !a.isLoopbackAddress())
                        return a.getHostAddress();
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    @Override public synchronized void close() {
        running = false;
        try { if (server != null) server.close(); } catch (Exception ignored) { }
        server = null;
    }
}
