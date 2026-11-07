package org.kompakt.remote;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.net.ssl.*;

/** Native Android client for the personal Mac companion. */
public final class MainActivity extends Activity {
    private final Handler handler = new Handler();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private TextView status;
    private LinearLayout transcript;
    private EditText composer;
    private Button review;
    private IconButton send;
    private ScrollView scroll;
    private JSONObject pairing, state;
    private String endpoint = "", token = "", requestId = "", requestText = "";
    private String lastText = "";
    private SSLSocketFactory ssl;
    private boolean foreground, polling, submitting, connected, newConversation;
    private long lastRevision = -1;
    private final Set<String> expandedTools = new HashSet<>();
    private String renderedThread = "";
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!foreground) return;
            if (!polling && pairing != null) {
                polling = true;
                io.execute(() -> {
                    try {
                        JSONObject snapshot = api("GET", "/state", null);
                        runOnUiThread(() -> { connected = true; render(snapshot); });
                    } catch (Exception e) {
                        runOnUiThread(() -> {
                            connected = false;
                            status.setText("Mac disconnected · saved chat");
                            status.setContentDescription(friendly(e));
                            refreshButtons();
                        });
                    } finally { runOnUiThread(() -> polling = false); }
                });
            }
            handler.postDelayed(this, 2000);
        }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        root.setPadding(dp(12), dp(6), dp(12), dp(10));
        LinearLayout toolbar = row(); toolbar.setGravity(Gravity.CENTER_VERTICAL);
        IconButton menu = new IconButton("menu", "Open chats", v -> showChats());
        toolbar.addView(menu, new LinearLayout.LayoutParams(dp(42), dp(50)));
        LinearLayout heading = new LinearLayout(this); heading.setOrientation(LinearLayout.VERTICAL);
        TextView title = text("Codex for Kompakt", 19); title.setTypeface(null, Typeface.BOLD);
        title.setSingleLine(true); title.setEllipsize(TextUtils.TruncateAt.END); heading.addView(title);
        status = text("Connecting to your Mac…", 12); status.setSingleLine(true); status.setEllipsize(TextUtils.TruncateAt.END);
        heading.addView(status); toolbar.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        toolbar.addView(new IconButton("new", "New chat", v -> selectChat(null)), new LinearLayout.LayoutParams(dp(42), dp(50)));
        root.addView(toolbar);
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setSmoothScrollingEnabled(false);
        transcript = new LinearLayout(this); transcript.setOrientation(LinearLayout.VERTICAL);
        transcript.setPadding(0, dp(20), 0, dp(12));
        scroll.addView(transcript);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        review = button("Review action", v -> showPending()); review.setBackground(rounded(Color.WHITE, 14, true));
        review.setVisibility(View.GONE);
        LinearLayout.LayoutParams approvalParams = new LinearLayout.LayoutParams(-1, dp(46)); approvalParams.setMargins(0, 0, 0, dp(8));
        root.addView(review, approvalParams);
        LinearLayout input = row(); input.setGravity(Gravity.BOTTOM); input.setPadding(dp(10), dp(5), dp(6), dp(5));
        input.setBackground(rounded(Color.WHITE, 25, true));
        composer = new EditText(this);
        composer.setTextColor(Color.BLACK);
        composer.setHintTextColor(Color.DKGRAY);
        composer.setTextSize(18);
        composer.setHint("Message Codex…");
        composer.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        composer.setMinLines(1); composer.setMaxLines(5); composer.setMinHeight(dp(46));
        composer.setGravity(Gravity.TOP);
        composer.setBackgroundColor(Color.TRANSPARENT);
        composer.setPadding(dp(4), dp(12), dp(8), dp(10));
        composer.setText(getPreferences(0).getString("draft", ""));
        requestId = getPreferences(0).getString("pendingId", "");
        requestText = getPreferences(0).getString("pendingText", "");
        newConversation = getPreferences(0).getBoolean("pendingNew", false);
        input.addView(composer, new LinearLayout.LayoutParams(0, -2, 1));
        send = new IconButton("send", "Send message", v -> {
            if (state == null || !state.optBoolean("busy")) { submit(); return; }
            new AlertDialog.Builder(this).setTitle("Stop this task?")
                .setMessage("Completed changes remain on the Mac.")
                .setPositiveButton("Stop task", (d,w) -> post("/interrupt", new JSONObject(), () -> toast("Stop requested")))
                .setNegativeButton("Keep working", null).show();
        });
        LinearLayout.LayoutParams sendParams = new LinearLayout.LayoutParams(dp(44), dp(46));
        input.addView(send, sendParams); root.addView(input);
        setContentView(root);
        try {
            File file = new File(getFilesDir(), "pairing.json");
            pairing = new JSONObject(read(new FileInputStream(file)));
            token = pairing.getString("token");
            endpoint = getPreferences(0).getString("endpoint", pairing.getString("usbUrl"));
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            java.security.cert.Certificate cert = cf.generateCertificate(new ByteArrayInputStream(pairing.getString("certificate").getBytes(StandardCharsets.US_ASCII)));
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null); ks.setCertificateEntry("mac", cert);
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ks);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, tmf.getTrustManagers(), null); ssl = ctx.getSocketFactory();
            File cached = new File(getFilesDir(), "last-state.json");
            if (cached.exists()) render(new JSONObject(read(new FileInputStream(cached))));
        } catch (Exception e) {
            pairing = null;
            status.setText("Pair this phone with your Mac");
        }
        if (state == null) renderMessages(new JSONObject());
        refreshButtons();
    }

    @Override public void onResume() { super.onResume(); foreground = true; handler.post(poll); }
    @Override public void onPause() {
        foreground = false; handler.removeCallbacks(poll);
        getPreferences(0).edit().putString("draft", composer.getText().toString()).apply();
        super.onPause();
    }
    @Override public void onDestroy() { handler.removeCallbacksAndMessages(null); io.shutdown(); super.onDestroy(); }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private TextView text(String value, int size) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(Color.BLACK);
        t.setLineSpacing(dp(2), 1); return t;
    }
    private GradientDrawable border() {
        GradientDrawable b = new GradientDrawable(); b.setColor(Color.WHITE); b.setStroke(dp(1), Color.BLACK); return b;
    }
    private GradientDrawable rounded(int fill, int radius, boolean outline) {
        GradientDrawable b = new GradientDrawable(); b.setColor(fill); b.setCornerRadius(dp(radius));
        if (outline) b.setStroke(dp(1), 0xff888888); return b;
    }
    private final class IconButton extends View {
        private String kind;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        IconButton(String kind, String description, OnClickListener action) {
            super(MainActivity.this); this.kind = kind; setContentDescription(description);
            setClickable(true); setFocusable(true); setOnClickListener(action);
        }
        void change(String kind, String description) { this.kind = kind; setContentDescription(description); invalidate(); }
        @Override protected void onDraw(Canvas c) {
            super.onDraw(c); float x = getWidth()/2f, y = getHeight()/2f, r = dp(10);
            paint.setColor(isEnabled() ? Color.BLACK : Color.GRAY); paint.setStrokeWidth(dp(2));
            paint.setStrokeCap(Paint.Cap.ROUND); paint.setStyle(Paint.Style.STROKE);
            if (kind.equals("menu")) {
                for (int i=-1;i<=1;i++) c.drawLine(x-r,y+i*dp(6),x+r,y+i*dp(6),paint);
            } else if (kind.equals("new")) {
                c.drawLine(x-r,y,x+r,y,paint); c.drawLine(x,y-r,x,y+r,paint);
            } else {
                paint.setStyle(Paint.Style.FILL); c.drawCircle(x,y,dp(18),paint); paint.setColor(Color.WHITE);
                if (kind.equals("stop")) c.drawRoundRect(x-dp(6),y-dp(6),x+dp(6),y+dp(6),dp(1),dp(1),paint);
                else {
                    paint.setStyle(Paint.Style.STROKE);
                    c.drawLine(x,y+dp(8),x,y-dp(8),paint);
                    c.drawLine(x,y-dp(8),x-dp(6),y-dp(2),paint); c.drawLine(x,y-dp(8),x+dp(6),y-dp(2),paint);
                }
            }
        }
    }
    private LinearLayout row() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); return l; }
    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(48), 1); p.setMargins(dp(2), dp(4), dp(2), dp(4)); return p;
    }
    private Button button(String title, View.OnClickListener click) {
        Button b = new Button(this); b.setText(title); b.setTextSize(14); b.setAllCaps(false);
        b.setTextColor(Color.BLACK); b.setBackground(border()); b.setOnClickListener(click); return b;
    }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
    private String friendly(Exception e) {
        if (e instanceof java.net.ConnectException || e instanceof java.net.SocketTimeoutException)
            return "Mac unavailable. Check Connection and keep the companion running.";
        if (e instanceof SSLException) return "The Mac certificate could not be verified. Pair again over USB.";
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
    private static String read(InputStream input) throws IOException {
        try (InputStream stream = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] b = new byte[8192]; int n;
            while ((n = stream.read(b)) != -1) {
                out.write(b, 0, n);
                if (out.size() > 4000000) throw new IOException("Result too large");
            }
            return out.toString("UTF-8");
        }
    }
    private JSONObject api(String method, String path, JSONObject body) throws Exception {
        HttpsURLConnection c = (HttpsURLConnection)new java.net.URL(endpoint + path).openConnection();
        c.setSSLSocketFactory(ssl);
        c.setConnectTimeout(5000); c.setReadTimeout(method.equals("GET") ? 8000 : 60000);
        c.setInstanceFollowRedirects(false);
        c.setRequestMethod(method); c.setRequestProperty("Authorization", "Bearer " + token);
        try {
            if (body != null) {
                byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
                c.setDoOutput(true); c.setRequestProperty("Content-Type", "application/json");
                c.setFixedLengthStreamingMode(payload.length);
                try (OutputStream out = c.getOutputStream()) { out.write(payload); }
            }
            int code = c.getResponseCode();
            InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
            JSONObject result = new JSONObject(in == null ? "{}" : read(in));
            if (code != 200) throw new IOException(result.optString("error", "Connection failed: " + code));
            return result;
        } finally { c.disconnect(); }
    }

    private void refreshButtons() {
        boolean busy = state != null && state.optBoolean("busy");
        send.setEnabled(connected && !submitting);
        send.change(busy ? "stop" : "send", busy ? "Stop task" : "Send message");
        review.setEnabled(connected && !submitting);
    }
    private void render(JSONObject s) {
        state = s;
        String mode = endpoint.contains("127.0.0.1") ? "USB" : "Wi-Fi";
        JSONObject host = s.optJSONObject("host");
        String hostName = host == null ? "Your Mac" : host.optString("name", "Your Mac");
        status.setText(s.optString("status") + " · " + hostName + " · " + mode);
        JSONArray approvals = s.optJSONArray("approvals");
        int count = approvals == null ? 0 : approvals.length();
        review.setVisibility(count > 0 ? View.VISIBLE : View.GONE);
        review.setText("Approve or deny (" + count + ")");
        if (s.optLong("revision") != lastRevision) {
            lastRevision = s.optLong("revision");
            JSONArray messages = s.optJSONArray("messages");
            String value = messages == null ? "" : messages.toString();
            String thread = s.optString("threadId", "");
            boolean changedChat = !thread.equals(renderedThread);
            if (!value.equals(lastText) || changedChat) {
                boolean bottom = scroll.getScrollY() + scroll.getHeight() >= transcript.getHeight() - dp(80);
                lastText = value; renderedThread = thread;
                renderMessages(s);
                if (bottom || changedChat) scroll.post(() -> scroll.scrollTo(0, transcript.getHeight()));
            }
            try (FileOutputStream out = openFileOutput("last-state.json", MODE_PRIVATE)) {
                out.write(s.toString().getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) { }
        }
        refreshButtons();
    }
    private void renderMessages(JSONObject s) {
        transcript.removeAllViews();
        JSONArray messages = s.optJSONArray("messages");
        if (messages == null || messages.length() == 0) {
            LinearLayout welcome = new LinearLayout(this); welcome.setOrientation(LinearLayout.VERTICAL); welcome.setGravity(Gravity.CENTER);
            welcome.setPadding(dp(20), dp(70), dp(20), dp(40));
            TextView title = text("What shall we work on?", 23); title.setGravity(Gravity.CENTER); title.setTypeface(null, Typeface.BOLD);
            welcome.addView(title);
            TextView hint = text("Send a task to your Mac.\nPick up a previous chat from the menu.", 16);
            hint.setGravity(Gravity.CENTER); hint.setPadding(0, dp(12), 0, 0); welcome.addView(hint); transcript.addView(welcome); return;
        }
        for (int i=0;i<messages.length();i++) {
            JSONObject m = messages.optJSONObject(i); if (m == null) continue;
            String role = m.optString("role"), body = m.optString("text"), key = m.optString("id");
            LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(-1, -2); space.setMargins(0, 0, 0, dp(18));
            if (role.equals("you") && (body.equals("Approved this action") || body.equals("Denied this action") || body.equals("Answered question"))) {
                TextView decision = text("✓ " + body, 12); decision.setTextColor(Color.DKGRAY); card.addView(decision);
            } else if (role.equals("you")) {
                TextView prompt = text(body, 17); prompt.setTextIsSelectable(true); prompt.setPadding(dp(14), dp(11), dp(14), dp(11));
                prompt.setBackground(rounded(0xffeeeeee, 18, false)); card.addView(prompt);
                space.setMargins(dp(30), 0, 0, dp(22));
            } else if (role.equals("command") || role.equals("changes")) {
                boolean expanded = expandedTools.contains(key);
                Button details = button((expanded ? "⌄ " : "› ") + (role.equals("command") ? "Command" : "File changes") + "  ·  " + (expanded ? "Hide" : "Details"), v -> {
                    if (expandedTools.contains(key)) expandedTools.remove(key); else expandedTools.add(key);
                    renderMessages(state);
                });
                details.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL); details.setPadding(dp(10), 0, 0, 0);
                details.setBackground(rounded(0xfff4f4f4, 10, false)); card.addView(details, new LinearLayout.LayoutParams(-1, dp(42)));
                if (expanded) card.addView(new MarkdownView(this, "````text\n"+body+"\n````"));
                space.setMargins(0, 0, 0, dp(9));
            } else if (role.equals("system")) {
                TextView note = text(body, 14); note.setTextColor(Color.DKGRAY); card.addView(note);
            } else {
                TextView author = text(role.equals("plan") ? "Plan" : "Codex", 12); author.setTypeface(null, Typeface.BOLD);
                author.setPadding(0, 0, 0, dp(7)); card.addView(author); card.addView(new MarkdownView(this, body));
            }
            transcript.addView(card, space);
        }
    }
    private void submit() {
        String prompt = composer.getText().toString().trim();
        if (prompt.isEmpty()) { toast("Enter a task first."); return; }
        if (!prompt.equals(requestText) || requestId.isEmpty()) { requestText = prompt; requestId = UUID.randomUUID().toString(); }
        getPreferences(0).edit().putString("pendingId", requestId).putString("pendingText", requestText)
            .putBoolean("pendingNew", newConversation).putString("draft", prompt).commit();
        try {
            JSONObject body = new JSONObject().put("prompt", prompt).put("requestId", requestId).put("newConversation", newConversation);
            ((InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(composer.getWindowToken(), 0);
            composer.clearFocus();
            post("/task", body, () -> {
                composer.setText(""); requestText = ""; requestId = ""; newConversation = false;
                getPreferences(0).edit().remove("draft").remove("pendingId").remove("pendingText").remove("pendingNew").apply();
            });
        } catch (JSONException e) { toast(e.getMessage()); }
    }
    private void post(String path, JSONObject body, Runnable success) {
        if (submitting) return;
        submitting = true; refreshButtons();
        io.execute(() -> {
            try {
                api("POST", path, body);
                runOnUiThread(success);
                JSONObject snapshot = api("GET", "/state", null);
                runOnUiThread(() -> { connected = true; render(snapshot); });
            } catch (Exception e) {
                runOnUiThread(() -> new AlertDialog.Builder(this).setTitle("Could not confirm")
                    .setMessage(friendly(e) + "\n\nCheck the current task before resubmitting.")
                    .setPositiveButton("OK", null).show());
            } finally { runOnUiThread(() -> { submitting = false; refreshButtons(); }); }
        });
    }
    private void selectChat(String id) {
        if (!connected) { toast("Reconnect to your Mac to open a chat."); return; }
        if (state != null && state.optBoolean("busy")) { toast("Finish or stop the current task before switching chats."); return; }
        String current = state == null ? "new" : state.optString("threadId", "new");
        getPreferences(0).edit().putString("draft:"+current, composer.getText().toString()).apply();
        try {
            post("/chat/select", new JSONObject().put("id", id == null ? JSONObject.NULL : id), () -> {
                composer.setText(getPreferences(0).getString("draft:"+(id == null ? "null" : id), ""));
                requestId = ""; requestText = ""; newConversation = false; lastRevision = -1;
                getPreferences(0).edit().remove("pendingId").remove("pendingText").remove("pendingNew").apply();
            });
        } catch (JSONException e) { toast(e.getMessage()); }
    }
    private void showChats() {
        Dialog drawer = new Dialog(this, android.R.style.Theme_Material_Light_NoActionBar);
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(Color.WHITE); box.setPadding(dp(18), dp(12), dp(16), dp(12));
        LinearLayout heading = row(); heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("Chats", 27); title.setTypeface(null, Typeface.BOLD); heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button close = button("Close", v -> drawer.dismiss()); close.setBackgroundColor(Color.TRANSPARENT);
        heading.addView(close, new LinearLayout.LayoutParams(dp(64), dp(48))); box.addView(heading);
        Button start = button("+   New chat", v -> { drawer.dismiss(); selectChat(null); }); start.setBackground(rounded(0xffeeeeee, 13, false));
        LinearLayout.LayoutParams startParams = new LinearLayout.LayoutParams(-1, dp(48)); startParams.setMargins(0, dp(12), 0, dp(22)); box.addView(start, startParams);
        ScrollView list = new ScrollView(this); list.setSmoothScrollingEnabled(false);
        LinearLayout groups = new LinearLayout(this); groups.setOrientation(LinearLayout.VERTICAL); groups.addView(text("Loading chats…", 15));
        list.addView(groups); box.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        Button changes = button("Changes in this chat", v -> { drawer.dismiss(); showText("Changes", state == null ? "" : state.optString("diff")); });
        changes.setBackgroundColor(Color.TRANSPARENT); box.addView(changes, new LinearLayout.LayoutParams(-1, dp(44)));
        Button connect = button("Connection", v -> { drawer.dismiss(); showConnection(); }); connect.setBackgroundColor(Color.TRANSPARENT);
        box.addView(connect, new LinearLayout.LayoutParams(-1, dp(44)));
        drawer.setContentView(box); drawer.show(); drawer.getWindow().setGravity(Gravity.LEFT);
        drawer.getWindow().setLayout((int)(getResources().getDisplayMetrics().widthPixels*0.94), -1);
        if (!connected) { groups.removeAllViews(); groups.addView(text("Connect to your Mac to load previous chats.", 16)); return; }
        io.execute(() -> {
            try {
                JSONObject result = api("GET", "/chats", null);
                runOnUiThread(() -> {
                    if (!drawer.isShowing()) return;
                    groups.removeAllViews(); JSONArray hosts = result.optJSONArray("hosts");
                    for (int h=0;hosts!=null && h<hosts.length();h++) {
                        JSONObject group = hosts.optJSONObject(h), host = group.optJSONObject("host");
                        TextView kind = text("REMOTE COMPUTER", 11); kind.setTextColor(Color.DKGRAY); groups.addView(kind);
                        TextView name = text(host.optString("name", "Your Mac"), 17); name.setTypeface(null, Typeface.BOLD); name.setPadding(0, dp(5), 0, dp(12)); groups.addView(name);
                        JSONArray chats = group.optJSONArray("chats");
                        if (chats == null || chats.length()==0) groups.addView(text("Your chats will appear here.", 15));
                        for (int c=0;chats!=null && c<chats.length();c++) {
                            JSONObject chat = chats.optJSONObject(c); String id = chat.optString("id");
                            LinearLayout entry = new LinearLayout(this); entry.setOrientation(LinearLayout.VERTICAL);
                            entry.setPadding(dp(12), dp(10), dp(10), dp(10));
                            entry.setBackground(rounded(chat.optBoolean("selected") ? 0xffeeeeee : Color.WHITE, 12, false));
                            TextView label = text(chat.optString("title", "Untitled chat"), 16); label.setMaxLines(2); label.setEllipsize(TextUtils.TruncateAt.END); entry.addView(label);
                            String date = new java.text.SimpleDateFormat("MMM d", Locale.getDefault()).format(new Date((long)(chat.optDouble("updatedAt")*1000)));
                            TextView meta = text((chat.optBoolean("selected") ? "Current chat · " : "")+date, 11); meta.setTextColor(Color.DKGRAY); meta.setPadding(0, dp(4), 0, 0); entry.addView(meta);
                            entry.setOnClickListener(v -> { drawer.dismiss(); selectChat(id); });
                            entry.setOnLongClickListener(v -> { renameChat(id, chat.optString("title"), drawer); return true; });
                            LinearLayout.LayoutParams itemParams = new LinearLayout.LayoutParams(-1,-2); itemParams.setMargins(0,0,0,dp(5)); groups.addView(entry,itemParams);
                        }
                    }
                });
            } catch (Exception e) { runOnUiThread(() -> { groups.removeAllViews(); groups.addView(text(friendly(e), 15)); }); }
        });
    }
    private void renameChat(String id, String title, Dialog drawer) {
        EditText name = new EditText(this); name.setText(title); name.setSingleLine(true); name.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this).setTitle("Rename chat").setView(name).setNegativeButton("Cancel",null)
            .setPositiveButton("Save", (d,w) -> {
                try { post("/chat/rename",new JSONObject().put("id",id).put("title",name.getText().toString()), () -> { drawer.dismiss(); showChats(); }); }
                catch(JSONException e) { toast(e.getMessage()); }
            }).show();
    }
    private void showConnection() {
        if (pairing == null) { toast("Run the USB pairing script on the Mac."); return; }
        if (pairing.optString("wifiUrl").isEmpty()) {
            new AlertDialog.Builder(this).setTitle("USB connection")
                .setMessage("This first build connects through the USB cable. Keep the Mac companion running. Wi-Fi and access over cellular are not configured yet.")
                .setPositiveButton("OK", null).show();
            return;
        }
        String[] labels = {"USB cable", "Wi-Fi · " + pairing.optString("wifiUrl")};
        new AlertDialog.Builder(this).setTitle("Connect to your Mac")
            .setSingleChoiceItems(labels, endpoint.contains("127.0.0.1") ? 0 : 1, (dialog, which) -> {
                endpoint = pairing.optString(which == 0 ? "usbUrl" : "wifiUrl");
                getPreferences(0).edit().putString("endpoint", endpoint).apply();
                connected = false; lastRevision = -1; status.setText("Connecting…"); refreshButtons(); dialog.dismiss();
            }).setNegativeButton("Close", null).show();
    }
    private void showText(String title, String value) {
        ScrollView s = new ScrollView(this); TextView t = text(value.isEmpty() ? "No changes yet." : value, 16);
        t.setPadding(dp(16), dp(8), dp(16), dp(8)); t.setTextIsSelectable(true); s.addView(t);
        new AlertDialog.Builder(this).setTitle(title).setView(s).setPositiveButton("Close", null).show();
    }
    private void showPending() {
        if (state == null) return;
        JSONArray list = state.optJSONArray("approvals");
        if (list == null || list.length() == 0) return;
        JSONObject request = list.optJSONObject(0), details = request.optJSONObject("details");
        String id = request.optString("id"), kind = request.optString("kind");
        if (kind.endsWith("requestUserInput")) { showQuestions(id, details); return; }
        StringBuilder value = new StringBuilder();
        if (!details.isNull("command")) value.append(details.optString("command")).append("\n\n");
        if (!details.isNull("cwd")) value.append("Folder: ").append(details.optString("cwd")).append("\n\n");
        if (!details.isNull("reason")) value.append(details.optString("reason")).append("\n\n");
        if (!request.optString("itemText").isEmpty() && !request.optString("itemText").equals(details.optString("command"))) value.append(request.optString("itemText")).append("\n\n");
        if (kind.contains("permissions")) {
            try { value.append(details.optJSONObject("permissions").toString(2)); } catch (Exception ignored) { value.append(details.toString()); }
        }
        if (value.length() == 0) value.append(details.toString());
        Dialog dialog = new Dialog(this, android.R.style.Theme_Material_Light_NoActionBar);
        LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(12), dp(8), dp(12), dp(8)); layout.setBackgroundColor(Color.WHITE);
        TextView heading = text("Review action", 23); heading.setTypeface(null, Typeface.BOLD); layout.addView(heading);
        layout.addView(text("Scroll to read. Buttons stay below.", 14));
        layout.addView(button("Back to task", v -> dialog.dismiss()), new LinearLayout.LayoutParams(-1, dp(44)));
        ScrollView sc = new ScrollView(this); sc.setFillViewport(true); sc.setSmoothScrollingEnabled(false);
        TextView t = text(value.toString(), 17);
        t.setPadding(0, dp(12), 0, dp(12)); t.setTextIsSelectable(true); sc.addView(t);
        layout.addView(sc, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout buttons = row();
        buttons.addView(button("Deny", v -> { dialog.dismiss(); decision(id, "decline"); }), weight());
        buttons.addView(button("Approve once", v -> { dialog.dismiss(); decision(id, "accept"); }), weight());
        layout.addView(buttons);
        dialog.setContentView(layout); dialog.show();
        dialog.getWindow().setLayout(-1, -1);
    }
    private void decision(String id, String choice) {
        try { post("/approval", new JSONObject().put("id", id).put("decision", choice), () -> {}); }
        catch (JSONException e) { toast(e.getMessage()); }
    }
    private void showQuestions(String id, JSONObject details) {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(16), 0, dp(16), 0);
        Map<String, EditText> fields = new LinkedHashMap<>();
        JSONArray questions = details.optJSONArray("questions");
        for (int i=0; questions != null && i<questions.length(); i++) {
            JSONObject q = questions.optJSONObject(i);
            box.addView(text(q.optString("question"), 18));
            EditText input = new EditText(this); input.setTextColor(Color.BLACK); input.setTextSize(18);
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            fields.put(q.optString("id"), input); box.addView(input);
            JSONArray options = q.optJSONArray("options");
            for (int j=0; options != null && j<options.length(); j++) {
                String label = options.optJSONObject(j).optString("label");
                box.addView(button(label, v -> input.setText(label)));
            }
        }
        ScrollView s = new ScrollView(this); s.addView(box);
        new AlertDialog.Builder(this).setTitle("Codex needs your answer").setView(s)
            .setPositiveButton("Send answer", (d,w) -> {
                try {
                    JSONObject answers = new JSONObject();
                    for (Map.Entry<String, EditText> f : fields.entrySet()) answers.put(f.getKey(), f.getValue().getText().toString());
                    post("/approval", new JSONObject().put("id",id).put("decision","answer").put("answers",answers), () -> {});
                } catch(JSONException e) { toast(e.getMessage()); }
            }).setNegativeButton("Later", null).show();
    }
}
