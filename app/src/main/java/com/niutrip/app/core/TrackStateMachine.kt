package com.niutrip.app.core

enum class TrackStatus { NOT_STARTED, RECORDING, FINISHED }

object TrackStateMachine {
    fun canStart(status: TrackStatus) = status == TrackStatus.NOT_STARTED
    fun canFinish(status: TrackStatus) = status == TrackStatus.RECORDING
    fun showResume(status: TrackStatus) = status == TrackStatus.RECORDING
    fun canCheckin(status: TrackStatus) = status == TrackStatus.RECORDING
}

fun String.asTrackStatus(): TrackStatus = runCatching { TrackStatus.valueOf(this) }
    .getOrDefault(TrackStatus.NOT_STARTED)
