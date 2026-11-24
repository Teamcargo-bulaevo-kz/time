package kz.bulaevo.audit;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Первая (простая) версия разбора отчёта: правила без ИИ.
 * Итог — список находок по устройству и оценка риска каждого приложения.
 * Правила будут уточняться по реальным отчётам.
 */
public class Analyzer {

    public static final int HIGH = 3, MED = 2, LOW = 1, INFO = 0;

    public static class Finding {
        public final int level;
        public final String title;
        public final String details;
        public final String advice;
        public final String action; // какой экран настроек открыть (см. MainActivity)

        Finding(int level, String title, String details, String advice, String action) {
            this.level = level;
            this.title = title;
            this.details = details;
            this.advice = advice;
            this.action = action;
        }
    }

    public static class AppRisk {
        public final String pkg;
        public final String label;
        public final int score;
        public final List<String> reasons;

        AppRisk(String pkg, String label, int score, List<String> reasons) {
            this.pkg = pkg;
            this.label = label;
            this.score = score;
            this.reasons = reasons;
        }

        public int level() {
            if (score >= 9) return HIGH;
            if (score >= 6) return MED;
            if (score >= 3) return LOW;
            return INFO;
        }
    }

    /** Магазины, из которых установка считается «нормальной». */
    static final Set<String> STORES = new HashSet<>(Arrays.asList(
            "com.android.vending", "com.google.android.feedback",
            "ru.vk.store", "com.huawei.appmarket", "com.xiaomi.market", "com.xiaomi.mipicks",
            "com.xiaomi.discover", "com.sec.android.app.samsungapps", "com.heytap.market", "com.oppo.market",
            "com.bbk.appstore", "com.vivo.appstore", "com.amazon.venezia", "com.transsion.phonemaster",
            "com.hihonor.appmarket", "org.fdroid.fdroid", "com.aurora.store", "com.looker.droidify",
            "com.nothing.market", "com.oneplus.market"
    ));

    /** Слова в названии, под которые обычно маскируются трояны. */
    static final String[] BAIT = {
            "google", "android", "system", "систем", "update", "обновлен", "play", "chrome", "security",
            "защит", "безопасн", "антивирус", "antivirus", "сбер", "sber", "банк", "bank", "тинькофф",
            "т-банк", "tinkoff", "втб", "vtb", "альфа", "госуслуг", "gosuslugi", "kaspi", "halyk", "беларус",
            "приорбанк", "почта", "wildberries", "ozon", "мтс", "мегафон", "билайн", "tele2", "service",
            "сервис", "settings", "настройк", "flash", "player", "плеер"
    };

    public final List<Finding> findings = new ArrayList<>();
    public final List<AppRisk> apps = new ArrayList<>();
    public int userApps, systemApps, sideloaded;

    public Analyzer(JSONObject r) {
        try {
            device(r);
        } catch (Exception e) {
            add(INFO, "Ошибка разбора (устройство)", e.toString(), "", null);
        }
        try {
            apps(r);
        } catch (Exception e) {
            add(INFO, "Ошибка разбора (приложения)", e.toString(), "", null);
        }
        Collections.sort(findings, new Comparator<Finding>() {
            public int compare(Finding a, Finding b) {
                return b.level - a.level;
            }
        });
        Collections.sort(apps, new Comparator<AppRisk>() {
            public int compare(AppRisk a, AppRisk b) {
                return b.score - a.score;
            }
        });
    }

    private void add(int level, String title, String details, String advice, String action) {
        findings.add(new Finding(level, title, details, advice, action));
    }

    // ---------------------------------------------------------------- устройство

    private void device(JSONObject r) throws JSONException {
        JSONObject dev = r.optJSONObject("device");
        if (dev != null) {
            int sdk = dev.optInt("sdk");
            long age = dev.optLong("security_patch_age_days", -1);
            String patch = dev.optString("security_patch");
            if (age > 365) {
                add(HIGH, "Обновления безопасности устарели",
                        "Последний патч: " + patch + " (" + age / 30 + " мес. назад). Известные дыры Android не закрыты.",
                        "Проверьте обновления системы. Если производитель больше не обновляет телефон — "
                                + "не ставьте APK из интернета и не храните там банк без крайней нужды.", "update");
            } else if (age > 180) {
                add(MED, "Патч безопасности старше полугода",
                        "Последний патч: " + patch + " (" + age / 30 + " мес. назад).",
                        "Проверьте «Обновление системы».", "update");
            } else if (age > 90) {
                add(LOW, "Патч безопасности старше 3 месяцев", "Последний патч: " + patch + ".",
                        "Проверьте «Обновление системы».", "update");
            } else if (age >= 0) {
                add(INFO, "Патч безопасности свежий", "Последний патч: " + patch + ".", "", null);
            }
            if (sdk > 0 && sdk < 29) {
                add(HIGH, "Старая версия Android (" + dev.optString("android") + ")",
                        "Для неё давно не выпускаются исправления.", "По возможности смените телефон для банковских приложений.", null);
            }
            String tags = dev.optString("tags");
            String type = dev.optString("type");
            if (tags.contains("test-keys") || "userdebug".equals(type) || "eng".equals(type)) {
                add(MED, "Неофициальная или отладочная прошивка", "tags=" + tags + ", type=" + type,
                        "Банковские приложения могут не работать, защита слабее. Если прошивку ставили не вы — это тревожный знак.", null);
            }
        }

        JSONObject in = r.optJSONObject("integrity");
        if (in != null) {
            List<String> root = new ArrayList<>();
            JSONArray su = in.optJSONArray("su_files");
            if (su != null) for (int i = 0; i < su.length(); i++) root.add(su.optString(i));
            JSONArray rp = in.optJSONArray("root_packages");
            if (rp != null) for (int i = 0; i < rp.length(); i++) root.add(rp.optString(i));
            String which = in.optString("which_su", "");
            if (which.startsWith("/")) root.add("which su → " + which);
            if (!root.isEmpty()) {
                add(HIGH, "Есть признаки root-доступа", join(root),
                        "С root любое вредоносное приложение может получить полный контроль. Если root ставили не вы — сбросьте телефон.", null);
            }
            JSONObject p = in.optJSONObject("props");
            if (p != null) {
                String vb = p.optString("ro.boot.verifiedbootstate");
                String fl = p.optString("ro.boot.flash.locked");
                String vs = p.optString("ro.boot.vbmeta.device_state");
                if ("orange".equals(vb) || "0".equals(fl) || "unlocked".equals(vs)) {
                    add(HIGH, "Загрузчик разблокирован",
                            "verifiedbootstate=" + vb + ", flash.locked=" + fl + ", vbmeta=" + vs,
                            "Прошивку можно подменить без вашего ведома. Если разблокировали не вы — обратитесь в сервис.", null);
                } else if ("yellow".equals(vb)) {
                    add(MED, "Проверка загрузки: жёлтый статус", "verifiedbootstate=yellow", "Прошивка подписана не производителем.", null);
                }
                if ("1".equals(p.optString("ro.debuggable"))) {
                    add(MED, "Система в отладочном режиме", "ro.debuggable=1", "", null);
                }
            }
            String enf = in.optString("getenforce", "");
            if (enf.equalsIgnoreCase("Permissive")) {
                add(HIGH, "SELinux выключен (Permissive)", "Защита разделения приложений ослаблена.", "", null);
            }
            JSONArray rw = in.optJSONArray("rw_system");
            if (rw != null && rw.length() > 0) {
                add(MED, "Системный раздел открыт на запись", rw.toString(), "", null);
            }
        }

        JSONObject lock = r.optJSONObject("lock");
        if (lock != null) {
            if (lock.has("device_secure") && !lock.optBoolean("device_secure")) {
                add(HIGH, "Нет блокировки экрана",
                        "Кто угодно с телефоном в руках откроет все приложения.",
                        "Поставьте PIN-код или пароль (не графический ключ).", "lock");
            }
            String enc = lock.optString("encryption");
            if ("inactive".equals(enc) || "unsupported".equals(enc)) {
                add(HIGH, "Память не зашифрована", "Статус: " + enc, "Включите шифрование в настройках безопасности.", "security");
            }
        }

        JSONObject st = r.optJSONObject("settings");
        JSONObject g = st != null ? st.optJSONObject("global") : null;
        JSONObject s = st != null ? st.optJSONObject("secure") : null;
        if (g != null) {
            if ("1".equals(g.optString("adb_wifi_enabled"))) {
                add(HIGH, "Включена отладка по Wi-Fi", "К телефону можно подключиться по сети.",
                        "Выключите в «Для разработчиков».", "dev");
            }
            if ("1".equals(g.optString("adb_enabled"))) {
                add(MED, "Включена отладка по USB", "Через кабель (зарядку в публичном месте) можно управлять телефоном.",
                        "Выключите в «Для разработчиков», если не пользуетесь.", "dev");
            } else if ("1".equals(g.optString("development_settings_enabled"))) {
                add(LOW, "Включено меню «Для разработчиков»", "", "Если не нужно — выключите.", "dev");
            }
            if ("0".equals(g.optString("package_verifier_enable"))) {
                add(HIGH, "Play Защита выключена", "package_verifier_enable=0",
                        "Включите: Play Маркет → профиль → Play Защита.", "playprotect");
            }
            String proxy = g.optString("http_proxy", "");
            String gproxy = g.optString("global_http_proxy_host", "");
            if ((!proxy.isEmpty() && !"null".equals(proxy) && !":0".equals(proxy)) || (!gproxy.isEmpty() && !"null".equals(gproxy))) {
                add(HIGH, "Задан системный прокси", "http_proxy=" + proxy + " " + gproxy,
                        "Весь трафик идёт через чужой сервер. Если ставили не вы — удалите в настройках Wi-Fi/сети.", "wifi");
            }
            String dns = g.optString("private_dns_mode", "");
            if ("off".equals(dns)) {
                add(LOW, "Частный DNS выключен", "Запросы адресов сайтов идут открыто.",
                        "Можно включить «Частный DNS» (например dns.google или dns.adguard-dns.com).", "network");
            }
        }
        if (s != null) {
            if ("1".equals(s.optString("install_non_market_apps"))) {
                add(MED, "Разрешена установка из неизвестных источников (глобально)", "", "Выключите.", "security");
            }
            String lsn = s.optString("lock_screen_allow_private_notifications");
            if ("1".equals(lsn) || (lsn.isEmpty() || "null".equals(lsn))) {
                // по умолчанию на многих телефонах содержимое уведомлений видно на заблокированном экране
                add(LOW, "Текст уведомлений виден на заблокированном экране",
                        "Коды из СМС банка можно прочитать, не разблокируя телефон.",
                        "Настройки → Уведомления → На заблокированном экране → скрывать содержимое.", "notif");
            }
            if (!"null".equals(s.optString("mock_location")) && "1".equals(s.optString("mock_location"))) {
                add(LOW, "Включены фиктивные местоположения", "", "", "dev");
            }
        }

        JSONObject pr = r.optJSONObject("privileged");
        if (pr != null) {
            JSONArray acc = pr.optJSONArray("accessibility_enabled");
            if (acc != null && acc.length() > 0) {
                List<String> l = new ArrayList<>();
                for (int i = 0; i < acc.length(); i++) {
                    JSONObject a = acc.optJSONObject(i);
                    if (a != null) l.add(labelOf(r, a.optString("package")) + " (" + a.optString("package") + ")");
                }
                add(MED, "Включены спец. возможности: " + acc.length(),
                        join(l) + "\nТакое приложение видит всё на экране и может нажимать за вас. Это главный инструмент банковских троянов.",
                        "Оставьте только то, что ставили сами и знаете зачем.", "accessibility");
            }
            JSONArray adm = pr.optJSONArray("device_admins");
            if (adm != null) {
                List<String> l = new ArrayList<>();
                for (int i = 0; i < adm.length(); i++) {
                    JSONObject a = adm.optJSONObject(i);
                    if (a == null) continue;
                    String pk = a.optString("package");
                    if (!isSystem(r, pk)) l.add(labelOf(r, pk) + " (" + pk + ")");
                }
                if (!l.isEmpty()) {
                    add(MED, "Сторонние администраторы устройства: " + l.size(), join(l),
                            "Администратор мешает удалить приложение и может стереть телефон. Отключите незнакомые.", "admins");
                }
            }
            JSONArray nl = pr.optJSONArray("notification_listeners");
            if (nl != null) {
                List<String> l = new ArrayList<>();
                for (int i = 0; i < nl.length(); i++) {
                    String pk = nl.optString(i).split("/")[0];
                    if (!isSystem(r, pk)) l.add(labelOf(r, pk) + " (" + pk + ")");
                }
                if (!l.isEmpty()) {
                    add(LOW, "Сторонние приложения читают уведомления: " + l.size(), join(l),
                            "Они видят коды подтверждения из СМС и мессенджеров. Оставьте только нужные (часы, браслет).", "notiflisteners");
                }
            }
            JSONObject def = pr.optJSONObject("defaults");
            if (def != null) {
                String sms = def.optString("sms");
                if (!sms.isEmpty() && !"null".equals(sms) && !isSystem(r, sms) && !"com.google.android.apps.messaging".equals(sms)) {
                    add(HIGH, "СМС-приложение по умолчанию — стороннее", labelOf(r, sms) + " (" + sms + ")",
                            "Оно получает все СМС, включая коды банка. Если не выбирали его сами — верните стандартное.", "defaults");
                }
            }
            String ime = pr.optString("input_method_default", "");
            String imePkg = ime.split("/")[0];
            if (!imePkg.isEmpty() && !"null".equals(imePkg) && !isSystem(r, imePkg) && !isFromStore(r, imePkg)) {
                add(HIGH, "Клавиатура по умолчанию установлена не из магазина", labelOf(r, imePkg) + " (" + imePkg + ")",
                        "Клавиатура видит все пароли, которые вы вводите.", "keyboard");
            }
        }

        JSONObject certs = r.optJSONObject("certificates");
        if (certs != null) {
            JSONArray u = certs.optJSONArray("user_installed");
            if (u != null && u.length() > 0) {
                List<String> l = new ArrayList<>();
                for (int i = 0; i < u.length(); i++) {
                    JSONObject c = u.optJSONObject(i);
                    if (c != null) l.add(c.optString("subject"));
                }
                add(HIGH, "Установлены пользовательские сертификаты: " + u.length(), join(l),
                        "С ними можно подсматривать защищённый трафик. Если ставили не вы (не работа/VPN) — удалите: "
                                + "Настройки → Безопасность → Шифрование и учётные данные → Учётные данные пользователя.", "security");
            }
        }

        JSONObject net = r.optJSONObject("network");
        if (net != null) {
            if (net.optBoolean("vpn_active")) {
                add(INFO, "Активен VPN", "Весь трафик идёт через VPN-приложение.", "Убедитесь, что VPN вам знаком.", null);
            }
            Object lt = net.opt("listening_tcp");
            if (lt instanceof JSONArray) {
                JSONArray a = (JSONArray) lt;
                List<String> l = new ArrayList<>();
                for (int i = 0; i < a.length(); i++) {
                    JSONObject x = a.optJSONObject(i);
                    if (x != null && !x.optBoolean("loopback_only")) l.add(x.optInt("port") + " — " + x.optString("owner"));
                }
                if (!l.isEmpty()) {
                    add(MED, "Открытые сетевые порты: " + l.size(), join(l), "Приложения принимают подключения из сети.", null);
                }
            }
        }
    }

    // ---------------------------------------------------------------- приложения

    private void apps(JSONObject r) {
        JSONArray a = r.optJSONArray("apps");
        if (a == null) return;
        Set<String> accEnabled = new HashSet<>();
        Set<String> adminActive = new HashSet<>();
        Set<String> nlEnabled = new HashSet<>();
        JSONObject pr = r.optJSONObject("privileged");
        if (pr != null) {
            JSONArray acc = pr.optJSONArray("accessibility_enabled");
            if (acc != null) for (int i = 0; i < acc.length(); i++) {
                JSONObject o = acc.optJSONObject(i);
                if (o != null) accEnabled.add(o.optString("package"));
            }
            JSONArray adm = pr.optJSONArray("device_admins");
            if (adm != null) for (int i = 0; i < adm.length(); i++) {
                JSONObject o = adm.optJSONObject(i);
                if (o != null) adminActive.add(o.optString("package"));
            }
            JSONArray nl = pr.optJSONArray("notification_listeners");
            if (nl != null) for (int i = 0; i < nl.length(); i++) nlEnabled.add(nl.optString(i).split("/")[0]);
        }

        long now = System.currentTimeMillis();
        for (int i = 0; i < a.length(); i++) {
            JSONObject x = a.optJSONObject(i);
            if (x == null) continue;
            String pkg = x.optString("package");
            boolean system = x.optBoolean("system");
            boolean updSys = x.optBoolean("updated_system");
            if (system && !updSys) systemApps++;
            else userApps++;

            List<String> why = new ArrayList<>();
            int score = 0;
            String inst = installerOf(x);
            boolean fromStore = inst != null && STORES.contains(inst);
            boolean side = !system && !fromStore;
            if (side) {
                sideloaded++;
                score += 2;
                why.add(inst == null ? "установлено не из магазина (источник неизвестен)" : "установлено не из магазина: " + inst);
            }
            if (!system && !x.optBoolean("has_launcher_icon", true)) {
                score += 2;
                why.add("нет значка на главном экране (скрыто)");
            }
            Set<String> decl = toSet(x.optJSONArray("declares"));
            Set<String> granted = toSet(x.optJSONArray("permissions_granted"));
            JSONObject sp = x.optJSONObject("special_access");

            if (accEnabled.contains(pkg)) {
                score += system ? 1 : 5;
                why.add("ВКЛЮЧЕНЫ спец. возможности (видит экран, нажимает за вас)");
            } else if (decl.contains("accessibility_service") && !system) {
                score += 2;
                why.add("умеет просить спец. возможности");
            }
            if (adminActive.contains(pkg) && !system) {
                score += 4;
                why.add("АКТИВНЫЙ администратор устройства");
            } else if (decl.contains("device_admin") && !system) {
                score += 1;
                why.add("может стать администратором устройства");
            }
            if (nlEnabled.contains(pkg) && !system) {
                score += 2;
                why.add("читает уведомления");
            }
            if (!system && (granted.contains("READ_SMS") || granted.contains("RECEIVE_SMS"))) {
                score += 3;
                why.add("читает СМС");
            }
            if (!system && granted.contains("SEND_SMS")) {
                score += 2;
                why.add("может отправлять СМС");
            }
            if (!system && sp != null && "allowed".equals(sp.optString("overlay"))) {
                score += 2;
                why.add("может рисовать поверх других приложений");
            }
            if (!system && sp != null && "allowed".equals(sp.optString("install_apps"))) {
                score += 1;
                why.add("может устанавливать другие приложения");
            }
            if (!system && granted.contains("CAMERA") && granted.contains("RECORD_AUDIO")) {
                score += 1;
                why.add("камера и микрофон");
            }
            if (!system && granted.contains("READ_CALL_LOG")) {
                score += 1;
                why.add("журнал звонков");
            }
            if (!system && x.optInt("target_sdk") > 0 && x.optInt("target_sdk") < 23) {
                score += 2;
                why.add("сделано под старый Android (targetSdk " + x.optInt("target_sdk") + ") — получает разрешения без спроса");
            }
            if (x.toString().contains("CN=Android Debug")) {
                score += 1;
                why.add("подписано отладочным ключом");
            }
            if (side && decl.contains("starts_on_boot")) {
                score += 1;
                why.add("запускается при включении телефона");
            }
            if (side && recent(x.optString("first_install"), now, 30)) {
                score += 1;
                why.add("установлено меньше месяца назад");
            }
            if (side) {
                String label = x.optString("label").toLowerCase(Locale.ROOT);
                for (String b : BAIT) {
                    if (label.contains(b)) {
                        score += 2;
                        why.add("название похоже на системное/банковское («" + x.optString("label") + "»), но не из магазина");
                        break;
                    }
                }
            }
            if (score > 0) apps.add(new AppRisk(pkg, x.optString("label", pkg), score, why));
        }

        int high = 0;
        for (AppRisk ar : apps) if (ar.level() == HIGH) high++;
        if (high > 0) {
            add(HIGH, "Подозрительные приложения: " + high, "См. список приложений ниже.",
                    "Проверьте каждое: знаете ли вы его, ставили ли сами.", null);
        }
    }

    // ---------------------------------------------------------------- вспомогательное

    static String installerOf(JSONObject app) {
        JSONObject in = app.optJSONObject("installer");
        if (in == null) return null;
        String s = in.optString("installing", "");
        if (s.isEmpty() || "null".equals(s)) {
            s = in.optString("initiating", "");
        }
        return s.isEmpty() || "null".equals(s) ? null : s;
    }

    private static JSONObject findApp(JSONObject r, String pkg) {
        JSONArray a = r.optJSONArray("apps");
        if (a == null) return null;
        for (int i = 0; i < a.length(); i++) {
            JSONObject x = a.optJSONObject(i);
            if (x != null && pkg.equals(x.optString("package"))) return x;
        }
        return null;
    }

    private static boolean isSystem(JSONObject r, String pkg) {
        JSONObject x = findApp(r, pkg);
        return x != null && x.optBoolean("system");
    }

    private static boolean isFromStore(JSONObject r, String pkg) {
        JSONObject x = findApp(r, pkg);
        if (x == null) return false;
        String in = installerOf(x);
        return in != null && STORES.contains(in);
    }

    private static String labelOf(JSONObject r, String pkg) {
        JSONObject x = findApp(r, pkg);
        return x == null ? pkg : x.optString("label", pkg);
    }

    private static Set<String> toSet(JSONArray a) {
        Set<String> s = new HashSet<>();
        if (a != null) for (int i = 0; i < a.length(); i++) s.add(a.optString(i));
        return s;
    }

    private static boolean recent(String iso, long now, int days) {
        try {
            long t = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).parse(iso).getTime();
            return now - t < days * 86400000L;
        } catch (Exception e) {
            return false;
        }
    }

    private static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) {
            if (sb.length() > 0) sb.append('\n');
            sb.append("• ").append(s);
        }
        return sb.toString();
    }

    /** Короткий текстовый отчёт (для «Поделиться» текстом). */
    public String summaryText(JSONObject r) {
        StringBuilder sb = new StringBuilder();
        JSONObject d = r.optJSONObject("device");
        if (d != null) {
            sb.append("Телефон: ").append(d.optString("manufacturer")).append(' ').append(d.optString("model"))
                    .append(", Android ").append(d.optString("android"))
                    .append(", патч ").append(d.optString("security_patch")).append('\n');
        }
        sb.append("Приложений: пользовательских ").append(userApps).append(", системных ").append(systemApps)
                .append(", не из магазина ").append(sideloaded).append("\n\n");
        for (Finding f : findings) {
            sb.append(levelName(f.level)).append(": ").append(f.title).append('\n');
            if (!f.details.isEmpty()) sb.append(f.details).append('\n');
            if (!f.advice.isEmpty()) sb.append("→ ").append(f.advice).append('\n');
            sb.append('\n');
        }
        sb.append("Приложения с признаками риска:\n");
        for (AppRisk a : apps) {
            if (a.level() == INFO) continue;
            sb.append(levelName(a.level())).append(" [").append(a.score).append("] ").append(a.label)
                    .append(" (").append(a.pkg).append(")\n");
            for (String w : a.reasons) sb.append("   - ").append(w).append('\n');
        }
        return sb.toString();
    }

    public static String levelName(int l) {
        switch (l) {
            case HIGH: return "ВЫСОКИЙ";
            case MED: return "СРЕДНИЙ";
            case LOW: return "НИЗКИЙ";
            default: return "ИНФО";
        }
    }

    /** Находки в JSON — кладутся в файл отчёта рядом с сырыми данными. */
    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        JSONArray f = new JSONArray();
        for (Finding x : findings) {
            JSONObject j = new JSONObject();
            j.put("level", levelName(x.level));
            j.put("title", x.title);
            j.put("details", x.details);
            j.put("advice", x.advice);
            f.put(j);
        }
        o.put("findings", f);
        JSONArray a = new JSONArray();
        for (AppRisk x : apps) {
            JSONObject j = new JSONObject();
            j.put("package", x.pkg);
            j.put("label", x.label);
            j.put("score", x.score);
            j.put("reasons", new JSONArray(x.reasons));
            a.put(j);
        }
        o.put("app_risks", a);
        o.put("user_apps", userApps);
        o.put("system_apps", systemApps);
        o.put("sideloaded", sideloaded);
        return o;
    }
}
