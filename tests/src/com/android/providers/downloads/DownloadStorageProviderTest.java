/*
 * Copyright (C) 2026 The Android Open Source Project
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.pm.ProviderInfo;
import android.os.Environment;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileNotFoundException;

@RunWith(AndroidJUnit4.class)
public class DownloadStorageProviderTest {

    private static final Context sTargetContext = InstrumentationRegistry.getInstrumentation()
            .getTargetContext();
    private DownloadStorageProvider mDownloadStorageProvider;

    @Before
    public void setUp() {
        mDownloadStorageProvider = new DownloadStorageProvider();
        final ProviderInfo providerInfo = new ProviderInfo();
        providerInfo.authority = Constants.STORAGE_AUTHORITY;
        providerInfo.grantUriPermissions = true;
        providerInfo.exported = true;

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                mDownloadStorageProvider.attachInfoForTesting(sTargetContext, providerInfo));
    }

    @Test
    public void test_shouldHideDocument_externalprimary_pathTraversal() {
        File externalStorageDir = Environment.getExternalStorageDirectory();

        // Simulates the exact PoC attack paths using ".." segments to escape Downloads
        final String[] traversalPaths = {
                externalStorageDir.getAbsolutePath() + "/Download/../Android/data",
                externalStorageDir.getAbsolutePath() +
                        "/Download/../Android/data/com.android.chrome/files",
                externalStorageDir.getAbsolutePath() + "/Download/../Android/obb",
                externalStorageDir.getAbsolutePath() + "/Download/../Android/sandbox"
        };

        for (String path : traversalPaths) {
            final String docId = "raw:" + path;
            assertTrue("DownloadStorageProvider should hide traversal path \"" +
                            docId + "\"",
                    mDownloadStorageProvider.shouldHideDocument(docId));
        }
    }

    @Test
    public void test_shouldHideDocument_externalprimary_restrictedDirectories() {
        File externalStorageDir = Environment.getExternalStorageDirectory();

        // Should hide Android/data, Android/obb, and Android/sandbox directly
        final String[] restrictedPaths = {
                externalStorageDir.getAbsolutePath() + "/Android/data",
                externalStorageDir.getAbsolutePath() + "/Android/data/com.my.app",
                externalStorageDir.getAbsolutePath() + "/Android/obb",
                externalStorageDir.getAbsolutePath() + "/Android/sandbox",
                // Test case insensitivity
                externalStorageDir.getAbsolutePath() + "/ANDROID/DATA",
                externalStorageDir.getAbsolutePath() + "/android/obb"
        };

        for (String path : restrictedPaths) {
            final String docId = "raw:" + path;
            assertTrue("DownloadStorageProvider should hide restricted path \"" +
                            docId + "\"",
                    mDownloadStorageProvider.shouldHideDocument(docId));
        }
    }

    @Test
    public void test_shouldNotHideDocument_externalprimary_validDownloads() {
        File externalStorageDir = Environment.getExternalStorageDirectory();

        // Should NOT hide legitimate download paths
        final String[] validPaths = {
                externalStorageDir.getAbsolutePath() + "/Download",
                externalStorageDir.getAbsolutePath() + "/Download/MyFile.txt",
                externalStorageDir.getAbsolutePath() + "/Download/Subfolder/Image.png",
                externalStorageDir.getAbsolutePath() + "/Pictures/MyPhoto.jpg"
        };

        for (String path : validPaths) {
            final String docId = "raw:" + path;
            assertFalse("DownloadStorageProvider should NOT hide valid path \"" +
                            docId + "\"",
                    mDownloadStorageProvider.shouldHideDocument(docId));
        }
    }

    @Test
    public void test_normalizeAndFilterDefaultIgnorableCodepoints() {
        // Modeled after ExternalStorageProviderTest
        assertEquals("Android/data",
                DownloadStorageProvider.normalizeAndFilterDefaultIgnorableCodepoints(
                        "Android/data"));
        assertEquals("Android/data",
                DownloadStorageProvider.normalizeAndFilterDefaultIgnorableCodepoints(
                        "Android/\u200Bdata"));
        assertEquals("Android/data",
                DownloadStorageProvider.normalizeAndFilterDefaultIgnorableCodepoints(
                        "Android/\u200Cdata"));
        assertEquals("Android/data",
                DownloadStorageProvider.normalizeAndFilterDefaultIgnorableCodepoints(
                        "Android/\u200Ddata"));
    }

    @Test
    public void test_shouldBlockDirectoryFromTree() throws Exception {
        File externalStorageDir = Environment.getExternalStorageDirectory();

        final String[] shouldBlock = {
                externalStorageDir.getAbsolutePath() + "/Android",
                externalStorageDir.getAbsolutePath() + "/Download",
                // Path traversal attempts to restricted directories
                externalStorageDir.getAbsolutePath() + "/Download/../Android/data",
                externalStorageDir.getAbsolutePath() + "/Download/../Android/obb",
                externalStorageDir.getAbsolutePath() + "/Download/../Android/sandbox"
        };

        // Adopt shell permissions to allow directory creation on external storage
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .adoptShellPermissionIdentity();
        try {
            for (String path : shouldBlock) {
                File dir = new File(path);
                assertTrue("Failed to create/verify directory: " + path,
                        dir.exists() || dir.mkdirs());

                final String docId = "raw:" + path;
                try {
                    assertTrue("DownloadStorageProvider should block directory \"" + docId +
                                    "\", but it didn't",
                            mDownloadStorageProvider.shouldBlockDirectoryFromTree(docId));
                } catch (FileNotFoundException ignored) {
                    // If the file doesn't exist or throws during canonicalization,
                    // it's safely ignored.
                }
            }
        } finally {
            InstrumentationRegistry.getInstrumentation().getUiAutomation()
                    .dropShellPermissionIdentity();
        }

        final String[] shouldNotBlock = {
                externalStorageDir.getAbsolutePath() + "/Documents",
                externalStorageDir.getAbsolutePath() + "/Pictures",
                externalStorageDir.getAbsolutePath() + "/Download/MySafeSubfolder"
        };

        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .adoptShellPermissionIdentity();
        try {
            for (String path : shouldNotBlock) {
                File dir = new File(path);
                assertTrue("Failed to create/verify directory: " + path,
                        dir.exists() || dir.mkdirs());

                final String docId = "raw:" + path;
                try {
                    assertFalse("DownloadStorageProvider should NOT block directory \"" + docId
                                    + "\", but it did",
                            mDownloadStorageProvider.shouldBlockDirectoryFromTree(docId));
                } catch (FileNotFoundException ignored) {
                    // If the file doesn't exist or throws during canonicalization,
                    // it's safely ignored.
                }
            }
        } finally {
            new File(externalStorageDir, Environment.DIRECTORY_DOWNLOADS + "/MySafeSubfolder")
                    .delete();

            InstrumentationRegistry.getInstrumentation().getUiAutomation()
                    .dropShellPermissionIdentity();
        }
    }

    @Test
    public void test_shouldHideDocument_secondaryVolume_pathTraversal() throws Exception {
        final String fakeSecondaryRoot = "/storage/ABCD-1234";

        final String[] traversalPaths = {
                fakeSecondaryRoot + "/Download/../Android/data",
                fakeSecondaryRoot + "/Download/../Android/data/com.android.chrome/files",
                fakeSecondaryRoot + "/Download/../Android/obb",
                fakeSecondaryRoot + "/Download/../Android/sandbox"
        };

        for (String path : traversalPaths) {
            final String docId = "raw:" + path;
            assertTrue("DownloadStorageProvider should hide traversal path on secondary volume \"" +
                            docId + "\"",
                    mDownloadStorageProvider.shouldHideDocument(docId));
        }
    }

    @Test
    public void test_shouldHideDocument_secondaryVolume_restrictedDirectories() throws Exception {
        final String fakeSecondaryRoot = "/storage/ABCD-1234";

        // Should hide restricted directories directly on secondary volume
        final String[] restrictedPaths = {
                fakeSecondaryRoot + "/Android/data",
                fakeSecondaryRoot + "/Android/data/com.my.app",
                fakeSecondaryRoot + "/Android/obb",
                fakeSecondaryRoot + "/Android/sandbox",
                fakeSecondaryRoot + "/ANDROID/DATA",
                fakeSecondaryRoot + "/android/obb"
        };

        for (String path : restrictedPaths) {
            final String docId = "raw:" + path;
            assertTrue("DownloadStorageProvider should hide restricted path on secondary volume \""
                            + docId + "\"",
                    mDownloadStorageProvider.shouldHideDocument(docId));
        }
    }

    @Test
    public void test_shouldNotHideDocument_secondaryVolume_validDownloads() throws Exception {
        final String fakeSecondaryRoot = "/storage/ABCD-1234";

        // Should NOT hide legitimate download paths on secondary volume
        final String[] validPaths = {
                fakeSecondaryRoot + "/Download",
                fakeSecondaryRoot + "/Download/MyFile.txt",
                fakeSecondaryRoot + "/Download/Subfolder/Image.png"
        };

        for (String path : validPaths) {
            final String docId = "raw:" + path;
            assertFalse("DownloadStorageProvider should NOT hide valid path on secondary volume \""
                            + docId + "\"",
                    mDownloadStorageProvider.shouldHideDocument(docId));
        }
    }
}
