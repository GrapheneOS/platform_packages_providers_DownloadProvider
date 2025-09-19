/*
 * Copyright (C) 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.providers.downloads;

import static android.provider.Flags.FLAG_ENABLE_DOCUMENTS_TRASH_API;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.MediaStore;
import android.util.Log;

import androidx.test.InstrumentationRegistry;
import androidx.test.annotation.UiThreadTest;
import androidx.test.filters.SdkSuppress;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.util.Objects;

/**
 * Verifies constraints for trashing and restoring files via the {@link DownloadStorageProvider}.
 */
@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.BAKLAVA, codeName = "Baklava")
@RequiresFlagsEnabled(FLAG_ENABLE_DOCUMENTS_TRASH_API)
public class DownloadFilesTrashTest {

    private static final String TAG = "DownloadFilesTrashTest";

    private File mTestDirectory;
    private Context mContext;
    private ContentResolver mResolver;

    private Context getContext() {
        return InstrumentationRegistry.getContext();
    }

    @Before
    public void setUp() throws Exception {
        // Skip test if the platform SDK is not newer than Android Baklava (SDK 36).
        // The Trash feature under test relies on DocumentsContract APIs introduced in the
        // Android release after Baklava (SDK 36).
        assumeTrue(Build.VERSION.SDK_INT > Build.VERSION_CODES.BAKLAVA);
        mContext = getContext();
        mResolver = mContext.getContentResolver();

        final String downloadsPath = Environment.getExternalStorageDirectory()
                + File.separator + Environment.DIRECTORY_DOWNLOADS;
        mTestDirectory = new File(downloadsPath, "download_files_trash_test");

        if (mTestDirectory.exists()) {
            FsHelper.deleteContents(mTestDirectory);
        } else {
            assertTrue("Failed to create test directory", mTestDirectory.mkdirs());
        }
        MediaStore.scanFile(getContext().getContentResolver(), mTestDirectory);
    }

    @After
    public void tearDown() {
        if (mTestDirectory != null && mTestDirectory.exists()) {
            FsHelper.deleteContents(mTestDirectory);
            mTestDirectory.delete();
        }
    }

    @Test
    public void testQueryDocument() throws Exception {
        final String[] fileNames = new String[]{
                "document-1.txt", "audio.mp3", "video.mp4"
        };
        for (final String fileName : fileNames) {
            createAndScanFileInTestDir(fileName);
        }
        String dirDocId = getTestDirectoryDocId();
        final Uri dirChildDocumentUri = DocumentsContract.buildChildDocumentsUri(
                Constants.STORAGE_AUTHORITY, dirDocId);

        for (final String fileName : fileNames) {
            try (Cursor fileCursor = findDocumentByDisplayName(mResolver, dirChildDocumentUri,
                    fileName)) {
                assertNotNull("File '" + fileName + "' not found", fileCursor);
                assertTrashFlag(fileCursor);
            }
        }
    }

    /**
     * Verifies that a document can be successfully trashed via the {@link DownloadStorageProvider}.
     *
     * <p>The {@link UiThreadTest} annotation is required because this
     * test directly instantiates a ContentProvider, an action that must occur on the UI thread
     * to prevent a {@link RuntimeException}.
     */
    @Test
    @UiThreadTest
    public void testTrashDocument() throws Exception {
        final DownloadStorageProvider downloadStorageProvider = createAndAttachProvider();
        final String fileToTrash = "document-to-trash.txt";
        createAndScanFileInTestDir(fileToTrash);
        final String testDirDocId = getTestDirectoryDocId();
        final Uri dirChildUri = DocumentsContract.buildChildDocumentsUri(
                Constants.STORAGE_AUTHORITY, testDirDocId);
        String fileToTrashDocId;
        try (Cursor fileCursor = findDocumentByDisplayName(mResolver, dirChildUri, fileToTrash)) {
            assertNotNull("File to trash '" + fileToTrash + "' not found before trashing",
                    fileCursor);
            fileToTrashDocId = fileCursor.getString(
                    fileCursor.getColumnIndex(Document.COLUMN_DOCUMENT_ID));
            assertTrashFlag(fileCursor);
        }

        String trashedDocumentId = null;
        try {
            // Trash document
            trashedDocumentId = downloadStorageProvider.trashDocument(fileToTrashDocId);

            assertNotNull("Trashed document ID should not be null", trashedDocumentId);
            try (Cursor trashedFileCursor = findDocumentByDisplayName(mResolver, dirChildUri,
                    fileToTrash)) {
                assertNull("File '" + fileToTrash + "' should not be found after trashing",
                        trashedFileCursor);
            }
        } finally {
            if (trashedDocumentId != null) {
                downloadStorageProvider.deleteDocument(trashedDocumentId);
            }
        }
    }

    /**
     * Verifies that a folder containing a file can be successfully trashed.
     *
     * <p>The {@link UiThreadTest} annotation is required for direct provider instantiation.
     */
    @Test
    @UiThreadTest
    public void testTrashFolderWithFile() throws Exception {
        final DownloadStorageProvider downloadStorageProvider = createAndAttachProvider();
        final String folderName = "folder-to-trash";
        final String fileName = "file-in-folder.txt";
        // Create directory and file inside it
        File folderToTrash = createAndScanFolderInTestDir(folderName);
        createAndScanFileInDir(fileName, folderToTrash);
        // Find the folder's document ID
        final String testDirDocId = getTestDirectoryDocId();
        final Uri testDirChildUri = DocumentsContract.buildChildDocumentsUri(
                Constants.STORAGE_AUTHORITY, testDirDocId);
        String folderToTrashDocId;
        try (Cursor folderCursor = findDocumentByDisplayName(mResolver, testDirChildUri,
                folderName)) {
            assertNotNull("Folder '" + folderName + "' not found before trashing",
                    folderCursor);
            folderToTrashDocId = folderCursor.getString(
                    folderCursor.getColumnIndex(Document.COLUMN_DOCUMENT_ID));
            assertTrashFlag(folderCursor);
        }
        // Verify file exists inside the folder before trashing
        try (Cursor fileCursor = findDocumentByDisplayName(mResolver, testDirChildUri,
                fileName)) {
            assertNotNull("File '" + fileName + "' not found before trashing",
                    fileCursor);
        }

        String trashedFolderDocId = null;
        try {
            // Trash the folder
            trashedFolderDocId = downloadStorageProvider.trashDocument(folderToTrashDocId);

            assertNotNull("Trashed folder document ID should not be null", trashedFolderDocId);
            // Verify the folder is gone from its original location
            try (Cursor trashedFolderCursor = findDocumentByDisplayName(mResolver, testDirChildUri,
                    folderName)) {
                assertNull("Folder " + folderName + " should not be found after trashing",
                        trashedFolderCursor);
            }
            // Verify the file is also gone from its original location
            try (Cursor trashedFileCursor = findDocumentByDisplayName(mResolver, testDirChildUri,
                    fileName)) {
                assertNull("File " + fileName + " should not be found after trashing",
                        trashedFileCursor);
            }
        } finally {
            // Clean up by permanently deleting the trashed folder
            if (trashedFolderDocId != null) {
                downloadStorageProvider.deleteDocument(trashedFolderDocId);
            }
        }
    }


    /**
     * Verifies that a document can be successfully restored via the
     * {@link DownloadStorageProvider}.
     *
     * <p>The {@link UiThreadTest} annotation is required because this
     * test directly instantiates a ContentProvider, an action that must occur on the UI thread
     * to prevent a {@link RuntimeException}.
     */
    @Test
    @UiThreadTest
    public void testRestoreDocument() throws Exception {
        final DownloadStorageProvider downloadStorageProvider = createAndAttachProvider();
        final String fileToRestore = "document-to-restore.txt";
        createAndScanFileInTestDir(fileToRestore);
        final String testDirDocId = getTestDirectoryDocId();
        final Uri dirChildUri = DocumentsContract.buildChildDocumentsUri(
                Constants.STORAGE_AUTHORITY, testDirDocId);
        String fileToTrashedDocId;
        try (Cursor fileCursor = findDocumentByDisplayName(mResolver, dirChildUri, fileToRestore)) {
            assertNotNull("File '" + fileToRestore + "' not found before trashing", fileCursor);
            fileToTrashedDocId = fileCursor.getString(
                    fileCursor.getColumnIndex(Document.COLUMN_DOCUMENT_ID));
            assertTrashFlag(fileCursor);
        }
        // Trash document
        String trashedDocumentId = downloadStorageProvider.trashDocument(fileToTrashedDocId);
        assertNotNull("Trashed document ID should not be null", trashedDocumentId);

        try (Cursor trashedFileCursor = findDocumentByDisplayName(mResolver, dirChildUri,
                fileToRestore)) {
            assertNull("File '" + fileToRestore + "' should not be found after trashing",
                    trashedFileCursor);
        }

        // Restore document
        String restoredDocumentId = downloadStorageProvider.restoreDocumentFromTrash(
                trashedDocumentId, null /* targetId */);

        assertNotNull("Restored document ID should not be null", restoredDocumentId);
        try (Cursor restoredFileCursor = findDocumentByDisplayName(mResolver, dirChildUri,
                fileToRestore)) {
            assertNotNull("File '" + fileToRestore + "' should be found after restoring",
                    restoredFileCursor);
        }
    }

    /**
     * Verifies that a folder containing a file can be successfully restored from trash.
     *
     * <p>The {@link UiThreadTest} annotation is required for direct provider instantiation.
     */
    @Test
    @UiThreadTest
    public void testRestoreFolderWithFile() throws Exception {
        final DownloadStorageProvider downloadStorageProvider = createAndAttachProvider();
        final String folderName = "folder-to-restore";
        final String fileName = "file-in-folder.txt";
        File folderToRestore = createAndScanFolderInTestDir(folderName);
        createAndScanFileInDir(fileName, folderToRestore);
        // Find the folder's document ID
        final String testDirDocId = getTestDirectoryDocId();
        final Uri testDirChildUri = DocumentsContract.buildChildDocumentsUri(
                Constants.STORAGE_AUTHORITY, testDirDocId);
        String folderToTrashedDocId;
        try (Cursor folderCursor = findDocumentByDisplayName(mResolver, testDirChildUri,
                folderName)) {
            assertNotNull("Folder '" + folderName + "' not found before trashing",
                    folderCursor);
            folderToTrashedDocId = folderCursor.getString(
                    folderCursor.getColumnIndex(Document.COLUMN_DOCUMENT_ID));
            assertTrashFlag(folderCursor);
        }
        String trashedFolderDocId = downloadStorageProvider.trashDocument(folderToTrashedDocId);
        assertNotNull("Trashed folder document ID should not be null", trashedFolderDocId);
        // Verify it's gone
        try (Cursor trashedFolderCursor = findDocumentByDisplayName(mResolver, testDirChildUri,
                folderName)) {
            assertNull("Folder '" + folderName + "' should not be found after trashing",
                    trashedFolderCursor);
        }

        // Restore the folder
        String restoredFolderDocId = downloadStorageProvider.restoreDocumentFromTrash(
                trashedFolderDocId, null /* targetId */);

        assertNotNull("Restored folder document ID should not be null", restoredFolderDocId);
        // Verify the folder is back
        String restoredFolderIdFromQuery;
        try (Cursor restoredFolderCursor = findDocumentByDisplayName(mResolver, testDirChildUri,
                folderName)) {
            assertNotNull("Folder '" + folderName + "' should be found after restoring",
                    restoredFolderCursor);
            restoredFolderIdFromQuery = restoredFolderCursor.getString(
                    restoredFolderCursor.getColumnIndex(Document.COLUMN_DOCUMENT_ID));
        }
        // Verify the file is back inside the restored folder
        final Uri restoredFolderChildUri = DocumentsContract.buildChildDocumentsUri(
                Constants.STORAGE_AUTHORITY, restoredFolderIdFromQuery);
        try (Cursor restoredFileCursor = findDocumentByDisplayName(mResolver,
                restoredFolderChildUri,
                fileName)) {
            assertNotNull("File '" + fileName + "' should be found inside restored folder",
                    restoredFileCursor);
        }
    }

    /**
     * Creates a new, empty file in the test directory and synchronously scans it.
     *
     * @param fileName The name of the file to create.
     * @throws IOException If the file cannot be created.
     */
    private File createAndScanFileInTestDir(String fileName)
            throws IOException {
        return createAndScanFileInDir(fileName, mTestDirectory);
    }

    /**
     * Creates a new, empty file in the specified parent directory and synchronously scans it.
     *
     * @param fileName The name of the file to create.
     * @param parent   The directory in which to create the file.
     * @return The {@link File} object for the newly created file.
     * @throws IOException If the file cannot be created.
     */
    private File createAndScanFileInDir(String fileName, File parent) throws IOException {
        File file = new File(parent, fileName);
        if (!file.createNewFile()) {
            throw new IOException("Failed to create new file: " + file.getPath());
        }
        MediaStore.scanFile(getContext().getContentResolver(), file);
        return file;
    }

    /**
     * Creates a new folder in the test directory and synchronously scans it.
     *
     * @param folderName The name of the folder to create.
     * @return The {@link File} object for the newly created folder.
     * @throws IOException If the folder cannot be created.
     */
    private File createAndScanFolderInTestDir(String folderName) throws IOException {
        File folder = new File(mTestDirectory, folderName);
        if (!folder.mkdirs()) {
            throw new IOException("Failed to create new folder: " + folder.getPath());
        }
        MediaStore.scanFile(getContext().getContentResolver(), folder);
        return folder;
    }

    /**
     * Instantiates and attaches info to a new {@link DownloadStorageProvider}.
     * <p>
     * This method must be called from a method annotated with {@link UiThreadTest}.
     *
     * @return An initialized {@link DownloadStorageProvider}.
     * @throws PackageManager.NameNotFoundException if the provider info cannot be resolved.
     */
    private DownloadStorageProvider createAndAttachProvider()
            throws PackageManager.NameNotFoundException {
        DownloadStorageProvider downloadStorageProvider = new DownloadStorageProvider();
        final ProviderInfo info = mContext.getPackageManager()
                .resolveContentProvider(Constants.STORAGE_AUTHORITY,
                        PackageManager.GET_META_DATA);
        downloadStorageProvider.attachInfo(mContext, info);
        return downloadStorageProvider;
    }

    /**
     * Finds the document ID of the test directory.
     *
     * @return The {@link Document#COLUMN_DOCUMENT_ID} of the test directory.
     * @throws Exception if the directory cannot be found.
     */
    private String getTestDirectoryDocId() throws Exception {
        final Uri rootChildUri = DocumentsContract.buildChildDocumentsUri(
                Constants.STORAGE_AUTHORITY,
                Constants.STORAGE_ROOT_ID);
        try (Cursor dirCursor = findDocumentByDisplayName(mResolver, rootChildUri,
                mTestDirectory.getName())) {
            assertNotNull("Test directory '" + mTestDirectory.getName() + "' not found in provider",
                    dirCursor);
            return dirCursor.getString(dirCursor.getColumnIndex(Document.COLUMN_DOCUMENT_ID));
        }
    }

    /**
     * Recursively walks document children to find a target by its display name.
     *
     * @param resolver          The {@link ContentResolver} to use for queries.
     * @param parentUri         The {@link Uri} of the directory to start searching in.
     * @param targetDisplayName The {@link Document#COLUMN_DISPLAY_NAME} to find.
     * @return A {@link Cursor} positioned at the matched document, or {@code null} if not found.
     */
    private Cursor findDocumentByDisplayName(ContentResolver resolver, Uri parentUri,
            String targetDisplayName) {
        try (Cursor childCursor = resolver.query(parentUri, null, null, null, null)) {
            if (childCursor == null) {
                Log.w(TAG, "Query returned null cursor for: " + parentUri);
                return null;
            }
            while (childCursor.moveToNext()) {
                final String displayName = childCursor.getString(
                        childCursor.getColumnIndex(Document.COLUMN_DISPLAY_NAME));

                if (Objects.equals(displayName, targetDisplayName)) {
                    return childCursor;
                }

                // Check subdirectories
                final String docId = childCursor.getString(
                        childCursor.getColumnIndex(Document.COLUMN_DOCUMENT_ID));
                final String mimeType = childCursor.getString(
                        childCursor.getColumnIndex(Document.COLUMN_MIME_TYPE));

                if (Objects.equals(Document.MIME_TYPE_DIR, mimeType)) {
                    final Uri grandChildUri = DocumentsContract.buildChildDocumentsUri(
                            Constants.STORAGE_AUTHORITY, docId);
                    final Cursor foundInChild = findDocumentByDisplayName(resolver, grandChildUri,
                            targetDisplayName);

                    if (foundInChild != null) {
                        return foundInChild;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error while traversing documents: " + e.getMessage(), e);
        }
        return null;
    }

    /**
     * Asserts that the cursor indicates support for trashing but not restoring.
     *
     * @param cursor The document cursor to check.
     */
    private void assertTrashFlag(Cursor cursor) {
        int flags = cursor.getInt(cursor.getColumnIndex(Document.COLUMN_FLAGS));
        assertTrue("Cursor should contains the FLAG_SUPPORTS_TRASH", (flags
                & Document.FLAG_SUPPORTS_TRASH) != 0);
        assertEquals("Cursor should not contains the FLAG_SUPPORTS_RESTORE", 0,
                (flags & Document.FLAG_SUPPORTS_RESTORE));
    }
}
