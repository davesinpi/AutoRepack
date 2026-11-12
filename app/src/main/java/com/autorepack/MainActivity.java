package com.autorepack;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.InstallSourceInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.AnimationUtils;
import android.view.animation.LayoutAnimationController;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

import com.autorepack.core.ManifestPatcher;

public class MainActivity extends Activity {
    static final String EXTRA_TARGET_PACKAGE = "com.autorepack.TARGET_PACKAGE";

    private static final String[] MEDIA_ACTIONS = {
            "android.media.browse.MediaBrowserService",
            "androidx.media3.session.MediaLibraryService",
    };
    private static final Pattern PACKAGE_NAME =
            Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+");

    private enum Kind { COPY, ALREADY_SUPPORTED, MEDIA, OTHER }

    private enum Filter { ALL, READY, COPIES }

    private static final class AppEntry {
        ApplicationInfo info;
        String label;
        Drawable icon;
        Kind kind;
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<AppEntry> allApps = new ArrayList<>();
    private final List<AppEntry> shownApps = new ArrayList<>();
    private AppAdapter adapter;
    private ListView list;
    private TextView emptyView, statReady, statCopies;
    private TextView chipAll, chipReady, chipCopies;
    private String filter = "";
    private Filter kindFilter = Filter.ALL;
    private boolean loaded;

    private Dialog progressDialog;
    private ProgressBar progressBar;
    private TextView progressText, progressPercent;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        emptyView = findViewById(R.id.empty);
        statReady = findViewById(R.id.stat_ready);
        statCopies = findViewById(R.id.stat_copies);
        chipAll = findViewById(R.id.filter_all);
        chipReady = findViewById(R.id.filter_ready);
        chipCopies = findViewById(R.id.filter_copies);
        chipAll.setOnClickListener(v -> selectFilter(Filter.ALL));
        chipReady.setOnClickListener(v -> selectFilter(Filter.READY));
        chipCopies.setOnClickListener(v -> selectFilter(Filter.COPIES));
        findViewById(R.id.help).setOnClickListener(v -> showHelp());

        EditText search = findViewById(R.id.search);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                filter = s.toString().trim().toLowerCase(Locale.ROOT);
                applyFilter();
            }
        });

        list = findViewById(R.id.list);
        adapter = new AppAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> onAppClicked(shownApps.get(position)));
        list.setLayoutAnimation(new LayoutAnimationController(
                AnimationUtils.loadAnimation(this, android.R.anim.fade_in), 0.08f));

        selectFilter(Filter.ALL);
        handleInstallStatus(getIntent());
    }

    /** Reloads the list whenever the screen comes back, e.g. after a copy was uninstalled. */
    @Override
    protected void onResume() {
        super.onResume();
        if (progressDialog == null) loadApps();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleInstallStatus(intent);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }

    // ------------------------------------------------------------------ app list

    private void loadApps() {
        executor.execute(() -> {
            PackageManager pm = getPackageManager();
            List<AppEntry> result = new ArrayList<>();
            for (ApplicationInfo ai : pm.getInstalledApplications(PackageManager.GET_META_DATA)) {
                if (ai.packageName.equals(getPackageName())) continue;
                boolean system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                        && (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
                boolean media = hasMediaService(pm, ai.packageName);
                if (system && !media) continue;
                if (pm.getLaunchIntentForPackage(ai.packageName) == null && !media) continue;

                AppEntry e = new AppEntry();
                e.info = ai;
                e.label = String.valueOf(pm.getApplicationLabel(ai));
                e.icon = pm.getApplicationIcon(ai);
                if (installedByUs(pm, ai.packageName)) e.kind = Kind.COPY;
                else if (ai.metaData != null && ai.metaData.containsKey(ManifestPatcher.CAR_APP_META)) e.kind = Kind.ALREADY_SUPPORTED;
                else if (media) e.kind = Kind.MEDIA;
                else e.kind = Kind.OTHER;
                result.add(e);
            }
            Collections.sort(result, (a, b) -> {
                if (a.kind != b.kind) return a.kind.ordinal() - b.kind.ordinal();
                return a.label.compareToIgnoreCase(b.label);
            });
            main.post(() -> {
                boolean first = !loaded;
                loaded = true;
                allApps.clear();
                allApps.addAll(result);
                applyFilter();
                if (first) list.scheduleLayoutAnimation();
            });
        });
    }

    private void selectFilter(Filter f) {
        kindFilter = f;
        chipAll.setSelected(f == Filter.ALL);
        chipReady.setSelected(f == Filter.READY);
        chipCopies.setSelected(f == Filter.COPIES);
        applyFilter();
    }

    private void applyFilter() {
        int ready = 0, copies = 0;
        shownApps.clear();
        for (AppEntry e : allApps) {
            boolean isReady = e.kind == Kind.MEDIA || e.kind == Kind.ALREADY_SUPPORTED;
            if (isReady) ready++;
            if (e.kind == Kind.COPY) copies++;
            if (kindFilter == Filter.READY && !isReady) continue;
            if (kindFilter == Filter.COPIES && e.kind != Kind.COPY) continue;
            if (filter.isEmpty() || e.label.toLowerCase(Locale.ROOT).contains(filter)
                    || e.info.packageName.toLowerCase(Locale.ROOT).contains(filter)) {
                shownApps.add(e);
            }
        }
        statReady.setText(ready + " car-ready");
        statCopies.setText(copies + (copies == 1 ? " copy" : " copies"));
        emptyView.setText(loaded ? "Nothing here" : "Loading…");
        emptyView.setVisibility(shownApps.isEmpty() ? View.VISIBLE : View.GONE);
        adapter.notifyDataSetChanged();
    }

    private static boolean hasMediaService(PackageManager pm, String pkg) {
        for (String action : MEDIA_ACTIONS) {
            List<ResolveInfo> r = pm.queryIntentServices(new Intent(action).setPackage(pkg), 0);
            if (!r.isEmpty()) return true;
        }
        return false;
    }

    private boolean installedByUs(PackageManager pm, String pkg) {
        try {
            String installer;
            if (Build.VERSION.SDK_INT >= 30) {
                InstallSourceInfo src = pm.getInstallSourceInfo(pkg);
                installer = src.getInstallingPackageName();
            } else {
                installer = pm.getInstallerPackageName(pkg);
            }
            return getPackageName().equals(installer);
        } catch (Exception e) {
            return false;
        }
    }

    /** Short status shown under an app's name. */
    private static String statusText(Kind kind) {
        switch (kind) {
            case COPY: return "Car copy";
            case ALREADY_SUPPORTED: return "Android Auto ready";
            case MEDIA: return "Works in the car";
            default: return "No car screen";
        }
    }

    private int statusColor(Kind kind) {
        switch (kind) {
            case COPY: return getColor(R.color.brand);
            case ALREADY_SUPPORTED:
            case MEDIA: return getColor(R.color.success);
            default: return getColor(R.color.warning);
        }
    }

    private final class AppAdapter extends BaseAdapter {
        @Override public int getCount() { return shownApps.size(); }
        @Override public Object getItem(int position) { return shownApps.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView != null ? convertView
                    : getLayoutInflater().inflate(R.layout.row_app, parent, false);
            AppEntry e = shownApps.get(position);
            ((ImageView) v.findViewById(R.id.icon)).setImageDrawable(e.icon);
            ((TextView) v.findViewById(R.id.label)).setText(e.label);
            int color = statusColor(e.kind);
            TextView badge = v.findViewById(R.id.badge);
            badge.setText(statusText(e.kind));
            badge.setTextColor(color);
            v.findViewById(R.id.dot).setBackgroundTintList(ColorStateList.valueOf(color));
            return v;
        }
    }

    // ------------------------------------------------------------------ sheets

    /** Shows a view as a sheet that slides up from the bottom of the screen. */
    private Dialog sheet(View content, boolean cancelable) {
        Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setContentView(content);
        d.setCancelable(cancelable);
        d.setCanceledOnTouchOutside(cancelable);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
            w.setWindowAnimations(android.R.style.Animation_InputMethod);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        d.show();
        return d;
    }

    /** A sheet with an icon, a title, a short message and one main button. */
    private void showMessage(int icon, int colorRes, String title, String body, String button,
                             Runnable action, boolean showCancel) {
        View v = getLayoutInflater().inflate(R.layout.sheet_message, null);
        ImageView iconView = v.findViewById(R.id.icon);
        iconView.setImageResource(icon);
        iconView.setImageTintList(ColorStateList.valueOf(getColor(colorRes)));
        ((TextView) v.findViewById(R.id.title)).setText(title);
        ((TextView) v.findViewById(R.id.body)).setText(body);
        TextView ok = v.findViewById(R.id.button);
        ok.setText(button);
        Dialog d = sheet(v, true);
        ok.setOnClickListener(x -> {
            d.dismiss();
            if (action != null) action.run();
        });
        if (showCancel) {
            View cancel = v.findViewById(R.id.cancel);
            cancel.setVisibility(View.VISIBLE);
            cancel.setOnClickListener(x -> d.dismiss());
        }
    }

    private void showHelp() {
        showMessage(R.drawable.ic_car, R.color.brand, "How it works",
                "1. Pick an app. AutoRepack makes a copy that Android Auto can show.\n"
                        + "2. Open the copy once and sign in.\n"
                        + "3. In Android Auto settings, tap Version 10 times, then turn on "
                        + "Unknown sources in Developer settings.\n\n"
                        + "Only music, audio and messaging apps can appear in the car.",
                "Got it", null, false);
    }

    // ------------------------------------------------------------------ repack flow

    private void onAppClicked(AppEntry e) {
        if (e.kind == Kind.COPY) {
            showMessage(R.drawable.ic_car, R.color.brand, e.label, "Remove this car copy?", "Remove",
                    () -> startActivity(new Intent(Intent.ACTION_DELETE,
                            Uri.parse("package:" + e.info.packageName))), true);
            return;
        }
        if (!getPackageManager().canRequestPackageInstalls()) {
            showMessage(R.drawable.ic_error, R.color.warning, "Allow installs",
                    "AutoRepack needs permission to install the copy. Allow it, then pick the app again.",
                    "Open settings",
                    () -> startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + getPackageName()))), true);
            return;
        }
        showOptions(e);
    }

    private void showOptions(AppEntry e) {
        View v = getLayoutInflater().inflate(R.layout.sheet_options, null);
        ((ImageView) v.findViewById(R.id.icon)).setImageDrawable(e.icon);
        ((TextView) v.findViewById(R.id.title)).setText(e.label);
        TextView status = v.findViewById(R.id.status);
        status.setText(statusText(e.kind));
        status.setTextColor(statusColor(e.kind));

        TextView note = v.findViewById(R.id.note);
        if (e.kind == Kind.OTHER) {
            note.setText("This app has no car screen, so the copy probably won't appear in Android Auto.");
            note.setBackgroundResource(R.drawable.badge_amber);
            note.setTextColor(getColor(R.color.warning));
            note.setVisibility(View.VISIBLE);
        } else if (e.kind == Kind.ALREADY_SUPPORTED) {
            note.setText("Missing in the car? Turn on Unknown sources in Android Auto first. "
                    + "You may not need a copy.");
            note.setBackgroundResource(R.drawable.badge_green);
            note.setTextColor(getColor(R.color.success));
            note.setVisibility(View.VISIBLE);
        }
        if (note.getVisibility() == View.VISIBLE) {
            int p = dp(14);
            note.setPadding(p, p, p, p); // setBackgroundResource resets padding
        }

        TextView media = v.findViewById(R.id.use_media);
        TextView messaging = v.findViewById(R.id.use_messaging);
        media.setSelected(e.kind == Kind.MEDIA || e.kind == Kind.ALREADY_SUPPORTED);
        media.setOnClickListener(x -> media.setSelected(!media.isSelected()));
        messaging.setOnClickListener(x -> messaging.setSelected(!messaging.isSelected()));

        View advanced = v.findViewById(R.id.advanced);
        v.findViewById(R.id.advanced_toggle).setOnClickListener(x -> advanced.setVisibility(
                advanced.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        EditText name = v.findViewById(R.id.name);
        EditText pkg = v.findViewById(R.id.pkg);
        name.setText(e.label + " (AA)");
        pkg.setText(e.info.packageName + ".aa");
        TextView error = v.findViewById(R.id.error);

        Dialog d = sheet(v, true);
        v.findViewById(R.id.go).setOnClickListener(x -> {
            String newPkg = pkg.getText().toString().trim();
            String label = name.getText().toString().trim();
            List<String> uses = new ArrayList<>();
            if (media.isSelected()) uses.add("media");
            if (messaging.isSelected()) uses.add("notification");

            String problem = null;
            if (uses.isEmpty()) {
                problem = "Pick how it should show in the car.";
            } else if (!PACKAGE_NAME.matcher(newPkg).matches() || newPkg.length() > 120) {
                problem = "Package name must look like com.example.app.aa";
            } else if (newPkg.equals(e.info.packageName)) {
                problem = "Package name must differ from the original app's.";
            }
            if (problem != null) {
                error.setText(problem);
                error.setVisibility(View.VISIBLE);
                if (!uses.isEmpty()) advanced.setVisibility(View.VISIBLE);
                return;
            }
            d.dismiss();
            startRepack(e, newPkg, label.isEmpty() ? null : label, uses);
        });
    }

    private void startRepack(AppEntry e, String newPkg, String label, List<String> uses) {
        showProgress(e.label);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        executor.execute(() -> {
            try {
                List<File> apks = RepackJob.run(this, e.info, newPkg, label, uses,
                        (text, percent) -> main.post(() -> updateProgress(text, percent)));
                main.post(() -> updateProgress("Installing…", 100));
                Installer.install(this, apks, newPkg);
            } catch (Throwable t) {
                main.post(() -> fail("Couldn't copy " + e.label, describe(t)));
            }
        });
    }

    private static String describe(Throwable t) {
        String msg = t.getMessage();
        if (t instanceof OutOfMemoryError) return "Ran out of memory. The app is too large to copy on this phone.";
        return (msg == null || msg.isEmpty()) ? t.getClass().getSimpleName() : msg;
    }

    // ------------------------------------------------------------------ install status

    private void handleInstallStatus(Intent intent) {
        if (intent == null || !Installer.ACTION_INSTALL_STATUS.equals(intent.getAction())) return;
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        setIntent(new Intent(this, MainActivity.class)); // don't re-handle after a config change

        switch (status) {
            case PackageInstaller.STATUS_PENDING_USER_ACTION: {
                Intent confirm = Build.VERSION.SDK_INT >= 33
                        ? intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class)
                        : intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    updateProgress("Confirm the install…", 100);
                    startActivity(confirm);
                }
                break;
            }
            case PackageInstaller.STATUS_SUCCESS:
                finishJob();
                showMessage(R.drawable.ic_check, R.color.success, "Copy installed",
                        "1. Open the copy once and sign in.\n"
                                + "2. In Android Auto settings, tap Version 10 times, then turn on "
                                + "Unknown sources in Developer settings.\n"
                                + "3. Reconnect to the car.",
                        "Done", null, false);
                loadApps();
                break;
            default:
                fail("Install failed", explainFailure(status, message));
        }
    }

    private static String explainFailure(int status, String message) {
        String m = message == null ? "" : message;
        if (status == PackageInstaller.STATUS_FAILURE_ABORTED) return "The install was cancelled.";
        if (m.contains("UPDATE_INCOMPATIBLE") || m.contains("signatures do not match")) {
            return "A different app already uses this package name. Remove it, or change the package "
                    + "name under Advanced.";
        }
        if (m.contains("DUPLICATE_PERMISSION") || m.contains("CONFLICTING_PROVIDER")) {
            return "The copy still clashes with the original app, so Android won't install both.";
        }
        if (m.contains("DEPRECATED_SDK_VERSION")) {
            return "This app is too old for this phone to install.";
        }
        if (status == PackageInstaller.STATUS_FAILURE_INVALID) {
            return "Android couldn't read the copy. This app is protected in a way AutoRepack can't handle."
                    + (m.isEmpty() ? "" : "\n\n" + m);
        }
        return m.isEmpty() ? "Status " + status : m;
    }

    // ------------------------------------------------------------------ progress

    private void showProgress(String title) {
        View v = getLayoutInflater().inflate(R.layout.sheet_progress, null);
        ((TextView) v.findViewById(R.id.title)).setText("Copying " + title);
        progressText = v.findViewById(R.id.text);
        progressPercent = v.findViewById(R.id.percent);
        progressBar = v.findViewById(R.id.bar);
        progressDialog = sheet(v, false);
    }

    private void updateProgress(String text, int percent) {
        if (progressDialog == null) return;
        progressText.setText(text);
        progressPercent.setText(percent + "%");
        progressBar.setProgress(percent, true);
    }

    private void finishJob() {
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (progressDialog != null) {
            progressDialog.dismiss();
            progressDialog = null;
        }
        RepackJob.deleteRecursive(new File(getCacheDir(), "repack"));
    }

    private void fail(String title, String message) {
        finishJob();
        showMessage(R.drawable.ic_error, R.color.warning, title, message, "OK", null, false);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
