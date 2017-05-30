

function createStatusBlock() {

  var avgMemoryUsage = $( "div#avgMemoryUsage" ).data( "value" );
  var avgHeapUsageGauge = $( "div#avgHeapUsage" ).data( "value" );
  var avgOffHeapUsageGauge = $( "div#avgOffHeapUsage" ).data( "value" );
  var avgJVMHeapUsageGauge = $( "div#avgJvmHeapUsage" ).data( "value" );

  var config = liquidFillGaugeDefaultSettings();
  config.circleThickness = 0.15;
  config.circleColor = "#3EC0FF";
  config.textColor = "#3EC0FF";
  config.waveTextColor = "#3EC0FF";
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

  var memoryGauge = loadLiquidFillGauge("memoryUsageGauge", avgMemoryUsage, config);
  var heapGauge = loadLiquidFillGauge("heapUsageGauge", avgHeapUsageGauge, config);
  var offHeapGauge = loadLiquidFillGauge("offHeapUsageGauge", avgOffHeapUsageGauge, config);
  var jvmGauge = loadLiquidFillGauge("jvmHeapUsageGauge", avgJVMHeapUsageGauge, config);


  /* function NewValue(){
      if(Math.random() > .5){
          return Math.round(Math.random()*100);
      } else {
          return (Math.random()*100).toFixed(1);
      }
  } */
}

$(document).ready(function() {

  createStatusBlock()

  $.ajaxSetup({
      cache : false
    });

});
