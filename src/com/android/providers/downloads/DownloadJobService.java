/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.providers.downloads;

import static android.provider.Downloads.Impl.ALL_DOWNLOADS_CONTENT_URI;

import static com.android.providers.downloads.Constants.TAG;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.ContentValues;
import android.database.ContentObserver;
import android.provider.Downloads;
import android.util.Log;
import android.util.SparseArray;

import com.android.providers.downloads.flags.Flags;

/**
 * Service that hosts download jobs. Each active download job is handled as a
 * unique {@link DownloadThread} instance.
 * <p>
 * The majority of downloads should have ETag values to enable resuming, so if a
 * given download isn't able to finish in the normal job timeout (10 minutes),
 * we just reschedule the job and resume again in the future.
 */
public class DownloadJobService extends JobService {
    // @GuardedBy("mActiveThreads")
    private SparseArray<DownloadThread> mActiveThreads = new SparseArray<>();

    @Override
    public void onCreate() {
        super.onCreate();

        // While someone is bound to us, watch for database changes that should
        // trigger notification updates.
        getContentResolver().registerContentObserver(ALL_DOWNLOADS_CONTENT_URI, true, mObserver);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        getContentResolver().unregisterContentObserver(mObserver);
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        final int id = params.getJobId();

        // Spin up thread to handle this download
        final DownloadInfo info = DownloadInfo.queryDownloadInfo(this, id);
        if (info == null) {
            Log.w(TAG, "Odd, no details found for download " + id);
            return false;
        }

        if (Flags.ensureUijNotification() && params.isUserInitiatedJob()) {
            // For User-Initiated Jobs, we must satisfy the "visual contract" immediately.
            // We update the status to RUNNING now so the Notifier identifies this download
            // as active during the immediate update() call below.
            final ContentValues values = new ContentValues();
            values.put(Downloads.Impl.COLUMN_STATUS, Downloads.Impl.STATUS_RUNNING);
            getContentResolver().update(info.getAllDownloadsUri(), values, null, null);

            // This call performs the mandatory setNotification() handshake with JobScheduler.
            Helpers.getDownloadNotifier(this).update(this, params);
        }

        final DownloadThread thread;
        synchronized (mActiveThreads) {
            if (mActiveThreads.indexOfKey(id) >= 0) {
                Log.w(TAG, "Odd, already running download " + id);
                return false;
            }
            thread = new DownloadThread(this, params, info);
            mActiveThreads.put(id, thread);
        }
        thread.start();

        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        final int id = params.getJobId();
        Log.d(TAG, "onStopJob id=" + id + ", reason=" + params.getDebugStopReason());

        final DownloadThread thread;
        synchronized (mActiveThreads) {
            thread = mActiveThreads.removeReturnOld(id);
        }
        if (thread != null) {
            // If the thread is still running, asynchronously request a
            // shutdown. The thread is responsible for rescheduling the
            // job based on its latest progress.
            thread.requestShutdown();
        }
        return false;
    }

    public void jobFinishedInternal(JobParameters params, boolean needsReschedule) {
        final int id = params.getJobId();

        synchronized (mActiveThreads) {
            mActiveThreads.remove(params.getJobId());
        }
        if (needsReschedule) {
            Helpers.scheduleJob(this, DownloadInfo.queryDownloadInfo(this, id));
        }

        // Update notifications one last time while job is protecting us
        mObserver.onChange(false);

        // We do our own rescheduling above
        jobFinished(params, false);
    }

    private ContentObserver mObserver = new ContentObserver(Helpers.getAsyncHandler()) {
        @Override
        public void onChange(boolean selfChange) {
            Helpers.getDownloadNotifier(DownloadJobService.this).update();
        }
    };
}
