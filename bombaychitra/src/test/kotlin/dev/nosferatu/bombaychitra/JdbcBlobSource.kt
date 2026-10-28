package dev.nosferatu.bombaychitra

import java.io.File
import java.sql.DriverManager

/** Reads a BombayChitra database (full or the extracted graph.db) through JDBC, for tests and tools. */
class JdbcBlobSource(file: File) : RoutingBlobSource, AutoCloseable {
    private val conn = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}?open_mode=1")

    override fun blob(name: String): ByteArray? =
        conn.prepareStatement("SELECT data FROM routing WHERE name = ?").use { st ->
            st.setString(1, name)
            st.executeQuery().use { rs -> if (rs.next()) rs.getBytes(1) else null }
        }

    override fun meta(): Map<String, String> =
        conn.createStatement().use { st ->
            st.executeQuery("SELECT key, value FROM meta").use { rs ->
                buildMap { while (rs.next()) put(rs.getString(1), rs.getString(2)) }
            }
        }

    override fun close() = conn.close()

    companion object {
        /** The graph extracted into the app's assets, or the `-Pbombaychitra.db=` path; null when neither exists. */
        fun locate(): File? {
            val explicit = System.getProperty("bombaychitra.db").orEmpty()
            return listOf(explicit, "../app/src/main/assets/bombaychitra/graph.db")
                .filter { it.isNotEmpty() }
                .map(::File)
                .firstOrNull { it.isFile }
        }
    }
}
