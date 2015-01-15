package com.fsck.k9.ui.messageview;


import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Environment;
import android.util.Log;
import android.widget.Toast;

import com.fsck.k9.K9;
import com.fsck.k9.R;
import com.fsck.k9.cache.TemporaryAttachmentStore;
import com.fsck.k9.helper.FileHelper;
import com.fsck.k9.helper.MediaScannerNotifier;
import com.fsck.k9.mail.internet.MimeUtility;
import com.fsck.k9.mailstore.AttachmentViewInfo;
import org.apache.commons.io.IOUtils;


public class AttachmentController {
    private final Context context;
    private final SingleMessageView messageView;
    private final AttachmentViewInfo attachment;

    AttachmentController(SingleMessageView messageView, AttachmentViewInfo attachment) {
        this.context = messageView.getContext();
        this.messageView = messageView;
        this.attachment = attachment;
    }

    public void viewAttachment() {
        new ViewAttachmentAsyncTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    public void saveAttachment() {
        saveAttachmentTo(K9.getAttachmentDefaultPath());
    }

    public void saveAttachmentTo(String directory) {
        saveAttachmentTo(new File(directory));
    }

    private void saveAttachmentTo(File directory) {
        boolean isExternalStorageMounted = Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED);
        if (!isExternalStorageMounted) {
            String message = context.getString(R.string.message_view_status_attachment_not_saved);
            displayMessageToUser(message);
            return;
        }

        //FIXME: write file in background thread
        try {
            File file = saveAttachmentWithUniqueFileName(directory);

            displayAttachmentSavedMessage(file.toString());

            MediaScannerNotifier.notify(context, file);
        } catch (IOException ioe) {
            if (K9.DEBUG) {
                Log.e(K9.LOG_TAG, "Error saving attachment", ioe);
            }
            displayAttachmentNotSavedMessage();
        }
    }

    private File saveAttachmentWithUniqueFileName(File directory) throws IOException {
        String filename = FileHelper.sanitizeFilename(attachment.displayName);
        File file = FileHelper.createUniqueFile(directory, filename);

        writeAttachmentToStorage(file);

        return file;
    }

    private void writeAttachmentToStorage(File file) throws IOException {
        InputStream in = context.getContentResolver().openInputStream(attachment.uri);
        try {
            OutputStream out = new FileOutputStream(file);
            try {
                IOUtils.copy(in, out);
                out.flush();
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
    }

    private Intent getBestViewIntentAndSaveFileIfNecessary() {
        String displayName = attachment.displayName;
        String inferredMimeType = MimeUtility.getMimeTypeByExtension(displayName);

        IntentAndResolvedActivitiesCount resolvedIntentInfo;
        String mimeType = attachment.mimeType;
        if (MimeUtility.isDefaultMimeType(mimeType)) {
            resolvedIntentInfo = getBestViewIntentForMimeType(inferredMimeType);
        } else {
            resolvedIntentInfo = getBestViewIntentForMimeType(mimeType);
            if (!resolvedIntentInfo.hasResolvedActivities() && !inferredMimeType.equals(mimeType)) {
                resolvedIntentInfo = getBestViewIntentForMimeType(inferredMimeType);
            }
        }

        if (!resolvedIntentInfo.hasResolvedActivities()) {
            resolvedIntentInfo = getBestViewIntentForMimeType(MimeUtility.DEFAULT_ATTACHMENT_MIME_TYPE);
        }

        Intent viewIntent;
        if (resolvedIntentInfo.hasResolvedActivities() && resolvedIntentInfo.containsFileUri()) {
            try {
                File tempFile = TemporaryAttachmentStore.getFileForWriting(context, displayName);
                writeAttachmentToStorage(tempFile);
                viewIntent = createViewIntentForFileUri(resolvedIntentInfo.getMimeType(), Uri.fromFile(tempFile));
            } catch (IOException e) {
                if (K9.DEBUG) {
                    Log.e(K9.LOG_TAG, "Error while saving attachment to use file:// URI with ACTION_VIEW Intent", e);
                }
                viewIntent = createViewIntentForAttachmentProviderUri(MimeUtility.DEFAULT_ATTACHMENT_MIME_TYPE);
            }
        } else {
            viewIntent = resolvedIntentInfo.getIntent();
        }

        return viewIntent;
    }

    private IntentAndResolvedActivitiesCount getBestViewIntentForMimeType(String mimeType) {
        Intent contentUriIntent = createViewIntentForAttachmentProviderUri(mimeType);
        int contentUriActivitiesCount = getResolvedIntentActivitiesCount(contentUriIntent);

        if (contentUriActivitiesCount > 0) {
            return new IntentAndResolvedActivitiesCount(contentUriIntent, contentUriActivitiesCount);
        }

        File tempFile = TemporaryAttachmentStore.getFile(context, attachment.displayName);
        Uri tempFileUri = Uri.fromFile(tempFile);
        Intent fileUriIntent = createViewIntentForFileUri(mimeType, tempFileUri);
        int fileUriActivitiesCount = getResolvedIntentActivitiesCount(fileUriIntent);

        if (fileUriActivitiesCount > 0) {
            return new IntentAndResolvedActivitiesCount(fileUriIntent, fileUriActivitiesCount);
        }

        return new IntentAndResolvedActivitiesCount(contentUriIntent, contentUriActivitiesCount);
    }

    private Intent createViewIntentForAttachmentProviderUri(String mimeType) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(attachment.uri, mimeType);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        addUiIntentFlags(intent);

        return intent;
    }

    private Intent createViewIntentForFileUri(String mimeType, Uri uri) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, mimeType);
        addUiIntentFlags(intent);

        return intent;
    }

    private void addUiIntentFlags(Intent intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET);
    }

    private int getResolvedIntentActivitiesCount(Intent intent) {
        PackageManager packageManager = context.getPackageManager();

        List<ResolveInfo> resolveInfos =
                packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);

        return resolveInfos.size();
    }

    private void displayAttachmentSavedMessage(final String filename) {
        String message = context.getString(R.string.message_view_status_attachment_saved, filename);
        displayMessageToUser(message);
    }

    private void displayAttachmentNotSavedMessage() {
        String message = context.getString(R.string.message_view_status_attachment_not_saved);
        displayMessageToUser(message);
    }

    private void displayMessageToUser(String message) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show();
    }

    private static class IntentAndResolvedActivitiesCount {
        private Intent intent;
        private int activitiesCount;

        IntentAndResolvedActivitiesCount(Intent intent, int activitiesCount) {
            this.intent = intent;
            this.activitiesCount = activitiesCount;
        }

        public Intent getIntent() {
            return intent;
        }

        public boolean hasResolvedActivities() {
            return activitiesCount > 0;
        }

        public String getMimeType() {
            return intent.getType();
        }

        public boolean containsFileUri() {
            return "file".equals(intent.getData().getScheme());
        }
    }

    private class ViewAttachmentAsyncTask extends AsyncTask<Void, Void, Intent> {

        @Override
        protected void onPreExecute() {
            messageView.disableAttachmentViewButton(attachment);
        }

        @Override
        protected Intent doInBackground(Void... params) {
            return getBestViewIntentAndSaveFileIfNecessary();
        }

        @Override
        protected void onPostExecute(Intent intent) {
            viewAttachment(intent);
            messageView.enableAttachmentViewButton(attachment);
        }

        private void viewAttachment(Intent intent) {
            try {
                context.startActivity(intent);
            } catch (ActivityNotFoundException e) {
                Log.e(K9.LOG_TAG, "Could not display attachment of type " + attachment.mimeType, e);

                String message = context.getString(R.string.message_view_no_viewer, attachment.mimeType);
                displayMessageToUser(message);
            }
        }
    }
}
