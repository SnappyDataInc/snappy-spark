
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

  var totalCoresCount = $( "div#totalCores" ).data( "value" );
  var cpuUsage = $( "div#cpuUsage" ).data( "value" );
  var memoryUsage = $( "div#memoryUsage" ).data( "value" );
  // var heapUsageGauge = $( "div#heapUsage" ).data( "value" );
  // var offHeapUsageGauge = $( "div#offHeapUsage" ).data( "value" );
  var jvmHeapUsageGauge = $( "div#jvmHeapUsage" ).data( "value" );

  var configCores = liquidFillGaugeDefaultSettings();
  configCores.circleThickness = 0.15;
  configCores.circleColor = "#3EC0FF";
  configCores.textColor = "#3EC0FF";
  configCores.textVertPosition = 0.5;
  configCores.textSize = 1.5;
  configCores.waveTextColor = "#00B0FF";
  configCores.waveColor = "#A0DFFF";
  configCores.waveAnimateTime = 1000;
  configCores.waveAnimate = false;
  configCores.waveRise = false;
  configCores.waveHeight = 0;
  configCores.waveHeightScaling = false;
  configCores.waveOffset = 0.25;
  configCores.waveCount = 2;
  configCores.displayPercent = false;
  configCores.minValue = 0;
  configCores.maxValue = (totalCoresCount * 1.3);

  var totalCoresCountGauge = loadLiquidFillGauge("totalCoresGauge", totalCoresCount, configCores);

  var config = liquidFillGaugeDefaultSettings();
  config.circleThickness = 0.15;
  config.circleColor = "#3EC0FF";
  config.textColor = "#3EC0FF";
  config.textVertPosition = 0.8;
  config.textSize = 0.75;
  config.waveTextColor = "#00B0FF";
  config.waveColor = "#A0DFFF";
  config.waveAnimateTime = 1000;
  config.waveAnimate = true;
  config.waveRise = false;
  config.waveHeight = 0.05;
  config.waveHeightScaling = false;
  config.waveOffset = 0.25;
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
