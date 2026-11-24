package com.yuqiplus.androidsmb;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.text.InputFilter;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.LinkedHashSet;
import java.util.Set;

public final class MainActivity extends Activity {
    private static final int NETWORK_REQUEST = 1;
    private static final int NOTIFICATION_REQUEST = 2;
    private static final int BACKGROUND = 0xffedf3ef;
    private static final int SURFACE = Color.WHITE;
    private static final int TEXT = 0xff142b23;
    private static final int MUTED = 0xff4a6659;
    private static final int GREEN = 0xff17614d;
    private static final int TINT = 0xffe4f3e8;
    private static final int FIELD = 0xfff5f8f5;
    private static final int STROKE = 0xffdce7df;
    private static final int HERO = 0xff18392e;
    private static final int HERO_WELL = 0xff244a3c;
    private static final int HERO_TEXT = 0xfff5fff6;
    private static final int HERO_MUTED = 0xffc3dacb;
    private static final int MINT = 0xffb9efb8;
    private static final int ERROR = 0xffa42e28;

    private final Set<String> networks = new LinkedHashSet<>();

    private final Runnable statusChanged = () -> {
        launchPending = false;
        refresh();
    };

    private View statusDot;
    private TextView status;
    private LinearLayout addressBox;
    private TextView address;
    private TextView guidance;
    private EditText user;
    private EditText password;
    private EditText port;
    private EditText networkInput;
    private LinearLayout networkList;
    private LinearLayout networkComposer;
    private TextView networkError;
    private TextView networkLocked;
    private Button toggle;
    private Button copy;
    private boolean launchPending;
    private String message = "";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Settings settings = Settings.load(this);
        for (String entry : ClientNetworks.split(settings.allowedNetworks)) {
            try {
                networks.add(ClientNetworks.normalize(entry));
            } catch (IllegalArgumentException ignored) {
                networks.add(entry);
            }
        }

        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BACKGROUND);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                Insets safe = insets.getInsets(WindowInsets.Type.systemBars()
                    | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            } else {
                view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });

        FrameLayout page = new FrameLayout(this);
        LinearLayout content = column();
        content.setPadding(dp(18), dp(17), dp(18), dp(25));
        content.setFocusableInTouchMode(true);
        int width = Math.min(600, getResources().getConfiguration().screenWidthDp);
        page.addView(content, new FrameLayout.LayoutParams(
            dp(width), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        scroll.addView(page);
        setContentView(scroll);

        content.addView(header());
        addBelow(content, connectionCard(), 18);
        addBelow(content, settingsCard(settings), 14);
        addBelow(content, networksCard(), 14);
        addBelow(content, filesCard(), 14);
        if (state != null) {
            networkInput.setText(state.getString("networkDraft", ""));
        }
        content.requestFocus();
        refresh();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("networkDraft", networkInput.getText().toString());
    }

    private LinearLayout header() {
        LinearLayout header = row();
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_share);
        icon.setPadding(dp(11), dp(11), dp(11), dp(11));
        icon.setBackground(rounded(TINT, 15, 0));
        header.addView(icon, new LinearLayout.LayoutParams(dp(48), dp(48)));

        LinearLayout title = column();
        title.addView(label("Android SMB", 29, TEXT, true));
        title.addView(label("局域网文件共享", 13, MUTED, false));
        addWeighted(header, title, 1, 13);
        return header;
    }

    private LinearLayout connectionCard() {
        LinearLayout card = card(HERO);
        LinearLayout headline = row();
        statusDot = new View(this);
        headline.addView(statusDot, new LinearLayout.LayoutParams(dp(9), dp(9)));
        status = label("", 23, HERO_TEXT, true);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        addWeighted(headline, status, 1, 9);
        card.addView(headline);

        addressBox = column();
        addressBox.setPadding(dp(13), dp(9), dp(13), dp(9));
        addressBox.setBackground(rounded(HERO_WELL, 13, 0));
        addressBox.addView(label("SMB 地址", 12, HERO_MUTED, false));
        address = label("", 15, HERO_TEXT, false);
        address.setTypeface(Typeface.MONOSPACE);
        address.setTextIsSelectable(true);
        addBelow(addressBox, address, 2);
        addBelow(card, addressBox, 10);

        guidance = label("", 14, HERO_MUTED, false);
        addBelow(card, guidance, 7);

        LinearLayout actions = row();
        toggle = button("启动共享", MINT, HERO);
        toggle.setOnClickListener(view -> {
            if (ServerService.status.active()) {
                startService(new Intent(this, ServerService.class).setAction(ServerService.STOP));
            } else {
                startSharing();
            }
        });
        addWeighted(actions, toggle, 1, 0);
        copy = button("复制地址", HERO_WELL, HERO_TEXT);
        copy.setOnClickListener(view -> copyAddress());
        addWeighted(actions, copy, 1, 10);
        addBelow(card, actions, 13);
        return card;
    }

    private void copyAddress() {
        if (ServerService.status.stage() == ServerService.Stage.RUNNING) {
            getSystemService(ClipboardManager.class).setPrimaryClip(
                ClipData.newPlainText("SMB 地址", ServerService.status.address()));
            if (Build.VERSION.SDK_INT < 33) {
                Toast.makeText(this, "已复制地址", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private LinearLayout settingsCard(Settings settings) {
        LinearLayout card = card(SURFACE);
        card.addView(label("连接设置", 19, TEXT, true));

        user = field(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
            settings.user, new InputFilter.LengthFilter(32));
        port = field(InputType.TYPE_CLASS_NUMBER, Integer.toString(settings.port),
            new InputFilter.LengthFilter(5));
        user.setImeOptions(EditorInfo.IME_ACTION_NEXT);
        port.setImeOptions(EditorInfo.IME_ACTION_NEXT);
        LinearLayout pair = row();
        addWeighted(pair, labeledField("用户名", user, user), 2, 0);
        addWeighted(pair, labeledField("TCP 端口", port, port), 1, 10);
        addBelow(card, pair, 10);

        password = field(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD,
            settings.password);
        password.setTransformationMethod(PasswordTransformationMethod.getInstance());
        LinearLayout passwordRow = row();
        addWeighted(passwordRow, password, 1, 0);
        Button reveal = button("显示", TINT, GREEN);
        reveal.setContentDescription("显示密码");
        reveal.setOnClickListener(view -> {
            boolean hidden = password.getTransformationMethod() instanceof PasswordTransformationMethod;
            password.setTransformationMethod(hidden ? null : PasswordTransformationMethod.getInstance());
            password.setSelection(password.length());
            reveal.setText(hidden ? "隐藏" : "显示");
            reveal.setContentDescription(hidden ? "隐藏密码" : "显示密码");
        });
        addBeside(passwordRow, reveal, 8);
        addBelow(card, labeledField("密码", password, passwordRow), 10);
        return card;
    }

    private LinearLayout networksCard() {
        LinearLayout card = card(SURFACE);
        card.addView(label("允许的其他网段", 19, TEXT, true));
        addBelow(card, label("默认允许手机所在子网。可另外放行单台设备或整个网段。",
            13, MUTED, false), 5);

        networkComposer = column();
        LinearLayout inputRow = row();
        networkInput = field(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI
            | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, "");
        networkInput.setTypeface(Typeface.MONOSPACE);
        networkInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
        networkInput.setHint("10.20.30.45 或 10.0.0.0/8");
        networkInput.setContentDescription("IPv4 地址或 CIDR");
        networkInput.setOnEditorActionListener((view, action, event) -> {
            if (action == EditorInfo.IME_ACTION_DONE) {
                addNetworkDraft();
                return true;
            }
            return false;
        });
        addWeighted(inputRow, networkInput, 1, 0);
        Button add = button("添加", GREEN, Color.WHITE);
        add.setOnClickListener(view -> addNetworkDraft());
        addBeside(inputRow, add, 8);
        networkComposer.addView(inputRow);

        networkError = label("", 13, ERROR, false);
        networkError.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        networkError.setVisibility(View.GONE);
        addBelow(networkComposer, networkError, 4);
        addBelow(networkComposer, label("支持粘贴多项，用逗号、空格或换行分隔。", 12, MUTED, false), 5);
        addBelow(card, networkComposer, 10);

        networkList = column();
        addBelow(card, networkList, 3);
        networkLocked = label("停止共享后可编辑网段。", 12, MUTED, false);
        addBelow(card, networkLocked, 6);
        return card;
    }

    private void addNetworkDraft() {
        if (commitNetworkDraft()) {
            getSystemService(InputMethodManager.class)
                .hideSoftInputFromWindow(networkInput.getWindowToken(), 0);
            networkInput.clearFocus();
        }
    }

    private boolean commitNetworkDraft() {
        Set<String> additions = new LinkedHashSet<>();
        for (String entry : ClientNetworks.split(networkInput.getText())) {
            try {
                additions.add(ClientNetworks.normalize(entry));
            } catch (IllegalArgumentException exception) {
                showNetworkError(exception.getMessage());
                return false;
            }
        }
        networkError.setVisibility(View.GONE);
        if (networks.addAll(additions)) {
            saveNetworkList();
        }
        networkInput.setText("");
        return true;
    }

    private void showNetworkError(String error) {
        networkError.setText(error);
        networkError.setVisibility(View.VISIBLE);
        networkInput.requestFocus();
    }

    private void saveNetworkList() {
        Settings.saveAllowedNetworks(this, String.join(", ", networks));
        renderNetworkList(false);
    }

    private void renderNetworkList(boolean active) {
        networkList.removeAllViews();
        networkList.setVisibility(networks.isEmpty() ? View.GONE : View.VISIBLE);
        for (String entry : networks) {
            LinearLayout item = row();
            item.setPadding(dp(12), 0, dp(6), 0);
            item.setMinimumHeight(dp(44));
            item.setBackground(rounded(FIELD, 12, STROKE));
            TextView value = label(entry, 15, TEXT, true);
            value.setTypeface(Typeface.MONOSPACE);
            addWeighted(item, value, 1, 0);
            if (!active) {
                Button remove = button("移除", Color.TRANSPARENT, MUTED);
                remove.setContentDescription("移除 " + entry);
                remove.setOnClickListener(view -> {
                    networks.remove(entry);
                    saveNetworkList();
                });
                addBeside(item, remove, 6);
            }
            addBelow(networkList, item, 6);
        }
    }

    private LinearLayout filesCard() {
        LinearLayout container = card(SURFACE);
        LinearLayout row = row();
        LinearLayout text = column();
        text.addView(label("共享目录", 17, TEXT, true));
        TextView share = label(Settings.SHARE, 13, MUTED, false);
        share.setTypeface(Typeface.MONOSPACE);
        text.addView(share);
        addWeighted(row, text, 1, 0);
        Button open = button("打开共享目录", TINT, GREEN);
        open.setOnClickListener(view -> openFiles());
        addBeside(row, open, 8);
        container.addView(row);
        return container;
    }

    @Override
    protected void onStart() {
        super.onStart();
        ServerService.statusListener = statusChanged;
        refresh();
    }

    @Override
    protected void onStop() {
        if (ServerService.statusListener == statusChanged) {
            ServerService.statusListener = null;
        }
        super.onStop();
    }

    private void startSharing() {
        String username = user.getText().toString().trim();
        String secret = password.getText().toString();
        String portText = port.getText().toString();
        int listenPort = portText.isEmpty() ? 0 : Integer.parseInt(portText);
        if (!Settings.validUser(username)) {
            user.setError("请输入 1–32 位用户名，可包含字母、数字、点、下划线和连字符。");
            user.requestFocus();
            return;
        }
        if (secret.isEmpty()) {
            password.setError("请输入密码。");
            password.requestFocus();
            return;
        }
        if (!Settings.validPort(listenPort)) {
            port.setError("端口范围为 1024–65535。");
            port.requestFocus();
            return;
        }

        if (!commitNetworkDraft()) {
            return;
        }
        new Settings(username, secret, listenPort, String.join(", ", networks)).save(this);
        message = "";
        if (Build.VERSION.SDK_INT >= 37
                && checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK)
                    != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_LOCAL_NETWORK}, NETWORK_REQUEST);
        } else {
            requestNotificationOrLaunch();
        }
    }

    private void requestNotificationOrLaunch() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED
                && !getPreferences(MODE_PRIVATE).getBoolean("asked_notifications", false)) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("asked_notifications", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_REQUEST);
        } else {
            launchServer();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(requestCode, permissions, grants);
        if (requestCode == NETWORK_REQUEST) {
            if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) {
                requestNotificationOrLaunch();
            } else {
                message = "请在系统设置中允许局域网访问后重试。";
                refresh();
            }
        } else if (requestCode == NOTIFICATION_REQUEST) {
            launchServer();
        }
    }

    private void launchServer() {
        try {
            launchPending = true;
            startForegroundService(new Intent(this, ServerService.class));
        } catch (RuntimeException exception) {
            launchPending = false;
            message = "启动服务失败，请保持 App 在前台并重试。";
        }
        refresh();
    }

    private void refresh() {
        ServerService.Status current = ServerService.status;
        boolean running = current.stage() == ServerService.Stage.RUNNING;
        boolean active = launchPending || current.active();
        status.setText(switch (current.stage()) {
            case WAITING_FOR_WIFI -> "正在等待 Wi-Fi";
            case RUNNING -> "共享运行中";
            case STARTING -> "正在启动";
            default -> launchPending ? "正在启动" : "共享已停止";
        });
        statusDot.setBackground(rounded(running ? MINT : active ? 0xfff4cf84 : HERO_MUTED, 5, 0));
        address.setText(running ? current.address() : "");
        addressBox.setVisibility(running ? View.VISIBLE : View.GONE);
        copy.setVisibility(running ? View.VISIBLE : View.GONE);
        String hint = !message.isEmpty() ? message : current.message();
        guidance.setVisibility(running && hint.isEmpty() ? View.GONE : View.VISIBLE);
        guidance.setTextColor(hint.isEmpty() ? HERO_MUTED : 0xffffcdc6);
        guidance.setText(!hint.isEmpty() ? hint
            : current.stage() == ServerService.Stage.WAITING_FOR_WIFI
                ? "Wi-Fi 恢复后会自动继续共享。"
                : "启动后会显示可在电脑上使用的 SMB 地址。");
        toggle.setEnabled(!launchPending);
        toggle.setText(active ? "停止共享" : "启动共享");
        user.setEnabled(!active);
        password.setEnabled(!active);
        port.setEnabled(!active);
        networkComposer.setVisibility(active ? View.GONE : View.VISIBLE);
        networkLocked.setVisibility(active ? View.VISIBLE : View.GONE);
        renderNetworkList(active);
    }

    private void openFiles() {
        var root = DocumentsContract.buildRootUri(getPackageName() + ".documents",
            ShareDocumentsProvider.ROOT);
        Intent intent = new Intent(Intent.ACTION_VIEW)
            .setDataAndType(root, DocumentsContract.Root.MIME_TYPE_ITEM);
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException exception) {
            Toast.makeText(this, "请在系统「文件」应用的侧栏中选择 Android SMB。", Toast.LENGTH_LONG).show();
        }
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(this);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    private LinearLayout card(int color) {
        LinearLayout card = column();
        card.setPadding(dp(15), dp(13), dp(15), dp(14));
        card.setBackground(rounded(color, 20, color == SURFACE ? STROKE : 0));
        return card;
    }

    private TextView label(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
        if (bold) {
            view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        }
        return view;
    }

    private Button button(String value, int fill, int ink) {
        Button button = new Button(this);
        button.setText(value);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(ink);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(12), dp(7), dp(12), dp(7));
        button.setMinHeight(dp(46));
        button.setMinimumHeight(dp(46));
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setStateListAnimator(null);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x24000000),
            rounded(fill, 12, 0), rounded(Color.WHITE, 12, 0)));
        return button;
    }

    private EditText field(int inputType, String value, InputFilter... filters) {
        EditText field = new EditText(this);
        field.setId(View.generateViewId());
        field.setSingleLine(true);
        field.setInputType(inputType);
        field.setTextSize(15);
        field.setTextColor(new ColorStateList(
            new int[][]{{-android.R.attr.state_enabled}, {}}, new int[]{MUTED, TEXT}));
        field.setHintTextColor(MUTED);
        field.setMinHeight(dp(46));
        field.setPadding(dp(11), dp(7), dp(11), dp(7));
        StateListDrawable outline = new StateListDrawable();
        outline.addState(new int[]{android.R.attr.state_focused}, rounded(Color.WHITE, 11, GREEN));
        outline.addState(new int[]{}, rounded(FIELD, 11, STROKE));
        field.setBackground(outline);
        field.setFilters(filters);
        field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        field.setText(value);
        return field;
    }

    private LinearLayout labeledField(String value, EditText input, View fieldLayout) {
        LinearLayout group = column();
        TextView title = label(value, 13, MUTED, true);
        title.setLabelFor(input.getId());
        group.addView(title);
        addBelow(group, fieldLayout, 5);
        return group;
    }

    private void addBelow(LinearLayout parent, View child, int margin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(margin);
        parent.addView(child, params);
    }

    private void addWeighted(LinearLayout parent, View child, float weight, int margin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, weight);
        params.setMarginStart(dp(margin));
        parent.addView(child, params);
    }

    private void addBeside(LinearLayout parent, View child, int margin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMarginStart(dp(margin));
        parent.addView(child, params);
    }

    private GradientDrawable rounded(int color, int radius, int border) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        if (border != 0) {
            drawable.setStroke(dp(1), border);
        }
        return drawable;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
