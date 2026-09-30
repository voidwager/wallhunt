package dev.voidwager.wallhunt;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.WallpaperManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Insets;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.PermissionDeniedException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.UnauthorizedException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    static final String PREFS = "wh";
    private static final String KEY = "api_key";
    private static final int BG = Ui.BG, CARD = Ui.CARD, INK = Ui.INK, DIM = Ui.DIM, ACCENT = Ui.ACCENT;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private EditText prompt;
    private Button hunt, next, home, lock, both, source;
    private TextView status, reason;
    private ImageView preview;

    private Hunter hunter;
    private List<Wallhaven.Wall> walls = new ArrayList<>();
    private List<Hunter.Pick> picks = new ArrayList<>();
    private int pos;
    private Bitmap current;
    private boolean busy;
    private History history;
    private Button undo, update;
    private String lastPrompt = "";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        history = new History(this, prefs());
        setContentView(build());
        enable(undo, history.canRevert());
        String key = prefs().getString(KEY, "");
        showResult(false);
        if (key.isEmpty()) askKey(); else hunter = new Hunter(key);
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private View build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((v, in) -> {
            Insets i = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
            v.setPadding(dp(16) + i.left, dp(12) + i.top, dp(16) + i.right, dp(12) + i.bottom);
            return WindowInsets.CONSUMED;
        });

        LinearLayout head = row();
        TextView title = new TextView(this);
        title.setText("Wallhunt");
        title.setTextColor(INK);
        title.setTextSize(22);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button histBtn = button("History", false);
        histBtn.setOnClickListener(v -> startActivity(new Intent(this, HistoryActivity.class)));
        head.addView(histBtn);
        undo = button("Revert", false);
        undo.setOnClickListener(v -> revert());
        LinearLayout.LayoutParams rp0 = new LinearLayout.LayoutParams(-2, -2);
        rp0.leftMargin = dp(8);
        head.addView(undo, rp0);
        Button keyBtn = button("Key", false);
        keyBtn.setOnClickListener(v -> askKey());
        LinearLayout.LayoutParams kp = new LinearLayout.LayoutParams(-2, -2);
        kp.leftMargin = dp(8);
        head.addView(keyBtn, kp);
        root.addView(head);

        update = button("", true);
        update.setVisibility(View.GONE);
        LinearLayout.LayoutParams up = new LinearLayout.LayoutParams(-1, -2);
        up.topMargin = dp(8);
        root.addView(update, up);

        LinearLayout search = row();
        prompt = new EditText(this);
        prompt.setHint("rainy neon street, calm");
        prompt.setHintTextColor(DIM);
        prompt.setTextColor(INK);
        prompt.setSingleLine(true);
        prompt.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        prompt.setBackground(round(CARD, 12));
        prompt.setPadding(dp(14), dp(10), dp(14), dp(10));
        prompt.setOnEditorActionListener((v, id, ev) -> {
            if (id == EditorInfo.IME_ACTION_SEARCH
                    || (ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER)) { startHunt(); return true; }
            return false;
        });
        search.addView(prompt, new LinearLayout.LayoutParams(0, -2, 1));
        hunt = button("Hunt", true);
        hunt.setOnClickListener(v -> startHunt());
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-2, -2);
        hp.leftMargin = dp(8);
        search.addView(hunt, hp);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.topMargin = dp(12);
        root.addView(search, sp);

        status = new TextView(this);
        status.setTextColor(DIM);
        status.setTextSize(13);
        status.setText("Describe a wallpaper. Claude searches Wallhaven, looks at what it finds and ranks it.");
        LinearLayout.LayoutParams stp = new LinearLayout.LayoutParams(-1, -2);
        stp.topMargin = dp(10);
        root.addView(status, stp);

        preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-1, 0, 1);
        pp.topMargin = dp(12);
        root.addView(preview, pp);

        reason = new TextView(this);
        reason.setTextColor(INK);
        reason.setTextSize(14);
        reason.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
        rp.topMargin = dp(8);
        root.addView(reason, rp);

        LinearLayout nav = row();
        next = button("Next pick", false);
        next.setOnClickListener(v -> showPick(pos + 1));
        source = button("Open on Wallhaven", false);
        source.setOnClickListener(v -> startActivity(new Intent(Intent.ACTION_VIEW,
                Uri.parse(walls.get(picks.get(pos).index).page))));
        nav.addView(next, new LinearLayout.LayoutParams(0, -2, 1));
        nav.addView(source, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-1, -2);
        np.topMargin = dp(8);
        root.addView(nav, np);

        LinearLayout set = row();
        home = button("Home", true);
        lock = button("Lock", true);
        both = button("Both", true);
        home.setOnClickListener(v -> apply(WallpaperManager.FLAG_SYSTEM, "home screen"));
        lock.setOnClickListener(v -> apply(WallpaperManager.FLAG_LOCK, "lock screen"));
        both.setOnClickListener(v -> apply(WallpaperManager.FLAG_SYSTEM | WallpaperManager.FLAG_LOCK, "both screens"));
        for (Button b : new Button[]{home, lock, both}) {
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, -2, 1);
            bp.leftMargin = dp(3);
            bp.rightMargin = dp(3);
            set.addView(b, bp);
        }
        root.addView(set, new LinearLayout.LayoutParams(-1, -2));
        return root;
    }

    // ---- the hunt ----

    private void startHunt() {
        String q = prompt.getText().toString().trim();
        if (busy || q.isEmpty()) return;
        if (hunter == null) { askKey(); return; }
        getSystemService(InputMethodManager.class).hideSoftInputFromWindow(prompt.getWindowToken(), 0);
        setBusy(true);
        showResult(false);
        Rect screen = screen();
        worker.execute(() -> {
            try {
                say("Claude is working out what to search for...");
                Hunter.Plan plan = hunter.plan(q);
                say("Searching Wallhaven: " + String.join(" / ", plan.queries));
                int minW = Math.min(screen.width(), 1080), minH = Math.min(screen.height(), 1920);
                List<Wallhaven.Wall> found = Wallhaven.search(plan.queries, minW, minH, 6, 12);
                if (found.size() < 4) {
                    // Portrait walls are scarce; retry each query on its leading keyword before giving up.
                    List<String> broad = new ArrayList<>();
                    for (String s : plan.queries) broad.add(s.trim().split("\\s+")[0]);
                    say("Few matches, widening to: " + String.join(" / ", broad));
                    for (Wallhaven.Wall w : Wallhaven.search(broad, minW, minH, 6, 12)) {
                        if (found.size() >= 12) break;
                        boolean dup = false;
                        for (Wallhaven.Wall f : found) dup |= f.id.equals(w.id);
                        if (!dup) found.add(w);
                    }
                }
                if (found.isEmpty()) {
                    fail("No portrait wallpapers matched " + plan.queries + ". Try broader words.");
                    return;
                }
                say("Fetching " + found.size() + " thumbnails...");
                List<byte[]> thumbs = new ArrayList<>();
                // Crop each thumbnail the way fitScreen will crop the full image, so Claude judges what you get.
                for (Wallhaven.Wall w : found) thumbs.add(screenCrop(Wallhaven.get(w.thumb), screen));
                say("Claude is looking at " + found.size() + " candidates...");
                List<Hunter.Pick> ranked = hunter.rank(q, plan.look, found, thumbs);
                if (ranked.isEmpty()) {
                    fail("Claude rejected all " + found.size() + " candidates. Rephrase and try again.");
                    return;
                }
                main.post(() -> {
                    walls = found;
                    lastPrompt = q;
                    picks = ranked;
                    showPick(0);
                });
            } catch (Hunter.RefusedException e) {
                fail(e.getMessage());
            } catch (UnauthorizedException | PermissionDeniedException e) {
                fail("The API key was rejected. Tap Key to replace it.");
            } catch (RateLimitException e) {
                fail("Rate limited by the Claude API. Wait a minute and try again.");
            } catch (AnthropicServiceException e) {
                fail("Claude API error " + e.statusCode() + ": " + e.getMessage());
            } catch (AnthropicIoException | IOException e) {
                fail("Network problem: " + e.getMessage());
            } catch (RuntimeException e) {
                fail("Something broke: " + e);
            }
        });
    }

    private void showPick(int i) {
        if (i >= picks.size() || busy) return;
        pos = i;
        Hunter.Pick p = picks.get(i);
        Wallhaven.Wall w = walls.get(p.index);
        setBusy(true);
        Rect screen = screen();
        worker.execute(() -> {
            try {
                say("Downloading pick " + (i + 1) + " of " + picks.size() + " (" + w.resolution + ")...");
                Bitmap bm = fitScreen(Wallhaven.get(w.full), screen.width(), screen.height());
                main.post(() -> {
                    if (current != null) current.recycle();
                    current = bm;
                    preview.setImageBitmap(bm);
                    reason.setText(p.why);
                    status.setText("Pick " + (i + 1) + " of " + picks.size() + " · " + w.resolution);
                    setBusy(false);
                    showResult(true);
                });
            } catch (IOException | RuntimeException e) {
                fail("Couldn't load that image: " + e.getMessage());
            }
        });
    }

    private void apply(int which, String where) {
        if (current == null || busy) return;
        if (history.firstTouch(which) && !Environment.isExternalStorageManager()
                && !prefs().getBoolean("asked_original", false)) {
            prefs().edit().putBoolean("asked_original", true).apply();
            new AlertDialog.Builder(this)
                    .setTitle("Keep your current wallpaper?")
                    .setMessage("Revert can always undo what Wallhunt sets. To also go back to the wallpaper "
                            + "you have now, Android requires \"All files access\". Wallhunt uses it only to "
                            + "save your current wallpaper once.\n\nAfter allowing, come back and tap the "
                            + "same button again.")
                    .setPositiveButton("Allow", (d, x) -> startActivity(new Intent(
                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:" + getPackageName()))))
                    .setNegativeButton("Skip", (d, x) -> apply(which, where))
                    .show();
            return;
        }
        Bitmap bm = current;
        String page = picks.isEmpty() ? "" : walls.get(picks.get(pos).index).page;
        setBusy(true);
        worker.execute(() -> {
            try {
                ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
                bm.compress(Bitmap.CompressFormat.JPEG, 95, jpeg);
                history.set(jpeg.toByteArray(), which, lastPrompt, page);
                main.post(() -> {
                    setBusy(false);
                    Toast.makeText(this, "Set on " + where, Toast.LENGTH_SHORT).show();
                });
            } catch (IOException e) {
                fail("Couldn't set the wallpaper: " + e.getMessage());
            }
        });
    }

    private void revert() {
        if (busy) return;
        setBusy(true);
        worker.execute(() -> {
            try {
                String msg = history.revert();
                main.post(() -> {
                    setBusy(false);
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                });
            } catch (IOException e) {
                fail("Couldn't revert: " + e.getMessage());
            }
        });
    }

    /** Decodes at the smallest sample size that still covers the screen, then centre-crops to it exactly. */
    static Bitmap fitScreen(byte[] data, int w, int h) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        if (o.outWidth <= 0) throw new IllegalStateException("not an image");
        int sample = 1;
        while (o.outWidth / (sample * 2) >= w && o.outHeight / (sample * 2) >= h) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        Bitmap src = BitmapFactory.decodeByteArray(data, 0, data.length, o);
        float scale = Math.max((float) w / src.getWidth(), (float) h / src.getHeight());
        int cw = Math.round(w / scale), ch = Math.round(h / scale);
        int x = (src.getWidth() - cw) / 2, y = (src.getHeight() - ch) / 2;
        Bitmap crop = Bitmap.createBitmap(src, Math.max(0, x), Math.max(0, y),
                Math.min(cw, src.getWidth()), Math.min(ch, src.getHeight()));
        Bitmap out = Bitmap.createScaledBitmap(crop, w, h, true);
        if (crop != src) src.recycle();
        if (out != crop) crop.recycle();
        return out;
    }

    /** A thumbnail centre-cropped to the screen's aspect ratio, re-encoded as JPEG. */
    static byte[] screenCrop(byte[] thumb, Rect screen) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(thumb, 0, thumb.length, o);
        int h = Math.min(o.outHeight, 600);
        int w = Math.round(h * (float) screen.width() / screen.height());
        Bitmap bm = fitScreen(thumb, w, h);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bm.compress(Bitmap.CompressFormat.JPEG, 85, out);
        bm.recycle();
        return out.toByteArray();
    }

    // ---- state + helpers ----

    // ---- self-update from GitHub releases ----

    @Override
    protected void onResume() {
        super.onResume();
        // The History screen may have set or deleted wallpapers; pick up its changes.
        history = new History(this, prefs());
        if (!busy) enable(undo, history.canRevert());
        showUpdate();
        if (Updater.due(prefs())) worker.execute(() -> {
            Updater.check(prefs());
            main.post(this::showUpdate);
        });
    }

    /** Shows the update button only while GitHub has a release newer than this install. */
    private void showUpdate() {
        Updater.Release r = Updater.available(this, prefs());
        update.setVisibility(r == null ? View.GONE : View.VISIBLE);
        if (r == null) return;
        update.setText("Update to " + r.version + " (" + r.size / (1024 * 1024) + " MB)");
        update.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Wallhunt " + r.version)
                .setMessage(r.notes.isEmpty() ? "A newer version is on GitHub." : r.notes)
                .setPositiveButton("Install", (d, x) -> installUpdate(r))
                .setNegativeButton("Later", null)
                .show());
    }

    private void installUpdate(Updater.Release r) {
        if (busy) return;
        if (!getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(this, "Allow Wallhunt to install updates, then tap Update again.", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
            return;
        }
        setBusy(true);
        worker.execute(() -> {
            try {
                java.io.File apk = Updater.download(this, r, pct -> say("Downloading update… " + pct + "%"));
                String err = Updater.verify(this, apk);
                if (err != null) {
                    fail("Update refused: " + err + ".");
                    return;
                }
                say("Installing " + r.version + "…");
                Updater.install(this, apk);
                main.post(() -> setBusy(false));
            } catch (IOException e) {
                fail("Update failed: " + e.getMessage());
            }
        });
    }

    private void askKey() {
        EditText in = new EditText(this);
        in.setHint("sk-ant-...");
        in.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        in.setText(prefs().getString(KEY, ""));
        new AlertDialog.Builder(this)
                .setTitle("Claude API key")
                .setMessage("Stored on this phone only. Create one at console.anthropic.com.")
                .setView(in)
                .setPositiveButton("Save", (d, b) -> {
                    String k = in.getText().toString().trim();
                    if (k.isEmpty()) return;
                    prefs().edit().putString(KEY, k).apply();
                    hunter = new Hunter(k);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void say(String s) { main.post(() -> status.setText(s)); }

    private void fail(String s) {
        main.post(() -> {
            status.setText(s);
            setBusy(false);
        });
    }

    private void setBusy(boolean b) {
        busy = b;
        enable(hunt, !b);
        for (Button x : new Button[]{source, home, lock, both}) enable(x, !b);
        enable(next, !b && pos + 1 < picks.size());
        enable(undo, !b && history.canRevert());
        enable(update, !b);
    }

    private static void enable(Button b, boolean on) {
        b.setEnabled(on);
        b.setAlpha(on ? 1f : 0.4f);
    }

    private void showResult(boolean on) {
        int v = on ? View.VISIBLE : View.INVISIBLE;
        for (View x : new View[]{preview, reason, next, source, home, lock, both}) x.setVisibility(v);
    }

    private Rect screen() {
        return getWindowManager().getMaximumWindowMetrics().getBounds();
    }

    private SharedPreferences prefs() { return getSharedPreferences(PREFS, MODE_PRIVATE); }

    private LinearLayout row() { return Ui.row(this); }

    private Button button(String label, boolean primary) { return Ui.button(this, label, primary); }

    private GradientDrawable round(int color, int r) { return Ui.round(this, color, r); }

    private int dp(int v) { return Ui.dp(this, v); }
}
