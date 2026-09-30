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
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.PermissionDeniedException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.UnauthorizedException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class MainActivity extends Activity {
    static final String PREFS = "wh";
    private static final String KEY = "api_key";
    private static final int BG = Ui.BG, CARD = Ui.CARD, INK = Ui.INK, DIM = Ui.DIM, ACCENT = Ui.ACCENT;
    private static final int MAX_RESULTS = 30, RANKED = 12, COLS = 3;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService thumbLoader = Executors.newFixedThreadPool(4);
    private final Handler main = new Handler(Looper.getMainLooper());

    private EditText prompt;
    private Button hunt, back, home, lock, both, source;
    private TextView status, reason, credit;
    private ImageView preview;
    private ScrollView results;
    private LinearLayout grid, previewPane;

    private Hunter hunter;          // null when no Claude key: plain keyword search, you pick from the grid
    private List<Sources.Wall> walls = new ArrayList<>();
    private final Map<String, String> why = new HashMap<>();      // Claude's reason, by wall id
    private Sources.Wall shown;     // the wall in the preview
    private String summary = "";    // status line for the grid
    private int generation;         // bumps per search, so late thumbnails from an old one are dropped
    private Bitmap current;
    private boolean busy, backRegistered;
    private History history;
    private Button undo, update;
    private String lastPrompt = "";

    private final OnBackInvokedCallback backToGrid = this::showGrid;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        history = History.get(this);
        setContentView(build());
        enable(undo, history.canRevert());
        String key = prefs().getString(KEY, "");
        hunter = key.isEmpty() ? null : new Hunter(key);
        showNothing();
        status.setText(intro());
        restore((Kept) getLastNonConfigurationInstance());
    }

    /**
     * What survives Android recreating this screen. Setting a wallpaper recreates it (Android 12+ re-themes
     * apps from the new wallpaper's colours), and so does rotating; without this, results vanish on every set.
     */
    private static final class Kept {
        List<Sources.Wall> walls;
        Map<String, String> why;
        Sources.Wall shown;
        Bitmap current;
        String summary, lastPrompt;
        boolean previewing;
    }

    @Override
    public Object onRetainNonConfigurationInstance() {
        Kept k = new Kept();
        k.walls = walls;
        k.why = new HashMap<>(why);
        k.shown = shown;
        k.current = current;
        k.summary = summary;
        k.lastPrompt = lastPrompt;
        k.previewing = previewPane.getVisibility() == View.VISIBLE;
        return k;
    }

    private void restore(Kept k) {
        if (k == null || k.walls.isEmpty()) return;
        walls = k.walls;
        why.putAll(k.why);
        shown = k.shown;
        current = k.current;
        summary = k.summary;
        lastPrompt = k.lastPrompt;
        fillGrid();
        if (k.previewing && shown != null && current != null) showWall();
        else showGrid();
    }

    @Override
    protected void onDestroy() {
        // Only being recreated (e.g. by the wallpaper we just set): let a running save finish, cut the rest.
        if (isChangingConfigurations()) worker.shutdown();
        else worker.shutdownNow();
        thumbLoader.shutdownNow();
        super.onDestroy();
    }

    private String intro() {
        return hunter == null
                ? "Describe a wallpaper and tap Hunt. Optional: add a Claude key under Keys to have AI rank the results."
                : "Describe a wallpaper. Claude picks where to search and ranks what it finds.";
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
        TextView version = new TextView(this);
        version.setText("v" + Updater.installedVersion(this) + " · updates");
        version.setTextColor(DIM);
        version.setTextSize(11);
        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.VERTICAL);
        brand.addView(title);
        brand.addView(version);
        brand.setOnClickListener(v -> about());
        head.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        Button histBtn = button("History", false);
        histBtn.setOnClickListener(v -> startActivity(new Intent(this, HistoryActivity.class)));
        head.addView(histBtn);
        undo = button("Revert", false);
        undo.setOnClickListener(v -> revert());
        LinearLayout.LayoutParams rp0 = new LinearLayout.LayoutParams(-2, -2);
        rp0.leftMargin = dp(8);
        head.addView(undo, rp0);
        Button keyBtn = button("Keys", false);
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
        LinearLayout.LayoutParams stp = new LinearLayout.LayoutParams(-1, -2);
        stp.topMargin = dp(10);
        root.addView(status, stp);

        // The stage below the status line shows either the results grid or one wall's preview.
        FrameLayout stage = new FrameLayout(this);
        LinearLayout.LayoutParams stg = new LinearLayout.LayoutParams(-1, 0, 1);
        stg.topMargin = dp(12);
        root.addView(stage, stg);

        results = new ScrollView(this);
        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        results.addView(grid);
        stage.addView(results, new FrameLayout.LayoutParams(-1, -1));

        previewPane = new LinearLayout(this);
        previewPane.setOrientation(LinearLayout.VERTICAL);
        stage.addView(previewPane, new FrameLayout.LayoutParams(-1, -1));

        preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        previewPane.addView(preview, new LinearLayout.LayoutParams(-1, 0, 1));

        reason = new TextView(this);
        reason.setTextColor(INK);
        reason.setTextSize(14);
        reason.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
        rp.topMargin = dp(8);
        previewPane.addView(reason, rp);

        credit = new TextView(this);
        credit.setTextColor(DIM);
        credit.setTextSize(12);
        credit.setGravity(Gravity.CENTER_HORIZONTAL);
        credit.setOnClickListener(v -> {
            if (shown != null && !shown.creditUrl.isEmpty()) {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(shown.creditUrl)));
            }
        });
        LinearLayout.LayoutParams crp = new LinearLayout.LayoutParams(-1, -2);
        crp.topMargin = dp(2);
        previewPane.addView(credit, crp);

        LinearLayout nav = row();
        back = button("‹ Results", false);
        back.setOnClickListener(v -> showGrid());
        source = button("Open source", false);
        source.setOnClickListener(v -> {
            if (shown != null) startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(shown.page)));
        });
        nav.addView(back, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams sb = new LinearLayout.LayoutParams(0, -2, 1);
        sb.leftMargin = dp(6);
        nav.addView(source, sb);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-1, -2);
        np.topMargin = dp(8);
        previewPane.addView(nav, np);

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
        LinearLayout.LayoutParams setp = new LinearLayout.LayoutParams(-1, -2);
        setp.topMargin = dp(6);
        previewPane.addView(set, setp);
        return root;
    }

    // ---- the hunt ----

    private void startHunt() {
        String q = prompt.getText().toString().trim();
        if (busy || q.isEmpty()) return;
        getSystemService(InputMethodManager.class).hideSoftInputFromWindow(prompt.getWindowToken(), 0);
        setBusy(true);
        showNothing();
        Rect screen = screen();
        Hunter ai = hunter;
        worker.execute(() -> {
            try {
                List<String> available = available();
                List<String> sources = available, queries = Sources.keywordQueries(q);
                Hunter.Plan plan = null;
                String note = "";
                if (ai != null) {
                    try {
                        say("Claude is working out what to search for...");
                        plan = ai.plan(q, available);
                        sources = plan.sources;
                        queries = plan.queries;
                    } catch (Exception e) {
                        note = claudeProblem(e) + " Showing plain search results.";
                    }
                }
                int minW = Math.min(screen.width(), 1080), minH = Math.min(screen.height(), 1920);
                List<Sources.Wall> found = new ArrayList<>();
                List<String> problems = new ArrayList<>();
                say("Searching " + names(sources) + ": " + String.join(" / ", queries));
                gather(found, sources, queries, minW, minH, problems);
                List<String> others = new ArrayList<>(available);
                others.removeAll(sources);
                if (found.size() < 4 && !others.isEmpty()) {
                    say("Few matches, also trying " + names(others) + "...");
                    gather(found, others, queries, minW, minH, problems);
                }
                if (found.size() < 4) {
                    // Portrait images are scarce; retry each query on its leading keyword before giving up.
                    List<String> broad = new ArrayList<>();
                    for (String s : queries) {
                        String w = s.trim().split("\\s+")[0];
                        if (!broad.contains(w)) broad.add(w);
                    }
                    say("Still few, widening to: " + String.join(" / ", broad));
                    gather(found, available, broad, minW, minH, problems);
                }
                if (found.isEmpty()) {
                    fail((note.isEmpty() ? "" : note + "\n") + "No portrait images matched " + queries
                            + ". Try broader words." + (problems.isEmpty() ? "" : "\n" + String.join("\n", problems)));
                    return;
                }

                Map<String, String> reasons = new HashMap<>();
                if (plan != null) {
                    try {
                        List<Sources.Wall> judged = new ArrayList<>(found.subList(0, Math.min(RANKED, found.size())));
                        say("Fetching " + judged.size() + " thumbnails for Claude...");
                        List<byte[]> thumbs = new ArrayList<>();
                        // Crop each thumbnail the way fitScreen will crop the full image, so Claude judges what you get.
                        for (Sources.Wall w : judged) thumbs.add(screenCrop(Sources.get(w.thumb), screen));
                        say("Claude is looking at " + judged.size() + " candidates...");
                        List<Sources.Wall> top = new ArrayList<>();
                        for (Hunter.Pick p : ai.rank(q, plan.look, judged, thumbs)) {
                            Sources.Wall w = judged.get(p.index);
                            if (top.contains(w)) continue;
                            top.add(w);
                            reasons.put(w.id, "#" + top.size() + " · " + p.why);
                        }
                        found.removeAll(top);
                        found.addAll(0, top);
                    } catch (Exception e) {
                        note = claudeProblem(e) + " Showing results unranked.";
                    }
                }
                String sum = found.size() + " results from " + names(sourcesOf(found))
                        + (reasons.isEmpty() ? "" : " · Claude's top " + reasons.size() + " first")
                        + ". Tap one to preview." + (note.isEmpty() ? "" : "\n" + note)
                        + (problems.isEmpty() ? "" : "\n" + String.join("\n", problems));
                main.post(() -> {
                    walls = found;
                    why.clear();
                    why.putAll(reasons);
                    lastPrompt = q;
                    summary = sum;
                    setBusy(false);
                    fillGrid();
                    showGrid();
                });
            } catch (RuntimeException e) {
                fail("Something went wrong: " + e.getMessage());
            }
        });
    }

    /** A Claude failure as one short sentence; the hunt carries on without it. */
    private static String claudeProblem(Exception e) {
        if (e instanceof Hunter.RefusedException) return "Claude declined this one.";
        if (e instanceof UnauthorizedException || e instanceof PermissionDeniedException) {
            return "Claude key rejected (fix it under Keys).";
        }
        if (e instanceof RateLimitException) return "Claude is rate limited right now.";
        if (e instanceof AnthropicServiceException) {
            return "Claude API error " + ((AnthropicServiceException) e).statusCode() + ".";
        }
        if (e instanceof AnthropicIoException) return "Couldn't reach Claude.";
        return "Claude step failed (" + e.getClass().getSimpleName() + ").";
    }

    private void fillGrid() {
        int gen = ++generation;
        grid.removeAllViews();
        int cellW = (results.getWidth() > 0 ? results.getWidth() : getResources().getDisplayMetrics().widthPixels - dp(32));
        cellW = (cellW - dp(8) * (COLS - 1)) / COLS;
        Rect s = screen();
        int cellH = Math.round(cellW * (float) s.height() / s.width());
        LinearLayout row = null;
        for (int i = 0; i < walls.size(); i++) {
            if (i % COLS == 0) {
                row = new LinearLayout(this);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
                rp.bottomMargin = dp(8);
                grid.addView(row, rp);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cellW, cellH);
            if (i % COLS != 0) lp.leftMargin = dp(8);
            row.addView(cell(walls.get(i), cellW, gen), lp);
        }
        results.scrollTo(0, 0);
    }

    private View cell(Sources.Wall w, int cellW, int gen) {
        FrameLayout f = new FrameLayout(this);
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setBackground(round(CARD, 10));
        img.setClipToOutline(true);
        f.addView(img, new FrameLayout.LayoutParams(-1, -1));

        String r = why.get(w.id);
        if (r != null) {
            TextView badge = tag(r.substring(0, r.indexOf(' ')), ACCENT, BG);
            FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
            bl.setMargins(dp(6), dp(6), 0, 0);
            f.addView(badge, bl);
        }
        TextView src = tag(Sources.name(w.source), 0xB0000000, INK);
        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        sl.setMargins(dp(6), 0, 0, dp(6));
        f.addView(src, sl);

        f.setOnClickListener(v -> openWall(w));
        thumbLoader.execute(() -> {
            if (gen != generation) return;
            try {
                byte[] data = Sources.get(w.thumb);
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(data, 0, data.length, o);
                int sample = 1;
                while (o.outWidth / (sample * 2) >= cellW) sample *= 2;
                o = new BitmapFactory.Options();
                o.inSampleSize = sample;
                o.inPreferredConfig = Bitmap.Config.RGB_565;
                Bitmap bm = BitmapFactory.decodeByteArray(data, 0, data.length, o);
                if (bm != null) main.post(() -> { if (gen == generation) img.setImageBitmap(bm); });
            } catch (IOException | RuntimeException ignored) {
                // a missing thumbnail leaves the tile blank; the wall can still be opened
            }
        });
        return f;
    }

    private TextView tag(String text, int bg, int fg) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(11);
        t.setTextColor(fg);
        t.setBackground(round(bg, 6));
        t.setPadding(dp(6), dp(2), dp(6), dp(2));
        return t;
    }

    private void openWall(Sources.Wall w) {
        if (busy) return;
        setBusy(true);
        Rect screen = screen();
        worker.execute(() -> {
            try {
                say("Downloading " + Sources.name(w.source) + " image (" + w.resolution + ")...");
                Bitmap bm = fitScreen(Sources.get(w.full), screen.width(), screen.height());
                main.post(() -> {
                    if (current != null) current.recycle();
                    current = bm;
                    shown = w;
                    setBusy(false);
                    showWall();
                });
            } catch (IOException | RuntimeException e) {
                fail("Couldn't load that image (" + e.getMessage() + "). Try another one.");
            }
        });
    }

    /** Fills the preview pane from {@link #shown} and {@link #current}, and switches to it. */
    private void showWall() {
        Sources.Wall w = shown;
        preview.setImageBitmap(current);
        String r = why.get(w.id);
        reason.setText(r == null ? "" : r);
        reason.setVisibility(r == null ? View.GONE : View.VISIBLE);
        credit.setText(w.credit);
        credit.setVisibility(w.credit.isEmpty() ? View.GONE : View.VISIBLE);
        source.setText("Open on " + Sources.name(w.source));
        status.setText(Sources.name(w.source) + " · " + w.resolution);
        showPreview();
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
        Sources.Wall w = shown;
        setBusy(true);
        worker.execute(() -> {
            try {
                ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
                bm.compress(Bitmap.CompressFormat.JPEG, 95, jpeg);
                if (w == null) history.set(jpeg.toByteArray(), which, lastPrompt, "", "", "");
                else {
                    history.set(jpeg.toByteArray(), which, lastPrompt, w.source, w.page, w.credit);
                    Sources.reportUse(w, prefs().getString(Sources.UNSPLASH + "_key", ""));
                }
                main.post(() -> {
                    setBusy(false);
                    Toast.makeText(getApplicationContext(), "Set on " + where, Toast.LENGTH_SHORT).show();
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
                    Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_LONG).show();
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

    // ---- which pane shows ----

    private void showNothing() {
        results.setVisibility(View.GONE);
        previewPane.setVisibility(View.GONE);
        setBackToGrid(false);
    }

    private void showGrid() {
        results.setVisibility(View.VISIBLE);
        previewPane.setVisibility(View.GONE);
        status.setText(summary);
        setBackToGrid(false);
    }

    private void showPreview() {
        results.setVisibility(View.GONE);
        previewPane.setVisibility(View.VISIBLE);
        setBackToGrid(true);
    }

    /** While a preview is open, the system back gesture returns to the grid instead of closing the app. */
    private void setBackToGrid(boolean on) {
        if (on == backRegistered) return;
        OnBackInvokedDispatcher d = getOnBackInvokedDispatcher();
        if (on) d.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, backToGrid);
        else d.unregisterOnBackInvokedCallback(backToGrid);
        backRegistered = on;
    }

    // ---- self-update from GitHub releases ----

    @Override
    protected void onResume() {
        super.onResume();
        // The History screen may have set or deleted wallpapers; Revert's state can have changed.
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

    /** Version, last update check, and a manual "Check now". */
    private void about() {
        long at = prefs().getLong("upd_at", 0);
        String last = at == 0 ? "never"
                : System.currentTimeMillis() - at < 60_000 ? "just now"
                : android.text.format.DateUtils.getRelativeTimeSpanString(at, System.currentTimeMillis(),
                        android.text.format.DateUtils.MINUTE_IN_MILLIS).toString();
        new AlertDialog.Builder(this)
                .setTitle("Wallhunt " + Updater.installedVersion(this))
                .setMessage("Updates come from github.com/" + Updater.REPO + ". It checks by itself every 6 hours "
                        + "while open.\n\nLast checked: " + last)
                .setPositiveButton("Check now", (d, x) -> checkNow())
                .setNegativeButton("Close", null)
                .show();
    }

    private void checkNow() {
        if (busy) return;
        setBusy(true);
        say("Checking GitHub for updates...");
        worker.execute(() -> {
            String err = Updater.check(prefs());
            main.post(() -> {
                setBusy(false);
                showUpdate();
                if (err != null) {
                    status.setText("Couldn't check for updates: " + err);
                    return;
                }
                Updater.Release r = Updater.available(this, prefs());
                if (r == null) status.setText("You're on the latest version (" + Updater.installedVersion(this) + ").");
                else {
                    status.setText("Wallhunt " + r.version + " is available.");
                    update.performClick();
                }
            });
        });
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

    // ---- keys ----

    /** All keys are optional: Claude ranks results; Unsplash and Pexels switch those sources on. */
    private void askKey() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(4), dp(20), 0);
        EditText claude = keyField(box, "Claude (optional, paid) · AI ranking · console.anthropic.com", KEY,
                "sk-ant-...");
        EditText unsplash = keyField(box, "Unsplash (optional, free) · unsplash.com/developers",
                Sources.UNSPLASH + "_key", "Access key");
        EditText pexels = keyField(box, "Pexels (optional, free) · pexels.com/api", Sources.PEXELS + "_key",
                "API key");
        new AlertDialog.Builder(this)
                .setTitle("API keys")
                .setMessage("All optional, stored on this phone only. Wallhaven and Openverse work without any key.")
                .setView(box)
                .setPositiveButton("Save", (d, b) -> {
                    String k = claude.getText().toString().trim();
                    prefs().edit()
                            .putString(KEY, k)
                            .putString(Sources.UNSPLASH + "_key", unsplash.getText().toString().trim())
                            .putString(Sources.PEXELS + "_key", pexels.getText().toString().trim())
                            .apply();
                    hunter = k.isEmpty() ? null : new Hunter(k);
                    if (!busy && walls.isEmpty()) status.setText(intro());
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private EditText keyField(LinearLayout box, String label, String pref, String hint) {
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextSize(12);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(10);
        box.addView(l, lp);
        EditText in = new EditText(this);
        in.setHint(hint);
        in.setSingleLine(true);
        in.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        in.setText(prefs().getString(pref, ""));
        box.addView(in);
        return in;
    }

    // ---- sources ----

    /** Sources this install can search: the keyless ones plus any whose key is set. */
    private List<String> available() {
        List<String> out = new ArrayList<>();
        for (String src : Sources.ALL) {
            if (!Sources.needsKey(src) || !prefs().getString(src + "_key", "").isEmpty()) out.add(src);
        }
        return out;
    }

    /**
     * Tops {@code found} up to {@link #MAX_RESULTS}, searching {@code sources} in parallel and interleaving their
     * results so no one source crowds the grid. A failing source is noted in {@code problems} and skipped.
     */
    private void gather(List<Sources.Wall> found, List<String> sources, List<String> queries, int minW, int minH,
                        List<String> problems) {
        int room = MAX_RESULTS - found.size();
        if (room <= 0 || sources.isEmpty()) return;
        int each = Math.max(4, (room + sources.size() - 1) / sources.size());
        ExecutorService pool = Executors.newFixedThreadPool(sources.size());
        List<Future<List<Sources.Wall>>> jobs = new ArrayList<>();
        for (String src : sources) {
            String key = prefs().getString(src + "_key", "");
            jobs.add(pool.submit(() -> Sources.search(src, key, queries, minW, minH, each)));
        }
        List<List<Sources.Wall>> lists = new ArrayList<>();
        for (int i = 0; i < jobs.size(); i++) {
            try {
                lists.add(jobs.get(i).get());
            } catch (ExecutionException e) {
                String p = Sources.name(sources.get(i)) + ": " + (e.getCause() == null ? e : e.getCause().getMessage());
                if (!problems.contains(p)) problems.add(p);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        pool.shutdown();
        for (int r = 0; found.size() < MAX_RESULTS; r++) {
            boolean any = false;
            for (List<Sources.Wall> l : lists) {
                if (r >= l.size() || found.size() >= MAX_RESULTS) continue;
                any = true;
                Sources.Wall w = l.get(r);
                boolean dup = false;
                for (Sources.Wall f : found) dup |= f.id.equals(w.id);
                if (!dup) found.add(w);
            }
            if (!any) break;
        }
    }

    private static List<String> sourcesOf(List<Sources.Wall> walls) {
        List<String> out = new ArrayList<>();
        for (Sources.Wall w : walls) if (!out.contains(w.source)) out.add(w.source);
        return out;
    }

    private static String names(List<String> sources) {
        List<String> n = new ArrayList<>();
        for (String s : sources) n.add(Sources.name(s));
        return String.join(" + ", n);
    }

    // ---- state + helpers ----

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
        for (Button x : new Button[]{back, source, home, lock, both}) enable(x, !b);
        enable(undo, !b && history.canRevert());
        enable(update, !b);
    }

    private static void enable(Button b, boolean on) {
        b.setEnabled(on);
        b.setAlpha(on ? 1f : 0.4f);
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
