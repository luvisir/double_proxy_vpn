package com.lynn.proxyvpn;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private static final int VPN_PERMISSION_REQUEST = 1001;
    private Spinner protocolInput;
    private EditText nameInput;
    private EditText hostInput;
    private EditText portInput;
    private EditText usernameInput;
    private EditText passwordInput;
    private Button editSaveButton;
    private Button startButton;
    private TextView connectionTimeText;
    private TextView statusText;
    private List<ProxyConfig> profiles = new ArrayList<>();
    private String currentProfileId = "profile_1";
    private String actualUsername = "";
    private String actualPassword = "";
    private boolean bindingSensitiveFields;
    private boolean editing;
    private boolean vpnRunning;
    private long connectionStartedAtMillis;
    private final Handler timerHandler = new Handler(Looper.getMainLooper());
    private final Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            updateConnectionTime();
            if (vpnRunning) {
                timerHandler.postDelayed(this, 1000);
            }
        }
    };

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ProxyVpnService.ACTION_STATUS.equals(intent.getAction())) {
                String status = intent.getStringExtra(ProxyVpnService.EXTRA_STATUS);
                statusText.setText(status);
                if ("全局代理已启动".equals(status)) {
                    setVpnRunning(true);
                } else if (status != null && (status.contains("启动失败") || status.contains("内核退出") || status.contains("停止"))) {
                    setVpnRunning(false);
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestNotificationPermission();
        setContentView(buildLayout());
        loadProfiles();
        syncVpnStateFromService();
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(ProxyVpnService.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(statusReceiver, filter);
        }
        syncVpnStateFromService();
    }

    @Override
    protected void onResume() {
        super.onResume();
        syncVpnStateFromService();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        syncVpnStateFromService();
    }

    @Override
    protected void onStop() {
        super.onStop();
        unregisterReceiver(statusReceiver);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        timerHandler.removeCallbacks(timerRunnable);
    }

    private View buildLayout() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setPadding(40, 28, 40, 28);
        scrollView.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.MATCH_PARENT
        ));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("EProxy");
        title.setTextSize(28);
        title.setGravity(Gravity.START);
        header.addView(title, weightWrap());

        editSaveButton = button("编辑", v -> toggleEditing());
        header.addView(editSaveButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        root.addView(header, matchWrap());

        nameInput = field("名称", InputType.TYPE_CLASS_TEXT);
        root.addView(label("名称"));
        root.addView(nameInput, matchWrap());

        protocolInput = new Spinner(this);
        protocolInput.setAdapter(new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{ProxyConfig.PROTOCOL_SOCKS5, ProxyConfig.PROTOCOL_HTTP, ProxyConfig.PROTOCOL_HTTPS}
        ));
        root.addView(label("协议"));
        root.addView(protocolInput, matchWrap());

        hostInput = field("代理 Host", InputType.TYPE_CLASS_TEXT);
        portInput = field("端口", InputType.TYPE_CLASS_NUMBER);
        usernameInput = field("用户名，可空", InputType.TYPE_CLASS_TEXT);
        passwordInput = field("密码，可空", InputType.TYPE_CLASS_TEXT);
        configureSensitiveField(usernameInput, true);
        configureSensitiveField(passwordInput, false);

        root.addView(label("代理 Host"));
        root.addView(hostInput, matchWrap());
        root.addView(label("端口"));
        root.addView(portInput, matchWrap());
        root.addView(label("用户名"));
        root.addView(usernameInput, matchWrap());
        root.addView(label("密码"));
        root.addView(passwordInput, matchWrap());

        root.addView(button("测试连接", v -> testProxy()), matchWrap());

        startButton = button("启动", v -> toggleVpn());
        root.addView(startButton, matchWrap());

        connectionTimeText = new TextView(this);
        connectionTimeText.setText("连接时间 00:00:00");
        connectionTimeText.setTextSize(17);
        connectionTimeText.setGravity(Gravity.CENTER);
        connectionTimeText.setPadding(0, 18, 0, 18);
        root.addView(connectionTimeText, matchWrap());

        statusText = new TextView(this);
        statusText.setText("未连接");
        statusText.setTextSize(15);
        statusText.setPadding(0, 8, 0, 0);
        root.addView(statusText, matchWrap());

        setEditing(false);
        setVpnRunning(false);

        return scrollView;
    }

    private void loadProfiles() {
        profiles = ProxyConfig.loadProfiles(this);
        String selectedId = ProxyConfig.loadSelectedProfileId(this);
        int selectedIndex = 0;
        for (int i = 0; i < profiles.size(); i++) {
            ProxyConfig profile = profiles.get(i);
            if (profile.id.equals(selectedId)) {
                selectedIndex = i;
            }
        }

        showProfile(profiles.get(selectedIndex));
    }

    private void showProfile(ProxyConfig config) {
        currentProfileId = config.id;
        ProxyConfig.saveSelectedProfileId(this, config.id);
        nameInput.setText(config.displayName());
        if (config.protocol.equals(ProxyConfig.PROTOCOL_HTTP)) {
            protocolInput.setSelection(1);
        } else if (config.protocol.equals(ProxyConfig.PROTOCOL_HTTPS)) {
            protocolInput.setSelection(2);
        } else {
            protocolInput.setSelection(0);
        }
        hostInput.setText(config.host);
        portInput.setText(String.valueOf(config.port));
        actualUsername = config.username == null ? "" : config.username;
        actualPassword = config.password == null ? "" : config.password;
        hideSensitiveFields(false);
        setEditing(false);
    }

    private ProxyConfig saveCurrentProfile(boolean showStatus) {
        ProxyConfig config = currentConfig();
        ProxyConfig.saveProfile(this, config);
        replaceProfileInMemory(config);
        if (showStatus) {
            statusText.setText("已保存");
        }
        hideSensitiveFields(true);
        return config;
    }

    private ProxyConfig persistCurrentProfileWithoutRefresh() {
        ProxyConfig config = currentConfig();
        ProxyConfig.saveProfile(this, config);
        replaceProfileInMemory(config);
        return config;
    }

    private void replaceProfileInMemory(ProxyConfig config) {
        for (int i = 0; i < profiles.size(); i++) {
            if (profiles.get(i).id.equals(config.id)) {
                profiles.set(i, config);
                return;
            }
        }
        profiles.add(config);
    }

    private EditText field(String hint, int inputType) {
        EditText editText = new EditText(this);
        editText.setHint(hint);
        editText.setInputType(inputType);
        editText.setSingleLine(true);
        return editText;
    }

    private void configureSensitiveField(EditText editText, boolean username) {
        editText.setOnFocusChangeListener((view, hasFocus) -> {
            if (hasFocus) {
                showSensitiveField(editText, username);
            } else {
                captureSensitiveField(editText, username);
                hideSensitiveField(editText, username, false);
            }
        });
        editText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (bindingSensitiveFields || !editText.hasFocus()) {
                    return;
                }
                if (username) {
                    actualUsername = s.toString();
                } else {
                    actualPassword = s.toString();
                }
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
    }

    private void showSensitiveField(EditText editText, boolean username) {
        bindingSensitiveFields = true;
        String value = username ? actualUsername : actualPassword;
        editText.setText(value);
        editText.setSelection(editText.getText().length());
        bindingSensitiveFields = false;
    }

    private void hideSensitiveFields(boolean force) {
        hideSensitiveField(usernameInput, true, force);
        hideSensitiveField(passwordInput, false, force);
    }

    private void hideSensitiveField(EditText editText, boolean username, boolean force) {
        if (editText == null || (!force && editText.hasFocus())) {
            return;
        }
        if (force) {
            editText.clearFocus();
        }
        bindingSensitiveFields = true;
        String value = username ? actualUsername : actualPassword;
        editText.setText(mask(value));
        editText.setSelection(editText.getText().length());
        bindingSensitiveFields = false;
    }

    private void captureSensitiveField(EditText editText, boolean username) {
        if (bindingSensitiveFields) {
            return;
        }
        String value = editText.getText().toString();
        if (isMask(value)) {
            return;
        }
        if (username) {
            actualUsername = value;
        } else {
            actualPassword = value;
        }
    }

    private String mask(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            builder.append('*');
        }
        return builder.toString();
    }

    private boolean isMask(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) != '*') {
                return false;
            }
        }
        return true;
    }

    private TextView label(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(13);
        label.setPadding(0, 14, 0, 0);
        return label;
    }

    private Button button(String text, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setOnClickListener(listener);
        button.setAllCaps(false);
        return button;
    }

    private void updateButtonState(boolean running) {
        if (startButton == null) {
            return;
        }
        if (running) {
            startButton.setText("停止");
            styleButton(startButton, "#16A34A", "#FFFFFF");
        } else {
            startButton.setText("启动");
            styleButton(startButton, "#D1D5DB", "#111827");
        }
    }

    private void styleButton(Button button, String backgroundColor, String textColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Color.parseColor(backgroundColor));
        drawable.setCornerRadius(10);
        button.setTextColor(Color.parseColor(textColor));
        button.setBackground(drawable);
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
    }

    private LinearLayout.LayoutParams weightWrap() {
        return new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1
        );
    }

    private void toggleEditing() {
        if (editing) {
            saveCurrentProfile(true);
            setEditing(false);
        } else {
            setEditing(true);
        }
    }

    private void setEditing(boolean enabled) {
        editing = enabled;
        if (editSaveButton != null) {
            editSaveButton.setText(enabled ? "保存" : "编辑");
        }
        setConfigFieldsEnabled(enabled);
        if (!enabled) {
            hideSensitiveFields(true);
        }
    }

    private void setConfigFieldsEnabled(boolean enabled) {
        if (nameInput != null) {
            nameInput.setEnabled(enabled);
        }
        if (protocolInput != null) {
            protocolInput.setEnabled(enabled);
        }
        if (hostInput != null) {
            hostInput.setEnabled(enabled);
        }
        if (portInput != null) {
            portInput.setEnabled(enabled);
        }
        if (usernameInput != null) {
            usernameInput.setEnabled(enabled);
        }
        if (passwordInput != null) {
            passwordInput.setEnabled(enabled);
        }
    }

    private void toggleVpn() {
        if (vpnRunning) {
            stopVpn();
        } else {
            startVpn();
        }
    }

    private void setVpnRunning(boolean running) {
        setVpnRunning(running, running ? ProxyVpnService.getStartedAtMillis() : 0);
    }

    private void setVpnRunning(boolean running, long startedAt) {
        vpnRunning = running;
        updateButtonState(running);
        if (running) {
            startConnectionTimer(startedAt);
        } else {
            stopConnectionTimer();
        }
    }

    private void startConnectionTimer(long startedAt) {
        connectionStartedAtMillis = startedAt > 0 ? startedAt : System.currentTimeMillis();
        timerHandler.removeCallbacks(timerRunnable);
        updateConnectionTime();
        timerHandler.postDelayed(timerRunnable, 1000);
    }

    private void syncVpnStateFromService() {
        if (ProxyVpnService.isRunning()) {
            setVpnRunning(true, ProxyVpnService.getStartedAtMillis());
            if (statusText != null) {
                statusText.setText("全局代理已启动");
            }
        } else {
            setVpnRunning(false);
        }
    }

    private void stopConnectionTimer() {
        timerHandler.removeCallbacks(timerRunnable);
        connectionStartedAtMillis = 0;
        if (connectionTimeText != null) {
            connectionTimeText.setText("连接时间 00:00:00");
        }
    }

    private void updateConnectionTime() {
        if (connectionTimeText == null || connectionStartedAtMillis <= 0) {
            return;
        }
        long elapsedSeconds = Math.max(0, (System.currentTimeMillis() - connectionStartedAtMillis) / 1000);
        long hours = elapsedSeconds / 3600;
        long minutes = (elapsedSeconds % 3600) / 60;
        long seconds = elapsedSeconds % 60;
        connectionTimeText.setText(String.format("连接时间 %02d:%02d:%02d", hours, minutes, seconds));
    }

    private ProxyConfig currentConfig() {
        captureSensitiveField(usernameInput, true);
        captureSensitiveField(passwordInput, false);
        int port = 0;
        try {
            port = Integer.parseInt(portInput.getText().toString().trim());
        } catch (NumberFormatException ignored) {
        }
        String name = nameInput.getText().toString().trim();
        if (name.isEmpty()) {
            name = "代理 1";
        }
        return new ProxyConfig(
                currentProfileId,
                name,
                protocolInput.getSelectedItem().toString(),
                hostInput.getText().toString().trim(),
                port,
                actualUsername.trim(),
                actualPassword
        );
    }

    private void testProxy() {
        ProxyConfig config = saveCurrentProfile(false);
        statusText.setText("正在测试...");
        new Thread(() -> {
            try {
                String ip = ProxyTester.test(config);
                runOnUiThread(() -> statusText.setText("测试成功，出口 IP: " + ip));
            } catch (Exception e) {
                runOnUiThread(() -> statusText.setText("测试失败: " + e.getMessage()));
            }
        }).start();
    }

    private void startVpn() {
        ProxyConfig config = saveCurrentProfile(false);
        setEditing(false);
        if (!config.isValid()) {
            statusText.setText("请先填写正确的代理地址和端口");
            return;
        }
        Intent intent = VpnService.prepare(this);
        if (intent != null) {
            startActivityForResult(intent, VPN_PERMISSION_REQUEST);
        } else {
            startVpnService();
        }
    }

    private void stopVpn() {
        ProxyVpnService.requestStop(this);
        setVpnRunning(false);
        statusText.setText("已停止");
        keepActivityVisibleAfterStop();
    }

    private void keepActivityVisibleAfterStop() {
        timerHandler.postDelayed(() -> {
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
        }, 250);
    }

    private void startVpnService() {
        Intent intent = new Intent(this, ProxyVpnService.class);
        intent.setAction(ProxyVpnService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        statusText.setText("正在启动全局代理...");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VPN_PERMISSION_REQUEST && resultCode == RESULT_OK) {
            startVpnService();
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 42);
        }
    }

}
