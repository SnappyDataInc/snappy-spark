package org.apache.spark

/**
 * Created by sachin on 31/7/17.
 */
object SparkCallBackFactory {

  var snappySparkCallbackImpl: SnappySparkCallback = _

  def setSnappySparkCallback(snappySparkCallback: SnappySparkCallback): Unit = {
    snappySparkCallbackImpl = snappySparkCallback
  }

  def getSnappySparkCallback(): SnappySparkCallback = {
    snappySparkCallbackImpl
  }
}
