# Android Auto Notification Checklist

## Critical Understanding

**Android Auto (projected) does NOT use `CarMessagingService`**. That API only exists for Android Automotive OS (AAOS) apps running directly on the car's system.

For projected Android Auto (DHU, phone apps), Android Auto **filters** standard phone notifications - it does NOT replace the notification pipeline.

## Checklist - All Items Must Be True

### ✅ 1. CATEGORY_MESSAGE
**Status:** ✅ VERIFIED
- Line 603 in `EnhancedNotificationDisplay.kt`: `.setCategory(NotificationCompat.CATEGORY_MESSAGE)`

### ✅ 2. MessagingStyle
**Status:** ✅ VERIFIED
- Using `NotificationCompat.MessagingStyle` with proper Person objects
- Person objects include URIs (required for conversation recognition)

### ✅ 3. Person for Sender
**Status:** ✅ VERIFIED
- Person objects created with `setKey()`, `setName()`, `setUri()`, and `setIcon()`
- Both "me" person and message person are properly configured

### ✅ 4. Long-Lived Conversation Shortcut
**Status:** ✅ VERIFIED
- Line 1144 in `ConversationsApi.kt`: `.setLongLived(true)`
- Shortcut has conversation category: `setCategories(setOf("android.shortcut.conversation"))`
- Shortcut is linked to notification via `setShortcutInfo()` or `setShortcutId()`

### ✅ 5. IMPORTANCE_HIGH
**Status:** ✅ VERIFIED
- All conversation channels use `NotificationManager.IMPORTANCE_HIGH`
- Channels checked and updated if importance is too low

### ✅ 6. Messaging CarAppService Present
**Status:** ✅ VERIFIED
- `AndromuksCarAppService` declared in manifest
- Has messaging category: `androidx.car.app.category.MESSAGING`
- `automotive_app_desc.xml` declares `car_messaging`

### ✅ 7. Reply Action Compliant
**Status:** ✅ VERIFIED
- Uses `RemoteInput` ✅
- Targets broadcast receiver (not activity) ✅
- Has `SEMANTIC_ACTION_REPLY` ✅
- Does not start activity directly ✅

### ⚠️ 8. App Registered as Messaging in Play Console
**Status:** ⚠️ USER MUST VERIFY
- App category must be "Communication"
- Car App type must be "Messaging"
- This is required even for internal testing/DHU

## What Was Removed

- ❌ `AndromuksCarMessagingService.kt` - Deleted (does not exist in projected Android Auto)
- ❌ Bridge code calling CarMessagingService - Removed
- ❌ Manifest entry for CarMessagingService - Removed

## Current Implementation

The app now correctly uses:
- Standard `NotificationManager.notify()` for phone notifications
- Android Auto filters these notifications based on the checklist above
- All checklist items are verified in code ✅
- Only Play Console registration needs user verification ⚠️

## Next Steps

1. **Verify Play Console Settings:**
   - App category = Communication
   - Car App type = Messaging
   - This is critical - Android Auto will silently drop notifications without this

2. **Test on DHU:**
   - After Play Console verification
   - Send test message
   - Notification should appear in Android Auto

3. **If Still Not Working:**
   - Check Android Auto app settings on phone
   - Ensure app is enabled in Android Auto
   - Clear Android Auto cache
   - Restart DHU completely

## Key Insight

Android Auto does NOT consume notifications directly. It filters the standard phone notification pipeline. If all checklist items are met, Android Auto will mirror the notification. If any item is missing, Android Auto silently drops it.

