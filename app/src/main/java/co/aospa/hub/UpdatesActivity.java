/*
 * Copyright (C) 2017-2023 The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package co.aospa.hub;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.icu.text.DateFormat;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemProperties;
import android.text.format.Formatter;
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.LinearInterpolator;
import android.view.animation.RotateAnimation;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.view.ContextThemeWrapper;
import androidx.appcompat.view.menu.MenuBuilder;
import androidx.appcompat.view.menu.MenuPopupHelper;
import androidx.appcompat.widget.PopupMenu;
import androidx.appcompat.widget.SwitchCompat;
import androidx.appcompat.widget.Toolbar;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.preference.PreferenceManager;

import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.appbar.CollapsingToolbarLayout;
import com.google.android.material.snackbar.Snackbar;

import org.json.JSONException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import co.aospa.hub.controller.UpdaterController;
import co.aospa.hub.controller.UpdaterService;
import co.aospa.hub.download.DownloadClient;
import co.aospa.hub.misc.BuildInfoUtils;
import co.aospa.hub.misc.Constants;
import co.aospa.hub.misc.StringGenerator;
import co.aospa.hub.misc.Utils;
import co.aospa.hub.model.UpdateInfo;
import co.aospa.hub.model.UpdateStatus;

public class UpdatesActivity extends AppCompatActivity {

    private static final String TAG = "UpdatesActivity";
    private static final int BATTERY_PLUGGED_ANY = BatteryManager.BATTERY_PLUGGED_AC
            | BatteryManager.BATTERY_PLUGGED_USB
            | BatteryManager.BATTERY_PLUGGED_WIRELESS;

    private UpdaterService mUpdaterService;
    private BroadcastReceiver mBroadcastReceiver;

    private View mRefreshIconView;
    private RotateAnimation mRefreshAnimation;

    // UI elements for update item
    private Button mAction;
    private ImageButton mMenu;
    private TextView mBuildDate;
    private TextView mBuildVersion;
    private TextView mBuildSize;
    private LinearLayout mProgress;
    private ProgressBar mProgressBar;
    private TextView mProgressText;
    private TextView mPercentage;

    private String mSelectedDownload;
    private List<String> mDownloadIds;
    private UpdaterController mUpdaterController;
    private final ServiceConnection mConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName className,
                                       IBinder service) {
            UpdaterService.LocalBinder binder = (UpdaterService.LocalBinder) service;
            mUpdaterService = binder.getService();
            mUpdaterController = mUpdaterService.getUpdaterController();
            getUpdatesList();
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            mUpdaterService = null;
            mUpdaterController = null;
        }
    };
    private float mAlphaDisabledValue;
    private AlertDialog infoDialog;

    private static boolean isScratchMounted() {
        try (Stream<String> lines = Files.lines(Path.of("/proc/mounts"))) {
            return lines.anyMatch(x -> x.split(" ")[1].equals("/mnt/scratch"));
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_updates);

        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.disabledAlpha, tv, true);
        mAlphaDisabledValue = tv.getFloat();

        mBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (UpdaterController.ACTION_UPDATE_STATUS.equals(intent.getAction())) {
                    String downloadId = intent.getStringExtra(UpdaterController.EXTRA_DOWNLOAD_ID);
                    handleDownloadStatusChange(downloadId);
                    updateUI(downloadId);
                } else if (UpdaterController.ACTION_DOWNLOAD_PROGRESS.equals(intent.getAction()) ||
                        UpdaterController.ACTION_INSTALL_PROGRESS.equals(intent.getAction())) {
                    String downloadId = intent.getStringExtra(UpdaterController.EXTRA_DOWNLOAD_ID);
                    updateUI(downloadId);
                } else if (UpdaterController.ACTION_UPDATE_REMOVED.equals(intent.getAction())) {
                    String downloadId = intent.getStringExtra(UpdaterController.EXTRA_DOWNLOAD_ID);
                    removeUpdate(downloadId);
                    downloadUpdatesList(false);
                }
            }
        };

        // Initialize UI elements
        mAction = findViewById(R.id.update_action);
        mMenu = findViewById(R.id.update_menu);
        mBuildDate = findViewById(R.id.build_date);
        mBuildVersion = findViewById(R.id.build_version);
        mBuildSize = findViewById(R.id.build_size);
        mProgress = findViewById(R.id.progress);
        mProgressBar = findViewById(R.id.progress_bar);
        mProgressText = findViewById(R.id.progress_text);
        mPercentage = findViewById(R.id.progress_percent);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayShowTitleEnabled(false);
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        TextView headerTitle = findViewById(R.id.header_title);
        headerTitle.setText(getString(R.string.header_title_text,
                Utils.getDisplayVersion(BuildInfoUtils.getBuildVersion())));

        updateLastCheckedString();

        TextView headerBuildVersion = findViewById(R.id.header_build_version);
        headerBuildVersion.setText(
                getString(R.string.header_android_version, Build.VERSION.RELEASE));

        TextView headerBuildDate = findViewById(R.id.header_build_date);
        headerBuildDate.setText(StringGenerator.getDateLocalizedUTC(this,
                DateFormat.LONG, BuildInfoUtils.getBuildDateTimestamp()));

        // Switch between header title and appbar title minimizing overlaps
        final CollapsingToolbarLayout collapsingToolbar = findViewById(R.id.collapsing_toolbar);
        final AppBarLayout appBar = findViewById(R.id.app_bar);
        appBar.addOnOffsetChangedListener(new AppBarLayout.OnOffsetChangedListener() {
            boolean mIsShown = false;

            @Override
            public void onOffsetChanged(AppBarLayout appBarLayout, int verticalOffset) {
                int scrollRange = appBarLayout.getTotalScrollRange();
                if (!mIsShown && scrollRange + verticalOffset < 10) {
                    collapsingToolbar.setTitle(getString(R.string.display_name));
                    mIsShown = true;
                } else if (mIsShown && scrollRange + verticalOffset > 100) {
                    collapsingToolbar.setTitle(null);
                    mIsShown = false;
                }
            }
        });

        mRefreshAnimation = new RotateAnimation(0, 360, Animation.RELATIVE_TO_SELF, 0.5f,
                Animation.RELATIVE_TO_SELF, 0.5f);
        mRefreshAnimation.setInterpolator(new LinearInterpolator());
        mRefreshAnimation.setDuration(1000);

        if (!Utils.hasTouchscreen(this)) {
            // This can't be collapsed without a touchscreen
            appBar.setExpanded(false);
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        Intent intent = new Intent(this, UpdaterService.class);
        startService(intent);
        bindService(intent, mConnection, Context.BIND_AUTO_CREATE);

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(UpdaterController.ACTION_UPDATE_STATUS);
        intentFilter.addAction(UpdaterController.ACTION_DOWNLOAD_PROGRESS);
        intentFilter.addAction(UpdaterController.ACTION_INSTALL_PROGRESS);
        intentFilter.addAction(UpdaterController.ACTION_UPDATE_REMOVED);
        LocalBroadcastManager.getInstance(this).registerReceiver(mBroadcastReceiver, intentFilter);
    }

    @Override
    public void onStop() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mBroadcastReceiver);
        if (mUpdaterService != null) {
            unbindService(mConnection);
        }
        super.onStop();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_toolbar, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int itemId = item.getItemId();
        if (itemId == R.id.menu_refresh) {
            downloadUpdatesList(true);
            return true;
        } else if (itemId == R.id.menu_preferences) {
            showPreferencesDialog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }

    private void loadUpdatesList(File jsonFile, boolean manualRefresh)
            throws IOException, JSONException {
        Log.d(TAG, "Adding remote updates");
        UpdaterController controller = mUpdaterService.getUpdaterController();
        boolean newUpdates = false;

        List<UpdateInfo> updates = Utils.parseJson(jsonFile, true);
        List<String> updatesOnline = new ArrayList<>();
        for (UpdateInfo update : updates) {
            newUpdates |= controller.addUpdate(update);
            updatesOnline.add(update.getDownloadId());
        }
        controller.setUpdatesAvailableOnline(updatesOnline, true);

        if (manualRefresh) {
            showSnackbar(
                    newUpdates ? R.string.snack_updates_found : R.string.snack_no_updates_found,
                    Snackbar.LENGTH_SHORT);
        }

        List<String> updateIds = new ArrayList<>();
        List<UpdateInfo> sortedUpdates = controller.getUpdates();
        if (sortedUpdates.isEmpty()) {
            findViewById(R.id.no_new_updates_view).setVisibility(View.VISIBLE);
            findViewById(R.id.updates_available_view).setVisibility(View.GONE);
        } else {
            findViewById(R.id.no_new_updates_view).setVisibility(View.GONE);
            findViewById(R.id.updates_available_view).setVisibility(View.VISIBLE);
            sortedUpdates.sort((u1, u2) -> Long.compare(u2.getTimestamp(), u1.getTimestamp()));
            for (UpdateInfo update : sortedUpdates) {
                updateIds.add(update.getDownloadId());
            }
            mDownloadIds = updateIds;
            updateUI(mDownloadIds.get(0)); // Update UI with the first (latest) update
        }
    }

    private void getUpdatesList() {
        File jsonFile = Utils.getCachedUpdateList(this);
        if (jsonFile.exists()) {
            try {
                loadUpdatesList(jsonFile, false);
                Log.d(TAG, "Cached list parsed");
            } catch (IOException | JSONException e) {
                Log.e(TAG, "Error while parsing json list", e);
            }
        } else {
            downloadUpdatesList(false);
        }
    }

    private void processNewJson(File json, File jsonNew, boolean manualRefresh) {
        try {
            loadUpdatesList(jsonNew, manualRefresh);
            SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
            long millis = System.currentTimeMillis();
            preferences.edit().putLong(Constants.PREF_LAST_UPDATE_CHECK, millis).apply();
            updateLastCheckedString();
            if (json.exists() && Utils.isUpdateCheckEnabled(this) &&
                    Utils.checkForNewUpdates(json, jsonNew)) {
                UpdatesCheckReceiver.updateRepeatingUpdatesCheck(this);
            }
            // In case we set a one-shot check because of a previous failure
            UpdatesCheckReceiver.cancelUpdatesCheck(this);
            //noinspection ResultOfMethodCallIgnored
            jsonNew.renameTo(json);
        } catch (IOException | JSONException e) {
            Log.e(TAG, "Could not read json", e);
            showSnackbar(R.string.snack_updates_check_failed, Snackbar.LENGTH_LONG);
        }
    }

    private void downloadUpdatesList(final boolean manualRefresh) {
        final File jsonFile = Utils.getCachedUpdateList(this);
        final File jsonFileTmp = new File(jsonFile.getAbsolutePath() + UUID.randomUUID());
        String url = Utils.getServerURL(this);
        Log.d(TAG, "Checking " + url);

        DownloadClient.DownloadCallback callback = new DownloadClient.DownloadCallback() {
            @Override
            public void onFailure(final boolean cancelled) {
                Log.e(TAG, "Could not download updates list");
                runOnUiThread(() -> {
                    if (!cancelled) {
                        showSnackbar(R.string.snack_updates_check_failed, Snackbar.LENGTH_LONG);
                    }
                    refreshAnimationStop();
                });
            }

            @Override
            public void onResponse(DownloadClient.Headers headers) {
            }

            @Override
            public void onSuccess() {
                runOnUiThread(() -> {
                    Log.d(TAG, "List downloaded");
                    processNewJson(jsonFile, jsonFileTmp, manualRefresh);
                    refreshAnimationStop();
                });
            }
        };

        final DownloadClient downloadClient;
        try {
            downloadClient = new DownloadClient.Builder()
                    .setUrl(url)
                    .setDestination(jsonFileTmp)
                    .setDownloadCallback(callback)
                    .build();
        } catch (IOException exception) {
            Log.e(TAG, "Could not build download client");
            showSnackbar(R.string.snack_updates_check_failed, Snackbar.LENGTH_LONG);
            return;
        }

        refreshAnimationStart();
        downloadClient.start();
    }

    private void updateLastCheckedString() {
        final SharedPreferences preferences =
                PreferenceManager.getDefaultSharedPreferences(this);
        long lastCheck = preferences.getLong(Constants.PREF_LAST_UPDATE_CHECK, -1) / 1000;
        String lastCheckString = getString(R.string.header_last_updates_check,
                StringGenerator.getDateLocalized(this, DateFormat.LONG, lastCheck),
                StringGenerator.getTimeLocalized(this, lastCheck));
        TextView headerLastCheck = findViewById(R.id.header_last_check);
        headerLastCheck.setText(lastCheckString);
    }

    private void handleDownloadStatusChange(String downloadId) {
        UpdateInfo update = mUpdaterController.getUpdate(downloadId);
        switch (update.getStatus()) {
            case PAUSED_ERROR:
                showSnackbar(R.string.snack_download_failed, Snackbar.LENGTH_LONG);
                break;
            case VERIFICATION_FAILED:
                showSnackbar(R.string.snack_download_verification_failed, Snackbar.LENGTH_LONG);
                break;
            case VERIFIED:
                showSnackbar(R.string.snack_download_verified, Snackbar.LENGTH_LONG);
                break;
        }
    }

    private void updateUI(String downloadId) {
        if (mDownloadIds == null || mDownloadIds.isEmpty()) {
            return;
        }

        UpdateInfo update = mUpdaterController.getUpdate(downloadId);
        if (update == null) {
            return;
        }

        mBuildDate.setText(StringGenerator.getDateLocalizedUTC(this,
                DateFormat.LONG, update.getTimestamp()));
        mBuildVersion.setText(getString(R.string.list_build_version,
                Utils.getDisplayVersion(update.getVersion())));

        boolean activeLayout = update.getPersistentStatus() == UpdateStatus.Persistent.INCOMPLETE ||
                update.getStatus() == UpdateStatus.STARTING ||
                update.getStatus() == UpdateStatus.INSTALLING ||
                mUpdaterController.isVerifyingUpdate();

        if (activeLayout) {
            handleActiveStatus(update);
        } else {
            handleNotActiveStatus(update);
        }
    }

    private void handleActiveStatus(UpdateInfo update) {
        boolean canDelete = false;

        final String downloadId = update.getDownloadId();
        if (mUpdaterController.isDownloading(downloadId)) {
            canDelete = true;
            String downloaded = Formatter.formatShortFileSize(this, update.getFile().length());
            String total = Formatter.formatShortFileSize(this, update.getFileSize());
            String percentage = NumberFormat.getPercentInstance().format(update.getProgress() / 100.f);
            mPercentage.setText(percentage);
            mProgressText.setText(getString(R.string.list_download_progress_newer, downloaded, total));
            setButtonAction(Action.PAUSE, downloadId, true);
            mProgressBar.setIndeterminate(update.getStatus() == UpdateStatus.STARTING);
            mProgressBar.setProgress(update.getProgress());
        } else if (mUpdaterController.isInstallingUpdate(downloadId)) {
            setButtonAction(Action.CANCEL_INSTALLATION, downloadId, true);
            boolean notAB = !mUpdaterController.isInstallingABUpdate();
            mProgressText.setText(notAB ? R.string.dialog_prepare_zip_message :
                    update.getFinalizing() ?
                            R.string.finalizing_package :
                            R.string.preparing_ota_first_boot);
            mPercentage.setText(NumberFormat.getPercentInstance().format(update.getInstallProgress() / 100.f));
            mProgressBar.setIndeterminate(false);
            mProgressBar.setProgress(update.getInstallProgress());
        } else if (mUpdaterController.isVerifyingUpdate(downloadId)) {
            setButtonAction(Action.INSTALL, downloadId, false);
            mProgressText.setText(R.string.list_verifying_update);
            mProgressBar.setIndeterminate(true);
        } else {
            canDelete = true;
            setButtonAction(Action.RESUME, downloadId, !isBusy());
            String downloaded = Formatter.formatShortFileSize(this, update.getFile().length());
            String total = Formatter.formatShortFileSize(this, update.getFileSize());
            String percentage = NumberFormat.getPercentInstance().format(update.getProgress() / 100.f);
            mPercentage.setText(percentage);
            mProgressText.setText(getString(R.string.list_download_progress_newer, downloaded, total));
            mProgressBar.setIndeterminate(false);
            mProgressBar.setProgress(update.getProgress());
        }

        mMenu.setOnClickListener(getClickListener(update, canDelete, mMenu));
        mProgress.setVisibility(View.VISIBLE);
        mProgressText.setVisibility(View.VISIBLE);
        mBuildSize.setVisibility(View.INVISIBLE);
    }

    private void handleNotActiveStatus(UpdateInfo update) {
        final String downloadId = update.getDownloadId();
        if (mUpdaterController.isWaitingForReboot(downloadId)) {
            setButtonAction(Action.REBOOT, downloadId, true);
        } else if (update.getPersistentStatus() == UpdateStatus.Persistent.VERIFIED) {
            setButtonAction(Utils.canInstall(update) ? Action.INSTALL : Action.DELETE, downloadId, !isBusy());
        } else if (!Utils.canInstall(update)) {
            setButtonAction(Action.INFO, downloadId, !isBusy());
        } else {
            setButtonAction(Action.DOWNLOAD, downloadId, !isBusy());
        }
        String fileSize = Formatter.formatShortFileSize(this, update.getFileSize());
        mBuildSize.setText(fileSize);

        mMenu.setOnClickListener(getClickListener(update, true, mMenu));
        mProgress.setVisibility(View.INVISIBLE);
        mProgressText.setVisibility(View.INVISIBLE);
        mBuildSize.setVisibility(View.VISIBLE);
    }

    private void removeUpdate(String downloadId) {
        if (mDownloadIds != null) {
            mDownloadIds.remove(downloadId);
            if (mDownloadIds.isEmpty()) {
                findViewById(R.id.no_new_updates_view).setVisibility(View.VISIBLE);
                findViewById(R.id.updates_available_view).setVisibility(View.GONE);
            } else {
                updateUI(mDownloadIds.get(0));
            }
        }
    }

    private boolean isBusy() {
        return mUpdaterController.hasActiveDownloads()
                || mUpdaterController.isVerifyingUpdate()
                || mUpdaterController.isInstallingUpdate();
    }

    public void showSnackbar(int stringId, int duration) {
        Snackbar.make(findViewById(R.id.main_container), stringId, duration).show();
    }

    private void refreshAnimationStart() {
        if (mRefreshIconView == null) {
            mRefreshIconView = findViewById(R.id.menu_refresh);
        }
        if (mRefreshIconView != null) {
            mRefreshAnimation.setRepeatCount(Animation.INFINITE);
            mRefreshIconView.startAnimation(mRefreshAnimation);
            mRefreshIconView.setEnabled(false);
        }
    }

    private void refreshAnimationStop() {
        if (mRefreshIconView != null) {
            mRefreshAnimation.setRepeatCount(0);
            mRefreshIconView.setEnabled(true);
        }
    }

    private void setButtonAction(Action action, final String downloadId, boolean enabled) {
        final View.OnClickListener clickListener;
        switch (action) {
            case DOWNLOAD:
                mAction.setText(R.string.action_download);
                clickListener = enabled ? view -> startDownloadWithWarning(downloadId) : null;
                break;
            case PAUSE:
                mAction.setText(R.string.action_pause);
                clickListener = enabled ? view -> mUpdaterController.pauseDownload(downloadId) : null;
                break;
            case RESUME:
                mAction.setText(R.string.action_resume);
                UpdateInfo update = mUpdaterController.getUpdate(downloadId);
                final boolean canInstall = Utils.canInstall(update) ||
                        update.getFile().length() == update.getFileSize();
                clickListener = enabled ? view -> {
                    if (canInstall) {
                        mUpdaterController.resumeDownload(downloadId);
                    } else {
                        showSnackbar(R.string.snack_update_not_installable, Snackbar.LENGTH_LONG);
                    }
                } : null;
                break;
            case INSTALL:
                mAction.setText(R.string.action_install);
                clickListener = enabled ? view -> {
                    if (Utils.canInstall(mUpdaterController.getUpdate(downloadId))) {
                        AlertDialog.Builder installDialog = getInstallDialog(downloadId);
                        if (installDialog != null) {
                            installDialog.show();
                        }
                    } else {
                        showSnackbar(R.string.snack_update_not_installable, Snackbar.LENGTH_LONG);
                    }
                } : null;
                break;
            case INFO:
                mAction.setText(R.string.action_info);
                clickListener = enabled ? view -> showInfoDialog() : null;
                break;
            case DELETE:
                mAction.setText(R.string.action_delete);
                clickListener = enabled ? view -> getDeleteDialog(downloadId).show() : null;
                break;
            case CANCEL_INSTALLATION:
                mAction.setText(R.string.action_cancel);
                clickListener = enabled ? view -> getCancelInstallationDialog().show() : null;
                break;
            case REBOOT:
                mAction.setText(R.string.reboot);
                clickListener = enabled ? view -> {
                    PowerManager pm = getSystemService(PowerManager.class);
                    pm.reboot(null);
                } : null;
                break;
            default:
                clickListener = null;
        }
        mAction.setEnabled(enabled);
        mAction.setOnClickListener(clickListener);
        mAction.setAlpha(enabled ? 1.f : mAlphaDisabledValue);
    }

    private void startDownloadWithWarning(final String downloadId) {
        if (!Utils.isNetworkMetered(this)) {
            mUpdaterController.startDownload(downloadId);
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.update_over_metered_network_title)
                .setMessage(R.string.update_over_metered_network_message)
                .setPositiveButton(R.string.action_download,
                        (dialog, which) -> mUpdaterController.startDownload(downloadId))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private View.OnClickListener getClickListener(final UpdateInfo update,
                                                  final boolean canDelete, View anchor) {
        return view -> startActionMode(update, canDelete, anchor);
    }

    private void startActionMode(final UpdateInfo update, final boolean canDelete, View anchor) {
        mSelectedDownload = update.getDownloadId();

        ContextThemeWrapper wrapper = new ContextThemeWrapper(this,
                R.style.AppTheme_PopupMenuOverlapAnchor);
        PopupMenu popupMenu = new PopupMenu(wrapper, anchor, Gravity.NO_GRAVITY);
        popupMenu.inflate(R.menu.menu_action_mode);

        MenuBuilder menu = (MenuBuilder) popupMenu.getMenu();
        menu.findItem(R.id.menu_delete_action).setVisible(canDelete);

        popupMenu.setOnMenuItemClickListener(item -> {
            int itemId = item.getItemId();
            if (itemId == R.id.menu_delete_action) {
                getDeleteDialog(update.getDownloadId()).show();
                return true;
            }
            return false;
        });

        MenuPopupHelper helper = new MenuPopupHelper(wrapper, menu, anchor);
        helper.show();
    }

    private void showInfoDialog() {
        if (infoDialog != null) {
            infoDialog.dismiss();
        }
        infoDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.blocked_update_dialog_title)
                .setPositiveButton(android.R.string.ok, null)
                .setMessage(R.string.blocked_update_dialog_message)
                .show();
        TextView textView = infoDialog.findViewById(android.R.id.message);
        if (textView != null) {
            textView.setMovementMethod(LinkMovementMethod.getInstance());
        }
    }

    private AlertDialog.Builder getDeleteDialog(final String downloadId) {
        return new AlertDialog.Builder(this)
                .setTitle(R.string.confirm_delete_dialog_title)
                .setMessage(R.string.confirm_delete_dialog_message)
                .setPositiveButton(android.R.string.ok,
                        (dialog, which) -> {
                            mUpdaterController.pauseDownload(downloadId);
                            mUpdaterController.deleteUpdate(downloadId);
                        })
                .setNegativeButton(android.R.string.cancel, null);
    }

    private AlertDialog.Builder getInstallDialog(final String downloadId) {
        if (!isBatteryLevelOk()) {
            Resources resources = getResources();
            String message = resources.getString(R.string.dialog_battery_low_message_pct,
                    resources.getInteger(R.integer.battery_ok_percentage_discharging),
                    resources.getInteger(R.integer.battery_ok_percentage_charging));
            return new AlertDialog.Builder(this)
                    .setTitle(R.string.dialog_battery_low_title)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok, null);
        }
        if (isScratchMounted()) {
            return new AlertDialog.Builder(this)
                    .setTitle(R.string.dialog_scratch_mounted_title)
                    .setMessage(R.string.dialog_scratch_mounted_message)
                    .setPositiveButton(android.R.string.ok, null);
        }
        UpdateInfo update = mUpdaterController.getUpdate(downloadId);
        int resId;
        try {
            if (Utils.isABUpdate(update.getFile())) {
                resId = R.string.apply_update_dialog_message_ab;
            } else {
                resId = R.string.apply_update_dialog_message;
            }
        } catch (IOException e) {
            Log.e(TAG, "Could not determine the type of the update");
            return null;
        }

        String buildDate = StringGenerator.getDateLocalizedUTC(this,
                DateFormat.MEDIUM, update.getTimestamp());
        String buildInfoText = getString(R.string.list_build_version_date,
                update.getVersion(), buildDate);
        return new AlertDialog.Builder(this)
                .setTitle(R.string.apply_update_dialog_title)
                .setMessage(getString(resId, buildInfoText,
                        getString(android.R.string.ok)))
                .setPositiveButton(android.R.string.ok,
                        (dialog, which) -> {
                            Utils.triggerUpdate(this, downloadId);
                            maybeShowInfoDialog();
                        })
                .setNegativeButton(android.R.string.cancel, null);
    }

    private AlertDialog.Builder getCancelInstallationDialog() {
        return new AlertDialog.Builder(this)
                .setMessage(R.string.cancel_installation_dialog_message)
                .setPositiveButton(android.R.string.ok,
                        (dialog, which) -> {
                            Intent intent = new Intent(this, UpdaterService.class);
                            intent.setAction(UpdaterService.ACTION_INSTALL_STOP);
                            startService(intent);
                        })
                .setNegativeButton(android.R.string.cancel, null);
    }

    private void maybeShowInfoDialog() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        boolean alreadySeen = preferences.getBoolean(Constants.HAS_SEEN_INFO_DIALOG, false);
        if (alreadySeen) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.info_dialog_title)
                .setMessage(R.string.info_dialog_message)
                .setPositiveButton(R.string.info_dialog_ok, (dialog, which) -> preferences.edit()
                        .putBoolean(Constants.HAS_SEEN_INFO_DIALOG, true)
                        .apply())
                .show();
    }

    private boolean isBatteryLevelOk() {
        Intent intent = registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (!intent.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false)) {
            return true;
        }
        int percent = Math.round(100.f * intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 100) /
                intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100));
        int plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        int required = (plugged & BATTERY_PLUGGED_ANY) != 0 ?
                getResources().getInteger(R.integer.battery_ok_percentage_charging) :
                getResources().getInteger(R.integer.battery_ok_percentage_discharging);
        return percent >= required;
    }

    @SuppressLint("ClickableViewAccessibility")
    private void showPreferencesDialog() {
        View view = LayoutInflater.from(this).inflate(R.layout.preferences_dialog, null);
        Spinner autoCheckInterval = view.findViewById(R.id.preferences_auto_updates_check_interval);
        SwitchCompat abPerfMode = view.findViewById(R.id.preferences_ab_perf_mode);
        SwitchCompat updateRecovery = view.findViewById(R.id.preferences_update_recovery);

        if (!Utils.isABDevice()) {
            abPerfMode.setVisibility(View.GONE);
        }

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        autoCheckInterval.setSelection(Utils.getUpdateCheckSetting(this));
        abPerfMode.setChecked(prefs.getBoolean(Constants.PREF_AB_PERF_MODE, false));

        if (getResources().getBoolean(R.bool.config_hideRecoveryUpdate)) {
            // Hide the update feature if explicitly requested.
            // Might be the case of A-only devices using prebuilt vendor images.
            updateRecovery.setVisibility(View.GONE);
        } else if (Utils.isRecoveryUpdateExecPresent()) {
            updateRecovery.setChecked(
                    SystemProperties.getBoolean(Constants.UPDATE_RECOVERY_PROPERTY, false));
        } else {
            // There is no recovery updater script in the device, so the feature is considered
            // forcefully enabled, just to avoid users to be confused and complain that
            // recovery gets overwritten. That's the case of A/B and recovery-in-boot devices.
            updateRecovery.setChecked(true);
            updateRecovery.setOnTouchListener(new View.OnTouchListener() {
                private Toast forcedUpdateToast = null;

                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    if (forcedUpdateToast != null) {
                        forcedUpdateToast.cancel();
                    }
                    forcedUpdateToast = Toast.makeText(getApplicationContext(),
                            getString(R.string.toast_forced_update_recovery), Toast.LENGTH_SHORT);
                    forcedUpdateToast.show();
                    return true;
                }
            });
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.menu_preferences)
                .setView(view)
                .setOnDismissListener(dialogInterface -> {
                    prefs.edit()
                            .putInt(Constants.PREF_AUTO_UPDATES_CHECK_INTERVAL,
                                    autoCheckInterval.getSelectedItemPosition())
                            .putBoolean(Constants.PREF_AB_PERF_MODE, abPerfMode.isChecked())
                            .apply();

                    if (Utils.isUpdateCheckEnabled(this)) {
                        UpdatesCheckReceiver.scheduleRepeatingUpdatesCheck(this);
                    } else {
                        UpdatesCheckReceiver.cancelRepeatingUpdatesCheck(this);
                        UpdatesCheckReceiver.cancelUpdatesCheck(this);
                    }

                    if (Utils.isABDevice()) {
                        boolean enableABPerfMode = abPerfMode.isChecked();
                        mUpdaterController.setPerformanceMode(enableABPerfMode);
                    }
                    if (Utils.isRecoveryUpdateExecPresent()) {
                        boolean enableRecoveryUpdate = updateRecovery.isChecked();
                        SystemProperties.set(Constants.UPDATE_RECOVERY_PROPERTY,
                                String.valueOf(enableRecoveryUpdate));
                    }
                })
                .show();
    }

    private enum Action {
        DOWNLOAD,
        PAUSE,
        RESUME,
        INSTALL,
        INFO,
        DELETE,
        CANCEL_INSTALLATION,
        REBOOT,
    }
}
