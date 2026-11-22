package com.photodateusb.organizer;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.media.ExifInterface;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Uses Android's Storage Access Framework (SAF) for both phone and USB.
 *
 * This is NOT a raw /storage/emulated/0 file explorer: Android 11+ requires
 * the user to explicitly select each folder via ACTION_OPEN_DOCUMENT_TREE.
 *
 * Each source photo stays untouched until its USB copy has been verified.
 */
public final class TransferEngine {
    private static final String DIR_MIME = DocumentsContract.Document.MIME_TYPE_DIR;
    private static final String[] FIELDS = {
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        DocumentsContract.Document.COLUMN_FLAGS
    };
    private static final String LOG_PREFIX = "照片移动记录_";
    private final Context app;
    private final ContentResolver resolver;

    public TransferEngine(Context app) {
        this.app = app.getApplicationContext();
        this.resolver = this.app.getContentResolver();
    }

    public interface Progress {
        void show(int done, int total, String status);
    }

    public static final class Options {
        public Uri sourceTree;
        public Uri targetTree;
        public DateRules.Group group = DateRules.Group.MONTH;
        public boolean recursive = true;
        public boolean filenameDate = true;
        public boolean modifiedDate = false;
        public boolean verifyHash = true;

        public Options copy() {
            Options c = new Options();
            c.sourceTree = sourceTree;
            c.targetTree = targetTree;
            c.group = group;
            c.recursive = recursive;
            c.filenameDate = filenameDate;
            c.modifiedDate = modifiedDate;
            c.verifyHash = verifyHash;
            return c;
        }
    }

    public static final class Photo {
        public final Uri sourceUri;
        public final String name;
        public final String sourceRelative;
        public final List<String> destinationFolders;
        public final String dateSource;
        public final LocalDateTime captureDate;
        public final long knownBytes;
        public final long knownModified;

        Photo(Uri uri, String name, String relative, List<String> folders,
              String sourceOfDate, LocalDateTime captured, long bytes, long modified) {
            this.sourceUri = uri;
            this.name = name;
            this.sourceRelative = relative;
            this.destinationFolders = folders;
            this.dateSource = sourceOfDate;
            this.captureDate = captured;
            this.knownBytes = bytes;
            this.knownModified = modified;
        }

        public String destinationPreview() {
            return String.join("/", destinationFolders) + "/" + name;
        }
    }

    public static final class Scan {
        public final List<Photo> photos = new ArrayList<>();
        public int exifCount, filenameCount, modifiedCount, unknownCount;
        public long knownBytes;

        public String summary() {
            return "照片 " + photos.size() + " 张 · EXIF " + exifCount +
                " · 文件名 " + filenameCount + " · 修改时间 " + modifiedCount +
                " · 未识别 " + unknownCount + " · 约 " + bytes(knownBytes);
        }
    }

    public static final class Outcome {
        public int moved, copiedOnly, failed, processed, total;
        public boolean cancelled;
        public String localLog;
        public String usbLog;
        public String logError;
        public String lastError;

        public String summary() {
            return "已移动 " + moved + " 张，已复制但源文件未删除 " + copiedOnly +
                " 张，失败 " + failed + " 张" +
                (cancelled ? "（已停止后续任务）" : "");
        }
    }

    private static final class Folder {
        final String docId;
        final String relative;
        Folder(String id, String relative) {
            this.docId = id;
            this.relative = relative;
        }
    }

    private static final class Entry {
        final String id, name, mime;
        final long size, modified;
        final int flags;
        Entry(String id, String name, String mime, long size, long modified, int flags) {
            this.id = id;
            this.name = name;
            this.mime = mime;
            this.size = size;
            this.modified = modified;
            this.flags = flags;
        }
    }

    private static final class DateGuess {
        LocalDateTime date;
        String from;
        DateGuess(LocalDateTime date, String from) {
            this.date = date;
            this.from = from;
        }
    }

    private static final class FolderIndex {
        final Uri folderUri;
        final Set<String> names = new HashSet<>();
        FolderIndex(Uri folder) { folderUri = folder; }
    }

    /**
     * Disallow identical and nested source/target tree grants.
     * Some nonstandard providers use opaque IDs; in that case also refuse
     * identical URIs, and instruct the user to choose separate volumes.
     */
    public static void assertSafeTrees(Uri source, Uri target) {
        if (source == null || target == null) {
            throw new IllegalArgumentException("请先选择手机来源文件夹和 U 盘目标文件夹。");
        }
        if (source.equals(target)) {
            throw new IllegalArgumentException("来源与目标不能是同一文件夹。");
        }
        if (!DocumentsContract.isTreeUri(source) || !DocumentsContract.isTreeUri(target)) {
            throw new IllegalArgumentException("请选择文件夹，而不是单个文件。");
        }
        if (!source.getAuthority().equals(target.getAuthority())) return;
        String a = DocumentsContract.getTreeDocumentId(source);
        String b = DocumentsContract.getTreeDocumentId(target);
        if (a.equals(b) || isAncestorId(a, b) || isAncestorId(b, a)) {
            throw new IllegalArgumentException("来源与目标不能相同或相互包含，请改选 U 盘上的独立文件夹。");
        }
    }

    private static boolean isAncestorId(String parent, String child) {
        // Standard Android document IDs look like primary:DCIM/Camera,
        // 1234-ABCD:Photos, etc. Opaque IDs intentionally get no guesswork.
        if (parent.endsWith(":")) return child.startsWith(parent);
        return child.startsWith(parent + "/");
    }

    private Uri asDocument(Uri tree, String id) {
        return DocumentsContract.buildDocumentUriUsingTree(tree, id);
    }

    private Uri root(Uri tree) {
        return asDocument(tree, DocumentsContract.getTreeDocumentId(tree));
    }

    private List<Entry> children(Uri tree, String folderDocId) throws IOException {
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, folderDocId);
        List<Entry> out = new ArrayList<>();
        try (Cursor c = resolver.query(childrenUri, FIELDS, null, null, null)) {
            if (c == null) throw new IOException("存储设备没有返回目录内容（可能未授权或已拔出）。");
            int id = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int name = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            int mime = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE);
            int bytes = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE);
            int modified = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED);
            int flags = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_FLAGS);
            while (c.moveToNext()) {
                out.add(new Entry(
                    c.getString(id), c.getString(name), c.getString(mime),
                    c.isNull(bytes) ? -1 : c.getLong(bytes),
                    c.isNull(modified) ? 0 : c.getLong(modified),
                    c.isNull(flags) ? 0 : c.getInt(flags)));
            }
        } catch (SecurityException e) {
            throw new IOException("没有目录访问权限，请重新选择文件夹。", e);
        } catch (RuntimeException e) {
            throw new IOException("读取目录失败：设备可能断开或不支持该操作。", e);
        }
        return out;
    }

    public Scan scan(Options options, AtomicBoolean cancel, Progress listener) throws IOException {
        assertSafeTrees(options.sourceTree, options.targetTree);
        Scan scan = new Scan();
        ArrayDeque<Folder> pending = new ArrayDeque<>();
        pending.add(new Folder(DocumentsContract.getTreeDocumentId(options.sourceTree), ""));
        while (!pending.isEmpty()) {
            ensureActive(cancel);
            Folder current = pending.removeFirst();
            List<Entry> entries = children(options.sourceTree, current.docId);
            for (Entry entry : entries) {
                ensureActive(cancel);
                if (entry.name == null) continue;
                if (DIR_MIME.equals(entry.mime)) {
                    if (options.recursive) {
                        pending.addLast(new Folder(entry.id, current.relative + entry.name + "/"));
                    }
                } else if (DateRules.imageFile(entry.name)) {
                    Uri document = asDocument(options.sourceTree, entry.id);
                    DateGuess guess = guessDate(document, entry, options);
                    List<String> segments = DateRules.folders(guess.date, options.group);
                    Photo photo = new Photo(
                        document, entry.name, current.relative + entry.name,
                        segments, guess.from, guess.date, entry.size, entry.modified
                    );
                    scan.photos.add(photo);
                    if (guess.from.startsWith("EXIF")) scan.exifCount++;
                    else if (guess.from.equals("文件名")) scan.filenameCount++;
                    else if (guess.from.equals("文件修改时间")) scan.modifiedCount++;
                    else scan.unknownCount++;
                    if (entry.size > 0) scan.knownBytes += entry.size;
                    if (scan.photos.size() % 20 == 0) {
                        listener.show(scan.photos.size(), 0, "正在读取拍摄日期，已扫描 " + scan.photos.size() + " 张…");
                    }
                }
            }
        }
        listener.show(scan.photos.size(), scan.photos.size(), "扫描完成：" + scan.summary());
        return scan;
    }

    private DateGuess guessDate(Uri document, Entry entry, Options options) {
        try (InputStream stream = resolver.openInputStream(document)) {
            if (stream != null) {
                // Android API 28+ supports JPEG/HEIF EXIF; never save attributes.
                ExifInterface exif = new ExifInterface(stream);
                String[] tags = {
                    ExifInterface.TAG_DATETIME_ORIGINAL,
                    ExifInterface.TAG_DATETIME_DIGITIZED
                };
                for (int i = 0; i < tags.length; i++) {
                    LocalDateTime date = DateRules.parseExif(exif.getAttribute(tags[i]));
                    if (date != null) {
                        return new DateGuess(date, i == 0 ? "EXIF拍摄时间" : "EXIF数字化时间");
                    }
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // Some provider/format combinations cannot expose EXIF; next fallback.
        }
        if (options.filenameDate) {
            LocalDateTime fromName = DateRules.parseFilename(entry.name);
            if (fromName != null) return new DateGuess(fromName, "文件名");
        }
        if (options.modifiedDate && entry.modified > 0) {
            LocalDateTime dt = Instant.ofEpochMilli(entry.modified)
                .atZone(ZoneId.systemDefault()).toLocalDateTime();
            return new DateGuess(dt, "文件修改时间");
        }
        return new DateGuess(null, "无可信日期");
    }

    private static void ensureActive(AtomicBoolean cancel) {
        if (cancel.get()) throw new CancellationException("用户已请求停止");
    }

    public Outcome move(
            Options options, Scan scan, AtomicBoolean cancel, Progress listener
    ) throws IOException {
        assertSafeTrees(options.sourceTree, options.targetTree);
        if (scan == null || scan.photos.isEmpty()) {
            throw new IllegalArgumentException("没有可以移动的照片，请先扫描。");
        }
        Outcome outcome = new Outcome();
        outcome.total = scan.photos.size();
        File logfile = new File(app.getFilesDir(),
            LOG_PREFIX + System.currentTimeMillis() + ".csv");
        outcome.localLog = logfile.getAbsolutePath();

        Map<String, FolderIndex> indexes = new HashMap<>();
        // Logs written locally and flushed after every file to survive disconnection.
        try (BufferedWriter log = new BufferedWriter(new FileWriter(logfile, false))) {
            log.write("状态,手机来源路径,U盘目标路径,日期,日期来源,错误信息\n");
            log.flush();
            for (Photo photo : scan.photos) {
                if (cancel.get()) {
                    outcome.cancelled = true;
                    break;
                }
                String status, finalName = "", error = "";
                listener.show(outcome.processed, outcome.total, "正在移动：" + photo.name);
                try {
                    String[] result = moveOne(options.targetTree, photo, options.verifyHash, cancel, indexes);
                    status = result[0];
                    finalName = result[1];
                    error = result[2];
                    if (status.equals("已移动")) outcome.moved++;
                    else if (status.equals("已复制，源文件未删除")) outcome.copiedOnly++;
                } catch (CancellationException e) {
                    outcome.cancelled = true;
                    break;
                } catch (Exception e) {
                    status = "失败，源文件未删除";
                    error = e.getClass().getSimpleName() + ": " + e.getMessage();
                    outcome.failed++;
                    outcome.lastError = photo.name + "：" + error;
                }
                outcome.processed++;
                String date = photo.captureDate == null ? "" : DateRules.show(photo.captureDate);
                log.write(csv(status, photo.sourceRelative,
                    finalName.isEmpty() ? photo.destinationPreview() : finalName,
                    date, photo.dateSource, error));
                log.newLine();
                log.flush();
                listener.show(outcome.processed, outcome.total,
                    "已处理 " + outcome.processed + "/" + outcome.total +
                    " · 成功移动 " + outcome.moved + " 张");
            }
        }
        // Export the log to USB; if unavailable, local log remains accessible to a later run.
        try {
            outcome.usbLog = exportLog(options.targetTree, logfile);
        } catch (Exception e) {
            outcome.logError = e.getMessage();
        }
        return outcome;
    }

    private String[] moveOne(Uri targetTree, Photo photo, boolean verify,
                             AtomicBoolean cancel, Map<String, FolderIndex> indexes)
            throws IOException {
        ensureActive(cancel);
        Uri folder = ensureSubfolders(targetTree, photo.destinationFolders);
        FolderIndex index = folderIndex(targetTree, folder, indexes);
        String wantedName = chooseName(index.names, photo.name);
        Uri tempUri = null;
        Uri finalUri = null;
        boolean committed = false;
        long written = 0;
        try {
            // Temporary file stays visibly incomplete if process is killed.
            String originalExt = "";
            int dot = photo.name.lastIndexOf('.');
            if (dot >= 0) originalExt = photo.name.substring(dot);
            String tempName = ".照片整理中_" + UUID.randomUUID().toString().replace("-", "")
                + originalExt;
            tempUri = DocumentsContract.createDocument(
                resolver, folder, DateRules.mimeFor(photo.name), tempName);
            if (tempUri == null) throw new IOException("U 盘无法创建临时文件，请检查写入权限。");

            MessageDigest sourceDigest = sha256Digest();
            byte[] buffer = new byte[1024 * 1024];
            try (InputStream in = new BufferedInputStream(requireInput(photo.sourceUri));
                 OutputStream out = new BufferedOutputStream(requireOutput(tempUri))) {
                while (true) {
                    ensureActive(cancel);
                    int n = in.read(buffer);
                    if (n < 0) break;
                    out.write(buffer, 0, n);
                    written += n;
                    if (verify) sourceDigest.update(buffer, 0, n);
                }
                out.flush();
            }
            ensureActive(cancel);
            if (photo.knownBytes >= 0 && written != photo.knownBytes) {
                throw new IOException("源文件大小发生变化，已取消本张移动。");
            }
            long destSize = getSize(tempUri);
            if (destSize >= 0 && written != destSize) {
                throw new IOException("U 盘复制大小不同，源照片仍然保留。");
            }
            byte[] copiedHash = verify ? sourceDigest.digest() : null;
            // Source might change during copy; compare its current metadata before deletion.
            if (photo.knownBytes >= 0 || photo.knownModified > 0) {
                Entry current = inspect(photo.sourceUri);
                if ((photo.knownBytes >= 0 && current.size != photo.knownBytes)
                    || (photo.knownModified > 0 && current.modified != photo.knownModified)) {
                    throw new IOException("手机源文件已发生变化，已保留源文件。");
                }
            }
            ensureActive(cancel);
            // Re-check names immediately before renaming: never deliberately replace a file.
            refreshIndex(targetTree, folder, index);
            wantedName = chooseName(index.names, photo.name);
            Uri renamed = DocumentsContract.renameDocument(resolver, tempUri, wantedName);
            if (renamed == null) throw new IOException("U 盘不支持重命名临时文件。");
            finalUri = renamed;
            tempUri = null;
            committed = true;
            Entry finalEntry = inspect(finalUri);
            if (finalEntry.name == null || finalEntry.name.startsWith(".照片整理中_")) {
                throw new IOException("复制已完成，但最终文件名未确认，源照片保留。");
            }
            if (finalEntry.size >= 0 && finalEntry.size != written) {
                throw new IOException("最终文件大小异常，源照片保留。");
            }
            if (verify && (
                !Arrays.equals(copiedHash, hashUri(finalUri)) ||
                !Arrays.equals(copiedHash, sourceDigestForCompare(photo, cancel)))) {
                throw new IOException("最终文件或手机原件校验失败，手机原件保留。");
            }

            index.names.add(finalEntry.name.toLowerCase(Locale.ROOT));
            String finalRel = String.join("/", photo.destinationFolders)
                + "/" + finalEntry.name;
            if (cancel.get()) {
                return new String[]{"已复制，源文件未删除", finalRel,
                    "用户请求停止：U 盘已校验完成，手机原件保留。"};
            }
            try {
                // On failure do NOT delete the verified destination copy.
                boolean deleted = DocumentsContract.deleteDocument(resolver, photo.sourceUri);
                if (!deleted) {
                    return new String[]{"已复制，源文件未删除", finalRel,
                        "源文件提供者拒绝删除，请手工核对。"};
                }
                return new String[]{"已移动", finalRel, ""};
            } catch (Exception ex) {
                return new String[]{"已复制，源文件未删除", finalRel,
                    "删除手机原件时出错：" + ex.getMessage()};
            }
        } finally {
            // Only remove incomplete temporary file, NEVER a committed USB backup.
            if (!committed && tempUri != null) {
                try { DocumentsContract.deleteDocument(resolver, tempUri); }
                catch (Exception ignored) { }
            }
        }
    }

    private byte[] sourceDigestForCompare(Photo photo, AtomicBoolean cancel) throws IOException {
        // Re-read source to detect any mutation invisible to file timestamps.
        MessageDigest md = sha256Digest();
        try (InputStream in = new BufferedInputStream(requireInput(photo.sourceUri))) {
            byte[] buffer = new byte[1024 * 1024];
            int n;
            while ((n = in.read(buffer)) >= 0) {
                // Once the USB file is committed, finish its verification even
                // if the user requests stop; this prevents unlogged duplicates.
                md.update(buffer, 0, n);
            }
        }
        return md.digest();
    }

    private static String chooseName(Set<String> used, String original) throws IOException {
        for (int i = 0; i < 100000; i++) {
            String candidate = DateRules.numberFile(original, i);
            if (!used.contains(candidate.toLowerCase(Locale.ROOT))) return candidate;
        }
        throw new IOException("目标文件夹同名文件过多。");
    }

    private FolderIndex folderIndex(Uri tree, Uri folder, Map<String, FolderIndex> map)
            throws IOException {
        String key = folder.toString();
        FolderIndex found = map.get(key);
        if (found == null) {
            found = new FolderIndex(folder);
            refreshIndex(tree, folder, found);
            map.put(key, found);
        }
        return found;
    }

    private void refreshIndex(Uri tree, Uri folder, FolderIndex index) throws IOException {
        String id = DocumentsContract.getDocumentId(folder);
        index.names.clear();
        for (Entry e : children(tree, id)) {
            if (e.name != null) index.names.add(e.name.toLowerCase(Locale.ROOT));
        }
    }

    private Uri ensureSubfolders(Uri tree, List<String> folders) throws IOException {
        Uri current = root(tree);
        for (String part : folders) {
            String id = DocumentsContract.getDocumentId(current);
            Uri found = null;
            for (Entry item : children(tree, id)) {
                if (part.equals(item.name)) {
                    if (!DIR_MIME.equals(item.mime)) {
                        throw new IOException("存在同名文件，不能创建日期目录：" + part);
                    }
                    found = asDocument(tree, item.id);
                    break;
                }
            }
            if (found == null) {
                found = DocumentsContract.createDocument(resolver, current, DIR_MIME, part);
                if (found == null) throw new IOException("U 盘无法创建日期文件夹：" + part);
                Entry created = inspect(found);
                if (!part.equals(created.name)) {
                    throw new IOException("目录创建名称与预期不同：" + created.name);
                }
            }
            current = found;
        }
        return current;
    }

    private Entry inspect(Uri uri) throws IOException {
        try (Cursor c = resolver.query(uri, FIELDS, null, null, null)) {
            if (c == null || !c.moveToFirst()) {
                throw new IOException("目标或源文件无法读取，可能已断开 U 盘。");
            }
            return new Entry(
                c.getString(c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)),
                c.getString(c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)),
                c.getString(c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)),
                c.isNull(c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE))
                    ? -1 : c.getLong(c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)),
                c.isNull(c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED))
                    ? 0 : c.getLong(c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)),
                c.getInt(c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_FLAGS))
            );
        } catch (RuntimeException e) {
            throw new IOException("读取文件属性失败，存储设备可能断开。", e);
        }
    }

    private long getSize(Uri uri) throws IOException { return inspect(uri).size; }

    private InputStream requireInput(Uri uri) throws IOException {
        InputStream s = resolver.openInputStream(uri);
        if (s == null) throw new IOException("无法读取源文件：" + uri);
        return s;
    }

    private OutputStream requireOutput(Uri uri) throws IOException {
        OutputStream s = resolver.openOutputStream(uri, "w");
        if (s == null) throw new IOException("U 盘拒绝写入。");
        return s;
    }

    private byte[] hashUri(Uri uri) throws IOException {
        MessageDigest digest = sha256Digest();
        try (InputStream in = new BufferedInputStream(requireInput(uri))) {
            byte[] block = new byte[1024 * 1024];
            int n;
            while ((n = in.read(block)) != -1) digest.update(block, 0, n);
        }
        return digest.digest();
    }

    private MessageDigest sha256Digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private String exportLog(Uri tree, File logfile) throws IOException {
        Uri root = root(tree);
        String filename = LOG_PREFIX + System.currentTimeMillis() + ".csv";
        Uri logUri = DocumentsContract.createDocument(resolver, root, "text/csv", filename);
        if (logUri == null) throw new IOException("无法将日志复制到 U 盘。");
        boolean done = false;
        try (InputStream in = new java.io.FileInputStream(logfile);
             OutputStream out = requireOutput(logUri)) {
            byte[] block = new byte[8192];
            int n;
            while ((n = in.read(block)) != -1) out.write(block, 0, n);
            out.flush();
            done = true;
        } finally {
            if (!done) {
                try { DocumentsContract.deleteDocument(resolver, logUri); }
                catch (Exception ignored) { }
            }
        }
        return filename;
    }

    private static String csv(String... fields) {
        List<String> out = new ArrayList<>(fields.length);
        for (String value : fields) {
            if (value == null) value = "";
            out.add("\"" + value.replace("\"", "\"\"") + "\"");
        }
        return String.join(",", out);
    }

    public static String bytes(long num) {
        if (num < 1024) return num + " B";
        double scaled = num;
        String[] suffixes = {"B", "KB", "MB", "GB", "TB"};
        int i = 0;
        while (scaled >= 1024 && i < suffixes.length - 1) {
            scaled /= 1024.0;
            i++;
        }
        return String.format(Locale.CHINA, "%.1f %s", scaled, suffixes[i]);
    }
}
