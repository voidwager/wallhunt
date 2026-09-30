package dev.voidwager.wallhunt;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Insets;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A grid of every wallpaper Wallhunt has set, newest first. Tap one to set it again, open its source, or delete it. */
public class HistoryActivity extends Activity {
    private static final int COLS = 3;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat when = new SimpleDateFormat("d MMM, HH:mm", Locale.getDefault());
    private final SimpleDateFormat day = new SimpleDateFormat("d MMM", Locale.getDefault());

    private History history;
    private LinearLayout grid;
    private TextView count;
    private float aspect;   // screen height / width
    private boolean busy;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        Rect s = getWindowManager().getMaximumWindowMetrics().getBounds();
        aspect = (float) s.height() / s.width();
        history = History.get(this);
        setContentView(build());
        render();
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private View build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setOnApplyWindowInsetsListener((v, in) -> {
            Insets i = in.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(dp(16) + i.left, dp(12) + i.top, dp(16) + i.right, i.bottom);
            return WindowInsets.CONSUMED;
        });

        LinearLayout head = Ui.row(this);
        TextView title = new TextView(this);
        title.setText("History");
        title.setTextColor(Ui.INK);
        title.setTextSize(22);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button back = Ui.button(this, "Done", false);
        back.setOnClickListener(v -> finish());
        head.addView(back);
        root.addView(head);

        count = new TextView(this);
        count.setTextColor(Ui.DIM);
        count.setTextSize(13);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
        cp.topMargin = dp(6);
        cp.bottomMargin = dp(10);
        root.addView(count, cp);

        ScrollView scroll = new ScrollView(this);
        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setPadding(0, 0, 0, dp(24));
        scroll.addView(grid);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        return root;
    }

    private void render() {
        grid.removeAllViews();
        List<History.Item> items = history.items();
        count.setText(items.isEmpty()
                ? "Nothing yet. Wallpapers you set from Wallhunt show up here."
                : items.size() + (items.size() == 1 ? " wallpaper" : " wallpapers") + ". Tap one to set it again.");
        int cellW = (getResources().getDisplayMetrics().widthPixels - dp(32) - dp(8) * (COLS - 1)) / COLS;
        LinearLayout row = null;
        for (int i = 0; i < items.size(); i++) {
            if (i % COLS == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
                rp.bottomMargin = dp(12);
                grid.addView(row, rp);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cellW, -2);
            if (i % COLS != 0) lp.leftMargin = dp(8);
            row.addView(cell(items.get(i), cellW), lp);
        }
    }

    private View cell(History.Item item, int w) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setBackground(Ui.round(this, Ui.CARD, 10));
        img.setClipToOutline(true);
        c.addView(img, new LinearLayout.LayoutParams(w, Math.round(w * aspect)));
        load(item, img, w);

        TextView label = new TextView(this);
        label.setText(!item.prompt.isEmpty() ? item.prompt : item.original ? "Your original" : "Wallpaper");
        label.setTextColor(Ui.INK);
        label.setTextSize(12);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(4);
        c.addView(label, lp);

        TextView meta = new TextView(this);
        meta.setText(day.format(new Date(item.time)) + " · " + screens(item.screens));
        meta.setTextColor(Ui.DIM);
        meta.setTextSize(11);
        meta.setSingleLine(true);
        c.addView(meta);

        c.setOnClickListener(v -> open(item));
        return c;
    }

    /** Decodes a small copy off the main thread. */
    private void load(History.Item item, ImageView into, int w) {
        worker.execute(() -> {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(item.file.getPath(), o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= w) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            Bitmap bm = BitmapFactory.decodeFile(item.file.getPath(), o);
            if (bm != null) main.post(() -> into.setImageBitmap(bm));
        });
    }

    private void open(History.Item item) {
        if (busy) return;
        Dialog d = new Dialog(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(Ui.round(this, Ui.BG, 16));
        box.setPadding(dp(16), dp(16), dp(16), dp(16));

        int w = Math.round(getResources().getDisplayMetrics().widthPixels * 0.5f);
        ImageView big = new ImageView(this);
        big.setScaleType(ImageView.ScaleType.CENTER_CROP);
        big.setBackground(Ui.round(this, Ui.CARD, 12));
        big.setClipToOutline(true);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(w, Math.round(w * aspect));
        bp.gravity = Gravity.CENTER_HORIZONTAL;
        box.addView(big, bp);
        load(item, big, w);

        TextView info = new TextView(this);
        info.setText((!item.prompt.isEmpty() ? "“" + item.prompt + "”" : item.original ? "Your original wallpaper" : "Wallpaper")
                + "\n" + when.format(new Date(item.time)) + " · was on " + screens(item.screens)
                + (item.credit.isEmpty() ? "" : "\n" + item.credit));
        info.setTextColor(Ui.INK);
        info.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(-1, -2);
        ip.topMargin = dp(10);
        ip.bottomMargin = dp(10);
        box.addView(info, ip);

        LinearLayout set = Ui.row(this);
        String[] labels = {"Home", "Lock", "Both"};
        int[] flags = {History.HOME, History.LOCK, History.HOME | History.LOCK};
        String[] where = {"home screen", "lock screen", "both screens"};
        for (int i = 0; i < 3; i++) {
            int f = flags[i];
            String wh = where[i];
            Button b = Ui.button(this, labels[i], true);
            b.setOnClickListener(v -> { d.dismiss(); reuse(item, f, wh); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            lp.leftMargin = dp(3);
            lp.rightMargin = dp(3);
            set.addView(b, lp);
        }
        box.addView(set);

        LinearLayout more = Ui.row(this);
        if (!item.page.isEmpty()) {
            Button src = Ui.button(this, item.source.isEmpty() ? "Source" : Sources.name(item.source), false);
            src.setOnClickListener(v -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(item.page))));
            more.addView(src, new LinearLayout.LayoutParams(0, -2, 1));
        }
        Button del = Ui.button(this, "Delete", false);
        del.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Delete from history?")
                .setMessage(item.original
                        ? "This is your original wallpaper. Once deleted, Revert can't go back to it."
                        : "Revert will skip it. The wallpaper stays on screen if it's there now.")
                .setPositiveButton("Delete", (x, y) -> {
                    history.delete(item);
                    d.dismiss();
                    render();
                })
                .setNegativeButton("Cancel", null)
                .show());
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(0, -2, 1);
        if (!item.page.isEmpty()) dl.leftMargin = dp(6);
        more.addView(del, dl);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-1, -2);
        mp.topMargin = dp(8);
        box.addView(more, mp);

        d.setContentView(box);
        if (d.getWindow() != null) d.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        d.show();
    }

    private void reuse(History.Item item, int which, String where) {
        busy = true;
        worker.execute(() -> {
            try {
                history.reuse(item, which);
                main.post(() -> {
                    busy = false;
                    Toast.makeText(this, "Set on " + where, Toast.LENGTH_SHORT).show();
                    render();
                });
            } catch (IOException e) {
                main.post(() -> {
                    busy = false;
                    Toast.makeText(this, "Couldn't set it: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private static String screens(int which) {
        boolean h = (which & History.HOME) != 0, l = (which & History.LOCK) != 0;
        return h && l ? "home + lock" : h ? "home" : l ? "lock" : "–";
    }

    private int dp(int v) { return Ui.dp(this, v); }
}
