package com.mochyy.desktop

import com.mochyy.desktop.ui.*
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals

/** What a failed page tells the person: the kind of problem decides the words and whether it retries alone. */
class ErrorsCheck {
    @Test
    fun noConnectionIsOffline() {
        assertEquals(Problem.Offline, problemOf(UnknownHostException("music.youtube.com")))
        assertEquals(Problem.Offline, problemOf(ConnectException("Connection refused")))
        // ktor and innertube wrap the real cause
        assertEquals(Problem.Offline, problemOf(IOException("request failed", UnknownHostException("music.youtube.com"))))
    }

    @Test
    fun slowIsATimeout() {
        assertEquals(Problem.Slow, problemOf(SocketTimeoutException("Read timed out")))
        assertEquals(Problem.Slow, problemOf(RuntimeException(HttpRequestTimeoutExceptionLike())))
    }

    @Test
    fun anythingElseIsUnexpected() {
        assertEquals(Problem.Other, problemOf(IllegalStateException("missing field")))
    }

    // ktor's HttpRequestTimeoutException, by name: what problemOf goes by for timeouts it doesn't know
    private class HttpRequestTimeoutExceptionLike : IOException("Request timeout has expired")
}
