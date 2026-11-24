package kz.bulaevo.audit;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int BG = 0xFF101820, CARD = 0xFF1B2A3A, TEXT = 0xFFE8EEF4, DIM = 0xFF9AA8B6;
    private static final int[] LEVEL_COLOR = {0xFF5B8DEF, 0xFFE0C341, 0xFFF08A3C, 0xFFE5484D};

    private LinearLayout list;
    private TextView status;
    private ProgressBar bar;
    private Button scanBtn, saveBtn, shareBtn, textBtn;
    private JSONObject report;   // сырые данные + разбор
    private Analyzer analyzer;
    private Uri savedUri;
    private boolean showAllApps;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(BG);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        root.setPadding(p, p, p, p * 2);
        sv.addView(root);

        TextView title = text("Аудит телефона", 24, TEXT, true);
        root.addView(title);
        TextView sub = text("Тренировочный сканер: проверяет настройки защиты и установленные приложения. "
                + "Ничего не меняет и никуда не отправляет — отчёт сохраняется у вас.", 14, DIM, false);
        sub.setPadding(0, dp(4), 0, dp(12));
        root.addView(sub);

        scanBtn = button("Сканировать");
        scanBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                scan();
            }
        });
        root.addView(scanBtn, fullWidth());

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        saveBtn = button("Сохранить");
        shareBtn = button("Отправить файл");
        textBtn = button("Текстом");
        row.addView(saveBtn, weight());
        row.addView(shareBtn, weight());
        row.addView(textBtn, weight());
        root.addView(row);
        saveBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                save(true);
            }
        });
        shareBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                shareFile();
            }
        });
        textBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                shareText();
            }
        });
        setResultButtons(false);

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setIndeterminate(true);
        bar.setVisibility(View.GONE);
        root.addView(bar, fullWidth());

        status = text("Нажмите «Сканировать». Проверка занимает от 10 секунд до пары минут.", 14, DIM, false);
        status.setPadding(0, dp(8), 0, dp(8));
        root.addView(status);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list);

        setContentView(sv);

        if (getIntent() != null && getIntent().getBooleanExtra("autoscan", false)) scan();
    }

    // ---------------------------------------------------------------- сканирование

    private void scan() {
        scanBtn.setEnabled(false);
        setResultButtons(false);
        bar.setVisibility(View.VISIBLE);
        list.removeAllViews();
        savedUri = null;
        new Thread(new Runnable() {
            public void run() {
                try {
                    JSONObject raw = new Collector(MainActivity.this, new Collector.Progress() {
                        public void onProgress(final String t) {
                            runOnUiThread(new Runnable() {
                                public void run() {
                                    status.setText(t + "…");
                                }
                            });
                        }
                    }).collect();
                    final Analyzer an = new Analyzer(raw);
                    final JSONObject full = new JSONObject();
                    full.put("analysis", an.toJson());
                    full.put("raw", raw);
                    writePrivateCopy(full);
                    runOnUiThread(new Runnable() {
                        public void run() {
                            report = full;
                            analyzer = an;
                            show();
                        }
                    });
                } catch (final Throwable e) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            status.setText("Ошибка: " + e);
                        }
                    });
                } finally {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            bar.setVisibility(View.GONE);
                            scanBtn.setEnabled(true);
                        }
                    });
                }
            }
        }).start();
    }

    /** Копия последнего отчёта в папке приложения (для отладки через adb). */
    private void writePrivateCopy(JSONObject full) {
        try {
            File dir = getExternalFilesDir(null);
            if (dir == null) return;
            FileOutputStream out = new FileOutputStream(new File(dir, "last-report.json"));
            out.write(full.toString(1).getBytes(StandardCharsets.UTF_8));
            out.close();
        } catch (Exception ignored) {
        }
    }

    // ---------------------------------------------------------------- показ

    private void show() {
        setResultButtons(true);
        list.removeAllViews();
        int[] cnt = new int[4];
        for (Analyzer.Finding f : analyzer.findings) cnt[f.level]++;
        int riskyApps = 0;
        for (Analyzer.AppRisk a : analyzer.apps) if (a.level() >= Analyzer.MED) riskyApps++;
        status.setText("Готово. Высокий риск: " + cnt[3] + ", средний: " + cnt[2] + ", низкий: " + cnt[1]
                + "\nПриложений: своих " + analyzer.userApps + ", системных " + analyzer.systemApps
                + ", не из магазина " + analyzer.sideloaded + ", заметных по риску " + riskyApps
                + "\nЧтобы я разобрал отчёт подробно — «Отправить файл» и пришлите файл в чат.");

        list.addView(header("Настройки и система"));
        for (final Analyzer.Finding f : analyzer.findings) {
            LinearLayout c = card(f.level);
            c.addView(text(Analyzer.levelName(f.level) + " · " + f.title, 16, TEXT, true));
            if (!f.details.isEmpty()) c.addView(text(f.details, 13, DIM, false));
            if (!f.advice.isEmpty()) c.addView(text("→ " + f.advice, 14, TEXT, false));
            if (f.action != null) {
                Button go = smallButton("Открыть настройки");
                go.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        openSettings(f.action);
                    }
                });
                c.addView(go);
            }
            list.addView(c);
        }

        list.addView(header("Приложения по степени риска"));
        int shown = 0;
        for (final Analyzer.AppRisk a : analyzer.apps) {
            if (!showAllApps && a.level() < Analyzer.LOW) continue;
            shown++;
            LinearLayout c = card(a.level());
            c.addView(text(a.label + "  [" + a.score + "]", 16, TEXT, true));
            c.addView(text(a.pkg, 12, DIM, false));
            StringBuilder sb = new StringBuilder();
            for (String r : a.reasons) sb.append("• ").append(r).append('\n');
            c.addView(text(sb.toString().trim(), 13, TEXT, false));
            LinearLayout r = new LinearLayout(this);
            Button info = smallButton("О приложении");
            Button del = smallButton("Удалить");
            info.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    start(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.pkg)));
                }
            });
            del.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    start(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + a.pkg)));
                }
            });
            r.addView(info);
            r.addView(del);
            c.addView(r);
            list.addView(c);
        }
        if (shown == 0) list.addView(text("Явно подозрительных приложений не найдено.", 14, DIM, false));
        Button more = smallButton(showAllApps ? "Скрыть малозначимые" : "Показать все с баллами");
        more.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showAllApps = !showAllApps;
                show();
            }
        });
        list.addView(more);
    }

    // ---------------------------------------------------------------- отчёт: сохранить / поделиться

    private String fileName() {
        return "audit-" + new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date()) + ".json";
    }

    private Uri save(boolean toast) {
        if (report == null) return null;
        if (savedUri != null) {
            if (toast) toast("Уже сохранено в «Загрузки»");
            return savedUri;
        }
        try {
            byte[] data = report.toString(1).getBytes(StandardCharsets.UTF_8);
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName());
                cv.put(MediaStore.MediaColumns.MIME_TYPE, "application/json");
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/Audit");
                Uri u = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (u == null) throw new IllegalStateException("MediaStore insert = null");
                OutputStream out = getContentResolver().openOutputStream(u);
                out.write(data);
                out.close();
                savedUri = u;
                if (toast) toast("Сохранено: Загрузки/Audit/");
                return u;
            } else {
                File dir = getExternalFilesDir(null);
                File f = new File(dir, fileName());
                FileOutputStream out = new FileOutputStream(f);
                out.write(data);
                out.close();
                if (toast) toast("Сохранено: " + f.getAbsolutePath());
                return null;
            }
        } catch (Exception e) {
            toast("Не удалось сохранить: " + e.getMessage());
            return null;
        }
    }

    private void shareFile() {
        Uri u = save(false);
        if (u == null) {
            toast("На этой версии Android файл отправить нельзя — отправляю текстом");
            shareText();
            return;
        }
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("application/json");
        i.putExtra(Intent.EXTRA_STREAM, u);
        i.putExtra(Intent.EXTRA_SUBJECT, "Отчёт аудита телефона");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        start(Intent.createChooser(i, "Отправить отчёт"));
    }

    private void shareText() {
        if (report == null) return;
        String s = analyzer.summaryText(report.optJSONObject("raw"));
        if (s.length() > 60000) s = s.substring(0, 60000) + "\n…(обрезано)";
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, s);
        start(Intent.createChooser(i, "Отправить краткий отчёт"));
    }

    // ---------------------------------------------------------------- переходы в настройки

    private void openSettings(String what) {
        Intent i;
        switch (what) {
            case "update": i = new Intent("android.settings.SYSTEM_UPDATE_SETTINGS"); break;
            case "lock": i = new Intent(DevicePolicyManager.ACTION_SET_NEW_PASSWORD); break;
            case "dev": i = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS); break;
            case "playprotect": i = new Intent("com.google.android.gms.settings.VERIFY_APPS_SETTINGS"); break;
            case "wifi": i = new Intent(Settings.ACTION_WIFI_SETTINGS); break;
            case "network": i = new Intent(Settings.ACTION_WIRELESS_SETTINGS); break;
            case "notif": i = new Intent("android.settings.NOTIFICATION_SETTINGS"); break;
            case "accessibility": i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS); break;
            case "admins":
                i = new Intent().setComponent(new ComponentName("com.android.settings",
                        "com.android.settings.DeviceAdminSettings"));
                break;
            case "notiflisteners": i = new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"); break;
            case "defaults": i = new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS); break;
            case "keyboard": i = new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS); break;
            default: i = new Intent(Settings.ACTION_SECURITY_SETTINGS);
        }
        if (!start(i)) {
            if (!start(new Intent(Settings.ACTION_SECURITY_SETTINGS))) start(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private boolean start(Intent i) {
        try {
            startActivity(i);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- вёрстка

    private void setResultButtons(boolean on) {
        saveBtn.setEnabled(on);
        shareBtn.setEnabled(on);
        textBtn.setEnabled(on);
        float a = on ? 1f : 0.4f;
        saveBtn.setAlpha(a);
        shareBtn.setAlpha(a);
        textBtn.setAlpha(a);
    }

    private TextView header(String s) {
        TextView t = text(s, 18, TEXT, true);
        t.setPadding(0, dp(18), 0, dp(6));
        return t;
    }

    private LinearLayout card(int level) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(dp(10));
        bg.setStroke(dp(2), LEVEL_COLOR[Math.max(0, Math.min(3, level))]);
        c.setBackground(bg);
        LinearLayout.LayoutParams lp = fullWidth();
        lp.setMargins(0, dp(4), 0, dp(4));
        c.setLayoutParams(lp);
        return c;
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextIsSelectable(true);
        return t;
    }

    private Button button(String s) {
        Button btn = new Button(this);
        btn.setText(s);
        btn.setAllCaps(false);
        btn.setTextColor(Color.WHITE);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF2E9E6B);
        bg.setCornerRadius(dp(8));
        btn.setBackground(bg);
        return btn;
    }

    private Button smallButton(String s) {
        Button btn = new Button(this);
        btn.setText(s);
        btn.setAllCaps(false);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        btn.setTextColor(TEXT);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF2A3D52);
        bg.setCornerRadius(dp(6));
        btn.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(38));
        lp.setMargins(0, dp(6), dp(8), 0);
        btn.setLayoutParams(lp);
        btn.setPadding(dp(12), 0, dp(12), 0);
        btn.setGravity(Gravity.CENTER);
        return btn;
    }

    private LinearLayout.LayoutParams fullWidth() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(4), 0, dp(4));
        return lp;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(2), dp(4), dp(2), dp(4));
        return lp;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
