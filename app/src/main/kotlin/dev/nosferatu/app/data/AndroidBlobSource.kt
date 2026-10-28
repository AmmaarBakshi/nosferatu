package dev.nosferatu.app.data

import android.database.sqlite.SQLiteDatabase
import dev.nosferatu.bombaychitra.RoutingBlobSource
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * BombayChitra's `routing` table through Android's SQLite. Android's CursorWindow refuses rows over 2 MB and the
 * `shape` blob is 7 MB, so blobs are read in 1 MB slices with `substr()`, which works on BLOBs byte-wise.
 */
class AndroidBlobSource(file: File) : RoutingBlobSource, AutoCloseable {
    private val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)

    override fun blob(name: String): ByteArray? {
        val length = db.rawQuery("SELECT length(data) FROM routing WHERE name = ?", arrayOf(name)).use { c ->
            if (c.moveToFirst()) c.getLong(0) else return null
        }
        val out = ByteArrayOutputStream(length.toInt())
        var offset = 1L // substr() is 1-based
        while (offset <= length) {
            db.rawQuery("SELECT substr(data, ?, ?) FROM routing WHERE name = ?", arrayOf(offset.toString(), CHUNK.toString(), name)).use { c ->
                if (!c.moveToFirst()) return null
                out.write(c.getBlob(0))
            }
            offset += CHUNK
        }
        return out.toByteArray()
    }

    override fun meta(): Map<String, String> = db.rawQuery("SELECT key, value FROM meta", null).use { c ->
        buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1)) }
    }

    override fun close() = db.close()

    private companion object {
        const val CHUNK = 1_000_000
    }
}
