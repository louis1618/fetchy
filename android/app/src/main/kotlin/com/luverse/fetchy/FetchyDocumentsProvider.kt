package com.luverse.fetchy

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract as DC
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException

/** Exposes only Fetchy's automatic media directory through the existing SAF storage pipeline. */
class FetchyDocumentsProvider : DocumentsProvider() {
    companion object {
        const val AUTHORITY = "com.luverse.fetchy.documents"
        private const val ROOT = "fetchy"
        fun treeUri(): Uri = DC.buildTreeDocumentUri(AUTHORITY, ROOT)

        @Suppress("DEPRECATION")
        fun rootDirectory(context: Context): File {
            val media = context.externalMediaDirs.firstOrNull()
                ?: throw FileNotFoundException("기기 저장소를 사용할 수 없습니다.")
            return File(media, "Fetchy").canonicalFile.also {
                if (!it.isDirectory && !it.mkdirs()) throw FileNotFoundException("Fetchy 저장 폴더를 만들 수 없습니다.")
            }
        }

        internal fun resolve(root: File, documentId: String): File {
            require(documentId == ROOT || documentId.startsWith("$ROOT/")) { "Unknown document root" }
            val relative = documentId.removePrefix(ROOT).removePrefix("/")
            require(documentId == ROOT || relative.isNotEmpty()) { "Invalid document ID" }
            if (relative.isNotEmpty()) relative.split('/').forEach { StorageNames.validate(it) }
            val file = if (relative.isEmpty()) root.canonicalFile else File(root, relative).canonicalFile
            require(file == root.canonicalFile || file.path.startsWith(root.canonicalPath + File.separator)) { "Document outside Fetchy" }
            return file
        }
    }

    private val root get() = rootDirectory(context!!)
    private fun file(id: String): File = resolve(root, id).also {
        if (!it.exists()) throw FileNotFoundException("Document missing")
    }
    private fun id(file: File): String = file.canonicalFile.relativeTo(root).path.let {
        if (it.isEmpty()) ROOT else "$ROOT/$it"
    }
    private fun mutable(id: String): File = file(id).also { require(it != root) { "Cannot change Fetchy root" } }
    private fun mime(file: File): String = if (file.isDirectory) DC.Document.MIME_TYPE_DIR
        else MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"
    private fun changed(parent: File) {
        context!!.contentResolver.notifyChange(DC.buildChildDocumentsUri(AUTHORITY, id(parent)), null)
    }

    override fun onCreate() = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val columns = projection ?: arrayOf(DC.Root.COLUMN_ROOT_ID, DC.Root.COLUMN_DOCUMENT_ID,
            DC.Root.COLUMN_TITLE, DC.Root.COLUMN_FLAGS, DC.Root.COLUMN_MIME_TYPES, DC.Root.COLUMN_AVAILABLE_BYTES)
        return MatrixCursor(columns).apply {
            val values = mapOf(DC.Root.COLUMN_ROOT_ID to ROOT, DC.Root.COLUMN_DOCUMENT_ID to ROOT,
                DC.Root.COLUMN_TITLE to "Fetchy", DC.Root.COLUMN_FLAGS to (DC.Root.FLAG_LOCAL_ONLY or DC.Root.FLAG_SUPPORTS_CREATE or DC.Root.FLAG_SUPPORTS_IS_CHILD),
                DC.Root.COLUMN_MIME_TYPES to "*/*", DC.Root.COLUMN_AVAILABLE_BYTES to root.usableSpace)
            addRow(columns.map { values[it] }.toTypedArray())
        }
    }

    private fun cursor(projection: Array<out String>?) = MatrixCursor(projection ?: arrayOf(
        DC.Document.COLUMN_DOCUMENT_ID, DC.Document.COLUMN_DISPLAY_NAME, DC.Document.COLUMN_MIME_TYPE,
        DC.Document.COLUMN_FLAGS, DC.Document.COLUMN_SIZE, DC.Document.COLUMN_LAST_MODIFIED))
    private fun add(cursor: MatrixCursor, file: File) {
        val documentId = id(file)
        val flags = (if (file.isDirectory) DC.Document.FLAG_DIR_SUPPORTS_CREATE else DC.Document.FLAG_SUPPORTS_WRITE) or
            (if (documentId == ROOT) 0 else DC.Document.FLAG_SUPPORTS_DELETE or DC.Document.FLAG_SUPPORTS_RENAME or DC.Document.FLAG_SUPPORTS_MOVE)
        val values = mapOf(DC.Document.COLUMN_DOCUMENT_ID to documentId, DC.Document.COLUMN_DISPLAY_NAME to file.name,
            DC.Document.COLUMN_MIME_TYPE to mime(file), DC.Document.COLUMN_FLAGS to flags,
            DC.Document.COLUMN_SIZE to if (file.isFile) file.length() else null, DC.Document.COLUMN_LAST_MODIFIED to file.lastModified())
        cursor.addRow(cursor.columnNames.map { values[it] }.toTypedArray())
    }
    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor = cursor(projection).apply { add(this, file(documentId)) }
    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor = cursor(projection).apply {
        val parent = file(parentDocumentId)
        val children = parent.listFiles() ?: throw FileNotFoundException("Cannot enumerate folder")
        children.sortedBy { it.name }.forEach { add(this, file(id(it))) }
        setNotificationUri(context!!.contentResolver, DC.buildChildDocumentsUri(AUTHORITY, parentDocumentId))
    }
    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val parent = resolve(root, parentDocumentId)
        val child = resolve(root, documentId)
        return child.path.startsWith(parent.path + File.separator)
    }
    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        signal?.throwIfCanceled()
        val target = file(documentId)
        if (!target.isFile) throw FileNotFoundException("Not a file")
        return ParcelFileDescriptor.open(target, ParcelFileDescriptor.parseMode(mode))
    }
    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        StorageNames.validate(displayName)
        val parent = file(parentDocumentId)
        require(parent.isDirectory) { "Not a directory" }
        val target = resolve(root, "$parentDocumentId/$displayName")
        check(!target.exists()) { "Name already exists" }
        check(if (mimeType == DC.Document.MIME_TYPE_DIR) target.mkdir() else target.createNewFile()) { "Creation failed" }
        changed(parent); return id(target)
    }
    override fun renameDocument(documentId: String, displayName: String): String {
        StorageNames.validate(displayName)
        val source = mutable(documentId)
        val target = resolve(root, "${id(source.parentFile!!)}/$displayName")
        if (source == target) return documentId
        check(!target.exists() && source.renameTo(target)) { "Rename failed" }
        changed(target.parentFile!!); return id(target)
    }
    override fun moveDocument(sourceDocumentId: String, sourceParentDocumentId: String, targetParentDocumentId: String): String {
        val source = mutable(sourceDocumentId)
        val previous = file(sourceParentDocumentId)
        val parent = file(targetParentDocumentId)
        require(source.parentFile == previous && parent.isDirectory) { "Invalid parent" }
        require(parent != source && !parent.path.startsWith(source.path + File.separator)) { "Cannot move into descendant" }
        val target = resolve(root, "$targetParentDocumentId/${source.name}")
        if (source == target) return sourceDocumentId
        check(!target.exists() && source.renameTo(target)) { "Move failed" }
        changed(previous); changed(parent); return id(target)
    }
    override fun deleteDocument(documentId: String) {
        val target = mutable(documentId)
        // Storage preserves directory contents first; never recursively erase unknown files here.
        check(target.delete()) { "Deletion failed; folder may not be empty" }
        changed(target.parentFile!!)
    }
}
