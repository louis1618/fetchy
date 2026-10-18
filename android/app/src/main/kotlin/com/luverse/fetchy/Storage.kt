package com.luverse.fetchy

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract as DC
import android.provider.MediaStore
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * Synchronous SAF operations: run on IO and call recover() before accepting mutations.
 * Records retain their original rootUri; root selection never releases earlier grants.
 * files.id is the publishing job ID. folderOps is internal durable recovery state.
 * recover() updates missing flags; unavailable providers keep their records and journals.
 */
class Storage(private val context: Context, private val store: Store) {
    private val resolver = context.contentResolver
    private val platforms = listOf("YouTube", "Instagram", "TikTok", "X", "Other")
    private val knownPlatforms = platforms + mediaPlatformLabels().map { if (it == "X (Twitter)") "X" else it }
    private val directory = DC.Document.MIME_TYPE_DIR
    // ponytail: process-wide serialization; use per-root locks if throughput requires it.
    companion object { private val lock = Any() }
    private data class Doc(val uri: Uri, val name: String, val mime: String, val flags: Long)
    private fun id() = UUID.randomUUID().toString()
    private fun uri(record: JSONObject) = Uri.parse(record.getString("uri"))
    private fun root(): Uri {
        ensureRoot()
        val value = store.settings().getString("rootUri")
        val tree = Uri.parse(value)
        return DC.buildDocumentUriUsingTree(tree, DC.getTreeDocumentId(tree))
    }
    private fun rootOf(record: JSONObject): Uri = Uri.parse(record.getString("rootUri")).let {
        DC.buildDocumentUriUsingTree(it, DC.getTreeDocumentId(it))
    }
    private fun read(uri: Uri): Doc? = try { resolver.query(uri, arrayOf(DC.Document.COLUMN_DISPLAY_NAME,
        DC.Document.COLUMN_MIME_TYPE, DC.Document.COLUMN_FLAGS), null, null, null)?.use {
        if (it.moveToFirst()) Doc(uri, it.getString(0), it.getString(1), it.getLong(2)) else null
    } } catch (_: java.io.FileNotFoundException) { null }
    private fun children(parent: Uri): List<Doc> {
        val query = DC.buildChildDocumentsUriUsingTree(parent, DC.getDocumentId(parent))
        return (resolver.query(query, arrayOf(DC.Document.COLUMN_DOCUMENT_ID, DC.Document.COLUMN_DISPLAY_NAME,
            DC.Document.COLUMN_MIME_TYPE, DC.Document.COLUMN_FLAGS), null, null, null)
            ?: error("Cannot enumerate folder")).use { cursor ->
            buildList { while (cursor.moveToNext()) add(Doc(DC.buildDocumentUriUsingTree(parent,
                cursor.getString(0)), cursor.getString(1), cursor.getString(2), cursor.getLong(3))) }
        }
    }
    private fun exists(uri: Uri): Boolean = try { read(uri) != null } catch (_: java.io.FileNotFoundException) { false }
    private fun valid(name: String) = StorageNames.validate(name)
    private fun unique(parent: Uri, name: String, except: Uri? = null): String {
        val names = children(parent).filter { it.uri != except }.map { it.name }.toSet()
        if (name !in names) return name
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        var n = 1
        var candidate: String
        do { candidate = "${name.take(dot)} (${n++})${name.drop(dot)}" } while (candidate in names)
        return candidate
    }
    private fun folder(id: String) = store.get("folders", id) ?: error("Folder not found")
    private fun file(id: String) = store.get("files", id) ?: error("File not found")
    private fun parent(file: JSONObject): Uri = file.optString("folderId")
        .takeIf { !file.isNull("folderId") && it.isNotEmpty() }
        ?.let { uri(folder(it)) } ?: rootOf(file)
    private fun jobState(job: JSONObject) = job.optString("state", job.optString("status"))
    private fun folderParent(record: JSONObject): Uri = record.optString("parentId")
        .takeIf { !record.isNull("parentId") && it.isNotEmpty() }?.let { uri(folder(it)) } ?: rootOf(record)
    private fun descendants(id: String): Set<String> {
        val result = mutableSetOf(id)
        do { val before = result.size
            store.list("folders").filter { it.optString("parentId") in result }.forEach { result.add(it.getString("id")) }
        } while (result.size != before)
        return result
    }
    private fun idle(folderId: String) {
        check(store.list("jobs").none { job ->
            !job.isNull("folderId") && job.optString("folderId") in descendants(folderId) &&
                jobState(job) !in listOf("completed", "complete", "failed", "cancelled", "canceled", "done", "paused", "stopped")
        }) { "Folder is used by an active job" }
    }

    fun ensureRoot() = synchronized(lock) {
        if (store.settings().optString("rootUri").isEmpty()) chooseRoot(FetchyDocumentsProvider.treeUri())
    }

    private fun approvedRoot(uri: Uri): Uri {
        require(uri.scheme == "content" && DC.isTreeUri(uri)) { "Invalid storage tree" }
        if (uri.authority != FetchyDocumentsProvider.AUTHORITY)
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val document = DC.buildDocumentUriUsingTree(uri, DC.getTreeDocumentId(uri))
        check(read(document)?.mime == directory) { "Not a directory" }
        return document
    }
    fun chooseRoot(uri: Uri) = synchronized(lock) {
        check(migration() == null) { "Storage migration is pending" }
        val document = approvedRoot(uri)
        store.setting("rootUri", uri.toString())
        store.setting("rootName", read(document)!!.name)
        // Root-specific tombstones survive re-selection; deleted mappings stay disabled.
        platforms.forEach { platform ->
            if (!store.settings().optBoolean("disabled:$uri:$platform") && store.list("folders").none {
                it.optString("mappingRootUri", it.optString("rootUri")) == uri.toString() && it.optString("platform") == platform && it.optBoolean("mappingEnabled", true)
            }) createFolder(platform, platform)
        }
    }

    fun createFolder(name: String, platform: String? = null, parentId: String? = null, color: Long = 0xff0a6bffL): JSONObject = synchronized(lock) {
        valid(name); require(color in 0..0xffffffffL) { "Invalid folder color" }
        val parentRecord = parentId?.takeIf { it.isNotBlank() }?.let { folder(it).also { idle(parentId); check(!it.optBoolean("deleting")) } }
        val parent = parentRecord?.let { uri(it) } ?: root()
        val existing = children(parent).firstOrNull { it.name == name }
        check(existing == null || (platform != null && existing.mime == directory)) { "Name already exists" }
        val doc = existing?.uri ?: DC.createDocument(resolver, parent, directory, name) ?: error("Folder creation failed")
        store.list("folders").firstOrNull { it.optString("uri") == doc.toString() } ?: JSONObject().apply {
            put("id", id()); put("name", read(doc)?.name ?: name); put("uri", doc.toString())
            put("rootUri", parentRecord?.getString("rootUri") ?: store.settings().getString("rootUri")); put("platform", platform ?: JSONObject.NULL)
            put("parentId", parentId ?: JSONObject.NULL); put("color", if (platform != null) when(platform) { "YouTube" -> 0xffff343fL; "Instagram" -> 0xffec49b4L; "TikTok" -> 0xff40cdb7L; else -> color } else color); put("favorite", false)
            put("mappingEnabled", true); if (platform != null) put("mappingRootUri", store.settings().getString("rootUri")); store.put("folders", this)
        }
    }

    fun folderFor(platform: String, explicitId: String? = null): JSONObject? = synchronized(lock) {
        ensureRoot()
        if (!explicitId.isNullOrEmpty()) return@synchronized folder(explicitId).also {
            check(!it.optBoolean("deleting") && exists(uri(it))) { "Folder unavailable" }
        }
        if (!store.settings().optBoolean("platformFolders", true)) return@synchronized null
        val label = if (platform.equals("X (Twitter)", true)) "X" else platform
        val canonical = knownPlatforms.firstOrNull { it.equals(label, true) } ?: "Other"
        val rootUri = store.settings().optString("rootUri")
        if (rootUri.isEmpty()) return@synchronized null
        if (store.settings().optBoolean("disabled:$rootUri:$canonical")) return@synchronized null
        store.list("folders").firstOrNull { it.optString("mappingRootUri", it.optString("rootUri")) == rootUri && it.optString("platform") == canonical && it.optBoolean("mappingEnabled", true) }
            ?.also { check(!it.optBoolean("deleting") && exists(uri(it))) { "Platform folder unavailable" } }
            ?: createFolder(canonical, canonical)
    }

    fun renameFolder(id: String, name: String) = synchronized(lock) {
        valid(name); idle(id)
        val record = folder(id)
        check(!record.optBoolean("deleting")) { "Folder deletion pending" }
        rename(record, "folders", folderParent(record), name)
    }

    fun deleteFolder(id: String) = synchronized(lock) {
        idle(id)
        val record = folder(id)
        record.put("deleting", true); store.put("folders", record)
        if (record.optString("platform").isNotEmpty() && !record.isNull("platform"))
            store.setting("disabled:${record.optString("mappingRootUri", record.getString("rootUri"))}:${record.getString("platform")}", true)
        val op = JSONObject().put("id", "delete-folder:$id").put("type", "deleteFolder").put("folderId", id)
        store.put("folderOps", op)
        finishDeleteFolder(op)
    }
    private fun finishDeleteFolder(op: JSONObject) {
        val folderId = op.getString("folderId")
        val record = store.get("folders", folderId) ?: return store.delete("folderOps", op.getString("id"))
        // Repeated during recovery so a crash cannot leave resumable jobs pointing at a deleted folder.
        store.list("jobs").filter { !it.isNull("folderId") && it.optString("folderId") == folderId &&
            jobState(it) in listOf("paused", "stopped", "failed", "canceled", "cancelled") }.forEach { job ->
            job.put("folderId", record.optString("parentId").takeIf { !record.isNull("parentId") } ?: "").put("rootUri", record.getString("rootUri"))
            store.put("jobs", job)
        }
        val source = uri(record)
        if (existsForDeletion(record, "parentId")) {
            // Enumerate provider contents, including files and nested folders unknown to the database.
            children(source).forEach { doc ->
                val tracked = (store.list("files") + store.list("folders")).firstOrNull { it.optString("uri") == doc.uri.toString() }
                transfer(doc.uri, source, folderParent(record), tracked,
                    record.optString("parentId").takeIf { !record.isNull("parentId") && it.isNotBlank() }, record.getString("rootUri"))
            }
            check(children(source).isEmpty()) { "Folder changed during deletion; retry" }
            check(DC.deleteDocument(resolver, source)) { "Folder deletion failed" }
        } else {
            store.list("files").filter { it.optString("folderId") == folderId }.forEach {
                it.put("folderId", record.opt("parentId")).put("missing", true); store.put("files", it)
            }
            store.list("folders").filter { it.optString("parentId") == folderId }.forEach {
                it.put("parentId", record.opt("parentId")).put("missing", true); store.put("folders", it)
            }
        }
        store.delete("folders", folderId); store.delete("folderOps", op.getString("id"))
    }

    fun publish(jobId: String, source: File, name: String, mime: String, folderId: String?, rootUri: String? = null, metadata: JSONObject? = null): JSONObject = synchronized(lock) {
        ensureRoot()
        store.get("files", jobId)?.let { if (existsForDeletion(it)) return@synchronized it }
        store.get("folderOps", "publish:$jobId")?.let { finishPublish(it); return@synchronized file(jobId) }
        valid(name); require(source.isFile) { "Download source missing" }
        require(jobId.isNotBlank() && mime.isNotBlank() && mime != directory) { "Invalid publish request" }
        val targetFolder = folderId?.takeIf { it.isNotEmpty() }?.let { folder(it) }
        check(targetFolder?.optBoolean("deleting") != true) { "Folder deletion pending" }
        val publishRoot = targetFolder?.getString("rootUri")
            ?: rootUri
            ?: store.get("jobs", jobId)?.optString("rootUri")?.takeIf { it.isNotEmpty() }
            ?: store.settings().getString("rootUri")
        val parent = targetFolder?.let { uri(it) } ?: Uri.parse(publishRoot).let {
            DC.buildDocumentUriUsingTree(it, DC.getTreeDocumentId(it))
        }
        val existing = children(parent).firstOrNull { it.name == name }
        val policy = store.get("jobs", jobId)?.optJSONObject("settings")?.optString("duplicates", "rename")
            ?: store.settings().optString("duplicates", "rename")
        if (existing != null && policy == "skip") {
            check(existing.mime != directory) { "Destination is a folder" }
            return@synchronized publicationMetadata(recordFile(jobId, existing.uri, existing.name, existing.mime, folderId,
                publishRoot), metadata, applyDate = false)
        }
        check(existing == null || policy != "replace" || existing.mime != directory) { "Cannot replace a directory" }
        val job = store.get("jobs", jobId)
        val sourceDate = job?.optJSONObject("asset")?.optLong("sourceTimestamp")?.takeIf { it > 0 }
        val publication = JSONObject(metadata?.toString() ?: "{}")
        if (publication.optLong("mediaDate") <= 0) publication.put("mediaDate", job?.optLong("mediaDate")?.takeIf { it > 0 }
            ?: selectedMediaDate((job?.optJSONObject("settings") ?: store.settings()).optString("fileDate", "download"), sourceDate, System.currentTimeMillis()))
        sourceDate?.let { publication.put("sourceTimestamp", it) }
        val op = JSONObject().put("id", "publish:$jobId").put("type", "publish").put("fileId", jobId)
            .put("source", source.absolutePath).put("parent", parent.toString()).put("name", name).put("mime", mime)
            .put("folderId", folderId ?: JSONObject.NULL).put("rootUri", publishRoot)
            .put("replace", if (policy == "replace") existing?.uri?.toString() ?: "" else "")
            .put("tempName", ".fetchy-${id()}")
        op.put("metadata", publication)
        store.put("folderOps", op); finishPublish(op); file(jobId)
    }
    private fun finishPublish(op: JSONObject) {
        val parent = Uri.parse(op.getString("parent"))
        val target = staged(op, parent, op.getString("mime"))
        if (!op.optBoolean("verified")) {
            val source = File(op.getString("source"))
            source.inputStream().use { input -> resolver.openOutputStream(target, "wt")!!.use { input.copyTo(it) } }
            check(source.inputStream().use { digest(it) } == resolver.openInputStream(target)!!.use { digest(it) }) { "Publish verification failed" }
            op.put("verified", true); store.put("folderOps", op)
        }
        val result = finalizeTarget(op, parent, target)
        val record = recordFile(op.getString("fileId"), result, read(result)!!.name, op.getString("mime"),
            op.optString("folderId").takeIf { !op.isNull("folderId") && it.isNotEmpty() }, op.getString("rootUri"))
        publicationMetadata(record, op.optJSONObject("metadata"))
        store.delete("folderOps", op.getString("id"))
    }
    private fun publicationMetadata(record: JSONObject, metadata: JSONObject?, applyDate: Boolean = true): JSONObject {
        metadata?.let {
            for (key in listOf("subtitleFor", "language", "automatic")) if (it.has(key)) record.put(key, it.get(key))
            if (applyDate && it.optLong("mediaDate") > 0) {
                val date = it.getLong("mediaDate")
                record.put("mediaDate", date)
                if (it.optLong("sourceTimestamp") > 0) record.put("sourceTimestamp", it.getLong("sourceTimestamp"))
                val applied = runCatching {
                    resolver.openFileDescriptor(uri(record), "rw")?.use { fd -> File("/proc/self/fd/${fd.fd}").setLastModified(date) } ?: false
                }.getOrDefault(false)
                record.put("dateApplied", applied)
            }
        }
        store.put("files", record)
        runCatching { scan(uri(record), record.getString("mime"), record.getString("id")) }
        return record
    }
    private fun recordFile(id: String, document: Uri, name: String, mime: String, folderId: String?, rootUri: String): JSONObject {
        var modified = 0L
        val size = resolver.query(document, arrayOf(DC.Document.COLUMN_SIZE, DC.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use {
            val index = it.getColumnIndex(DC.Document.COLUMN_LAST_MODIFIED)
            if (it.moveToFirst()) { if (index >= 0 && !it.isNull(index)) modified = it.getLong(index); if (!it.isNull(0)) it.getLong(0) else -1L } else -1L
        } ?: -1L
        return (store.get("files", id) ?: JSONObject()).put("id", id).put("uri", document.toString()).put("name", name).put("mime", mime)
            .put("folderId", folderId ?: JSONObject.NULL).put("rootUri", rootUri).put("size", size).put("missing", false).also {
                it.remove("previewPath"); it.remove("metadataRead"); it.remove("mediaUri")
                if (Build.VERSION.SDK_INT >= 29) runCatching { MediaStore.getMediaUri(context, document) }.getOrNull()?.let { media -> it.put("mediaUri", media.toString()) }
                store.get("jobs", id)?.let { job ->
                    for (key in listOf("title", "platform", "sourceUrl", "createdAt")) if (!it.has(key)) it.put(key, job.opt(key))
                    job.optJSONObject("mediaMetadata")?.let { metadata -> metadata.keys().forEach { key -> it.put(key, metadata.opt(key)) } }
                    job.optJSONObject("asset")?.let { asset -> for (key in listOf("thumbnail", "duration", "width", "height", "fps")) if (asset.has(key) && !it.has(key)) it.put(key, asset.opt(key)) }
                }
                if (!it.has("mediaDate") && modified > 0) it.put("mediaDate", modified)
                store.put("files", it)
            }
    }
    private fun scan(document: Uri, mime: String, fileId: String) {
        val path = if (document.authority == FetchyDocumentsProvider.AUTHORITY)
            FetchyDocumentsProvider.resolve(FetchyDocumentsProvider.rootDirectory(context), DC.getDocumentId(document))
        else {
            if (document.authority != "com.android.externalstorage.documents") return
            val docId = DC.getDocumentId(document)
            if (!docId.startsWith("primary:")) return
            val base = Environment.getExternalStorageDirectory().canonicalFile
            File(base, docId.substringAfter(':')).canonicalFile.takeIf { it.path.startsWith(base.path + File.separator) } ?: return
        }
        MediaScannerConnection.scanFile(context, arrayOf(path.path), arrayOf(mime)) { _, mediaUri ->
            if (mediaUri != null) synchronized(lock) {
                store.get("files", fileId)?.takeIf { it.optString("uri") == document.toString() }?.let {
                    if ((mime.startsWith("image/") || mime.startsWith("video/")) && it.optLong("mediaDate") > 0) {
                        val applied = runCatching { resolver.update(mediaUri, android.content.ContentValues().apply {
                            put(MediaStore.Images.Media.DATE_TAKEN, it.getLong("mediaDate"))
                        }, null, null) > 0 }.getOrDefault(false)
                        it.put("galleryDateApplied", applied)
                    }
                    it.put("mediaUri", mediaUri.toString()); store.put("files", it)
                }
            }
        }
    }

    private fun digest(input: InputStream): String {
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) { check(!Thread.currentThread().isInterrupted) { "Storage migration interrupted" }; val count = input.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }
    private fun staged(op: JSONObject, parent: Uri, mime: String): Uri {
        if (op.optString("target").isNotEmpty()) return Uri.parse(op.getString("target"))
        val target = children(parent).firstOrNull { it.name == op.getString("tempName") }?.uri
            ?: DC.createDocument(resolver, parent, mime, op.getString("tempName")) ?: error("Creation failed")
        op.put("target", target.toString()); store.put("folderOps", op); return target
    }
    private fun finalizeTarget(op: JSONObject, parent: Uri, target: Uri): Uri {
        if (op.optBoolean("finalized")) return Uri.parse(op.getString("target"))
        val replace = op.optString("replace")
        if (replace.isNotEmpty()) {
            val old = Uri.parse(replace)
            if (exists(old)) check(DC.deleteDocument(resolver, old)) { "Replacement failed" }
            store.list("files").filter { it.optString("uri") == replace }.forEach { store.delete("files", it.getString("id")) }
        }
        val name = op.optString("finalName").takeIf { it.isNotEmpty() } ?: unique(parent, op.getString("name"), target)
        op.put("finalName", name); store.put("folderOps", op)
        // Discover a completed rename if the provider changed the URI before the journal committed.
        val result = if (!exists(target)) children(parent).firstOrNull { it.name == name }?.uri
            ?: error("Renamed document unavailable") else if (read(target)?.name == name) target
            else DC.renameDocument(resolver, target, name) ?: error("Rename failed")
        op.put("target", result.toString()).put("finalized", true); store.put("folderOps", op); return result
    }

    private fun transfer(source: Uri, sourceParent: Uri, targetParent: Uri, record: JSONObject?, folderId: String?, rootUri: String) {
        val opId = "move:$source"
        store.get("folderOps", opId)?.let { finishMove(it); return }
        val doc = read(source) ?: error("Source missing")
        val op = JSONObject().put("id", opId).put("type", "move").put("source", source.toString())
            .put("sourceParent", sourceParent.toString()).put("parent", targetParent.toString()).put("mime", doc.mime)
            .put("name", doc.name).put("tempName", ".fetchy-${id()}").put("rootUri", rootUri)
            .put("folderId", folderId ?: JSONObject.NULL).put("record", record ?: JSONObject.NULL)
        store.put("folderOps", op); finishMove(op)
    }
    private fun copyTree(source: Uri, target: Uri) {
        val doc = read(source) ?: error("Source missing")
        if (doc.mime == directory) {
            children(source).forEach { child ->
                val dest = children(target).firstOrNull { it.name == child.name }?.also { check(it.mime == child.mime) }?.uri
                    ?: DC.createDocument(resolver, target, child.mime, child.name) ?: error("Copy creation failed")
                copyTree(child.uri, dest)
            }
        } else {
            resolver.openInputStream(source)!!.use { input -> resolver.openOutputStream(target, "wt")!!.use { output ->
                val buffer = ByteArray(65536)
                while (true) { check(!Thread.currentThread().isInterrupted) { "Storage migration interrupted" }; val count = input.read(buffer); if (count < 0) break; output.write(buffer, 0, count) }
            } }
            check(resolver.openInputStream(source)!!.use { digest(it) } == resolver.openInputStream(target)!!.use { digest(it) }) { "Copy verification failed" }
        }
    }
    private fun fingerprint(document: Uri): String {
        val doc = read(document) ?: error("Document missing during verification")
        if (doc.mime != directory) return resolver.openInputStream(document)!!.use { digest(it) }
        return children(document).sortedBy { it.name }.joinToString("\n") {
            "${it.name.length}:${it.name}:${it.mime}:${fingerprint(it.uri)}"
        }
    }
    private fun finishMove(op: JSONObject) {
        val source = Uri.parse(op.getString("source")); val parent = Uri.parse(op.getString("parent"))
        var target = op.optString("target").takeIf { it.isNotEmpty() }?.let { Uri.parse(it) }
        if (!op.optBoolean("verified")) {
            val doc = read(source)
            // Resolve a completed native move before issuing another provider mutation.
            val moved = children(parent).firstOrNull { DC.getDocumentId(it.uri) == DC.getDocumentId(source) }
                ?: if (doc == null && op.optBoolean("nativeAttempt")) children(parent).firstOrNull {
                    it.name == op.getString("name") && it.mime == op.getString("mime")
                } else null
            if (moved != null) target = moved.uri
            else if (doc != null && Build.VERSION.SDK_INT >= 24 && source.authority == parent.authority &&
                doc.flags and DC.Document.FLAG_SUPPORTS_MOVE.toLong() != 0L && children(parent).none { it.name == doc.name }) {
                op.put("nativeAttempt", true); store.put("folderOps", op)
                target = try { DC.moveDocument(resolver, source, Uri.parse(op.getString("sourceParent")), parent)?.let { DC.buildDocumentUriUsingTree(parent, DC.getDocumentId(it)) } }
                    catch (_: UnsupportedOperationException) { null }
                if (target == null) {
                    target = children(parent).firstOrNull { DC.getDocumentId(it.uri) == DC.getDocumentId(source) }?.uri
                        ?: staged(op, parent, op.getString("mime")).also { copyTree(source, it) }
                }
            } else {
                check(doc != null) { "Source disappeared" }
                target = staged(op, parent, op.getString("mime")); copyTree(source, target)
            }
            op.put("target", target.toString()).put("verified", true); store.put("folderOps", op)
        }
        target = finalizeTarget(op, parent, target ?: Uri.parse(op.getString("target")))
        // Native moves remove the old parent link; copies delete only after full verification.
        if (children(Uri.parse(op.getString("sourceParent"))).any { it.uri == source }) {
            if (!op.optBoolean("deletingSource")) {
                check(fingerprint(source) == fingerprint(target)) { "Source changed after copy; refusing deletion" }
                op.put("deletingSource", true).put("targetFingerprint", fingerprintHash(target)); store.put("folderOps", op)
            }
            check(op.getString("targetFingerprint") == fingerprintHash(target)) { "Destination changed after copy; refusing deletion" }
            deleteCopiedTree(source, target)
        }
        if (!op.isNull("record")) {
            val record = op.getJSONObject("record")
            if (op.getString("mime") == directory) {
                preserveMappingRoot(record)
                record.put("parentId", op.opt("folderId")).put("rootUri", op.getString("rootUri"))
                rebindFolder(record, target)
            } else recordFile(record.getString("id"), target, read(target)!!.name, record.getString("mime"),
                op.optString("folderId").takeIf { !op.isNull("folderId") }, op.getString("rootUri")).also { publicationMetadata(it, it) }
        }
        store.delete("folderOps", op.getString("id"))
    }
    private fun fingerprintHash(document: Uri): String = MessageDigest.getInstance("SHA-256")
        .digest(fingerprint(document).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    /** Resume a verified copy's partial cleanup without allowing a provider to erase unknown contents. */
    private fun deleteCopiedTree(source: Uri, target: Uri) {
        check(!Thread.currentThread().isInterrupted) { "Storage migration interrupted" }
        val doc = read(source) ?: error("Source disappeared during cleanup")
        if (doc.mime == directory) {
            check(read(target)?.mime == directory) { "Destination folder unavailable" }
            val saved = children(target)
            for (child in children(source)) {
                val copy = saved.firstOrNull { it.name == child.name && it.mime == child.mime } ?: error("Source changed after copy")
                deleteCopiedTree(child.uri, copy.uri)
            }
            check(children(source).isEmpty()) { "Source changed during cleanup" }
        } else check(fingerprint(source) == fingerprint(target)) { "Source changed after copy; refusing deletion" }
        check(DC.deleteDocument(resolver, source)) { "Source deletion failed" }
    }

    /** Re-enumerate by stable record IDs and provider names; a journal remains until all descendants commit. */
    private fun preserveMappingRoot(record: JSONObject) {
        if (!record.isNull("platform") && record.optString("platform").isNotBlank() && !record.has("mappingRootUri")) record.put("mappingRootUri", record.getString("rootUri"))
    }
    private fun rebindFolder(record: JSONObject, document: Uri) {
        record.put("uri", document.toString()).put("name", read(document)!!.name)
        store.put("folders", record)
        val docs = children(document)
        store.list("folders").filter { it.optString("parentId") == record.getString("id") }.forEach { child ->
            val target = docs.firstOrNull { it.name == child.getString("name") && it.mime == directory }
            if (target == null && child.optBoolean("missing")) { child.put("parentId", JSONObject.NULL); store.put("folders", child); return@forEach }
            check(target != null) { "Subfolder unavailable" }
            preserveMappingRoot(child); child.put("rootUri", record.getString("rootUri")); rebindFolder(child, target.uri)
        }
        store.list("files").filter { it.optString("folderId") == record.getString("id") }.forEach { child ->
            val target = docs.firstOrNull { it.name == child.getString("name") && it.mime != directory }
            if (target == null && child.optBoolean("missing")) { child.put("folderId", JSONObject.NULL); store.put("files", child); return@forEach }
            check(target != null) { "Folder file unavailable" }
            child.put("uri", target.uri.toString()).put("rootUri", record.getString("rootUri")); child.remove("mediaUri")
            publicationMetadata(child, child)
        }
        store.list("jobs").filter { it.optString("folderId") == record.getString("id") }.forEach {
            it.put("rootUri", record.getString("rootUri")); store.put("jobs", it)
        }
    }
    fun moveFolder(id: String, parentId: String?) = synchronized(lock) {
        idle(id)
        val record = folder(id)
        check(!record.optBoolean("deleting")) { "Folder deletion pending" }
        require(parentId !in descendants(id)) { "Cannot move into this folder or its descendants" }
        val dest = parentId?.takeIf { it.isNotBlank() }?.let { folder(it).also { idle(parentId); check(!it.optBoolean("deleting")) } }
        val target = dest?.let { uri(it) } ?: root()
        check(children(target).none { it.name == record.getString("name") && it.uri != uri(record) }) { "Name already exists" }
        if (folderParent(record) != target) transfer(uri(record), folderParent(record), target, record, parentId,
            dest?.getString("rootUri") ?: store.settings().getString("rootUri"))
    }
    fun favorite(kind: String, id: String, value: Boolean) = synchronized(lock) {
        require(kind in listOf("folders", "files")); val record = store.get(kind, id) ?: error("Item not found")
        record.put("favorite", value); store.put(kind, record)
    }
    fun openFileFolder(id: String) {
        val record = file(id)
        openDirectory(parent(record))
    }
    fun moveFile(id: String, folderId: String?) = synchronized(lock) {
        val record = file(id)
        record.optString("folderId").takeIf { !record.isNull("folderId") && it.isNotEmpty() }?.let { idle(it) }
        val dest = folderId?.takeIf { it.isNotEmpty() }?.let { folder(it).also { idle(folderId) } }
        check(dest?.optBoolean("deleting") != true) { "Folder deletion pending" }
        val target = dest?.let { uri(it) } ?: root()
        if (parent(record) != target) transfer(uri(record), parent(record), target, record, folderId,
            dest?.getString("rootUri") ?: store.settings().getString("rootUri"))
    }
    fun renameFile(id: String, name: String) = synchronized(lock) {
        val record = file(id); valid(name)
        record.optString("folderId").takeIf { !record.isNull("folderId") && it.isNotEmpty() }?.let { idle(it) }
        rename(record, "files", parent(record), name)
    }
    private fun rename(record: JSONObject, kind: String, parent: Uri, name: String) {
        check(children(parent).none { it.name == name && it.uri != uri(record) }) { "Name already exists" }
        val op = JSONObject().put("id", "rename:${record.getString("id")}").put("type", "rename")
            .put("record", record).put("kind", kind).put("target", record.getString("uri"))
            .put("parent", parent.toString()).put("name", name)
        store.put("folderOps", op); finishRename(op)
    }
    private fun finishRename(op: JSONObject) {
        val record = op.getJSONObject("record")
        val result = finalizeTarget(op, Uri.parse(op.getString("parent")), Uri.parse(op.getString("target")))
        record.put("uri", result.toString()).put("name", read(result)!!.name)
        record.remove("mediaUri"); if (op.getString("kind") == "files") record.put("title", read(result)!!.name.substringBeforeLast('.'))
        store.put(op.getString("kind"), record)
        if (op.getString("kind") == "folders") rebindFolder(record, result)
        store.delete("folderOps", op.getString("id"))
    }
    fun deleteFile(id: String) = synchronized(lock) {
        val record = file(id)
        record.optString("folderId").takeIf { !record.isNull("folderId") && it.isNotEmpty() }?.let { idle(it) }
        val op = JSONObject().put("id", "delete-file:$id").put("type", "deleteFile").put("record", record)
        store.put("folderOps", op); finishDeleteFile(op)
    }
    private fun finishDeleteFile(op: JSONObject) {
        val record = op.getJSONObject("record")
        if (existsForDeletion(record)) {
            try { check(DC.deleteDocument(resolver, uri(record))) { "File deletion failed" } }
            catch (error: Exception) { if (existsForDeletion(record)) throw error }
        }
        store.delete("files", record.getString("id")); store.delete("folderOps", op.getString("id"))
    }
    private fun existsForDeletion(record: JSONObject, parentKey: String = "folderId"): Boolean {
        try { if (exists(uri(record))) return true }
        catch (_: IllegalArgumentException) { /* Confirm absence through accessible ancestors. */ }
        return presentInParent(record, parentKey)
    }
    private fun presentInParent(record: JSONObject, parentKey: String): Boolean {
        // A deleted tree URI can fail Android's child check before query reaches the provider.
        val ancestors = mutableListOf<JSONObject>()
        val seen = mutableSetOf<String>()
        var parentId = record.optString(parentKey).takeIf { !record.isNull(parentKey) && it.isNotBlank() }
        while (parentId != null) {
            check(seen.add(parentId)) { "Invalid folder hierarchy" }
            val ancestor = folder(parentId); ancestors.add(ancestor)
            parentId = ancestor.optString("parentId").takeIf { !ancestor.isNull("parentId") && it.isNotBlank() }
        }
        var parent = rootOf(record)
        for (ancestor in ancestors.asReversed()) {
            parent = children(parent).firstOrNull { DC.getDocumentId(it.uri) == DC.getDocumentId(uri(ancestor)) }?.uri ?: return false
        }
        return children(parent).any { DC.getDocumentId(it.uri) == DC.getDocumentId(uri(record)) }
    }
    fun openFile(id: String, share: Boolean = false) {
        val record = file(id); val document = uri(record)
        val intent = if (share) Intent(Intent.ACTION_SEND).setType(record.getString("mime"))
            .putExtra(Intent.EXTRA_STREAM, document) else Intent(Intent.ACTION_VIEW).setDataAndType(document, record.getString("mime"))
        intent.clipData = ClipData.newRawUri(record.getString("name"), document)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        val launch = if (share) Intent.createChooser(intent, "Share file").apply {
            clipData = intent.clipData
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        } else intent
        try { context.startActivity(launch) }
        catch (error: android.content.ActivityNotFoundException) {
            throw IllegalStateException(if (share) "No application is available to share this file"
                else "No application can open this file type", error)
        }
    }
    fun openFolder(id: String) {
        openDirectory(uri(folder(id)))
    }
    internal fun migration(): JSONObject? = store.get("folderOps", "storage-migration")

    internal fun prepareMigration(destination: Uri) = synchronized(lock) {
        val target = approvedRoot(destination)
        migration()?.let { pending ->
            require(destination.toString() in listOf(pending.getString("sourceRoot"), pending.getString("destinationRoot"))) { "Storage migration is pending" }
            pending.put("state", "queued").remove("error"); store.put("folderOps", pending); return@synchronized
        }
        ensureRoot()
        val original = store.settings().getString("rootUri")
        if (Uri.parse(original).authority == destination.authority && DC.getTreeDocumentId(Uri.parse(original)) == DC.getTreeDocumentId(destination)) return@synchronized
        val sourceRoot = root()
        if (sourceRoot.authority == target.authority) require(!DC.isChildDocument(resolver, sourceRoot, target)) { "Cannot migrate into a source folder" }
        check(store.list("jobs").none { jobState(it) !in setOf("completed", "complete", "failed", "cancelled", "canceled", "done", "paused", "stopped") }) { "Pause downloads before migrating storage" }
        recover()
        check(store.list("folderOps").isEmpty()) { "Complete pending file operations first" }
        val folders = store.list("folders").filter { it.optString("rootUri") == original && (it.isNull("parentId") || it.optString("parentId").isEmpty()) }
        for (record in folders.filter { !it.optBoolean("missing") && uri(it).authority == target.authority }) {
            require(DC.getDocumentId(uri(record)) != DC.getDocumentId(target) && !DC.isChildDocument(resolver, uri(record), target)) { "Cannot migrate into a source folder" }
        }
        val files = store.list("files").filter { it.optString("rootUri") == original && (it.isNull("folderId") || it.optString("folderId").isEmpty()) }
        val items = org.json.JSONArray((folders.map { "folders" to it } + files.map { "files" to it }).map { (kind, record) -> JSONObject().put("kind", kind).put("id", record.getString("id")) })
        store.put("folderOps", JSONObject().put("id", "storage-migration").put("type", "rootMigration").put("state", "queued")
            .put("sourceRoot", original).put("destinationRoot", destination.toString()).put("destinationName", read(target)!!.name)
            .put("items", items).put("folderIds", org.json.JSONArray(store.list("folders").filter { it.optString("rootUri") == original }.map { it.getString("id") }))
            .put("done", 0).put("total", items.length()).put("missing", 0))
    }

    internal fun migrate(progress: () -> Unit = {}) = synchronized(lock) {
        val operation = migration() ?: return@synchronized
        val destination = Uri.parse(operation.getString("destinationRoot"))
        val target = DC.buildDocumentUriUsingTree(destination, DC.getTreeDocumentId(destination))
        check(read(target)?.mime == directory) { "Destination unavailable" }
        operation.put("state", "running"); operation.remove("error"); store.put("folderOps", operation); progress()
        val items = operation.getJSONArray("items")
        while (operation.getInt("done") < items.length()) {
            check(!Thread.currentThread().isInterrupted) { "Storage migration interrupted" }
            val item = items.getJSONObject(operation.getInt("done")); val kind = item.getString("kind")
            val record = store.get(kind, item.getString("id"))
            if (record != null && record.optString("rootUri") != destination.toString()) {
                if (record.optBoolean("missing")) operation.put("missing", operation.optInt("missing") + 1)
                else transfer(uri(record), if (kind == "folders") folderParent(record) else parent(record), target, record, null, destination.toString())
            }
            operation.put("done", operation.getInt("done") + 1); store.put("folderOps", operation); progress()
        }
        val folderIds = operation.optJSONArray("folderIds") ?: org.json.JSONArray()
        for (i in 0 until folderIds.length()) {
            val moved = store.get("folders", folderIds.getString(i)) ?: continue
            if (moved.optString("rootUri") != destination.toString()) continue
            val platform = moved.optString("platform").takeIf { !moved.isNull("platform") && it.isNotBlank() } ?: continue
            store.list("folders").filter { it.getString("id") != moved.getString("id") && it.optString("mappingRootUri", it.optString("rootUri")) == destination.toString() && it.optString("platform") == platform }
                .forEach { it.put("mappingEnabled", false); store.put("folders", it) }
            moved.put("mappingRootUri", destination.toString()).put("mappingEnabled", true); store.put("folders", moved)
        }
        store.list("jobs").filter { it.optString("rootUri") == operation.getString("sourceRoot") && (it.optString("folderId").isBlank() || store.get("folders", it.optString("folderId"))?.optString("rootUri") == destination.toString()) }.forEach { it.put("rootUri", destination.toString()); store.put("jobs", it) }
        for (platform in knownPlatforms) if (store.settings().optBoolean("disabled:${operation.getString("sourceRoot")}:$platform")) store.setting("disabled:$destination:$platform", true)
        // Count unavailable descendants too, including records detached during URI rebinding.
        operation.put("missing", (store.list("folders") + store.list("files")).count {
            it.optString("rootUri") == operation.getString("sourceRoot") && it.optBoolean("missing")
        })
        store.put("folderOps", operation)
        // Persist the summary before dropping the journal; a restarted finalization repeats safely.
        store.put("settings", store.settings().put("id", "settings").put("rootUri", destination.toString())
            .put("rootName", read(target)!!.name).put("lastMigration", JSONObject().put("state", "completed").put("destinationName", operation.getString("destinationName"))
                .put("total", operation.getInt("total")).put("done", operation.getInt("done")).put("missing", operation.optInt("missing")).put("completedAt", System.currentTimeMillis())))
        store.delete("folderOps", "storage-migration")
        chooseRoot(destination); progress()
    }
    private fun openDirectory(document: Uri) {
        try { context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(document, directory)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (_: android.content.ActivityNotFoundException) {
            context.startActivity(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                if (Build.VERSION.SDK_INT >= 26) putExtra(DC.EXTRA_INITIAL_URI, document)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            })
        }
    }
    /** Failed journals remain durable. Caller may display the exception and retry after restoring access. */
    fun recover() = synchronized(lock) {
        store.list("folders").filter { !it.has("parentId") }.forEach {
            it.put("parentId", JSONObject.NULL).put("favorite", false)
            it.put("color", when (it.optString("platform")) { "YouTube" -> 0xffff343fL; "Instagram" -> 0xffec49b4L; "TikTok" -> 0xff40cdb7L; else -> 0xff0a6bffL })
            store.put("folders", it)
        }
        // Also recover a crash between marking a folder and creating its deletion journal.
        store.list("folders").filter { it.optBoolean("deleting") }.forEach { record ->
            val opId = "delete-folder:${record.getString("id")}"
            if (store.get("folderOps", opId) == null) store.put("folderOps", JSONObject()
                .put("id", opId).put("type", "deleteFolder").put("folderId", record.getString("id")))
            if (!record.isNull("platform") && record.optString("platform").isNotEmpty())
                store.setting("disabled:${record.optString("mappingRootUri", record.getString("rootUri"))}:${record.getString("platform")}", true)
        }
        val ops = store.list("folderOps")
        var failure: Throwable? = null
        fun attempt(block: () -> Unit) {
            try { block() } catch (error: Exception) { if (failure == null) failure = error }
        }
        ops.filter { it.optString("type") != "deleteFolder" }.forEach { op -> attempt {
            when (op.getString("type")) {
                "publish" -> finishPublish(op); "move" -> finishMove(op)
                "rename" -> finishRename(op); "deleteFile" -> finishDeleteFile(op)
            }
        } }
        ops.filter { it.optString("type") == "deleteFolder" }.forEach { attempt { finishDeleteFolder(it) } }
        (store.list("folders").map { "folders" to it } + store.list("files").map { "files" to it }).forEach { (kind, record) ->
            // Permission failures are unavailable, not proof that a provider deleted the document.
            val available = runCatching { exists(uri(record)) }.getOrDefault(false)
            record.put("missing", !available); store.put(kind, record)
        }
        failure?.let { throw it }
    }
}

/** Provider-independent validation, also usable before dispatching a UI request onto IO. */
internal object StorageNames {
    fun title(value: String): String {
        val output = StringBuilder()
        var bytes = 0
        for (point in value.codePoints().toArray()) {
            val text = String(Character.toChars(if (point in 0xd800..0xdfff) '_'.code else point))
            val count = text.toByteArray(Charsets.UTF_8).size
            if (bytes + count > 160) break
            output.append(text); bytes += count
        }
        return output.toString().trim().ifBlank { "media" }
    }
    fun validate(name: String): String {
        require(name.isNotBlank() && name == name.trim() && name != "." && name != ".." &&
            name.none { it == '/' || it == '\\' || it.code < 32 || it.code == 127 } && name.toByteArray(Charsets.UTF_8).size <= 240) {
            "Invalid file or folder name"
        }
        return name
    }
}
