

var baseParams;

var curLogLength;
var startByte;
var endByte;
var totalLogLength;

var byteLength;

function setLogScroll(oldHeight) {
  var logContent = $(".log-content");
  logContent.scrollTop(logContent[0].scrollHeight - oldHeight);
}

function tailLog() {
  var logContent = $(".log-content");
  logContent.scrollTop(logContent[0].scrollHeight);
}

function setLogData() {
  $('#log-data').html("Showing " + curLogLength + " Bytes: " + startByte
    + " - " + endByte + " of " + totalLogLength);
}

function disableMoreButton() {
  var moreBtn = $(".log-more-btn");
  moreBtn.attr("disabled", "disabled");
  moreBtn.html("Top of Log");
}

function noNewAlert() {
  var alert = $(".no-new-alert");
  alert.css("display", "block");
  window.setTimeout(function () {alert.css("display", "none");}, 4000);
}

function loadMore() {
  var offset = Math.max(startByte - byteLength, 0);
  var moreByteLength = Math.min(byteLength, startByte);

  $.ajax({
    type: "GET",
    url: "/dashboard/memberDetails/log" + baseParams + "&offset=" + offset + "&byteLength=" + moreByteLength,
    success: function (data) {
      var oldHeight = $(".log-content")[0].scrollHeight;
      var newlineIndex = data.indexOf('\n');
      var dataInfo = data.substring(0, newlineIndex).match(/\d+/g);
      var retStartByte = dataInfo[0];
      var retLogLength = dataInfo[2];

      var cleanData = data.substring(newlineIndex + 1);
      if (retStartByte == 0) {
        disableMoreButton();
      }
      $("pre", ".log-content").prepend(cleanData);

      curLogLength = curLogLength + (startByte - retStartByte);
      startByte = retStartByte;
      totalLogLength = retLogLength;
      setLogScroll(oldHeight);
      setLogData();
    }
  });
}

function loadNew() {
  $.ajax({
    type: "GET",
    url: "/dashboard/memberDetails/log" + baseParams + "&byteLength=0",
    success: function (data) {
      var dataInfo = data.substring(0, data.indexOf('\n')).match(/\d+/g);
      var newDataLen = dataInfo[2] - totalLogLength;
      if (newDataLen != 0) {
        $.ajax({
          type: "GET",
          url: "/dashboard/memberDetails/log" + baseParams + "&byteLength=" + newDataLen,
          success: function (data) {
            var newlineIndex = data.indexOf('\n');
            var dataInfo = data.substring(0, newlineIndex).match(/\d+/g);
            var retStartByte = dataInfo[0];
            var retEndByte = dataInfo[1];
            var retLogLength = dataInfo[2];

            var cleanData = data.substring(newlineIndex + 1);
            $("pre", ".log-content").append(cleanData);

            curLogLength = curLogLength + (retEndByte - retStartByte);
            endByte = retEndByte;
            totalLogLength = retLogLength;
            tailLog();
            setLogData();
          }
        });
      } else {
        noNewAlert();
      }
    }
  });
}

function initLogPage(params, logLen, start, end, totLogLen, defaultLen) {
  baseParams = params;
  curLogLength = logLen;
  startByte = start;
  endByte = end;
  totalLogLength = totLogLen;
  byteLength = defaultLen;
  tailLog();
  if (startByte == 0) {
    disableMoreButton();
  }
}