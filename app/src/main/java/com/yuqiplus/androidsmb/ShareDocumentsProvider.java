package com.yuqiplus.androidsmb;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.os.storage.StorageManager;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;

public final class ShareDocumentsProvider extends DocumentsProvider {
    static final String ROOT = "share";
    private static final String ROOT_DOCUMENT = "share/";
    private static final String INTERNAL = ".smbsrv";
    private static final String[] ROOT_COLUMNS = {
        Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS,
        Root.COLUMN_AVAILABLE_BYTES, Root.COLUMN_ICON
    };
    private static final String[] DOCUMENT_COLUMNS = {
        Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
        Document.COLUMN_FLAGS, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED
    };

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor queryRoots(String[] projection) {
        MatrixCursor cursor = new MatrixCursor(projection == null ? ROOT_COLUMNS : projection);
        cursor.newRow()
            .add(Root.COLUMN_ROOT_ID, ROOT)
            .add(Root.COLUMN_DOCUMENT_ID, ROOT_DOCUMENT)
            .add(Root.COLUMN_TITLE, getContext().getString(R.string.documents_root))
            .add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE | Root.FLAG_SUPPORTS_IS_CHILD)
            .add(Root.COLUMN_AVAILABLE_BYTES, allocatableBytes())
            .add(Root.COLUMN_ICON, R.drawable.ic_share);
        return cursor;
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        MatrixCursor cursor = new MatrixCursor(projection == null ? DOCUMENT_COLUMNS : projection);
        addDocument(cursor, documentId, existing(documentId));
        return cursor;
    }

    @Override
    public Cursor queryChildDocuments(String parentId, String[] projection, String sortOrder)
            throws FileNotFoundException {
        MatrixCursor cursor = new MatrixCursor(projection == null ? DOCUMENT_COLUMNS : projection);
        File[] children = existing(parentId).listFiles(file -> !INTERNAL.equalsIgnoreCase(file.getName())
            && !Files.isSymbolicLink(file.toPath()));
        if (children != null) {
            Arrays.sort(children, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
            for (File child : children) {
                addDocument(cursor, childId(parentId, child.getName()), child);
            }
        }
        return cursor;
    }

    @Override
    public boolean isChildDocument(String parentId, String documentId) {
        try {
            Path parent = existing(parentId).toPath();
            Path child = existing(documentId).toPath();
            return !child.equals(parent) && child.startsWith(parent);
        } catch (FileNotFoundException exception) {
            return false;
        }
    }

    @Override
    public ParcelFileDescriptor openDocument(String documentId, String mode,
            CancellationSignal signal) throws FileNotFoundException {
        File file = existing(documentId);
        if (!file.isFile()) {
            throw new FileNotFoundException(documentId);
        }
        if (signal != null) {
            signal.throwIfCanceled();
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode));
    }

    @Override
    public String createDocument(String parentId, String mimeType, String displayName)
            throws FileNotFoundException {
        boolean folder = Document.MIME_TYPE_DIR.equals(mimeType);
        String extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType);
        if (!folder && extension != null && displayName.lastIndexOf('.') <= 0) {
            displayName += "." + extension;
        }
        String documentId = childId(parentId, validName(displayName));
        Path target = resolve(documentId).toPath();
        try {
            if (folder) {
                Files.createDirectory(target);
            } else {
                Files.createFile(target);
            }
            return documentId;
        } catch (IOException exception) {
            throw missing(documentId, exception);
        }
    }

    @Override
    public String renameDocument(String documentId, String displayName) throws FileNotFoundException {
        ensureNotRoot(documentId);
        String parent = documentId.substring(0, documentId.lastIndexOf('/') + 1);
        String renamed = childId(parent, validName(displayName));
        try {
            Files.move(existing(documentId).toPath(), resolve(renamed).toPath());
            return renamed;
        } catch (IOException exception) {
            throw missing(documentId, exception);
        }
    }

    @Override
    public void deleteDocument(String documentId) throws FileNotFoundException {
        ensureNotRoot(documentId);
        try {
            deleteTree(existing(documentId).toPath());
        } catch (IOException exception) {
            throw missing(documentId, exception);
        }
    }

    private void deleteTree(Path path) throws IOException {
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            try (var children = Files.newDirectoryStream(path)) {
                for (Path child : children) {
                    deleteTree(child);
                }
            }
        }
        Files.delete(path);
    }

    private Long allocatableBytes() {
        StorageManager storage = getContext().getSystemService(StorageManager.class);
        try {
            return storage.getAllocatableBytes(storage.getUuidForPath(directory()));
        } catch (IOException exception) {
            return null;
        }
    }

    private File directory() {
        return Settings.directory(getContext());
    }

    private File resolve(String id) throws FileNotFoundException {
        if (!id.startsWith(ROOT_DOCUMENT)) {
            throw new FileNotFoundException(id);
        }
        try {
            File root = directory().getCanonicalFile();
            File file = new File(root, id.substring(ROOT_DOCUMENT.length()));
            File canonical = file.getCanonicalFile();
            if (!canonical.equals(file) || !canonical.toPath().startsWith(root.toPath())) {
                throw new FileNotFoundException(id);
            }
            for (Path part : root.toPath().relativize(canonical.toPath())) {
                if (INTERNAL.equalsIgnoreCase(part.toString())) {
                    throw new FileNotFoundException(id);
                }
            }
            return canonical;
        } catch (IOException exception) {
            throw missing(id, exception);
        }
    }

    private File existing(String id) throws FileNotFoundException {
        File file = resolve(id);
        if (!file.exists()) {
            throw new FileNotFoundException(id);
        }
        return file;
    }

    private static void ensureNotRoot(String id) throws FileNotFoundException {
        if (ROOT_DOCUMENT.equals(id)) {
            throw new FileNotFoundException(id);
        }
    }

    private static String validName(String name) throws FileNotFoundException {
        if (name.isEmpty() || name.equals(".") || name.equals("..") || INTERNAL.equalsIgnoreCase(name)
                || name.endsWith(".") || name.endsWith(" ")
                || name.matches(".*[\\\\/:*?\"<>|].*")
                || name.codePoints().anyMatch(Character::isISOControl)) {
            throw new FileNotFoundException("无效的文件名");
        }
        return name;
    }

    private static String childId(String parent, String name) {
        return parent.endsWith("/") ? parent + name : parent + "/" + name;
    }

    private static FileNotFoundException missing(String id, IOException cause) {
        FileNotFoundException exception = new FileNotFoundException(id);
        exception.initCause(cause);
        return exception;
    }

    private void addDocument(MatrixCursor cursor, String id, File file) {
        boolean folder = file.isDirectory();
        int flags = folder ? Document.FLAG_DIR_SUPPORTS_CREATE : Document.FLAG_SUPPORTS_WRITE;
        if (!ROOT_DOCUMENT.equals(id)) {
            flags |= Document.FLAG_SUPPORTS_RENAME | Document.FLAG_SUPPORTS_DELETE;
        }
        String name = file.getName();
        String mime = Document.MIME_TYPE_DIR;
        if (!folder) {
            int dot = name.lastIndexOf('.');
            String extension = dot == -1 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
            String known = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
            mime = known == null ? "application/octet-stream" : known;
        }

        cursor.newRow()
            .add(Document.COLUMN_DOCUMENT_ID, id)
            .add(Document.COLUMN_DISPLAY_NAME, name)
            .add(Document.COLUMN_MIME_TYPE, mime)
            .add(Document.COLUMN_FLAGS, flags)
            .add(Document.COLUMN_SIZE, folder ? null : file.length())
            .add(Document.COLUMN_LAST_MODIFIED, file.lastModified());
    }
}
