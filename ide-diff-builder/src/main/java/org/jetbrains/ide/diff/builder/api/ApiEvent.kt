/*
 * Copyright 2000-2020 JetBrains s.r.o. and other contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
 */

package org.jetbrains.ide.diff.builder.api

import com.jetbrains.plugin.structure.intellij.version.IdeVersion
import kotlinx.serialization.*
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Base class for all events associated with API.
 */
sealed class ApiEvent {
  abstract val ideVersion: IdeVersion
}

data class IntroducedIn(override val ideVersion: IdeVersion) : ApiEvent() {
  override fun toString() = "Added in $ideVersion"
}

data class RemovedIn(override val ideVersion: IdeVersion) : ApiEvent() {
  override fun toString() = "Removed in $ideVersion"
}

data class MarkedExperimentalIn(override val ideVersion: IdeVersion) : ApiEvent() {
  override fun toString() = "Marked @ApiStatus.Experimental in $ideVersion"
}

data class UnmarkedExperimentalIn(override val ideVersion: IdeVersion) : ApiEvent() {
  override fun toString() = "Unmarked @ApiStatus.Experimental in $ideVersion"
}

data class MarkedDeprecatedIn(
  override val ideVersion: IdeVersion,
  val forRemoval: Boolean,
  val removalVersion: String?
) : ApiEvent() {
  override fun toString() = buildString {
    append("Marked deprecated")
    if (forRemoval) {
      append(" (to be removed")
      if (removalVersion != null) {
        append(" in $removalVersion)")
      } else {
        append(")")
      }
    }
    append(" in $ideVersion")
  }
}

data class UnmarkedDeprecatedIn(override val ideVersion: IdeVersion) : ApiEvent() {
  override fun toString() = "Unmarked deprecated in $ideVersion"
}

object ApiEventSerializer : KSerializer<ApiEvent> {
  override val descriptor
    get() = PrimitiveSerialDescriptor("ApiEventSerializer", PrimitiveKind.STRING)

  override fun serialize(encoder: Encoder, value: ApiEvent) {
    val qualifier = when (value) {
      is IntroducedIn -> "+"
      is RemovedIn -> "-"
      is MarkedExperimentalIn -> "E"
      is UnmarkedExperimentalIn -> "NE"
      is MarkedDeprecatedIn -> "D"
      is UnmarkedDeprecatedIn -> "ND"
    }
    val payload = when (value) {
      is MarkedDeprecatedIn -> (if (value.forRemoval) "1" else "0") + "|" + (value.removalVersion ?: "")
      else -> ""
    }
    encoder.encodeString(qualifier + ":" + payload + ":" + value.ideVersion.asString())
  }

  override fun deserialize(decoder: Decoder): ApiEvent {
    val string = decoder.decodeString()
    val qualifier = string.substringBefore(":")
    val payload = string.substringAfter(":").substringBefore(":")
    val ideVersion = IdeVersion.createIdeVersion(string.substringAfterLast(":"))
    return when (qualifier) {
      "+" -> IntroducedIn(ideVersion)
      "-" -> RemovedIn(ideVersion)
      "E" -> MarkedExperimentalIn(ideVersion)
      "NE" -> UnmarkedExperimentalIn(ideVersion)
      "D" -> {
        val forRemoval = payload.substringBefore("|") == "1"
        val removalVersion = payload.substringAfter("|").takeIf { it.isNotEmpty() }
        MarkedDeprecatedIn(ideVersion, forRemoval, removalVersion)
      }
      "ND" -> UnmarkedDeprecatedIn(ideVersion)
      else -> throw IllegalArgumentException()
    }
  }
}