package com.ecarx.autoservice.media

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.os.Process
import java.io.File

/** 单个当前封面，只读且同签名；不接受外部文件路径，不暴露私有认证目录。 */
class ArtworkProvider : ContentProvider() {
    /** 不打开文件，提供者本身没有需要初始化的连接。 */
    override fun onCreate() = true

    /**
     * 只对同签名调用方开放 `/current` 的只读句柄。
     *
     * 异签名、其他路径或其他打开模式直接拒绝，不跟随调用方给出的路径。
     */
    override fun openFile(
        uri: Uri,
        mode: String,
    ): ParcelFileDescriptor {
        val context = checkNotNull(context)
        check(
            context.packageManager.checkSignatures(Binder.getCallingUid(), Process.myUid()) ==
                PackageManager.SIGNATURE_MATCH,
        )
        require(uri.path == "/current" && mode == "r")
        return ParcelFileDescriptor.open(File(context.cacheDir, "artwork.png"), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    /** 当前资源按图片类型声明，不因路径不同改类型。 */
    override fun getType(uri: Uri) = "image/*"

    /** 不提供查询。 */
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    /** 不接受插入。 */
    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    /** 不接受删除。 */
    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ) = 0

    /** 不接受更新。 */
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ) = 0
}
