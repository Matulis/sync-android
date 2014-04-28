/**
 * Copyright (c) 2014 Cloudant, Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */

package com.cloudant.sync.datastore;

import com.cloudant.common.Log;
import com.cloudant.sync.sqlite.ContentValues;
import com.cloudant.sync.sqlite.Cursor;
import com.cloudant.sync.util.CouchUtils;
import com.cloudant.sync.util.Misc;

import org.apache.commons.codec.binary.Hex;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Created by tomblench on 14/03/2014.
 */
class AttachmentManager {

    private static final String LOG_TAG = "AttachmentManager";

    private static final String EXTENSION_NAME = "com.cloudant.attachments";

    private static final String SQL_ATTACHMENTS_SELECT = "SELECT sequence, " +
            "filename, " +
            "key, " +
            "type, " +
            "encoding, " +
            "length, " +
            "encoded_length, " +
            "revpos " +
            " FROM attachments " +
            " WHERE filename = ? and sequence = ?";

    private static final String SQL_ATTACHMENTS_SELECT_ALL = "SELECT sequence, " +
            "filename, " +
            "key, " +
            "type, " +
            "encoding, " +
            "length, " +
            "encoded_length, " +
            "revpos " +
            " FROM attachments " +
            " WHERE sequence = ?";

    private static final String SQL_ATTACHMENTS_SELECT_ALL_KEYS = "SELECT key " +
            " FROM attachments";

    private String attachmentsDir;

    private BasicDatastore datastore;

    private enum Encoding {
        Plain,
        Gzip
    }

    public AttachmentManager(BasicDatastore datastore) {
        this.datastore = datastore;
        this.attachmentsDir = datastore.extensionDataFolder(EXTENSION_NAME);
    }

    protected boolean addAttachment(Attachment a, DocumentRevision rev) {

        // do it this way to only go thru inputstream once
        // * write to temp location using copyinputstreamtofile
        // * get sha1
        // * stick it into database
        // * move file using sha1 as name

        try {
            File tempFile = new File(this.attachmentsDir, "temp" + UUID.randomUUID());
            FileUtils.copyInputStreamToFile(a.getInputStream(), tempFile);

            ContentValues values = new ContentValues();
            long sequence = rev.getSequence();
            String filename = a.name;
            byte[] sha1 = Misc.getSha1(new FileInputStream(tempFile));
            String type = a.type;
            int encoding = Encoding.Plain.ordinal();
            long length = a.size;
            long revpos = CouchUtils.generationFromRevId(rev.getRevision());

            values.put("sequence", sequence);
            values.put("filename", filename);
            values.put("key", sha1);
            values.put("type", type);
            values.put("encoding", encoding);
            values.put("length", length);
            values.put("encoded_length", length);
            values.put("revpos", revpos);

            // delete and insert in case there is already an attachment at this seq (eg copied over from a previous rev)
            datastore.getSQLDatabase().delete("attachments", " filename = ? and sequence = ? ", new String[]{filename, String.valueOf(sequence)});
            long result = datastore.getSQLDatabase().insert("attachments", values);
            if (result == -1) {
                // if we can't insert into DB then don't copy the attachment
                Log.e(LOG_TAG, "Could not insert attachment " + a + " into database; not copying to attachments directory");
                tempFile.delete();
                return false;
            }
            // move file to blob store, with file name based on sha1
            File newFile = fileFromKey(sha1);
            FileUtils.moveFile(tempFile, newFile);
        } catch (IOException e) {
            Log.e(LOG_TAG, "Got IOException in addAttachment: "+e);
            // TODO check if temp file is still there and delete it?
            return false;
        }
        return true;
    }

    protected DocumentRevision updateAttachments(DocumentRevision rev, List<? extends Attachment> attachments) throws ConflictException {

        // add attachments and then return new revision:
        // * save new (unmodified) revision which will have new _attachments when synced
        // * for each attachment, add attachment to db linked to this revision

        try {
            this.datastore.getSQLDatabase().beginTransaction();

            DocumentRevision newDocument = datastore.updateDocument(rev.getId(),
                    rev.getRevision(),
                    rev.getBody());

            boolean ok = true;

            for (Attachment a : attachments) {
                boolean result = this.addAttachment(a, newDocument);
                if (!result) {
                    ok = false;
                    break;
                }
            }

            if (ok) {
                this.datastore.getSQLDatabase().setTransactionSuccessful();
            }

            if (ok) {
                return newDocument;
            } else {
                return null;
            }
        } finally {
            this.datastore.getSQLDatabase().endTransaction();
        }
    }

    protected Attachment getAttachment(DocumentRevision rev, String attachmentName) {
        try {
            Cursor c = datastore.getSQLDatabase().rawQuery(SQL_ATTACHMENTS_SELECT,
                    new String[]{attachmentName, String.valueOf(rev.getSequence())});
            if (c.moveToFirst()) {
                int sequence = c.getInt(0);
                byte[] key = c.getBlob(2);
                String type = c.getString(3);
                int revpos = c.getInt(7);
                File file = fileFromKey(key);
                return new SavedAttachment(attachmentName, revpos, sequence, key, type, file);
            }
            return null;
        } catch (SQLException e) {
            return null;
        }
    }

    protected List<? extends Attachment> attachmentsForRevision(DocumentRevision rev) {
        try {
            LinkedList<SavedAttachment> atts = new LinkedList<SavedAttachment>();
            long sequence = rev.getSequence();
            Cursor c = datastore.getSQLDatabase().rawQuery(SQL_ATTACHMENTS_SELECT_ALL,
                    new String[]{String.valueOf(sequence)});
            while (c.moveToNext()) {
                String name = c.getString(1);
                byte[] key = c.getBlob(2);
                String type = c.getString(3);
                int revpos = c.getInt(7);
                File file = fileFromKey(key);
                atts.add(new SavedAttachment(name, revpos, sequence, key, type, file));
            }
            return atts;
        } catch (SQLException e) {
            return null;
        }
    }

    protected DocumentRevision removeAttachments(DocumentRevision rev, String[] attachmentNames)
            throws ConflictException {

        boolean rowsDeleted = false;

        // args looks like {attName_1, ..., attName_n, sequence}
        String[] args = new String[attachmentNames.length+1];
        System.arraycopy(attachmentNames, 0, args, 0, attachmentNames.length);
        args[args.length-1] = String.valueOf(rev.getSequence());

        rowsDeleted = datastore.getSQLDatabase().delete("attachments",
                String.format("filename in (%s) and sequence = ?",
                        SQLDatabaseUtils.makePlaceholders(attachmentNames.length)),
                args) > 0;

        if (!rowsDeleted) {
            Log.w(LOG_TAG, "No attachments were deleted for rev "+rev+" with attachmentNames "+ Arrays.toString(attachmentNames));
        }

        if (rowsDeleted) {
            // return a new rev for the version with attachment removed
            return datastore.updateDocument(rev.getId(), rev.getRevision(), rev.getBody());
        } else {
            // nothing deleted, just return the same rev
            return rev;
        }
    }

    protected void purgeAttachments() {
        // it's easier to deal with Strings since java doesn't know how to compare byte[]s
        Set<String> currentKeys = new HashSet<String>();
        try {
            // get all keys from attachments table
            Cursor c = datastore.getSQLDatabase().rawQuery(SQL_ATTACHMENTS_SELECT_ALL_KEYS, null);
            while (c.moveToNext()) {
                byte[] key = c.getBlob(0);
                currentKeys.add(keyToString(key));
            }
            // iterate thru attachments dir
            for (File f : new File(attachmentsDir).listFiles()) {
                // if file isn't in the keys list, delete it
                String keyForFile = f.getName();
                if (!currentKeys.contains(keyForFile)) {
                    try {
                        boolean deleted = f.delete();
                        if (!deleted) {
                            Log.w(LOG_TAG, "Could not delete file from BLOB store: " + f.getAbsolutePath());
                        }
                    } catch (SecurityException e) {
                        Log.w(LOG_TAG, "SecurityException when trying to delete file from BLOB store: " + f.getAbsolutePath() + ", "+e);
                    }
                }
            }
        } catch (SQLException e) {
            Log.e(LOG_TAG, "Problem in purgeAttachments, executing SQL to fetch all attachment keys "+e);
        }
    }

    private String keyToString(byte[] key) {
        return new String(new Hex().encode(key));
    }

    private File fileFromKey(byte[] key) {
        File file = new File(attachmentsDir, keyToString(key));
        return file;
    }
}

