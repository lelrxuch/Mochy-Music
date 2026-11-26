package com.mochyy.desktop

import com.mochyy.desktop.text.*
import com.mochyy.desktop.playback.*
import com.mochyy.desktop.library.*
import com.mochyy.desktop.account.*
import com.mochyy.desktop.ui.*
import com.mochyy.desktop.ui.screens.*
import java.awt.Frame
import java.awt.Window
import java.io.File
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import javax.swing.SwingUtilities

/**
 * One Mochy at a time. A second launch (the dock, the app menu, a double click) would start a second
 * player with its own mpv, so instead it knocks on the first one's socket, which comes to the front,
 * and quits. The socket sits in the user's private runtime dir.
 */
object SingleInstance {
    private val socket = File(System.getenv("XDG_RUNTIME_DIR") ?: System.getProperty("java.io.tmpdir"), "mochy-desktop.sock")

    @Volatile
    var window: Window? = null

    /** true when a running Mochy answered; this launch should end there */
    fun handOff(): Boolean = runCatching {
        SocketChannel.open(UnixDomainSocketAddress.of(socket.toPath())).close()
        true
    }.getOrDefault(false)

    /** takes the knocks of later launches; call once [handOff] found nobody */
    fun listen() {
        // nobody answered, so a socket file still there was left by a crash
        socket.delete()
        val server = runCatching { ServerSocketChannel.open(StandardProtocolFamily.UNIX).bind(UnixDomainSocketAddress.of(socket.toPath())) }
            .getOrNull() ?: return
        socket.deleteOnExit()
        Thread({
            while (true) {
                runCatching { server.accept().close() }.getOrNull() ?: break
                SwingUtilities.invokeLater(::raise)
            }
        }, "single-instance").apply { isDaemon = true }.start()
    }

    fun raise() {
        val frame = window as? Frame ?: return
        if (frame.extendedState and Frame.ICONIFIED != 0) frame.extendedState = frame.extendedState and Frame.ICONIFIED.inv()
        frame.isVisible = true
        frame.toFront()
        frame.requestFocus()
    }
}
