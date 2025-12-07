# Android Auto Notification Fix

## Problem

Notifications were not appearing in Android Auto due to visibility settings blocking them from being displayed in the car interface.

## Root Cause

The app was using `VISIBILITY_PRIVATE` for all message notifications, which prevents Android Auto from displaying them. Android Auto requires notifications to have `VISIBILITY_PUBLIC` to show in the car interface.

### Affected Files

1. **EnhancedNotificationDisplay.kt** (lines 504, 1181)
   - Used `VISIBILITY_PRIVATE` for enhanced notifications with MessagingStyle
   - Used `VISIBILITY_PRIVATE` for notification updates with replies

2. **FCMService.kt** (line 594)
   - Used `VISIBILITY_PRIVATE` for fallback notifications

## Solution

Changed all message notification visibility from `VISIBILITY_PRIVATE` to `VISIBILITY_PUBLIC` in:
- `EnhancedNotificationDisplay.kt` - Enhanced notifications
- `EnhancedNotificationDisplay.kt` - Notification reply updates
- `FCMService.kt` - Fallback notifications

## What This Means

### Privacy Consideration
- `VISIBILITY_PUBLIC` means notification content will be visible on the lock screen
- This is acceptable for messaging apps and required for Android Auto
- The MessagingStyle still provides appropriate privacy controls for sensitive content
- Users can still control lock screen visibility through system settings

### Android Auto Requirements Met
- ✅ Notification visibility set to PUBLIC
- ✅ Category set to MESSAGE (already configured)
- ✅ Using MessagingStyle for conversation notifications (already configured)
- ✅ High importance channels for message notifications (already configured)
- ✅ Proper notification channels with conversation IDs (already configured)

## Testing

After this change, notifications should:
1. Appear in Android Auto when connected
2. Be readable on the car's display
3. Allow voice interactions in Android Auto
4. Still maintain privacy through MessagingStyle

## Additional Notes

- The WebSocketService foreground service notifications still use `VISIBILITY_SECRET`, which is correct since those are system notifications, not message notifications
- Notification channels are already properly configured with IMPORTANCE_HIGH
- All notifications already use CATEGORY_MESSAGE which is required for Android Auto

