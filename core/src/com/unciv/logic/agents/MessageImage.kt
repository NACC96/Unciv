package com.unciv.logic.agents

import com.unciv.logic.IsPartOfGameInfoSerialization

/** Content-addressed metadata only. Bytes stay in the host's userdata image store. */
class DiplomaticImageAttachment(
    var attachmentId: String = "",
    var mimeType: String = "image/png",
    var width: Int = 0,
    var height: Int = 0,
    var byteLength: Int = 0
) : IsPartOfGameInfoSerialization {
    @Suppress("unused")
    constructor() : this("", "image/png", 0, 0, 0)

    fun copy() = DiplomaticImageAttachment(attachmentId, mimeType, width, height, byteLength)

    fun validate() {
        require(attachmentId.matches(Regex("[0-9a-f]{64}"))) { "Image ID must be a lowercase SHA-256" }
        require(mimeType == "image/png") { "Message images must be PNG" }
        require(width in 1..MessageImageLimits.MAX_EDGE && height in 1..MessageImageLimits.MAX_EDGE) {
            "Message image dimensions must be between 1 and 2048 pixels"
        }
        require(byteLength in 1..MessageImageLimits.MAX_BYTES) { "Message image must fit within 4 MiB" }
    }
}

/** Owns normalized bytes; callers copy before crossing an asynchronous boundary. */
class MessageImageDraft(bytes: ByteArray, val mimeType: String, val width: Int, val height: Int) {
    val bytes: ByteArray = bytes.copyOf()
    fun copy() = MessageImageDraft(bytes, mimeType, width, height)
}

interface MessageImageClipboard {
    fun hasImageContents(): Boolean
    fun readImage(onComplete: (MessageImageDraft?, String?) -> Unit)
}

object MessageImageLimits {
    const val MAX_EDGE = 2048
    const val MAX_BYTES = 4 * 1024 * 1024
    const val MAX_SOURCE_PIXELS = 32_000_000L
}
