package org.gotson.komga.infrastructure.metadata.mylar.dto

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonProperty

enum class Status {
  @JsonProperty("Ended")
  @JsonAlias(
    "ended", "ENDED",
    "completed", "Completed", "COMPLETED",
    "finished", "Finished", "FINISHED",
  )
  Ended,

  @JsonProperty("Continuing")
  @JsonAlias(
    "continuing", "CONTINUING",
    "ongoing", "Ongoing", "ONGOING",
    "releasing", "Releasing", "RELEASING",
    "current", "Current", "CURRENT",
    "not_yet_released", "NOT_YET_RELEASED",
  )
  Continuing,

  @JsonProperty("Hiatus")
  @JsonAlias("hiatus", "HIATUS", "paused", "PAUSED")
  Hiatus,

  @JsonProperty("Cancelled")
  @JsonAlias(
    "cancelled", "CANCELLED",
    "canceled", "Canceled", "CANCELED",
    "dropped", "Dropped", "DROPPED",
  )
  Cancelled,
}
