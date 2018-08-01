/*
 * Changes for SnappyData data platform.
 *
 * Portions Copyright (c) 2017 SnappyData, Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License. See accompanying
 * LICENSE file.
 */

package org.apache.spark.ui.dashboard

import javax.servlet.http.HttpServletRequest

import scala.xml.Node

import org.apache.spark.internal.Logging
import org.apache.spark.ui.{JettyUtils, SparkUI, SparkUITab, UIUtils, WebUIPage}

class DashboardTab(parent: SparkUI)
    extends SparkUITab(parent, "dashboard") with Logging {

  val appUIBaseAddress = parent.appUIAddress

  // var snappyApiHandler: ServletContextHandler = null
  var snappyDashboardPage: DashboardPage = null
  var snappyMemberDetailsPage: MemberDetailsPage = null

  // Attaching dashboard ui page
  val dashboardPage = new SparkDashboardPage(this)
  attachPage(dashboardPage)
  // Attaching members details page
  val memberDetailsPage = new SparkMemberDetailsPage(this)
  attachPage(memberDetailsPage)

  // create and add member logs request handler
  parent.attachHandler(JettyUtils.createServletHandler("/dashboard/memberDetails/log",
    (request: HttpServletRequest) => memberDetailsPage.renderLog(request),
    parent.securityManager,
    parent.conf))

  def getParentUI: SparkUI = {
    this.parent
  }

}

abstract class DashboardPage(parent: DashboardTab) extends WebUIPage("") {

}

private[ui] class SparkDashboardPage(parent: DashboardTab)
    extends DashboardPage(parent: DashboardTab) {
  override def render(request: HttpServletRequest): Seq[Node] = {
    if (parent.snappyDashboardPage != null) {
      parent.snappyDashboardPage.render(request)
    } else {
      val pageHeaderText = "SnappyData Dashboard"
      val pageContent =
        <h3>Dashboard is not yet available. Please wait for a moment and reload the page..</h3>

      UIUtils.headerSparkPage(pageHeaderText, pageContent, parent, Some(500),
        useDataTables = true, isSnappyPage = true)
    }
  }
}

abstract class MemberDetailsPage(parent: DashboardTab) extends WebUIPage("memberDetails") {
  def renderLog(request: HttpServletRequest): String
}


private[ui] class SparkMemberDetailsPage(parent: DashboardTab)
    extends MemberDetailsPage(parent: DashboardTab) {

  override def render(request: HttpServletRequest): Seq[Node] = {
    if (parent.snappyMemberDetailsPage != null) {
      parent.snappyMemberDetailsPage.render(request)
    } else {
      val pageHeaderText = "SnappyData Member Details"
      val pageContent =
        <h3>Member Details is not yet available. Please wait for a moment and reload the page..</h3>

      UIUtils.headerSparkPage(pageHeaderText, pageContent, parent, Some(500),
        useDataTables = true, isSnappyPage = true)
    }
  }

  def renderLog(request: HttpServletRequest): String = {
    if (parent.snappyMemberDetailsPage != null) {
      parent.snappyMemberDetailsPage.renderLog(request)
    } else {
      "Failed to fetch member logs."
    }
  }
}