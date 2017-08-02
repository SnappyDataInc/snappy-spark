package org.apache.spark

/**
 * Created by sachin on 31/7/17.
 */
trait SnappySparkCallback {

  def checkCacheClosing(t: Throwable): Boolean

  def checkRuntimeOrGemfireException(t: Throwable): Boolean
}
