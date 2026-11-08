package com.veacon.veilmusic.ui.viewmodel

/** Main-thread ownership of asynchronous queue requests. Only the latest choice may commit. */
internal class PlaybackRequestGate {
    var revision = 0L
        private set
    fun select(): Long = ++revision

    fun isCurrent(request: Long): Boolean = request == revision

}

/** Play/pause intent while a selected song has no attached source yet. Never resumes old audio. */
internal class SourcePreparation {
    private var generation: Int? = null
    var playWhenReady = true
        private set
    val isPending: Boolean get() = generation != null

    fun begin(request: Int, shouldPlay: Boolean = true) {
        generation = request
        playWhenReady = shouldPlay
    }

    fun toggle() { playWhenReady = !playWhenReady }
    fun pause() { playWhenReady = false }
    fun resume() { playWhenReady = true }
    fun cancel() { generation = null }

    /** Null means a newer selection owns playback. Otherwise consume the latest user intent. */
    fun finish(request: Int): Boolean? {
        if (generation != request) return null
        generation = null
        return playWhenReady
    }
}
