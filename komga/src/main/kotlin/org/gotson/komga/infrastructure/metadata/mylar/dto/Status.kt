package org.gotson.komga.infrastructure.metadata.mylar.dto

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonProperty

enum class Status {
  @JsonProperty("Ended")
  @JsonAlias("ended", "completed", "Completed", "finished", "Finished")
  Ended,

  @JsonProperty("Continuing")
  @JsonAlias("continuing", "ongoing", "Ongoing", "releasing", "Releasing")
  Continuing,

  @JsonProperty("Hiatus")
  @JsonAlias("hiatus")
  Hiatus,

  @JsonProperty("Cancelled")
  @JsonAlias("cancelled", "canceled", "Canceled")
  Cancelled,
}
