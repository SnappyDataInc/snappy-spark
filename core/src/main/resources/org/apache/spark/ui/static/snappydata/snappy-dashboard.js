
var isGoogleChartLoaded = false;
var isAutoUpdateTurnedON = true;
var isMemberCellExpanded = {};
var isMemberRowExpanded = {};

function setClusterStartDate() {
  var months = ['JAN', 'FEB', 'MAR', 'APR', 'MAY', 'JUN' , 'JUL', 'AUG', 'SEP', 'OCT', 'NOV', 'DEC'];

  var clusterStartTime = $("#hiddenData").data("clusterstarttime");
  var dt = new Date(clusterStartTime);
  var dd = dt.getDate();
  if ( dd < 10 ) { dd = '0' + dd; }

  var displayDateStr = months[dt.getMonth()] + ' ' + dd + ', ' + dt.getFullYear()
                     + ' ' + dt.getHours() + ':' + dt.getMinutes() + ':' + dt.getSeconds();

  $("#clusterStartDate").html(displayDateStr);
  updateClusterUptime();
}

function updateClusterUptime() {
  var clusterStartTime = $("#hiddenData").data("clusterstarttime");
  var start_date = new Date(clusterStartTime);
  var now_date = new Date();

  var seconds = Math.floor((now_date - start_date)/1000);
  var minutes = Math.floor(seconds/60);
  var hours = Math.floor(minutes/60);
  var days = Math.floor(hours/24);

  hours = hours-(days*24);
  minutes = minutes-(days*24*60)-(hours*60);
  seconds = seconds-(days*24*60*60)-(hours*60*60)-(minutes*60);

  var displayDateStr = "";
  if (days > 0) {
    if (days < 2) {
      displayDateStr += days + ' Day ';
    } else {
      displayDateStr += days + ' Days ';
    }
  }
  if (hours > 0) {
    if (hours < 2) {
      displayDateStr += hours + ' Hr ';
    } else {
      displayDateStr += hours + ' Hrs ';
    }
  }
  if (minutes > 0) {
    if (minutes > 0 && minutes < 2) {
      displayDateStr += minutes + ' Min ';
    } else {
      displayDateStr += minutes + ' Mins ';
    }
  }
  displayDateStr += seconds + ' Secs';

  $("#clusterUptime").html(displayDateStr);
}

function updateCoreDetails(coresInfo) {
  $("#totalCores").html(coresInfo.totalCores);
}

function toggleCellDetails(detailsId) {

  $("#"+detailsId).toggle();

  var spanId = $("#"+detailsId+"-btn");
  if (spanId.hasClass("caret-downward")) {
    spanId.addClass("caret-upward");
    spanId.removeClass("caret-downward");
    isMemberCellExpanded[detailsId] = true;
  } else {
    spanId.addClass("caret-downward");
    spanId.removeClass("caret-upward");
    isMemberCellExpanded[detailsId] = false;
  }
}

function toggleRowAddOnDetails(detailsId) {

  var expRowBtn = $("#"+detailsId+"-expandrow-btn");

  if (expRowBtn.hasClass('row-caret-downward')) {
    expRowBtn.removeClass('row-caret-downward');
    expRowBtn.addClass('row-caret-upward');
    isMemberRowExpanded[detailsId] = true;

    $("#" + detailsId).show();
    $("#" + detailsId + '-heap').show();
    $("#" + detailsId + '-offheap').show();
    // show sparklines
    $("#cpuUsageSLDiv-" + detailsId).show();
    $("#memoryUsageSLDiv-" + detailsId).show();

    // make sparklines visible
    $.sparkline_display_visible();

  } else {
    expRowBtn.removeClass('row-caret-upward');
    expRowBtn.addClass('row-caret-downward');
    isMemberRowExpanded[detailsId] = false;

    $("#" + detailsId).hide();
    $("#" + detailsId + '-heap').hide();
    $("#" + detailsId + '-offheap').hide();
    // hide sparklines
    $("#cpuUsageSLDiv-" + detailsId).hide();
    $("#memoryUsageSLDiv-" + detailsId).hide();
  }
}

function toggleAllRowsAddOnDetails() {
  var expandAllRowsBtn = $('#expandallrows-btn');
  var expandAction = true;
  if (expandAllRowsBtn.hasClass('row-caret-downward')) {
    expandAction = true;
    expandAllRowsBtn.removeClass('row-caret-downward');
    expandAllRowsBtn.addClass('row-caret-upward');
  } else {
    expandAction = false;
    expandAllRowsBtn.removeClass('row-caret-upward');
    expandAllRowsBtn.addClass('row-caret-downward');
  }

  for (memIndex in memberStatsGridData) {
    if (expandAction) { // expand row
      if ($('#' + memberStatsGridData[memIndex].diskStoreUUID
           + '-expandrow-btn').hasClass('row-caret-downward')) {
        toggleRowAddOnDetails(memberStatsGridData[memIndex].diskStoreUUID);
      }
    } else { // collapse row
      if ($('#' + memberStatsGridData[memIndex].diskStoreUUID
           + '-expandrow-btn').hasClass('row-caret-upward')) {
        toggleRowAddOnDetails(memberStatsGridData[memIndex].diskStoreUUID);
      }
    }
  }
}

var toggleAutoUpdateSwitch = function() {
  if ($("#myonoffswitch").prop('checked')) {
    // Turn ON auto update
    isAutoUpdateTurnedON = true;
  } else {
    // Turn OFF auto update
    isAutoUpdateTurnedON = false;
  }
}

function generateProgressBarHtml(progressValue){
  var progressBarHtml =
          '<div style="width:100%;">'
           + '<div style="float: left; width: 75%;">'
             + '<div class="progressBar">'
               + '<div class="completedProgress" style="width: '
                   + progressValue.toFixed(1) + '%;">&nbsp;</div>'
             + '</div>'
           + '</div>'
           + '<div class="progressValue"> ' + progressValue.toFixed(1) + ' %</div>'
        + '</div>';

  return progressBarHtml;
}

function getDetailsCellExpansionProps(key){
  var cellProps = {
        caretClass: 'caret-downward',
        displayStyle: 'display:none;'
      };
  if(isMemberCellExpanded[key]) {
      cellProps.caretClass = 'caret-upward';
      cellProps.displayStyle = 'display:block;';
  }
  return cellProps;
}

function generateDescriptionCellHtml(row) {
  var cellDisplayState = 'display:none;';
  if (isMemberRowExpanded[row.diskStoreUUID]) {
    cellDisplayState = 'display:block;';
  }

  var descText = row.host + " | " + row.userDir + " | " + row.processId;
  var descHtml =
          '<div style="float: left; width: 100%; font-weight: bold;">'
          + '<a href="/dashboard/memberDetails/?memId=' + row.id + '">'
          + descText + '</a>'
        + '</div>'
        + '<div class="cellDetailsBox" id="' + row.diskStoreUUID + '" '
          + 'style="'+ cellDisplayState + '">'
          + '<span>'
            + '<strong>Host:</strong>' + row.host
            + '<br/><strong>Directory:</strong>' + row.userDirFullPath
            + '<br/><strong>Process ID:</strong>' + row.processId
          + '</span>'
        + '</div>';
  return descHtml;
}

// Content to be displayed in heap memory cell in Members Stats Grid
function generateHeapCellHtml(row){
  var cellDisplayState = 'display:none;';
  if (isMemberRowExpanded[row.diskStoreUUID]) {
    cellDisplayState = 'display:block;';
  }

  var heapHtml = "NA";
  var heapStorageHtml = "NA";
  var heapExecutionHtml = "NA";

  if(row.memberType.toUpperCase() !== "LOCATOR"){
    var heapUsed = convertSizeToHumanReadable(row.heapMemoryUsed);
    var heapSize = convertSizeToHumanReadable(row.heapMemorySize);
    heapHtml = heapUsed[0] + " " + heapUsed[1]
                   + " / " + heapSize[0] + " " + heapSize[1];
    var heapStorageUsed = convertSizeToHumanReadable(row.heapStoragePoolUsed);
    var heapStorageSize = convertSizeToHumanReadable(row.heapStoragePoolSize);
    heapStorageHtml = heapStorageUsed[0] + " " + heapStorageUsed[1]
                      + " / " + heapStorageSize[0] + " " + heapStorageSize[1];
    var heapExecutionUsed = convertSizeToHumanReadable(row.heapExecutionPoolUsed);
    var heapExecutionSize = convertSizeToHumanReadable(row.heapExecutionPoolSize);
    heapExecutionHtml = heapExecutionUsed[0] + " " + heapExecutionUsed[1]
                      + " / " + heapExecutionSize[0] + " " + heapExecutionSize[1];
  }
  var jvmHeapUsed = convertSizeToHumanReadable(row.usedMemory);
  var jvmHeapSize = convertSizeToHumanReadable(row.totalMemory);
  var jvmHeapHtml = jvmHeapUsed[0] + " " + jvmHeapUsed[1]
                    + " / " + jvmHeapSize[0] + " " + jvmHeapSize[1];

  var heapCellHtml =
          '<div style="width: 95%; float: left; padding-right:10px;'
           + 'text-align:right;">' + heapHtml
        + '</div>'
        + '<div class="cellDetailsBox" id="'+ row.diskStoreUUID + '-heap" '
           + 'style="width: 90%; ' + cellDisplayState + '">'
           + '<span><strong>JVM Heap:</strong>'
           + '<br>' + jvmHeapHtml
           + '<br><strong>Storage Memory:</strong>'
           + '<br>' + heapStorageHtml
           + '<br><strong>Execution Memory:</strong>'
           + '<br>' + heapExecutionHtml
           + '</span>'
        + '</div>';
  return heapCellHtml;
}

// Content to be displayed in off-heap memory cell in Members Stats Grid
function generateOffHeapCellHtml(row){
  var cellDisplayState = 'display:none;';
  if (isMemberRowExpanded[row.diskStoreUUID]) {
    cellDisplayState = 'display:block;';
  }

  var offHeapHtml = "NA";
  var offHeapStorageHtml = "NA";
  var offHeapExecutionHtml = "NA";

  if(row.memberType.toUpperCase() !== "LOCATOR"){
    var offHeapUsed = convertSizeToHumanReadable(row.offHeapMemoryUsed);
    var offHeapSize = convertSizeToHumanReadable(row.offHeapMemorySize);
    offHeapHtml = offHeapUsed[0] + " " + offHeapUsed[1]
                      + " / " + offHeapSize[0] + " " + offHeapSize[1];
    var offHeapStorageUsed = convertSizeToHumanReadable(row.offHeapStoragePoolUsed);
    var offHeapStorageSize = convertSizeToHumanReadable(row.offHeapStoragePoolSize);
    offHeapStorageHtml = offHeapStorageUsed[0] + " " + offHeapStorageUsed[1]
                      + " / " + offHeapStorageSize[0] + " " + offHeapStorageSize[1];
    var offHeapExecutionUsed = convertSizeToHumanReadable(row.offHeapExecutionPoolUsed);
    var offHeapExecutionSize = convertSizeToHumanReadable(row.offHeapExecutionPoolSize);
    offHeapExecutionHtml = offHeapExecutionUsed[0] + " " + offHeapExecutionUsed[1]
                      + " / " + offHeapExecutionSize[0] + " " + offHeapExecutionSize[1];
  }

  var offHeapCellHtml =
          '<div style="width: 95%; float: left; padding-right:10px;'
           + 'text-align:right;">' + offHeapHtml
        + '</div>'
        + '<div class="cellDetailsBox" id="'+ row.diskStoreUUID + '-offheap" '
           + 'style="width: 90%; ' + cellDisplayState + '">'
           + '<span><strong>Storage Memory:</strong>'
           + '<br>' + offHeapStorageHtml
           + '<br><strong>Execution Memory:</strong>'
           + '<br>' + offHeapExecutionHtml
           + '</span>'
        + '</div>';
  return offHeapCellHtml;
}

function getMemberStatsGridConf() {
  // Members Grid Data Table Configurations
  var memberStatsGridConf = {
    data: memberStatsGridData,
    "lengthMenu": [[10, 25, 50, 100, -1], [10, 25, 50, 100, "All"]],
    "iDisplayLength": 50,
    "columns": [
      { // Expand/Collapse Button
        data: function(row, type) {
              var expandRowClass = 'row-caret-downward';
              if (isMemberRowExpanded[row.diskStoreUUID]) {
                expandRowClass = 'row-caret-upward';
              }
              return '<div style="padding: 0 5px; text-align: center; cursor: pointer;" ' +
                     'onclick="toggleRowAddOnDetails(\'' + row.diskStoreUUID + '\');">' +
                     '<span id="' + row.diskStoreUUID + '-expandrow-btn" ' +
                     'class="' + expandRowClass + '"></span></div>';
        },
        "orderable": false
      },
      { // Status
        data: function(row, type) {
                var statusImgUri = "";
                var statusText = "";
                if (row.status.toUpperCase() == "RUNNING") {
                  statusImgUri = "/static/snappydata/running-status-icon-20x19.png";
                  statusText = '<span style="display:none;">running</span>';
                } else {
                  statusImgUri = "/static/snappydata/stopped-status-icon-20x19.png";
                  statusText = '<span style="display:none;">stopped</span>';
                }
                var statusHtml = statusText
                                  + '<div style="float: left; height: 24px; padding: 0 20px;" >'
                                  + '<img src="' + statusImgUri +'" data-toggle="tooltip" '
                                  + ' title="" data-original-title="'+ row.status +'" />'
                               + '</div>';
                return statusHtml;
              }
      },
      { // Description
        data: function(row, type) {
                var descHtml = generateDescriptionCellHtml(row);
                return descHtml;
              }
      },
      { // Type
        data: function(row, type) {
                var memberType = "";
                if(row.isActiveLead) {
                  memberType = '<div style="text-align:center;">'
                               + '<strong data-toggle="tooltip" title="" '
                                 + 'data-original-title="Active Lead">'
                                 + row.memberType
                               + '</strong>'
                             + '</div>';
                } else {
                  memberType = '<div style="text-align:center;">' + row.memberType + '</div>';
                }
                return memberType;
              }
      },
      { // CPU Usage
        data: function(row, type) {
                var displayStatus = "display:none;";
                if ($('#'+ row.diskStoreUUID + '-expandrow-btn').hasClass('row-caret-upward') ) {
                  displayStatus =  "display:block;";
                }
                var progBarHtml = generateProgressBarHtml(row.cpuActive);
                var sparklineHtml = '<div id="cpuUsageSLDiv-' + row.diskStoreUUID + '" '
                                  + 'class="cellDetailsBox" style="' + displayStatus + '">'
                                  + '<div style="text-align: right; font-size: 12px; color: #0A8CAE;">'
                                  + 'Values in %, Last 15 mins</div>'
                                  + '<span id="cpuUsageSparklines-' + row.diskStoreUUID + '"></span></div>';
                return progBarHtml + sparklineHtml;
              }
      },
      { // Memory Usage
        data: function(row, type) {
                var totalMemorySize = row.heapMemorySize + row.offHeapMemorySize;
                var totalMemoryUsed = row.heapMemoryUsed + row.offHeapMemoryUsed;
                var memoryUsage = (totalMemoryUsed * 100) / totalMemorySize;
                if(isNaN(memoryUsage)){
                  memoryUsage = 0;
                }
                var displayStatus = "display:none;";
                if ($('#'+ row.diskStoreUUID + '-expandrow-btn').hasClass('row-caret-upward') ) {
                  displayStatus =  "display:block;";
                }
                var progBarHtml = generateProgressBarHtml(memoryUsage);
                var sparklineHtml = '<div id="memoryUsageSLDiv-' + row.diskStoreUUID + '" '
                                  + 'class="cellDetailsBox" style="' + displayStatus + '">'
                                  + '<div style="text-align: right; font-size: 12px; color: #0A8CAE;">'
                                  + 'Values in GB, Last 15 mins</div>'
                                  + '<span id="memoryUsageSparklines-' + row.diskStoreUUID + '"></span></div>';
                return  progBarHtml + sparklineHtml;
              }
      },
      { // Heap Usage
        data: function(row, type) {
                return generateHeapCellHtml(row);
              },
        "orderable": false
      },
      { // Off-Heap Usage
        data: function(row, type) {
                return generateOffHeapCellHtml(row);
              },
        "orderable": false
      }
    ],
    "order": [[3, 'desc']]
  }

  return memberStatsGridConf;
}

function getTableStatsGridConf() {
  // Tables Grid Data Table Configurations
  var tableStatsGridConf = {
    data: tableStatsGridData,
    "lengthMenu": [[10, 25, 50, 100, -1], [10, 25, 50, 100, "All"]],
    "iDisplayLength": 50,
    "columns": [
      { // Name
        data: function(row, type) {
                var nameHtml = '<div style="width:100%; padding-left:10px;">'
                               + row.tableName
                             + '</div>';
                return nameHtml;
              }
      },
      { // Storage Model
        data: function(row, type) {
                var smHtml = '<div style="width:100%; text-align:center;">'
                             + row.storageModel
                           + '</div>';
                return smHtml;
              }
      },
      { // Distribution Type
        data: function(row, type) {
                var dtHtml = '<div style="width:100%; text-align:center;">'
                             + row.distributionType
                           + '</div>';
                return dtHtml;
              }
      },
      { // Row Count
        data: function(row, type) {
                var rcHtml = '<div style="padding-right:10px; text-align:right;">'
                             + row.rowCount.toLocaleString(navigator.language)
                           + '</div>';
                return rcHtml;
              }
      },
      { // In Memory Size
        data: function(row, type) {
                var tableInMemorySize = convertSizeToHumanReadable(row.sizeInMemory);
                return tableInMemorySize[0] + ' ' + tableInMemorySize[1];
              }
      },
      { // Spillover to Disk Size
        data: function(row, type) {
                var tableSpillToDiskSize = convertSizeToHumanReadable(row.sizeSpillToDisk);
                return tableSpillToDiskSize[0] + ' ' + tableSpillToDiskSize[1];
              }
      },
      { // Total Size
        data: function(row, type) {
                var tableTotalSize = convertSizeToHumanReadable(row.totalSize);
                return tableTotalSize[0] + ' ' + tableTotalSize[1];
              }
      },
      { // Bucket Count
        data: function(row, type) {
                var bcHtml = '';
                if (row.isAnyBucketLost) {
                  bcHtml = '<div style="padding-right:10px; text-align:right; color:#ea4335;">'
                           + row.bucketCount
                         + '</div>';
                } else {
                  bcHtml = '<div style="padding-right:10px; text-align:right;">'
                           + row.bucketCount
                         + '</div>';
                }
                return bcHtml;
              }
      },
      { // Redundancy
        data: function(row, type) {
                var bcHtml = '';
                if (row.distributionType == "REPLICATE") {
                  bcHtml = '<div style="padding-right:10px; text-align:right;">'
                           + 'NA'
                         + '</div>';
                } else {
                  bcHtml = '<div style="padding-right:10px; text-align:right;">'
                           + row.redundancy
                         + '</div>';
                }
                return bcHtml;
              }
      },
      { // Redundancy Status
        data: function(row, type) {
                var bcHtml = '';
                if (row.redundancy == 0) {
                  bcHtml = '<div style="padding-right:10px; text-align:right;">'
                           + 'NA'
                         + '</div>';
                } else {
                  if (row.redundancyImpaired) {
                    bcHtml = '<div style="padding-right:10px; text-align:right; color:#ea4335;">'
                             + 'BROKEN'
                           + '</div>';
                  } else {
                    bcHtml = '<div style="padding-right:10px; text-align:right; color:#34a853;">'
                             + 'SATISFIED'
                           + '</div>';
                  }
                }
                return bcHtml;
              }
      }
    ],
    "order": [[0, 'asc']],
    columnDefs: [
      { type: 'file-size', targets: 4 },
      { type: 'file-size', targets: 5 },
      { type: 'file-size', targets: 6 }
    ]
  }

  return tableStatsGridConf;
}

function getExternalTableStatsGridConf() {
  // External Tables Grid Data Table Configurations
  var extTableStatsGridConf = {
    data: extTableStatsGridData,
    "lengthMenu": [[10, 25, 50, 100, -1], [10, 25, 50, 100, "All"]],
    "iDisplayLength": 50,
    "columns": [
      { // Name
        data: function(row, type) {
                var nameHtml = '<div style="width:100%; padding-left:10px;">'
                               + row.tableFQName
                             + '</div>';
                return nameHtml;
              }
      },
      { // Provider
        data: function(row, type) {
                var providerHtml = '<div style="width:100%; text-align:center;">'
                                   + row.provider
                                 + '</span>';
                return providerHtml;
              }
      },
      { // Source
        data: function(row, type) {
                var sourceHtml = '<div style="padding-right:10px; text-align:left;">'
                                 + row.source
                               + '</span>';
                return sourceHtml;
              }
      }
    ],
    "order": [[0, 'asc']]
  }

  return extTableStatsGridConf;
}

var globalSparklineOptions = {
      type: 'line',
      width: '200',
      height: '100',
      lineColor: '#0000ff',
      minSpotColor: '#00bf5f',
      maxSpotColor: '#ff0000',
      highlightSpotColor: '#7f007f',
      highlightLineColor: '#666666',
      spotRadius: 2.5,
      numberFormatter: function(value) {
        if ((value % 1) == 0) {
          return value;
        } else {
          return value.toFixed(3);
        }
      }
}

function updateSparklines(memberStatsGridData) {

  for (var i=0; i < memberStatsGridData.length; i++) {
    var cpuSL = $('#cpuUsageSparklines-' + memberStatsGridData[i].diskStoreUUID);
    if (cpuSL.length != 0) {
      cpuSL.sparkline(memberStatsGridData[i].cpuUsageTrend, globalSparklineOptions);
    }
    var memSL = $('#memoryUsageSparklines-' + memberStatsGridData[i].diskStoreUUID);
    if (memSL.length != 0) {
      memSL.sparkline(memberStatsGridData[i].aggrMemoryUsageTrend, globalSparklineOptions);
    }
  }
}

function updateUsageCharts(statsData){

  // Load charts library if not already loaded
  if(!isGoogleChartLoaded) {
    // Set error message
    $("#googleChartsErrorMsg").show();
    return;
  }

  var cpuChartData = new google.visualization.DataTable();
  cpuChartData.addColumn('datetime', 'Time of Day');
  cpuChartData.addColumn('number', 'CPU');

  var heapChartData = new google.visualization.DataTable();
  heapChartData.addColumn('datetime', 'Time of Day');
  heapChartData.addColumn('number', 'JVM');
  heapChartData.addColumn('number', 'Storage');
  heapChartData.addColumn('number', 'Execution');

  var offHeapChartData = new google.visualization.DataTable();
  offHeapChartData.addColumn('datetime', 'Time of Day');
  offHeapChartData.addColumn('number', 'Storage');
  offHeapChartData.addColumn('number', 'Execution');

  var diskSpaceUsageChartData = new google.visualization.DataTable();
  diskSpaceUsageChartData.addColumn('datetime', 'Time of Day');
  diskSpaceUsageChartData.addColumn('number', 'Disk');

  var timeLine = statsData.timeLine;
  var cpuUsageTrend = statsData.cpuUsageTrend;

  var jvmUsageTrend = statsData.jvmUsageTrend;
  var heapStorageUsageTrend = statsData.heapStorageUsageTrend;
  var heapExecutionUsageTrend = statsData.heapExecutionUsageTrend;

  var offHeapStorageUsageTrend = statsData.offHeapStorageUsageTrend;
  var offHeapExecutionUsageTrend = statsData.offHeapExecutionUsageTrend;

  var diskStoreDiskSpaceTrend = statsData.diskStoreDiskSpaceTrend;

  for(var i=0; i<timeLine.length; i++){
    var timeX = new Date(timeLine[i]);

    cpuChartData.addRow([timeX, cpuUsageTrend[i]]);
    heapChartData.addRow([timeX,
                          jvmUsageTrend[i],
                          heapStorageUsageTrend[i],
                          heapExecutionUsageTrend[i]]);
    offHeapChartData.addRow([timeX,
                          offHeapStorageUsageTrend[i],
                          offHeapExecutionUsageTrend[i]]);
    diskSpaceUsageChartData.addRow([timeX, diskStoreDiskSpaceTrend[i]]);
  }

  cpuChartOptions = {
    title: 'CPU Usage (%)',
    curveType: 'function',
    legend: { position: 'bottom' },
    colors:['#2139EC'],
    crosshair: { trigger: 'focus' },
    hAxis: {
      format: 'HH:mm'
    },
    vAxis: {
      minValue: 0
    }
  };
  heapChartOptions = {
    title: 'Heap Usage (GB)',
    curveType: 'function',
    legend: { position: 'bottom' },
    colors:['#6C3483', '#2139EC', '#E67E22'],
    crosshair: { trigger: 'focus' },
    hAxis: {
      format: 'HH:mm'
    }
  };
  offHeapChartOptions = {
    title: 'Off-Heap Usage (GB)',
    curveType: 'function',
    legend: { position: 'bottom' },
    colors:['#2139EC', '#E67E22'],
    crosshair: { trigger: 'focus' },
    hAxis: {
      format: 'HH:mm'
    }
  };
  diskSpaceUsageChartOptions = {
    title: 'Disk Space Usage (GB)',
    curveType: 'function',
    legend: { position: 'bottom' },
    colors:['#2139EC', '#E67E22'],
    crosshair: { trigger: 'focus' },
    hAxis: {
      format: 'HH:mm'
    }
  };

  cpuChart = new google.visualization.LineChart(
                      document.getElementById('cpuUsageContainer'));
  cpuChart.draw(cpuChartData, cpuChartOptions);

  var heapChart = new google.visualization.LineChart(
                      document.getElementById('heapUsageContainer'));
  heapChart.draw(heapChartData, heapChartOptions);

  var offHeapChart = new google.visualization.LineChart(
                      document.getElementById('offheapUsageContainer'));
  offHeapChart.draw(offHeapChartData, offHeapChartOptions);

  var diskSpaceUsageChart = new google.visualization.LineChart(
                        document.getElementById('diskSpaceUsageContainer'));
    diskSpaceUsageChart.draw(diskSpaceUsageChartData, diskSpaceUsageChartOptions);
}

function loadGoogleCharts() {

  if((typeof google === 'object' && typeof google.charts === 'object')) {
    $("#googleChartsErrorMsg").hide();
    google.charts.load('current', {'packages':['corechart']});
    google.charts.setOnLoadCallback(googleChartsLoaded);
    isGoogleChartLoaded = true;
  } else {
    $("#googleChartsErrorMsg").show();
  }

}

function googleChartsLoaded() {
  loadClusterInfo();
}

function loadClusterInfo() {

  if(!isGoogleChartLoaded) {
    $.ajax({
      url: "https://www.gstatic.com/charts/loader.js",
      dataType: "script",
      success: function() {
        loadGoogleCharts()
      }
    });
  }

  $.ajax({
    url:"/snappy-api/services/clusterinfo",
    dataType: 'json',
    // timeout: 5000,
    success: function (response, status, jqXHR) {

      // Hide error message, if displayed
      $("#AutoUpdateErrorMsg").hide();

      var clusterInfo = response[0].clusterInfo;
      updateUsageCharts(clusterInfo);

      memberStatsGridData = response[0].membersInfo;
      membersStatsGrid.clear().rows.add(memberStatsGridData).draw();
      if (membersStatsGrid.page.info().pages > membersStatsGridCurrPage) {
        membersStatsGrid.page(membersStatsGridCurrPage).draw(false);
      } else {
        membersStatsGridCurrPage = 0;
      }

      updateSparklines(memberStatsGridData);

      tableStatsGridData = response[0].tablesInfo;
      tableStatsGrid.clear().rows.add(tableStatsGridData).draw();
      if (tableStatsGrid.page.info().pages > tableStatsGridCurrPage) {
        tableStatsGrid.page(tableStatsGridCurrPage).draw(false);
      } else {
        tableStatsGridCurrPage = 0;
      }

      extTableStatsGridData = response[0].externalTablesInfo;
      extTableStatsGrid.clear().rows.add(extTableStatsGridData).draw();
      if (extTableStatsGrid.page.info().pages > extTableStatsGridCurrPage) {
        extTableStatsGrid.page(extTableStatsGridCurrPage).draw(false);
      } else {
        extTableStatsGridCurrPage = 0;
      }

      // Display External tables only if available
      if (extTableStatsGridData.length > 0) {
        $("#extTablesStatsTitle").show();
        $("#extTableStatsGridContainer").show();
      } else {
        $("#extTablesStatsTitle").hide();
        $("#extTableStatsGridContainer").hide();
      }

      updateCoreDetails(clusterInfo.coresInfo);
      updateClusterUptime();

    },
    error: ajaxRequestErrorHandler
   });
}

var memberStatsGridData = [];
var membersStatsGrid;
var membersStatsGridCurrPage = 0;

var tableStatsGridData = [];
var tableStatsGrid;
var tableStatsGridCurrPage = 0;

var extTableStatsGridData = [];
var extTableStatsGrid;
var extTableStatsGridCurrPage = 0;

$(document).ready(function() {

  loadGoogleCharts();

  $.ajaxSetup({
      cache : false
    });

  setClusterStartDate();

  $("#myonoffswitch").on( 'change', toggleAutoUpdateSwitch );

  // Members Grid Data Table
  membersStatsGrid = $('#memberStatsGrid').DataTable( getMemberStatsGridConf() );

  membersStatsGrid.on( 'page.dt', function () {
    membersStatsGridCurrPage = membersStatsGrid.page.info().page;
  });

  // Tables Grid Data Table
  tableStatsGrid = $('#tableStatsGrid').DataTable( getTableStatsGridConf() );
  tableStatsGrid.on( 'page.dt', function () {
    tableStatsGridCurrPage = tableStatsGrid.page.info().page;
  });

  // External Tables Grid Data Table
  extTableStatsGrid = $('#extTableStatsGrid').DataTable( getExternalTableStatsGridConf() );
  extTableStatsGrid.on( 'page.dt', function () {
    extTableStatsGridCurrPage = extTableStatsGrid.page.info().page;
  });

  var clusterStatsUpdateInterval = setInterval(function() {
    if(isAutoUpdateTurnedON) {
      loadClusterInfo();
    }
  }, 5000);

});
