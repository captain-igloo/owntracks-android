package org.owntracks.android.ui.speedLimit

import android.database.SQLException
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.databinding.DataBindingUtil
import dagger.hilt.android.AndroidEntryPoint
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.io.WKTReader
import org.owntracks.android.R
import org.owntracks.android.data.SpatialiteFileDbHelper
import org.owntracks.android.databinding.UiSpeedLimitBinding
import org.owntracks.android.support.DrawerProvider
import java.io.IOException
import javax.inject.Inject

@AndroidEntryPoint
class SpeedLimitActivity: AppCompatActivity() {
    @Inject
    lateinit var drawerProvider: DrawerProvider

    private val viewModel: SpeedLimitViewModel by viewModels()

    private var lastShape: Geometry? = null

    private var currentSpeedLimit: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val dbUri = Uri.parse("android.resource://" + getPackageName() + "/" + R.raw.nslr)
        val dbHelper = SpatialiteFileDbHelper(this, dbUri, "nslr.sqlite")
        try {
            dbHelper.createDataBase(false)
        } catch (ioEx: IOException) {
            throw Error("Unable to open database", ioEx)
        } catch (sqlEx: SQLException) {
            throw sqlEx
        }

        DataBindingUtil.setContentView<UiSpeedLimitBinding>(this, R.layout.ui_speed_limit).apply {
            drawerProvider.attach(appbar.toolbar)
        }

        viewModel.currentLocation.observe(this) { location ->
            setCurrentSpeed(location)

            val point = GeometryFactory().createPoint(Coordinate(location.longitude, location.latitude))
            val localLastShape = lastShape
            if (localLastShape == null || !localLastShape.intersects(point)) {
                var speedLimitText = "OK"
                var speedLimitDescText = "Speed limit only available in New Zealand"
                val db = dbHelper.readableDatabase;
                val cursor = db.rawQuery(getQuery(location), null)
                if (cursor.count > 0) {
                    cursor.moveToFirst()
                    currentSpeedLimit = cursor.getInt(cursor.getColumnIndexOrThrow("speed_limit"))
                    speedLimitText = currentSpeedLimit.toString()
                    speedLimitDescText = cursor.getString(cursor.getColumnIndexOrThrow("description"))
                    lastShape = WKTReader().read(cursor.getString(cursor.getColumnIndexOrThrow("shape")))
                    cursor.close()
                }
                val speedLimit = findViewById<TextView>(R.id.speed_limit_text)
                speedLimit.text = speedLimitText
                // speedLimit.text = "VAR"
                val speedLimitDesc = findViewById<TextView>(R.id.speed_limit_desc)
                speedLimitDesc.text = speedLimitDescText
            }

            val policeCar = findViewById<ImageView>(R.id.police_car_image)
            val localCurrentSpeedLimit = currentSpeedLimit
            if (localCurrentSpeedLimit !== null && location.getSpeed() * 3.6 > localCurrentSpeedLimit) {
                policeCar.visibility = View.VISIBLE
            } else {
                policeCar.visibility = View.INVISIBLE
            }
        }
    }

    fun setCurrentSpeed(location: Location?) {
        val textView = findViewById<TextView>(R.id.current_speed)
        if (location == null) {
            textView.text = ""
        } else {
            textView.text = Math.round(location.getSpeed() * 3.6)
                .toString() + " km/h"
        }
    }

    fun getQuery(location: Location): String {
        return """
            SELECT
                id,
                description,
                speed_limit,
                ST_AsText(ST_Transform(shape, 4326)) AS shape
            FROM
                nslr
            WHERE
                ST_Intersects(shape, ST_Transform(ST_GeomFromText('POINT(
        """.trimIndent() + location.longitude + " " + location.latitude + ")', 4326), 2193)) LIMIT 1"
    }
}
