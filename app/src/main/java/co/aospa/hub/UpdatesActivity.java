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
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemProperties;
import android.text.format.Formatter;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.preference.PreferenceManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
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

    private ExtendedFloatingActionButton mUpdateAction;
    private ExtendedFloatingActionButton mDeleteAction;
    private TextView mUpdateStatus;
    private TextView mHeaderSecurityPatch;
    private LinearLayout mProgress;
    private LinearProgressIndicator mProgressBar;
    private TextView mProgressText;
    private TextView mProgressPercent;

    private String mSelectedDownload;
    private List<String> mDownloadIds;
    private UpdaterController mUpdaterController;
    private final ServiceConnection mConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
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

        mUpdateAction = findViewById(R.id.update_action);
        mDeleteAction = findViewById(R.id.delete_action);
        mUpdateStatus = findViewById(R.id.update_status);
        mProgress = findViewById(R.id.progress);
        mProgressBar = findViewById(R.id.progress_bar);
        mProgressText = findViewById(R.id.progress_text);
        mProgressPercent = findViewById(R.id.progress_percent);

        TextView headerBuildVersion = findViewById(R.id.header_build_version);
        headerBuildVersion.setText(
                getString(R.string.header_android_version, Build.VERSION.RELEASE));

        TextView headerSecurityPatch = findViewById(R.id.header_security_patch);
        headerSecurityPatch.setText(getString(R.string.header_android_security_update, Build.VERSION.SECURITY_PATCH));

        updateLastCheckedString();

        mUpdateAction.setOnClickListener(v -> handleUpdateAction());
        mDeleteAction.setOnClickListener(v -> getDeleteDialog(mSelectedDownload).show());
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
        if (itemId == R.id.menu_preferences) {
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

    private void handleUpdateAction() {
        switch (mUpdateAction.getText().toString()) {
            case "Check for update":
                downloadUpdatesList(true);
                break;
            case "Download":
                startDownloadWithWarning(mSelectedDownload);
                break;
            case "Pause":
                mUpdaterController.pauseDownload(mSelectedDownload);
                break;
            case "Resume":
                UpdateInfo update = mUpdaterController.getUpdate(mSelectedDownload);
                if (Utils.canInstall(update) || update.getFile().length() == update.getFileSize()) {
                    mUpdaterController.resumeDownload(mSelectedDownload);
                } else {
                    showSnackbar(R.string.snack_update_not_installable, Snackbar.LENGTH_LONG);
                }
                break;
            case "Install":
                if (Utils.canInstall(mUpdaterController.getUpdate(mSelectedDownload))) {
                    getInstallDialog(mSelectedDownload).show();
                } else {
                    showSnackbar(R.string.snack_update_not_installable, Snackbar.LENGTH_LONG);
                }
                break;
            case "Reboot":
                PowerManager pm = getSystemService(PowerManager.class);
                pm.reboot(null);
                break;
        }
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
            setUpdateActionButton(Action.CHECK_UPDATES, null, true);
            mUpdateStatus.setText(R.string.list_no_updates);
            return;
        }

        UpdateInfo update = mUpdaterController.getUpdate(downloadId);
        if (update == null) {
            return;
        }

        mUpdateStatus.setText(R.string.system_update_available);

        boolean activeLayout = update.getPersistentStatus() == UpdateStatus.Persistent.INCOMPLETE ||
                update.getStatus() == UpdateStatus.STARTING ||
                update.getStatus() == UpdateStatus.INSTALLING ||
                mUpdaterController.isVerifyingUpdate();

        if (activeLayout) {
            handleActiveStatus(update);
        } else {
            handleNotActiveStatus(update);
        }

        mSelectedDownload = downloadId;
        mDeleteAction.setVisibility(activeLayout ? View.GONE : View.VISIBLE);
    }

    private void handleActiveStatus(UpdateInfo update) {
        final String downloadId = update.getDownloadId();
        if (mUpdaterController.isDownloading(downloadId)) {
            String downloaded = Formatter.formatShortFileSize(this, update.getFile().length());
            String total = Formatter.formatShortFileSize(this, update.getFileSize());
            String percentage = NumberFormat.getPercentInstance().format(update.getProgress() / 100.f);
            mProgressPercent.setText(percentage);
            mProgressText.setText(getString(R.string.list_download_progress_newer, downloaded, total));
            setUpdateActionButton(Action.PAUSE, downloadId, true);
            mProgressBar.setIndeterminate(update.getStatus() == UpdateStatus.STARTING);
            mProgressBar.setProgress(update.getProgress());
        } else if (mUpdaterController.isInstallingUpdate(downloadId)) {
            setUpdateActionButton(Action.CANCEL_INSTALLATION, downloadId, true);
            boolean notAB = !mUpdaterController.isInstallingABUpdate();
            mProgressText.setText(notAB ? R.string.dialog_prepare_zip_message :
                    update.getFinalizing() ?
                            R.string.finalizing_package :
                            R.string.preparing_ota_first_boot);
            mProgressPercent.setText(NumberFormat.getPercentInstance().format(update.getInstallProgress() / 100.f));
            mProgressBar.setIndeterminate(false);
            mProgressBar.setProgress(update.getInstallProgress());
        } else if (mUpdaterController.isVerifyingUpdate(downloadId)) {
            setUpdateActionButton(Action.INSTALL, downloadId, false);
            mProgressText.setText(R.string.list_verifying_update);
            mProgressBar.setIndeterminate(true);
        } else {
            setUpdateActionButton(Action.RESUME, downloadId, !isBusy());
            String downloaded = Formatter.formatShortFileSize(this, update.getFile().length());
            String total = Formatter.formatShortFileSize(this, update.getFileSize());
            String percentage = NumberFormat.getPercentInstance().format(update.getProgress() / 100.f);
            mProgressPercent.setText(percentage);
            mProgressText.setText(getString(R.string.list_download_progress_newer, downloaded, total));
            mProgressBar.setIndeterminate(false);
            mProgressBar.setProgress(update.getProgress());
        }

        mProgress.setVisibility(View.VISIBLE);
    }

    private void handleNotActiveStatus(UpdateInfo update) {
        final String downloadId = update.getDownloadId();
        if (mUpdaterController.isWaitingForReboot(downloadId)) {
            setUpdateActionButton(Action.REBOOT, downloadId, true);
        } else if (update.getPersistentStatus() == UpdateStatus.Persistent.VERIFIED) {
            setUpdateActionButton(Utils.canInstall(update) ? Action.INSTALL : Action.DELETE, downloadId, !isBusy());
        } else if (!Utils.canInstall(update)) {
            setUpdateActionButton(Action.INFO, downloadId, !isBusy());
        } else {
            setUpdateActionButton(Action.DOWNLOAD, downloadId, !isBusy());
        }
        mProgress.setVisibility(View.INVISIBLE);
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

    private void showSnackbar(int stringId, int duration) {
        mUpdateStatus.setText(stringId);
    }

    private void setUpdateActionButton(Action action, final String downloadId, boolean enabled) {
        switch (action) {
            case CHECK_UPDATES:
                mUpdateAction.setText(R.string.check_for_update);
                mUpdateAction.setIcon(getDrawable(R.drawable.ic_refresh));
                break;
            case DOWNLOAD:
                mUpdateAction.setText(R.string.action_download);
                mUpdateAction.setIcon(getDrawable(R.drawable.ic_download));
                break;
            case PAUSE:
                mUpdateAction.setText(R.string.action_pause);
                mUpdateAction.setIcon(getDrawable(R.drawable.ic_pause));
                break;
            case RESUME:
                mUpdateAction.setText(R.string.action_resume);
                mUpdateAction.setIcon(getDrawable(R.drawable.ic_resume));
                break;
            case INSTALL:
                mUpdateAction.setText(R.string.action_install);
                mUpdateAction.setIcon(getDrawable(R.drawable.ic_system_update));
                break;
            case INFO:
                mUpdateAction.setText(R.string.action_info);
                mUpdateAction.setIcon(getDrawable(R.drawable.ic_info));
                break;
            case DELETE:
                mUpdateAction.setText(R.string.action_delete);
                mUpdateAction.setIcon(getDrawable(R.drawable.ic_delete));
                break;
            case CANCEL_INSTALLATION:
                mUpdateAction.setText(R.string.action_cancel);
                mUpdateAction.setIcon(getDrawable(R.drawable.ic_cancel));
                break;
            case REBOOT:
                mUpdateAction.setText(R.string.reboot);
                mUpdateAction.setIcon(getDrawable(R.drawable.ic_refresh));
                break;
        }
        mUpdateAction.setEnabled(enabled);
    }

    private void startDownloadWithWarning(final String downloadId) {
        if (!Utils.isNetworkMetered(this)) {
            mUpdaterController.startDownload(downloadId);
            return;
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.update_over_metered_network_title)
                .setMessage(R.string.update_over_metered_network_message)
                .setPositiveButton(R.string.action_download,
                        (dialog, which) -> mUpdaterController.startDownload(downloadId))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private MaterialAlertDialogBuilder getDeleteDialog(final String downloadId) {
        return new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.confirm_delete_dialog_title)
                .setMessage(R.string.confirm_delete_dialog_message)
                .setPositiveButton(android.R.string.ok,
                        (dialog, which) -> {
                            mUpdaterController.pauseDownload(downloadId);
                            mUpdaterController.deleteUpdate(downloadId);
                        })
                .setNegativeButton(android.R.string.cancel, null);
    }

    private MaterialAlertDialogBuilder getInstallDialog(final String downloadId) {
        if (!isBatteryLevelOk()) {
            Resources resources = getResources();
            String message = resources.getString(R.string.dialog_battery_low_message_pct,
                    resources.getInteger(R.integer.battery_ok_percentage_discharging),
                    resources.getInteger(R.integer.battery_ok_percentage_charging));
            return new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.dialog_battery_low_title)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok, null);
        }
        if (isScratchMounted()) {
            return new MaterialAlertDialogBuilder(this)
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
        return new MaterialAlertDialogBuilder(this)
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

    private MaterialAlertDialogBuilder getCancelInstallationDialog() {
        return new MaterialAlertDialogBuilder(this)
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
        new MaterialAlertDialogBuilder(this)
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
            updateRecovery.setVisibility(View.GONE);
        } else if (Utils.isRecoveryUpdateExecPresent()) {
            updateRecovery.setChecked(
                    SystemProperties.getBoolean(Constants.UPDATE_RECOVERY_PROPERTY, false));
        } else {
            updateRecovery.setChecked(true);
            updateRecovery.setOnTouchListener((v, event) -> {
                Toast.makeText(getApplicationContext(),
                        R.string.toast_forced_update_recovery, Toast.LENGTH_SHORT).show();
                return true;
            });
        }

        new MaterialAlertDialogBuilder(this)
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
        CHECK_UPDATES,
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