package com.ecarx.autoservice.media

import android.service.notification.NotificationListenerService

/** 权限仅用于媒体会话发现，禁止处理或转发任何通知正文。 */
class MediaAccessListener : NotificationListenerService()
