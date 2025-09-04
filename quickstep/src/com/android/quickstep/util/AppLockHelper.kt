/*
 * Copyright (C) 2025 AxionOS Project
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
package com.android.quickstep.util

import android.app.AppLockManager
import android.content.Context
import android.os.RemoteException
import android.util.Log

import com.android.launcher3.dagger.ApplicationContext
import com.android.launcher3.dagger.LauncherAppComponent
import com.android.launcher3.dagger.LauncherAppSingleton
import com.android.launcher3.util.DaggerSingletonObject

import javax.inject.Inject

@LauncherAppSingleton
class AppLockHelper 
@Inject
constructor(
    @ApplicationContext private val context: Context,
) {
    private val appLockManager = context.getSystemService(AppLockManager::class.java)

    fun isAppLockedOrHidden(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        return isAppLocked(packageName) || isAppHidden(packageName)
    }

    fun isAppLocked(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        return try {
            appLockManager?.packageData?.find {
                it.packageName == packageName
            }?.shouldProtectApp == true
        } catch (e: RemoteException) {
            Log.w(TAG, "RemoteException in isAppLocked: ${e.message}")
            false
        }
    }

    fun isAppHidden(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        return try {
            appLockManager?.getHiddenPackages()?.contains(packageName) ?: false
        } catch (e: RemoteException) {
            Log.w(TAG, "RemoteException in isAppHidden: ${e.message}")
            false
        }
    }

    companion object {
        private const val TAG = "AppLockHelper"

        @JvmField
        val INSTANCE = DaggerSingletonObject(LauncherAppComponent::getAppLockHelper)
    }
}
