package com.mochyy.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.music.innertube.YouTube
import com.music.innertube.utils.parseCookieString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.sql.DriverManager
import java.time.Duration
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** A YouTube Music sign-in: the browser cookie plus what the Android app keeps beside it. */
@Serializable
data class Session(
    val cookie: String,
    val visitorData: String? = null,
    val dataSyncId: String? = null,
    val name: String = "",
    val email: String? = null,
    val handle: String? = null,
    val avatar: String? = null,
)

/**
 * The account, what YouTubeLoginScreen saves on Android. There a WebView does the Google sign-in;
 * Google turns embedded browsers away on desktop, so here the session comes from a browser where
 * the person already signed in (or a pasted cookie). Kept in ~/.local/share/mochy-desktop/account.json,
 * readable only by its owner since the cookie is as good as the password.
 */
class AccountStore(private val file: File = dataFile("account.json")) {
    var session by mutableStateOf(load())
        private set

    private val refreshing = Mutex()

    init {
        use(session)
        current = this
    }

    /**
     * Google rotates a signed-in browser's session cookies every few minutes and voids the old ones,
     * so the copy taken at sign-in goes stale (401). This reads the browser again for the same session,
     * known by its SAPISID, which doesn't rotate. True when there's a newer cookie to use.
     */
    suspend fun refresh(): Boolean = withContext(Dispatchers.IO) {
        val before = session?.cookie
        refreshing.withLock {
            val now = session ?: return@withLock false
            // someone else refreshed while this caller waited
            if (now.cookie != before) return@withLock true
            val id = sapisid(now.cookie) ?: return@withLock false
            val fresh = BrowserCookies.find().map { it.cookie }.firstOrNull { sapisid(it) == id } ?: return@withLock false
            if (fresh == now.cookie) return@withLock false
            val updated = now.copy(cookie = fresh)
            use(updated)
            save(updated)
            session = updated
            true
        }
    }

    /** picks up the browser's rotations in the background, so a request rarely meets a stale cookie */
    fun keepFresh(scope: CoroutineScope) = scope.launch {
        while (isActive) {
            delay(4 * 60_000L)
            if (session != null) refresh()
        }
    }

    /** checks the cookie against YouTube Music and keeps it only if it names an account */
    suspend fun signIn(rawCookie: String): Result<Session> = withContext(Dispatchers.IO) {
        val previous = session
        runCatching {
            val cookie = cleanCookie(rawCookie)
            require(hasLogin(cookie)) { "the cookie has no SAPISID, so it isn't signed in" }
            val page = musicPage(cookie)
            val draft = Session(
                cookie = cookie,
                visitorData = VISITOR_DATA.find(page)?.groupValues?.get(1),
                dataSyncId = DATASYNC_ID.find(page)?.groupValues?.get(1)?.let(::ownDataSyncId),
            )
            use(draft)
            val info = YouTube.accountInfo().getOrThrow()
            draft.copy(name = info.name, email = info.email, handle = info.channelHandle, avatar = info.thumbnailUrl)
        }.onSuccess {
            save(it)
            session = it
        }.onFailure { use(previous) }
    }

    fun signOut() {
        use(null)
        file.delete()
        session = null
    }

    private fun use(session: Session?) {
        YouTube.cookie = session?.cookie
        YouTube.dataSyncId = session?.dataSyncId
        YouTube.visitorData = session?.visitorData
        YouTube.useLoginForBrowse = session != null
    }

    private fun load(): Session? = runCatching { json.decodeFromString<Session>(file.readText()) }.getOrNull()

    private fun save(session: Session) {
        file.parentFile.mkdirs()
        val temp = File(file.path + ".tmp")
        temp.delete()
        Files.createFile(temp.toPath(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        temp.writeText(json.encodeToString(Session.serializer(), session))
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    companion object {
        /** the app's account, for [signedIn] */
        @Volatile
        var current: AccountStore? = null
            private set

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        private val VISITOR_DATA = Regex("\"VISITOR_DATA\":\"([^\"]+)\"")
        private val DATASYNC_ID = Regex("\"DATASYNC_ID\":\"([^\"]+)\"")

        // the page config YouTube Music hands a signed-in browser, where the app's WebView reads the same two ids
        private fun musicPage(cookie: String): String {
            val request = HttpRequest.newBuilder(URI("https://music.youtube.com/"))
                .header("Cookie", cookie)
                .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36")
                .header("Accept-Language", "es-419,es;q=0.9,en;q=0.8")
                .timeout(Duration.ofSeconds(20))
                .build()
            return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
                .send(request, HttpResponse.BodyHandlers.ofString()).body()
        }
    }
}

/** "a||b" is the brand account id and the google one; the app keeps the one that's this user's */
fun ownDataSyncId(id: String): String =
    id.takeIf { "||" !in it } ?: id.takeIf { it.endsWith("||") }?.substringBefore("||") ?: id.substringAfter("||")

/** takes a pasted "Cookie: a=b; c=d", line breaks and all, down to "a=b; c=d" */
fun cleanCookie(raw: String): String =
    parseCookieString(raw.replace(Regex("^\\s*cookie:\\s*", RegexOption.IGNORE_CASE), "").replace(Regex("[\\r\\n]+"), " "))
        .entries.joinToString("; ") { "${it.key}=${it.value}" }

/**
 * Runs a YouTube request; if it comes back 401 (a sign-in the browser has rotated away), reads the
 * session again and tries once more.
 */
suspend fun <T> signedIn(fetch: suspend () -> Result<T>): Result<T> {
    val first = fetch()
    val unauthorized = first.exceptionOrNull()?.message.orEmpty().contains("invalid: 401")
    return if (unauthorized && AccountStore.current?.refresh() == true) fetch() else first
}

private fun sapisid(cookie: String) = parseCookieString(cookie).let { it["SAPISID"] ?: it["__Secure-3PAPISID"] }

fun hasLogin(cookie: String) = parseCookieString(cookie).let { "SAPISID" in it || "__Secure-3PAPISID" in it }

fun dataFile(name: String): File {
    val data = System.getenv("XDG_DATA_HOME") ?: "${System.getProperty("user.home")}/.local/share"
    return File(data, "mochy-desktop/$name")
}

// ---- reading the sign-in out of the browsers people use on Fedora ----

/** a browser profile signed in to YouTube, with the cookie it holds for it */
data class BrowserLogin(val label: String, val cookie: String)

private data class StoredCookie(val host: String, val name: String, val value: String)

object BrowserCookies {
    private val home = System.getProperty("user.home")

    private val firefoxRoots = listOf(
        "Firefox" to ".mozilla/firefox",
        "Firefox" to ".var/app/org.mozilla.firefox/.mozilla/firefox",
        "Firefox" to "snap/firefox/common/.mozilla/firefox",
        "Zen" to ".zen",
        "Zen" to ".var/app/app.zen_browser.zen/.zen",
        "LibreWolf" to ".librewolf",
        "LibreWolf" to ".var/app/io.gitlab.librewolf-community/.librewolf",
        "Floorp" to ".floorp",
        "Floorp" to ".var/app/one.ablaze.floorp/.floorp",
    )

    // label, config folder, and the name its key is filed under in the GNOME keyring
    private val chromiumRoots = listOf(
        Triple("Brave", ".config/BraveSoftware/Brave-Browser", "brave"),
        Triple("Brave", ".var/app/com.brave.Browser/config/BraveSoftware/Brave-Browser", "brave"),
        Triple("Chrome", ".config/google-chrome", "chrome"),
        Triple("Chrome", ".var/app/com.google.Chrome/config/google-chrome", "chrome"),
        Triple("Chromium", ".config/chromium", "chromium"),
        Triple("Chromium", ".var/app/org.chromium.Chromium/config/chromium", "chromium"),
        Triple("Vivaldi", ".config/vivaldi", "vivaldi"),
    )

    /** every profile with a YouTube sign-in; a browser that can't be read is skipped, never fatal */
    fun find(): List<BrowserLogin> {
        val firefox = firefoxRoots.flatMap { (label, root) ->
            profiles(File(home, root)) { File(it, "cookies.sqlite").takeIf(File::isFile) }.mapNotNull { (profile, db) ->
                runCatching { login("$label · ${profile.name.substringAfter('.')}", firefoxCookies(db)) }.getOrNull()
            }
        }
        val chromium = chromiumRoots.flatMap { (label, root, keyring) ->
            val keys by lazy { chromiumKeys(keyring) }
            profiles(File(home, root)) { dir ->
                listOf(File(dir, "Network/Cookies"), File(dir, "Cookies")).firstOrNull(File::isFile)
            }.mapNotNull { (profile, db) ->
                runCatching { login("$label · ${profile.name}", chromiumCookies(db, keys)) }.getOrNull()
            }
        }
        return (firefox + chromium).distinctBy { it.cookie }
    }

    private fun profiles(root: File, cookies: (File) -> File?): List<Pair<File, File>> =
        root.listFiles().orEmpty().filter { it.isDirectory }.sortedBy { it.name }.mapNotNull { dir -> cookies(dir)?.let { dir to it } }

    private fun login(label: String, cookies: List<StoredCookie>): BrowserLogin? {
        // a cookie on .youtube.com wins over a host-only copy of the same name
        val merged = cookies.filter { it.host == "youtube.com" || it.host.endsWith(".youtube.com") }
            .sortedBy { it.host == ".youtube.com" }
            .associate { it.name to it.value }
        val cookie = merged.entries.joinToString("; ") { "${it.key}=${it.value}" }
        return BrowserLogin(label, cookie).takeIf { hasLogin(cookie) }
    }

    private fun firefoxCookies(db: File): List<StoredCookie> = query(db) { connection ->
        connection.prepareStatement("SELECT host, name, value FROM moz_cookies WHERE host LIKE '%youtube.com'").executeQuery().use { rows ->
            buildList { while (rows.next()) add(StoredCookie(rows.getString(1), rows.getString(2), rows.getString(3))) }
        }
    }

    private fun chromiumCookies(db: File, keys: List<ByteArray>): List<StoredCookie> = query(db) { connection ->
        val version = connection.prepareStatement("SELECT value FROM meta WHERE key = 'version'").executeQuery()
            .use { if (it.next()) it.getString(1).toIntOrNull() ?: 0 else 0 }
        connection.prepareStatement("SELECT host_key, name, value, encrypted_value FROM cookies WHERE host_key LIKE '%youtube.com'")
            .executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        val plain = rows.getString(3).orEmpty()
                        val value = plain.ifEmpty { decryptChromium(rows.getBytes(4) ?: ByteArray(0), keys, version).orEmpty() }
                        if (value.isNotEmpty()) add(StoredCookie(rows.getString(1), rows.getString(2), value))
                    }
                }
            }
    }

    // a running browser keeps its database locked, so read a copy (with its journal, so recent changes count)
    private fun <T> query(db: File, read: (java.sql.Connection) -> T): T {
        val dir: Path = Files.createTempDirectory("mochy-cookies")
        try {
            for (suffix in listOf("", "-wal", "-shm", "-journal")) {
                val source = File(db.path + suffix)
                if (source.isFile) Files.copy(source.toPath(), dir.resolve(db.name + suffix))
            }
            return DriverManager.getConnection("jdbc:sqlite:${dir.resolve(db.name)}").use(read)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // the password Chromium filed in the GNOME keyring, then the two it falls back to without one
    private fun chromiumKeys(keyring: String): List<ByteArray> =
        listOfNotNull(keyringPassword(keyring)?.let(::chromiumKey), chromiumKey("peanuts"), chromiumKey(""))

    private fun keyringPassword(application: String): String? {
        val process = runCatching { ProcessBuilder("secret-tool", "lookup", "application", application).start() }.getOrNull() ?: return null
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroy()
            return null
        }
        return process.inputStream.bufferedReader().readText().trim().takeIf { process.exitValue() == 0 && it.isNotEmpty() }
    }
}

/** Chromium's cookie key on Linux: PBKDF2-SHA1 over "saltysalt", one round, 16 bytes; done by hand because Java refuses an empty password */
fun chromiumKey(password: String): ByteArray =
    hmacSha1(password.toByteArray(), "saltysalt".toByteArray() + byteArrayOf(0, 0, 0, 1)).copyOf(16)

private fun hmacSha1(key: ByteArray, data: ByteArray): ByteArray {
    val block = (if (key.size > 64) MessageDigest.getInstance("SHA-1").digest(key) else key).copyOf(64)
    val inner = MessageDigest.getInstance("SHA-1").digest(ByteArray(64) { (block[it].toInt() xor 0x36).toByte() } + data)
    return MessageDigest.getInstance("SHA-1").digest(ByteArray(64) { (block[it].toInt() xor 0x5c).toByte() } + inner)
}

/**
 * "v10"/"v11" + AES-128-CBC with an IV of 16 spaces. From database version 24 on, the plain text starts
 * with the SHA-256 of the cookie's host, which is dropped. A wrong key almost always fails the padding;
 * the printable check catches the rare one that doesn't.
 */
fun decryptChromium(encrypted: ByteArray, keys: List<ByteArray>, version: Int): String? {
    if (encrypted.size < 3) return null
    val prefix = String(encrypted, 0, 3, Charsets.US_ASCII)
    if (prefix != "v10" && prefix != "v11") return null
    val body = encrypted.copyOfRange(3, encrypted.size)
    for (key in keys) {
        val plain = runCatching {
            Cipher.getInstance("AES/CBC/PKCS5Padding")
                .apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(ByteArray(16) { ' '.code.toByte() })) }
                .doFinal(body)
        }.getOrNull() ?: continue
        val text = String(if (version >= 24 && plain.size >= 32) plain.copyOfRange(32, plain.size) else plain, Charsets.UTF_8)
        if (text.all { it.code in 0x20..0x7e }) return text
    }
    return null
}
