package net.vrkknn.andromuks.car

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import net.vrkknn.andromuks.BuildConfig
import net.vrkknn.andromuks.R
import net.vrkknn.andromuks.RoomSectionType
import android.util.Log

/**
 * Android Auto landing screen with 5 main navigation buttons.
 * This is the entry point when opening the app.
 */
class CarLandingScreen(carContext: CarContext) : Screen(carContext) {
    
    companion object {
        private const val TAG = "CarLandingScreen"
        private const val ICON_SIZE = 128
    }
    
    override fun onGetTemplate(): Template {
        val exitAction = Action.Builder()
            .setTitle(carContext.getString(R.string.car_action_close))
            .setOnClickListener {
                carContext.finishCarApp()
            }
            .build()
        
        val actionStrip = ActionStrip.Builder()
            .addAction(exitAction)
            .build()
        
        val itemListBuilder = ItemList.Builder()
        
        // Home button - All Rooms
        itemListBuilder.addItem(
            Row.Builder()
                .setTitle("Home")
                .addText("All Rooms")
                .setImage(createIconForSection(RoomSectionType.HOME))
                .setOnClickListener {
                    screenManager.push(CarRoomListScreen(carContext, RoomSectionType.HOME))
                }
                .build()
        )
        
        // Spaces button - Top Level Spaces
        itemListBuilder.addItem(
            Row.Builder()
                .setTitle("Spaces")
                .addText("Top Level Spaces")
                .setImage(createIconForSection(RoomSectionType.SPACES))
                .setOnClickListener {
                    screenManager.push(CarSpacesListScreen(carContext))
                }
                .build()
        )
        
        // Direct button - Direct Message rooms
        itemListBuilder.addItem(
            Row.Builder()
                .setTitle("Direct")
                .addText("Direct Message rooms")
                .setImage(createIconForSection(RoomSectionType.DIRECT_CHATS))
                .setOnClickListener {
                    screenManager.push(CarRoomListScreen(carContext, RoomSectionType.DIRECT_CHATS))
                }
                .build()
        )
        
        // Unread button - Rooms with unread messages
        itemListBuilder.addItem(
            Row.Builder()
                .setTitle("Unread")
                .addText("Rooms with unread messages")
                .setImage(createIconForSection(RoomSectionType.UNREAD))
                .setOnClickListener {
                    screenManager.push(CarRoomListScreen(carContext, RoomSectionType.UNREAD))
                }
                .build()
        )
        
        // Favourites button - Rooms in favourite category
        itemListBuilder.addItem(
            Row.Builder()
                .setTitle("Favourites")
                .addText("Rooms in favourite category")
                .setImage(createIconForSection(RoomSectionType.FAVOURITES))
                .setOnClickListener {
                    screenManager.push(CarRoomListScreen(carContext, RoomSectionType.FAVOURITES))
                }
                .build()
        )
        
        return ListTemplate.Builder()
            .setTitle("Andromuks")
            .setHeaderAction(Action.BACK)
            .setSingleList(itemListBuilder.build())
            .setActionStrip(actionStrip)
            .build()
    }
    
    /**
     * Create a simple icon bitmap for a section type.
     * Uses emoji/unicode symbols to match the Material icons from the main app.
     */
    private fun createIconForSection(sectionType: RoomSectionType): CarIcon {
        val symbol = when (sectionType) {
            RoomSectionType.HOME -> "🏠"
            RoomSectionType.SPACES -> "📍"
            RoomSectionType.DIRECT_CHATS -> "👤"
            RoomSectionType.UNREAD -> "🔔"
            RoomSectionType.FAVOURITES -> "⭐"
            RoomSectionType.MENTIONS -> "🏷️"
        }
        
        return try {
            val bitmap = Bitmap.createBitmap(ICON_SIZE, ICON_SIZE, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            
            // Draw background circle
            val bgPaint = Paint().apply {
                color = Color.parseColor("#2196F3") // Material blue
                style = Paint.Style.FILL
                isAntiAlias = true
            }
            canvas.drawCircle(ICON_SIZE / 2f, ICON_SIZE / 2f, ICON_SIZE / 2f, bgPaint)
            
            // Draw emoji/symbol text
            val textPaint = Paint().apply {
                this.color = Color.WHITE
                textSize = ICON_SIZE * 0.5f
                textAlign = Paint.Align.CENTER
                isAntiAlias = true
                typeface = Typeface.DEFAULT
            }
            
            // Center text vertically
            val textBounds = android.graphics.Rect()
            textPaint.getTextBounds(symbol, 0, symbol.length, textBounds)
            val textY = ICON_SIZE / 2f + textBounds.height() / 2f - textBounds.bottom
            
            canvas.drawText(symbol, ICON_SIZE / 2f, textY, textPaint)
            
            val iconCompat = IconCompat.createWithBitmap(bitmap)
            CarIcon.Builder(iconCompat).build()
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                Log.e(TAG, "Error creating icon for section $sectionType", e)
            }
            // Return a default icon on error
            val bitmap = Bitmap.createBitmap(ICON_SIZE, ICON_SIZE, Bitmap.Config.ARGB_8888)
            val iconCompat = IconCompat.createWithBitmap(bitmap)
            CarIcon.Builder(iconCompat).build()
        }
    }
}
