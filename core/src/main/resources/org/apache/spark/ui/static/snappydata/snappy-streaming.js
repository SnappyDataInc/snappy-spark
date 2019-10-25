
function displayQueryStatistics(queryId) {
  var queryStats = {};
  if(selectedQueryUUID == "") {
    queryStats = streamingQueriesGridData[0];
  } else {
    queryStats = streamingQueriesGridData.find(obj => obj.queryUUID == queryId);
  }
  // set current selected
  selectedQueryUUID = queryStats.queryUUID;

  $("#selectedQueryName").html(queryStats.queryName);
  $("#startDateTime").html(queryStats.queryStartTimeText);
  $("#uptime").html(queryStats.queryUptimeText);
  $("#numBatchesProcessed").html(queryStats.numBatchesProcessed);
  var statusText = queryStats.isActive ? "Active" : "Inactive"
  $("#status").html(statusText);
  $("#totalInputRows").html(queryStats.totalInputRows);
  $("#totalInputRowsPerSec").html(queryStats.avgInputRowsPerSec);
  $("#totalProcessedRowsPerSec").html(queryStats.avgProcessedRowsPerSec);
  $("#totalProcessingTime").html(queryStats.totalProcessingTime);
  $("#avgProcessingTime").html(queryStats.avgProcessingTime);

  updateCharts(queryStats);

  $("#sourcesDetailsContainer").html(queryStats.sources );
  $("#sinkDetailsContainer").html(queryStats.sink );
}

function updateCharts(queryStats) {
  // Load charts library if not already loaded
  if(!isGoogleChartLoaded) {
    // Set error message
    $("#googleChartsErrorMsg").show();
    return;
  }

  var numInputRowsChartData = new google.visualization.DataTable();
  numInputRowsChartData.addColumn('datetime', 'Time of Day');
  numInputRowsChartData.addColumn('number', 'Input Records');

  var inputVsProcessedRowsChartData = new google.visualization.DataTable();
  inputVsProcessedRowsChartData.addColumn('datetime', 'Time of Day');
  inputVsProcessedRowsChartData.addColumn('number', 'Input Records Per Sec');
  inputVsProcessedRowsChartData.addColumn('number', 'Processed Records Per Sec');

  var processingTimeChartData = new google.visualization.DataTable();
  processingTimeChartData.addColumn('datetime', 'Time of Day');
  processingTimeChartData.addColumn('number', 'Processing Time');

  var timeLine = queryStats.timeLine;
  var numInputRowsTrend = queryStats.numInputRowsTrend;
  var inputRowsPerSecondTrend = queryStats.inputRowsPerSecondTrend;
  var processedRowsPerSecondTrend = queryStats.processedRowsPerSecondTrend;
  var processingTimeTrend = queryStats.processingTimeTrend;

  for(var i=0 ; i < timeLine.length ; i++) {
    var timeX = new Date(timeLine[i]);

    numInputRowsChartData.addRow([
        timeX,
        numInputRowsTrend[i]]);

    inputVsProcessedRowsChartData.addRow([
        timeX,
        inputRowsPerSecondTrend[i],
        processedRowsPerSecondTrend[i]]);

     processingTimeChartData.addRow([
        timeX,
        processingTimeTrend[i]]);
  }

  numInputRowsChartOptions = {
    title: 'Input Records',
    curveType: 'function',
    legend: { position: 'bottom' },
    colors:['#2139EC'],
    crosshair: { trigger: 'focus' },
    hAxis: {
      format: 'HH:mm'
    }
  };

  inputVsProcessedRowsChartOptions = {
    title: 'Input vs Processed Records per Sec',
    curveType: 'function',
    legend: { position: 'bottom' },
    colors:['#2139EC', '#E67E22'],
    crosshair: { trigger: 'focus' },
    hAxis: {
      format: 'HH:mm'
    }
  };

  processingTimeChartOptions = {
    title: 'Processing Time',
    curveType: 'function',
    legend: { position: 'bottom' },
    colors:['#2139EC'],
    crosshair: { trigger: 'focus' },
    hAxis: {
      format: 'HH:mm'
    }
  };

  var numInputRowsChart = new google.visualization.LineChart(
        document.getElementById('inputTrendsContainer'));
  numInputRowsChart.draw(numInputRowsChartData,
        numInputRowsChartOptions);

  var inputVsProcessedRowsChart = new google.visualization.LineChart(
        document.getElementById('processingTrendContainer'));
  inputVsProcessedRowsChart.draw(inputVsProcessedRowsChartData,
        inputVsProcessedRowsChartOptions);

  var processingTimeChart = new google.visualization.LineChart(
        document.getElementById('processingTimeContainer'));
  processingTimeChart.draw(processingTimeChartData,
        processingTimeChartOptions);

}

function getStreamingQueriesGridConf() {
  // Streaming Queries Grid Data Table Configurations
  var streamingQueriesGridConf = {
    data: streamingQueriesGridData,
    "dom": '',
    "columns": [
      { // Query Names
        data: function(row, type) {
                var qNameHtml = '<div style="width:100%; padding-left:10px;"'
                              + ' onclick="displayQueryStatistics(\''+ row.queryUUID +'\')">'
                              + row.queryName
                              + '</div>';
                return qNameHtml;
              },
        "orderable": true
      }
    ]
  }
  return streamingQueriesGridConf;
}

function loadStreamingStatsInfo() {

  if(!isGoogleChartLoaded) {
    $.ajax({
      url: "https://www.gstatic.com/charts/loader.js",
      dataType: "script",
      success: function() {
        loadGoogleCharts();
      }
    });
  }

  $.ajax({
    url:"/snappy-streaming/services/streams",
    dataType: 'json',
    // timeout: 5000,
    success: function (response, status, jqXHR) {
      // on success handler

      streamingQueriesGridData = response[0].allQueries;
      streamingQueriesGrid.clear().rows.add(streamingQueriesGridData).draw();

      // Display currently selected queries stats
      displayQueryStatistics(selectedQueryUUID);

    },
    error: ajaxRequestErrorHandler
  });
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
  loadStreamingStatsInfo();
}

var streamingQueriesGrid;
var streamingQueriesGridData = [];
var selectedQueryUUID = "";

$(document).ready(function() {

  loadGoogleCharts();

  $.ajaxSetup({
      cache : false
    });

  // Members Grid Data Table
  streamingQueriesGrid = $('#streamingQueriesGrid').DataTable( getStreamingQueriesGridConf() );

  var streamingStatsUpdateInterval = setInterval(function() {
    loadStreamingStatsInfo();
  }, 5000);



});