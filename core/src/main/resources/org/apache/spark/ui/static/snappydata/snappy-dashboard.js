
function toggleCellDetails(detailsId) {

  $("#"+detailsId).toggle();

  var spanId = $("#"+detailsId+"-btn");
  if(spanId.hasClass("caret-downward")) {
    spanId.addClass("caret-upward");
    spanId.removeClass("caret-downward");
  } else {
    spanId.addClass("caret-downward");
    spanId.removeClass("caret-upward");
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

});
