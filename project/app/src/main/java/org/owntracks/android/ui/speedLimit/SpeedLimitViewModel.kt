package org.owntracks.android.ui.speedLimit

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import org.owntracks.android.ui.map.LocationLiveData
import javax.inject.Inject

@HiltViewModel
class SpeedLimitViewModel @Inject constructor(
    application: Application,
) : AndroidViewModel(application) {
    val currentLocation = LocationLiveData(application, viewModelScope)
}
