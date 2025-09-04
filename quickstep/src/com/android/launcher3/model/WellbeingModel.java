/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.launcher3.model;

import static android.content.ContentResolver.SCHEME_CONTENT;

import static com.android.launcher3.util.SimpleBroadcastReceiver.getPackageFilter;

import android.app.AlertDialog;
import android.app.AppGlobals;
import android.app.RemoteAction;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.SuspendDialogInfo;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Bundle;
import android.os.DeadObjectException;
import android.os.Handler;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;
import android.view.View;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.android.launcher3.AbstractFloatingView;
import com.android.launcher3.R;
import com.android.launcher3.dagger.ApplicationContext;
import com.android.launcher3.dagger.LauncherAppSingleton;
import com.android.launcher3.model.data.ItemInfo;
import com.android.launcher3.popup.RemoteActionShortcut;
import com.android.launcher3.popup.SystemShortcut;
import com.android.launcher3.util.DaggerSingletonObject;
import com.android.launcher3.util.DaggerSingletonTracker;
import com.android.launcher3.util.Executors;
import com.android.launcher3.util.Preconditions;
import com.android.launcher3.util.SafeCloseable;
import com.android.launcher3.util.SimpleBroadcastReceiver;
import com.android.launcher3.views.ActivityContext;
import com.android.quickstep.dagger.QuickstepBaseAppComponent;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.inject.Inject;

/**
 * Data model for digital wellbeing status of apps.
 */
@LauncherAppSingleton
public final class WellbeingModel implements SafeCloseable {
    private static final String TAG = "WellbeingModel";
    private static final int[] RETRY_TIMES_MS = {5000, 15000, 30000};
    private static final boolean DEBUG = false;

    // Welbeing contract
    private static final String PATH_ACTIONS = "actions";
    private static final String METHOD_GET_ACTIONS = "get_actions";
    private static final String EXTRA_ACTIONS = "actions";
    private static final String EXTRA_ACTION = "action";
    private static final String EXTRA_MAX_NUM_ACTIONS_SHOWN = "max_num_actions_shown";
    private static final String EXTRA_PACKAGES = "packages";
    private static final String EXTRA_SUCCESS = "success";

    public static final DaggerSingletonObject<WellbeingModel> INSTANCE =
            new DaggerSingletonObject<>(QuickstepBaseAppComponent::getWellbeingModel);

    private final Context mContext;
    private final String mWellbeingProviderPkg;

    private final Handler mWorkerHandler;
    private final ContentObserver mContentObserver;
    private final SimpleBroadcastReceiver mWellbeingAppChangeReceiver;
    private final SimpleBroadcastReceiver mAppAddRemoveReceiver;

    private final Object mModelLock = new Object();
    // Maps the action Id to the corresponding RemoteAction
    private final Map<String, RemoteAction> mActionIdMap = new ArrayMap<>();
    private final Map<String, String> mPackageToActionId = new HashMap<>();

    private boolean mIsInTest;

    @Inject
    WellbeingModel(@ApplicationContext final Context context,
            DaggerSingletonTracker tracker) {
        mContext = context;
        mWellbeingProviderPkg = mContext.getString(R.string.wellbeing_provider_pkg);
        mWorkerHandler = new Handler(TextUtils.isEmpty(mWellbeingProviderPkg)
                ? Executors.UI_HELPER_EXECUTOR.getLooper()
                : Executors.getPackageExecutor(mWellbeingProviderPkg).getLooper());
        mWellbeingAppChangeReceiver =
                new SimpleBroadcastReceiver(context, mWorkerHandler, t -> restartObserver());
        mAppAddRemoveReceiver =
                new SimpleBroadcastReceiver(context, mWorkerHandler, this::onAppPackageChanged);


        mContentObserver = new ContentObserver(mWorkerHandler) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                updateAllPackages();
            }
        };
        mWorkerHandler.post(this::initializeInBackground);
        tracker.addCloseable(this);
    }

    @WorkerThread
    private void initializeInBackground() {
        if (!TextUtils.isEmpty(mWellbeingProviderPkg)) {
            mContext.registerReceiver(
                    mWellbeingAppChangeReceiver,
                    getPackageFilter(mWellbeingProviderPkg,
                            Intent.ACTION_PACKAGE_ADDED, Intent.ACTION_PACKAGE_CHANGED,
                            Intent.ACTION_PACKAGE_REMOVED, Intent.ACTION_PACKAGE_DATA_CLEARED,
                            Intent.ACTION_PACKAGE_RESTARTED),
                    null, mWorkerHandler);

            IntentFilter filter = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
            filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
            filter.addDataScheme("package");
            mContext.registerReceiver(mAppAddRemoveReceiver, filter, null, mWorkerHandler);

            restartObserver();
        }
    }

    @Override
    public void close() {
        if (!TextUtils.isEmpty(mWellbeingProviderPkg)) {
            mWorkerHandler.post(() -> {
                mWellbeingAppChangeReceiver.unregisterReceiverSafely();
                mAppAddRemoveReceiver.unregisterReceiverSafely();
                mContext.getContentResolver().unregisterContentObserver(mContentObserver);
            });
        }
    }

    public void setInTest(boolean inTest) {
        mIsInTest = inTest;
    }

    @WorkerThread
    private void restartObserver() {
        final ContentResolver resolver = mContext.getContentResolver();
        resolver.unregisterContentObserver(mContentObserver);
        Uri actionsUri = apiBuilder().path(PATH_ACTIONS).build();
        try {
            resolver.registerContentObserver(
                    actionsUri, true /* notifyForDescendants */, mContentObserver);
        } catch (Exception e) {
            Log.e(TAG, "Failed to register content observer for " + actionsUri + ": " + e);
            if (mIsInTest) throw new RuntimeException(e);
        }
        updateAllPackages();
    }

    @MainThread
    private SystemShortcut getShortcutForApp(String packageName, int userId,
            Context context, ItemInfo info, View originalView) {
        Preconditions.assertUIThread();
        // Work profile apps are not recognized by digital wellbeing.
        if (userId != UserHandle.myUserId()) {
            if (DEBUG || mIsInTest) {
                Log.d(TAG, "getShortcutForApp [" + packageName + "]: not current user");
            }
            return null;
        }

        synchronized (mModelLock) {
            String actionId = mPackageToActionId.get(packageName);
            final RemoteAction action = actionId != null ? mActionIdMap.get(actionId) : null;
            if (action == null) {
                if (DEBUG || mIsInTest) {
                    Log.d(TAG, "getShortcutForApp [" + packageName + "]: no action");
                }
                return null;
            }
            if (DEBUG || mIsInTest) {
                Log.d(TAG,
                        "getShortcutForApp [" + packageName + "]: action: '" + action.getTitle()
                                + "'");
            }
            return new RemoteActionShortcut(action, context, info, originalView);
        }
    }

    private Uri.Builder apiBuilder() {
        return new Uri.Builder()
                .scheme(SCHEME_CONTENT)
                .authority(mWellbeingProviderPkg + ".api");
    }

    @WorkerThread
    private boolean updateActions(String[] packageNames) {
        if (packageNames.length == 0) {
            return true;
        }
        if (DEBUG || mIsInTest) {
            Log.d(TAG, "retrieveActions() called with: packageNames = [" + String.join(", ",
                    packageNames) + "]");
        }
        Preconditions.assertNonUiThread();

        Uri contentUri = apiBuilder().build();
        final Bundle remoteActionBundle;
        try (ContentProviderClient client = mContext.getContentResolver()
                .acquireUnstableContentProviderClient(contentUri)) {
            if (client == null) {
                if (DEBUG || mIsInTest) Log.i(TAG, "retrieveActions(): null provider");
                return false;
            }

            // Prepare wellbeing call parameters.
            final Bundle params = new Bundle();
            params.putStringArray(EXTRA_PACKAGES, packageNames);
            params.putInt(EXTRA_MAX_NUM_ACTIONS_SHOWN, 1);
            // Perform wellbeing call .
            remoteActionBundle = client.call(METHOD_GET_ACTIONS, null, params);
            if (!remoteActionBundle.getBoolean(EXTRA_SUCCESS, true)) return false;

            synchronized (mModelLock) {
                // Remove the entries for requested packages, and then update the fist with what we
                // got from service
                Arrays.stream(packageNames).forEach(mPackageToActionId::remove);

                // The result consists of sub-bundles, each one is per a remote action. Each
                // sub-bundle has a RemoteAction and a list of packages to which the action applies.
                for (String actionId :
                        remoteActionBundle.getStringArray(EXTRA_ACTIONS)) {
                    final Bundle actionBundle = remoteActionBundle.getBundle(actionId);
                    mActionIdMap.put(actionId,
                            actionBundle.getParcelable(EXTRA_ACTION));

                    final String[] packagesForAction =
                            actionBundle.getStringArray(EXTRA_PACKAGES);
                    if (DEBUG || mIsInTest) {
                        Log.d(TAG, "....actionId: " + actionId + ", packages: " + String.join(", ",
                                packagesForAction));
                    }
                    for (String packageName : packagesForAction) {
                        mPackageToActionId.put(packageName, actionId);
                    }
                }
            }
        } catch (DeadObjectException e) {
            Log.i(TAG, "retrieveActions(): DeadObjectException");
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Failed to retrieve data from " + contentUri + ": " + e);
            if (mIsInTest) throw new RuntimeException(e);
            return true;
        }
        if (DEBUG || mIsInTest) Log.i(TAG, "retrieveActions(): finished");
        return true;
    }

    @WorkerThread
    private void updateActionsWithRetry(int retryCount, @Nullable String packageName) {
        if (DEBUG || mIsInTest) {
            Log.i(TAG,
                    "updateActionsWithRetry(); retryCount: " + retryCount + ", package: "
                            + packageName);
        }
        String[] packageNames = TextUtils.isEmpty(packageName)
                ? mContext.getSystemService(LauncherApps.class)
                .getActivityList(null, Process.myUserHandle()).stream()
                .map(li -> li.getApplicationInfo().packageName).distinct()
                .toArray(String[]::new)
                : new String[]{packageName};

        mWorkerHandler.removeCallbacksAndMessages(packageName);
        if (updateActions(packageNames)) {
            return;
        }
        if (retryCount >= RETRY_TIMES_MS.length) {
            // To many retries, skip
            return;
        }
        mWorkerHandler.postDelayed(
                () -> {
                    if (DEBUG || mIsInTest) Log.i(TAG, "Retrying; attempt " + (retryCount + 1));
                    updateActionsWithRetry(retryCount + 1, packageName);
                },
                packageName, RETRY_TIMES_MS[retryCount]);
    }

    @WorkerThread
    private void updateAllPackages() {
        if (DEBUG || mIsInTest) Log.i(TAG, "updateAllPackages");
        updateActionsWithRetry(0, null);
    }

    @WorkerThread
    private void onAppPackageChanged(Intent intent) {
        if (DEBUG || mIsInTest) Log.d(TAG, "Changes in apps: intent = [" + intent + "]");
        Preconditions.assertNonUiThread();

        final String packageName = intent.getData().getSchemeSpecificPart();
        if (packageName == null || packageName.length() == 0) {
            // they sent us a bad intent
            return;
        }
        final String action = intent.getAction();
        if (Intent.ACTION_PACKAGE_REMOVED.equals(action)) {
            mWorkerHandler.removeCallbacksAndMessages(packageName);
            synchronized (mModelLock) {
                mPackageToActionId.remove(packageName);
            }
        } else if (Intent.ACTION_PACKAGE_ADDED.equals(action)) {
            updateActionsWithRetry(0, packageName);
        }
    }

    /**
     * Shortcut factory for generating wellbeing action
     */
    public static final SystemShortcut.Factory<ActivityContext> SHORTCUT_FACTORY =
            (context, info, originalView) ->
                    (info.getTargetComponent() == null) ? null
                            : INSTANCE.get(originalView.getContext()).getShortcutForApp(
                                    info.getTargetComponent().getPackageName(), info.user.getIdentifier(),
                                    ActivityContext.lookupContext(originalView.getContext()),
                                    info, originalView);

    public static final SystemShortcut.Factory<ActivityContext> PAUSE_APPS =
            (activity, itemInfo, originalView) -> {
                if (originalView == null) {
                    return null;
                }
                String packageName = itemInfo.getTargetComponent().getPackageName();
                PackageManager packageManager = originalView.getContext().getPackageManager();
                if (Arrays.asList(packageManager.getUnsuspendablePackages(
                        new String[]{packageName})).contains(packageName)) {
                    return null;
                }
                if (packageManager.isPackageSuspendedForUser(
                        itemInfo.getTargetComponent().getPackageName(),
                        itemInfo.user.getIdentifier())) {
                    return null;
                }
                return new PauseApps(activity, itemInfo, originalView);
            };

    public static class PauseApps<T extends ActivityContext> extends SystemShortcut<T> {

        public PauseApps(T target, ItemInfo itemInfo, View originalView) {
            super(R.drawable.ic_hourglass, R.string.paused_apps_drop_target_label, target,
                    itemInfo, originalView);
        }

        @Override
        public void onClick(View view) {
            final Context context = view.getContext();
            final String packageToSuspend = mItemInfo.getTargetComponent().getPackageName();
            final UserHandle packageUser = mItemInfo.user;
            final PackageManager packageManager = context.getPackageManager();
            CharSequence appLabel = packageToSuspend;
            try {
                appLabel = packageManager.getApplicationLabel(
                        packageManager.getApplicationInfoAsUser(packageToSuspend,
                                PackageManager.ApplicationInfoFlags.of(0), packageUser));
            } catch (PackageManager.NameNotFoundException e) {

            }
            new AlertDialog.Builder(context)
                    .setTitle(context.getString(R.string.pause_apps_dialog_title, appLabel))
                    .setMessage(context.getString(R.string.pause_apps_dialog_message, appLabel))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.pause, (dialog, which) ->
                            suspendPackages(context, List.of(packageToSuspend), packageUser))
                    .show();
            AbstractFloatingView.closeAllOpenViews(mTarget);
        }
    }

    /** Suspends a list of packages in the target user. */
    public static void suspendPackages(final @NonNull Context context,
            final @NonNull List<String> packages,
            final @NonNull UserHandle targetUser) {
        Objects.requireNonNull(packages, "packages must not be null");
        Objects.requireNonNull(targetUser, "targetUser must not be null");
        try {
            AppGlobals.getPackageManager().setPackagesSuspendedAsUser(
                    /* packageNames */ packages.toArray(new String[0]),
                    /* suspended */ true,
                    /* appExtras */ null,
                    /* launcherExtras */ null,
                    buildSuspendDialog(),
                    /* flags */ 0,
                    /* suspendingPackage */ context.getOpPackageName(),
                    /* suspendingUserId */ context.getUserId(),
                    targetUser.getIdentifier());
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to suspend " + targetUser + " packages: " + packages, e);
        }
    }

    private static SuspendDialogInfo buildSuspendDialog() {
        return new SuspendDialogInfo.Builder()
                .setIcon(R.drawable.ic_hourglass_top)
                .setTitle(R.string.paused_apps_dialog_title)
                .setMessage(R.string.paused_apps_dialog_message)
                .setNeutralButtonAction(SuspendDialogInfo.BUTTON_ACTION_UNSUSPEND)
                .build();
    }
}
