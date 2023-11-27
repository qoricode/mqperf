package com.softwaremill.mqperf.config

import com.amazonaws.auth._
import com.amazonaws.regions.Regions

object AWS {
  val DefaultRegion: String = sys.env.getOrElse("AWS_REGION", Regions.EU_WEST_1.getName)
  val CredentialProvider: AWSCredentialsProvider = new EnvironmentVariableCredentialsProvider()
}
