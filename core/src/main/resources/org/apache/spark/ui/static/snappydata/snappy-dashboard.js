
var isMemberCellExpanded = {};


function toggleCellDetails(detailsId) {

  $("#"+detailsId).toggle();

  var spanId = $("#"+detailsId+"-btn");
  if(spanId.hasClass("caret-downward")) {
    spanId.addClass("caret-upward");
    spanId.removeClass("caret-downward");
    isMemberCellExpanded[detailsId] = true;
  } else {
    spanId.addClass("caret-downward");
    spanId.removeClass("caret-upward");
    isMemberCellExpanded[detailsId] = false;
  }
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
  var cellProps = getDetailsCellExpansionProps(row.userDir);

  var descText = row.host + " | " + row.userDir + " | " + row.processId;
  var descHtml =
          '<div style="float: left; font-weight: bold;">'
          + '<a href="/dashboard/memberDetails/?memId=' + row.id + '">'
          + descText + '</a>'
        + '</div>'
        + '<div style="width: 10px; float: right; padding-right: 10px;'
          +' cursor: pointer;" onclick="toggleCellDetails(\'' + row.userDir + '\');">'
          + '<span class="' + cellProps.caretClass + '" id="' + row.userDir + '-btn' + '"></span>'
        + '</div>'
        + '<div class="cellDetailsBox" id="' + row.userDir + '" '
          + 'style="'+ cellProps.displayStyle + '">'
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
  var cellProps = getDetailsCellExpansionProps(row.userDir + '-heap');

  var heapUsed = convertSizeToHumanReadable(row.heapMemoryUsed);
  var heapSize = convertSizeToHumanReadable(row.heapMemorySize);
  var heapHtml = heapUsed[0] + " " + heapUsed[1]
                 + " / " + heapSize[0] + " " + heapSize[1];
  var heapStorageUsed = convertSizeToHumanReadable(row.heapStoragePoolUsed);
  var heapStorageSize = convertSizeToHumanReadable(row.heapStoragePoolSize);
  var heapStorageHtml = heapStorageUsed[0] + " " + heapStorageUsed[1]
                    + " / " + heapStorageSize[0] + " " + heapStorageSize[1];
  var heapExecutionUsed = convertSizeToHumanReadable(row.heapExecutionPoolUsed);
  var heapExecutionSize = convertSizeToHumanReadable(row.heapExecutionPoolSize);
  var heapExecutionHtml = heapExecutionUsed[0] + " " + heapExecutionUsed[1]
                    + " / " + heapExecutionSize[0] + " " + heapExecutionSize[1];
  var jvmHeapUsed = convertSizeToHumanReadable(row.usedMemory);
  var jvmHeapSize = convertSizeToHumanReadable(row.totalMemory);
  var jvmHeapHtml = jvmHeapUsed[0] + " " + jvmHeapUsed[1]
                    + " / " + jvmHeapSize[0] + " " + jvmHeapSize[1];

  var heapCellHtml =
          '<div style="width: 80%; float: left; padding-right:10px;'
           + 'text-align:right;">' + heapHtml
        + '</div>'
        + '<div style="width: 5px; float: right; padding-right: 10px; '
           + 'cursor: pointer;" '
           + 'onclick="toggleCellDetails(\'' + row.userDir + '-heap' + '\');">'
           + '<span class="' + cellProps.caretClass + '" '
           + 'id="' + row.userDir + '-heap-btn"></span>'
        + '</div>'
        + '<div class="cellDetailsBox" id="'+ row.userDir + '-heap" '
           + 'style="width: 90%; ' + cellProps.displayStyle + '">'
           + '<span><strong>JVM Heap:</strong>'
           + '<br>' + jvmHeapHtml
           + '<span><strong>Storage Memory:</strong>'
           + '<br>' + heapStorageHtml
           + '<br><strong>Execution Memory:</strong>'
           + '<br>' + heapExecutionHtml
           + '</span>'
        + '</div>';
  return heapCellHtml;
}

// Content to be displayed in off-heap memory cell in Members Stats Grid
function generateOffHeapCellHtml(row){
  var cellProps = getDetailsCellExpansionProps(row.userDir + '-offheap');

  var offHeapUsed = convertSizeToHumanReadable(row.offHeapMemoryUsed);
  var offHeapSize = convertSizeToHumanReadable(row.offHeapMemorySize);
  var offHeapHtml = offHeapUsed[0] + " " + offHeapUsed[1]
                    + " / " + offHeapSize[0] + " " + offHeapSize[1];
  var offHeapStorageUsed = convertSizeToHumanReadable(row.offHeapStoragePoolUsed);
  var offHeapStorageSize = convertSizeToHumanReadable(row.offHeapStoragePoolSize);
  var offHeapStorageHtml = offHeapStorageUsed[0] + " " + offHeapStorageUsed[1]
                    + " / " + offHeapStorageSize[0] + " " + offHeapStorageSize[1];
  var offHeapExecutionUsed = convertSizeToHumanReadable(row.offHeapExecutionPoolUsed);
  var offHeapExecutionSize = convertSizeToHumanReadable(row.offHeapExecutionPoolSize);
  var offHeapExecutionHtml = offHeapExecutionUsed[0] + " " + offHeapExecutionUsed[1]
                    + " / " + offHeapExecutionSize[0] + " " + offHeapExecutionSize[1];

  var offHeapCellHtml =
          '<div style="width: 80%; float: left; padding-right:10px;'
           + 'text-align:right;">' + offHeapHtml
        + '</div>'
        + '<div style="width: 5px; float: right; padding-right: 10px; '
           + 'cursor: pointer;" '
           + 'onclick="toggleCellDetails(\'' + row.userDir + '-offheap' + '\');">'
           + '<span class="' + cellProps.caretClass + '" '
           + 'id="' + row.userDir + '-offheap-btn"></span>'
        + '</div>'
        + '<div class="cellDetailsBox" id="'+ row.userDir + '-offheap" '
           + 'style="width: 90%; ' + cellProps.displayStyle + '">'
           + '<span><strong>Storage Memory:</strong>'
           + '<br>' + offHeapStorageHtml
           + '<br><strong>Execution Memory:</strong>'
           + '<br>' + offHeapExecutionHtml
           + '</span>'
        + '</div>';
  return offHeapCellHtml;
}

function createStatusBlock() {

  var cpuUsage = $( "div#cpuUsage" ).data( "value" );
  var memoryUsage = $( "div#memoryUsage" ).data( "value" );
  // var heapUsageGauge = $( "div#heapUsage" ).data( "value" );
  // var offHeapUsageGauge = $( "div#offHeapUsage" ).data( "value" );
  var jvmHeapUsageGauge = $( "div#jvmHeapUsage" ).data( "value" );

  var config = liquidFillGaugeDefaultSettings();
  config.circleThickness = 0.15;
  config.circleColor = "#3EC0FF";
  config.textColor = "#3EC0FF";
  config.waveTextColor = "#00B0FF";
  config.waveColor = "#A0DFFF";
  config.textVertPosition = 0.8;
  config.waveAnimateTime = 1000;
  config.waveHeight = 0.05;
  config.waveAnimate = true;
  config.waveRise = false;
  config.waveHeightScaling = false;
  config.waveOffset = 0.25;
  config.textSize = 0.75;
  config.waveCount = 2;

  var cpuGauge = loadLiquidFillGauge("cpuUsageGauge", cpuUsage, config);
  var memoryGauge = loadLiquidFillGauge("memoryUsageGauge", memoryUsage, config);
  // var heapGauge = loadLiquidFillGauge("heapUsageGauge", heapUsageGauge, config);
  // var offHeapGauge = loadLiquidFillGauge("offHeapUsageGauge", offHeapUsageGauge, config);
  var jvmGauge = loadLiquidFillGauge("jvmHeapUsageGauge", jvmHeapUsageGauge, config);

}

$(document).ready(function() {

  createStatusBlock()

  $.ajaxSetup({
      cache : false
    });

  var memberStatsGridConf = {
    "ajax": {
      "url": "/snappy-api/services/allmembers",
      "dataSrc": ""
    },
    "columns": [
      { // Status
        data: function(row, type) {
                var statusImgUri = "";
                if (row.status.toUpperCase() == "RUNNING") {
                  statusImgUri = "/static/snappydata/running-status-icon-20x19.png"
                } else {
                  statusImgUri = "/static/snappydata/stopped-status-icon-20x19.png"
                }
                var statusHtml = '<div style="float: left; height: 24px; padding: 0 20px;" >'
                                  + '<img src="' + statusImgUri +'" data-toggle="tooltip" '
                                  + ' title="" data-original-title="'+ row.status +'" />'
                               + '</div>';
                return statusHtml;
              }
      },
      { // description
        data: function(row, type) {
                var descHtml = generateDescriptionCellHtml(row);
                return descHtml;
              }
      },
      { // type
        data: function(row, type) {
                var memberType = "";
                if(row.isActiveLead) {
                  memberType = '<strong data-toggle="tooltip" title="" '
                               + 'data-original-title="Active Lead">'
                               + row.memberType
                             + '</strong>';
                } else {
                  memberType = row.memberType;
                }
                return memberType;
              }
      },
      { // cpu
        data: function(row, type) {
                return row.cpuActive + " %";
              }
      },
      { // memory usage
        data: function(row, type) {
                var memoryUsage = row.usedMemory * 100 / row.totalMemory;
                return memoryUsage.toFixed(2) + " %";
              }
      },
      { // heap usage
        data: function(row, type) {
                return generateHeapCellHtml(row);
              }
      },
      { // off-heap usage
        data: function(row, type) {
                return generateOffHeapCellHtml(row);
              }
      }
    ]
  }

  var membersStatsGrid = $('#memberStatsGrid').DataTable( memberStatsGridConf );


  var x = setInterval(function() {
    /*
    // todo: need to provision when to stop and start update feature
    if (i > 5) {
      clearInterval(x);
    }
    */
    $('#memberStatsGrid').DataTable().ajax.reload();
  }, 5000);

});
