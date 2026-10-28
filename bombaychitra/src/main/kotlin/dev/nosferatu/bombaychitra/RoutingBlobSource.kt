package dev.nosferatu.bombaychitra

/**
 * Read access to BombayChitra's `routing` and `meta` tables. The Android app implements it on its SQLite API
 * (reading large blobs in chunks to stay under the CursorWindow limit); tests implement it with JDBC.
 */
interface RoutingBlobSource {
    /** The gzip-compressed blob named [name] (`nodes`, `edge_to`…), or null when absent. */
    fun blob(name: String): ByteArray?

    /** The `meta` table as key/value text. */
    fun meta(): Map<String, String>
}

/** BombayChitra's `routing` table could not be used. */
class RoutingGraphException(message: String, cause: Throwable? = null) : Exception(message, cause)
