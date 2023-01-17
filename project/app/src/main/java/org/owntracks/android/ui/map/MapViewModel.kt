package org.owntracks.android.ui.map

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.location.Location
import androidx.annotation.MainThread
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlin.math.asin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.owntracks.android.data.repos.ContactsRepo
import org.owntracks.android.data.repos.LocationRepo
import org.owntracks.android.data.repos.WaypointsRepo
import org.owntracks.android.geocoding.GeocoderProvider
import org.owntracks.android.location.LatLng
import org.owntracks.android.location.toLatLng
import org.owntracks.android.model.FusedContact
import org.owntracks.android.model.messages.MessageClear
import org.owntracks.android.model.messages.MessageLocation.Companion.REPORT_TYPE_USER
import org.owntracks.android.preferences.Preferences
import org.owntracks.android.preferences.types.ConnectionMode
import org.owntracks.android.preferences.types.MonitoringMode
import org.owntracks.android.services.LocationProcessor
import org.owntracks.android.services.MessageProcessor
import org.owntracks.android.support.RequirementsChecker
import org.owntracks.android.support.SimpleIdlingResource
import timber.log.Timber

@HiltViewModel
class MapViewModel @Inject constructor(
    private val contactsRepo: ContactsRepo,
    private val locationProcessor: LocationProcessor,
    private val messageProcessor: MessageProcessor,
    private val geocoderProvider: GeocoderProvider,
    private val preferences: Preferences,
    private val locationRepo: LocationRepo,
    private val waypointsRepo: WaypointsRepo,
    @ApplicationContext private val applicationContext: Context,
    private val requirementsChecker: RequirementsChecker
) : ViewModel() {
    // controls who the currently selected contact is
    private val mutableCurrentContact = MutableLiveData<FusedContact?>()
    val currentContact: LiveData<FusedContact?>
        get() = mutableCurrentContact

    // controls the state of the bottom sheet on the map
    private val mutableBottomSheetHidden = MutableLiveData<Boolean>()
    val bottomSheetHidden: LiveData<Boolean>
        get() = mutableBottomSheetHidden

    // Controls where the map should set the camera to
    private val mutableMapCenter = MutableLiveData<LatLng>()
    val mapCenter: LiveData<LatLng>
        get() = mutableMapCenter

    // Shows the current distance to the selected contact
    private val mutableContactDistance = MutableLiveData(0f)
    val contactDistance: LiveData<Float>
        get() = mutableContactDistance

    // Shows the bearing to the selected contact
    private val mutableContactBearing = MutableLiveData(0f)
    val contactBearing: LiveData<Float>
        get() = mutableContactBearing

    // Shows the relative bearing from this device orientation to the contact
    private val mutableRelativeContactBearing = MutableLiveData(0f)
    val relativeContactBearing: LiveData<Float>
        get() = mutableRelativeContactBearing

    // Controls the current map layer style
    private val mutableMapLayerStyle = MutableLiveData<MapLayerStyle>(preferences.mapLayerStyle)
    val mapLayerStyle: LiveData<MapLayerStyle>
        get() = mutableMapLayerStyle

    // Controls the status of the MyLocation FAB on the map
    private val mutableMyLocationStatus = MutableLiveData<MyLocationStatus>(MyLocationStatus.DISABLED)
    val myLocationStatus: LiveData<MyLocationStatus>
        get() = mutableMyLocationStatus

    val currentLocation = LocationLiveData(applicationContext, viewModelScope)
    val regions = waypointsRepo.allLive
    val allContacts = contactsRepo.all

    val scope: CoroutineScope
        get() = viewModelScope

    private val mutableCurrentMonitoringMode: MutableLiveData<MonitoringMode> by lazy {
        MutableLiveData(preferences.monitoring)
    }

    val currentMonitoringMode: LiveData<MonitoringMode>
        get() = mutableCurrentMonitoringMode

    private val currentConnectionMode: MutableLiveData<ConnectionMode> by lazy {
        MutableLiveData(preferences.mode)
    }

    fun getCurrentConnectionMode(): LiveData<ConnectionMode> = currentConnectionMode

    val locationIdlingResource = SimpleIdlingResource("locationIdlingResource", false)

    val viewMode: ViewMode by locationRepo::viewMode

    fun updateMyLocationStatus() {
        mutableMyLocationStatus.postValue(
            if (requirementsChecker.isLocationPermissionCheckPassed() &&
                requirementsChecker.isLocationServiceEnabled()
            ) {
                if (viewMode == ViewMode.Device) {
                    MyLocationStatus.FOLLOWING
                } else {
                    MyLocationStatus.AVAILABLE
                }
            } else {
                MyLocationStatus.DISABLED
            }
        )
    }

    val preferenceChangeListener = object : Preferences.OnPreferenceChangeListener {
        override fun onPreferenceChanged(properties: List<String>) {
            if (properties.contains("monitoring")) {
                mutableCurrentMonitoringMode.postValue(preferences.monitoring)
            }
            if (properties.contains("mode")) {
                currentConnectionMode.postValue(preferences.mode)
                clearActiveContact()
            }
        }
    }

    init {
        preferences.registerOnPreferenceChangedListener(preferenceChangeListener)
    }

    override fun onCleared() {
        super.onCleared()
        preferences.unregisterOnPreferenceChangedListener(preferenceChangeListener)
    }

    fun onMapReady() {
        when (viewMode) {
            is ViewMode.Contact -> {
                mutableCurrentContact.value?.run { setViewModeContact(this, true) }
            }
            is ViewMode.Free -> {
                setViewModeFree()
            }
            is ViewMode.Device -> {
                setViewModeDevice()
            }
        }
    }

    fun refreshGeocodeForContact(contact: FusedContact) {
        viewModelScope.launch {
            contact.messageLocation?.run { geocoderProvider.resolve(this) }
        }
    }

    fun sendLocation() {
        currentLocation.value?.run {
            locationProcessor.onLocationChanged(this, REPORT_TYPE_USER)
        }
    }

    /**
     * User has clicked on the MyLocation FAB, so we should set the viewMode to be device.
     *
     */
    fun onMyLocationClicked() {
        setViewModeDevice()
    }

    private fun setViewModeContact(contactId: String, center: Boolean) {
        val c = contactsRepo.getById(contactId)
        if (c != null) setViewModeContact(c, center) else Timber.e(
            "contact not found %s, ",
            contactId
        )
    }

    private fun setViewModeContact(contact: FusedContact, center: Boolean) {
        Timber.d("setting view mode: VIEW_CONTACT for $contact, center=$center")
        locationRepo.viewMode = ViewMode.Contact(center)
        mutableCurrentContact.value = contact
        mutableBottomSheetHidden.value = false
        refreshGeocodeForContact(contact)
        updateActiveContactDistanceAndBearing(contact)
        if (center && contact.latLng != null) mutableMapCenter.postValue(contact.latLng)
        updateMyLocationStatus()
    }

    private fun setViewModeFree() {
        Timber.d("setting view mode: VIEW_FREE")
        locationRepo.viewMode = ViewMode.Free
        clearActiveContact()
        updateMyLocationStatus()
    }

    private fun setViewModeDevice() {
        Timber.d("setting view mode: VIEW_DEVICE")
        locationRepo.viewMode = ViewMode.Device
        clearActiveContact()
        currentLocation.value?.apply {
            mutableMapCenter.postValue(this.toLatLng())
        } ?: run {
            Timber.w("no location available")
        }
        updateMyLocationStatus()
    }

    @MainThread
    fun setLiveContact(contactId: String?) {
        contactId?.let {
            locationRepo.viewMode = ViewMode.Contact(true)
            contactsRepo.getById(it)
                ?.run(mutableCurrentContact::setValue)
        }
    }

    private fun clearActiveContact() {
        mutableCurrentContact.postValue(null)
        mutableBottomSheetHidden.postValue(true)
    }

    fun onClearContactClicked() {
        mutableCurrentContact.value?.also {
            messageProcessor.queueMessageForSending(MessageClear().apply { topic = it.id })
            contactsRepo.remove(it.id)
        }
        clearActiveContact()
    }

    fun contactPeekPopupmenuVisibility(): Boolean =
        mutableCurrentContact.value?.messageLocation != null || preferences.mode != ConnectionMode.HTTP

    fun contactHasLocation(): Boolean {
        return mutableCurrentContact.value?.messageLocation != null
    }

    private fun updateActiveContactDistanceAndBearing(contact: FusedContact) {
        currentLocation.value?.run {
            updateActiveContactDistanceAndBearing(this, contact)
        }
    }

    fun updateActiveContactDistanceAndBearing(currentLocation: Location) {
        mutableCurrentContact.value?.run {
            updateActiveContactDistanceAndBearing(currentLocation, this)
        }
    }

    private fun updateActiveContactDistanceAndBearing(
        currentLocation: Location,
        contact: FusedContact
    ) {
        contact.messageLocation?.run {
            val distanceBetween = FloatArray(2)
            Location.distanceBetween(
                currentLocation.latitude,
                currentLocation.longitude,
                latitude,
                longitude,
                distanceBetween
            )
            mutableContactDistance.postValue(distanceBetween[0])
            mutableContactBearing.postValue(distanceBetween[1])
            mutableRelativeContactBearing.postValue(distanceBetween[1])
        }
    }

    fun onMapClick() {
        setViewModeFree()
    }

    /**
     * User has clicked on a marker on the map. Should set the view mode to contact, but not center the map
     *
     * @param id identifier of the marker that was clicked on
     */
    fun onMarkerClick(id: String) {
        setViewModeContact(id, false)
    }

    /**
     * User has long clicked on the bottom contact sheet. This should trigger centering the map on the currently active contact
     */
    fun onBottomSheetLongClick() {
        mutableCurrentContact.value?.run {
            setViewModeContact(id, true)
        }
    }

    /**
     * Start requesting location updates for the blue dot
     *
     */
    fun requestLocationUpdatesForBlueDot() {
        viewModelScope.launch { currentLocation.requestLocationUpdates() }
    }

    /**
     * Moves the blue dot on the map to the given location
     *
     * @param latLng location to move the blue dot to
     */
    fun setCurrentBlueDotLocation(latLng: LatLng, isMock: Boolean) {
        if (isMock) {
            locationIdlingResource.setIdleState(true)
        }
        locationRepo.currentBlueDotOnMapLocation = latLng
    }

    /**
     * Updates the stored mapView to the given location zoom and rotation. Triggered when the map is moved from the [MapFragment]
     *
     * @param mapLocationZoomLevelAndRotation the current map location, zoom and rotation values
     */
    fun setMapLocationFromMapMoveEvent(mapLocationZoomLevelAndRotation: MapLocationZoomLevelAndRotation) {
        locationRepo.mapViewWindowLocationAndZoom = mapLocationZoomLevelAndRotation
    }

    fun initMapStartingLocation(): MapLocationZoomLevelAndRotation =
        if (viewMode == ViewMode.Contact(true) && currentContact.value?.latLng != null) {
            MapLocationZoomLevelAndRotation(
                currentContact.value!!.latLng!!,
                locationRepo.mapViewWindowLocationAndZoom?.zoom ?: STARTING_ZOOM
            )
        } else {
            locationRepo.mapViewWindowLocationAndZoom
                ?: locationRepo.currentBlueDotOnMapLocation?.let {
                    MapLocationZoomLevelAndRotation(it, STARTING_ZOOM)
                } ?: locationRepo.currentPublishedLocation.value?.let {
                MapLocationZoomLevelAndRotation(
                    it.toLatLng(),
                    STARTING_ZOOM
                )
            } ?: MapLocationZoomLevelAndRotation(
                LatLng(
                    STARTING_LATITUDE,
                    STARTING_LONGITUDE
                ),
                STARTING_ZOOM
            )
        }

    val orientationSensorEventListener = object : SensorEventListener {
        override fun onSensorChanged(maybeEvent: SensorEvent?) {
            maybeEvent?.let { event ->
                currentContact.value?.messageLocation?.let { contactLatLng ->
                    currentLocation.value?.let { currentLocation ->
                        // Orientation is angle around the Z axis
                        val azimuth = (180 / Math.PI) * 2 * asin(event.values[2])
                        val distanceBetween = FloatArray(2)
                        Location.distanceBetween(
                            currentLocation.latitude,
                            currentLocation.longitude,
                            contactLatLng.latitude,
                            contactLatLng.longitude,
                            distanceBetween
                        )
                        mutableRelativeContactBearing.postValue(distanceBetween[1] + azimuth.toFloat())
                    }
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            // noop
        }
    }

    fun setMonitoringMode(mode: MonitoringMode) {
        preferences.monitoring = mode
    }

    fun setMapLayerStyle(mapLayerStyle: MapLayerStyle) {
        preferences.mapLayerStyle = mapLayerStyle
        mutableMapLayerStyle.postValue(mapLayerStyle)
    }

    companion object {
        // Paris
        private const val STARTING_LATITUDE = 48.856826
        private const val STARTING_LONGITUDE = 2.292713
        private const val STARTING_ZOOM = 15.0
    }

    sealed class ViewMode {
        object Free : ViewMode()
        object Device : ViewMode()
        data class Contact(val follow: Boolean) : ViewMode()
    }
}
