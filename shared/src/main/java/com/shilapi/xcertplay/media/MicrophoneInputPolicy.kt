package com.shilapi.xcertplay.media

/** 只用于已经获准的上行流；导航播放本身不会据此打开麦克风。 */
internal object MicrophoneInputPolicy {
    fun communication(audioType: String, appCommunication: Boolean): Boolean = when (audioType) {
        "telephony", "facetime" -> true
        "default", "compatibility" -> appCommunication
        else -> false
    }

    fun channel(audioType: String): AudioChannel =
        if (audioType == "speechrecognition") AudioChannel.ASSISTANT else AudioChannel.PHONE
}
