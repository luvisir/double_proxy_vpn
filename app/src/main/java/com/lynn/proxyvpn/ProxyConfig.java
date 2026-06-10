package com.lynn.proxyvpn;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class ProxyConfig {
    public static final String PROTOCOL_HTTP = "HTTP";
    public static final String PROTOCOL_HTTPS = "HTTPS";
    public static final String PROTOCOL_SOCKS5 = "SOCKS5";
    private static final String KEY_PROFILES = "profiles";
    private static final String KEY_SELECTED_PROFILE_ID = "selected_profile_id";

    public final String id;
    public final String name;
    public final String protocol;
    public final String host;
    public final int port;
    public final String username;
    public final String password;

    public ProxyConfig(String protocol, String host, int port, String username, String password) {
        this("default", "代理 1", protocol, host, port, username, password);
    }

    public ProxyConfig(String id, String name, String protocol, String host, int port, String username, String password) {
        this.id = id;
        this.name = name;
        this.protocol = protocol;
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
    }

    public boolean isValid() {
        return host != null && !host.trim().isEmpty() && port > 0 && port <= 65535;
    }

    public void save(Context context) {
        saveProfile(context, this);
    }

    public String displayName() {
        if (name != null && !name.trim().isEmpty()) {
            return name.trim();
        }
        if (host != null && !host.trim().isEmpty()) {
            return protocol + " " + host + ":" + port;
        }
        return "未命名代理";
    }

    public static ProxyConfig blank(String id, int number) {
        return new ProxyConfig(id, "代理 " + number, PROTOCOL_SOCKS5, "", 1080, "", "");
    }

    public static void saveProfile(Context context, ProxyConfig profile) {
        List<ProxyConfig> profiles = loadProfiles(context);
        boolean replaced = false;
        for (int i = 0; i < profiles.size(); i++) {
            if (profiles.get(i).id.equals(profile.id)) {
                profiles.set(i, profile);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            profiles.add(profile);
        }
        saveProfiles(context, profiles);
        saveSelectedProfileId(context, profile.id);

        SharedPreferences.Editor editor = preferences(context).edit();
        editor.putString("protocol", profile.protocol);
        editor.putString("host", profile.host);
        editor.putInt("port", profile.port);
        editor.putString("username", profile.username);
        editor.putString("password", profile.password);
        editor.apply();
    }

    public static ProxyConfig load(Context context) {
        List<ProxyConfig> profiles = loadProfiles(context);
        String selectedId = loadSelectedProfileId(context);
        for (ProxyConfig profile : profiles) {
            if (profile.id.equals(selectedId)) {
                return profile;
            }
        }
        return profiles.get(0);
    }

    public static List<ProxyConfig> loadProfiles(Context context) {
        SharedPreferences prefs = preferences(context);
        String raw = prefs.getString(KEY_PROFILES, "");
        if (raw != null && !raw.isEmpty()) {
            try {
                JSONArray array = new JSONArray(raw);
                List<ProxyConfig> profiles = new ArrayList<>();
                for (int i = 0; i < array.length(); i++) {
                    JSONObject item = array.getJSONObject(i);
                    profiles.add(fromJson(item));
                }
                if (!profiles.isEmpty()) {
                    return profiles;
                }
            } catch (JSONException ignored) {
            }
        }

        List<ProxyConfig> profiles = new ArrayList<>();
        ProxyConfig migrated = new ProxyConfig(
                "profile_1",
                "代理 1",
                prefs.getString("protocol", PROTOCOL_SOCKS5),
                prefs.getString("host", ""),
                prefs.getInt("port", 1080),
                prefs.getString("username", ""),
                prefs.getString("password", "")
        );
        profiles.add(migrated);
        saveProfiles(context, profiles);
        saveSelectedProfileId(context, migrated.id);
        return profiles;
    }

    public static void saveProfiles(Context context, List<ProxyConfig> profiles) {
        JSONArray array = new JSONArray();
        for (ProxyConfig profile : profiles) {
            array.put(toJson(profile));
        }
        preferences(context).edit().putString(KEY_PROFILES, array.toString()).apply();
    }

    public static void saveSelectedProfileId(Context context, String id) {
        preferences(context).edit().putString(KEY_SELECTED_PROFILE_ID, id).apply();
    }

    public static String loadSelectedProfileId(Context context) {
        return preferences(context).getString(KEY_SELECTED_PROFILE_ID, "profile_1");
    }

    private static JSONObject toJson(ProxyConfig profile) {
        JSONObject json = new JSONObject();
        try {
            json.put("id", profile.id);
            json.put("name", profile.name);
            json.put("protocol", profile.protocol);
            json.put("host", profile.host);
            json.put("port", profile.port);
            json.put("username", profile.username);
            json.put("password", profile.password);
        } catch (JSONException ignored) {
        }
        return json;
    }

    private static ProxyConfig fromJson(JSONObject json) {
        return new ProxyConfig(
                json.optString("id", "profile_" + System.currentTimeMillis()),
                json.optString("name", "代理"),
                json.optString("protocol", PROTOCOL_SOCKS5),
                json.optString("host", ""),
                json.optInt("port", 1080),
                json.optString("username", ""),
                json.optString("password", "")
        );
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("proxy_config", Context.MODE_PRIVATE);
    }
}
