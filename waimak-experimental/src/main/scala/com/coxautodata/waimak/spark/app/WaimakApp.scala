package com.coxautodata.waimak.spark.app

import com.coxautodata.waimak.dataflow.Waimak
import com.coxautodata.waimak.dataflow.spark.SparkDataFlow
import org.apache.spark.sql.SparkSession

import scala.reflect.runtime.universe.TypeTag

/**
  * This is a [[SparkApp]] specifically for applications using Waimak
  *
  * @tparam E the type of the [[Env]] implementation (must be a case class)
  */
abstract class WaimakApp[E <: Env : TypeTag] extends SparkApp[E] {

  override protected def run(sparkSession: SparkSession, env: E): Unit = {
    val executor = Waimak.sparkExecutor()
    val emptyFlow = Waimak.sparkFlow(sparkSession, env.tmpDir)
    executor.execute(flow(emptyFlow, env))
  }

  def flow(emptyFlow: SparkDataFlow, env: E): SparkDataFlow
}
