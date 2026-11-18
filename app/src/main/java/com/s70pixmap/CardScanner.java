package com.s70pixmap;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * 通过 SAF（Storage Access Framework）遍历存储卡上的文件。
 *
 * <p>不依赖 AndroidX 的 DocumentFile，直接使用 DocumentsContract，避免任何
 * 第三方依赖。
 */
final class CardScanner {

    static final class Doc {
        Uri uri;
        String name;
        String mime;
        long size;
        String folder;
    }

    private CardScanner() {
    }

    /** 递归遍历授权目录（最多 3 层、5000 个目录，防止卡里文件太多卡死）。 */
    static List<Doc> scan(Context context, Uri treeUri) {
        List<Doc> out = new ArrayList<>();
        ContentResolver cr = context.getContentResolver();
        String rootId;
        try {
            rootId = DocumentsContract.getTreeDocumentId(treeUri);
        } catch (IllegalArgumentException e) {
            return out;
        }

        ArrayDeque<String[]> queue = new ArrayDeque<>();
        queue.add(new String[]{rootId, ""});
        HashSet<String> visited = new HashSet<>();

        while (!queue.isEmpty() && visited.size() < 5000) {
            String[] cur = queue.poll();
            String parentId = cur[0];
            String folderName = cur[1];
            if (!visited.add(parentId)) continue;

            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId);
            Cursor c = null;
            try {
                c = cr.query(children, new String[]{
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_SIZE
                }, null, null, null);
                if (c == null) continue;
                while (c.moveToNext()) {
                    String id = c.getString(0);
                    String name = c.getString(1);
                    String mime = c.getString(2);
                    long size = c.isNull(3) ? 0L : c.getLong(3);
                    if (id == null) continue;
                    if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                        queue.add(new String[]{id, name == null ? folderName : name});
                    } else {
                        Doc d = new Doc();
                        d.uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id);
                        d.name = name == null ? "" : name;
                        d.mime = mime == null ? "" : mime;
                        d.size = size;
                        d.folder = folderName;
                        out.add(d);
                    }
                }
            } catch (Exception ignored) {
                // 某些 provider 对部分目录会抛异常，跳过即可
            } finally {
                if (c != null) c.close();
            }
        }
        return out;
    }

    static boolean isJpeg(Doc d) {
        String n = d.name.toLowerCase();
        return n.endsWith(".jpg") || n.endsWith(".jpeg");
    }

    /** 文件名以 dark 开头，或位于名为 darks 的文件夹里。 */
    static boolean isDarkFrame(Doc d) {
        if (!isJpeg(d)) return false;
        String n = d.name.toLowerCase();
        if (n.startsWith("dark")) return true;
        return d.folder != null && d.folder.equalsIgnoreCase("darks");
    }

    static Bitmap decode(Context context, Uri docUri) {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        opts.inScaled = false;
        InputStream in = null;
        try {
            in = context.getContentResolver().openInputStream(docUri);
            if (in == null) return null;
            return BitmapFactory.decodeStream(in, null, opts);
        } catch (Exception e) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /** 在树的根目录下找到（或创建）名为 name 的文件夹。 */
    static Uri ensureDir(Context context, Uri treeUri, String name) throws IOException {
        Uri root = DocumentsContract.buildDocumentUriUsingTree(
                treeUri, DocumentsContract.getTreeDocumentId(treeUri));
        Uri existing = findChild(context, treeUri, root, name, true);
        if (existing != null) return existing;
        Uri created = DocumentsContract.createDocument(
                context.getContentResolver(), root, DocumentsContract.Document.MIME_TYPE_DIR, name);
        if (created == null) throw new IOException("无法创建输出文件夹");
        return created;
    }

    /** 在 dir 下创建新文件，重名时自动加序号。 */
    static Uri createFile(Context context, Uri treeUri, Uri dir, String mime, String name)
            throws IOException {
        String candidate = name;
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 0; i < 50; i++) {
            Uri existing = findChild(context, treeUri, dir, candidate, false);
            if (existing == null) {
                Uri created = DocumentsContract.createDocument(
                        context.getContentResolver(), dir, mime, candidate);
                if (created == null) throw new IOException("无法创建文件: " + candidate);
                return created;
            }
            candidate = stem + "_fixed" + (i == 0 ? "" : String.valueOf(i)) + ext;
        }
        throw new IOException("重名文件过多: " + name);
    }

    private static Uri findChild(Context context, Uri treeUri, Uri dir, String name, boolean dirOnly) {
        ContentResolver cr = context.getContentResolver();
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri, DocumentsContract.getDocumentId(dir));
        Cursor c = null;
        try {
            c = cr.query(children, new String[]{
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
            }, null, null, null);
            if (c == null) return null;
            while (c.moveToNext()) {
                String id = c.getString(0);
                String childName = c.getString(1);
                String mime = c.getString(2);
                if (name.equals(childName)) {
                    if (dirOnly && !DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) continue;
                    return DocumentsContract.buildDocumentUriUsingTree(treeUri, id);
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return null;
    }
}
