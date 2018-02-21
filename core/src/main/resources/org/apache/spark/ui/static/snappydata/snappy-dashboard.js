
var isMemberDescrExpanded = {};


function toggleCellDetails(detailsId) {

  $("#"+detailsId).toggle();

  var spanId = $("#"+detailsId+"-btn");
  if(spanId.hasClass("caret-downward")) {
    spanId.addClass("caret-upward");
    spanId.removeClass("caret-downward");
    isMemberDescrExpanded[detailsId] = true;
  } else {
    spanId.addClass("caret-downward");
    spanId.removeClass("caret-upward");
    isMemberDescrExpanded[detailsId] = false;
  }
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
                var memberDir = row.userDir;
                var caretClass = 'caret-downward';
                var displayStyle = 'display:none;';
                if(isMemberDescrExpanded[memberDir]) {
                  caretClass = 'caret-upward';
                  displayStyle = 'display:block;';
                }

                var descText = row.host + " | " + row.userDir + " | " + row.processId;
                var descHtml =
                        '<div style="float: left; font-weight: bold;">'
                        + '<a href="/dashboard/memberDetails/?memId=' + row.id + '">'
                        + descText + '</a>'
                      + '</div>'
                      + '<div style="width: 10px; float: right; padding-right: 10px;'
                        +' cursor: pointer;" onclick="toggleCellDetails(\'' + row.userDir + '\');">'
                        + '<span class="'+ caretClass +'" id="'+ row.userDir + '-btn' + '"></span>'
                      + '</div>'
                      + '<div class="cellDetailsBox" id="' + row.userDir + '" '
                        + 'style="'+ displayStyle+ '">'
                        + '<span>'
                          + '<strong>Host:</strong>' + row.host
                          + '<br/><strong>Directory:</strong>' + row.userDirFullPath
                          + '<br/><strong>Process ID:</strong>' + row.processId
                        + '</span>'
                      + '</div>';
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
                var heapUsed = convertSizeToHumanReadable(row.heapMemoryUsed);
                var heapSize = convertSizeToHumanReadable(row.heapMemorySize);
                var heapHtml = heapUsed[0] + " " + heapUsed[1]
                                  + " / " + heapSize[0] + " " + heapSize[1];
                return heapHtml;
              }
      },
      { // off-heap usage
        data: function(row, type) {
                var offHeapUsed = convertSizeToHumanReadable(row.offHeapMemoryUsed);
                var offHeapSize = convertSizeToHumanReadable(row.offHeapMemorySize);
                var offHeapHtml = offHeapUsed[0] + " " + offHeapUsed[1]
                                  + " / " + offHeapSize[0] + " " + offHeapSize[1];
                return offHeapHtml;
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
