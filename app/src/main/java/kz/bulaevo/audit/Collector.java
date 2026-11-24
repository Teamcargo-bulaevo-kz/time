package kz.bulaevo.audit;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.AppOpsManager;
import android.app.KeyguardManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.InstallSourceInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.Signature;
import android.hardware.biometrics.BiometricManager;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.provider.Telephony;
import android.telecom.TelecomManager;
import android.view.accessibility.AccessibilityManager;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Собирает сведения о защищённости телефона. Только чтение: ничего не меняет и никуда не отправляет.
 * Результат — JSON, который потом разбирает {@link Analyzer} (и человек).
 */
public class Collector {

    public interface Progress {
        void onProgress(String text);
    }

    static final int REPORT_VERSION = 1;

    private final Context ctx;
    private final PackageManager pm;
    private final Progress progress;
    private final JSONArray errors = new JSONArray();

    public Collector(Context ctx, Progress progress) {
        this.ctx = ctx.getApplicationContext();
        this.pm = ctx.getPackageManager();
        this.progress = progress;
    }

    public JSONObject collect() throws JSONException {
        long t0 = System.currentTimeMillis();
        JSONObject r = new JSONObject();
        r.put("report_version", REPORT_VERSION);
        r.put("app_version", appVersion());
        r.put("created", iso(t0));

        step("Сведения об устройстве");
        r.put("device", safe("device", new Part() { public Object get() throws Exception { return device(); } }));
        step("Признаки root и прошивка");
        r.put("integrity", safe("integrity", new Part() { public Object get() throws Exception { return integrity(); } }));
        step("Блокировка экрана и шифрование");
        r.put("lock", safe("lock", new Part() { public Object get() throws Exception { return lock(); } }));
        step("Системные настройки");
        r.put("settings", safe("settings", new Part() { public Object get() throws Exception { return settings(); } }));
        step("Спец. возможности, администраторы, клавиатуры");
        r.put("privileged", safe("privileged", new Part() { public Object get() throws Exception { return privileged(); } }));
        step("Сертификаты");
        r.put("certificates", safe("certificates", new Part() { public Object get() throws Exception { return certificates(); } }));
        step("Сеть");
        r.put("network", safe("network", new Part() { public Object get() throws Exception { return network(); } }));
        step("Ключевые компоненты системы");
        r.put("components", safe("components", new Part() { public Object get() throws Exception { return components(); } }));
        r.put("apps", safe("apps", new Part() { public Object get() throws Exception { return apps(); } }));

        r.put("errors", errors);
        r.put("scan_ms", System.currentTimeMillis() - t0);
        return r;
    }

    // ---------------------------------------------------------------- устройство

    private JSONObject device() throws Exception {
        JSONObject o = new JSONObject();
        o.put("manufacturer", Build.MANUFACTURER);
        o.put("brand", Build.BRAND);
        o.put("model", Build.MODEL);
        o.put("device", Build.DEVICE);
        o.put("product", Build.PRODUCT);
        o.put("hardware", Build.HARDWARE);
        o.put("android", Build.VERSION.RELEASE);
        o.put("sdk", Build.VERSION.SDK_INT);
        o.put("security_patch", Build.VERSION.SECURITY_PATCH);
        o.put("security_patch_age_days", patchAgeDays(Build.VERSION.SECURITY_PATCH));
        o.put("display", Build.DISPLAY);
        o.put("incremental", Build.VERSION.INCREMENTAL);
        o.put("fingerprint", Build.FINGERPRINT);
        o.put("tags", Build.TAGS);
        o.put("type", Build.TYPE);
        o.put("bootloader", Build.BOOTLOADER);
        o.put("first_api_level", prop("ro.product.first_api_level"));
        o.put("miui", prop("ro.miui.ui.version.name"));
        o.put("hyperos", prop("ro.mi.os.version.name"));
        o.put("emui", prop("ro.build.version.emui"));
        o.put("oneui", prop("ro.build.version.oneui"));
        if (Build.VERSION.SDK_INT >= 31) {
            o.put("soc", Build.SOC_MANUFACTURER + " " + Build.SOC_MODEL);
        }
        o.put("now", iso(System.currentTimeMillis()));
        return o;
    }

    static long patchAgeDays(String patch) {
        if (patch == null || patch.isEmpty()) return -1;
        try {
            Date d = new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(patch);
            return (System.currentTimeMillis() - d.getTime()) / 86400000L;
        } catch (Exception e) {
            return -1;
        }
    }

    // ---------------------------------------------------------------- root / прошивка

    private static final String[] SU_PATHS = {
            "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su", "/system/su",
            "/data/local/su", "/data/local/bin/su", "/data/local/xbin/su", "/system/bin/.ext/su",
            "/system/usr/we-need-root/su", "/cache/su", "/data/su", "/dev/su",
            "/system/app/Superuser.apk", "/system/app/SuperSU.apk", "/data/adb/magisk", "/sbin/.magisk",
            "/data/adb/ksu", "/data/adb/ap"
    };

    private static final String[] ROOT_PACKAGES = {
            "com.topjohnwu.magisk", "io.github.vvb2060.magisk", "io.github.huskydg.magisk",
            "eu.chainfire.supersu", "com.koushikdutta.superuser", "com.noshufou.android.su",
            "com.thirdparty.superuser", "com.kingroot.kinguser", "com.kingo.root", "com.zhiqupk.root.global",
            "com.yellowes.su", "me.weishu.kernelsu", "me.bmax.apatch",
            "de.robv.android.xposed.installer", "org.lsposed.manager", "org.meowcat.edxposed.manager",
            "com.saurik.substrate", "com.devadvance.rootcloak", "com.devadvance.rootcloakplus",
            "com.amphoras.hidemyroot", "com.formyhm.hideroot", "com.zachspong.temprootremovejb",
            "catch_.me_.if_.you_.can_"
    };

    private JSONObject integrity() throws Exception {
        JSONObject o = new JSONObject();
        JSONArray su = new JSONArray();
        for (String p : SU_PATHS) {
            try {
                if (new File(p).exists()) su.put(p);
            } catch (Exception ignored) {
            }
        }
        o.put("su_files", su);
        o.put("which_su", exec("which", "su"));
        JSONArray pk = new JSONArray();
        for (String p : ROOT_PACKAGES) {
            if (installed(p)) pk.put(p);
        }
        o.put("root_packages", pk);

        String[] keys = {
                "ro.debuggable", "ro.secure", "ro.adb.secure", "service.adb.root",
                "ro.boot.verifiedbootstate", "ro.boot.flash.locked", "ro.boot.vbmeta.device_state",
                "ro.boot.veritymode", "ro.boot.warranty_bit", "ro.warranty_bit", "ro.secureboot.lockstate",
                "ro.build.selinux", "ro.boot.selinux", "ro.crypto.state", "ro.crypto.type",
                "persist.sys.usb.config", "sys.usb.state", "ro.oem_unlock_supported", "sys.oem_unlock_allowed",
                "ro.kernel.qemu", "ro.build.tags", "ro.build.type"
        };
        JSONObject props = new JSONObject();
        for (String k : keys) props.put(k, prop(k));
        o.put("props", props);

        String enforce = readFirstLine("/sys/fs/selinux/enforce");
        o.put("selinux_enforce_file", enforce);
        o.put("getenforce", exec("getenforce"));
        o.put("kernel", System.getProperty("os.version"));
        o.put("rw_system", mountedRw());
        return o;
    }

    private Object mountedRw() {
        // Раздел /system смонтирован на запись — признак модифицированной прошивки.
        try {
            BufferedReader br = new BufferedReader(new FileReader("/proc/mounts"));
            JSONArray rw = new JSONArray();
            String line;
            while ((line = br.readLine()) != null) {
                String[] f = line.split(" ");
                if (f.length < 4) continue;
                String mp = f[1];
                if ((mp.equals("/system") || mp.equals("/") || mp.equals("/vendor"))
                        && (f[3].startsWith("rw,") || f[3].equals("rw"))) {
                    rw.put(mp);
                }
            }
            br.close();
            return rw;
        } catch (Exception e) {
            return "недоступно: " + e.getClass().getSimpleName();
        }
    }

    // ---------------------------------------------------------------- блокировка

    private JSONObject lock() throws Exception {
        JSONObject o = new JSONObject();
        KeyguardManager km = (KeyguardManager) ctx.getSystemService(Context.KEYGUARD_SERVICE);
        if (km != null) {
            o.put("device_secure", km.isDeviceSecure());
            o.put("keyguard_secure", km.isKeyguardSecure());
        }
        DevicePolicyManager dpm = (DevicePolicyManager) ctx.getSystemService(Context.DEVICE_POLICY_SERVICE);
        if (dpm != null) {
            int st = dpm.getStorageEncryptionStatus();
            String s;
            switch (st) {
                case DevicePolicyManager.ENCRYPTION_STATUS_UNSUPPORTED: s = "unsupported"; break;
                case DevicePolicyManager.ENCRYPTION_STATUS_INACTIVE: s = "inactive"; break;
                case DevicePolicyManager.ENCRYPTION_STATUS_ACTIVATING: s = "activating"; break;
                case DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE: s = "active"; break;
                case DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_DEFAULT_KEY: s = "active_default_key"; break;
                case DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_PER_USER: s = "active_per_user"; break;
                default: s = "code_" + st;
            }
            o.put("encryption", s);
        }
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                BiometricManager bm = (BiometricManager) ctx.getSystemService(Context.BIOMETRIC_SERVICE);
                if (bm != null) {
                    int res;
                    if (Build.VERSION.SDK_INT >= 30) {
                        res = bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG);
                    } else {
                        res = bm.canAuthenticate();
                    }
                    o.put("biometric", biometricName(res));
                }
            } catch (Exception e) {
                o.put("biometric", "ошибка: " + e.getClass().getSimpleName());
            }
        }
        return o;
    }

    private static String biometricName(int r) {
        switch (r) {
            case 0: return "enrolled";             // BIOMETRIC_SUCCESS
            case 11: return "none_enrolled";       // BIOMETRIC_ERROR_NONE_ENROLLED
            case 12: return "no_hardware";         // BIOMETRIC_ERROR_NO_HARDWARE
            case 1: return "hw_unavailable";       // BIOMETRIC_ERROR_HW_UNAVAILABLE
            case 15: return "security_update_required";
            default: return "code_" + r;
        }
    }

    // ---------------------------------------------------------------- настройки

    private JSONObject settings() throws Exception {
        JSONObject o = new JSONObject();
        ContentResolver cr = ctx.getContentResolver();
        JSONObject g = new JSONObject();
        String[] global = {
                "adb_enabled", "adb_wifi_enabled", "development_settings_enabled", "package_verifier_enable",
                "package_verifier_user_consent", "verifier_verify_adb_installs", "upload_apk_enable",
                "install_non_market_apps", "private_dns_mode", "private_dns_specifier", "http_proxy",
                "global_http_proxy_host", "global_http_proxy_port", "airplane_mode_on", "wifi_on",
                "bluetooth_on", "auto_time", "stay_on_while_plugged_in", "mobile_data", "data_roaming",
                "usb_mass_storage_enabled", "wifi_networks_available_notification_on", "wifi_scan_always_enabled",
                "ble_scan_always_enabled", "nfc_on"
        };
        for (String k : global) g.put(k, globalSetting(cr, k));
        o.put("global", g);

        JSONObject s = new JSONObject();
        String[] secure = {
                "accessibility_enabled", "enabled_accessibility_services", "enabled_notification_listeners",
                "enabled_notification_assistant", "enabled_notification_policy_access_packages",
                "default_input_method", "enabled_input_methods", "install_non_market_apps",
                "lock_screen_show_notifications", "lock_screen_allow_private_notifications",
                "lock_screen_lock_after_timeout", "lock_screen_owner_info_enabled", "location_mode",
                "location_providers_allowed", "mock_location", "voice_interaction_service", "assistant",
                "sms_default_application", "dialer_default_application", "screensaver_enabled",
                "trust_agents_initialized", "enabled_vr_listeners", "autofill_service", "credential_service",
                "lockscreen.disabled", "lock_pattern_visible_pattern", "selected_spell_checker",
                "always_on_vpn_app", "always_on_vpn_lockdown"
        };
        for (String k : secure) s.put(k, secureSetting(cr, k));
        o.put("secure", s);

        JSONObject sy = new JSONObject();
        String[] system = {"screen_off_timeout", "show_password", "lock_to_app_enabled"};
        for (String k : system) sy.put(k, systemSetting(cr, k));
        o.put("system", sy);
        return o;
    }

    private Object globalSetting(ContentResolver cr, String k) {
        try {
            String v = Settings.Global.getString(cr, k);
            return v == null ? JSONObject.NULL : v;
        } catch (Throwable e) {
            return "недоступно";
        }
    }

    private Object secureSetting(ContentResolver cr, String k) {
        try {
            String v = Settings.Secure.getString(cr, k);
            return v == null ? JSONObject.NULL : v;
        } catch (Throwable e) {
            return "недоступно";
        }
    }

    private Object systemSetting(ContentResolver cr, String k) {
        try {
            String v = Settings.System.getString(cr, k);
            return v == null ? JSONObject.NULL : v;
        } catch (Throwable e) {
            return "недоступно";
        }
    }

    // ---------------------------------------------------------------- привилегии

    private JSONObject privileged() throws Exception {
        JSONObject o = new JSONObject();

        JSONArray acc = new JSONArray();
        AccessibilityManager am = (AccessibilityManager) ctx.getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (am != null) {
            List<AccessibilityServiceInfo> list =
                    am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
            for (AccessibilityServiceInfo i : list) {
                JSONObject a = new JSONObject();
                ServiceInfo si = i.getResolveInfo() != null ? i.getResolveInfo().serviceInfo : null;
                a.put("id", i.getId());
                if (si != null) {
                    a.put("package", si.packageName);
                    a.put("service", si.name);
                }
                a.put("can_retrieve_window", (i.getCapabilities()
                        & AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT) != 0);
                a.put("can_gestures", Build.VERSION.SDK_INT >= 24 && (i.getCapabilities()
                        & AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES) != 0);
                a.put("can_filter_keys", (i.getCapabilities()
                        & AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_FILTER_KEY_EVENTS) != 0);
                acc.put(a);
            }
        }
        o.put("accessibility_enabled", acc);

        JSONArray admins = new JSONArray();
        DevicePolicyManager dpm = (DevicePolicyManager) ctx.getSystemService(Context.DEVICE_POLICY_SERVICE);
        if (dpm != null) {
            List<ComponentName> a = dpm.getActiveAdmins();
            if (a != null) {
                for (ComponentName cn : a) {
                    JSONObject x = new JSONObject();
                    x.put("package", cn.getPackageName());
                    x.put("receiver", cn.getClassName());
                    x.put("device_owner", dpm.isDeviceOwnerApp(cn.getPackageName()));
                    x.put("profile_owner", dpm.isProfileOwnerApp(cn.getPackageName()));
                    admins.put(x);
                }
            }
        }
        o.put("device_admins", admins);

        o.put("notification_listeners", splitComponents(
                secureSetting(ctx.getContentResolver(), "enabled_notification_listeners")));

        JSONArray imes = new JSONArray();
        InputMethodManager imm = (InputMethodManager) ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            for (InputMethodInfo i : imm.getEnabledInputMethodList()) {
                imes.put(i.getPackageName() + "/" + i.getServiceName());
            }
        }
        o.put("input_methods_enabled", imes);
        o.put("input_method_default", secureSetting(ctx.getContentResolver(), "default_input_method"));

        JSONObject def = new JSONObject();
        try {
            String sms = Telephony.Sms.getDefaultSmsPackage(ctx);
            def.put("sms", sms == null ? JSONObject.NULL : sms);
        } catch (Exception e) {
            def.put("sms", "ошибка");
        }
        try {
            TelecomManager tm = (TelecomManager) ctx.getSystemService(Context.TELECOM_SERVICE);
            String d = tm != null ? tm.getDefaultDialerPackage() : null;
            def.put("dialer", d == null ? JSONObject.NULL : d);
        } catch (Exception e) {
            def.put("dialer", "ошибка");
        }
        def.put("browser", resolveDefault(new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))));
        Intent home = new Intent(Intent.ACTION_MAIN);
        home.addCategory(Intent.CATEGORY_HOME);
        def.put("launcher", resolveDefault(home));
        def.put("assistant", secureSetting(ctx.getContentResolver(), "assistant"));
        def.put("autofill", secureSetting(ctx.getContentResolver(), "autofill_service"));
        o.put("defaults", def);
        return o;
    }

    private Object resolveDefault(Intent i) {
        try {
            ResolveInfo ri = pm.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY);
            if (ri == null || ri.activityInfo == null) return JSONObject.NULL;
            return ri.activityInfo.packageName;
        } catch (Exception e) {
            return "ошибка";
        }
    }

    private static JSONArray splitComponents(Object v) {
        JSONArray a = new JSONArray();
        if (!(v instanceof String)) return a;
        for (String s : ((String) v).split(":")) {
            if (!s.isEmpty()) a.put(s);
        }
        return a;
    }

    // ---------------------------------------------------------------- сертификаты

    private JSONObject certificates() throws Exception {
        JSONObject o = new JSONObject();
        KeyStore ks = KeyStore.getInstance("AndroidCAStore");
        ks.load(null, null);
        int system = 0;
        JSONArray user = new JSONArray();
        Enumeration<String> en = ks.aliases();
        while (en.hasMoreElements()) {
            String alias = en.nextElement();
            if (alias.startsWith("user:")) {
                JSONObject c = new JSONObject();
                c.put("alias", alias);
                try {
                    X509Certificate x = (X509Certificate) ks.getCertificate(alias);
                    c.put("subject", x.getSubjectX500Principal().getName());
                    c.put("issuer", x.getIssuerX500Principal().getName());
                    c.put("not_after", iso(x.getNotAfter().getTime()));
                    c.put("sha256", hex(MessageDigest.getInstance("SHA-256").digest(x.getEncoded())));
                } catch (Exception e) {
                    c.put("error", e.getClass().getSimpleName());
                }
                user.put(c);
            } else {
                system++;
            }
        }
        o.put("system_count", system);
        o.put("user_installed", user);
        return o;
    }

    // ---------------------------------------------------------------- сеть

    private JSONObject network() throws Exception {
        JSONObject o = new JSONObject();
        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        JSONArray nets = new JSONArray();
        boolean vpn = false;
        if (cm != null) {
            Network active = cm.getActiveNetwork();
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                if (nc == null) continue;
                JSONObject x = new JSONObject();
                List<String> tr = new ArrayList<>();
                if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) tr.add("wifi");
                if (nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) tr.add("cellular");
                if (nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    tr.add("vpn");
                    vpn = true;
                }
                if (nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) tr.add("ethernet");
                if (nc.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) tr.add("bluetooth");
                x.put("transport", new JSONArray(tr));
                x.put("active", n.equals(active));
                x.put("validated", nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED));
                LinkProperties lp = cm.getLinkProperties(n);
                if (lp != null) {
                    JSONArray dns = new JSONArray();
                    for (InetAddress a : lp.getDnsServers()) dns.put(a.getHostAddress());
                    x.put("dns", dns);
                    if (Build.VERSION.SDK_INT >= 28) {
                        x.put("private_dns_active", lp.isPrivateDnsActive());
                        String sn = lp.getPrivateDnsServerName();
                        x.put("private_dns_server", sn == null ? JSONObject.NULL : sn);
                    }
                    x.put("http_proxy", lp.getHttpProxy() == null ? JSONObject.NULL : lp.getHttpProxy().toString());
                }
                nets.put(x);
            }
        }
        o.put("networks", nets);
        o.put("vpn_active", vpn);
        String ph = System.getProperty("http.proxyHost");
        o.put("system_proxy", ph == null ? JSONObject.NULL : ph + ":" + System.getProperty("http.proxyPort"));
        o.put("listening_tcp", listening());
        return o;
    }

    /** Открытые на телефоне TCP-порты (кто «слушает» входящие подключения). На Android 10+ обычно закрыто. */
    private Object listening() {
        JSONArray out = new JSONArray();
        String[] files = {"/proc/net/tcp", "/proc/net/tcp6"};
        boolean any = false;
        for (String f : files) {
            try {
                BufferedReader br = new BufferedReader(new FileReader(f));
                any = true;
                String line = br.readLine();
                while ((line = br.readLine()) != null) {
                    String[] p = line.trim().split("\\s+");
                    if (p.length < 8) continue;
                    if (!"0A".equals(p[3])) continue; // LISTEN
                    String local = p[1];
                    int colon = local.lastIndexOf(':');
                    int port = Integer.parseInt(local.substring(colon + 1), 16);
                    String addr = local.substring(0, colon);
                    boolean loopback = addr.equals("0100007F") || addr.endsWith("0100007F")
                            || addr.equals("00000000000000000000000001000000");
                    int uid = Integer.parseInt(p[7]);
                    JSONObject x = new JSONObject();
                    x.put("port", port);
                    x.put("loopback_only", loopback);
                    x.put("uid", uid);
                    String name = pm.getNameForUid(uid);
                    x.put("owner", name == null ? JSONObject.NULL : name);
                    x.put("file", f);
                    out.put(x);
                }
                br.close();
            } catch (Exception ignored) {
            }
        }
        if (!any) return "недоступно (ограничение Android)";
        return out;
    }

    // ---------------------------------------------------------------- компоненты

    private static final String[] KEY_COMPONENTS = {
            "com.google.android.webview", "com.android.webview", "com.android.chrome",
            "com.google.android.gms", "com.android.vending", "com.google.android.modulemetadata",
            "com.google.android.packageinstaller", "com.android.packageinstaller",
            "com.miui.securitycenter", "com.miui.guardprovider", "com.samsung.android.sm.devicesecurity",
            "com.huawei.systemmanager", "ru.vk.store"
    };

    private JSONObject components() throws Exception {
        JSONObject o = new JSONObject();
        for (String p : KEY_COMPONENTS) {
            try {
                PackageInfo pi = pm.getPackageInfo(p, 0);
                JSONObject x = new JSONObject();
                x.put("version", pi.versionName == null ? JSONObject.NULL : pi.versionName);
                x.put("updated", iso(pi.lastUpdateTime));
                x.put("enabled", pi.applicationInfo != null && pi.applicationInfo.enabled);
                o.put(p, x);
            } catch (PackageManager.NameNotFoundException e) {
                // нет такого — не пишем
            }
        }
        return o;
    }

    // ---------------------------------------------------------------- приложения

    static final String[] DANGEROUS = {
            "android.permission.READ_SMS", "android.permission.RECEIVE_SMS", "android.permission.SEND_SMS",
            "android.permission.RECEIVE_MMS", "android.permission.RECEIVE_WAP_PUSH",
            "android.permission.READ_CONTACTS", "android.permission.WRITE_CONTACTS",
            "android.permission.READ_CALL_LOG", "android.permission.WRITE_CALL_LOG",
            "android.permission.PROCESS_OUTGOING_CALLS", "android.permission.CALL_PHONE",
            "android.permission.READ_PHONE_STATE", "android.permission.READ_PHONE_NUMBERS",
            "android.permission.ANSWER_PHONE_CALLS",
            "android.permission.CAMERA", "android.permission.RECORD_AUDIO",
            "android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.ACCESS_BACKGROUND_LOCATION",
            "android.permission.READ_EXTERNAL_STORAGE", "android.permission.WRITE_EXTERNAL_STORAGE",
            "android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO",
            "android.permission.READ_MEDIA_AUDIO",
            "android.permission.GET_ACCOUNTS", "android.permission.BODY_SENSORS",
            "android.permission.POST_NOTIFICATIONS", "android.permission.ACTIVITY_RECOGNITION",
            "android.permission.NEARBY_WIFI_DEVICES", "android.permission.BLUETOOTH_CONNECT",
            "android.permission.BLUETOOTH_SCAN"
    };

    /** Специальные доступы (через AppOps), которые не видны как обычные разрешения. */
    private static final String[][] OPS = {
            {"android:system_alert_window", "overlay"},
            {"android:request_install_packages", "install_apps"},
            {"android:get_usage_stats", "usage_stats"},
            {"android:manage_external_storage", "all_files"},
            {"android:write_settings", "write_settings"},
            {"android:project_media", "screen_capture"},
            {"android:schedule_exact_alarm", "exact_alarm"}
    };

    private static final String[] MODE_NAMES = {"allowed", "ignored", "errored", "default", "foreground"};

    private JSONArray apps() throws Exception {
        step("Поиск особых служб у приложений");
        Set<String> launcher = packagesOf(pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0));
        Set<String> accSvc = packagesOf(pm.queryIntentServices(
                new Intent("android.accessibilityservice.AccessibilityService"), PackageManager.GET_META_DATA));
        Set<String> nlSvc = packagesOf(pm.queryIntentServices(
                new Intent("android.service.notification.NotificationListenerService"), 0));
        Set<String> vpnSvc = packagesOf(pm.queryIntentServices(new Intent("android.net.VpnService"), 0));
        Set<String> imeSvc = packagesOf(pm.queryIntentServices(new Intent("android.view.InputMethod"), 0));
        Set<String> adminRcv = packagesOf(pm.queryBroadcastReceivers(
                new Intent("android.app.action.DEVICE_ADMIN_ENABLED"), 0));
        Set<String> smsRcv = packagesOf(pm.queryBroadcastReceivers(
                new Intent("android.provider.Telephony.SMS_RECEIVED"), 0));
        Set<String> smsDeliver = packagesOf(pm.queryBroadcastReceivers(
                new Intent("android.provider.Telephony.SMS_DELIVER"), 0));
        Set<String> bootRcv = packagesOf(pm.queryBroadcastReceivers(
                new Intent(Intent.ACTION_BOOT_COMPLETED), 0));
        Set<String> pkgAdded = packagesOf(pm.queryBroadcastReceivers(
                new Intent(Intent.ACTION_PACKAGE_ADDED).setData(Uri.parse("package:x")), 0));

        AppOpsManager ops = (AppOpsManager) ctx.getSystemService(Context.APP_OPS_SERVICE);

        List<PackageInfo> all = pm.getInstalledPackages(0);
        JSONArray out = new JSONArray();
        int n = 0;
        for (PackageInfo base : all) {
            n++;
            if (n % 10 == 1) step("Приложения: " + n + " из " + all.size());
            String pkg = base.packageName;
            try {
                out.put(app(pkg, launcher, accSvc, nlSvc, vpnSvc, imeSvc, adminRcv, smsRcv, smsDeliver,
                        bootRcv, pkgAdded, ops));
            } catch (Throwable e) {
                JSONObject x = new JSONObject();
                x.put("package", pkg);
                x.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
                out.put(x);
            }
        }
        return out;
    }

    @SuppressWarnings("deprecation")
    private JSONObject app(String pkg, Set<String> launcher, Set<String> accSvc, Set<String> nlSvc,
                           Set<String> vpnSvc, Set<String> imeSvc, Set<String> adminRcv, Set<String> smsRcv,
                           Set<String> smsDeliver, Set<String> bootRcv, Set<String> pkgAdded,
                           AppOpsManager ops) throws Exception {
        int flags = PackageManager.GET_PERMISSIONS;
        if (Build.VERSION.SDK_INT >= 28) flags |= PackageManager.GET_SIGNING_CERTIFICATES;
        else flags |= PackageManager.GET_SIGNATURES;
        PackageInfo pi = pm.getPackageInfo(pkg, flags);
        ApplicationInfo ai = pi.applicationInfo;

        JSONObject x = new JSONObject();
        x.put("package", pkg);
        boolean system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
        boolean updatedSystem = (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
        x.put("label", String.valueOf(ai.loadLabel(pm)));
        x.put("version", pi.versionName == null ? JSONObject.NULL : pi.versionName);
        x.put("version_code", Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode);
        x.put("system", system);
        x.put("updated_system", updatedSystem);
        x.put("enabled", ai.enabled);
        x.put("has_launcher_icon", launcher.contains(pkg));
        x.put("target_sdk", ai.targetSdkVersion);
        if (Build.VERSION.SDK_INT >= 24) x.put("min_sdk", ai.minSdkVersion);
        x.put("first_install", iso(pi.firstInstallTime));
        x.put("last_update", iso(pi.lastUpdateTime));
        x.put("installer", installer(pkg));
        x.put("uid", ai.uid);
        if (pi.sharedUserId != null) x.put("shared_user_id", pi.sharedUserId);

        JSONArray f = new JSONArray();
        if ((ai.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) f.put("debuggable");
        if ((ai.flags & ApplicationInfo.FLAG_ALLOW_BACKUP) != 0) f.put("allow_backup");
        if ((ai.flags & ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC) != 0) f.put("cleartext_traffic");
        if ((ai.flags & ApplicationInfo.FLAG_PERSISTENT) != 0) f.put("persistent");
        if ((ai.flags & ApplicationInfo.FLAG_STOPPED) != 0) f.put("stopped");
        if ((ai.flags & ApplicationInfo.FLAG_SUSPENDED) != 0) f.put("suspended");
        if ((ai.flags & ApplicationInfo.FLAG_EXTERNAL_STORAGE) != 0) f.put("on_sd_card");
        if ((ai.flags & ApplicationInfo.FLAG_HAS_CODE) == 0) f.put("no_code");
        x.put("flags", f);

        JSONArray special = new JSONArray();
        if (accSvc.contains(pkg)) special.put("accessibility_service");
        if (nlSvc.contains(pkg)) special.put("notification_listener");
        if (vpnSvc.contains(pkg)) special.put("vpn_service");
        if (imeSvc.contains(pkg)) special.put("keyboard");
        if (adminRcv.contains(pkg)) special.put("device_admin");
        if (smsRcv.contains(pkg)) special.put("sms_receiver");
        if (smsDeliver.contains(pkg)) special.put("sms_app");
        if (bootRcv.contains(pkg)) special.put("starts_on_boot");
        if (pkgAdded.contains(pkg)) special.put("watches_installs");
        x.put("declares", special);

        // разрешения
        JSONArray granted = new JSONArray();
        JSONArray denied = new JSONArray();
        if (pi.requestedPermissions != null) {
            for (int i = 0; i < pi.requestedPermissions.length; i++) {
                String p = pi.requestedPermissions[i];
                boolean g = pi.requestedPermissionsFlags != null
                        && (pi.requestedPermissionsFlags[i] & PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0;
                String shortName = p.startsWith("android.permission.") ? p.substring(19) : p;
                if (g) granted.put(shortName);
                else denied.put(shortName);
            }
        }
        x.put("permissions_granted", granted);
        // Для системных приложений список «не выданных» не нужен — он огромный и неинтересен.
        if (!system || updatedSystem) x.put("permissions_not_granted", denied);

        // особые доступы
        JSONObject sp = new JSONObject();
        if (ops != null) {
            for (String[] op : OPS) {
                String mode = opMode(ops, op[0], ai.uid, pkg);
                if (mode != null) sp.put(op[1], mode);
            }
        }
        x.put("special_access", sp);

        // файл и подпись
        x.put("apk_path", ai.sourceDir);
        File apk = new File(ai.sourceDir);
        x.put("apk_size", apk.length());
        if (ai.splitSourceDirs != null) x.put("splits", ai.splitSourceDirs.length);
        if (!system || updatedSystem) {
            x.put("apk_sha256", fileSha256(apk, 400L * 1024 * 1024));
        }
        x.put("signers", signers(pi));
        return x;
    }

    private String opMode(AppOpsManager ops, String op, int uid, String pkg) {
        try {
            int m;
            if (Build.VERSION.SDK_INT >= 29) m = ops.unsafeCheckOpNoThrow(op, uid, pkg);
            else m = ops.checkOpNoThrow(op, uid, pkg);
            return m >= 0 && m < MODE_NAMES.length ? MODE_NAMES[m] : "mode_" + m;
        } catch (Throwable e) {
            return null; // такой операции нет на этой версии Android
        }
    }

    private Object installer(String pkg) {
        try {
            JSONObject o = new JSONObject();
            if (Build.VERSION.SDK_INT >= 30) {
                InstallSourceInfo s = pm.getInstallSourceInfo(pkg);
                o.put("installing", nn(s.getInstallingPackageName()));
                o.put("initiating", nn(s.getInitiatingPackageName()));
                if (Build.VERSION.SDK_INT >= 33) o.put("package_source", s.getPackageSource());
            } else {
                o.put("installing", nn(pm.getInstallerPackageName(pkg)));
            }
            return o;
        } catch (Throwable e) {
            return "ошибка: " + e.getClass().getSimpleName();
        }
    }

    @SuppressWarnings("deprecation")
    private JSONArray signers(PackageInfo pi) {
        JSONArray a = new JSONArray();
        Signature[] sigs = null;
        try {
            if (Build.VERSION.SDK_INT >= 28 && pi.signingInfo != null) {
                sigs = pi.signingInfo.hasMultipleSigners()
                        ? pi.signingInfo.getApkContentsSigners()
                        : pi.signingInfo.getSigningCertificateHistory();
            } else {
                sigs = pi.signatures;
            }
        } catch (Throwable ignored) {
        }
        if (sigs == null) return a;
        for (Signature s : sigs) {
            try {
                JSONObject o = new JSONObject();
                byte[] der = s.toByteArray();
                o.put("sha256", hex(MessageDigest.getInstance("SHA-256").digest(der)));
                X509Certificate c = (X509Certificate) CertificateFactory.getInstance("X.509")
                        .generateCertificate(new ByteArrayInputStream(der));
                o.put("subject", c.getSubjectX500Principal().getName());
                o.put("not_before", iso(c.getNotBefore().getTime()));
                a.put(o);
            } catch (Throwable ignored) {
            }
        }
        return a;
    }

    // ---------------------------------------------------------------- утилиты

    private interface Part {
        Object get() throws Exception;
    }

    private Object safe(String name, Part p) {
        try {
            Object v = p.get();
            return v == null ? JSONObject.NULL : v;
        } catch (Throwable e) {
            JSONObject err = new JSONObject();
            try {
                err.put("part", name);
                err.put("error", e.getClass().getName() + ": " + e.getMessage());
            } catch (JSONException ignored) {
            }
            errors.put(err);
            return "ошибка: " + e.getClass().getSimpleName();
        }
    }

    private void step(String s) {
        if (progress != null) progress.onProgress(s);
    }

    private String appVersion() {
        try {
            return pm.getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    private boolean installed(String p) {
        try {
            pm.getPackageInfo(p, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private static Set<String> packagesOf(List<ResolveInfo> list) {
        Set<String> s = new HashSet<>();
        if (list == null) return s;
        for (ResolveInfo r : list) {
            if (r.activityInfo != null) s.add(r.activityInfo.packageName);
            else if (r.serviceInfo != null) s.add(r.serviceInfo.packageName);
        }
        return s;
    }

    static Object nn(String s) {
        return s == null ? JSONObject.NULL : s;
    }

    static String iso(long t) {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(new Date(t));
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format(Locale.US, "%02x", x));
        return sb.toString();
    }

    private static Object fileSha256(File f, long limit) {
        if (!f.canRead()) return "нет доступа";
        if (f.length() > limit) return "слишком большой";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            InputStream in = new FileInputStream(f);
            byte[] buf = new byte[1 << 16];
            int r;
            while ((r = in.read(buf)) > 0) md.update(buf, 0, r);
            in.close();
            return hex(md.digest());
        } catch (Exception e) {
            return "ошибка: " + e.getClass().getSimpleName();
        }
    }

    private static Method getProp;

    /** Системное свойство (как getprop). Пустая строка, если нет. */
    static String prop(String key) {
        try {
            if (getProp == null) {
                getProp = Class.forName("android.os.SystemProperties").getMethod("get", String.class);
            }
            Object v = getProp.invoke(null, key);
            return v == null ? "" : v.toString();
        } catch (Throwable e) {
            String v = exec("getprop", key);
            return v == null ? "" : v;
        }
    }

    private static String readFirstLine(String path) {
        try {
            BufferedReader br = new BufferedReader(new FileReader(path));
            String s = br.readLine();
            br.close();
            return s;
        } catch (Exception e) {
            return null;
        }
    }

    /** Запуск безобидной команды (which, getprop, getenforce) с ограничением по времени. */
    static String exec(String... cmd) {
        Process p = null;
        try {
            p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            int lines = 0;
            while ((line = br.readLine()) != null && lines++ < 20) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(line);
            }
            br.close();
            return sb.toString().trim();
        } catch (Exception e) {
            return null;
        } finally {
            if (p != null) p.destroy();
        }
    }
}
