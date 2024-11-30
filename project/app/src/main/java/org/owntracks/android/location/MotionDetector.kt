package org.owntracks.android.location

import android.location.Location
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

const val MAX_STATIONARY_SECONDS = 300

@Singleton
class MotionDetector @Inject constructor() {
  val currentMotion: StateFlow<Boolean>
    get() = mutableCurrentMotion

  private val mutableCurrentMotion = MutableStateFlow<Boolean>(false)

  private var previousLocation: Location? = null

  private var previousMovingTime: Long = 0

  private var moving = false

  suspend fun onLocationChanged(location: Location) {
    var newMoving: Boolean = false

    val localPreviousLocation = previousLocation

    if (localPreviousLocation != null) {
      val distance = location.distanceTo(localPreviousLocation)
      if (distance > location.accuracy && distance > localPreviousLocation.accuracy) {
        newMoving = true
        previousMovingTime = location.elapsedRealtimeNanos
        previousLocation = location
      } else if ((((location.elapsedRealtimeNanos - previousMovingTime)) / 1000000000) < MAX_STATIONARY_SECONDS) {
        newMoving = true
      }
    } else {
      previousLocation = location
    }
    if (moving !== newMoving) {
      moving = newMoving
      mutableCurrentMotion.emit(moving)
    }
  }

  fun getMoving(): Boolean {
    return moving
  }
}
